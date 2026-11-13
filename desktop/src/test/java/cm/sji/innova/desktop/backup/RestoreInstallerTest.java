package cm.sji.innova.desktop.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.persistence.SqliteProductPhotoRepository;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteSaleRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.ForcedClose;
import cm.sji.innova.desktop.TestChildProcess;
import cm.sji.innova.desktop.evidence.Evidence;
import cm.sji.innova.desktop.evidence.EvidenceDecision;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.FolderMaintenanceException;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.stock.FilePendingReceiptStore;
import cm.sji.innova.desktop.stock.PendingReceipt;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03b2: running a scheduled restore at start-up, on real shops joined to their folders, and recovering
 * after a real Cella process is killed after each durable step. The shop must always come back as the whole
 * old shop or the whole new one, never a mixture, and this computer's device ID never changes.
 */
class RestoreInstallerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneId.of("Africa/Douala"));
    /** Every item a restore may move, and the device ID, which must never move. */
    private static final List<String> WATCHED = List.of("cella.db", "cella.db-wal", "cella.db-shm", "cella.db-journal",
            "shop-id.txt", "photos", "recovery-evidence", BackupService.LAST_BACKUP, "device-id.txt");

    @TempDir Path directory;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private LocalAppData template;
    private Path backup;

    /** A shop with Rice, Oil, deliveries, a sale and a photo, backed up to a "USB key", then changed: Soap added. */
    @BeforeEach
    void aShopBackedUpThenChanged() throws Exception {
        template = new LocalAppData(directory.resolve("template"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(template);
        try (DataFolderCoordinator.Membership membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(template);
            Product rice = shop.products().create(shop.shopId(), "Rice", 1_000);
            Product oil = shop.products().create(shop.shopId(), "Oil", 1_500);
            for (Product product : List.of(rice, oil)) {
                shop.stock().receive(UUID.randomUUID(), shop.shopId(), product.id(), shop.deviceId(), 10_000, "Delivery");
            }
            shop.sales().makeCashSale(UUID.randomUUID(), shop.shopId(), shop.deviceId(), "REC-1",
                    List.of(new SaleItemRequest(rice.id(), 2_000)));
            Path picture = directory.resolve("rice.png");
            ImageIO.write(new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
            new ProductPhotoService(new SqliteProductPhotoRepository(new SqliteDatabase(template.databaseFile())), template.directory())
                    .setPhoto(shop.shopId(), rice.id(), picture);
            backup = backupOf(template, coordinator, shop, directory.resolve("usb"));
            shop.products().create(shop.shopId(), "Soap", 250);   // after the backup: in the old shop only
        }
    }

    @AfterEach
    void letGoOfEverything() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) toClose.get(i).close();
    }

    // ----- A restore at start-up -----

    @Test
    void aScheduledArchiveReplacedByAnIdenticalSymbolicLinkIsRefused() throws Exception {
        Shop destination = scheduled("linked-archive", backup);
        Path archive = destination.folder().resolve("archive.zip");
        Path probe = directory.resolve("archive-link-probe");
        try { Files.createSymbolicLink(probe, backup.toAbsolutePath()); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + unavailable.getMessage());
        }
        Files.delete(probe); Files.delete(archive); Files.createSymbolicLink(archive, backup.toAbsolutePath());
        try (var membership = join(destination)) {
            assertEquals(RestoreException.Reason.STAGING_CHANGED, assertThrows(RestoreException.class,
                    () -> installer(destination.location(), destination.coordinator(), step -> { }).atStart()).reason());
        }
        assertOldShop(destination);
        assertFalse(Files.exists(destination.location().directory().resolve(RestoreJournal.FILE)));
    }

    @Test
    void aScheduledRestoreReplacesTheShopAndKeepsTheOldOneAside() throws Exception {
        Shop shop = scheduled("shop", backup);
        try (DataFolderCoordinator.Membership membership = join(shop)) {
            RestoreInstaller.Result result = installer(shop.location(), shop.coordinator(), step -> { }).atStart();

            assertEquals(RestoreInstaller.Outcome.RESTORED, result.outcome());
            assertEquals(shop.restoreId(), result.restoreId());
            assertNewShop(shop, List.of("Oil", "Rice"));
        }
        Path folder = shop.folder();
        for (String item : List.of("cella.db", "shop-id.txt", "photos", BackupService.LAST_BACKUP)) {
            assertEquals(shop.old().get(item), RestoreInstaller.fingerprint(folder.resolve("old").resolve(item)), "The old " + item + " is kept, whole");
        }
        List<Path> before = files(folder.resolve("pre-restore"));
        assertEquals(1, before.size(), "One pre-restore backup");
        assertEquals(3, BackupArchive.check(before.getFirst(), Files.createDirectories(directory.resolve("scratch"))).products(),
                "The pre-restore backup holds the old shop, with Soap");
        assertTrue(Files.readString(folder.resolve(RestoreJournal.FILE)).contains("phase=COMMITTED"), "The journal is kept with the restore");
        assertTrue(Files.exists(folder.resolve(RestorePreparation.SCHEDULED)), "The note is kept with the restore");

        try (DataFolderCoordinator.Membership membership = join(shop)) {
            AppContext restored = AppContextLoader.load(shop.location());
            assertTrue(Files.exists(backupOf(shop.location(), shop.coordinator(), restored, directory.resolve("usb-after"))),
                    "The restored shop can be backed up: nothing blocks it");
        }
    }

    @Test
    void aBackupOfAnotherShopBringsThatShopButThisComputerKeepsItsDeviceId() throws Exception {
        LocalAppData elsewhere = new LocalAppData(directory.resolve("other-shop"));
        DataFolderCoordinator theirs = DataFolderCoordinator.forFolder(elsewhere);
        Path theirBackup;
        UUID theirShop;
        try (DataFolderCoordinator.Membership membership = theirs.join()) {
            AppContext other = AppContextLoader.load(elsewhere);
            other.products().create(other.shopId(), "Beans", 800);
            theirShop = other.shopId();
            theirBackup = backupOf(elsewhere, theirs, other, directory.resolve("usb-2"));
        }
        Shop shop = scheduled("shop", theirBackup);
        assertFalse(shop.summary().sameShop());

        try (DataFolderCoordinator.Membership membership = join(shop)) {
            assertEquals(RestoreInstaller.Outcome.RESTORED, installer(shop.location(), shop.coordinator(), step -> { }).atStart().outcome());
            assertNewShop(shop, List.of("Beans"));
            AppContext restored = AppContextLoader.load(shop.location());
            assertEquals(theirShop, restored.shopId(), "The shop ID comes from the backup");
            assertEquals(shop.deviceId(), restored.deviceId(), "The device ID stays this computer's");
        }
    }

    // ----- Stopped before anything moves -----

    @Test
    void withNothingScheduledNothingHappensButAnotherOpenCellaStopsARestoreBeforeAnythingMoves() throws Exception {
        Shop idleShop = copyOfTemplate("idle");
        try (TestChildProcess idle = idleCella(idleShop.location());
             DataFolderCoordinator.Membership membership = join(idleShop)) {
            assertEquals(RestoreInstaller.Outcome.NOTHING_SCHEDULED,
                    installer(idleShop.location(), idleShop.coordinator(), step -> { }).atStart().outcome(),
                    "With nothing scheduled, start-up takes nothing, so another open Cella is no problem");
        }

        Shop shop = scheduled("shop", backup);
        try (TestChildProcess idle = idleCella(shop.location());
             DataFolderCoordinator.Membership membership = join(shop)) {
            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class,
                    () -> installer(shop.location(), shop.coordinator(), step -> { }).atStart());
            assertEquals(FolderMaintenanceException.Reason.OTHER_INSTANCE, refused.reason());
        }
        assertNothingMoved(shop);
    }

    @Test
    void aSavedRequestAnUndecidedSetAsideFileOrAFailedPreRestoreBackupStopsItBeforeAnythingMoves() throws Exception {
        Shop shop = scheduled("shop", backup);
        try (DataFolderCoordinator.Membership membership = join(shop)) {
            RestoreInstaller installer = installer(shop.location(), shop.coordinator(), step -> { });
            Path request = shop.location().directory().resolve("pending-checkout.txt");
            Files.writeString(request, "a saved sale");
            assertEquals(BackupException.Reason.REQUEST_PENDING, assertThrows(BackupException.class, installer::atStart).reason());
            Files.delete(request);
            assertNothingMoved(shop);

            Path receipts = shop.location().directory().resolve("pending-stock-receipt.properties");
            Files.writeString(receipts, "format=1\noperationId=not-a-uuid\n");
            Path setAside = new FilePendingReceiptStore(receipts).setAsideDamaged();
            assertEquals(BackupException.Reason.EVIDENCE_UNRESOLVED, assertThrows(BackupException.class, installer::atStart).reason());
            Files.delete(setAside);
            assertNothingMoved(shop);

            Path photo = files(shop.location().directory().resolve("photos")).getFirst();
            byte[] picture = Files.readAllBytes(photo);
            Files.delete(photo);
            Map<String, String> withoutPhoto = state(shop.location());
            assertEquals(BackupException.Reason.PHOTO_CHANGED, assertThrows(BackupException.class, installer::atStart).reason(),
                    "The pre-restore backup fails, so the restore does not start");
            assertEquals(withoutPhoto, state(shop.location()));
            Files.write(photo, picture);
            assertNothingMoved(shop);

            assertEquals(RestoreInstaller.Outcome.RESTORED, installer.atStart().outcome(), "Once the work is resolved, it runs");
        }
    }

    @Test
    void changedPreparedFilesOrADamagedNoteOrJournalStopItBeforeAnythingMoves() throws Exception {
        Shop changed = scheduled("changed", backup);
        Path staged = changed.folder().resolve("new");
        flipAByte(files(staged.resolve("photos")).getFirst());
        try (DataFolderCoordinator.Membership membership = join(changed)) {
            assertEquals(RestoreException.Reason.STAGING_CHANGED, assertThrows(RestoreException.class,
                    () -> installer(changed.location(), changed.coordinator(), step -> { }).atStart()).reason());
        }
        assertNothingMoved(changed);

        Shop changedCopy = scheduled("copy", backup);
        flipAByte(changedCopy.folder().resolve("archive.zip"));
        try (DataFolderCoordinator.Membership membership = join(changedCopy)) {
            assertEquals(RestoreException.Reason.STAGING_CHANGED, assertThrows(RestoreException.class,
                    () -> installer(changedCopy.location(), changedCopy.coordinator(), step -> { }).atStart()).reason());
        }
        assertNothingMoved(changedCopy);

        // Prepared by an older Cella: its files and note agree, but its database is not at this version.
        Shop older = scheduledWith("older", "PRAGMA user_version = 8");
        try (DataFolderCoordinator.Membership membership = join(older)) {
            RestoreException refused = assertThrows(RestoreException.class,
                    () -> installer(older.location(), older.coordinator(), step -> { }).atStart());
            assertEquals(RestoreException.Reason.STAGED_CHECK_FAILED, refused.reason());
            assertTrue(refused.getMessage().contains("another version"), refused.getMessage());
        }
        assertNothingMoved(older);

        Shop damagedNote = scheduled("note", backup);
        Path note = damagedNote.location().directory().resolve("restore").resolve(RestorePreparation.SCHEDULED);
        Files.writeString(note, Files.readString(note).replace("products=2", "products=3"));
        try (DataFolderCoordinator.Membership membership = join(damagedNote)) {
            assertEquals(RestoreException.Reason.NOTE_DAMAGED, assertThrows(RestoreException.class,
                    () -> installer(damagedNote.location(), damagedNote.coordinator(), step -> { }).atStart()).reason());
        }
        assertEquals(damagedNote.old(), state(damagedNote.location()));

        Shop damagedJournal = scheduled("journal", backup);
        Path journal = damagedJournal.location().directory().resolve(RestoreJournal.FILE);
        Files.writeString(journal, "format=1\nphase=PREPARED\nitem.0.name=../outside\nchecksum=0\n");
        try (DataFolderCoordinator.Membership membership = join(damagedJournal)) {
            assertEquals(RestoreException.Reason.JOURNAL_DAMAGED, assertThrows(RestoreException.class,
                    () -> installer(damagedJournal.location(), damagedJournal.coordinator(), step -> { }).atStart()).reason());
        }
        assertEquals(damagedJournal.old(), state(damagedJournal.location()), "Nothing moved");
        assertTrue(Files.exists(journal), "The journal is kept for someone to look at");

        // A whole, checksummed journal that names something a restore never moves.
        Shop odd = copyOfTemplate("odd");
        new RestoreJournal(UUID.randomUUID(), RestoreJournal.Phase.PREPARED, odd.shopId(), odd.shopId(), 0, 0, 0, "x.zip", "0".repeat(64),
                List.of(new RestoreJournal.Item("../outside", false, "0".repeat(64), null))).publish(odd.location().directory());
        try (DataFolderCoordinator.Membership membership = join(odd)) {
            RestoreException refused = assertThrows(RestoreException.class, () -> installer(odd.location(), odd.coordinator(), step -> { }).atStart());
            assertEquals(RestoreException.Reason.JOURNAL_DAMAGED, refused.reason());
            assertTrue(refused.getMessage().contains("never moves"), refused.getMessage());
        }
        assertEquals(odd.old(), state(odd.location()), "Nothing moved");
    }

    // ----- Undone, or recovered after a kill -----

    @Test
    void aFailedCheckOfTheInstalledShopPutsTheWholeOldShopBack() throws Exception {
        Shop shop = scheduled("shop", backup);
        try (DataFolderCoordinator.Membership membership = join(shop)) {
            RestoreInstaller.Result result = installer(shop.location(), shop.coordinator(), step -> {
                if (step.equals("RESTORE STEP journal NEW_INSTALLED")) {
                    flipAByte(files(shop.location().directory().resolve("photos")).getFirst());   // an installed photo is spoiled
                }
            }).atStart();

            assertEquals(RestoreInstaller.Outcome.ROLLED_BACK, result.outcome());
            assertTrue(result.detail().contains("photos"), result.detail());
            assertOldShop(shop);
        }
        assertTrue(Files.readString(shop.folder().resolve(RestoreJournal.FILE)).contains("phase=ROLLED_BACK"));

        // Files exactly as confirmed, but a database that does not hold the confirmed records.
        Shop extraSale = scheduledWith("extra-sale", "INSERT INTO sales (id, shop_id, receipt_number, device_id, payment_method,"
                + " total_amount, created_at) VALUES ('" + UUID.randomUUID() + "', '" + shop.shopId() + "', 'REC-9', '"
                + UUID.randomUUID() + "', 'cash', 0, '2026-11-12T09:00:00Z')");
        try (DataFolderCoordinator.Membership membership = join(extraSale)) {
            RestoreInstaller.Result result = installer(extraSale.location(), extraSale.coordinator(), step -> { }).atStart();
            assertEquals(RestoreInstaller.Outcome.ROLLED_BACK, result.outcome());
            assertTrue(result.detail().contains("does not hold the backup's records"), result.detail());
            assertOldShop(extraSale);
        }
    }

    /** Ends a restore at a chosen step like a crash: no code in the restore catches it. */
    private static final class StopHere extends Error { }

    @Test
    void whatCannotBeProvedWholeStopsForRepairAndKeepsEveryFile() throws Exception {
        // An old item spoiled while it was set aside: the old shop cannot be proved whole.
        Shop spoiledOld = scheduled("spoiled-old", backup);
        try (DataFolderCoordinator.Membership membership = join(spoiledOld)) {
            RestoreException repair = assertThrows(RestoreException.class, () -> installer(spoiledOld.location(), spoiledOld.coordinator(), step -> {
                if (step.equals("RESTORE STEP journal NEW_INSTALLED")) {
                    flipAByte(spoiledOld.folder().resolve("old").resolve("shop-id.txt"));
                    flipAByte(files(spoiledOld.location().directory().resolve("photos")).getFirst());
                }
            }).atStart());
            assertEquals(RestoreException.Reason.NEEDS_REPAIR, repair.reason());
        }
        assertTrue(Files.exists(spoiledOld.location().directory().resolve(RestoreJournal.FILE)), "The journal stays: the shop stays closed");

        // Committed, but the new shop was spoiled before the restore was finished.
        Shop spoiledNew = scheduled("spoiled-new", backup);
        try (DataFolderCoordinator.Membership membership = join(spoiledNew)) {
            assertThrows(StopHere.class, () -> installer(spoiledNew.location(), spoiledNew.coordinator(), step -> {
                if (step.equals("RESTORE STEP journal COMMITTED")) throw new StopHere();
            }).atStart());
            flipAByte(files(spoiledNew.location().directory().resolve("photos")).getFirst());
            assertEquals(RestoreException.Reason.NEEDS_REPAIR, assertThrows(RestoreException.class,
                    () -> installer(spoiledNew.location(), spoiledNew.coordinator(), step -> { }).atStart()).reason());
        }
        assertTrue(Files.exists(spoiledNew.location().directory().resolve(RestoreJournal.FILE)));

        // A new identity invented while the real one was set aside, as a start-up that read it too early would.
        Shop invented = scheduled("invented", backup);
        try (DataFolderCoordinator.Membership membership = join(invented)) {
            assertThrows(StopHere.class, () -> installer(invented.location(), invented.coordinator(), step -> {
                if (step.equals("RESTORE STEP journal OLD_QUARANTINED")) throw new StopHere();
            }).atStart());
            Files.writeString(invented.location().shopIdFile(), UUID.randomUUID().toString());
            assertEquals(RestoreException.Reason.NEEDS_REPAIR, assertThrows(RestoreException.class,
                    () -> installer(invented.location(), invented.coordinator(), step -> { }).atStart()).reason());
        }
        assertEquals(invented.old().get("shop-id.txt"), RestoreInstaller.fingerprint(invented.folder().resolve("old").resolve("shop-id.txt")),
                "The real identity is kept, aside");
        assertTrue(Files.exists(invented.location().directory().resolve(RestoreJournal.FILE)));
    }

    /** Another Cella starting up with a scheduled or interrupted restore, killed right after its n-th restore step. */
    public static class RestartingCella {
        public static void main(String[] args) throws Exception {
            LocalAppData location = new LocalAppData(Path.of(args[0]));
            ForcedClose.stopAfter("RESTORE STEP", Integer.parseInt(args[1]));
            DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
            coordinator.join();
            installer(location, coordinator, ForcedClose::reached).atStart();
            ForcedClose.finishedWithoutStopping();
        }
    }

    @Test
    void aCellaKilledAfterAnyStepComesBackAsTheWholeOldShopOrTheWholeNewOne() throws Exception {
        List<String> steps = new ArrayList<>();
        Shop rehearsal = scheduled("rehearsal", backup);
        try (DataFolderCoordinator.Membership membership = join(rehearsal)) {
            installer(rehearsal.location(), rehearsal.coordinator(), steps::add).atStart();
        }
        int prepared = steps.indexOf("RESTORE STEP journal PREPARED") + 1;
        int committed = steps.indexOf("RESTORE STEP journal COMMITTED") + 1;
        assertTrue(prepared > 1 && committed > prepared && steps.size() > committed, steps.toString());

        for (int kill = 1; kill <= steps.size(); kill++) {
            String when = "killed after " + steps.get(kill - 1);
            Shop shop = scheduled("kill-" + kill, backup);
            ForcedClose.runAndKill(RestartingCella.class, shop.location().directory(), false, Integer.toString(kill));
            if (Files.exists(shop.location().directory().resolve(RestoreJournal.FILE))) {
                assertEquals(RestoreException.Reason.ALREADY_SCHEDULED, assertThrows(RestoreException.class,
                        () -> new RestorePreparation(shop.location(), shop.shopId(), CLOCK).prepare(backup)).reason(),
                        "No new restore is prepared while one is unfinished: " + when);
            }

            try (DataFolderCoordinator.Membership membership = join(shop)) {
                RestoreInstaller.Result result = installer(shop.location(), shop.coordinator(), step -> { }).atStart();
                if (kill < prepared || (kill >= committed && kill < steps.size())) {
                    assertEquals(RestoreInstaller.Outcome.RESTORED, result.outcome(), when);
                    assertNewShop(shop, List.of("Oil", "Rice"));
                } else if (kill < committed) {
                    assertEquals(RestoreInstaller.Outcome.ROLLED_BACK, result.outcome(), when);
                    assertOldShop(shop);
                    assertEquals(shop.summary().stagedFiles(), RestorePreparation.fingerprint(shop.folder().resolve("new")),
                            "The prepared files are whole again for another try: " + when);
                } else {
                    assertEquals(RestoreInstaller.Outcome.NOTHING_SCHEDULED, result.outcome(), when);
                    assertNewShop(shop, List.of("Oil", "Rice"));
                }
            }
        }
    }

    @Test
    void aCellaKilledWhilePuttingTheOldShopBackIsRecoveredAgain() throws Exception {
        Shop shop = scheduled("shop", backup);
        ForcedClose.runAndKill(RestartingCella.class, shop.location().directory(), false, "7");   // in the middle of the moves
        assertTrue(Files.exists(shop.location().directory().resolve(RestoreJournal.FILE)), "A restore was interrupted");
        assertEquals(RestoreException.Reason.ALREADY_SCHEDULED, assertThrows(RestoreException.class,
                () -> new RestorePreparation(shop.location(), shop.shopId(), CLOCK).prepare(backup)).reason(),
                "No new restore is prepared while one is unfinished");

        ForcedClose.runAndKill(RestartingCella.class, shop.location().directory(), false, "2");   // in the middle of putting it back

        try (DataFolderCoordinator.Membership membership = join(shop)) {
            assertEquals(RestoreInstaller.Outcome.ROLLED_BACK, installer(shop.location(), shop.coordinator(), step -> { }).atStart().outcome());
            assertOldShop(shop);
        }
    }

    @Test
    void aSaleCommittedByACrashedCellaIsKeptWithTheOldShop() throws Exception {
        Shop shop = scheduled("shop", backup);
        ForcedClose.runAndKill(BackupServiceTest.SellingCella.class, shop.location().directory());   // REC-2, committed, then killed

        try (DataFolderCoordinator.Membership membership = join(shop)) {
            assertEquals(RestoreInstaller.Outcome.RESTORED, installer(shop.location(), shop.coordinator(), step -> { }).atStart().outcome());
            assertFalse(Files.exists(shop.location().directory().resolve("cella.db-wal")), "No old log beside the restored database");
            assertEquals(1, count(shop.location().databaseFile(), "sales"), "The backup's one sale");
        }
        Path old = Files.createDirectories(directory.resolve("old-copy"));
        for (Path file : files(shop.folder().resolve("old"))) {
            if (file.getFileName().toString().startsWith("cella.db")) Files.copy(file, old.resolve(file.getFileName()));
        }
        assertEquals(2, count(old.resolve("cella.db"), "sales"), "The crashed Cella's committed sale is kept with the old shop");
        Path before = files(shop.folder().resolve("pre-restore")).getFirst();
        assertEquals(2, BackupArchive.check(before, Files.createDirectories(directory.resolve("scratch"))).sales(),
                "and in the pre-restore backup");
    }

    @Test
    void aLogLeftBesideTheOldDatabaseGoesAsideWithItAndComesBackWithIt() throws Exception {
        // A log that SQLite could not fold into the database, beside it when the files start to move.
        byte[] log = "a log beside the old database".getBytes(StandardCharsets.UTF_8);
        Shop undone = scheduled("undone", backup);
        Path besideUndone = undone.location().directory().resolve("cella.db-wal");
        Map<String, String> withLog = new TreeMap<>();
        try (DataFolderCoordinator.Membership membership = join(undone)) {
            RestoreInstaller.Result result = installer(undone.location(), undone.coordinator(), step -> {
                if (step.equals("RESTORE STEP pre-restore backup made")) {
                    Files.write(besideUndone, log);
                    withLog.putAll(state(undone.location()));
                }
                if (step.equals("RESTORE STEP quarantined cella.db-wal")) {
                    assertArrayEquals(log, Files.readAllBytes(undone.folder().resolve("old").resolve("cella.db-wal")), "It goes aside with the database");
                }
                if (step.equals("RESTORE STEP journal NEW_INSTALLED")) {
                    Files.writeString(undone.location().shopIdFile(), UUID.randomUUID().toString());   // spoiled, so it is undone
                }
            }).atStart();
            assertEquals(RestoreInstaller.Outcome.ROLLED_BACK, result.outcome());
        }
        assertTrue(withLog.containsKey("cella.db-wal"));
        assertEquals(withLog, state(undone.location()), "The old database comes back with its log, unchanged");

        Shop restored = scheduled("restored", backup);
        Path besideRestored = restored.location().directory().resolve("cella.db-wal");
        try (DataFolderCoordinator.Membership membership = join(restored)) {
            RestoreInstaller.Result result = installer(restored.location(), restored.coordinator(), step -> {
                if (step.equals("RESTORE STEP pre-restore backup made")) Files.write(besideRestored, log);
            }).atStart();
            assertEquals(RestoreInstaller.Outcome.RESTORED, result.outcome());
            assertFalse(Files.exists(besideRestored), "The restored database has no old log beside it");
        }
        assertArrayEquals(log, Files.readAllBytes(restored.folder().resolve("old").resolve("cella.db-wal")), "The old log is kept with the old database");
    }

    @Test
    void setAsideFilesOfTheBackupStayInactiveAndTheOldShopsGoAside() throws Exception {
        LocalAppData withEvidence = new LocalAppData(directory.resolve("with-evidence"));
        copy(template.directory(), withEvidence.directory());
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(withEvidence);
        Path evidenceBackup;
        String backedUp;
        String later;
        try (DataFolderCoordinator.Membership membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(withEvidence);
            backedUp = decidedSetAsideReceipt(withEvidence, shop, "Delivery 9");
            evidenceBackup = backupOf(withEvidence, coordinator, shop, directory.resolve("usb-evidence"));
            later = decidedSetAsideReceipt(withEvidence, shop, "Delivery 10");
            RestorePreparation preparation = new RestorePreparation(withEvidence, shop.shopId(), CLOCK);
            preparation.schedule(preparation.prepare(evidenceBackup));

            assertEquals(RestoreInstaller.Outcome.RESTORED, installer(withEvidence, coordinator, step -> { }).atStart().outcome());

            assertFalse(Files.exists(withEvidence.directory().resolve(later)), "The old shop's set-aside file went aside");
            assertFalse(Files.exists(withEvidence.directory().resolve(backedUp)), "The backup's set-aside file is not active again");
            assertTrue(Files.exists(withEvidence.directory().resolve("recovery-evidence").resolve(backedUp)), "It is kept, inactive");
            AppContext restored = AppContextLoader.load(withEvidence);
            assertEquals(List.of(), evidenceFor(withEvidence, restored.shopId()).inventory(), "Nothing waits for a decision");
            assertTrue(Files.exists(backupOf(withEvidence, coordinator, restored, directory.resolve("usb-after"))));
        }
        Path folder;
        try (var listed = Files.list(withEvidence.directory().resolve("restore"))) {
            folder = listed.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        assertTrue(Files.exists(folder.resolve("old").resolve(later)), "The old shop's set-aside file is kept with it");
    }

    @Test
    void aRestoreThatRanIsNeverDiscarded() throws Exception {
        Shop shop = scheduled("shop", backup);
        try (DataFolderCoordinator.Membership membership = join(shop)) {
            installer(shop.location(), shop.coordinator(), step -> { }).atStart();
            RestorePreparation preparation = new RestorePreparation(shop.location(), shop.shopId(), CLOCK);
            assertEquals(RestoreException.Reason.ALREADY_SCHEDULED,
                    assertThrows(RestoreException.class, () -> preparation.discard(shop.restoreId())).reason());
        }
        assertTrue(Files.exists(shop.folder().resolve("old").resolve("cella.db")), "The old shop is kept");
        assertEquals(1, files(shop.folder().resolve("pre-restore")).size(), "The pre-restore backup is kept");
    }

    // ----- Helpers -----

    /** A copy of the shop with the backup prepared and scheduled, and what its items are before and after. */
    private record Shop(LocalAppData location, DataFolderCoordinator coordinator, UUID shopId, UUID deviceId,
                        RestorePreparation.Summary summary, Map<String, String> old, Map<String, String> fresh) {
        UUID restoreId() { return summary.restoreId(); }

        Path folder() { return location.directory().resolve("restore").resolve(restoreId().toString()); }
    }

    private Shop copyOfTemplate(String name) throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve(name));
        copy(template.directory(), location.directory());
        return new Shop(location, DataFolderCoordinator.forFolder(location), id(location.shopIdFile()), id(location.deviceIdFile()),
                null, state(location), Map.of());
    }

    private Shop scheduled(String name, Path chosen) throws Exception {
        Shop shop = copyOfTemplate(name);
        RestorePreparation preparation = new RestorePreparation(shop.location(), shop.shopId(), CLOCK);
        RestorePreparation.Summary summary = preparation.prepare(chosen);
        preparation.schedule(summary);
        Map<String, String> fresh = new TreeMap<>();
        Path staged = shop.location().directory().resolve("restore").resolve(summary.restoreId().toString()).resolve("new");
        for (String item : WATCHED) {
            if (Files.exists(staged.resolve(item))) fresh.put(item, RestoreInstaller.fingerprint(staged.resolve(item)));
        }
        fresh.put("device-id.txt", shop.old().get("device-id.txt"));
        return new Shop(shop.location(), shop.coordinator(), shop.shopId(), shop.deviceId(), summary, shop.old(), fresh);
    }

    /**
     * A restore prepared from the backup, then its prepared database changed by {@code sql} and scheduled with
     * fingerprints that agree, but with the counts the person saw: the note itself cannot tell.
     */
    private Shop scheduledWith(String name, String sql) throws Exception {
        Shop shop = copyOfTemplate(name);
        RestorePreparation preparation = new RestorePreparation(shop.location(), shop.shopId(), CLOCK);
        RestorePreparation.Summary prepared = preparation.prepare(backup);
        Path staged = shop.location().directory().resolve("restore").resolve(prepared.restoreId().toString()).resolve("new");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + staged.resolve("cella.db")); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
        RestorePreparation.Summary relisted = new RestorePreparation.Summary(prepared.restoreId(), prepared.archiveSha256(), prepared.shopId(),
                prepared.sameShop(), prepared.sourceDeviceId(), prepared.createdAt(), prepared.backupSchemaVersion(), prepared.products(),
                prepared.sales(), prepared.stockMovements(), prepared.photos(), prepared.evidence(), RestorePreparation.fingerprint(staged));
        preparation.schedule(relisted);
        return new Shop(shop.location(), shop.coordinator(), shop.shopId(), shop.deviceId(), relisted, shop.old(), Map.of());
    }

    private DataFolderCoordinator.Membership join(Shop shop) throws Exception {
        return shop.coordinator().join();
    }

    private static RestoreInstaller installer(LocalAppData location, DataFolderCoordinator coordinator, RestoreInstaller.Steps steps) {
        return new RestoreInstaller(location, coordinator, CLOCK, steps);
    }

    private static EvidenceService evidenceFor(LocalAppData location, UUID shopId) {
        SqliteDatabase database = new SqliteDatabase(location.databaseFile());
        return new EvidenceService(location, shopId, new StockService(new SqliteStockRepository(database)),
                new SaleService(new SqliteSaleRepository(database), new SqliteProductRepository(database)),
                new SqliteProductImportRepository(database), CLOCK);
    }

    private static Path backupOf(LocalAppData location, DataFolderCoordinator coordinator, AppContext shop, Path destination) throws Exception {
        return new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidenceFor(location, shop.shopId()), CLOCK)
                .create(destination).file();
    }

    /** A receipt from another computer, already in the history, set aside, with its decision recorded. */
    private static String decidedSetAsideReceipt(LocalAppData location, AppContext shop, String reason) throws Exception {
        UUID operation = UUID.randomUUID();
        UUID otherComputer = UUID.randomUUID();
        UUID product = shop.products().list(shop.shopId()).getFirst().id();
        shop.stock().receive(operation, shop.shopId(), product, otherComputer, 1_000, reason);
        Path receipts = location.directory().resolve("pending-stock-receipt.properties");
        new FilePendingReceiptStore(receipts).save(new PendingReceipt(operation, shop.shopId(), product, otherComputer, "piece", 1_000, reason));
        String name = new FilePendingReceiptStore(receipts).setAside(operation).getFileName().toString();
        EvidenceService evidence = evidenceFor(location, shop.shopId());
        Evidence found = evidence.inventory().stream().filter(one -> one.fileName().equals(name)).findFirst().orElseThrow();
        evidence.decide(found.fileName(), found.sha256(), "Charles", EvidenceDecision.Outcome.ALREADY_RECORDED, operation, reason + " is in the history");
        return name;
    }

    /** The fingerprint of every item a restore may move, and of the device ID, as they are now. */
    private static Map<String, String> state(LocalAppData location) throws java.io.IOException {
        Map<String, String> items = new TreeMap<>();
        for (String item : WATCHED) {
            Path path = location.directory().resolve(item);
            if (Files.exists(path)) items.put(item, RestoreInstaller.fingerprint(path));
        }
        return items;
    }

    private static void assertOldShop(Shop shop) throws Exception {
        assertEquals(shop.old(), state(shop.location()), "The whole old shop, exactly");
        assertNoRestoreLeft(shop);
        assertEquals(List.of("Oil", "Rice", "Soap"), productNames(shop.location()));
    }

    private static void assertNewShop(Shop shop, List<String> products) throws Exception {
        assertEquals(shop.fresh(), state(shop.location()), "The whole new shop, exactly, with this computer's device ID");
        assertNoRestoreLeft(shop);
        assertEquals(products, productNames(shop.location()));
    }

    private static void assertNoRestoreLeft(Shop shop) throws Exception {
        assertFalse(Files.exists(shop.location().directory().resolve(RestoreJournal.FILE)), "No restore is left unfinished");
        assertFalse(Files.exists(shop.location().directory().resolve("restore").resolve(RestorePreparation.SCHEDULED)), "Nothing is left scheduled");
    }

    private static void assertNothingMoved(Shop shop) throws Exception {
        assertEquals(shop.old(), state(shop.location()), "Nothing moved");
        assertFalse(Files.exists(shop.location().directory().resolve(RestoreJournal.FILE)), "No journal was written");
        assertFalse(Files.exists(shop.folder().resolve("old")), "Nothing was set aside");
        assertTrue(Files.exists(shop.location().directory().resolve("restore").resolve(RestorePreparation.SCHEDULED)), "It is still scheduled");
    }

    /** Product names straight from the database file, without opening the shop. */
    private static List<String> productNames(LocalAppData location) throws Exception {
        List<String> names = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + location.databaseFile()); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT name FROM products ORDER BY name")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private static long count(Path database, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private TestChildProcess idleCella(LocalAppData location) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), BackupServiceTest.IdleCella.class.getName(),
                location.directory().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        TestChildProcess child = new TestChildProcess(process);
        assertEquals("READY", child.readOutput(() -> new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8)).readLine()).get(60, TimeUnit.SECONDS));
        return child;
    }

    private static UUID id(Path file) throws Exception {
        return UUID.fromString(Files.readString(file).replace("\uFEFF", "").strip());
    }

    /** A copy of a shop's folder, without its lock files or restores. */
    private static void copy(Path from, Path to) throws Exception {
        List<Path> found;
        try (var walk = Files.walk(from)) { found = walk.toList(); }
        for (Path path : found) {
            String name = from.relativize(path).toString().replace('\\', '/');
            if (name.endsWith(".lock") || name.startsWith("restore")) continue;
            Path target = to.resolve(from.relativize(path).toString());
            if (Files.isDirectory(path)) Files.createDirectories(target);
            else Files.copy(path, target);
        }
    }

    /** Every file in {@code folder} and below, in name order. */
    private static List<Path> files(Path folder) throws java.io.IOException {
        try (var walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void flipAByte(Path file) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);
    }
}

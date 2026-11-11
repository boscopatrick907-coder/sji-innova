package cm.sji.innova.desktop.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.persistence.SqliteProductPhotoRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03b1: preparing and scheduling a restore, on a real shop joined to its folder and a real backup of it on
 * a "USB key". Every test also checks that the shop itself is not changed: only {@code restore/} is written.
 */
class RestorePreparationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneId.of("Africa/Douala"));

    @TempDir Path directory;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private LocalAppData location;
    private DataFolderCoordinator coordinator;
    private DataFolderCoordinator.Membership membership;
    private AppContext shop;
    private Product rice;
    private Path backup;

    @BeforeEach
    void aShopWithABackupOnAUsbKeyThenMoreWork() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        coordinator = DataFolderCoordinator.forFolder(location);
        membership = coordinator.join();
        shop = AppContextLoader.load(location);
        rice = shop.products().create(shop.shopId(), "Rice", 1_000);
        Product oil = shop.products().create(shop.shopId(), "Oil", 1_500);
        for (Product product : List.of(rice, oil)) {
            shop.stock().receive(UUID.randomUUID(), shop.shopId(), product.id(), shop.deviceId(), 10_000, "Delivery");
        }
        shop.sales().makeCashSale(UUID.randomUUID(), shop.shopId(), shop.deviceId(), "REC-1",
                List.of(new SaleItemRequest(rice.id(), 2_000)));
        addPhoto(location, shop, rice);
        backup = backupOf(location, coordinator, shop, directory.resolve("usb"));
        shop.products().create(shop.shopId(), "Soap", 250);   // made after the backup: in the shop, not in the backup
    }

    @AfterEach
    void letGoOfEverything() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) toClose.get(i).close();
        membership.close();
    }

    private RestorePreparation preparation() {
        return new RestorePreparation(location, shop.shopId(), CLOCK);
    }

    // ----- Preparing -----

    @Test
    void aBackupIsCopiedCheckedUnpackedAndSummarisedWithoutChangingTheShop() throws Exception {
        Map<String, String> before = theShop();

        RestorePreparation.Summary summary = preparation().prepare(backup);

        assertEquals(before, theShop(), "The shop is not changed");
        assertEquals(shop.shopId(), summary.shopId());
        assertTrue(summary.sameShop());
        assertEquals(shop.deviceId(), summary.sourceDeviceId());
        assertEquals(Instant.parse("2026-11-12T08:00:00Z"), summary.createdAt());
        assertEquals(SqliteDatabase.SCHEMA_VERSION, summary.backupSchemaVersion());
        assertEquals(2, summary.products(), "Soap was made after the backup");
        assertEquals(1, summary.sales());
        assertEquals(3, summary.stockMovements(), "Two deliveries and one sale");
        assertEquals(2, summary.photos(), "The photo and its thumbnail");
        assertEquals(0, summary.evidence());

        Path folder = restoreFolder(summary);
        assertEquals(List.of(summary.restoreId().toString()), names(restore()));
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(folder.resolve("archive.zip")), "A copy of the chosen file");
        assertEquals(sha256(Files.readAllBytes(backup)), summary.archiveSha256());
        Path staged = folder.resolve("new");
        assertEquals(RestorePreparation.fingerprint(staged), summary.stagedFiles(), "Every prepared file, with its SHA-256");
        assertTrue(summary.stagedFiles().keySet().containsAll(List.of("cella.db", "shop-id.txt")));
        assertEquals(2, summary.stagedFiles().keySet().stream().filter(name -> name.startsWith("photos/" + shop.shopId() + "/")).count());
        assertFalse(Files.exists(staged.resolve("cella.db-wal")), "One self-contained database file");
        assertEquals(List.of("Oil", "Rice"), productNames(staged.resolve("cella.db")));
        assertEquals(Optional.empty(), preparation().scheduled(), "Nothing is scheduled yet");
    }

    @Test
    void theChosenFileIsReadOnceSoReplacingItAfterwardsChangesNothing() throws Exception {
        AnotherShop other = anotherShop();
        RestorePreparation.Summary theirs = preparation().prepare(other.backup());
        assertEquals(other.shopId(), theirs.shopId());
        assertFalse(theirs.sameShop(), "A backup of another shop is said to be one");
        preparation().discard(theirs.restoreId());

        RestorePreparation.Summary ours = preparation().prepare(backup);
        Files.copy(other.backup(), backup, StandardCopyOption.REPLACE_EXISTING);   // the file on the key is swapped after the check

        preparation().schedule(ours);

        assertEquals(ours, preparation().scheduled().orElseThrow(), "The note holds what the person saw");
        assertEquals(shop.shopId().toString(), Files.readString(restoreFolder(ours).resolve("new/shop-id.txt")).strip());
        assertEquals(List.of(ours.restoreId().toString(), RestorePreparation.SCHEDULED), names(restore()));
    }

    @Test
    void aFileThatIsNotAGoodBackupIsRefusedAndLeavesNothingBehind() throws Exception {
        Map<String, String> before = theShop();
        Path notZip = Files.writeString(directory.resolve("notes.zip"), "not a backup");
        Path changed = ArchiveEdits.rewrite(backup, directory.resolve("changed.zip"), entries -> {
            byte[] id = entries.get("shop-id.txt").clone();
            id[0] ^= 1;
            entries.put("shop-id.txt", id);
        });
        Path unsafe = ArchiveEdits.rewrite(backup, directory.resolve("unsafe.zip"), entries -> entries.put("../outside.txt", new byte[]{1}));
        Path newer = ArchiveEdits.rewrite(backup, directory.resolve("newer.zip"), entries -> {
            entries.put("cella.db", ArchiveEdits.editDatabase(entries.get("cella.db"), directory, "PRAGMA user_version = 99"));
            ArchiveEdits.relist(entries, manifest -> manifest.setProperty("schemaVersion", "99"));
        });

        for (Path bad : List.of(notZip, changed, unsafe, newer)) {
            BackupException refused = assertThrows(BackupException.class, () -> preparation().prepare(bad), bad.toString());
            assertEquals(BackupException.Reason.CHECK_FAILED, refused.reason());
            assertEquals(List.of(), names(restore()), "Nothing is left from " + bad.getFileName());
        }
        assertEquals(before, theShop());
    }

    @Test
    void aBackupFromAnOlderCellaIsBroughtUpToDateInTheCopyOnly() throws Exception {
        Path older = olderBackup(false);
        Map<String, String> before = theShop();

        RestorePreparation.Summary summary = preparation().prepare(older);

        assertEquals(8, summary.backupSchemaVersion());
        Path database = restoreFolder(summary).resolve("new/cella.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                assertEquals(SqliteDatabase.SCHEMA_VERSION, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT product_reference FROM sale_items")) {
                rows.next();
                assertEquals(rice.reference(), rows.getString(1), "The old sale line took its product's reference");
            }
        }
        assertEquals(before, theShop(), "Only the copy was brought up to date");
    }

    @Test
    void anUpgradeThatFailsLeavesNothingBehindAndChangesNothing() throws Exception {
        Path older = olderBackup(true);
        Map<String, String> before = theShop();

        RestoreException refused = assertThrows(RestoreException.class, () -> preparation().prepare(older));

        assertEquals(RestoreException.Reason.UPGRADE_FAILED, refused.reason());
        assertEquals(List.of(), names(restore()));
        assertEquals(before, theShop());
    }

    @Test
    void theFinalCheckRefusesWhatAFaultyUpgradeCouldLeave() throws Exception {
        assertStagedRefused(staged -> Files.delete(staged.resolve(aPhotoIn(staged))), "a photo is missing");
        assertStagedRefused(staged -> run(staged, "PRAGMA user_version = 8"), "not up to date");
        assertStagedRefused(staged -> run(staged, aSaleOf(shop.shopId())), "does not hold the backup's records");
        // A product family of another shop: not counted and linked to nothing, so only the shop rule can tell.
        assertStagedRefused(staged -> run(staged, "INSERT INTO product_families (id, shop_id, name, created_at) VALUES ('"
                + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'Drinks', '2026-11-12T09:00:00Z')"),
                "another shop's records in [product_families]");
    }

    // ----- Scheduling -----

    @Test
    void schedulingFirstChecksThatThePreparedFilesAreExactlyTheOnesSummarised() throws Exception {
        RestorePreparation.Summary photoChanged = preparation().prepare(backup);
        String photo = photoChanged.stagedFiles().keySet().stream().filter(name -> name.startsWith("photos/")).findFirst().orElseThrow();
        flipAByte(restoreFolder(photoChanged).resolve("new").resolve(photo));
        assertStagingChanged(photoChanged);

        RestorePreparation.Summary archiveChanged = preparation().prepare(backup);
        flipAByte(restoreFolder(archiveChanged).resolve("archive.zip"));
        assertStagingChanged(archiveChanged);

        RestorePreparation.Summary fileAdded = preparation().prepare(backup);
        Files.writeString(restoreFolder(fileAdded).resolve("new/photos/added.png"), "added later");
        assertStagingChanged(fileAdded);

        preparation().schedule(preparation().prepare(backup));
        assertTrue(preparation().scheduled().isPresent(), "An untouched one is scheduled");
    }

    @Test
    void onlyOneRestoreIsScheduledAndAScheduledOneIsNeverDiscarded() throws Exception {
        RestorePreparation.Summary first = preparation().prepare(backup);
        RestorePreparation.Summary second = preparation().prepare(backup);
        preparation().schedule(first);
        byte[] note = Files.readAllBytes(restore().resolve(RestorePreparation.SCHEDULED));

        assertEquals(RestoreException.Reason.ALREADY_SCHEDULED,
                assertThrows(RestoreException.class, () -> preparation().schedule(second)).reason());
        assertEquals(RestoreException.Reason.ALREADY_SCHEDULED,
                assertThrows(RestoreException.class, () -> preparation().prepare(backup)).reason());
        assertEquals(RestoreException.Reason.ALREADY_SCHEDULED,
                assertThrows(RestoreException.class, () -> preparation().discard(first.restoreId())).reason());
        preparation().discard(second.restoreId());

        assertArrayEquals(note, Files.readAllBytes(restore().resolve(RestorePreparation.SCHEDULED)), "The note is never replaced");
        assertEquals(first, preparation().scheduled().orElseThrow());
        assertEquals(List.of(first.restoreId().toString(), RestorePreparation.SCHEDULED), names(restore()),
                "The scheduled one is kept, the other is gone, and no temporary file is left");
    }

    @Test
    void aDamagedNoteOrOneFromAnotherFolderIsReportedNeverIgnored() throws Exception {
        preparation().schedule(preparation().prepare(backup));
        Path note = restore().resolve(RestorePreparation.SCHEDULED);
        byte[] good = Files.readAllBytes(note);

        Files.writeString(note, new String(good, StandardCharsets.ISO_8859_1).replace("products=2", "products=3"), StandardCharsets.ISO_8859_1);
        assertEquals(RestoreException.Reason.NOTE_DAMAGED, assertThrows(RestoreException.class, () -> preparation().scheduled()).reason());
        Files.write(note, Arrays.copyOf(good, good.length / 2));
        assertEquals(RestoreException.Reason.NOTE_DAMAGED, assertThrows(RestoreException.class, () -> preparation().scheduled()).reason());

        Files.write(note, good);
        LocalAppData copied = new LocalAppData(directory.resolve("copied"));
        Files.createDirectories(copied.directory().resolve(RestorePreparation.RESTORE_FOLDER));
        Files.write(copied.directory().resolve(RestorePreparation.RESTORE_FOLDER).resolve(RestorePreparation.SCHEDULED), good);
        RestoreException foreign = assertThrows(RestoreException.class, () -> new RestorePreparation(copied, shop.shopId(), CLOCK).scheduled());
        assertEquals(RestoreException.Reason.NOTE_DAMAGED, foreign.reason());
        assertTrue(foreign.getMessage().contains("another data folder"), foreign.getMessage());
        assertTrue(preparation().scheduled().isPresent(), "The note reads again once it is whole");
    }

    // ----- Folder ownership and the Swing thread -----

    @Test
    void preparingAndSchedulingWaitWhileTheFolderIsHeldAndAreRefusedOnTheSwingThread() throws Exception {
        ExecutorService worker = stoppableWorkers();
        Future<RestorePreparation.Summary> prepared;
        try (DataFolderCoordinator.Maintenance maintenance = coordinator.beginMaintenance(Duration.ofSeconds(10))) {
            prepared = worker.submit(() -> preparation().prepare(backup));
            Thread.sleep(500);
            assertFalse(prepared.isDone(), "Preparing waits while a backup or restore holds the folder");
            assertFalse(Files.exists(restore()), "and writes nothing meanwhile");
        }
        RestorePreparation.Summary summary = prepared.get(30, TimeUnit.SECONDS);

        Future<?> scheduled;
        try (DataFolderCoordinator.Maintenance maintenance = coordinator.beginMaintenance(Duration.ofSeconds(10))) {
            scheduled = worker.submit(() -> { preparation().schedule(summary); return null; });
            Thread.sleep(500);
            assertFalse(scheduled.isDone(), "Scheduling waits too");
            assertFalse(Files.exists(restore().resolve(RestorePreparation.SCHEDULED)));
        }
        scheduled.get(30, TimeUnit.SECONDS);
        assertTrue(preparation().scheduled().isPresent());

        List<Throwable> onTheSwingThread = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            onTheSwingThread.add(assertThrows(IllegalStateException.class, () -> preparation().prepare(backup)));
            onTheSwingThread.add(assertThrows(IllegalStateException.class, () -> preparation().schedule(summary)));
            onTheSwingThread.add(assertThrows(IllegalStateException.class, () -> preparation().discard(summary.restoreId())));
        });
        assertEquals(3, onTheSwingThread.size());
    }

    // ----- Helpers -----

    private record AnotherShop(UUID shopId, Path backup) { }

    /** Another shop, in its own folder, with one product and its own backup on another key. */
    private AnotherShop anotherShop() throws Exception {
        LocalAppData elsewhere = new LocalAppData(directory.resolve("other"));
        DataFolderCoordinator theirCoordinator = DataFolderCoordinator.forFolder(elsewhere);
        toClose.add(theirCoordinator.join());
        AppContext other = AppContextLoader.load(elsewhere);
        other.products().create(other.shopId(), "Beans", 800);
        return new AnotherShop(other.shopId(), backupOf(elsewhere, theirCoordinator, other, directory.resolve("usb-2")));
    }

    private static Path backupOf(LocalAppData location, DataFolderCoordinator coordinator, AppContext shop, Path destination) throws Exception {
        EvidenceService evidence = new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), CLOCK);
        return new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidence, CLOCK).create(destination).file();
    }

    private void addPhoto(LocalAppData location, AppContext shop, Product product) throws Exception {
        Path picture = directory.resolve("picture.png");
        ImageIO.write(new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
        new ProductPhotoService(new SqliteProductPhotoRepository(new SqliteDatabase(location.databaseFile())), location.directory())
                .setPhoto(shop.shopId(), product.id(), picture);
    }

    /**
     * The backup as an older Cella (schema 8) made it: sale lines without their product reference. With
     * {@code upgradeFails}, a table stands in the way of the upgrade's own rebuild, so the upgrade stops.
     */
    private Path olderBackup(boolean upgradeFails) throws Exception {
        List<String> sql = new ArrayList<>(List.of(
                "CREATE TABLE sale_items_v8 (id TEXT PRIMARY KEY NOT NULL, sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,"
                        + " product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT, product_name TEXT NOT NULL, unit TEXT NOT NULL,"
                        + " quantity_milli INTEGER NOT NULL CHECK(quantity_milli > 0), unit_price INTEGER NOT NULL CHECK(unit_price >= 0),"
                        + " subtotal INTEGER NOT NULL CHECK(subtotal >= 0))",
                "INSERT INTO sale_items_v8 (rowid, id, sale_id, product_id, product_name, unit, quantity_milli, unit_price, subtotal)"
                        + " SELECT rowid, id, sale_id, product_id, product_name, unit, quantity_milli, unit_price, subtotal FROM sale_items",
                "DROP TABLE sale_items",
                "ALTER TABLE sale_items_v8 RENAME TO sale_items",
                "CREATE INDEX sale_items_sale ON sale_items(sale_id)",
                "CREATE UNIQUE INDEX sale_items_sale_product_id ON sale_items(sale_id, id, product_id)",
                "CREATE TRIGGER sale_items_no_update BEFORE UPDATE ON sale_items BEGIN SELECT RAISE(ABORT, 'Sale items are append-only'); END",
                "CREATE TRIGGER sale_items_no_delete BEFORE DELETE ON sale_items BEGIN SELECT RAISE(ABORT, 'Sale items are append-only'); END",
                "PRAGMA user_version = 8"));
        if (upgradeFails) sql.add("CREATE TABLE sale_items_v9 (in_the_way TEXT)");
        return ArchiveEdits.rewrite(backup, directory.resolve("older-" + upgradeFails + ".zip"), entries -> {
            entries.put("cella.db", ArchiveEdits.editDatabase(entries.get("cella.db"), directory, sql.toArray(String[]::new)));
            ArchiveEdits.relist(entries, manifest -> manifest.setProperty("schemaVersion", "8"));
        });
    }

    private interface Damage { void apply(Path staged) throws Exception; }

    /** The backup unpacked as a restore would, passing the final check; then {@code damage}, which it must refuse. */
    private void assertStagedRefused(Damage damage, String why) throws Exception {
        Path staged = Files.createDirectories(directory.resolve("staged-" + UUID.randomUUID()));
        BackupArchive.Contents contents = BackupArchive.extract(backup, staged);
        RestorePreparation.verify(staged, contents);

        damage.apply(staged);

        RestoreException refused = assertThrows(RestoreException.class, () -> RestorePreparation.verify(staged, contents), why);
        assertEquals(RestoreException.Reason.STAGED_CHECK_FAILED, refused.reason());
        assertTrue(refused.getMessage().contains(why), refused.getMessage());
    }

    private static Path aPhotoIn(Path staged) throws Exception {
        try (var walk = Files.walk(staged.resolve("photos"))) {
            return staged.relativize(walk.filter(Files::isRegularFile).findFirst().orElseThrow());
        }
    }

    private static String aSaleOf(UUID shopId) {
        return "INSERT INTO sales (id, shop_id, receipt_number, device_id, payment_method, total_amount, created_at) VALUES ('"
                + UUID.randomUUID() + "', '" + shopId + "', 'REC-9', '" + UUID.randomUUID() + "', 'cash', 0, '2026-11-12T09:00:00Z')";
    }

    private static void run(Path staged, String... sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + staged.resolve("cella.db")); var statement = connection.createStatement()) {
            for (String one : sql) statement.execute(one);
        }
    }

    private void assertStagingChanged(RestorePreparation.Summary summary) throws Exception {
        RestoreException refused = assertThrows(RestoreException.class, () -> preparation().schedule(summary));
        assertEquals(RestoreException.Reason.STAGING_CHANGED, refused.reason());
        assertEquals(Optional.empty(), preparation().scheduled(), "Nothing was scheduled");
    }

    /** Every file of the shop outside {@code restore/}, with its SHA-256; lock files by name only, as they are held. */
    private Map<String, String> theShop() throws Exception {
        Map<String, String> files = new TreeMap<>();
        Path root = location.directory();
        List<Path> found;
        try (var walk = Files.walk(root)) { found = walk.filter(Files::isRegularFile).toList(); }
        for (Path file : found) {
            String name = root.relativize(file).toString().replace('\\', '/');
            if (name.startsWith(RestorePreparation.RESTORE_FOLDER + "/")) continue;
            files.put(name, name.endsWith(".lock") ? "held" : sha256(Files.readAllBytes(file)));
        }
        return files;
    }

    private Path restore() {
        return location.directory().resolve(RestorePreparation.RESTORE_FOLDER);
    }

    private Path restoreFolder(RestorePreparation.Summary summary) {
        return restore().resolve(summary.restoreId().toString());
    }

    private static List<String> productNames(Path database) throws Exception {
        List<String> names = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT name FROM products ORDER BY name")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private static void flipAByte(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);
    }

    private static List<String> names(Path folder) throws Exception {
        if (!Files.exists(folder)) return new ArrayList<>();
        try (var files = Files.list(folder)) {
            return new ArrayList<>(files.map(file -> file.getFileName().toString()).sorted().toList());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private ExecutorService stoppableWorkers() {
        ExecutorService pool = Executors.newFixedThreadPool(1, task -> {
            Thread thread = new Thread(task, "restore-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        toClose.add(pool::shutdownNow);
        return pool;
    }
}

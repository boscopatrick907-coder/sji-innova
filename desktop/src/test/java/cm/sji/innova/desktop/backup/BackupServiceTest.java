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
import cm.sji.innova.core.product.photo.ProductPhoto;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.desktop.DesktopApplication;
import cm.sji.innova.desktop.ForcedClose;
import cm.sji.innova.desktop.TestChildProcess;
import cm.sji.innova.desktop.catalog.importing.PendingImportStore;
import cm.sji.innova.desktop.evidence.Evidence;
import cm.sji.innova.desktop.evidence.EvidenceDecision;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.FolderMaintenanceException;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.stock.FilePendingReceiptStore;
import cm.sji.innova.desktop.stock.PendingReceiptFileLease;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03a3: the backup, on a real shop (SQLite, a photo, sales) joined to its folder as a running Cella.
 * Each test checks what the ZIP holds, or that nothing was written when the backup must not run.
 */
class BackupServiceTest {
    /** 15:30 in Douala, where the backup's file name takes its time from. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T14:30:00Z"), ZoneId.of("Africa/Douala"));
    private static final List<String> LOCKS = List.of(".pending-stock-receipt.lock", "pending-checkout.txt.lock",
            ".csv-import.lock", DataFolderCoordinator.LOCK_FILE, ".photo-store.lock");

    @TempDir Path directory;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private LocalAppData location;
    private DataFolderCoordinator coordinator;
    private DataFolderCoordinator.Membership membership;
    private AppContext shop;
    private Product rice;
    private ProductPhoto photo;
    private Path usb;

    @BeforeEach
    void aRunningShopWithTwoProductsAPhotoAndASale() throws Exception {
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
        Path picture = directory.resolve("rice.png");
        ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
        photo = new ProductPhotoService(new SqliteProductPhotoRepository(new SqliteDatabase(location.databaseFile())),
                location.directory()).setPhoto(shop.shopId(), rice.id(), picture);
        usb = directory.resolve("usb");
    }

    @AfterEach
    void letGoOfEverything() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) toClose.get(i).close();
        if (membership != null) membership.close();
    }

    private EvidenceService evidence() {
        return new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), CLOCK);
    }

    private BackupService service() {
        return new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidence(), CLOCK);
    }

    /** A backup whose last step, the rename to its final name, is done by {@code publication}. */
    private BackupService service(BackupService.Publication publication) {
        return new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidence(), CLOCK, publication);
    }

    // ----- What a backup holds -----

    @Test
    void lastBackupMetadataWaitsForFolderMaintenance() throws Exception {
        service().create(usb);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<java.util.Optional<Properties>> reading;
            try (var maintenance = coordinator.beginMaintenance(java.time.Duration.ofSeconds(10))) {
                reading = worker.submit(() -> { started.countDown(); return service().lastBackup(); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> reading.get(200, TimeUnit.MILLISECONDS));
            }
            assertTrue(reading.get(10, TimeUnit.SECONDS).isPresent());
        }
    }

    @Test
    void aBackupHoldsTheSnapshotTheShopIdThePhotosAndTheDecidedEvidenceEachWithItsChecksum() throws Exception {
        String setAside = decidedSetAsideReceipt();

        BackupService.Backup backup = service().create(usb);

        assertEquals(usb.toRealPath().resolve("cella-backup-2026-11-11-153000-" + backup.backupId().toString().substring(0, 8) + ".zip"),
                backup.file(), "The time in Douala, then the start of the backup's ID");
        assertEquals(List.of(backup.file().getFileName().toString()), names(usb), "Only the finished backup is left");
        assertEquals(9, backup.schemaVersion());
        assertEquals(2, backup.products());
        assertEquals(1, backup.sales());
        assertEquals(4, backup.stockMovements(), "Two deliveries, one sale, and the set-aside receipt's delivery");
        assertEquals(2, backup.photos(), "The photo and its thumbnail");
        assertEquals(1, backup.evidence());
        assertEquals(sha256(Files.readAllBytes(backup.file())), backup.sha256());

        try (ZipFile zip = new ZipFile(backup.file().toFile())) {
            List<String> entries = zip.stream().map(ZipEntry::getName).toList();
            assertEquals("manifest.properties", entries.getFirst());
            assertTrue(entries.containsAll(List.of("cella.db", "shop-id.txt", photo.relativePath(), photo.thumbnailPath(),
                    "recovery-evidence/" + setAside)), entries.toString());
            assertTrue(entries.stream().anyMatch(name -> name.startsWith("recovery-evidence/" + setAside + ".")
                    && name.endsWith(".decision.properties")), "Its decision is in the backup too");
            assertTrue(entries.stream().noneMatch(name -> name.contains("device-id") || name.endsWith(".lock")
                    || name.contains("logs/") || name.contains("last-backup")), entries.toString());

            Properties manifest = new Properties();
            try (InputStream in = zip.getInputStream(zip.getEntry("manifest.properties"))) { manifest.load(in); }
            assertEquals(backup.backupId().toString(), manifest.getProperty("backupId"));
            assertEquals(shop.shopId().toString(), manifest.getProperty("shopId"));
            assertEquals(shop.deviceId().toString(), manifest.getProperty("sourceDeviceId"), "Where it came from, for the record");
            assertEquals("2026-11-11T14:30:00Z", manifest.getProperty("createdAt"));
            assertEquals("1", manifest.getProperty("evidence"));
            assertEquals(setAside, manifest.getProperty("evidence.0.file"));
            assertEquals("ALREADY_RECORDED", manifest.getProperty("evidence.0.outcome"));
            int files = Integer.parseInt(manifest.getProperty("files"));
            assertEquals(entries.size() - 1, files, "Every entry but the manifest is listed");
            for (int i = 0; i < files; i++) {
                String name = manifest.getProperty("file." + i + ".path");
                byte[] bytes;
                try (InputStream in = zip.getInputStream(zip.getEntry(name))) { bytes = in.readAllBytes(); }
                assertEquals(Long.toString(bytes.length), manifest.getProperty("file." + i + ".size"), name);
                assertEquals(sha256(bytes), manifest.getProperty("file." + i + ".sha256"), name);
            }
            for (String path : List.of(photo.relativePath(), photo.thumbnailPath())) {
                try (InputStream in = zip.getInputStream(zip.getEntry(path))) {
                    assertArrayEquals(Files.readAllBytes(location.directory().resolve(path)), in.readAllBytes(), path);
                }
            }
            Path copy = directory.resolve("restored.db");
            try (InputStream in = zip.getInputStream(zip.getEntry("cella.db"))) { Files.copy(in, copy); }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT current_stock_milli FROM products WHERE name = 'Rice'")) {
                rows.next();
                assertEquals(9_000, rows.getLong(1), "10 delivered, 2 sold, 1 more delivered: every change is in the snapshot");
            }
        }

        Properties last = service().lastBackup().orElseThrow();
        assertEquals(backup.backupId().toString(), last.getProperty("backupId"));
        assertEquals(backup.sha256(), last.getProperty("sha256"), "The success record names this exact archive");
    }

    @Test
    void theLastBackupIsReadWithItsAgeAndAnUnreadableRecordCountsAsNone() throws Exception {
        assertEquals(Optional.empty(), service().lastSuccess(), "No backup yet");

        BackupService.Backup backup = service().create(usb);

        BackupService.LastBackup last = service().lastSuccess().orElseThrow();
        assertEquals(backup.backupId(), last.backupId());
        assertEquals(backup.file(), last.file());
        assertEquals(backup.sha256(), last.sha256());
        assertEquals(backup.createdAt(), last.createdAt());
        assertEquals(Duration.ofDays(8), last.age(Clock.offset(CLOCK, Duration.ofDays(8))), "Its age, by the clock");

        Files.writeString(location.directory().resolve(BackupService.LAST_BACKUP), "createdAt=last tuesday\n");
        assertEquals(Optional.empty(), service().lastSuccess(), "An unreadable record counts as none: the safe side");
    }

    @Test
    void aBackupChangesNothingInTheShopAndNormalWorkGoesOnAfterwards() throws Exception {
        List<String> before = names(location.directory());
        byte[] picture = Files.readAllBytes(location.directory().resolve(photo.relativePath()));

        service().create(usb);

        List<String> after = names(location.directory());
        after.removeAll(LOCKS);
        before.removeAll(LOCKS);
        before.add(BackupService.LAST_BACKUP);
        assertEquals(before.stream().sorted().toList(), after, "Only the success record was added");
        assertArrayEquals(picture, Files.readAllBytes(location.directory().resolve(photo.relativePath())));
        assertFalse(coordinator.inMaintenance());
        assertEquals("Soap", shop.products().create(shop.shopId(), "Soap", 250).name(), "Normal work goes on");
        try (var lease = PendingReceiptFileLease.acquire(location.directory().resolve("pending-stock-receipt.properties"));
             var importScreen = new PendingImportStore(location.directory(), shop.shopId())) {
            assertTrue(importScreen.read().isEmpty(), "Operations and the import screen can start again");
        }
    }

    // ----- When a backup must not run: nothing is written -----

    @Test
    void noBackupWhileARequestIsSavedAndTheRequestIsNamed() throws Exception {
        for (String saved : BackupService.SAVED_REQUESTS) {
            Path file = location.directory().resolve(saved);
            Files.writeString(file, "a saved request");

            BackupException refused = assertThrows(BackupException.class, () -> service().create(usb), saved);

            assertEquals(BackupException.Reason.REQUEST_PENDING, refused.reason());
            assertEquals(List.of(saved), refused.details());
            assertNothingWritten();
            assertEquals("a saved request", Files.readString(file), "The saved request is untouched");
            Files.delete(file);
        }
    }

    @Test
    void noBackupWhileASetAsideFileHasNoDecisionThenItWorksOnceDecided() throws Exception {
        Path receipts = location.directory().resolve("pending-stock-receipt.properties");
        Files.writeString(receipts, "format=1\noperationId=not-a-uuid\n");
        String setAside = new FilePendingReceiptStore(receipts).setAsideDamaged().getFileName().toString();

        BackupException refused = assertThrows(BackupException.class, () -> service().create(usb));

        assertEquals(BackupException.Reason.EVIDENCE_UNRESOLVED, refused.reason());
        assertEquals(List.of(setAside), refused.details());
        assertNothingWritten();

        Evidence undecided = evidence().inventory().getFirst();
        evidence().decide(undecided.fileName(), undecided.sha256(), "Charles", EvidenceDecision.Outcome.DID_NOT_HAPPEN, null,
                "No delivery came that day");
        assertEquals(1, service().create(usb).evidence(), "Once decided, it is backed up with its decision");
    }

    /** Another Cella: joins the folder and stays idle until it reads a byte. */
    public static class IdleCella {
        public static void main(String[] args) throws Exception {
            DataFolderCoordinator.forFolder(new LocalAppData(Path.of(args[0]))).join();
            System.out.println("READY");
            System.out.flush();
            System.in.read();
        }
    }

    @Test
    void noBackupWhileAnotherCellaHasTheShopOpen() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), IdleCella.class.getName(),
                location.directory().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (TestChildProcess child = new TestChildProcess(process)) {
            assertEquals("READY", child.readOutput(() -> new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8)).readLine()).get(60, TimeUnit.SECONDS));

            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class, () -> service().create(usb));

            assertEquals(FolderMaintenanceException.Reason.OTHER_INSTANCE, refused.reason());
            assertNothingWritten();
        }
    }

    @Test
    void noBackupWhileAnOperationIsInProgressOrTheImportScreenIsOpen() throws Exception {
        ExecutorService operation = stoppableWorkers(1);
        CompletableFuture<Void> held = new CompletableFuture<>(), release = new CompletableFuture<>();
        operation.submit(() -> {
            try (var receipt = PendingReceiptFileLease.acquire(location.directory().resolve("pending-stock-receipt.properties"))) {
                held.complete(null);
                release.get(20, TimeUnit.SECONDS);
            }
            return null;
        });
        try {
            held.get(10, TimeUnit.SECONDS);
            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class, () -> service().create(usb));
            assertEquals(FolderMaintenanceException.Reason.OPERATION_RUNNING, refused.reason());
        } finally {
            release.complete(null);
        }
        try (var importScreen = new PendingImportStore(location.directory(), shop.shopId())) {
            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class, () -> service().create(usb));
            assertEquals(FolderMaintenanceException.Reason.OPERATION_RUNNING, refused.reason());
        }
        assertNothingWritten();
    }

    @Test
    void aMissingPhotoStopsTheBackupAndLeavesNoFile() throws Exception {
        Files.delete(location.directory().resolve(photo.relativePath()));

        BackupException refused = assertThrows(BackupException.class, () -> service().create(usb));

        assertEquals(BackupException.Reason.PHOTO_CHANGED, refused.reason());
        assertEquals(List.of(photo.relativePath()), refused.details());
        assertNothingWritten();
    }

    @Test
    void aDestinationInsideTheShopFolderIsRefused() throws Exception {
        for (Path inside : List.of(location.directory(), location.directory().resolve("backups"),
                location.directory().resolve("photos"))) {
            BackupException refused = assertThrows(BackupException.class, () -> service().create(inside), inside.toString());
            assertEquals(BackupException.Reason.DESTINATION_REFUSED, refused.reason());
        }
        assertFalse(Files.exists(location.directory().resolve(BackupService.LAST_BACKUP)));
    }

    @Test
    void aBackupThatCannotTakeItsFinalNameLeavesNoNewBackupAndKeepsTheLastSuccessRecord() throws Exception {
        BackupService.Backup first = service().create(usb);
        Properties recorded = service().lastBackup().orElseThrow();

        BackupException unsafe = assertThrows(BackupException.class, () -> service((partial, published) -> {
            throw new AtomicMoveNotSupportedException(partial.toString(), published.toString(), "a disk that cannot rename safely");
        }).create(usb));
        assertEquals(BackupException.Reason.DESTINATION_REFUSED, unsafe.reason());

        IOException removed = assertThrows(IOException.class, () -> service((partial, published) -> {
            throw new IOException("The USB key was removed");
        }).create(usb));
        assertEquals("The USB key was removed", removed.getMessage());

        byte[] someoneElses = "another file that took the name".getBytes(StandardCharsets.UTF_8);
        List<Path> taken = new ArrayList<>();
        BackupException occupied = assertThrows(BackupException.class, () -> service((partial, published) -> {
            Files.write(published, someoneElses);
            taken.add(published);
            BackupService.RENAME.publish(partial, published);
        }).create(usb));
        assertEquals(BackupException.Reason.DESTINATION_REFUSED, occupied.reason());
        assertArrayEquals(someoneElses, Files.readAllBytes(taken.getFirst()), "The other file was not replaced");

        List<String> expected = new ArrayList<>(List.of(first.file().getFileName().toString(), taken.getFirst().getFileName().toString()));
        expected.sort(null);
        assertEquals(expected, names(usb), "No new backup, no temporary file, no work folder");
        assertEquals(recorded, service().lastBackup().orElseThrow(), "The success record still names the first backup");
        assertFalse(coordinator.inMaintenance(), "The folder was given back");
        assertTrue(Files.exists(service().create(usb).file()), "The next backup works");
    }

    // ----- Crashes -----

    /** Another Cella that sells rice and is killed just after the database commits the sale. */
    public static class SellingCella {
        public static void main(String[] args) throws Exception {
            ForcedClose.watchTheDatabase();
            LocalAppData location = new LocalAppData(Path.of(args[0]));
            AppContext shop = DesktopApplication.openShop(location);
            UUID rice = shop.products().list(shop.shopId()).stream().filter(p -> p.name().equals("Rice")).findFirst().orElseThrow().id();
            ForcedClose.stopAfter("COMMIT", 1);
            shop.sales().makeCashSale(UUID.randomUUID(), shop.shopId(), shop.deviceId(), "REC-2", List.of(new SaleItemRequest(rice, 1_000)));
            ForcedClose.finishedWithoutStopping();
        }
    }

    @Test
    void aSaleCommittedJustBeforeACrashIsInTheNextBackup() throws Exception {
        leaveTheFolder();
        ForcedClose.runAndKill(SellingCella.class, location.directory());
        rejoinTheFolder();

        BackupService.Backup backup = service().create(usb);

        assertEquals(2, backup.sales(), "The crashed Cella's committed sale is in the backup");
    }

    /** Another Cella making a backup, killed at a chosen statement. */
    public static class BackingUpCella {
        public static void main(String[] args) throws Exception {
            ForcedClose.watchTheDatabase();
            LocalAppData location = new LocalAppData(Path.of(args[0]));
            AppContext shop = DesktopApplication.openShop(location);
            EvidenceService evidence = new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                    new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), Clock.systemDefaultZone());
            ForcedClose.stopAfter(args[2], Integer.parseInt(args[3]));
            new BackupService(location, shop.shopId(), shop.deviceId(), DataFolderCoordinator.forFolder(location), evidence,
                    Clock.systemDefaultZone()).create(Path.of(args[1]));
            ForcedClose.finishedWithoutStopping();
        }
    }

    @Test
    void aBackupKilledWhileCopyingTheDatabaseLeavesNoBackupAndNoSuccessRecord() throws Exception {
        leaveTheFolder();
        ForcedClose.runAndKill(BackingUpCella.class, location.directory(), true, usb.toString(), "VACUUM INTO", "1");
        rejoinTheFolder();

        assertTrue(names(usb).stream().noneMatch(name -> name.endsWith(".zip")), "Nothing has a backup's final name: " + names(usb));
        assertFalse(Files.exists(location.directory().resolve(BackupService.LAST_BACKUP)), "No success was recorded");
        assertTrue(Files.exists(service().create(usb).file()), "The next backup works");
    }

    @Test
    void aBackupKilledJustBeforeItsFinalNameLeavesNoBackupAndNoSuccessRecord() throws Exception {
        leaveTheFolder();
        // The first integrity check is the snapshot's; the second, the archive's, just before publication.
        ForcedClose.runAndKill(BackingUpCella.class, location.directory(), false, usb.toString(), "PRAGMA integrity_check", "2");
        rejoinTheFolder();

        List<String> left = names(usb);
        assertTrue(left.stream().anyMatch(name -> name.endsWith(".partial")), "The kill came after the archive was written: " + left);
        assertTrue(left.stream().noneMatch(name -> name.endsWith(".zip")), "Nothing has a backup's final name: " + left);
        assertFalse(Files.exists(location.directory().resolve(BackupService.LAST_BACKUP)), "No success was recorded");
        BackupService.Backup next = service().create(usb);
        assertTrue(Files.exists(next.file()), "The next backup works");
        assertEquals(1, names(usb).stream().filter(name -> name.endsWith(".zip")).count());
    }

    // ----- Helpers -----

    /** A receipt set aside from another computer, already in the history, with its decision recorded. */
    private String decidedSetAsideReceipt() throws Exception {
        UUID operation = UUID.randomUUID();
        UUID otherComputer = UUID.randomUUID();
        shop.stock().receive(operation, shop.shopId(), rice.id(), otherComputer, 1_000, "Delivery 9");
        Path receipts = location.directory().resolve("pending-stock-receipt.properties");
        new FilePendingReceiptStore(receipts).save(new cm.sji.innova.desktop.stock.PendingReceipt(operation, shop.shopId(),
                rice.id(), otherComputer, "piece", 1_000, "Delivery 9"));
        String name = new FilePendingReceiptStore(receipts).setAside(operation).getFileName().toString();
        Evidence found = evidence().inventory().getFirst();
        evidence().decide(found.fileName(), found.sha256(), "Charles", EvidenceDecision.Outcome.ALREADY_RECORDED, operation,
                "Delivery 9 is in the history");
        return name;
    }

    /** The test's own Cella lets go of the folder, so another process can take it (or be killed holding it). */
    private void leaveTheFolder() throws Exception {
        membership.close();
        membership = null;
    }

    private void rejoinTheFolder() throws Exception {
        membership = coordinator.join();
    }

    private void assertNothingWritten() throws Exception {
        assertEquals(List.of(), names(usb), "No backup, no temporary file, no work folder");
        assertFalse(Files.exists(location.directory().resolve(BackupService.LAST_BACKUP)), "No success was recorded");
        assertFalse(coordinator.inMaintenance(), "The folder was given back");
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

    private ExecutorService stoppableWorkers(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "backup-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        toClose.add(pool::shutdownNow);
        return pool;
    }
}

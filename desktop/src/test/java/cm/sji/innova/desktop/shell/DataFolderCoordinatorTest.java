package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.DatabaseGate;
import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductPhotoRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import cm.sji.innova.desktop.DesktopApplication;
import cm.sji.innova.desktop.TestChildProcess;
import cm.sji.innova.desktop.catalog.importing.PendingImportStore;
import cm.sji.innova.desktop.sales.PendingCheckoutStore;
import cm.sji.innova.desktop.stock.FilePendingReceiptStore;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03a1: the folder coordinator, with real second Java processes for the other Cellas. Disposable
 * data only.
 */
class DataFolderCoordinatorTest {
    @TempDir Path directory;

    private LocalAppData location;
    private DataFolderCoordinator coordinator;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void aShopFolder() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        coordinator = DataFolderCoordinator.forFolder(location);
    }

    /** Closes everything, newest first, even when one of them fails, then reports the first failure. */
    @AfterEach
    void letGoOfEverything() throws Exception {
        Exception first = null;
        for (int i = toClose.size() - 1; i >= 0; i--) {
            try {
                toClose.get(i).close();
            } catch (Exception failure) {
                if (first == null) first = failure; else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    private DataFolderCoordinator.Membership join() throws Exception {
        DataFolderCoordinator.Membership membership = coordinator.join();
        toClose.add(membership);
        return membership;
    }

    // ----- Other Cellas, as separate processes -----

    /** Another Cella: "join" joins and stays idle, "maintain" also takes maintenance, "app" starts as Cella does. */
    public static class OtherCella {
        public static void main(String[] args) throws Exception {
            LocalAppData location = new LocalAppData(Path.of(args[0]));
            try {
                switch (args[1]) {
                    case "join", "maintain" -> DataFolderCoordinator.forFolder(location).join();
                    case "app" -> DesktopApplication.openShop(location);
                    default -> throw new IllegalArgumentException(args[1]);
                }
                if (args[1].equals("maintain")) DataFolderCoordinator.forFolder(location).beginMaintenance(Duration.ofSeconds(5));
                System.out.println("READY");
            } catch (FolderMaintenanceException refused) {
                System.out.println("REFUSED " + refused.reason());
            }
            System.out.flush();
            System.in.read();   // holds everything until the test lets go or kills it
        }
    }

    private Other start(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                OtherCella.class.getName(), location.directory().toString(), mode)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        TestChildProcess child = new TestChildProcess(process);
        String line = child.readOutput(() -> new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8)).readLine()).get(60, TimeUnit.SECONDS);
        Other other = new Other(child, line);
        toClose.add(other);
        return other;
    }

    /** A running other Cella; closing it ends the process. */
    private record Other(TestChildProcess process, String line) implements AutoCloseable {
        @Override public void close() throws Exception { process.close(); }
    }

    /** Windows releases a finished process's locks a moment after it ends; retry until the answer comes. */
    private static <T> T eventually(Callable<T> attempt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            try {
                return attempt.call();
            } catch (FolderMaintenanceException notYet) {
                if (System.nanoTime() > deadline) throw notYet;
                Thread.sleep(50);
            }
        }
    }

    // ----- Shared use -----

    @Test
    void twoCellasCanHaveTheSameShopOpenAtOnce() throws Exception {
        join();
        assertEquals("READY", start("join").line(), "A second Cella joins the folder too");
    }

    @Test
    void cellaJoinsTheFolderWhenItStartsBeforeOpeningTheShop() throws Exception {
        assertEquals("READY", start("app").line());
        assertTrue(Files.exists(location.shopIdFile()), "The other Cella opened the shop");
        join();

        FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class,
                () -> coordinator.beginMaintenance(Duration.ofSeconds(5)));

        assertEquals(FolderMaintenanceException.Reason.OTHER_INSTANCE, refused.reason(), "The started Cella holds the folder");
    }

    // ----- An idle Cella elsewhere -----

    @Test
    void anIdleCellaElsewhereStopsMaintenanceAndThisOneCarriesOnNormally() throws Exception {
        join();
        AppContextLoader.load(location);
        Other idle = start("join");

        FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class,
                () -> coordinator.beginMaintenance(Duration.ofSeconds(5)));

        assertEquals(FolderMaintenanceException.Reason.OTHER_INSTANCE, refused.reason());
        assertFalse(coordinator.inMaintenance());
        try (Connection connection = database().open()) {
            assertTrue(connection.isValid(5), "This Cella keeps working");
        }
        Other second = start("join");
        assertEquals("READY", second.line(), "The folder is still shared: nothing changed");

        idle.close();
        second.close();
        try (DataFolderCoordinator.Maintenance maintenance = eventually(() -> coordinator.beginMaintenance(Duration.ofSeconds(5)))) {
            assertTrue(coordinator.inMaintenance(), "Once the other Cellas are closed, maintenance works");
        }
        assertFalse(coordinator.inMaintenance());
    }

    @Test
    void aCellaCannotOpenAShopWhileAnotherIsMaintainingItAndCanOnceItEnds() throws Exception {
        Other maintaining = start("maintain");
        assertEquals("READY", maintaining.line());

        FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class, coordinator::join);
        assertEquals(FolderMaintenanceException.Reason.IN_MAINTENANCE, refused.reason());
        assertFalse(Files.exists(location.databaseFile()), "Nothing was opened");

        maintaining.close();
        toClose.add(eventually(coordinator::join));
        AppContextLoader.load(location);
    }

    // ----- Work in this Cella during maintenance -----

    @Test
    void maintenanceWaitsForOpenConnectionsThenPausesNewOnesExceptItsOwn() throws Exception {
        join();
        AppContextLoader.load(location);
        ExecutorService workers = stoppableWorkers(3);
        Connection open = workers.submit(() -> database().open()).get(10, TimeUnit.SECONDS);
        CompletableFuture<DataFolderCoordinator.Maintenance> maintenance = new CompletableFuture<>();
        CompletableFuture<Void> finish = new CompletableFuture<>();
        CompletableFuture<Boolean> ownConnection = new CompletableFuture<>();
        workers.submit(() -> {
            try (DataFolderCoordinator.Maintenance taken = coordinator.beginMaintenance(Duration.ofSeconds(20))) {
                maintenance.complete(taken);
                try (Connection own = taken.openDatabase()) {
                    ownConnection.complete(own.isValid(5));
                }
                finish.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                maintenance.completeExceptionally(failure);
            }
            return null;
        });
        try {
            assertThrows(TimeoutException.class, () -> maintenance.get(300, TimeUnit.MILLISECONDS),
                    "Maintenance waits while a connection is open");
            open.close();
            maintenance.get(10, TimeUnit.SECONDS);
            assertTrue(coordinator.inMaintenance());
            assertTrue(ownConnection.get(10, TimeUnit.SECONDS), "Maintenance uses the database through its own path");

            var waiting = workers.submit(() -> { try (Connection ignored = database().open()) { return "opened"; } });
            assertThrows(TimeoutException.class, () -> waiting.get(300, TimeUnit.MILLISECONDS),
                    "A new connection waits during maintenance");

            finish.complete(null);
            assertEquals("opened", waiting.get(10, TimeUnit.SECONDS), "It goes on once maintenance ends");
            assertFalse(coordinator.inMaintenance());
        } finally {
            finish.complete(null);   // whatever happened, maintenance ends
            open.close();
        }
    }

    @Test
    void maintenanceGivesUpWhenWorkDoesNotFinishInTimeAndChangesNothing() throws Exception {
        join();
        AppContextLoader.load(location);
        ExecutorService worker = stoppableWorkers(1);
        Connection open = worker.submit(() -> database().open()).get(10, TimeUnit.SECONDS);
        try {
            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class,
                    () -> coordinator.beginMaintenance(Duration.ofMillis(200)));

            assertEquals(FolderMaintenanceException.Reason.STILL_WORKING, refused.reason());
            try (Connection another = worker.submit(() -> database().open()).get(10, TimeUnit.SECONDS)) {
                assertTrue(another.isValid(5), "New work is not held back after giving up");
            }
        } finally {
            open.close();
        }
        assertEquals("READY", start("join").line(), "The folder is still shared");
    }

    // ----- Every writer takes part (Patrick's review of a1) -----

    @Test
    void productPhotoAndThresholdWritesWaitDuringMaintenanceAndGoOnAfterwards() throws Exception {
        join();
        AppContext shop = AppContextLoader.load(location);
        Product rice = shop.products().create(shop.shopId(), "Rice", 1_000);
        Path picture = directory.resolve("rice.png");
        ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
        // A leftover from an interrupted photo save. Clean-up deletes it before touching the database.
        Path leftover = location.directory().resolve("photos").resolve(".staging").resolve("left-over.png");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "interrupted");
        ExecutorService workers = stoppableWorkers(5);
        CompletableFuture<Void> started = new CompletableFuture<>(), finish = new CompletableFuture<>();
        workers.submit(() -> {
            try (DataFolderCoordinator.Maintenance ignored = coordinator.beginMaintenance(Duration.ofSeconds(20))) {
                started.complete(null);
                finish.get(30, TimeUnit.SECONDS);
            } catch (Exception failure) {
                started.completeExceptionally(failure);
            }
            return null;
        });
        try {
            started.get(10, TimeUnit.SECONDS);
            // The clean-up first and alone: nothing else holds the photo lock, so only the folder gate stops it.
            var cleanUp = workers.submit(() -> photos().cleanupOrphans(shop.shopId()));
            assertThrows(TimeoutException.class, () -> cleanUp.get(300, TimeUnit.MILLISECONDS), "The photo clean-up waits");
            assertTrue(Files.exists(leftover), "It deleted nothing during maintenance");
            var product = workers.submit(() -> shop.products().create(shop.shopId(), "Oil", 1_500));
            var threshold = workers.submit(() -> shop.stock().setMinimum(shop.shopId(), rice.id(), 2_000));
            var photo = workers.submit(() -> photos().setPhoto(shop.shopId(), rice.id(), picture));
            for (var write : List.of(product, threshold, photo, cleanUp)) {
                assertThrows(TimeoutException.class, () -> write.get(300, TimeUnit.MILLISECONDS), "Each write waits during maintenance");
            }
            assertEquals(1, filesUnder(location.directory().resolve("photos")),
                    "No photo file was written or deleted during maintenance: only the leftover is there");
            assertTrue(Files.exists(leftover));
            finish.complete(null);

            assertEquals("Oil", product.get(20, TimeUnit.SECONDS).name());
            assertEquals(2_000, threshold.get(20, TimeUnit.SECONDS).minimumMilli());
            assertTrue(Files.exists(location.directory().resolve(photo.get(20, TimeUnit.SECONDS).relativePath())),
                    "Each write goes on once maintenance ends");
            cleanUp.get(20, TimeUnit.SECONDS);
            assertFalse(Files.exists(leftover), "The clean-up ran afterwards");
        } finally {
            finish.complete(null);
        }
    }

    @Test
    void photoWorkAlreadyRunningHoldsMaintenanceBackAndItsOwnConnectionsDoNotWait() throws Exception {
        join();
        AppContextLoader.load(location);
        ExecutorService workers = stoppableWorkers(2);
        CompletableFuture<Void> inside = new CompletableFuture<>(), goOn = new CompletableFuture<>();
        var photoWork = workers.submit(() -> {
            try (DatabaseGate.Work work = DatabaseGate.enterFolder(location.directory())) {   // as a photo save does
                inside.complete(null);
                goOn.get(20, TimeUnit.SECONDS);
                try (Connection own = database().open()) {   // the photo's own database step
                    return own.isValid(5);
                }
            }
        });
        inside.get(10, TimeUnit.SECONDS);
        var maintenance = workers.submit(() -> {
            try (DataFolderCoordinator.Maintenance ignored = coordinator.beginMaintenance(Duration.ofSeconds(20))) {
                return "maintained";
            }
        });
        try {
            assertThrows(TimeoutException.class, () -> maintenance.get(300, TimeUnit.MILLISECONDS),
                    "Maintenance waits for the photo work");
            goOn.complete(null);
            assertTrue(photoWork.get(10, TimeUnit.SECONDS), "The photo's own connection did not wait for maintenance");
            assertEquals("maintained", maintenance.get(10, TimeUnit.SECONDS), "Maintenance runs once the photo work ends");
        } finally {
            goOn.complete(null);
        }
    }

    @Test
    void anOperationInProgressOrTheImportScreenStopsMaintenanceAndNothingChanges() throws Exception {
        join();
        AppContext shop = AppContextLoader.load(location);
        ExecutorService operation = stoppableWorkers(1);
        CompletableFuture<Void> held = new CompletableFuture<>(), release = new CompletableFuture<>();
        operation.submit(() -> {
            try (var receipt = cm.sji.innova.desktop.stock.PendingReceiptFileLease.acquire(
                    location.directory().resolve("pending-stock-receipt.properties"))) {
                held.complete(null);
                release.get(20, TimeUnit.SECONDS);
            }
            return null;
        });
        try {
            held.get(10, TimeUnit.SECONDS);
            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class,
                    () -> coordinator.beginMaintenance(Duration.ofSeconds(5)));
            assertEquals(FolderMaintenanceException.Reason.OPERATION_RUNNING, refused.reason());
            assertTrue(refused.getMessage().contains("stock"), refused.getMessage());
        } finally {
            release.complete(null);
        }

        try (var importScreen = PendingImportStore.holdFolder(location.directory())) {
            FolderMaintenanceException refused = assertThrows(FolderMaintenanceException.class,
                    () -> coordinator.beginMaintenance(Duration.ofSeconds(5)));
            assertEquals(FolderMaintenanceException.Reason.OPERATION_RUNNING, refused.reason());
            assertTrue(refused.getMessage().contains("import"), refused.getMessage());
        }

        assertFalse(coordinator.inMaintenance());
        assertEquals("Oil", shop.products().create(shop.shopId(), "Oil", 1_500).name(), "Normal work goes on");
        assertEquals("READY", start("join").line(), "The folder is still shared");
    }

    @Test
    void maintenanceHoldsEveryOperationLeaseUntilItEnds() throws Exception {
        join();
        AppContext shop = AppContextLoader.load(location);
        ExecutorService other = stoppableWorkers(1);
        Path receipt = location.directory().resolve("pending-stock-receipt.properties");
        try (DataFolderCoordinator.Maintenance ignored = coordinator.beginMaintenance(Duration.ofSeconds(5))) {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> other.submit(() -> new FilePendingReceiptStore(receipt).load()).get(10, TimeUnit.SECONDS),
                    "A stock receipt or correction cannot start");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> other.submit(() -> new PendingCheckoutStore(location.pendingCheckoutFile()).load()).get(10, TimeUnit.SECONDS),
                    "A cash sale cannot start");
            assertThrows(PendingImportStore.BusyException.class, () -> new PendingImportStore(location.directory(), shop.shopId()),
                    "The import screen cannot open");
        }
        other.submit(() -> new FilePendingReceiptStore(receipt).load()).get(10, TimeUnit.SECONDS);
        other.submit(() -> new PendingCheckoutStore(location.pendingCheckoutFile()).load()).get(10, TimeUnit.SECONDS);
        new PendingImportStore(location.directory(), shop.shopId()).close();
    }

    // ----- Explicit paths, and never a wait on the Swing thread -----

    @Test
    void duringMaintenanceOnlyItsOwnPathReachesTheDatabase() throws Exception {
        join();
        AppContextLoader.load(location);
        try (DataFolderCoordinator.Maintenance maintenance = coordinator.beginMaintenance(Duration.ofSeconds(5))) {
            assertThrows(java.sql.SQLException.class, () -> database().open(),
                    "An ordinary connection on the maintenance thread is refused, not let through");
            try (Connection own = maintenance.openDatabase()) {
                assertTrue(own.isValid(5));
            }
        }
        try (Connection afterwards = database().open()) {
            assertTrue(afterwards.isValid(5), "Ordinary connections work again afterwards");
        }
    }

    @Test
    void theSwingThreadNeverWaitsAndNeverStartsMaintenance() throws Exception {
        join();
        AppContextLoader.load(location);
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
                () -> coordinator.beginMaintenance(Duration.ofSeconds(5))));

        ExecutorService maintainer = stoppableWorkers(1);
        CompletableFuture<Void> started = new CompletableFuture<>(), finish = new CompletableFuture<>();
        maintainer.submit(() -> {
            try (DataFolderCoordinator.Maintenance ignored = coordinator.beginMaintenance(Duration.ofSeconds(5))) {
                started.complete(null);
                finish.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                started.completeExceptionally(failure);
            }
            return null;
        });
        try {
            started.get(10, TimeUnit.SECONDS);
            long before = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> assertThrows(java.sql.SQLException.class, () -> database().open(),
                    "On the Swing thread the connection is refused at once"));
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2), "It did not wait");
        } finally {
            finish.complete(null);
        }
    }

    private static long filesUnder(Path folder) throws Exception {
        if (!Files.exists(folder)) return 0;
        try (var walk = Files.walk(folder)) { return walk.filter(Files::isRegularFile).count(); }
    }

    private ProductPhotoService photos() {
        return new ProductPhotoService(new SqliteProductPhotoRepository(new SqliteDatabase(location.databaseFile())),
                location.directory());
    }

    @Test
    void theFolderIsOnlyLetGoWhenTheLastMemberLeaves() throws Exception {
        DataFolderCoordinator.Membership first = join();
        DataFolderCoordinator.Membership second = join();

        first.close();
        try (Other refused = start("maintain")) {
            assertEquals("REFUSED OTHER_INSTANCE", refused.line(), "The second member still holds it");
        }

        second.close();
        assertEquals("READY", eventuallyLine("maintain"), "Once the last member left, the folder is free");
    }

    private String eventuallyLine(String mode) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            Other other = start(mode);
            if (other.line().equals("READY") || System.nanoTime() > deadline) return other.line();
            other.close();
            toClose.remove(other);
            Thread.sleep(100);
        }
    }

    private SqliteDatabase database() { return new SqliteDatabase(location.databaseFile()); }

    /**
     * Worker threads the test always stops at the end. A worker stuck waiting for the database makes the
     * test fail on its timeout; it never keeps the build waiting, as closing an executor would.
     */
    private ExecutorService stoppableWorkers(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "coordinator-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        toClose.add(pool::shutdownNow);
        return pool;
    }
}

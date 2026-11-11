package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.desktop.DesktopApplication;
import cm.sji.innova.desktop.TestChildProcess;
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
                try (Connection own = database().open()) {
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
            assertTrue(ownConnection.get(10, TimeUnit.SECONDS), "Maintenance itself can use the database");

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

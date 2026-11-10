package cm.sji.innova.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * OFF-02c forced-close drills: runs part of Cella in its own Java process, stops it at an exact moment
 * of a database write, and kills it there, as a crash or the Task Manager would.
 *
 * <p>The child process watches every SQLite statement by putting a thin wrapper around the SQLite
 * driver. Nothing in Cella is changed for this. When the chosen statement has run the chosen number of
 * times, the child prints {@code PAUSED} and waits. The test then kills it: on Windows the process is
 * terminated, on Linux it gets {@code SIGKILL}. Neither lets Cella run any more code, so whatever was
 * not committed or cleaned up stays exactly as the crash left it.
 */
public final class ForcedClose {
    private static final String PAUSED = "PAUSED";
    private static final String FINISHED = "FINISHED";
    private static final Set<String> RUNS = Set.of("execute", "executeUpdate", "executeLargeUpdate", "executeBatch");
    private static volatile String stopAfter;
    private static volatile int stopAt;
    private static final AtomicInteger seen = new AtomicInteger();

    private ForcedClose() { }

    // ----- In the child process -----

    /** Call first in the child: every SQLite connection opened from now on is watched. */
    public static void watchTheDatabase() throws SQLException {
        Driver real = DriverManager.getDriver("jdbc:sqlite:");
        DriverManager.deregisterDriver(real);
        DriverManager.registerDriver(new WatchingDriver(real));
    }

    /**
     * From now on, stop right after the statement starting with {@code statementStart} has run for the
     * {@code time}-th time. {@code "COMMIT"} also matches {@link Connection#commit()}.
     */
    public static void stopAfter(String statementStart, int time) {
        seen.set(0);
        stopAt = time;
        stopAfter = normalized(statementStart);
    }

    /** Says the operation ended without reaching its moment, so the drill tested nothing. */
    public static void finishedWithoutStopping() {
        System.out.println(FINISHED);
        System.out.flush();
        System.exit(1);
    }

    private static void ran(String sql) {
        String watched = stopAfter;
        if (watched == null || !normalized(sql).startsWith(watched) || seen.incrementAndGet() != stopAt) return;
        System.out.println(PAUSED + " after " + watched + " #" + stopAt);
        System.out.flush();
        try {
            new CountDownLatch(1).await();   // until the test kills this process
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalized(String sql) {
        return sql.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** The real SQLite driver, whose connections report each statement after it has run. */
    private record WatchingDriver(Driver real) implements Driver {
        @Override public Connection connect(String url, Properties info) throws SQLException {
            Connection connection = real.connect(url, info);
            return connection == null ? null : watch(Connection.class, connection, null);
        }
        @Override public boolean acceptsURL(String url) throws SQLException { return real.acceptsURL(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return real.getPropertyInfo(url, info);
        }
        @Override public int getMajorVersion() { return real.getMajorVersion(); }
        @Override public int getMinorVersion() { return real.getMinorVersion(); }
        @Override public boolean jdbcCompliant() { return real.jdbcCompliant(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return real.getParentLogger(); }
    }

    /** Wraps a connection or statement; {@code sql} is the prepared statement's text, if any. */
    private static <T> T watch(Class<T> type, T real, String sql) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object result = call(method, real, args);
            String name = method.getName();
            if (result instanceof PreparedStatement prepared && name.equals("prepareStatement")) {
                return watch(PreparedStatement.class, prepared, (String) args[0]);
            }
            if (result instanceof Statement statement && name.equals("createStatement")) {
                return watch(Statement.class, statement, null);
            }
            if (type == Connection.class && name.equals("commit")) ran("COMMIT");
            else if (RUNS.contains(name)) {
                if (args != null && args.length > 0 && args[0] instanceof String text) ran(text);
                else if (sql != null) ran(sql);
            }
            return result;
        };
        return type.cast(Proxy.newProxyInstance(ForcedClose.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    // ----- In the test -----

    /**
     * Starts {@code main} in its own Java process on the test's class path, with the data folder as its
     * first argument. Waits until it has stopped at its moment, kills it, waits until it is gone, then
     * waits until the system has released its locks on the data folder's files.
     */
    public static void runAndKill(Class<?> main, Path dataFolder, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString(),
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), main.getName(),
                dataFolder.toString()));
        command.addAll(List.of(args));
        Process child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var output = executor.submit(() -> {
                List<String> lines = new ArrayList<>();
                var reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    lines.add(line);
                    if (line.startsWith(PAUSED)) return lines;
                }
                return lines;
            });
            List<String> lines = output.get(90, TimeUnit.SECONDS);
            if (lines.isEmpty() || !lines.get(lines.size() - 1).startsWith(PAUSED)) {
                fail("The process ended before reaching its moment: " + lines);
            }
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "The killed process is gone");
        }
        // A clean exit folds SQLite's write-ahead log into the database and removes it; a crash leaves it.
        assertTrue(Files.exists(dataFolder.resolve("cella.db-wal")), "The process died without closing the database");
        waitUntilReleased(dataFolder);
    }

    /**
     * Windows releases a killed process's file locks a moment after the process has ended ("the time it
     * takes ... depends upon available system resources", LockFileEx documentation). A person restarting
     * Cella after a crash takes longer than that, so the drill waits for it too: it takes and drops a lock
     * on every file in the data folder until all of them succeed, then waits until the database can be
     * read. The test must have no database connection open meanwhile: on Linux, closing any handle to a
     * file drops every lock this process holds on it.
     */
    private static void waitUntilReleased(Path folder) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!allFree(folder)) {
            assertTrue(System.nanoTime() < deadline, "The killed process's locks were never released");
            Thread.sleep(20);
        }
        // Windows can also keep the dead process's mapping of SQLite's shared index file (cella.db-shm) a
        // few milliseconds longer. Until it goes, SQLite cannot reset that file and reports a disk I/O
        // error. Wait until a new connection can read, as the first read of a restarted Cella would.
        while (true) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("cella.db"));
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
                rows.next();
                return;
            } catch (SQLException notYet) {
                if (System.nanoTime() > deadline) throw notYet;
                Thread.sleep(20);
            }
        }
    }

    private static boolean allFree(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                     var lock = channel.tryLock()) {
                    if (lock == null) return false;
                } catch (IOException | OverlappingFileLockException busy) {
                    return false;
                }
            }
        }
        return true;
    }
}

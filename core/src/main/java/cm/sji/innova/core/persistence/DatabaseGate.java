package cm.sji.innova.core.persistence;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the desktop pause all work on one shop folder's database and files, for backup and restore
 * (OFF-03).
 *
 * <p>A gate is installed for the folder that holds the database. From then on:
 * <ul>
 *   <li>every connection {@link SqliteDatabase#open()} makes to a database in that folder enters the
 *       gate before opening (it may wait while maintenance runs) and leaves it once, when it is closed
 *       or fails to open;</li>
 *   <li>file work in the folder, such as saving a photo, enters it through {@link #enterFolder(Path)}
 *       for its whole duration;</li>
 *   <li>maintenance opens its own connections with the {@link Pass} it got when installing the gate.
 *       There is no other way past a paused gate.</li>
 * </ul>
 * Without a gate, nothing changes.
 */
public final class DatabaseGate {
    /** One folder's gate. */
    public interface Gate {
        /** Called before a connection opens. It may wait, for example while a backup runs. */
        void enter() throws SQLException;

        /** Called exactly once for each successful {@link #enter()}, when that connection is closed or fails to open. */
        void leave();

        /** Called before file work in the folder. Connections opened inside that work must not wait for it. */
        default void enterWork() throws SQLException { enter(); }

        /** Called once when that file work ends, on the thread that entered. */
        default void leaveWork() { leave(); }
    }

    /** One piece of file work in a gated folder; closing it leaves the gate. */
    public interface Work extends AutoCloseable {
        @Override void close();
    }

    /** Proof of owning one installed gate: lets the owner's maintenance connections through. */
    public static final class Pass {
        private final Gate gate;

        private Pass(Gate gate) { this.gate = gate; }
    }

    private static final ConcurrentHashMap<Path, Gate> GATES = new ConcurrentHashMap<>();

    private DatabaseGate() { }

    /**
     * From now on, every connection to this database and every piece of file work in its folder passes
     * through {@code gate}. Returns the pass for the owner's own maintenance connections.
     */
    public static Pass install(Path databaseFile, Gate gate) {
        GATES.put(folderOf(databaseFile), gate);
        return new Pass(gate);
    }

    /** Removes {@code gate}, if it is still the one installed for this database's folder. */
    public static void remove(Path databaseFile, Gate gate) {
        GATES.remove(folderOf(databaseFile), gate);
    }

    /** Enters the folder's gate for file work, such as saving a photo. Without a gate it does nothing. */
    public static Work enterFolder(Path dataFolder) throws SQLException {
        Gate gate = GATES.get(dataFolder.toAbsolutePath().normalize());
        if (gate == null) return () -> { };
        gate.enterWork();
        AtomicBoolean left = new AtomicBoolean();
        return () -> {
            if (left.compareAndSet(false, true)) gate.leaveWork();
        };
    }

    static Gate forDatabase(Path databaseFile) {
        return GATES.get(folderOf(databaseFile));
    }

    /** True if {@code pass} belongs to the gate now installed for this database's folder. */
    static boolean admits(Pass pass, Path databaseFile) {
        return pass != null && pass.gate == forDatabase(databaseFile);
    }

    private static Path folderOf(Path databaseFile) {
        Path folder = databaseFile.toAbsolutePath().normalize().getParent();
        if (folder == null) throw new IllegalArgumentException("A database file has a folder: " + databaseFile);
        return folder;
    }

    /** The connection, which leaves the gate once when it is closed. */
    static Connection leavingOnClose(Connection connection, Gate gate) {
        AtomicBoolean left = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(DatabaseGate.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        } finally {
                            if (left.compareAndSet(false, true)) gate.leave();
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}

package cm.sji.innova.core.persistence;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the desktop pause every connection to one database file, for backup and restore (OFF-03).
 *
 * <p>When a gate is installed for a file, every connection {@link SqliteDatabase#open()} opens to that
 * file passes through it: {@link Gate#enter()} before opening (it may wait while maintenance runs) and
 * {@link Gate#leave()} once, when the connection is closed. So the gate always knows how many
 * connections are open. Without a gate, nothing changes.
 */
public final class DatabaseGate {
    /** One database file's gate. */
    public interface Gate {
        /** Called before a connection opens. It may wait, for example while a backup runs. */
        void enter() throws SQLException;

        /** Called exactly once for each successful {@link #enter()}, when that connection is closed or fails to open. */
        void leave();
    }

    private static final ConcurrentHashMap<Path, Gate> GATES = new ConcurrentHashMap<>();

    private DatabaseGate() { }

    /** Every connection to this file opened from now on passes through {@code gate}. */
    public static void install(Path databaseFile, Gate gate) {
        GATES.put(key(databaseFile), gate);
    }

    /** Removes {@code gate}, if it is still the one installed for this file. */
    public static void remove(Path databaseFile, Gate gate) {
        GATES.remove(key(databaseFile), gate);
    }

    static Gate forFile(Path databaseFile) {
        return GATES.get(key(databaseFile));
    }

    private static Path key(Path databaseFile) {
        return databaseFile.toAbsolutePath().normalize();
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

package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.persistence.DatabaseGate;
import cm.sji.innova.desktop.shell.FolderMaintenanceException.Reason;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OFF-03a1: who is using a shop's data folder. See "Folder ownership and maintenance" in
 * {@code docs/design/BACKUP_RESTORE.md}.
 *
 * <ul>
 *   <li><b>Every running Cella holds a shared lock</b> on {@value #LOCK_FILE} in its data folder, from
 *       start-up until it exits, even while it is idle. Several Cellas can hold it at once.</li>
 *   <li><b>Maintenance (backup, restore) needs the folder alone.</b> It first pauses this process's
 *       database work: new connections wait, and it waits for the open ones to close. It then gives up
 *       this process's shared lock and tries to take an exclusive one. If another Cella holds the folder,
 *       it takes its shared lock back and refuses: nothing is touched.</li>
 *   <li><b>One coordinator per folder per process.</b> Every window in this process shares its single
 *       file handle: on Linux, closing any second handle to the file would drop this process's lock.</li>
 * </ul>
 *
 * <p>Locks are taken in one order: maintenance, then the stock, checkout and import leases. Never begin
 * maintenance while holding one of those. Nothing here waits on the Swing event thread except a
 * connection opened there during maintenance; Cella opens connections in background workers.
 */
public final class DataFolderCoordinator {
    public static final String LOCK_FILE = ".cella-maintenance.lock";

    private static final ConcurrentHashMap<Path, DataFolderCoordinator> BY_FOLDER = new ConcurrentHashMap<>();

    /** The coordinator for this folder in this process. */
    public static DataFolderCoordinator forFolder(LocalAppData location) throws IOException {
        Files.createDirectories(location.directory());
        Path folder = location.directory().toRealPath();
        return BY_FOLDER.computeIfAbsent(folder, real -> new DataFolderCoordinator(real, location.databaseFile()));
    }

    private final Path folder;
    private final Path databaseFile;
    private final DatabaseGate.Gate gate = new Gate();

    // All fields below are guarded by "this".
    private FileChannel channel;
    private FileLock lock;
    private int members;
    private int openConnections;
    /** The thread running maintenance; other threads' new connections wait while it is set. */
    private Thread maintainer;
    /** Set while this process waits to get its shared lock back: every connection waits too. */
    private boolean blocked;

    private DataFolderCoordinator(Path folder, Path databaseFile) {
        this.folder = folder;
        this.databaseFile = databaseFile;
    }

    /** One window's (or one start-up's) use of the folder. Closing it lets go; the last one releases the lock. */
    public final class Membership implements AutoCloseable {
        private boolean closed;

        @Override public void close() throws IOException {
            synchronized (DataFolderCoordinator.this) {
                if (closed) return;
                closed = true;
                leave();
            }
        }
    }

    /**
     * Joins the folder: the first member in this process takes the shared lock and installs the database
     * gate. Refused while another Cella is making a backup or restore of this shop.
     */
    public synchronized Membership join() throws IOException {
        if (members == 0) {
            FileChannel opened = FileChannel.open(folder.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock shared;
            try {
                shared = tryLock(opened, true);
                if (shared == null) {
                    throw new FolderMaintenanceException(Reason.IN_MAINTENANCE,
                            "Another Cella is making a backup or restore of this shop. Wait until it finishes, then click Retry.");
                }
                if (!shared.isShared()) {
                    shared.release();
                    throw new FolderMaintenanceException(Reason.UNSUPPORTED,
                            "This computer cannot share the lock on the shop folder, so Cella cannot safely open it here.");
                }
            } catch (IOException | RuntimeException failure) {
                try { opened.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
            channel = opened;
            lock = shared;
            DatabaseGate.install(databaseFile, gate);
        }
        members++;
        return new Membership();
    }

    private void leave() throws IOException {
        if (--members > 0) return;
        if (maintainer != null) throw new IllegalStateException("The folder is left while maintenance runs");
        DatabaseGate.remove(databaseFile, gate);
        try {
            if (lock != null) lock.release();
        } finally {
            lock = null;
            try { channel.close(); } finally { channel = null; }
        }
    }

    /** Folder maintenance in progress. Closing it gives the folder back to normal use. */
    public final class Maintenance implements AutoCloseable {
        private boolean closed;

        @Override public void close() throws IOException {
            synchronized (DataFolderCoordinator.this) {
                if (closed) return;
                closed = true;
                try {
                    lock.release();
                } finally {
                    lock = null;
                    resumeShared();
                }
            }
        }
    }

    /**
     * Takes the folder for a backup or restore. First waits up to {@code drain} for this process's open
     * connections to close, while new ones wait; then swaps the shared lock for an exclusive one.
     * Call it off the Swing event thread, from a member, holding no stock, checkout or import lease.
     */
    public synchronized Maintenance beginMaintenance(Duration drain) throws IOException {
        if (members == 0) throw new IllegalStateException("Join the folder before maintenance");
        if (maintainer != null || blocked) {
            throw new FolderMaintenanceException(Reason.ALREADY_RUNNING, "A backup or restore is already running in this Cella.");
        }
        maintainer = Thread.currentThread();
        long deadline = System.nanoTime() + drain.toNanos();
        try {
            while (openConnections > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new FolderMaintenanceException(Reason.STILL_WORKING,
                            "Cella is still saving something. Try again in a moment.");
                }
                wait(Math.max(1, left / 1_000_000));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            maintainer = null;
            notifyAll();
            throw new FolderMaintenanceException(Reason.STILL_WORKING, "The backup or restore was cancelled.");
        } catch (FolderMaintenanceException tooLong) {
            maintainer = null;
            notifyAll();
            throw tooLong;
        }
        // This process is quiet. Swapping the locks is not atomic: another Cella may slip in between.
        FileLock exclusive;
        try {
            lock.release();
            lock = null;
            exclusive = tryLock(channel, false);
        } catch (IOException | RuntimeException failure) {
            if (lock == null) resumeShared();
            else { maintainer = null; notifyAll(); }
            throw failure;
        }
        if (exclusive == null) {
            resumeShared();
            throw new FolderMaintenanceException(Reason.OTHER_INSTANCE,
                    "Another Cella has this shop open. Close it, then try again.");
        }
        lock = exclusive;
        return new Maintenance();
    }

    /** True while this process holds the folder exclusively. */
    public synchronized boolean inMaintenance() {
        return lock != null && !lock.isShared();
    }

    /**
     * Takes the shared lock back and reopens the gate. If another Cella took the folder in the gap, every
     * connection keeps waiting until a background thread gets the shared lock again.
     */
    private void resumeShared() {
        maintainer = null;
        FileLock shared = null;
        try {
            shared = tryLock(channel, true);
        } catch (IOException ignored) {
            // retried below
        }
        if (shared != null) {
            lock = shared;
            blocked = false;
            notifyAll();
            return;
        }
        blocked = true;
        FileChannel waitingOn = channel;
        Thread waiter = new Thread(() -> {
            while (waitingOn.isOpen()) {
                try {
                    FileLock again = waitingOn.lock(0, Long.MAX_VALUE, true);
                    synchronized (DataFolderCoordinator.this) {
                        lock = again;
                        blocked = false;
                        DataFolderCoordinator.this.notifyAll();
                    }
                    return;
                } catch (IOException | OverlappingFileLockException retry) {
                    try { Thread.sleep(200); } catch (InterruptedException stop) { return; }
                }
            }
        }, "cella-folder-shared-lock");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static FileLock tryLock(FileChannel channel, boolean shared) throws IOException {
        try {
            return channel.tryLock(0, Long.MAX_VALUE, shared);
        } catch (OverlappingFileLockException heldInThisProcess) {
            return null;
        }
    }

    /** The database gate: connections wait during maintenance (except the maintainer's own) and are counted. */
    private final class Gate implements DatabaseGate.Gate {
        @Override public void enter() throws SQLException {
            synchronized (DataFolderCoordinator.this) {
                try {
                    while (blocked || (maintainer != null && maintainer != Thread.currentThread())) {
                        DataFolderCoordinator.this.wait();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while a backup or restore was running", interrupted);
                }
                openConnections++;
            }
        }

        @Override public void leave() {
            synchronized (DataFolderCoordinator.this) {
                openConnections--;
                DataFolderCoordinator.this.notifyAll();
            }
        }
    }
}

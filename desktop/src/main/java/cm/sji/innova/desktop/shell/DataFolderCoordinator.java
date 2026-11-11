package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.persistence.DatabaseGate;
import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.desktop.catalog.importing.PendingImportStore;
import cm.sji.innova.desktop.sales.CheckoutFileLease;
import cm.sji.innova.desktop.sales.PendingCheckoutStore;
import cm.sji.innova.desktop.shell.FolderMaintenanceException.Reason;
import cm.sji.innova.desktop.stock.FilePendingReceiptStore;
import cm.sji.innova.desktop.stock.PendingReceiptFileLease;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;

/**
 * OFF-03a1: who is using a shop's data folder. See "Folder ownership and maintenance" in
 * {@code docs/design/BACKUP_RESTORE.md}. It coordinates; it does not make backups.
 *
 * <ul>
 *   <li><b>Every running Cella holds a shared lock</b> on {@value #LOCK_FILE} in its data folder, from
 *       start-up until it exits, even while it is idle. Several Cellas can hold it at once.</li>
 *   <li><b>All database and file work passes the folder's gate:</b> every database connection for its
 *       whole life, and photo changes for their whole duration. Saved receipts, corrections, cash sales
 *       and imports are covered by their operation leases, which maintenance takes.</li>
 *   <li><b>Maintenance (backup, restore) needs the folder alone.</b> It pauses new work, waits for the
 *       work already running, swaps this process's shared lock for an exclusive one, then takes the
 *       stock, checkout and import leases, in that order. If another Cella is open, or an operation is
 *       running, it puts everything back and refuses: nothing is touched.</li>
 *   <li><b>Maintenance uses the database only through {@link Maintenance#openDatabase()}.</b> Any other
 *       connection waits, including one opened by mistake on the maintenance thread, which is refused.</li>
 *   <li><b>Nothing waits on the Swing event thread:</b> maintenance cannot start there, and a connection
 *       opened there during maintenance is refused at once.</li>
 *   <li><b>One coordinator per folder per process.</b> Every window shares its single lock-file handle:
 *       on Linux, closing any second handle to the file would drop this process's lock.</li>
 * </ul>
 */
public final class DataFolderCoordinator {
    public static final String LOCK_FILE = ".cella-maintenance.lock";

    private static final ConcurrentHashMap<Path, DataFolderCoordinator> BY_FOLDER = new ConcurrentHashMap<>();

    /** The coordinator for this folder in this process. */
    public static DataFolderCoordinator forFolder(LocalAppData location) throws IOException {
        Files.createDirectories(location.directory());
        Path folder = location.directory().toRealPath();
        return BY_FOLDER.computeIfAbsent(folder, real -> new DataFolderCoordinator(real, location));
    }

    private final Path folder;
    private final LocalAppData location;
    private final Gate gate = new Gate();
    /** How much file work (photos) the current thread is inside; its own connections then never wait. */
    private final ThreadLocal<int[]> workDepth = ThreadLocal.withInitial(() -> new int[1]);

    // All fields below are guarded by "this".
    private FileChannel channel;
    private FileLock lock;
    private DatabaseGate.Pass pass;
    private int members;
    /** Open connections and running file work in this process. */
    private int active;
    /** The thread running maintenance; while set, all other work waits. */
    private Thread maintainer;
    /** Set while this process waits to get its shared lock back: all work waits too. */
    private boolean blocked;

    private DataFolderCoordinator(Path folder, LocalAppData location) {
        this.folder = folder;
        this.location = location;
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
     * Joins the folder: the first member in this process takes the shared lock and installs the gate.
     * Refused while another Cella is making a backup or restore of this shop.
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
            pass = DatabaseGate.install(location.databaseFile(), gate);
        }
        members++;
        return new Membership();
    }

    private void leave() throws IOException {
        if (--members > 0) return;
        if (maintainer != null) throw new IllegalStateException("The folder is left while maintenance runs");
        DatabaseGate.remove(location.databaseFile(), gate);
        pass = null;
        try {
            if (lock != null) lock.release();
        } finally {
            lock = null;
            try { channel.close(); } finally { channel = null; }
        }
    }

    /** Folder maintenance in progress. Close it on the thread that began it, to give the folder back. */
    public final class Maintenance implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final List<AutoCloseable> leases;
        private boolean closed;

        private Maintenance(List<AutoCloseable> leases) { this.leases = leases; }

        /** A connection for the maintenance itself: the only way to the database while it runs. */
        public Connection openDatabase() throws SQLException {
            DatabaseGate.Pass own;
            synchronized (DataFolderCoordinator.this) {
                if (closed) throw new IllegalStateException("Maintenance has ended");
                own = pass;
            }
            return new SqliteDatabase(location.databaseFile()).open(own);
        }

        @Override public void close() throws IOException {
            synchronized (DataFolderCoordinator.this) {
                if (closed) return;
                if (Thread.currentThread() != owner) throw new IllegalStateException("Close maintenance on the thread that began it");
                closed = true;
                Exception failure = closeAll(leases);
                try {
                    lock.release();
                } finally {
                    lock = null;
                    resumeShared();
                }
                if (failure instanceof IOException io) throw io;
                if (failure != null) throw new IOException("Could not release an operation lease", failure);
            }
        }
    }

    /**
     * Takes the folder for a backup or restore. Pauses new work, waits up to {@code drain} for the work
     * already running, swaps the shared lock for an exclusive one, then takes the operation leases. Call
     * it from a member, off the Swing event thread, holding no stock, checkout or import lease.
     */
    public synchronized Maintenance beginMaintenance(Duration drain) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Never begin maintenance on the Swing event thread");
        if (members == 0) throw new IllegalStateException("Join the folder before maintenance");
        if (maintainer != null || blocked) {
            throw new FolderMaintenanceException(Reason.ALREADY_RUNNING, "A backup or restore is already running in this Cella.");
        }
        if (workDepth.get()[0] > 0) throw new IllegalStateException("Never begin maintenance from inside file work");
        maintainer = Thread.currentThread();
        long deadline = System.nanoTime() + drain.toNanos();
        try {
            while (active > 0) {
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

        // Then the operation leases, in the agreed order: stock, checkout, import.
        List<AutoCloseable> leases = new ArrayList<>();
        List<String> busy = new ArrayList<>();
        try {
            try {
                leases.add(PendingReceiptFileLease.acquire(location.directory().resolve("pending-stock-receipt.properties")));
            } catch (FilePendingReceiptStore.BusyException inUse) {
                busy.add("stock");
            }
            try {
                leases.add(CheckoutFileLease.acquire(location.pendingCheckoutFile()));
            } catch (PendingCheckoutStore.BusyException inUse) {
                busy.add("sales");
            }
            try {
                leases.add(PendingImportStore.holdFolder(location.directory()));
            } catch (PendingImportStore.BusyException inUse) {
                busy.add("import");
            }
        } catch (IOException | RuntimeException failure) {
            closeAll(leases);
            giveBackExclusive();
            throw failure;
        }
        if (!busy.isEmpty()) {
            closeAll(leases);
            giveBackExclusive();
            throw new FolderMaintenanceException(Reason.OPERATION_RUNNING,
                    "Finish the operation in progress first (" + String.join(", ", busy) + "), then try again.");
        }
        return new Maintenance(leases);
    }

    /** True while this process holds the folder exclusively. */
    public synchronized boolean inMaintenance() {
        return lock != null && !lock.isShared();
    }

    private void giveBackExclusive() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // the shared lock below replaces it in any case
        } finally {
            lock = null;
            resumeShared();
        }
    }

    private static Exception closeAll(List<AutoCloseable> leases) {
        Exception first = null;
        for (int i = leases.size() - 1; i >= 0; i--) {
            try {
                leases.get(i).close();
            } catch (Exception failure) {
                if (first == null) first = failure; else first.addSuppressed(failure);
            }
        }
        return first;
    }

    /**
     * Takes the shared lock back and reopens the gate. If another Cella took the folder in the gap, all
     * work keeps waiting until a background thread gets the shared lock again.
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

    /** The gate: counts connections and file work, and makes new ones wait during maintenance. */
    private final class Gate implements DatabaseGate.Gate {
        @Override public void enter() throws SQLException {
            synchronized (DataFolderCoordinator.this) {
                // Inside file work that began before maintenance: its own connections never wait for it.
                if (workDepth.get()[0] == 0) waitUntilOpen();
                active++;
            }
        }

        @Override public void leave() {
            synchronized (DataFolderCoordinator.this) {
                active--;
                DataFolderCoordinator.this.notifyAll();
            }
        }

        @Override public void enterWork() throws SQLException {
            synchronized (DataFolderCoordinator.this) {
                if (workDepth.get()[0] == 0) waitUntilOpen();
                workDepth.get()[0]++;
                active++;
            }
        }

        @Override public void leaveWork() {
            synchronized (DataFolderCoordinator.this) {
                workDepth.get()[0]--;
                active--;
                DataFolderCoordinator.this.notifyAll();
            }
        }

        private void waitUntilOpen() throws SQLException {
            try {
                while (blocked || maintainer != null) {
                    if (Thread.currentThread() == maintainer) {
                        throw new SQLException("During maintenance, use Maintenance.openDatabase()");
                    }
                    if (SwingUtilities.isEventDispatchThread()) {
                        throw new SQLException("Cella is busy with a backup or restore. Try again in a moment.");
                    }
                    DataFolderCoordinator.this.wait();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while a backup or restore was running", interrupted);
            }
        }
    }
}

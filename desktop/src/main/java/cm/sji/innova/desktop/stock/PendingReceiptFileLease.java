package cm.sji.innova.desktop.stock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes pending-receipt file operations within this JVM and across JVM processes. */
final class PendingReceiptFileLease implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final ReentrantLock localLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private PendingReceiptFileLease(ReentrantLock localLock, FileChannel channel, FileLock fileLock) {
        this.localLock = localLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static PendingReceiptFileLease acquire(Path pendingFile) throws IOException {
        Path parent = pendingFile.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Pending receipt path has no parent directory");
        Files.createDirectories(parent);
        Path lockPath = parent.toRealPath().resolve(".pending-stock-receipt.lock");
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        if (!local.tryLock()) throw new FilePendingReceiptStore.BusyException();

        if (local.getHoldCount() > 1) return new PendingReceiptFileLease(local, null, null);

        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw new FilePendingReceiptStore.BusyException(busy);
            }
            if (lock == null) throw new FilePendingReceiptStore.BusyException();
            return new PendingReceiptFileLease(local, channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            local.unlock();
            if (failure instanceof IOException io) throw io;
            throw new FilePendingReceiptStore.BusyException(failure);
        }
    }

    @Override public void close() throws IOException {
        try {
            if (fileLock != null) fileLock.release();
        } finally {
            try { if (channel != null) channel.close(); }
            finally { localLock.unlock(); }
        }
    }
}

package cm.sji.innova.core.product.photo;

import cm.sji.innova.core.persistence.DatabaseGate;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes managed-file mutations and cleanup across service instances and JVMs. It also enters the
 * data folder's gate first (OFF-03a1): a backup or restore never runs while photo files are being
 * changed, and photo changes wait while one runs.
 */
final class PhotoStoreLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();
    private final ReentrantLock local;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final DatabaseGate.Work folderWork;

    private PhotoStoreLock(ReentrantLock local, FileChannel channel, FileLock fileLock, DatabaseGate.Work folderWork) {
        this.local = local; this.channel = channel; this.fileLock = fileLock; this.folderWork = folderWork;
    }

    static PhotoStoreLock acquire(Path directory) throws IOException, SQLException {
        Files.createDirectories(directory);
        Path key = directory.toRealPath();
        ReentrantLock local = LOCAL.computeIfAbsent(key, ignored -> new ReentrantLock());
        if (local.isHeldByCurrentThread()) throw new IOException("Nested photo-store mutation is not supported");
        DatabaseGate.Work work = DatabaseGate.enterFolder(directory);
        local.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key.resolve(".photo-store.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return new PhotoStoreLock(local, channel, channel.lock(), work);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            local.unlock();
            work.close();
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        try { fileLock.release(); }
        finally { try { channel.close(); } finally { try { local.unlock(); } finally { folderWork.close(); } } }
    }
}

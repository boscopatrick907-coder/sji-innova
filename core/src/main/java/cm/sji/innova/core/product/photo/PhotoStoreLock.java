package cm.sji.innova.core.product.photo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes managed-file mutations and cleanup across service instances and JVMs. */
final class PhotoStoreLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();
    private final ReentrantLock local;
    private final FileChannel channel;
    private final FileLock fileLock;

    private PhotoStoreLock(ReentrantLock local, FileChannel channel, FileLock fileLock) {
        this.local = local; this.channel = channel; this.fileLock = fileLock;
    }

    static PhotoStoreLock acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path key = directory.toRealPath();
        ReentrantLock local = LOCAL.computeIfAbsent(key, ignored -> new ReentrantLock());
        if (local.isHeldByCurrentThread()) throw new IOException("Nested photo-store mutation is not supported");
        local.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key.resolve(".photo-store.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return new PhotoStoreLock(local, channel, channel.lock());
        } catch (IOException | RuntimeException failure) {
            if (channel != null) try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            local.unlock();
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        try { fileLock.release(); }
        finally { try { channel.close(); } finally { local.unlock(); } }
    }
}

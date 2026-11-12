package cm.sji.innova.desktop.evidence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One decision written at a time per folder, across threads and Cella processes. Inside one process only
 * one thread at a time opens the lock file: on Linux, closing a second handle would drop the first's lock.
 */
final class DecisionLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();
    private final ReentrantLock local;
    private final FileChannel channel;
    private final FileLock lock;

    private DecisionLock(ReentrantLock local, FileChannel channel, FileLock lock) {
        this.local = local; this.channel = channel; this.lock = lock;
    }

    static DecisionLock acquire(Path decisions) throws IOException {
        Files.createDirectories(decisions);
        Path key = decisions.toRealPath();
        ReentrantLock local = LOCAL.computeIfAbsent(key, ignored -> new ReentrantLock());
        local.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key.resolve(".decisions.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return new DecisionLock(local, channel, channel.lock());
        } catch (IOException | RuntimeException failure) {
            if (channel != null) try { channel.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
            local.unlock();
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); }
        finally { try { channel.close(); } finally { local.unlock(); } }
    }
}

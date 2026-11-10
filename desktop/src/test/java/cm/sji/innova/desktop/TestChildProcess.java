package cm.sji.innova.desktop;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Test-only ownership of a child and its output reader. Always kill before awaiting the reader. */
public final class TestChildProcess implements AutoCloseable {
    private final Process child;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "test-child-output");
        thread.setDaemon(true); // A failed cleanup must not strand the test JVM.
        return thread;
    });

    public TestChildProcess(Process child) { this.child = child; }

    public <T> Future<T> readOutput(Callable<T> task) { return reader.submit(task); }

    @Override public void close() throws Exception {
        // ExecutorService.close() first would wait forever on readLine when the child is silent.
        child.destroyForcibly();
        try {
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("Test child did not terminate after forced shutdown: " + child.pid());
            }
            // Once the child is gone, its pipe reaches EOF and readLine can finish.
            child.getInputStream().close();
            child.getErrorStream().close();
            child.getOutputStream().close();
        } finally {
            reader.shutdownNow();
        }
        if (!reader.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IOException("Test child output reader did not terminate");
        }
    }
}

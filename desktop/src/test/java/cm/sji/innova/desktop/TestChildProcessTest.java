package cm.sji.innova.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestChildProcessTest {
    @TempDir Path directory;

    /** Holds a real file lease and stays alive without printing the expected second marker. */
    public static class SilentChild {
        public static void main(String[] args) throws Exception {
            try (var file = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = file.lock()) {
                System.out.println("READY");
                System.out.flush();
                new CountDownLatch(1).await();
            }
        }
    }

    private Process start(Path lock) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SilentChild.class.getName(), lock.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private void assertReleased(Process child, Path path) throws Exception {
        assertFalse(child.isAlive(), "Cleanup must leave no running child");
        // Windows may release a dead process's file locks just after waitFor returns.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try (var channel = FileChannel.open(path, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock != null) return;
            }
            assertTrue(System.nanoTime() < deadline, "The child's file lease must be released");
            Thread.sleep(20);
        }
    }

    @Test void timeoutKillsSilentChildBeforeWaitingForItsReader() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Path lock = directory.resolve("timeout.lock");
            Process child = start(lock);
            Future<String> output;
            try {
                assertThrows(TimeoutException.class, () -> {
                    try (var process = new TestChildProcess(child)) {
                        var reader = new BufferedReader(new InputStreamReader(child.getInputStream()));
                        assertEquals("READY", process.readOutput(reader::readLine).get(10, TimeUnit.SECONDS));
                        outputReader = process.readOutput(reader::readLine);
                        outputReader.get(100, TimeUnit.MILLISECONDS);
                    }
                });
                output = outputReader;
                assertNotNull(output);
                assertTrue(output.isDone(), "The blocked output reader must finish during cleanup");
                assertReleased(child, lock);
            } finally {
                // Protect the runner even if a regression causes the outer deadline to fail.
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        });
    }

    private Future<String> outputReader;

    @Test void assertionFailureStillKillsChildAndReleasesItsLease() throws Exception {
        Path lock = directory.resolve("assertion.lock");
        Process child = start(lock);
        try {
            AssertionError failure = assertThrows(AssertionError.class, () -> {
                try (var process = new TestChildProcess(child)) {
                    var reader = new BufferedReader(new InputStreamReader(child.getInputStream()));
                    assertEquals("READY", process.readOutput(reader::readLine).get(10, TimeUnit.SECONDS));
                    fail("Original assertion");
                }
            });
            assertEquals("Original assertion", failure.getMessage());
            assertReleased(child, lock);
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }
}

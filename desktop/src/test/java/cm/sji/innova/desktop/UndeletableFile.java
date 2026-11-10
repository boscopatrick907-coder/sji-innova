package cm.sji.innova.desktop;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;

/**
 * Test only: makes one existing file impossible to delete until {@link #close()}, as a busy antivirus
 * scan or a locked folder would. The file can still be read.
 *
 * <p>Windows refuses to delete a file that another handle opened without delete sharing, which plain
 * {@code java.io} streams do. Linux and macOS refuse when the folder is not writable, so the whole
 * folder becomes read-only: keep such a file in a folder of its own, never next to the SQLite database.
 * Call {@link #assumeSupported} first: where the protection cannot work (for example as root), the test
 * is skipped rather than passing for the wrong reason.
 */
public final class UndeletableFile implements AutoCloseable {
    private final Path folder;
    private final Path probe;
    private final FileInputStream heldFile;
    private final FileInputStream heldProbe;
    private final Set<PosixFilePermission> permissions;

    /** Protects the file, then proves it on a sibling file; throws if this system still allows deleting. */
    public UndeletableFile(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        folder = absolute.getParent();
        probe = Files.createTempFile(folder, "undeletable-probe-", ".tmp");
        heldFile = new FileInputStream(absolute.toFile());
        heldProbe = new FileInputStream(probe.toFile());
        PosixFileAttributeView posix = Files.getFileAttributeView(folder, PosixFileAttributeView.class);
        permissions = posix == null ? null : posix.readAttributes().permissions();
        if (posix != null) posix.setPermissions(EnumSet.of(OWNER_READ, OWNER_EXECUTE));
        boolean probeDeleted;
        try {
            Files.delete(probe);
            probeDeleted = true;
        } catch (IOException refused) {
            probeDeleted = false;
        }
        if (probeDeleted) {
            close();
            throw new UnsupportedOperationException(
                    "This system lets the test delete a protected file, so the failure cannot be simulated here");
        }
    }

    /** Skips the calling test where files in this folder cannot be protected from deletion. */
    public static void assumeSupported(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path sample = Files.createTempFile(folder, "undeletable-sample-", ".tmp");
        try (UndeletableFile ignored = new UndeletableFile(sample)) {
            // Protected, and proven by the constructor.
        } catch (UnsupportedOperationException notHere) {
            Assumptions.abort(notHere.getMessage());
        } finally {
            Files.deleteIfExists(sample);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (permissions != null) Files.getFileAttributeView(folder, PosixFileAttributeView.class).setPermissions(permissions);
        } finally {
            try {
                heldProbe.close();
            } finally {
                heldFile.close();
                Files.deleteIfExists(probe);
            }
        }
    }
}

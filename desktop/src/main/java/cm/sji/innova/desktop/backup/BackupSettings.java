package cm.sji.innova.desktop.backup;

import cm.sji.innova.core.persistence.DatabaseGate;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import javax.swing.SwingUtilities;

/**
 * OFF-03c4: this computer's backup settings, kept in {@code backup-settings.properties} in the data folder.
 * Today it holds the folder the person chose for backups, so Settings offers it again after a restart.
 *
 * <p>The file belongs to this computer, like its device ID: a backup does not contain it, and a restore
 * leaves it in place. It is read and written through the folder's gate, so it waits while a backup or
 * restore holds the folder. A file that cannot be read counts as no setting: Settings then asks again.
 */
public final class BackupSettings {
    public static final String FILE = "backup-settings.properties";
    private static final String FOLDER = "folder";

    private final LocalAppData location;

    public BackupSettings(LocalAppData location) {
        this.location = Objects.requireNonNull(location, "location");
    }

    /** The folder chosen for backups, if one was remembered and can be read. */
    public Optional<Path> folder() throws IOException {
        refuseTheSwingThread();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            String folder = read().getProperty(FOLDER);
            if (folder == null || folder.isBlank()) return Optional.empty();
            Path path = Path.of(folder);
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException unreadable) {
            return Optional.empty();
        } catch (SQLException paused) {
            throw new IOException("Cannot read the backup settings while the folder is paused", paused);
        }
    }

    /** Remembers {@code folder} for the next backups. Other settings in the file are kept. */
    public void rememberFolder(Path folder) throws IOException {
        refuseTheSwingThread();
        Path absolute = folder.toAbsolutePath().normalize();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            Properties settings = read();
            settings.setProperty("format", "1");
            settings.setProperty(FOLDER, absolute.toString());
            write(settings);
        } catch (SQLException paused) {
            throw new IOException("Cannot save the backup settings while the folder is paused", paused);
        }
    }

    /** The file's settings; none if it is missing or cannot be read. */
    private Properties read() {
        Properties settings = new Properties();
        Path file = location.directory().resolve(FILE);
        if (!Files.isRegularFile(file)) return settings;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            settings.load(reader);
            return settings;
        } catch (IOException | IllegalArgumentException unreadable) {
            return new Properties();
        }
    }

    /** Replaces the file in one step, so a crash leaves the old file or the new one, never half of one. */
    private void write(Properties settings) throws IOException {
        Path file = location.directory().resolve(FILE);
        Path temporary = Files.createTempFile(location.directory(), ".backup-settings-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                settings.store(writer, "Cella: this computer's backup settings");
                writer.flush();
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void refuseTheSwingThread() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Never read or write backup settings on the Swing event thread");
    }
}

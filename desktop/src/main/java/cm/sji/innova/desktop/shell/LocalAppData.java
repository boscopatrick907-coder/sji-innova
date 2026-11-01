package cm.sji.innova.desktop.shell;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Where Cella keeps its files on this computer: one folder, with a fixed name for each file.
 *
 * <p>Default folder: {@code %APPDATA%\Cella} on Windows, {@code ~/.cella} on other systems.
 * Tests and developers can choose another folder (see {@link StartupOptions}).
 */
public record LocalAppData(Path directory) {
    public LocalAppData {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public Path databaseFile() { return directory.resolve("cella.db"); }

    public Path shopIdFile() { return directory.resolve("shop-id.txt"); }

    /** The folder used when the user did not choose one. */
    public static Path defaultDirectory(String osName, Map<String, String> environment, Path userHome) {
        if (osName.toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String appData = environment.get("APPDATA");
            Path base = appData == null || appData.isBlank()
                    ? userHome.resolve("AppData").resolve("Roaming") : Path.of(appData);
            return base.resolve("Cella");
        }
        return userHome.resolve(".cella");
    }

    public static LocalAppData forOptions(StartupOptions options) {
        Path dir = options.dataDir() != null ? options.dataDir()
                : defaultDirectory(System.getProperty("os.name"), System.getenv(),
                        Path.of(System.getProperty("user.home")));
        return new LocalAppData(dir);
    }
}

package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalAppDataTest {
    @Test
    void windowsDefaultIsInsideAppData() {
        Path dir = LocalAppData.defaultDirectory("Windows 11", Map.of("APPDATA", "C:\\Users\\a\\AppData\\Roaming"),
                Path.of("home"));
        assertEquals(Path.of("C:\\Users\\a\\AppData\\Roaming").resolve("Cella"), dir);
    }

    @Test
    void windowsWithoutAppDataFallsBackToTheUserFolder() {
        Path dir = LocalAppData.defaultDirectory("Windows 10", Map.of(), Path.of("home"));
        assertEquals(Path.of("home", "AppData", "Roaming", "Cella"), dir);
    }

    @Test
    void otherSystemsUseAHiddenFolderInHome() {
        assertEquals(Path.of("home", ".cella"), LocalAppData.defaultDirectory("Linux", Map.of(), Path.of("home")));
    }

    @Test
    void filesHaveFixedNamesInsideTheFolder() {
        LocalAppData data = new LocalAppData(Path.of("data"));
        assertEquals(data.directory().resolve("cella.db"), data.databaseFile());
        assertEquals(data.directory().resolve("shop-id.txt"), data.shopIdFile());
    }
}

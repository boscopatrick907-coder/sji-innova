package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLoggingTest {
    @TempDir Path folder;

    @Test
    void errorsAreWrittenToTheLogFileInTheDataFolder() throws Exception {
        LocalAppData location = new LocalAppData(folder.resolve("cella"));
        Handler handler = AppLogging.install(location);
        try {
            assertNotNull(handler);
            Logger.getLogger(ShellPanel.class.getName())
                    .log(Level.SEVERE, "Start-up failed", new IOException("disk is full"));
            handler.flush();
        } finally {
            AppLogging.uninstall(handler);   // releases the file so the temporary folder can be deleted
        }
        String log = Files.readString(location.logDirectory().resolve("cella-0.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("SEVERE"), log);
        assertTrue(log.contains("Start-up failed"), log);
        assertTrue(log.contains("java.io.IOException: disk is full"), log);
    }

    @Test
    void aLogFolderThatCannotBeCreatedDoesNotStopTheApplication() throws Exception {
        Path notAFolder = folder.resolve("cella");
        Files.writeString(notAFolder, "I am a file", StandardCharsets.UTF_8);
        assertNull(AppLogging.install(new LocalAppData(notAFolder)));
    }
}

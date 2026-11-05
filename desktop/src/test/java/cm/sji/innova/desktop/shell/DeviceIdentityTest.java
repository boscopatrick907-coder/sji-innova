package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceIdentityTest {
    @TempDir Path folder;

    @Test
    void createsTheIdOnceAndReusesItOnRestart() throws Exception {
        Path file = folder.resolve("nested").resolve("device-id.txt");
        UUID first = DeviceIdentity.loadOrCreate(file);
        UUID afterRestart = DeviceIdentity.loadOrCreate(file);
        assertEquals(first, afterRestart);
    }

    @Test
    void acceptsAFileWithBomAndExtraWhitespace() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = folder.resolve("device-id.txt");
        Files.writeString(file, "\uFEFF  " + id + "  \r\n", StandardCharsets.UTF_8);
        assertEquals(id, DeviceIdentity.loadOrCreate(file));
    }

    @Test
    void neverReplacesADamagedFile() throws Exception {
        Path file = folder.resolve("device-id.txt");
        Files.writeString(file, "not-a-uuid", StandardCharsets.UTF_8);
        IOException failure = assertThrows(IOException.class, () -> DeviceIdentity.loadOrCreate(file));
        assertEquals("not-a-uuid", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(failure.getMessage().contains("device identity file is damaged"), failure.getMessage());
    }
}

package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShopIdentityTest {
    @TempDir Path folder;

    @Test
    void createsTheIdOnceAndReusesItOnRestart() throws Exception {
        Path file = folder.resolve("nested").resolve("shop-id.txt");
        UUID first = ShopIdentity.loadOrCreate(file);
        UUID afterRestart = ShopIdentity.loadOrCreate(file);
        assertEquals(first, afterRestart);
    }

    @Test
    void acceptsAFileWithBomAndExtraWhitespace() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = folder.resolve("shop-id.txt");
        Files.writeString(file, "\uFEFF  " + id + "  \r\n", StandardCharsets.UTF_8);
        assertEquals(id, ShopIdentity.loadOrCreate(file));
    }

    @Test
    void neverReplacesADamagedFile() throws Exception {
        Path file = folder.resolve("shop-id.txt");
        Files.writeString(file, "not-a-uuid", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ShopIdentity.loadOrCreate(file));
        assertEquals("not-a-uuid", Files.readString(file, StandardCharsets.UTF_8));
    }
}

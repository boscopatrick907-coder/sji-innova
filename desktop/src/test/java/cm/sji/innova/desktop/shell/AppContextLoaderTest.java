package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppContextLoaderTest {
    @TempDir Path folder;

    @Test
    void firstStartCreatesFilesAndNoDemoProducts() throws Exception {
        LocalAppData location = new LocalAppData(folder.resolve("cella"));
        AppContext context = AppContextLoader.load(location);
        assertTrue(Files.exists(location.databaseFile()));
        assertTrue(Files.exists(location.shopIdFile()));
        assertTrue(context.products().list(context.shopId()).isEmpty());
    }

    @Test
    void restartReusesTheSameDatabaseAndShopIdAndKeepsProducts() throws Exception {
        LocalAppData location = new LocalAppData(folder.resolve("cella"));
        AppContext first = AppContextLoader.load(location);
        first.products().create(first.shopId(), "Rice 1 kg", 500);

        AppContext second = AppContextLoader.load(location);   // "restart"
        assertEquals(first.shopId(), second.shopId());
        assertEquals(first.location().databaseFile(), second.location().databaseFile());
        assertEquals(1, second.products().list(second.shopId()).size());
        assertEquals("Rice 1 kg", second.products().list(second.shopId()).get(0).name());
    }

    @Test
    void aDamagedShopIdFileStopsStartupAndIsLeftUntouched() throws Exception {
        LocalAppData location = new LocalAppData(folder.resolve("cella"));
        Files.createDirectories(location.directory());
        Files.writeString(location.shopIdFile(), "garbage", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> AppContextLoader.load(location));
        assertEquals("garbage", Files.readString(location.shopIdFile(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(location.databaseFile()), "no new database may be created next to a damaged identity");
    }

    @Test
    void aDataFolderThatIsReallyAFileIsReportedNotReplaced() throws Exception {
        Path notAFolder = folder.resolve("cella");
        Files.writeString(notAFolder, "I am a file", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> AppContextLoader.load(new LocalAppData(notAFolder)));
        assertEquals("I am a file", Files.readString(notAFolder, StandardCharsets.UTF_8));
    }

    @Test
    void aMissingShopIdNextToAnExistingDatabaseStopsStartup() throws Exception {
        LocalAppData location = new LocalAppData(folder.resolve("cella"));

        // 1. First start: save one product and remember the shop ID
        AppContext first = AppContextLoader.load(location);
        first.products().create(first.shopId(), "Rice 1 kg", 500);
        UUID oldId = first.shopId();

        // 2. Lose ONLY the ID file (the database stays)
        Files.delete(location.shopIdFile());

        // 3. The second start must refuse to start
        assertThrows(IOException.class, () -> AppContextLoader.load(location));

        // 4. Cella must NOT have invented a new ID file
        assertFalse(Files.exists(location.shopIdFile()));

        // 5. Put the old ID back: same shop, and the product is still there
        Files.writeString(location.shopIdFile(), oldId.toString(), StandardCharsets.UTF_8);
        AppContext recovered = AppContextLoader.load(location);
        assertEquals(oldId, recovered.shopId());
        assertEquals(1, recovered.products().list(recovered.shopId()).size());
    }
}
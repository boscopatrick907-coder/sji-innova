package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartupOptionsTest {
    @Test
    void usesDefaultsWhenNothingIsChosen() {
        StartupOptions options = StartupOptions.parse(new String[0], Map.of());
        assertNull(options.dataDir());
        assertEquals(StartupOptions.DEFAULT_LOCALE, options.locale());
    }

    @Test
    void readsOptionsFromTheCommandLine() {
        StartupOptions options = StartupOptions.parse(
                new String[] {"--data-dir", "some-folder", "--lang", "fr"}, Map.of());
        assertEquals(Path.of("some-folder"), options.dataDir());
        assertEquals(Locale.FRENCH, options.locale());
    }

    @Test
    void commandLineBeatsEnvironmentVariables() {
        StartupOptions options = StartupOptions.parse(new String[] {"--lang", "en"},
                Map.of("CELLA_LANG", "fr", "CELLA_DATA_DIR", "from-env"));
        assertEquals(Locale.ENGLISH, options.locale());
        assertEquals(Path.of("from-env"), options.dataDir());
    }

    @Test
    void rejectsUnknownOptionsMissingValuesAndUnknownLanguages() {
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--oops"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--lang"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--lang", "de"}, Map.of()));
    }
}

package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MessagesTest {
    @Test
    void everyShellKeyHasAText() {
        Messages messages = Messages.load(Locale.ENGLISH);
        for (String key : new FallbackMessages().keySet()) {
            assertFalse(messages.get(key, "x").isBlank(), key);
        }
        assertNotNull(messages.bundle());
    }

    @Test
    void placeholdersAreFilled() {
        Messages messages = Messages.load(Locale.ENGLISH);
        assertEquals(true, messages.get("shell.error.dataFolder", "C:\\data").contains("C:\\data"));
    }
}

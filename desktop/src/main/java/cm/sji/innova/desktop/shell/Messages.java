package cm.sji.innova.desktop.shell;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Looks up interface texts. It first uses the shared translation bundle
 * ({@value #BUNDLE_BASE}, owned by Pascal) and falls back to {@link FallbackMessages}
 * for any key that bundle does not have yet, so the window never shows a missing key.
 */
public final class Messages {
    public static final String BUNDLE_BASE = "cm.sji.innova.desktop.i18n.messages";

    private final ResourceBundle shared;   // null until the translation files exist
    private final ResourceBundle fallback = new FallbackMessages();
    private final Locale locale;

    private Messages(ResourceBundle shared, Locale locale) {
        this.shared = shared;
        this.locale = locale;
    }

    public static Messages load(Locale locale) {
        ResourceBundle shared = null;
        try {
            shared = ResourceBundle.getBundle(BUNDLE_BASE, locale);
        } catch (MissingResourceException notThereYet) {
            // Expected until messages_en.properties / messages_fr.properties are merged.
        }
        return new Messages(shared, locale);
    }

    public String get(String key, Object... arguments) {
        String pattern = shared != null && shared.containsKey(key) ? shared.getString(key) : fallback.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    /** The bundle to hand to panels such as Willy's ProductPanel. */
    public ResourceBundle bundle() { return shared != null ? shared : fallback; }

    public boolean usingSharedBundle() { return shared != null; }
}

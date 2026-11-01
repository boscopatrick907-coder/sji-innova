package cm.sji.innova.desktop.shell;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The choices made when the program starts: where the data lives and which language is used.
 *
 * <p>Order of priority: command-line option, then environment variable, then the default.
 * <ul>
 *   <li>{@code --data-dir PATH} or the variable {@code CELLA_DATA_DIR}</li>
 *   <li>{@code --lang fr} / {@code --lang en} or the variable {@code CELLA_LANG}</li>
 * </ul>
 *
 * @param dataDir the folder chosen by the user, or {@code null} to use the default folder
 * @param locale  the interface language (English or French)
 */
public record StartupOptions(Path dataDir, Locale locale) {
    /** Language used when nothing is chosen. Change this one line when the team decides. */
    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    public static StartupOptions parse(String[] args, Map<String, String> environment) {
        String dir = blankToNull(environment.get("CELLA_DATA_DIR"));
        String lang = blankToNull(environment.get("CELLA_LANG"));
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dir = valueAfter(args, i++);
                case "--lang" -> lang = valueAfter(args, i++);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]
                        + ". Use --data-dir PATH and/or --lang fr|en.");
            }
        }
        return new StartupOptions(dir == null ? null : Path.of(dir), toLocale(lang));
    }

    private static String valueAfter(String[] args, int optionIndex) {
        if (optionIndex + 1 >= args.length) {
            throw new IllegalArgumentException("Option " + args[optionIndex] + " needs a value.");
        }
        return args[optionIndex + 1];
    }

    private static Locale toLocale(String code) {
        if (code == null) return DEFAULT_LOCALE;
        return switch (code.strip().toLowerCase(Locale.ROOT)) {
            case "en" -> Locale.ENGLISH;
            case "fr" -> Locale.FRENCH;
            default -> throw new IllegalArgumentException("Language must be fr or en, not: " + code);
        };
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}

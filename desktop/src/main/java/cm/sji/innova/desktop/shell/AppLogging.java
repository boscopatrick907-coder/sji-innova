package cm.sji.innova.desktop.shell;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Writes what Cella's code logs (java.util.logging) to a file in the data folder, as well as to
 * the console. A user who started Cella with a double-click sees no console, so the file is
 * where a developer finds the full error after "Cella could not start".
 *
 * <p>File: {@code logs/cella-0.log} inside the data folder; it rotates over 3 files of 1 MB.
 * A problem with the log file is reported on the console and never stops the application.
 */
public final class AppLogging {
    /** Parent of every Cella logger ({@code cm.sji.innova.desktop...}, {@code cm.sji.innova.core...}). */
    public static final String LOGGER_NAME = "cm.sji.innova";
    private static final int FILE_LIMIT_BYTES = 1_000_000;
    private static final int FILE_COUNT = 3;

    // Strong reference: java.util.logging only keeps loggers weakly, which would lose the handler.
    private static final Logger APP = Logger.getLogger(LOGGER_NAME);

    private AppLogging() { }

    /**
     * Adds the log file. Returns its handler so that tests can close it, or {@code null} when
     * the file could not be opened (Cella then logs to the console only).
     */
    public static Handler install(LocalAppData location) {
        try {
            Files.createDirectories(location.logDirectory());
            // In a FileHandler pattern %g is the rotation number, so a % in the folder name is doubled.
            String pattern = location.logDirectory().toString().replace("%", "%%") + "/cella-%g.log";
            FileHandler file = new FileHandler(pattern, FILE_LIMIT_BYTES, FILE_COUNT, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new LineFormatter());
            APP.addHandler(file);
            return file;
        } catch (IOException | RuntimeException failure) {
            APP.log(Level.WARNING, "The log file could not be opened; logging to the console only", failure);
            return null;
        }
    }

    /** Removes and closes a handler returned by {@link #install}, releasing the file. */
    public static void uninstall(Handler handler) {
        if (handler == null) return;
        APP.removeHandler(handler);
        handler.close();
    }

    /** One line per record, then the stack trace if there is one. */
    static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TIME =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            StringWriter text = new StringWriter();
            text.append(TIME.format(record.getInstant())).append(' ')
                    .append(record.getLevel().getName()).append(' ')
                    .append(record.getLoggerName()).append(": ")
                    .append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                record.getThrown().printStackTrace(new PrintWriter(text));
            }
            return text.toString();
        }
    }
}

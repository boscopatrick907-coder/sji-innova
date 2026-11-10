package cm.sji.innova.desktop.stock;

import java.io.IOException;

/**
 * A saved stock request whose file can be read but not understood: wrong format, a missing or invalid
 * value, or too large. Retrying cannot fix it, unlike a file that is only unavailable for a moment.
 */
public final class DamagedPendingFileException extends IOException {
    public DamagedPendingFileException(String message) { super(message); }
    public DamagedPendingFileException(String message, Throwable cause) { super(message, cause); }
}

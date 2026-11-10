package cm.sji.innova.desktop.stock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Durable storage contract for the one receipt awaiting service confirmation. */
public interface PendingReceiptStore {
    Optional<PendingReceipt> load() throws IOException;

    /** Saves before submission; a different existing operation must never be overwritten. */
    void save(PendingReceipt receipt) throws IOException;

    /** Removes the pending file only if it still belongs to the supplied operation UUID. */
    void clear(UUID expectedOperationId) throws IOException;

    Path file();

    /**
     * Renames a damaged file, keeping it next to the original, so it no longer blocks the screen.
     * Refuses a readable request. Returns the file's new path.
     */
    default Path setAsideDamaged() throws IOException {
        throw new IOException("This store has no file to set aside");
    }

    /**
     * Renames the file holding this readable request, keeping it next to the original, when the screen
     * cannot use it. Refuses any other request. Returns the file's new path.
     */
    default Path setAside(UUID expectedOperationId) throws IOException {
        throw new IOException("This store has no file to set aside");
    }
}

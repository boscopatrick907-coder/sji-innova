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
}

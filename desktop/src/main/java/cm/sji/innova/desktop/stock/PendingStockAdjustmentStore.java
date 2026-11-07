package cm.sji.innova.desktop.stock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Durable store for one outstanding signed stock adjustment. All I/O must run off the EDT. */
public interface PendingStockAdjustmentStore {
    Optional<PendingStockAdjustment> load() throws IOException;
    void save(PendingStockAdjustment adjustment) throws IOException;
    void clear(UUID expectedOperationId) throws IOException;
    Path file();
}

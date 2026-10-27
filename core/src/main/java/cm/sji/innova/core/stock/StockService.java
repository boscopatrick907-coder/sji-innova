package cm.sji.innova.core.stock;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Thread-safe local operations. Units are integer thousandths, never double. */
public final class StockService {
    private final StockRepository repository;

    public StockService(StockRepository repository) { this.repository = Objects.requireNonNull(repository); }

    public StockResult receive(UUID operationId, UUID shopId, UUID productId, UUID deviceId,
                               long quantityMilli, String reason) throws SQLException {
        return repository.record(new StockMovement(operationId, shopId, productId, deviceId,
                StockMovement.Type.DELIVERY, quantityMilli, reason, Instant.now()));
    }

    /** Signed change, not an absolute replacement count. Never edit an old movement. */
    public StockResult adjust(UUID operationId, UUID shopId, UUID productId, UUID deviceId,
                              long deltaMilli, String reason) throws SQLException {
        return repository.record(new StockMovement(operationId, shopId, productId, deviceId,
                StockMovement.Type.ADJUSTMENT, deltaMilli, reason, Instant.now()));
    }

    public StockLevel current(UUID shopId, UUID productId) throws SQLException {
        return repository.current(Objects.requireNonNull(shopId), Objects.requireNonNull(productId));
    }

    public List<StockLevel> list(UUID shopId) throws SQLException {
        return repository.list(Objects.requireNonNull(shopId));
    }

    public List<StockMovement> history(UUID shopId, UUID productId) throws SQLException {
        return repository.history(Objects.requireNonNull(shopId), Objects.requireNonNull(productId));
    }

    public StockLevel setMinimum(UUID shopId, UUID productId, long minimumMilli) throws SQLException {
        if (minimumMilli < 0) throw new IllegalArgumentException("Minimum stock must not be negative");
        return repository.setMinimum(Objects.requireNonNull(shopId), Objects.requireNonNull(productId), minimumMilli);
    }
}

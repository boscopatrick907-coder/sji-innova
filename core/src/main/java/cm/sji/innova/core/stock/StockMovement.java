package cm.sji.innova.core.stock;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** id is the client-generated operation ID: reuse it for retries only. */
public record StockMovement(UUID id, UUID shopId, UUID productId, UUID deviceId,
                            Type type, long quantityMilli, String reason, Instant createdAt) {
    public enum Type { DELIVERY, ADJUSTMENT }

    public StockMovement {
        Objects.requireNonNull(id, "operationId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        reason = Objects.requireNonNull(reason, "reason").strip();
        if (reason.isEmpty()) throw new IllegalArgumentException("A reason is required");
        if (quantityMilli == 0 || (type == Type.DELIVERY && quantityMilli < 0)) {
            throw new IllegalArgumentException("Delivery quantity must be positive; adjustment must be nonzero");
        }
    }

    /** Retry time may differ; business details must match exactly. */
    public boolean sameOperation(StockMovement other) {
        return id.equals(other.id) && shopId.equals(other.shopId) && productId.equals(other.productId)
                && deviceId.equals(other.deviceId) && type == other.type
                && quantityMilli == other.quantityMilli && reason.equals(other.reason);
    }
}

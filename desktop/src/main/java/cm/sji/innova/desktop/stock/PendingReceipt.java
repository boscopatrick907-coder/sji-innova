package cm.sji.innova.desktop.stock;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable details of a receipt that has not yet been confirmed.
 * The same operation ID and business details must be reused for retries.
 */
public record PendingReceipt(
        UUID operationId,
        UUID shopId,
        UUID productId,
        UUID deviceId,
        String unit,
        long quantityMilli,
        String reason
) {
    public PendingReceipt {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(deviceId, "deviceId");

        unit = Objects.requireNonNull(unit, "unit").strip();
        reason = Objects.requireNonNull(reason, "reason").strip();

        if (!unit.equals("piece")
                && !unit.equals("bag")
                && !unit.equals("kg")
                && !unit.equals("litre")) {
            throw new IllegalArgumentException("Unsupported stock unit: " + unit);
        }

        if (quantityMilli <= 0) {
            throw new IllegalArgumentException("Receipt quantity must be positive");
        }

        if ((unit.equals("piece") || unit.equals("bag"))
                && quantityMilli % 1000 != 0) {
            throw new IllegalArgumentException(
                    "Piece and bag quantities must be whole items"
            );
        }

        if (reason.isEmpty()) {
            throw new IllegalArgumentException("A receipt reason is required");
        }
    }
}
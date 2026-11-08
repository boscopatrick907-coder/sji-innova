package cm.sji.innova.desktop.stock;

import java.util.Objects;
import java.util.UUID;

/** Immutable signed stock adjustment retained until the service confirms it. */
public record PendingStockAdjustment(
        UUID operationId,
        UUID shopId,
        UUID productId,
        UUID deviceId,
        String unit,
        long deltaMilli,
        String reason) {
    public PendingStockAdjustment {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(deviceId, "deviceId");
        unit = Objects.requireNonNull(unit, "unit").strip();
        reason = Objects.requireNonNull(reason, "reason").strip();
        if (!unit.equals("piece") && !unit.equals("bag")
                && !unit.equals("kg") && !unit.equals("litre")) {
            throw new IllegalArgumentException("Unsupported stock unit: " + unit);
        }
        if (deltaMilli == 0 || deltaMilli == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Adjustment quantity is outside the supported range");
        }
        if ((unit.equals("piece") || unit.equals("bag")) && deltaMilli % 1000 != 0) {
            throw new IllegalArgumentException("Piece and bag adjustments must be whole items");
        }
        if (reason.isEmpty()) throw new IllegalArgumentException("An adjustment reason is required");
    }
}

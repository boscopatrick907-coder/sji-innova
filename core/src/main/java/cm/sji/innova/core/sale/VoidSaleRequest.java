package cm.sji.innova.core.sale;

import java.util.Objects;
import java.util.UUID;

/**
 * Normalized request to void a recorded cash sale.
 * Until authentication is implemented, {@code performedBy} is an explicit caller-supplied attribution ID.
 */
public record VoidSaleRequest(UUID operationId, UUID shopId, UUID saleId,
                              UUID deviceId, UUID performedBy, String reason) {
    public VoidSaleRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(performedBy, "performedBy");
        reason = Objects.requireNonNull(reason, "reason").strip();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("Void reason must not be blank");
        }
    }

    /** Verifies that an existing event with the same operation ID matches all normalized business details. */
    public void requireMatches(SaleEvent existing) {
        Objects.requireNonNull(existing, "existing");
        boolean matches = operationId.equals(existing.id())
                && existing.type() == SaleEvent.Type.VOIDED
                && shopId.equals(existing.shopId())
                && saleId.equals(existing.saleId())
                && deviceId.equals(existing.deviceId())
                && performedBy.equals(existing.performedBy())
                && reason.equals(existing.reason());
        if (!matches) {
            throw new IllegalArgumentException("Void operation ID was already used with different details");
        }
    }
}


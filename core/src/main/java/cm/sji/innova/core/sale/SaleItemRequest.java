package cm.sji.innova.core.sale;

import java.util.Objects;
import java.util.UUID;

public record SaleItemRequest(UUID productId, long quantityMilli, Long customUnitPrice) {
    public SaleItemRequest(UUID productId, long quantityMilli) {
        this(productId, quantityMilli, null);
    }

    public SaleItemRequest {
        Objects.requireNonNull(productId, "productId");
        if (quantityMilli <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (customUnitPrice != null && customUnitPrice < 0) {
            throw new IllegalArgumentException("Custom unit price cannot be negative");
        }
    }
}

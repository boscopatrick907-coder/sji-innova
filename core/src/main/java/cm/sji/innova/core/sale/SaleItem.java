package cm.sji.innova.core.sale;

import java.util.Objects;
import java.util.UUID;

public record SaleItem(UUID id, UUID saleId, UUID productId, String productName,
                       String unit, long quantityMilli, long unitPrice, long subtotal) {
    public SaleItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unit, "unit");
        if (quantityMilli <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (unitPrice < 0) throw new IllegalArgumentException("Unit price must not be negative");
        if (subtotal < 0) throw new IllegalArgumentException("Subtotal must not be negative");
    }
}

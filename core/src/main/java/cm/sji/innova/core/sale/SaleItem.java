package cm.sji.innova.core.sale;

import java.util.Objects;
import java.util.UUID;

/**
 * One saved sale line. The product reference, name and unit are copied from the product when it is
 * sold, so a later catalogue change never alters a saved line.
 */
public record SaleItem(UUID id, UUID saleId, UUID productId, String productReference, String productName,
                       String unit, long quantityMilli, long unitPrice, long subtotal) {
    public SaleItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productReference, "productReference");
        if (productReference.isBlank()) throw new IllegalArgumentException("Product reference must not be blank");
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unit, "unit");
        if (quantityMilli <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (unitPrice < 0) throw new IllegalArgumentException("Unit price must not be negative");
        if (subtotal < 0) throw new IllegalArgumentException("Subtotal must not be negative");
    }
}

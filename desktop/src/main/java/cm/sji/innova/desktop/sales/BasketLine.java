package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.product.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of the basket: which variant, how much of it, and the price the customer was told.
 * It holds no buying price, so a buying price can never reach the checkout screen.
 */
public record BasketLine(UUID productId, String label, String reference, String unit,
                         long quantityMilli, long unitPrice) {
    public BasketLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(label, "label");
        reference = reference == null ? "" : reference;
        boolean whole = Quantities.isWholeUnit(Objects.requireNonNull(unit, "unit"));
        if (quantityMilli <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (whole && quantityMilli % Quantities.ONE != 0) {
            throw new IllegalArgumentException("Pieces and bags must be whole");
        }
        if (unitPrice < 0) throw new IllegalArgumentException("Price must not be negative");
    }

    /** One of this variant, at its current selling price. */
    public static BasketLine oneOf(Product product, String familyName) {
        String label = familyName == null ? product.name() : familyName + " - " + product.name();
        return new BasketLine(product.id(), label, product.reference(), product.unit(),
                Quantities.ONE, product.unitPrice());
    }

    public BasketLine withQuantity(long newQuantityMilli) {
        return new BasketLine(productId, label, reference, unit, newQuantityMilli, unitPrice);
    }

    public BasketLine withUnitPrice(long price) {
        return new BasketLine(productId, label, reference, unit, quantityMilli, price);
    }

    /**
     * Quantity times price, rounded to whole FCFA with exact halves going up: the same calculation
     * as SaleService. The saved sale stays the authority; a test checks that the two agree.
     *
     * @throws ArithmeticException if the amount does not fit in a {@code long}
     */
    public long total() {
        return BigDecimal.valueOf(quantityMilli, 3)
                .multiply(BigDecimal.valueOf(unitPrice))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}

package cm.sji.innova.core.sale;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Normalized checkout input. Generated IDs/timestamps and cart order are not retry identity. */
public record CashSaleRequest(UUID saleId, UUID shopId, UUID deviceId, String receiptNumber,
                              List<SaleItemRequest> items) {
    public CashSaleRequest {
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(deviceId, "deviceId");
        receiptNumber = Objects.requireNonNull(receiptNumber, "receiptNumber").strip();
        if (receiptNumber.isEmpty()) throw new IllegalArgumentException("Receipt number must not be blank");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) throw new IllegalArgumentException("Sale must contain at least one item");
    }

    private record Line(UUID productId, long quantityMilli) { }

    /**
     * Missing custom prices mean reuse the stored price, not today's catalog price.
     * Explicit prices must equal a stored line's charged price. Line multiplicity matters.
     */
    public void requireMatches(Sale stored) {
        boolean headerMatches = saleId.equals(stored.id()) && shopId.equals(stored.shopId())
                && deviceId.equals(stored.deviceId()) && receiptNumber.equals(stored.receiptNumber())
                && stored.paymentMethod() == PaymentMethod.CASH && items.size() == stored.items().size();
        if (!headerMatches) throw changedRequest();
        Map<Line, List<Long>> remaining = new HashMap<>();
        for (var item : stored.items()) {
            remaining.computeIfAbsent(new Line(item.productId(), item.quantityMilli()), key -> new ArrayList<>())
                    .add(item.unitPrice());
        }
        // Match constrained (explicit-price) lines before unconstrained (catalog-price) lines.
        // This prevents duplicate product/quantity lines being matched greedily to the wrong price.
        for (var item : items.stream().sorted(Comparator.comparing(i -> i.customUnitPrice() == null)).toList()) {
            var prices = remaining.get(new Line(item.productId(), item.quantityMilli()));
            if (prices == null || prices.isEmpty()) throw changedRequest();
            if (item.customUnitPrice() == null) prices.removeLast();
            else if (!prices.remove(item.customUnitPrice())) throw changedRequest();
        }
    }

    public static CashSaleRequest fromSale(Sale sale) {
        return new CashSaleRequest(sale.id(), sale.shopId(), sale.deviceId(), sale.receiptNumber(),
                sale.items().stream().map(item -> new SaleItemRequest(item.productId(),
                        item.quantityMilli(), item.unitPrice())).toList());
    }

    private static IllegalArgumentException changedRequest() {
        return new IllegalArgumentException("Sale ID was already used with different checkout details");
    }
}

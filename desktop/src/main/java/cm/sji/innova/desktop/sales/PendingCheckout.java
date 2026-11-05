package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.SaleItemRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything needed to send one sale, frozen when the cashier confirms it. Every retry sends exactly
 * this: same IDs, same receipt, same lines and same prices, so SaleService can recognise a repeat
 * and never records or charges a second sale.
 */
public record PendingCheckout(UUID saleId, UUID shopId, UUID deviceId, String receiptNumber,
                              List<BasketLine> lines) {
    public PendingCheckout {
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(deviceId, "deviceId");
        if (Objects.requireNonNull(receiptNumber, "receiptNumber").isBlank()) {
            throw new IllegalArgumentException("Receipt number must not be blank");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) throw new IllegalArgumentException("A sale needs at least one line");
        if (lines.stream().map(BasketLine::productId).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("A variant can only appear on one line");
        }
        Basket.totalOf(lines);              // throws ArithmeticException if the total is too large
    }

    /**
     * Starts a new sale with a new ID and receipt. The database does not force receipts to be unique,
     * so the receipt uses 48 random bits of the sale ID. There is no counter, so a restart or a second
     * computer cannot repeat a number by counting from the same place.
     */
    public static PendingCheckout start(UUID shopId, UUID deviceId, List<BasketLine> lines, LocalDate today) {
        UUID saleId = UUID.randomUUID();
        String random = saleId.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String receipt = "REC-" + DateTimeFormatter.BASIC_ISO_DATE.format(today) + "-" + random;
        return new PendingCheckout(saleId, shopId, deviceId, receipt, lines);
    }

    public long total() { return Basket.totalOf(lines); }

    /**
     * The lines as SaleService wants them. The price is sent on purpose: without it, a retry after
     * a price change could charge a different amount from the one the customer was told.
     */
    public List<SaleItemRequest> requests() {
        return lines.stream()
                .map(line -> new SaleItemRequest(line.productId(), line.quantityMilli(), line.unitPrice()))
                .toList();
    }
}

package cm.sji.innova.core.sale;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Sale(UUID id, UUID shopId, String receiptNumber, UUID deviceId,
                    PaymentMethod paymentMethod, long totalAmount, Instant createdAt,
                    List<SaleItem> items) {
    public Sale {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(receiptNumber, "receiptNumber");
        receiptNumber = receiptNumber.strip();
        if (receiptNumber.isEmpty()) throw new IllegalArgumentException("Receipt number must not be blank");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        Objects.requireNonNull(createdAt, "createdAt");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) throw new IllegalArgumentException("Sale must have at least one item");
        if (totalAmount < 0) throw new IllegalArgumentException("Total amount cannot be negative");
    }
}

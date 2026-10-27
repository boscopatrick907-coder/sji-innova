package cm.sji.innova.core.stock;

import java.util.UUID;

public record StockLevel(UUID shopId, UUID productId, String productName, String unit,
                         long quantityMilli, long minimumMilli) {
    /** At the threshold is already low. The default 0 threshold flags empty stock. */
    public boolean lowStock() { return quantityMilli <= minimumMilli; }
}

package cm.sji.innova.core.sale;

import java.util.Objects;

public record SaleResult(Sale sale, boolean replayed) {
    public SaleResult {
        Objects.requireNonNull(sale, "sale");
    }
}

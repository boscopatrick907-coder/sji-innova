package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the screens need, built once at start-up by {@link AppContextLoader}:
 * the data folder, the shop ID, this computer's device ID, and the services.
 * All three services use the SAME database, so a sale and the stock it removes
 * are always saved in the same file.
 */
public record AppContext(LocalAppData location, UUID shopId, UUID deviceId,
                         ProductService products, StockService stock, SaleService sales) {
    public AppContext {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(products, "products");
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(sales, "sales");
    }
}

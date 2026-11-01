package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.product.ProductService;
import java.util.Objects;
import java.util.UUID;

/** Everything the screens need: the shop ID and the services. Built once at start-up. */
public record AppContext(LocalAppData location, UUID shopId, ProductService products) {
    public AppContext {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(products, "products");
    }
}

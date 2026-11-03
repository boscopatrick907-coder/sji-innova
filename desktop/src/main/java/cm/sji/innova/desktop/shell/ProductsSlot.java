package cm.sji.innova.desktop.shell;

import cm.sji.innova.desktop.products.ProductPanel;

/** Connects the Products screen to the shell's real service, shop and messages. */
public final class ProductsSlot {
    private ProductsSlot() { }

    public static ProductPanel create(AppContext context, Messages messages) {
        return new ProductPanel(context.products(), context.shopId(), messages.bundle());
    }
}

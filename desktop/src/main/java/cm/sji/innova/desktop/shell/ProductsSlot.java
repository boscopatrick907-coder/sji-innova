package cm.sji.innova.desktop.shell;

import cm.sji.innova.desktop.catalog.CatalogPanel;
import cm.sji.innova.core.product.ProductFamilyService;
import cm.sji.innova.core.persistence.SqliteProductFamilyRepository;
import cm.sji.innova.core.persistence.SqliteDatabase;

/** Connects the Products screen to the shell's real service, shop and messages. */
public final class ProductsSlot {
    private ProductsSlot() { }

    public static CatalogPanel create(AppContext context, Messages messages) {
        var families = new ProductFamilyService(new SqliteProductFamilyRepository(
                new SqliteDatabase(context.location().databaseFile())), context.products());
        return new CatalogPanel(context.products(), families, context.stock(), context.shopId(), messages.bundle());
    }
}

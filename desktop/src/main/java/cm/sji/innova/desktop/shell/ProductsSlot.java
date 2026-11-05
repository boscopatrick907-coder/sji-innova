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
        var database = new SqliteDatabase(context.location().databaseFile());
        var imports = new cm.sji.innova.core.product.importer.ProductImportService(
                new cm.sji.innova.core.persistence.SqliteProductImportRepository(database));
        var photos = new cm.sji.innova.core.product.photo.ProductPhotoService(
                new cm.sji.innova.core.persistence.SqliteProductPhotoRepository(database), context.location().directory());
        return new CatalogPanel(context.products(), families, context.stock(), context.shopId(), messages.bundle(),
                imports, photos, context.location().directory());
    }
}

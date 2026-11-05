package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteSaleRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import java.util.UUID;

/** Test helper: an AppContext for screen tests that only need a (fake) ProductService. */
public final class TestAppContexts {
    private TestAppContexts() { }

    /**
     * The stock and sales services point at a database file that is never opened: creating them
     * touches no disk, and the tests that use this helper never call them.
     */
    public static AppContext withProducts(LocalAppData location, ProductService products) {
        SqliteDatabase neverOpened = new SqliteDatabase(location.databaseFile());
        return new AppContext(location, UUID.randomUUID(), UUID.randomUUID(), products,
                new StockService(new SqliteStockRepository(neverOpened)),
                new SaleService(new SqliteSaleRepository(neverOpened), new SqliteProductRepository(neverOpened)));
    }
}

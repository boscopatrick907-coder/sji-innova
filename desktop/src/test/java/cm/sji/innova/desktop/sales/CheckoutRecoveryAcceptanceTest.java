package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteSaleRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.UndeletableFile;
import cm.sji.innova.desktop.sales.Checkout.Outcome;
import cm.sji.innova.desktop.sales.Checkout.Recovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-02 acceptance for the cash sale: the case {@link CheckoutTest} and {@link CheckoutOwnershipTest}
 * do not already cover. The pending file lives in a folder of its own, away from the database, so it
 * can be protected from deletion without touching SQLite.
 */
class CheckoutRecoveryAcceptanceTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private StockService stock;
    private PendingCheckoutStore store;
    private FlakySaleRepository repository;
    private SaleService sales;
    private Product rice;

    @BeforeEach
    void openShopWithTenPiecesOfRice() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("database").resolve("cella.db"));
        database.initialize();
        ProductService products = new ProductService(new SqliteProductRepository(database));
        stock = new StockService(new SqliteStockRepository(database));
        repository = new FlakySaleRepository(new SqliteSaleRepository(database));
        sales = new SaleService(repository, new SqliteProductRepository(database));
        store = new PendingCheckoutStore(directory.resolve("checkout").resolve("pending-checkout.txt"));
        rice = products.create(shop, "Rice", 1_000);
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Test delivery");
    }

    private Checkout checkout() { return new Checkout(sales, store, shop); }

    private long riceLeft() throws Exception { return stock.current(shop, rice.id()).quantityMilli(); }

    @Test
    void aConfirmedSaleWhosePendingFileCannotBeRemovedIsCleanedUpAtTheNextOpeningAndCountsOnce() throws Exception {
        UndeletableFile.assumeSupported(store.file().getParent());
        PendingCheckout sale = PendingCheckout.start(shop, device,
                List.of(BasketLine.oneOf(rice, null).withQuantity(2_000)), LocalDate.of(2026, 11, 10));
        AtomicReference<UndeletableFile> protection = new AtomicReference<>();
        repository.afterSaving = () -> { protection.set(new UndeletableFile(store.file())); return null; };

        try {
            Outcome.Saved saved = assertInstanceOf(Outcome.Saved.class, checkout().submit(sale),
                    "The sale is in the database, so the cashier sees it as saved");
            assertEquals(sale.saleId(), saved.sale().id());
            assertEquals(Optional.of(sale), store.load(), "The file could not be removed, and it is unchanged");
        } finally {
            if (protection.get() != null) protection.get().close();
        }
        assertEquals(8_000, riceLeft());
        assertEquals(1, sales.listSales(shop).size());

        // The next opening checks the database first: it reports the sale as saved and removes the file.
        Recovery.AlreadySaved found = assertInstanceOf(Recovery.AlreadySaved.class, checkout().recover());
        assertEquals(sale.saleId(), found.sale().id());
        assertFalse(Files.exists(store.file()));
        assertTrue(checkout().recover() instanceof Recovery.Nothing);

        assertEquals(1, repository.calls.get(), "Nothing was sent again");
        assertEquals(8_000, riceLeft());
        assertEquals(1, sales.listSales(shop).size());
    }
}

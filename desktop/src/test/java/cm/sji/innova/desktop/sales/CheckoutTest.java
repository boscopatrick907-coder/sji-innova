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
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.sales.Checkout.Outcome;
import cm.sji.innova.desktop.sales.Checkout.Reason;
import cm.sji.innova.desktop.sales.Checkout.Recovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the safe sale on a real temporary SQLite database, and fails it at the worst moments. */
class CheckoutTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private SqliteDatabase database;
    private ProductService products;
    private StockService stock;
    private PendingCheckoutStore store;
    private Product rice;

    private FlakySaleRepository repository;

    @BeforeEach
    void openShopWithTenPiecesOfRice() throws Exception {
        database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        products = new ProductService(new SqliteProductRepository(database));
        stock = new StockService(new SqliteStockRepository(database));
        store = new PendingCheckoutStore(directory.resolve("pending-checkout.txt"));
        repository = new FlakySaleRepository(new SqliteSaleRepository(database));
        rice = products.create(shop, "Rice", 1_000);
        receive(rice, 10_000);
    }

    private void receive(Product product, long quantityMilli) throws Exception {
        stock.receive(UUID.randomUUID(), shop, product.id(), device, quantityMilli, "Test delivery");
    }

    private SaleService saleService() {
        return new SaleService(repository, new SqliteProductRepository(database));
    }

    private Checkout checkout() { return new Checkout(saleService(), store, shop); }

    private PendingCheckout twoPieces() {
        return PendingCheckout.start(shop, device,
                List.of(BasketLine.oneOf(rice, null).withQuantity(2_000)), LocalDate.of(2026, 11, 5));
    }

    private long riceLeft() throws Exception { return stock.current(shop, rice.id()).quantityMilli(); }

    private int salesSaved() throws Exception { return saleService().listSales(shop).size(); }

    @Test
    void aSaleIsSavedStockGoesDownAndTheFileIsRemoved() throws Exception {
        PendingCheckout sale = twoPieces();
        Outcome.Saved saved = assertInstanceOf(Outcome.Saved.class, checkout().submit(sale));
        assertEquals(sale.receiptNumber(), saved.sale().receiptNumber());
        assertEquals(2_000, saved.sale().totalAmount());
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
        assertNoPendingFile();
    }

    private void assertNoPendingFile() {
        assertFalse(Files.exists(store.file()), "The pending file should be gone");
    }

    @Test
    void theSameSaleSentTwiceCountsOnce() throws Exception {
        PendingCheckout sale = twoPieces();
        checkout().submit(sale);
        Outcome.Saved again = assertInstanceOf(Outcome.Saved.class, checkout().retry(sale));
        assertEquals(sale.saleId(), again.sale().id());
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
    }

    @Test
    void aFailureBeforeSavingCanBeRetriedWithoutLosingAnything() throws Exception {
        repository.failBeforeSaving = true;
        PendingCheckout sale = twoPieces();
        assertInstanceOf(Outcome.Uncertain.class, checkout().submit(sale));
        assertEquals(10_000, riceLeft());
        assertEquals(Optional.of(sale), store.load());

        assertInstanceOf(Outcome.Saved.class, checkout().retry(sale));
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
        assertNoPendingFile();
    }

    @Test
    void aFailureAfterSavingCanBeRetriedWithoutASecondSale() throws Exception {
        repository.failAfterSaving = true;
        PendingCheckout sale = twoPieces();
        assertInstanceOf(Outcome.Uncertain.class, checkout().submit(sale));
        assertEquals(8_000, riceLeft());                // it was saved, but the screen does not know that yet
        assertEquals(Optional.of(sale), store.load());

        assertInstanceOf(Outcome.Saved.class, checkout().retry(sale));
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
        assertNoPendingFile();
    }

    @Test
    void ifTheDetailsCannotBeSavedNothingIsSent() throws Exception {
        Path notAFolder = Files.writeString(directory.resolve("a-file"), "x");
        Checkout blocked = new Checkout(saleService(), new PendingCheckoutStore(notAFolder.resolve("p.txt")), shop);
        assertInstanceOf(Outcome.NotSent.class, blocked.submit(twoPieces()));
        assertEquals(0, repository.calls.get());
        assertEquals(10_000, riceLeft());
        assertEquals(0, salesSaved());
    }

    @Test
    void notEnoughStockSavesNothingAtAll() throws Exception {
        Product beans = products.create(shop, "Beans", 500);
        receive(beans, 1_000);
        PendingCheckout sale = PendingCheckout.start(shop, device, List.of(
                BasketLine.oneOf(rice, null).withQuantity(2_000),
                BasketLine.oneOf(beans, null).withQuantity(5_000)), LocalDate.of(2026, 11, 5));

        Outcome.Rejected rejected = assertInstanceOf(Outcome.Rejected.class, checkout().submit(sale));
        assertEquals(Reason.NOT_ENOUGH_STOCK, rejected.reason());
        assertEquals(10_000, riceLeft());               // the first line is not half-sold
        assertEquals(1_000, stock.current(shop, beans.id()).quantityMilli());
        assertEquals(0, salesSaved());
        assertNoPendingFile();
    }

    @Test
    void aProductFromAnotherShopIsRefused() throws Exception {
        PendingCheckout sale = PendingCheckout.start(shop, device, List.of(
                new BasketLine(UUID.randomUUID(), "Ghost", "", "piece", 1_000, 100)), LocalDate.of(2026, 11, 5));
        Outcome.Rejected rejected = assertInstanceOf(Outcome.Rejected.class, checkout().submit(sale));
        assertEquals(Reason.REFUSED, rejected.reason());
        assertEquals(0, salesSaved());
    }

    @Test
    void aRefusalIsNotTrustedWhenTheSaleIsReallySaved() throws Exception {
        PendingCheckout sale = twoPieces();
        checkout().submit(sale);
        PendingCheckout changed = new PendingCheckout(sale.saleId(), shop, device, sale.receiptNumber(),
                List.of(BasketLine.oneOf(rice, null).withQuantity(3_000)));
        assertInstanceOf(Outcome.Saved.class, checkout().retry(changed));
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
    }

    @Test
    void aRestartNeverSendsAnythingByItself() throws Exception {
        PendingCheckout sale = twoPieces();
        store.save(sale);                               // as if the power failed right after saving

        Recovery.Unfinished found = assertInstanceOf(Recovery.Unfinished.class, checkout().recover());
        assertEquals(sale, found.pending());
        assertEquals(0, repository.calls.get());
        assertEquals(10_000, riceLeft());
        assertEquals(Optional.of(sale), store.load());
    }

    @Test
    void afterARestartTheCashierCanFinishTheSale() throws Exception {
        PendingCheckout sale = twoPieces();
        store.save(sale);
        Recovery.Unfinished found = assertInstanceOf(Recovery.Unfinished.class, checkout().recover());
        assertInstanceOf(Outcome.Saved.class, checkout().retry(found.pending()));
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
        assertNoPendingFile();
    }

    @Test
    void afterARestartASaleThatWasReallySavedIsRecognised() throws Exception {
        repository.failAfterSaving = true;
        PendingCheckout sale = twoPieces();
        checkout().submit(sale);

        Recovery.AlreadySaved found = assertInstanceOf(Recovery.AlreadySaved.class, checkout().recover());
        assertEquals(sale.receiptNumber(), found.sale().receiptNumber());
        assertEquals(8_000, riceLeft());
        assertNoPendingFile();
    }

    @Test
    void cancellingRemovesAnUnsavedSale() throws Exception {
        PendingCheckout sale = twoPieces();
        store.save(sale);
        assertInstanceOf(Outcome.Discarded.class, checkout().discard(sale));
        assertEquals(10_000, riceLeft());
        assertEquals(0, salesSaved());
        assertNoPendingFile();
    }

    @Test
    void cancellingASaleThatWasReallySavedReportsItAsSaved() throws Exception {
        repository.failAfterSaving = true;
        PendingCheckout sale = twoPieces();
        checkout().submit(sale);
        assertInstanceOf(Outcome.Saved.class, checkout().discard(sale));
        assertEquals(8_000, riceLeft());
        assertEquals(1, salesSaved());
    }

    @Test
    void aDamagedFileIsReportedAndKept() throws Exception {
        Files.writeString(store.file(), "not our file");
        Recovery.Damaged damaged = assertInstanceOf(Recovery.Damaged.class, checkout().recover());
        assertEquals(store.file(), damaged.file());
        assertEquals("not our file", Files.readString(store.file()));

        Path kept = checkout().setAsideDamagedFile();
        assertEquals("not our file", Files.readString(kept));
        assertInstanceOf(Recovery.Nothing.class, checkout().recover());
    }

    @Test
    void aFileFromAnotherShopCountsAsDamaged() throws Exception {
        store.save(PendingCheckout.start(UUID.randomUUID(), device,
                List.of(BasketLine.oneOf(rice, null)), LocalDate.of(2026, 11, 5)));
        assertInstanceOf(Recovery.Damaged.class, checkout().recover());
        assertTrue(Files.exists(store.file()));
    }

    @Test
    void aPriceChangeDoesNotChangeASaleAlreadySubmitted() throws Exception {
        PendingCheckout sale = new PendingCheckout(UUID.randomUUID(), shop, device, "REC-TEST", List.of(
                new BasketLine(rice.id(), "Rice", "", "piece", 2_000, 777)));
        Outcome.Saved saved = assertInstanceOf(Outcome.Saved.class, checkout().submit(sale));
        assertEquals(1_554, saved.sale().totalAmount());
        assertEquals(777, saved.sale().items().getFirst().unitPrice());
    }

    @Test
    void theBasketAndTheSavedSaleAgreeOnRounding() throws Exception {
        long[][] cases = {{1_500, 333}, {500, 1}, {499, 1}, {1, 1}, {750, 1_999}, {333, 3}, {1_001, 999}};
        for (long[] c : cases) {
            Product flour = products.create(shop, "Flour " + c[0], c[1],
                    null, null, null, "kg", java.util.Map.of());
            receive(flour, 5_000);
            BasketLine line = new BasketLine(flour.id(), flour.name(), "", "kg", c[0], c[1]);
            Basket basket = new Basket();
            basket.add(line);
            PendingCheckout sale = PendingCheckout.start(shop, device, basket.lines(), LocalDate.of(2026, 11, 5));
            Outcome.Saved saved = assertInstanceOf(Outcome.Saved.class, checkout().submit(sale));
            assertEquals(basket.total(), saved.sale().totalAmount(), c[0] + " x " + c[1]);
            assertEquals(line.total(), saved.sale().items().getFirst().subtotal());
        }
    }

    @Test
    void aSaleSurvivesAFullRestart() throws Exception {
        PendingCheckout sale = twoPieces();
        checkout().submit(sale);

        SqliteDatabase reopened = new SqliteDatabase(directory.resolve("cella.db"));
        reopened.initialize();
        SaleService after = new SaleService(new SqliteSaleRepository(reopened), new SqliteProductRepository(reopened));
        Sale saved = after.getSale(shop, sale.saleId()).orElseThrow();
        assertEquals(sale.receiptNumber(), saved.receiptNumber());
        assertEquals(2_000, saved.totalAmount());
        assertEquals(8_000, new StockService(new SqliteStockRepository(reopened)).current(shop, rice.id()).quantityMilli());
    }
}

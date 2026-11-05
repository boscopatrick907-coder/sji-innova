package cm.sji.innova.desktop.stock;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.stock.StockService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Path;
import java.util.ListResourceBundle;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StockPanelTest {

    @TempDir
    Path tempDir;

    private SqliteDatabase database;
    private StockService stock;
    private ProductService products;
    private UUID shopId;
    private UUID deviceId;
    private Product product;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(tempDir.resolve("stock-panel.db"));
        database.initialize();

        stock = new StockService(new SqliteStockRepository(database));
        products = new ProductService(new SqliteProductRepository(database));

        shopId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        product = products.create(shopId, "T-shirt", 3000);
    }

    private ResourceBundle emptyMessages() {
        return new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[0][0];
            }
        };
    }

    /**
     * Creates the panel and waits for both the initial stock load and
     * the initial movement-history worker to finish.
     */
    private StockPanel createPanelAndWaitForLoad() throws Exception {
        AtomicReference<StockPanel> reference = new AtomicReference<>();
        CountDownLatch historyDone = new CountDownLatch(1);

        SwingUtilities.invokeAndWait(() -> {
            StockPanel panel = new StockPanel(
                    stock,
                    products,
                    shopId,
                    deviceId,
                    emptyMessages()
            );

            panel.setErrorHandler(message -> {});
            panel.setOnHistoryWorkerDone(historyDone::countDown);
            reference.set(panel);
        });

        StockPanel panel = reference.get();

        panel.initialLoadCompletion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertTrue(
                historyDone.await(5, TimeUnit.SECONDS),
                "Initial movement history loading timed out"
        );

        return panel;
    }

    private void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private void clickReceive(StockPanel panel) throws Exception {
        onEdt(() -> panel.receiveButton().doClick());
    }

    private void waitFor(CountDownLatch latch, String message)
            throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), message);
    }

    @Test
    void panelLoadsProductsAndInitialStock() throws Exception {
        StockPanel panel = createPanelAndWaitForLoad();

        onEdt(() -> {
            assertEquals(1, panel.stockTable().getRowCount());
            assertEquals(1, panel.productSelector().getItemCount());

            assertEquals(
                    "T-shirt",
                    panel.stockTable().getValueAt(0, 0)
            );

            assertEquals(
                    "piece",
                    panel.stockTable().getValueAt(0, 1)
            );

            assertEquals(
                    "0",
                    panel.stockTable().getValueAt(0, 2)
            );
        });

        assertEquals(
                0,
                stock.current(shopId, product.id()).quantityMilli()
        );
    }

    @Test
    void receivesTenPiecesAndDisplaysMovementHistory() throws Exception {
        StockPanel panel = createPanelAndWaitForLoad();

        CountDownLatch receiptDone = new CountDownLatch(1);
        CountDownLatch stockReloadDone = new CountDownLatch(1);
        CountDownLatch historyReloadDone = new CountDownLatch(1);

        onEdt(() -> {
            panel.setOnReceiptWorkerDone(receiptDone::countDown);
            panel.setOnLoadWorkerDone(stockReloadDone::countDown);
            panel.setOnHistoryWorkerDone(historyReloadDone::countDown);

            panel.quantityField().setText("10");
            panel.reasonField().setText("Delivery reference 001");
        });

        clickReceive(panel);

        waitFor(receiptDone, "Receipt operation timed out");
        waitFor(stockReloadDone, "Stock refresh timed out after receipt");
        waitFor(historyReloadDone, "History refresh timed out after receipt");

        assertEquals(
                10_000,
                stock.current(shopId, product.id()).quantityMilli()
        );

        assertEquals(
                1,
                stock.history(shopId, product.id()).size()
        );

        onEdt(() -> {
            assertEquals(1, panel.stockTable().getRowCount());
            assertEquals("10", panel.stockTable().getValueAt(0, 2));

            assertEquals(1, panel.historyTable().getRowCount());
            assertEquals(
                    "DELIVERY",
                    panel.historyTable().getValueAt(0, 1).toString()
            );

            assertEquals(
                    "10",
                    panel.historyTable().getValueAt(0, 2)
            );

            assertEquals(
                    "Delivery reference 001",
                    panel.historyTable().getValueAt(0, 3)
            );

            assertFalse(panel.hasPendingReceipt());
            assertEquals("", panel.quantityField().getText());
            assertEquals("", panel.reasonField().getText());
        });
    }

    @Test
    void rejectsMissingReasonAndInvalidQuantityWithoutSaving() throws Exception {
        StockPanel panel = createPanelAndWaitForLoad();

        onEdt(() -> {
            panel.quantityField().setText("10");
            panel.reasonField().setText("   ");
        });

        clickReceive(panel);

        assertEquals(
                0,
                stock.current(shopId, product.id()).quantityMilli()
        );

        onEdt(() -> {
            panel.quantityField().setText("1.5");
            panel.reasonField().setText("Delivery");
        });

        clickReceive(panel);

        // Pieces must be whole numbers. No receipt worker should start.
        assertEquals(
                0,
                stock.current(shopId, product.id()).quantityMilli()
        );

        onEdt(() -> {
            assertFalse(panel.hasPendingReceipt());
            assertEquals("1.5", panel.quantityField().getText());
            assertEquals("Delivery", panel.reasonField().getText());
        });
    }

    @Test
    void failedReceiptCanBeRetriedWithoutDuplicatingStock() throws Exception {
        StockPanel panel = createPanelAndWaitForLoad();

        // Simulate a database failure during the stock cache update.
        try (var connection = database.open();
             var statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TRIGGER reject_stock_update
                    BEFORE UPDATE OF current_stock_milli ON products
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated storage failure');
                    END
                    """);
        }

        onEdt(() -> {
            panel.quantityField().setText("10");
            panel.reasonField().setText("Delivery reference retry");
        });

        CountDownLatch firstAttempt = new CountDownLatch(1);
        onEdt(() -> panel.setOnReceiptWorkerDone(firstAttempt::countDown));

        clickReceive(panel);
        waitFor(firstAttempt, "First receipt attempt timed out");

        assertEquals(
                0,
                stock.current(shopId, product.id()).quantityMilli()
        );

        onEdt(() -> {
            assertTrue(panel.hasPendingReceipt());
            assertTrue(panel.receiveButton().isEnabled());
            assertFalse(panel.quantityField().isEnabled());
            assertFalse(panel.reasonField().isEnabled());
        });

        // Remove the simulated failure and retry the same request.
        try (var connection = database.open();
             var statement = connection.createStatement()) {

            statement.execute("DROP TRIGGER reject_stock_update");
        }

        CountDownLatch secondAttempt = new CountDownLatch(1);
        CountDownLatch stockReloadDone = new CountDownLatch(1);
        CountDownLatch historyReloadDone = new CountDownLatch(1);

        onEdt(() -> {
            panel.setOnReceiptWorkerDone(secondAttempt::countDown);
            panel.setOnLoadWorkerDone(stockReloadDone::countDown);
            panel.setOnHistoryWorkerDone(historyReloadDone::countDown);
        });

        clickReceive(panel);

        waitFor(secondAttempt, "Receipt retry timed out");
        waitFor(stockReloadDone, "Stock refresh timed out after retry");
        waitFor(historyReloadDone, "History refresh timed out after retry");

        assertEquals(
                10_000,
                stock.current(shopId, product.id()).quantityMilli()
        );

        assertEquals(
                1,
                stock.history(shopId, product.id()).size()
        );

        onEdt(() -> {
            assertEquals(1, panel.stockTable().getRowCount());
            assertEquals("10", panel.stockTable().getValueAt(0, 2));
            assertEquals(1, panel.historyTable().getRowCount());
            assertFalse(panel.hasPendingReceipt());
        });
    }

    @Test
    void filtersStockToTheSelectedShop() throws Exception {
        UUID anotherShop = UUID.randomUUID();

        products.create(anotherShop, "Other shop product", 5000);

        StockPanel panel = createPanelAndWaitForLoad();

        onEdt(() -> {
            assertEquals(1, panel.stockTable().getRowCount());
            assertEquals(
                    "T-shirt",
                    panel.stockTable().getValueAt(0, 0)
            );
        });

        assertEquals(1, stock.list(shopId).size());
        assertEquals(1, stock.list(anotherShop).size());
    }
}

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
    @Test
    void fractionalBagsAreEditableAfterRejectionAndWholeBagsCanBeReceived() throws Exception {
        Product bag = products.create(shopId,"Rice bag",28000,null,null,null,"bag",java.util.Map.of());
        StockPanel panel = createPanelAndWaitForLoad();
        onEdt(() -> {
            for(int i=0;i<panel.productSelector().getItemCount();i++)
                if(panel.productSelector().getItemAt(i).id().equals(bag.id()))panel.productSelector().setSelectedIndex(i);
            panel.quantityField().setText("1.5");panel.reasonField().setText("Delivery");
            panel.receiveButton().doClick();
            assertFalse(panel.hasPendingReceipt());assertTrue(panel.quantityField().isEnabled());
            assertEquals("1.5",panel.quantityField().getText());
        });
        CountDownLatch receipt=new CountDownLatch(1),loaded=new CountDownLatch(1),history=new CountDownLatch(1);
        onEdt(() -> {
            panel.setOnReceiptWorkerDone(receipt::countDown);panel.setOnLoadWorkerDone(loaded::countDown);panel.setOnHistoryWorkerDone(history::countDown);
            panel.quantityField().setText("2");panel.receiveButton().doClick();
        });
        waitFor(receipt,"Bag receipt");waitFor(loaded,"Bag stock reload");waitFor(history,"Bag history reload");
        assertEquals(2000,stock.current(shopId,bag.id()).quantityMilli());
    }

    @Test
    void refreshKeepsTheOriginalPendingProductAndSuccessfulReceiptHistory() throws Exception {
        products.create(shopId,"AAA Other product",100);
        StockPanel panel=createPanelAndWaitForLoad();
        CountDownLatch initialHistory=new CountDownLatch(1);
        onEdt(() -> {panel.setOnHistoryWorkerDone(initialHistory::countDown);panel.productSelector().setSelectedIndex(1);});
        waitFor(initialHistory,"Selected product history");
        try(var c=database.open();var statement=c.createStatement()){
            statement.execute("CREATE TRIGGER fail_receipt BEFORE UPDATE OF current_stock_milli ON products BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
        CountDownLatch failed=new CountDownLatch(1);
        onEdt(() -> {panel.setOnReceiptWorkerDone(failed::countDown);panel.quantityField().setText("10");panel.reasonField().setText("T-shirt delivery");panel.receiveButton().doClick();});
        waitFor(failed,"Failed receipt");
        CountDownLatch loaded=new CountDownLatch(1),history=new CountDownLatch(1);
        onEdt(() -> {panel.setOnLoadWorkerDone(loaded::countDown);panel.setOnHistoryWorkerDone(history::countDown);panel.refreshButton().doClick();});
        waitFor(loaded,"Pending refresh");waitFor(history,"Pending history");
        onEdt(() -> {assertEquals(product.id(),((Product)panel.productSelector().getSelectedItem()).id());assertFalse(panel.quantityField().isEnabled());assertEquals("10",panel.quantityField().getText());});
        try(var c=database.open();var statement=c.createStatement()){statement.execute("DROP TRIGGER fail_receipt");}
        CountDownLatch saved=new CountDownLatch(1),reloaded=new CountDownLatch(1),shown=new CountDownLatch(1);
        onEdt(() -> {panel.setOnReceiptWorkerDone(saved::countDown);panel.setOnLoadWorkerDone(reloaded::countDown);panel.setOnHistoryWorkerDone(shown::countDown);panel.receiveButton().doClick();});
        waitFor(saved,"Retry receipt");waitFor(reloaded,"Retry reload");waitFor(shown,"Retry history");
        onEdt(() -> {assertEquals(product.id(),((Product)panel.productSelector().getSelectedItem()).id());assertEquals("T-shirt delivery",panel.historyTable().getValueAt(0,3));});
        assertEquals(10000,stock.current(shopId,product.id()).quantityMilli());
    }

    @Test
    void historyPreservesNegativeAndPositiveAdjustmentSigns() throws Exception {
        stock.receive(UUID.randomUUID(),shopId,product.id(),deviceId,10000,"Opening");
        stock.adjust(UUID.randomUUID(),shopId,product.id(),deviceId,-2000,"Damage");
        stock.adjust(UUID.randomUUID(),shopId,product.id(),deviceId,1000,"Correction");
        StockPanel panel=createPanelAndWaitForLoad();
        onEdt(() -> {assertEquals("-2",panel.historyTable().getValueAt(1,2));assertEquals("1",panel.historyTable().getValueAt(2,2));});
    }

    @Test
    void deterministicServiceOverflowLeavesInputEditableInsteadOfRetryTrap() throws Exception {
        stock.receive(UUID.randomUUID(),shopId,product.id(),deviceId,(Long.MAX_VALUE/1000)*1000,"Opening");
        StockPanel panel=createPanelAndWaitForLoad();CountDownLatch receipt=new CountDownLatch(1);
        onEdt(() -> {panel.setOnReceiptWorkerDone(receipt::countDown);panel.quantityField().setText("10");panel.reasonField().setText("Overflow");panel.receiveButton().doClick();});
        waitFor(receipt,"Overflow response");
        onEdt(() -> {assertFalse(panel.hasPendingReceipt());assertTrue(panel.quantityField().isEnabled());assertEquals("10",panel.quantityField().getText());});
        assertEquals((Long.MAX_VALUE/1000)*1000,stock.current(shopId,product.id()).quantityMilli());
    }

    private static StockPanel findStock(java.awt.Component component) {
        if(component instanceof StockPanel panel)return panel;
        if(component instanceof java.awt.Container parent)for(var child:parent.getComponents()){StockPanel found=findStock(child);if(found!=null)return found;}
        return null;
    }

    @Test
    void stockIsReachableThroughTheRealShellWithInjectedServices() throws Exception {
        var context=cm.sji.innova.desktop.shell.AppContextLoader.load(new cm.sji.innova.desktop.shell.LocalAppData(tempDir.resolve("visible-shop")));
        context.products().create(context.shopId(),"Visible product",500);
        AtomicReference<cm.sji.innova.desktop.shell.ShellPanel> shell=new AtomicReference<>();
        onEdt(() -> {var window=new cm.sji.innova.desktop.shell.ShellPanel(cm.sji.innova.desktop.shell.Messages.load(java.util.Locale.FRENCH),context.location(),()->context,()->{});window.showMain(context);shell.set(window);});
        shell.get().productsReady().toCompletableFuture().get(5,TimeUnit.SECONDS);
        AtomicReference<StockPanel> opened=new AtomicReference<>();CountDownLatch history=new CountDownLatch(1);
        onEdt(() -> {shell.get().selectScreen("stock");StockPanel panel=findStock(shell.get());assertNotNull(panel);panel.setErrorHandler(text->{});panel.setOnHistoryWorkerDone(history::countDown);opened.set(panel);assertEquals("stock",shell.get().selectedScreenId());});
        opened.get().initialLoadCompletion().toCompletableFuture().get(5,TimeUnit.SECONDS);waitFor(history,"Visible stock history");
        onEdt(() -> {assertEquals(1,opened.get().stockTable().getRowCount());assertEquals("Enregistrer la réception",opened.get().receiveButton().getText());});
    }

}

package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteSaleRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clicks the real history page on a real temporary SQLite database. No window is opened. */
class SalesHistoryPanelTest {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private final AtomicInteger backClicks = new AtomicInteger();
    private ProductService products;
    private StockService stock;
    private FlakySaleRepository repository;
    private SaleService sales;
    private Product rice;

    @BeforeEach
    void openShopWithRice() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        products = new ProductService(new SqliteProductRepository(database));
        stock = new StockService(new SqliteStockRepository(database));
        repository = new FlakySaleRepository(new SqliteSaleRepository(database));
        sales = new SaleService(repository, new SqliteProductRepository(database));
        rice = products.create(shop, "Rice", 1_000);
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 100_000, "Test delivery");
    }

    private Sale sell(String receipt, long quantityMilli) throws Exception {
        return sales.makeCashSale(UUID.randomUUID(), shop, device, receipt,
                List.of(new SaleItemRequest(rice.id(), quantityMilli))).sale();
    }

    private void cancel(Sale sale) throws Exception {
        sales.voidCashSale(UUID.randomUUID(), shop, sale.id(), device, UUID.randomUUID(), "Wrong quantity");
    }

    private static <T> T edt(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(work.get()));
        return result.get();
    }

    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }

    private SalesHistoryPanel open() throws Exception { return open(Locale.ENGLISH); }

    private SalesHistoryPanel open(Locale locale) throws Exception {
        ResourceBundle bundle = Messages.load(locale).bundle();
        SalesHistoryPanel panel = edt(() -> new SalesHistoryPanel(sales, shop, bundle, backClicks::incrementAndGet));
        reload(panel);
        return panel;
    }

    private static void reload(SalesHistoryPanel panel) throws Exception {
        edt(panel::reload);
        waitUntilIdle(panel);
    }

    private static void waitUntilIdle(SalesHistoryPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (edt(panel::isBusy)) {
            assertTrue(System.nanoTime() < deadline, "The page is still busy");
            Thread.sleep(10);
        }
    }

    private static <T extends Component> T find(Component root, Class<T> type, String name) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JTable table(SalesHistoryPanel panel) { return find(panel, JTable.class, "historyTable"); }
    private static JTextField search(SalesHistoryPanel panel) { return find(panel, JTextField.class, "historySearch"); }
    private static JTextField from(SalesHistoryPanel panel) { return find(panel, JTextField.class, "historyFrom"); }
    private static JTextField to(SalesHistoryPanel panel) { return find(panel, JTextField.class, "historyTo"); }
    private static String message(SalesHistoryPanel panel) { return find(panel, JTextArea.class, "historyMessage").getText(); }
    private static String count(SalesHistoryPanel panel) { return find(panel, JLabel.class, "historyCount").getText(); }

    private static JButton button(SalesHistoryPanel panel, String name) {
        JButton button = find(panel, JButton.class, name);
        assertNotNull(button, name);
        return button;
    }

    /** The table is on screen only when there is at least one row; otherwise a message replaces it. */
    private static boolean listIsShown(SalesHistoryPanel panel) {
        Component ancestor = table(panel);
        while (ancestor != panel) {
            if (!ancestor.isVisible()) return false;
            ancestor = ancestor.getParent();
        }
        return true;
    }

    private static boolean messageIsShown(SalesHistoryPanel panel) {
        Component ancestor = find(panel, JTextArea.class, "historyMessage");
        while (ancestor != panel) {
            if (!ancestor.isVisible()) return false;
            ancestor = ancestor.getParent();
        }
        return true;
    }

    private static String cell(SalesHistoryPanel panel, int row, int column) throws Exception {
        return edt(() -> String.valueOf(table(panel).getValueAt(row, column)));
    }

    private static List<String> receipts(SalesHistoryPanel panel) throws Exception {
        return edt(() -> {
            JTable table = table(panel);
            return java.util.stream.IntStream.range(0, table.getRowCount())
                    .mapToObj(row -> String.valueOf(table.getValueAt(row, SalesHistoryPanel.COLUMN_RECEIPT))).toList();
        });
    }

    private static String day(Sale sale) {
        return DAY.format(sale.createdAt().atZone(SaleHistoryFilter.SHOP_ZONE));
    }

    @Test
    void savedSalesAreListedNewestFirstWithTheirReceiptDateTotalAndStatus() throws Exception {
        Sale first = sell("REC-FIRST", 3_000);
        Thread.sleep(5);        // so the two saved instants differ
        Sale second = sell("REC-SECOND", 2_000);
        assertTrue(second.createdAt().isAfter(first.createdAt()));

        SalesHistoryPanel panel = open();

        assertTrue(edt(() -> listIsShown(panel)));
        assertEquals(List.of("REC-SECOND", "REC-FIRST"), receipts(panel));
        assertEquals(CatalogStyle.localDateTime(second.createdAt(), Locale.UK), cell(panel, 0, SalesHistoryPanel.COLUMN_DATE));
        assertEquals("2,000", cell(panel, 0, SalesHistoryPanel.COLUMN_TOTAL));
        assertEquals("3,000", cell(panel, 1, SalesHistoryPanel.COLUMN_TOTAL));
        assertEquals("Paid", cell(panel, 0, SalesHistoryPanel.COLUMN_STATUS));
        assertEquals("Sales shown: 2 of 2", edt(() -> count(panel)));
    }

    @Test
    void aSavedTotalIsShownAsItWasRecordedEvenWithAFractionalQuantity() throws Exception {
        Product oil = products.create(shop, "Oil", 1_001, null, null, null, "litre", java.util.Map.of());
        stock.receive(UUID.randomUUID(), shop, oil.id(), device, 10_000, "Test delivery");
        Sale sale = sales.makeCashSale(UUID.randomUUID(), shop, device, "REC-OIL",
                List.of(new SaleItemRequest(oil.id(), 500))).sale();
        assertEquals(501, sale.totalAmount());      // 500.5 rounds up once, when the sale is saved

        SalesHistoryPanel panel = open();

        assertEquals("501", cell(panel, 0, SalesHistoryPanel.COLUMN_TOTAL));
    }

    @Test
    void anEmptyShopSaysSoInsteadOfShowingABlankTable() throws Exception {
        SalesHistoryPanel panel = open();

        assertFalse(edt(() -> listIsShown(panel)));
        assertTrue(edt(() -> messageIsShown(panel)));
        assertEquals("No sales have been saved yet.", edt(() -> message(panel)));
        assertFalse(edt(() -> button(panel, "historyRetry").isVisible()));
        assertFalse(edt(() -> button(panel, "historyClear").isVisible()));
    }

    @Test
    void anotherShopsSalesAreNotListed() throws Exception {
        UUID otherShop = UUID.randomUUID();
        Product theirs = products.create(otherShop, "Their rice", 1_000);
        stock.receive(UUID.randomUUID(), otherShop, theirs.id(), device, 10_000, "Test delivery");
        sales.makeCashSale(UUID.randomUUID(), otherShop, device, "REC-THEIRS",
                List.of(new SaleItemRequest(theirs.id(), 1_000)));
        sell("REC-OURS", 1_000);

        assertEquals(List.of("REC-OURS"), receipts(open()));
    }

    @Test
    void aFailedReadIsNotShownAsAnEmptyHistoryAndRetryWorks() throws Exception {
        sell("REC-1", 1_000);
        repository.failReadingHistory = true;

        SalesHistoryPanel panel = open();

        assertFalse(edt(() -> listIsShown(panel)));
        assertEquals("The history could not be read. This does not mean there are no sales. Try again.",
                edt(() -> message(panel)));
        assertTrue(edt(() -> button(panel, "historyRetry").isVisible()));

        repository.failReadingHistory = false;
        edt(() -> button(panel, "historyRetry").doClick());
        waitUntilIdle(panel);

        assertTrue(edt(() -> listIsShown(panel)));
        assertEquals(List.of("REC-1"), receipts(panel));
        assertFalse(edt(() -> button(panel, "historyRetry").isVisible()));
    }

    @Test
    void aFailedRefreshRemovesTheOldRowsAndKeepsTheFilters() throws Exception {
        sell("REC-1", 1_000);
        sell("REC-2", 1_000);
        SalesHistoryPanel panel = open();
        edt(() -> search(panel).setText("rec-2"));
        assertEquals(List.of("REC-2"), receipts(panel));

        repository.failReadingHistory = true;
        reload(panel);

        assertEquals(List.of(), receipts(panel));
        assertTrue(edt(() -> message(panel)).startsWith("The history could not be read"));
        assertEquals("rec-2", edt(() -> search(panel).getText()));

        repository.failReadingHistory = false;
        reload(panel);

        assertEquals(List.of("REC-2"), receipts(panel));
    }

    @Test
    void anOlderReadCannotReplaceANewerOne() throws Exception {
        SalesHistoryPanel panel = open();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.releaseHistoryRead = release;
        repository.historyReadReached = reached;

        edt(panel::reload);     // this read sees no sales, then waits
        assertTrue(reached.await(20, TimeUnit.SECONDS));
        sell("REC-NEW", 1_000);
        edt(panel::reload);     // this newer read sees the sale and finishes first
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (receipts(panel).isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "The newer read never arrived");
            Thread.sleep(10);
        }
        assertTrue(edt(panel::isBusy), "The older read must still be running");

        release.countDown();
        waitUntilIdle(panel);

        assertEquals(List.of("REC-NEW"), receipts(panel));
    }

    @Test
    void searchAndStatusFilterTheListAndClearBringsEverythingBack() throws Exception {
        sell("REC-PAID", 1_000);
        cancel(sell("REC-VOID", 1_000));
        SalesHistoryPanel panel = open();
        JComboBox<?> status = edt(() -> find(panel, JComboBox.class, "historyStatus"));
        assertEquals(2, receipts(panel).size());

        edt(() -> search(panel).setText("void"));
        assertEquals(List.of("REC-VOID"), receipts(panel));
        assertEquals("Sales shown: 1 of 2", edt(() -> count(panel)));
        edt(() -> search(panel).setText(""));

        edt(() -> status.setSelectedIndex(1));
        assertEquals(List.of("REC-PAID"), receipts(panel));
        edt(() -> status.setSelectedIndex(2));
        assertEquals(List.of("REC-VOID"), receipts(panel));

        edt(() -> search(panel).setText("paid"));
        assertEquals(List.of(), receipts(panel));
        assertFalse(edt(() -> listIsShown(panel)));
        assertEquals("No sales match these filters.", edt(() -> message(panel)));
        assertTrue(edt(() -> button(panel, "historyClear").isVisible()));

        edt(() -> button(panel, "historyClear").doClick());

        assertEquals(2, receipts(panel).size());
        assertEquals("", edt(() -> search(panel).getText()));
        assertEquals(0, (int) edt(status::getSelectedIndex));
        assertTrue(edt(() -> listIsShown(panel)));
    }

    @Test
    void aCancelledSaleKeepsItsRowAndSaysSoInWords() throws Exception {
        Sale sale = sell("REC-VOID", 2_000);
        cancel(sale);

        SalesHistoryPanel english = open();
        assertEquals("Cancelled", cell(english, 0, SalesHistoryPanel.COLUMN_STATUS));
        assertEquals("2,000", cell(english, 0, SalesHistoryPanel.COLUMN_TOTAL));

        SalesHistoryPanel french = open(Locale.FRENCH);
        assertEquals("Annulée", cell(french, 0, SalesHistoryPanel.COLUMN_STATUS));
        JLabel badge = edt(() -> {
            JTable table = table(french);
            Container cell = (Container) table.prepareRenderer(
                    table.getCellRenderer(0, SalesHistoryPanel.COLUMN_STATUS), 0, SalesHistoryPanel.COLUMN_STATUS);
            return (JLabel) cell.getComponent(0);
        });
        assertEquals("Annulée", edt(badge::getText));
    }

    @Test
    void theDatesFilterTheListByDayInTheShop() throws Exception {
        Sale sale = sell("REC-1", 1_000);
        String saleDay = day(sale);
        String dayBefore = DAY.format(sale.createdAt().atZone(SaleHistoryFilter.SHOP_ZONE).minusDays(1));
        SalesHistoryPanel panel = open();

        edt(() -> { from(panel).setText(saleDay); to(panel).setText(saleDay); });
        assertEquals(List.of("REC-1"), receipts(panel));

        edt(() -> { from(panel).setText(dayBefore); to(panel).setText(dayBefore); });
        assertEquals(List.of(), receipts(panel));
        assertEquals("No sales match these filters.", edt(() -> message(panel)));

        edt(() -> { from(panel).setText(""); to(panel).setText(""); });
        assertEquals(List.of("REC-1"), receipts(panel));
    }

    @Test
    void aWrongDateIsExplainedOnlyOnceItIsFinished() throws Exception {
        sell("REC-1", 1_000);
        SalesHistoryPanel panel = open();

        edt(() -> from(panel).setText("31/0"));         // still being typed: nothing changes
        assertEquals(List.of("REC-1"), receipts(panel));
        assertTrue(edt(() -> listIsShown(panel)));

        edt(() -> {
            from(panel).setText("31/02/2026");
            from(panel).postActionEvent();              // Enter
        });
        assertFalse(edt(() -> listIsShown(panel)));
        assertEquals("This date is not valid. Write it like 05/11/2026.", edt(() -> message(panel)));
        assertTrue(edt(() -> button(panel, "historyClear").isVisible()));
        assertFalse(edt(() -> button(panel, "historyRetry").isVisible()));

        edt(() -> from(panel).setText(""));
        assertEquals(List.of("REC-1"), receipts(panel));
    }

    @Test
    void aBackwardsRangeIsExplainedRatherThanShownAsNoSales() throws Exception {
        sell("REC-1", 1_000);
        SalesHistoryPanel panel = open();

        edt(() -> { from(panel).setText("06/11/2026"); to(panel).setText("05/11/2026"); });

        assertFalse(edt(() -> listIsShown(panel)));
        assertEquals("The From date is after the To date.", edt(() -> message(panel)));

        edt(() -> button(panel, "historyClear").doClick());
        assertEquals(List.of("REC-1"), receipts(panel));
    }

    @Test
    void refreshFindsNewSalesAndKeepsTheFilterAndTheSelectedSale() throws Exception {
        sell("REC-A1", 1_000);
        Thread.sleep(5);
        sell("REC-A2", 1_000);
        Thread.sleep(5);
        sell("REC-B", 1_000);
        SalesHistoryPanel panel = open();
        edt(() -> search(panel).setText("rec-a"));
        assertEquals(List.of("REC-A2", "REC-A1"), receipts(panel));
        edt(() -> table(panel).setRowSelectionInterval(1, 1));

        Thread.sleep(5);
        sell("REC-A3", 1_000);
        edt(() -> button(panel, "historyRefresh").doClick());
        waitUntilIdle(panel);

        assertEquals(List.of("REC-A3", "REC-A2", "REC-A1"), receipts(panel));
        assertEquals("rec-a", edt(() -> search(panel).getText()));
        assertEquals("REC-A1", edt(() -> String.valueOf(table(panel).getValueAt(
                table(panel).getSelectedRow(), SalesHistoryPanel.COLUMN_RECEIPT))));
    }

    @Test
    void browsingChangesNothing() throws Exception {
        Sale sale = sell("REC-1", 2_000);
        cancel(sell("REC-2", 3_000));
        long stockBefore = stock.current(shop, rice.id()).quantityMilli();
        int movementsBefore = stock.history(shop, rice.id()).size();
        int savesBefore = repository.calls.get();
        var stateBefore = sales.getSaleState(shop, sale.id());
        int auditBefore = sales.listAuditRecords(shop, sale.id()).size();

        SalesHistoryPanel panel = open();
        edt(() -> {
            search(panel).setText("rec");
            table(panel).setRowSelectionInterval(0, 0);
            button(panel, "historyRefresh").doClick();
        });
        waitUntilIdle(panel);

        assertEquals(stockBefore, stock.current(shop, rice.id()).quantityMilli());
        assertEquals(movementsBefore, stock.history(shop, rice.id()).size());
        assertEquals(savesBefore, repository.calls.get());
        assertEquals(stateBefore, sales.getSaleState(shop, sale.id()));
        assertEquals(auditBefore, sales.listAuditRecords(shop, sale.id()).size());
    }

    @Test
    void theBackButtonAsksToGoBackAndNothingElse() throws Exception {
        SalesHistoryPanel panel = open();

        edt(() -> button(panel, "historyBack").doClick());

        assertEquals(1, backClicks.get());
    }

    @Test
    void anUnfinishedSaleIsMentionedOnlyWhileThereIsOne() throws Exception {
        SalesHistoryPanel panel = open();
        JTextArea notice = edt(() -> find(panel, JTextArea.class, "historyPending"));

        assertFalse(edt(() -> notice.getParent().isVisible()));

        edt(() -> panel.setPendingNotice(true));
        assertTrue(edt(() -> notice.getParent().isVisible()));
        assertNotEquals("", edt(() -> notice.getText()));

        edt(() -> panel.setPendingNotice(false));
        assertFalse(edt(() -> notice.getParent().isVisible()));
    }
}

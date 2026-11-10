package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductFamilyRepository;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteSaleRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductFamilyService;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opens real saved receipts from a real temporary SQLite database. No window is opened. */
class SaleDetailsTest {
    private static final long BUYING_PRICE = 777;       // must never appear on a receipt

    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private SqliteDatabase database;
    private ProductService products;
    private StockService stock;
    private FlakySaleRepository repository;
    private SaleService sales;
    private Product rice;
    private Product cement;

    @BeforeEach
    void openShopWithRiceAndCement() throws Exception {
        database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        products = new ProductService(new SqliteProductRepository(database));
        stock = new StockService(new SqliteStockRepository(database));
        repository = new FlakySaleRepository(new SqliteSaleRepository(database));
        sales = new SaleService(repository, new SqliteProductRepository(database));
        rice = products.create(shop, "Basmati rice", 1_001, null, BUYING_PRICE, null, "kg", Map.of());
        cement = products.create(shop, "Cement", 28_000, null, BUYING_PRICE, null, "bag", Map.of());
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 100_000, "Test delivery");
        stock.receive(UUID.randomUUID(), shop, cement.id(), device, 100_000, "Test delivery");
    }

    /** 1.5 kg at 1,001 is 1,501.5, saved as 1,502; two bags at 28,000 are 56,000; total 57,502. */
    private Sale sellRiceAndCement(String receipt) throws Exception {
        return sales.makeCashSale(UUID.randomUUID(), shop, device, receipt, List.of(
                new SaleItemRequest(rice.id(), 1_500), new SaleItemRequest(cement.id(), 2_000))).sale();
    }

    private Sale sellOneBag(String receipt) throws Exception {
        return sales.makeCashSale(UUID.randomUUID(), shop, device, receipt,
                List.of(new SaleItemRequest(cement.id(), 1_000))).sale();
    }

    private void cancel(Sale sale, String reason) throws Exception {
        sales.voidCashSale(UUID.randomUUID(), shop, sale.id(), device, UUID.randomUUID(), reason);
    }

    private static <T> T edt(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(work.get()));
        return result.get();
    }

    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }

    private SalesHistoryPanel history() throws Exception { return history(Locale.ENGLISH); }

    private SalesHistoryPanel history(Locale locale) throws Exception {
        ResourceBundle bundle = Messages.load(locale).bundle();
        SalesHistoryPanel panel = edt(() -> new SalesHistoryPanel(sales, shop, bundle, () -> { }));
        edt(panel::reload);
        waitUntil(() -> !isBusy(panel));
        return panel;
    }

    private static boolean isBusy(SalesHistoryPanel panel) {
        try {
            return edt(panel::isBusy);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void waitUntil(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "The page is still busy");
            Thread.sleep(10);
        }
    }

    private static <T extends Component> T find(Component root, Class<T> type, String name) {
        if (type.isInstance(root) && (name == null || name.equals(root.getName()))) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton button(Component panel, String name) {
        JButton button = find(panel, JButton.class, name);
        assertNotNull(button, name);
        return button;
    }

    private static JTable table(Component panel) { return find(panel, JTable.class, "historyTable"); }
    private static JTable lines(Component panel) { return find(panel, JTable.class, "receiptLines"); }
    private static JTextField search(Component panel) { return find(panel, JTextField.class, "historySearch"); }
    private static String text(Component panel, String name) { return find(panel, JLabel.class, name).getText(); }
    private static String cancelledNotice(Component panel) {
        return find(panel, JTextArea.class, "receiptCancelled").getText();
    }

    private static boolean detailsShown(SalesHistoryPanel panel) throws Exception {
        return edt(() -> panel.showingDetails() && find(panel, SaleDetailsPanel.class, null).isVisible());
    }

    private static int rowOf(Component panel, String receipt) {
        JTable table = table(panel);
        for (int row = 0; row < table.getRowCount(); row++) {
            if (receipt.equals(table.getValueAt(row, SalesHistoryPanel.COLUMN_RECEIPT))) return row;
        }
        throw new AssertionError("Not listed: " + receipt);
    }

    private static void select(Component panel, String receipt) {
        int row = rowOf(panel, receipt);
        table(panel).setRowSelectionInterval(row, row);
    }

    /** The receipt cells, row by row, exactly as the cashier reads them. */
    private static List<List<String>> receiptLines(Component panel) throws Exception {
        return edt(() -> {
            JTable table = lines(panel);
            List<List<String>> rows = new ArrayList<>();
            for (int row = 0; row < table.getRowCount(); row++) {
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < table.getColumnCount(); column++) {
                    cells.add(String.valueOf(table.getValueAt(row, column)));
                }
                rows.add(cells);
            }
            return rows;
        });
    }

    /** Every word on the receipt page: labels, notices and table cells. */
    private static String everythingOnTheReceipt(SalesHistoryPanel panel) throws Exception {
        StringBuilder all = new StringBuilder();
        edt(() -> collect(find(panel, SaleDetailsPanel.class, null), all));
        receiptLines(panel).forEach(row -> all.append(String.join(" ", row)).append('\n'));
        return all.toString();
    }

    private static void collect(Component component, StringBuilder all) {
        if (component instanceof JLabel label) all.append(label.getText()).append('\n');
        if (component instanceof JTextArea area) all.append(area.getText()).append('\n');
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, all);
        }
    }

    private static void press(JComponent component, int key) {
        Object name = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(key, 0));
        assertNotNull(name, "No action for key " + key);
        component.getActionMap().get(name).actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, ""));
    }

    private static void doubleClick(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, 0, true);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                cell.x + 5, cell.y + cell.height / 2, 2, false, MouseEvent.BUTTON1));
    }

    /** No screen edits a reference today; a direct change stands in for a future edit or import. */
    private void changeTheCatalogue(Product product, String newName, String newReference, long newPrice) throws Exception {
        try (Connection connection = database.open();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE products SET name = ?, reference = ?, unit_price = ? WHERE id = ?")) {
            update.setString(1, newName);
            update.setString(2, newReference);
            update.setLong(3, newPrice);
            update.setString(4, product.id().toString());
            assertEquals(1, update.executeUpdate());
        }
    }

    @Test
    void theButtonEnterADoubleClickAndEnterInTheSearchEachOpenTheSelectedReceipt() throws Exception {
        sellOneBag("REC-A");
        sellOneBag("REC-B");
        SalesHistoryPanel panel = history();

        edt(() -> { select(panel, "REC-A"); button(panel, "historyOpen").doClick(); });
        assertTrue(detailsShown(panel));
        assertEquals("Receipt REC-A", edt(() -> text(panel, "receiptTitle")));
        edt(() -> button(panel, "receiptBack").doClick());

        edt(() -> { select(panel, "REC-B"); press(table(panel), KeyEvent.VK_ENTER); });
        assertEquals("Receipt REC-B", edt(() -> text(panel, "receiptTitle")));
        edt(() -> button(panel, "receiptBack").doClick());

        edt(() -> doubleClick(table(panel), rowOf(panel, "REC-A")));
        assertEquals("Receipt REC-A", edt(() -> text(panel, "receiptTitle")));
        edt(() -> button(panel, "receiptBack").doClick());

        edt(() -> { search(panel).setText("rec-b"); search(panel).postActionEvent(); });
        assertTrue(detailsShown(panel));
        assertEquals("Receipt REC-B", edt(() -> text(panel, "receiptTitle")));
    }

    @Test
    void enterInAnEmptySearchOpensNothingAndThereIsNothingToOpenWithoutARow() throws Exception {
        SalesHistoryPanel empty = history();
        assertFalse(edt(() -> button(empty, "historyOpen").isEnabled()));

        sellOneBag("REC-1");
        SalesHistoryPanel panel = history();
        edt(() -> search(panel).postActionEvent());
        assertFalse(detailsShown(panel));
        assertTrue(edt(() -> button(panel, "historyOpen").isEnabled()));

        edt(() -> search(panel).setText("nothing like this"));
        assertFalse(edt(() -> button(panel, "historyOpen").isEnabled()));
        edt(() -> search(panel).postActionEvent());
        assertFalse(detailsShown(panel));
    }

    @Test
    void theReceiptShowsWhatWasChargedEvenAfterTheCatalogueChanged() throws Exception {
        Sale sale = sellRiceAndCement("REC-1");
        assertEquals(List.of("P-0001", "P-0002"), List.of(rice.reference(), cement.reference()));
        changeTheCatalogue(rice, "Cheap rice", "P-0101", 500);
        changeTheCatalogue(cement, "Grey cement", "P-0102", 30_000);

        SalesHistoryPanel panel = history();
        edt(() -> button(panel, "historyOpen").doClick());

        assertEquals(List.of(
                List.of("Basmati rice", "P-0001", "1.5 kg", "1,001 / kg", "1,502"),
                List.of("Cement", "P-0002", "2 bags", "28,000 / bag", "56,000")), receiptLines(panel));
        assertEquals("Reference", edt(() -> lines(panel).getColumnName(SaleDetailsPanel.COLUMN_REFERENCE)));
        edt(() -> {
            assertEquals("57,502 FCFA", text(panel, "receiptTotal"));
            assertEquals(CatalogStyle.localDateTime(sale.createdAt(), Locale.UK), text(panel, "receiptDate"));
            assertEquals("Cash", text(panel, "receiptPayment"));
            assertEquals("Paid", text(panel, "receiptStatus"));
            assertEquals("Items sold (2)", text(panel, "receiptLinesHeading"));
            assertEquals("", cancelledNotice(panel));
            assertFalse(find(panel, JTextArea.class, "receiptCancelled").getParent().isVisible());
        });
        String everything = everythingOnTheReceipt(panel);
        assertFalse(everything.contains("Cheap rice") || everything.contains("Grey cement"), everything);
        assertFalse(everything.contains("P-0101") || everything.contains("P-0102"), everything);
        assertFalse(everything.contains(String.valueOf(BUYING_PRICE)), "A buying price is shown:\n" + everything);
    }

    @Test
    void aCancelledReceiptSaysSoWithItsDateAndReasonAndKeepsItsLines() throws Exception {
        Sale sale = sellRiceAndCement("REC-1");
        cancel(sale, "Wrong quantity");
        var voidEvent = sales.getSaleState(shop, sale.id()).orElseThrow().voidEvent().orElseThrow();

        SalesHistoryPanel panel = history();
        edt(() -> button(panel, "historyOpen").doClick());

        edt(() -> {
            assertEquals("Cancelled", text(panel, "receiptStatus"));
            String notice = cancelledNotice(panel);
            assertTrue(notice.contains(CatalogStyle.localDateTime(voidEvent.createdAt(), Locale.UK)), notice);
            assertTrue(notice.contains("“Wrong quantity”"), notice);
            assertTrue(find(panel, JTextArea.class, "receiptCancelled").getParent().isVisible());
            assertEquals("57,502 FCFA", text(panel, "receiptTotal"));
        });
        assertEquals(2, receiptLines(panel).size());
        assertEquals("1,502", receiptLines(panel).get(0).get(SaleDetailsPanel.COLUMN_LINE_TOTAL));
    }

    @Test
    void backAndEscapeReturnToTheSameFiltersAndSelectedSale() throws Exception {
        sellOneBag("REC-1");
        sellOneBag("REC-2");
        cancel(sellOneBag("REC-3"), "Test");
        SalesHistoryPanel panel = history();
        edt(() -> {
            search(panel).setText("rec");
            find(panel, JComboBox.class, "historyStatus").setSelectedIndex(1);    // paid only
            select(panel, "REC-1");
            button(panel, "historyOpen").doClick();
        });
        assertTrue(detailsShown(panel));

        edt(() -> button(panel, "receiptBack").doClick());

        assertFalse(detailsShown(panel));
        edt(() -> {
            assertEquals("rec", search(panel).getText());
            assertEquals(1, find(panel, JComboBox.class, "historyStatus").getSelectedIndex());
            assertEquals(2, table(panel).getRowCount());
            assertEquals("REC-1", table(panel).getValueAt(table(panel).getSelectedRow(), SalesHistoryPanel.COLUMN_RECEIPT));
        });

        edt(() -> button(panel, "historyOpen").doClick());
        edt(() -> press(find(panel, SaleDetailsPanel.class, null), KeyEvent.VK_ESCAPE));

        assertFalse(detailsShown(panel));
        assertEquals("REC-1", edt(() -> table(panel).getValueAt(table(panel).getSelectedRow(), SalesHistoryPanel.COLUMN_RECEIPT)));
    }

    @Test
    void aRefreshWhileTheReceiptIsOpenShowsItsNewerState() throws Exception {
        Sale sale = sellOneBag("REC-1");
        SalesHistoryPanel panel = history();
        edt(() -> button(panel, "historyOpen").doClick());
        assertEquals("Paid", edt(() -> text(panel, "receiptStatus")));

        cancel(sale, "Customer changed their mind");
        edt(panel::reload);
        waitUntil(() -> !isBusy(panel));

        assertTrue(detailsShown(panel));
        assertEquals("Cancelled", edt(() -> text(panel, "receiptStatus")));
        assertTrue(edt(() -> cancelledNotice(panel)).contains("Customer changed their mind"));
    }

    @Test
    void aFailedRefreshKeepsTheOpenReceiptAndTheListThenSaysItFailed() throws Exception {
        sellRiceAndCement("REC-1");
        SalesHistoryPanel panel = history();
        edt(() -> button(panel, "historyOpen").doClick());

        repository.failReadingHistory = true;
        edt(panel::reload);
        waitUntil(() -> !isBusy(panel));

        assertTrue(detailsShown(panel));
        assertEquals("Receipt REC-1", edt(() -> text(panel, "receiptTitle")));
        assertEquals(2, receiptLines(panel).size());

        edt(() -> button(panel, "receiptBack").doClick());
        assertTrue(edt(() -> find(panel, JTextArea.class, "historyMessage").getText()).contains("could not be read"));
        assertFalse(edt(() -> button(panel, "historyOpen").isEnabled()));
    }

    @Test
    void openingAReceiptChangesNothing() throws Exception {
        Sale sale = sellRiceAndCement("REC-1");
        Sale cancelled = sellOneBag("REC-2");
        cancel(cancelled, "Test");
        long riceBefore = stock.current(shop, rice.id()).quantityMilli();
        long cementBefore = stock.current(shop, cement.id()).quantityMilli();
        int movementsBefore = stock.history(shop, rice.id()).size() + stock.history(shop, cement.id()).size();
        int savesBefore = repository.calls.get();
        var stateBefore = sales.getSaleState(shop, sale.id());
        var cancelledBefore = sales.getSaleState(shop, cancelled.id());
        int auditBefore = sales.listAuditRecords(shop, sale.id()).size() + sales.listAuditRecords(shop, cancelled.id()).size();

        SalesHistoryPanel panel = history();
        for (String receipt : List.of("REC-1", "REC-2")) {
            edt(() -> { select(panel, receipt); button(panel, "historyOpen").doClick(); });
            edt(() -> button(panel, "receiptBack").doClick());
        }

        assertEquals(riceBefore, stock.current(shop, rice.id()).quantityMilli());
        assertEquals(cementBefore, stock.current(shop, cement.id()).quantityMilli());
        assertEquals(movementsBefore, stock.history(shop, rice.id()).size() + stock.history(shop, cement.id()).size());
        assertEquals(savesBefore, repository.calls.get());
        assertEquals(stateBefore, sales.getSaleState(shop, sale.id()));
        assertEquals(cancelledBefore, sales.getSaleState(shop, cancelled.id()));
        assertEquals(auditBefore,
                sales.listAuditRecords(shop, sale.id()).size() + sales.listAuditRecords(shop, cancelled.id()).size());
    }

    @Test
    void theFrenchReceiptUsesFrenchWordsAndNumbers() throws Exception {
        Sale sale = sellRiceAndCement("REC-1");
        cancel(sale, "Erreur de quantité");

        SalesHistoryPanel panel = history(Locale.FRENCH);
        edt(() -> button(panel, "historyOpen").doClick());

        List<List<String>> rows = receiptLines(panel);
        assertEquals("P-0001", rows.get(0).get(SaleDetailsPanel.COLUMN_REFERENCE));
        assertEquals("1,5 kg", rows.get(0).get(SaleDetailsPanel.COLUMN_QUANTITY));
        assertEquals("2 sacs", rows.get(1).get(SaleDetailsPanel.COLUMN_QUANTITY));
        assertEquals("Référence", edt(() -> lines(panel).getColumnName(SaleDetailsPanel.COLUMN_REFERENCE)));
        edt(() -> {
            assertEquals("Reçu REC-1", text(panel, "receiptTitle"));
            assertEquals("Espèces", text(panel, "receiptPayment"));
            assertEquals("Annulée", text(panel, "receiptStatus"));
            assertTrue(cancelledNotice(panel).contains("« Erreur de quantité »"), cancelledNotice(panel));
            // French groups thousands with a narrow no-break space, which \s does not match.
            assertEquals("57502FCFA", text(panel, "receiptTotal").replaceAll("[\\s\\u00A0\\u202F]", ""));
            assertEquals("Retour à l’historique", button(panel, "receiptBack").getText());
        });
    }

    @Test
    void aBasketIsUntouchedByOpeningAReceiptAndComingBack() throws Exception {
        sellOneBag("REC-1");
        ProductFamilyService families = new ProductFamilyService(new SqliteProductFamilyRepository(database), products);
        PendingCheckoutStore store = new PendingCheckoutStore(directory.resolve("pending-checkout.txt"));
        ResourceBundle bundle = Messages.load(Locale.ENGLISH).bundle();
        SalesPanel page = edt(() -> new SalesPanel(sales, products, families, stock, shop, device, store, bundle));
        edt(page::reload);
        waitUntil(() -> { try { return !edt(page::isBusy); } catch (Exception e) { throw new IllegalStateException(e); } });
        edt(() -> {
            find(page, JTextField.class, "salesSearch").setText("cement");
            button(page, "salesAdd").doClick();
            find(page, JTable.class, "salesBasket").setValueAt("3", 0, SalesPanel.COLUMN_QUANTITY);
        });
        String totalBefore = edt(() -> find(page, JLabel.class, "salesTotal").getText());
        long cementBefore = stock.current(shop, cement.id()).quantityMilli();

        edt(() -> button(page, "salesHistory").doClick());
        waitUntil(() -> { try { return !edt(page::isBusy); } catch (Exception e) { throw new IllegalStateException(e); } });
        edt(() -> button(page, "historyOpen").doClick());
        assertEquals("Receipt REC-1", edt(() -> text(page, "receiptTitle")));
        edt(() -> button(page, "receiptBack").doClick());
        edt(() -> button(page, "historyBack").doClick());

        edt(() -> {
            JTable basket = find(page, JTable.class, "salesBasket");
            assertEquals(1, basket.getRowCount());
            assertEquals("3", basket.getValueAt(0, SalesPanel.COLUMN_QUANTITY));
            assertEquals(totalBefore, find(page, JLabel.class, "salesTotal").getText());
            assertTrue(button(page, "salesCheckout").isEnabled());
        });
        assertEquals(1, sales.listSales(shop).size());
        assertEquals(cementBefore, stock.current(shop, cement.id()).quantityMilli());
        assertFalse(Files.exists(store.file()));
    }
}

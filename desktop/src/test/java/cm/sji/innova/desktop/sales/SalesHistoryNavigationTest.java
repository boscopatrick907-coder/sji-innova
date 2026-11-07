package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import cm.sji.innova.desktop.shell.ShellPanel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Going to the saved sales and back must leave the basket and any unfinished sale exactly as they were. */
class SalesHistoryNavigationTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private ProductService products;
    private ProductFamilyService families;
    private StockService stock;
    private FlakySaleRepository repository;
    private SaleService sales;
    private PendingCheckoutStore store;
    private Product rice;

    @BeforeEach
    void openShopWithTenPiecesOfRice() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        products = new ProductService(new SqliteProductRepository(database));
        families = new ProductFamilyService(new SqliteProductFamilyRepository(database), products);
        stock = new StockService(new SqliteStockRepository(database));
        repository = new FlakySaleRepository(new SqliteSaleRepository(database));
        sales = new SaleService(repository, new SqliteProductRepository(database));
        store = new PendingCheckoutStore(directory.resolve("pending-checkout.txt"));
        rice = products.create(shop, "Rice", 1_000);
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Test delivery");
    }

    private static <T> T edt(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(work.get()));
        return result.get();
    }

    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }

    private SalesPanel open() throws Exception {
        ResourceBundle bundle = Messages.load(Locale.ENGLISH).bundle();
        SalesPanel panel = edt(() -> new SalesPanel(sales, products, families, stock, shop, device, store, bundle));
        edt(panel::reload);
        waitUntilIdle(panel);
        return panel;
    }

    private static void waitUntilIdle(SalesPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (edt(panel::isBusy)) {
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

    private static JTable basket(SalesPanel panel) { return find(panel, JTable.class, "salesBasket"); }
    private static String total(SalesPanel panel) { return find(panel, JLabel.class, "salesTotal").getText(); }

    private static boolean historyIsShown(SalesPanel panel) throws Exception {
        return edt(() -> find(panel, SalesHistoryPanel.class, null).isVisible());
    }

    private static void openHistory(SalesPanel panel) throws Exception {
        edt(() -> button(panel, "salesHistory").doClick());
        waitUntilIdle(panel);
        assertTrue(historyIsShown(panel));
    }

    private static void backToCheckout(SalesPanel panel) throws Exception {
        edt(() -> button(panel, "historyBack").doClick());
        assertFalse(historyIsShown(panel));
    }

    private static void addToBasket(SalesPanel panel, String quantity) throws Exception {
        edt(() -> {
            find(panel, JTextField.class, "salesSearch").setText("rice");
            button(panel, "salesAdd").doClick();
            basket(panel).setValueAt(quantity, 0, SalesPanel.COLUMN_QUANTITY);
        });
    }

    private static void confirmCash(SalesPanel panel) throws Exception {
        edt(() -> button(panel, "salesCheckout").doClick());
        edt(() -> button(panel, "salesConfirm").doClick());
        waitUntilIdle(panel);
    }

    private static List<String> listedReceipts(Component panel) throws Exception {
        return edt(() -> {
            JTable table = find(panel, JTable.class, "historyTable");
            return IntStream.range(0, table.getRowCount())
                    .mapToObj(row -> String.valueOf(table.getValueAt(row, SalesHistoryPanel.COLUMN_RECEIPT))).toList();
        });
    }

    private static String pendingNotice(SalesPanel panel) throws Exception {
        return edt(() -> find(panel, JTextArea.class, "historyPending").getText());
    }

    private long riceLeft() throws Exception { return stock.current(shop, rice.id()).quantityMilli(); }

    @Test
    void theCheckoutIsShownFirstAndTheHistoryIsNotReadUntilItIsOpened() throws Exception {
        repository.failReadingHistory = true;       // reading it now would leave a visible error

        SalesPanel panel = open();

        assertFalse(historyIsShown(panel));
        assertFalse(edt(() -> find(panel, JTextArea.class, "historyMessage").getText()).contains("could not be read"));
    }

    @Test
    void aBasketIsUntouchedByAVisitToTheHistory() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "2");
        String totalBefore = edt(() -> total(panel));

        openHistory(panel);
        backToCheckout(panel);

        edt(() -> {
            assertEquals(1, basket(panel).getRowCount());
            assertEquals("2", basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY));
            assertEquals(totalBefore, total(panel));
            assertTrue(button(panel, "salesCheckout").isEnabled());
            assertTrue(basket(panel).isCellEditable(0, SalesPanel.COLUMN_QUANTITY));
        });
        assertEquals(List.of(), sales.listSales(shop));
        assertEquals(10_000, riceLeft());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void aWaitingCashConfirmationIsStillWaitingAfterTheHistory() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "2");
        edt(() -> button(panel, "salesCheckout").doClick());
        assertTrue(edt(() -> button(panel, "salesConfirm").isVisible()));

        openHistory(panel);
        backToCheckout(panel);

        assertTrue(edt(() -> button(panel, "salesConfirm").isVisible()));
        assertEquals(List.of(), sales.listSales(shop));

        edt(() -> button(panel, "salesConfirm").doClick());
        waitUntilIdle(panel);

        assertEquals(1, sales.listSales(shop).size());
        assertEquals(8_000, riceLeft());
    }

    @Test
    void aSaleWithNoCertainAnswerStaysLockedAndIdenticalAcrossTheHistory() throws Exception {
        repository.failAfterSaving = true;
        SalesPanel panel = open();
        addToBasket(panel, "2");
        confirmCash(panel);
        byte[] fileBefore = Files.readAllBytes(store.file());
        Sale really = sales.listSales(shop).getFirst();

        openHistory(panel);

        assertFalse(pendingNotice(panel).isBlank());
        assertEquals(List.of(really.receiptNumber()), listedReceipts(panel));   // it did reach the database
        assertArrayEquals(fileBefore, Files.readAllBytes(store.file()));

        backToCheckout(panel);

        edt(() -> {
            assertTrue(button(panel, "salesRetry").isVisible());
            assertTrue(button(panel, "salesDiscard").isVisible());
            assertFalse(button(panel, "salesCheckout").isVisible());
            assertFalse(basket(panel).isCellEditable(0, SalesPanel.COLUMN_QUANTITY));
            assertEquals(1, basket(panel).getRowCount());
        });
        assertArrayEquals(fileBefore, Files.readAllBytes(store.file()));

        edt(() -> button(panel, "salesRetry").doClick());
        waitUntilIdle(panel);

        assertEquals(List.of(really), sales.listSales(shop));       // the same one sale, with the same ID
        assertEquals(8_000, riceLeft());
        assertFalse(Files.exists(store.file()));
        assertEquals("", pendingNotice(panel));
    }

    @Test
    void aSaleThatDidNotReachTheDatabaseIsNotListedButIsStillMentioned() throws Exception {
        repository.failBeforeSaving = true;
        SalesPanel panel = open();
        addToBasket(panel, "2");
        confirmCash(panel);
        byte[] fileBefore = Files.readAllBytes(store.file());

        openHistory(panel);

        assertEquals(List.of(), listedReceipts(panel));
        assertFalse(pendingNotice(panel).isBlank());

        backToCheckout(panel);

        assertTrue(edt(() -> button(panel, "salesRetry").isVisible()));
        assertArrayEquals(fileBefore, Files.readAllBytes(store.file()));
    }

    @Test
    void anUnfinishedSaleFoundAfterARestartStaysInTheBasketAcrossTheHistory() throws Exception {
        repository.failBeforeSaving = true;
        SalesPanel before = open();
        addToBasket(before, "2");
        confirmCash(before);

        SalesPanel panel = open();
        byte[] fileBefore = Files.readAllBytes(store.file());

        openHistory(panel);
        assertFalse(pendingNotice(panel).isBlank());
        backToCheckout(panel);

        assertTrue(edt(() -> button(panel, "salesRetry").isVisible()));
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertArrayEquals(fileBefore, Files.readAllBytes(store.file()));
        assertEquals(List.of(), sales.listSales(shop));
    }

    @Test
    void theHistoryCannotBeOpenedWhileASaleIsBeingSaved() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "2");

        edt(() -> {
            // The background step cannot finish until this block ends, so the page is really saving.
            button(panel, "salesCheckout").doClick();
            button(panel, "salesConfirm").doClick();
            assertFalse(button(panel, "salesHistory").isEnabled());
            button(panel, "salesHistory").doClick();
        });
        assertFalse(historyIsShown(panel));

        waitUntilIdle(panel);

        assertTrue(edt(() -> button(panel, "salesHistory").isEnabled()));
        assertEquals(1, sales.listSales(shop).size());
    }

    @Test
    void aRefusedQuantityKeepsTheCashierOnTheBasket() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "2");

        edt(() -> {
            JTable table = basket(panel);
            table.editCellAt(0, SalesPanel.COLUMN_QUANTITY);
            ((JTextField) table.getEditorComponent()).setText("abc");
            button(panel, "salesHistory").doClick();
        });

        assertFalse(historyIsShown(panel));
        assertEquals("2", edt(() -> String.valueOf(basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY))));
    }

    @Test
    void aDamagedFileStaysBlockingAndUntouchedWhileTheHistoryOpens() throws Exception {
        Files.writeString(store.file(), "not our file");
        SalesPanel panel = open();

        openHistory(panel);

        assertFalse(pendingNotice(panel).isBlank());
        assertEquals("not our file", Files.readString(store.file()));

        backToCheckout(panel);

        edt(() -> {
            assertTrue(button(panel, "salesSetAside").isVisible());
            assertFalse(button(panel, "salesCheckout").isVisible());
        });
    }

    @Test
    void returningToVentesWithTheHistoryOpenReadsItAgain() throws Exception {
        SalesPanel panel = open();
        openHistory(panel);
        assertEquals(List.of(), listedReceipts(panel));

        Sale sale = sales.makeCashSale(UUID.randomUUID(), shop, device, "REC-NEW",
                List.of(new SaleItemRequest(rice.id(), 1_000))).sale();
        edt(panel::reload);     // what the sidebar does when Ventes is selected again
        waitUntilIdle(panel);

        assertTrue(historyIsShown(panel));
        assertEquals(List.of(sale.receiptNumber()), listedReceipts(panel));
    }

    @Test
    void theFiltersAreStillThereWhenTheHistoryIsOpenedAgain() throws Exception {
        sales.makeCashSale(UUID.randomUUID(), shop, device, "REC-A", List.of(new SaleItemRequest(rice.id(), 1_000)));
        sales.makeCashSale(UUID.randomUUID(), shop, device, "REC-B", List.of(new SaleItemRequest(rice.id(), 1_000)));
        SalesPanel panel = open();
        openHistory(panel);
        edt(() -> find(panel, JTextField.class, "historySearch").setText("rec-b"));

        backToCheckout(panel);
        openHistory(panel);

        assertEquals("rec-b", edt(() -> find(panel, JTextField.class, "historySearch").getText()));
        assertEquals(List.of("REC-B"), listedReceipts(panel));
    }

    /** The real start-up, the real shell and the real data folder, closed and opened again. */
    @Test
    void twoSalesMadeThroughTheShellAreStillInTheHistoryAfterARestart() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("shop"));
        AppContext first = AppContextLoader.load(location);
        Product beans = first.products().create(first.shopId(), "Beans", 1_500);
        first.stock().receive(UUID.randomUUID(), first.shopId(), beans.id(), first.deviceId(), 10_000, "Test delivery");
        SalesPanel selling = openSalesInShell(first);
        for (String quantity : List.of("2", "3")) {
            addBeans(selling, quantity);
            confirmCash(selling);
        }
        List<Sale> saved = first.sales().listSales(first.shopId());
        assertEquals(2, saved.size());

        AppContext second = AppContextLoader.load(location);
        SalesPanel reopened = openSalesInShell(second);
        openHistory(reopened);

        List<String> expected = SaleHistoryFilter.newestFirst(second.sales().listSaleStates(second.shopId()))
                .stream().map(state -> state.sale().receiptNumber()).toList();
        assertEquals(2, expected.size());
        assertEquals(expected, listedReceipts(reopened));
        JTable table = edt(() -> find(reopened, JTable.class, "historyTable"));
        for (int row = 0; row < 2; row++) {
            int line = row;
            Sale sale = saved.stream().filter(one -> one.receiptNumber().equals(expected.get(line))).findFirst().orElseThrow();
            assertEquals(java.text.NumberFormat.getIntegerInstance(Locale.ENGLISH).format(sale.totalAmount()),
                    edt(() -> table.getValueAt(line, SalesHistoryPanel.COLUMN_TOTAL)));
        }
    }

    private static SalesPanel openSalesInShell(AppContext context) throws Exception {
        ShellPanel shell = edt(() -> {
            ShellPanel created = new ShellPanel(Messages.load(Locale.ENGLISH), context.location(), () -> context, () -> { });
            created.showMain(context);
            return created;
        });
        shell.productsReady().toCompletableFuture().get(10, TimeUnit.SECONDS);
        SalesPanel panel = edt(() -> find(shell, SalesPanel.class, null));
        edt(() -> shell.selectScreen("sales"));
        waitUntilIdle(panel);
        return panel;
    }

    private static void addBeans(SalesPanel panel, String quantity) throws Exception {
        edt(() -> {
            find(panel, JTextField.class, "salesSearch").setText("beans");
            button(panel, "salesAdd").doClick();
            basket(panel).setValueAt(quantity, 0, SalesPanel.COLUMN_QUANTITY);
        });
    }
}

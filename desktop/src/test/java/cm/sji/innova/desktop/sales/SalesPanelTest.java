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
import cm.sji.innova.core.product.ProductRepository;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockLevel;
import cm.sji.innova.core.stock.StockMovement;
import cm.sji.innova.core.stock.StockRepository;
import cm.sji.innova.core.stock.StockResult;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clicks the real page on a real temporary SQLite database. No window is opened. */
class SalesPanelTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private SqliteDatabase database;
    private ProductService products;
    private ProductFamilyService families;
    private StockService stock;
    private FlakySaleRepository saleRepository;
    private SaleService sales;
    private PendingCheckoutStore store;
    private Product rice;

    @BeforeEach
    void openShopWithTenPiecesOfRice() throws Exception {
        database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        products = new ProductService(new SqliteProductRepository(database));
        families = new ProductFamilyService(new SqliteProductFamilyRepository(database), products);
        stock = new StockService(new SqliteStockRepository(database));
        saleRepository = new FlakySaleRepository(new SqliteSaleRepository(database));
        sales = new SaleService(saleRepository, new SqliteProductRepository(database));
        store = new PendingCheckoutStore(directory.resolve("pending-checkout.txt"));
        rice = products.create(shop, "Rice", 1_000);
        receive(rice, 10_000);
    }

    private void receive(Product product, long quantityMilli) throws Exception {
        stock.receive(UUID.randomUUID(), shop, product.id(), device, quantityMilli, "Test delivery");
    }

    private static <T> T edt(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(work.get()));
        return result.get();
    }

    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }

    private SalesPanel open() throws Exception { return open(Locale.ENGLISH, stock, products); }

    private SalesPanel open(Locale locale, StockService stockService, ProductService productService) throws Exception {
        ResourceBundle bundle = Messages.load(locale).bundle();
        SalesPanel panel = edt(() -> new SalesPanel(sales, productService, families, stockService,
                shop, device, store, bundle));
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
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton button(SalesPanel panel, String name) {
        JButton button = find(panel, JButton.class, name);
        assertNotNull(button, name);
        return button;
    }

    private static JTable results(SalesPanel panel) { return find(panel, JTable.class, "salesResults"); }
    private static JTable basket(SalesPanel panel) { return find(panel, JTable.class, "salesBasket"); }
    private static JTextField search(SalesPanel panel) { return find(panel, JTextField.class, "salesSearch"); }
    private static String notice(SalesPanel panel) { return find(panel, JTextArea.class, "salesNotice").getText(); }
    private static String total(SalesPanel panel) { return find(panel, JLabel.class, "salesTotal").getText(); }

    private static void click(SalesPanel panel, String name) throws Exception {
        edt(() -> button(panel, name).doClick());
    }

    /** Adds the first search result and sets its quantity, as the cashier would. */
    private static void addToBasket(SalesPanel panel, String query, String quantity) throws Exception {
        edt(() -> {
            search(panel).setText(query);
            button(panel, "salesAdd").doClick();
            int row = basket(panel).getRowCount() - 1;
            basket(panel).setValueAt(quantity, row, SalesPanel.COLUMN_QUANTITY);
        });
    }

    private static void confirmCash(SalesPanel panel) throws Exception {
        click(panel, "salesCheckout");
        click(panel, "salesConfirm");
        waitUntilIdle(panel);
    }

    private long riceLeft() throws Exception { return stock.current(shop, rice.id()).quantityMilli(); }

    private List<Sale> savedSales() throws Exception { return sales.listSales(shop); }

    @Test
    void zeroStockCannotBeAddedByButtonOrKeyboardAndCharacteristicsKeepTheirNames() throws Exception {
        Product shirt=products.create(shop,"T-shirt",5000,null,null,"Vêtements","piece",Map.of("Matière","Coton"));
        SalesPanel panel=open(Locale.FRENCH,stock,products);
        edt(()->{
            find(panel,JTextField.class,"salesSearch").setText("t-shirt");
            assertEquals("Matière : Coton",find(panel,JTable.class,"salesResults").getValueAt(0,2));
            assertFalse(find(panel,JButton.class,"salesAdd").isEnabled());
            assertTrue(find(panel,JTextArea.class,"salesResultsHint").getText().contains("Stock"));
            JTable results=find(panel,JTable.class,"salesResults");
            results.getActionMap().get("addToBasket").actionPerformed(null);
            assertEquals(0,find(panel,JTable.class,"salesBasket").getRowCount());
        });
        receive(shirt,3000);edt(panel::reload);waitUntilIdle(panel);
        edt(()->{assertTrue(find(panel,JButton.class,"salesAdd").isEnabled());
            find(panel,JButton.class,"salesAdd").doClick();
            assertEquals(1,find(panel,JTable.class,"salesBasket").getRowCount());});
    }

    @Test
    void sellingTwoOfTenLeavesEightAndOneSaleEvenAfterARestart() throws Exception {
        SalesPanel panel = open();
        assertEquals("10 pieces", edt(() -> results(panel).getValueAt(0, 3)));
        addToBasket(panel, "rice", "2");
        assertEquals("Total: 2,000 FCFA", edt(() -> total(panel)));

        confirmCash(panel);

        assertEquals(8_000, riceLeft());
        assertEquals(1, savedSales().size());
        Sale sale = savedSales().getFirst();
        assertEquals(2_000, sale.totalAmount());
        assertTrue(edt(() -> notice(panel)).contains(sale.receiptNumber()));
        assertTrue(edt(() -> notice(panel)).contains("2,000"));
        assertEquals(0, (int) edt(() -> basket(panel).getRowCount()));
        assertEquals("8 pieces", edt(() -> results(panel).getValueAt(0, 3)));
        assertFalse(Files.exists(store.file()));

        SalesPanel reopened = open();
        assertEquals("8 pieces", edt(() -> results(reopened).getValueAt(0, 3)));
        assertEquals(List.of(sale.id()), savedSales().stream().map(Sale::id).toList());
    }

    @Test
    void repeatedClicksCreateOnlyOneSale() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        click(panel, "salesCheckout");
        edt(() -> {
            JButton confirm = button(panel, "salesConfirm");
            confirm.doClick();
            confirm.doClick();
            confirm.doClick();
        });
        waitUntilIdle(panel);
        assertEquals(1, saleRepository.calls.get());
        assertEquals(1, savedSales().size());
        assertEquals(8_000, riceLeft());
    }

    @Test
    void theCashMustBeConfirmedAndBackLeavesTheBasketAlone() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        click(panel, "salesCheckout");
        assertTrue(edt(() -> notice(panel)).contains("2,000"));
        assertEquals(0, saleRepository.calls.get());
        click(panel, "salesBack");
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertEquals(10_000, riceLeft());
    }

    @Test
    void searchFindsAVariantByReferenceFamilyOrCharacteristicButNeverAFamily() throws Exception {
        var family = families.create(shop, "Riz parfumé", "Épicerie");
        Product basmati = families.createVariant(shop, family.id(), "Basmati 25 kg", 28_000, "SUP-25", null, null,
                "bag", Map.of("Couleur", "Blanc"));
        families.createVariant(shop, family.id(), "Jasmine 5 kg", 5_000, null, null, null, "bag", Map.of());
        SalesPanel panel = open();
        assertEquals(3, (int) edt(() -> results(panel).getRowCount()));

        for (String query : new String[] {basmati.reference(), "parfume blanc", "RIZ 25", "sup-25", "épicerie basmati"}) {
            edt(() -> search(panel).setText(query));
            assertEquals(1, (int) edt(() -> results(panel).getRowCount()), query);
            assertEquals("Riz parfumé - Basmati 25 kg", edt(() -> results(panel).getValueAt(0, 0)));
            assertEquals(basmati.reference(), edt(() -> results(panel).getValueAt(0, 1)));
        }
        edt(() -> search(panel).setText("nothing like this"));
        assertEquals(0, (int) edt(() -> results(panel).getRowCount()));
        assertFalse(edt(() -> button(panel, "salesAdd").isEnabled()));
        // The family name alone matches its two variants, never the family itself.
        edt(() -> search(panel).setText("riz parfume"));
        assertEquals(2, (int) edt(() -> results(panel).getRowCount()));
    }

    @Test
    void basketLinesCanBeChangedAndShowExactRoundedAmounts() throws Exception {
        Product sugar = products.create(shop, "Sugar", 333, null, null, null, "kg", Map.of());
        receive(sugar, 5_000);
        SalesPanel panel = open();

        addToBasket(panel, "sugar", "1.5");
        assertEquals("kg", edt(() -> basket(panel).getValueAt(0, 2)));
        assertEquals("1.5", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
        assertEquals("333", edt(() -> basket(panel).getValueAt(0, 4)));
        assertEquals("500", edt(() -> basket(panel).getValueAt(0, 5)));     // 499.5 rounds up
        assertEquals("5 kg", edt(() -> basket(panel).getValueAt(0, 6)));
        assertEquals("Total: 500 FCFA", edt(() -> total(panel)));

        addToBasket(panel, "rice", "3");
        assertEquals("Total: 3,500 FCFA", edt(() -> total(panel)));

        edt(() -> {
            basket(panel).setRowSelectionInterval(1, 1);
            button(panel, "salesRemove").doClick();
        });
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertEquals("Total: 500 FCFA", edt(() -> total(panel)));

        confirmCash(panel);
        assertEquals(500, savedSales().getFirst().totalAmount());
        assertEquals(3_500, stock.current(shop, sugar.id()).quantityMilli());
    }

    @Test
    void addingTheSameVariantTwiceAddsToItsLine() throws Exception {
        SalesPanel panel = open();
        edt(() -> {
            search(panel).setText("rice");
            button(panel, "salesAdd").doClick();
            search(panel).setText("rice");
            button(panel, "salesAdd").doClick();
        });
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertEquals("2", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
    }

    @Test
    void enterInAnEmptySearchBoxAddsNothing() throws Exception {
        Product beans = products.create(shop, "Beans", 500);
        receive(beans, 10000);
        SalesPanel panel = open();
        edt(() -> search(panel).postActionEvent());
        assertEquals(0, (int) edt(() -> basket(panel).getRowCount()));

        // The box empties after adding: a second Enter must not add the first product in the list.
        edt(() -> {
            search(panel).setText("beans");
            search(panel).postActionEvent();
            search(panel).postActionEvent();
        });
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertEquals("Beans", edt(() -> basket(panel).getValueAt(0, 0)));
        assertEquals("1", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
    }

    @Test
    void theArrowKeysChooseAResultEvenWithAnEmptySearchBox() throws Exception {
        Product beans = products.create(shop, "Beans", 500);
        receive(beans, 10000);
        SalesPanel panel = open();
        edt(() -> {
            search(panel).getActionMap().get("nextResult").actionPerformed(null);
            search(panel).postActionEvent();
        });
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertEquals(edt(() -> results(panel).getValueAt(1, 0)), edt(() -> basket(panel).getValueAt(0, 0)));

        // The choice is used once: another Enter adds nothing.
        edt(() -> search(panel).postActionEvent());
        assertEquals("1", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
    }

    @Test
    void aWrongQuantityShowsAMessageAndKeepsTheLine() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        for (String wrong : new String[] {"1.5", "abc", "0", "", "99999999999999999999"}) {
            edt(() -> basket(panel).setValueAt(wrong, 0, SalesPanel.COLUMN_QUANTITY));
            assertEquals("2", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)), wrong);
            assertFalse(edt(() -> notice(panel)).isBlank(), wrong);
        }
    }

    @Test
    void anAmountTooLargeIsRefusedAndTheBasketKeepsWorking() throws Exception {
        Product gold = products.create(shop, "Gold", Long.MAX_VALUE / 2);
        receive(gold, 10_000);
        SalesPanel panel = open();
        addToBasket(panel, "gold", "1");
        edt(() -> basket(panel).setValueAt("3", 0, SalesPanel.COLUMN_QUANTITY));
        assertEquals("1", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
        assertTrue(edt(() -> notice(panel)).contains("too large"));
    }

    @Test
    void theBuyingPriceNeverAppears() throws Exception {
        Product oil = products.create(shop, "Oil", 1_500, null, 987_654L, null, "litre", Map.of());
        receive(oil, 5000);
        SalesPanel panel = open();
        addToBasket(panel, "oil", "1");
        edt(() -> {
            for (JTable table : List.of(results(panel), basket(panel))) {
                for (int row = 0; row < table.getRowCount(); row++) {
                    for (int column = 0; column < table.getColumnCount(); column++) {
                        String cell = String.valueOf(table.getValueAt(row, column)).replaceAll("[^0-9]", "");
                        assertFalse(cell.contains("987654"), cell);
                    }
                }
            }
        });
    }

    @Test
    void notEnoughStockSellsNothingAndKeepsAUsableBasket() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "rice", "11");
        confirmCash(panel);

        assertEquals(0, savedSales().size());
        assertEquals(10_000, riceLeft());
        assertTrue(edt(() -> notice(panel)).contains("Not enough stock"));
        assertEquals("11", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
        assertFalse(Files.exists(store.file()));

        edt(() -> basket(panel).setValueAt("4", 0, SalesPanel.COLUMN_QUANTITY));
        confirmCash(panel);
        assertEquals(1, savedSales().size());
        assertEquals(6_000, riceLeft());
    }

    @Test
    void aRefusedBasketKeepsItsSaleIdUnlessItChanges() throws Exception {
        SalesPanel panel = open();
        addToBasket(panel, "rice", "11");
        confirmCash(panel);
        UUID refused = saleRepository.lastSaleId;

        receive(rice, 5_000);                           // the same basket can now be sold
        confirmCash(panel);
        assertEquals(refused, savedSales().getFirst().id());
        assertEquals(4_000, riceLeft());

        addToBasket(panel, "rice", "20");
        confirmCash(panel);
        UUID secondRefused = saleRepository.lastSaleId;
        assertFalse(refused.equals(secondRefused));
        edt(() -> basket(panel).setValueAt("1", 0, SalesPanel.COLUMN_QUANTITY));
        confirmCash(panel);
        assertFalse(secondRefused.equals(saleRepository.lastSaleId));   // a changed basket is a new checkout
        assertEquals(2, savedSales().size());
    }

    @Test
    void anUncertainResultLocksTheBasketAndRetryCreatesNoSecondSale() throws Exception {
        saleRepository.failAfterSaving = true;
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        confirmCash(panel);

        assertTrue(Files.exists(store.file()));
        assertTrue(edt(() -> notice(panel)).contains("could not be confirmed"));
        edt(() -> {
            assertTrue(button(panel, "salesRetry").isVisible());
            assertFalse(button(panel, "salesCheckout").isVisible());
            assertFalse(button(panel, "salesAdd").isEnabled());
            assertFalse(button(panel, "salesRemove").isEnabled());
            assertFalse(basket(panel).isCellEditable(0, SalesPanel.COLUMN_QUANTITY));
        });

        click(panel, "salesRetry");
        waitUntilIdle(panel);

        assertEquals(1, savedSales().size());
        assertEquals(8_000, riceLeft());
        assertEquals(0, (int) edt(() -> basket(panel).getRowCount()));
        assertFalse(Files.exists(store.file()));
        assertTrue(edt(() -> notice(panel)).contains(savedSales().getFirst().receiptNumber()));
    }

    @Test
    void aStorageFailureSavesNothingAndRetryFinishesTheSale() throws Exception {
        saleRepository.failBeforeSaving = true;
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        confirmCash(panel);
        assertEquals(0, savedSales().size());
        assertEquals(10_000, riceLeft());
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));

        click(panel, "salesRetry");
        waitUntilIdle(panel);
        assertEquals(1, savedSales().size());
        assertEquals(8_000, riceLeft());
    }

    @Test
    void cancellingAnUncertainSaleThatWasNotSavedGivesTheBasketBack() throws Exception {
        saleRepository.failBeforeSaving = true;
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        confirmCash(panel);

        click(panel, "salesDiscard");
        waitUntilIdle(panel);

        assertEquals(0, savedSales().size());
        assertFalse(Files.exists(store.file()));
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertTrue(edt(() -> button(panel, "salesCheckout").isEnabled()));
    }

    @Test
    void cancellingASaleThatWasReallySavedShowsItAsSaved() throws Exception {
        saleRepository.failAfterSaving = true;
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        confirmCash(panel);

        click(panel, "salesDiscard");
        waitUntilIdle(panel);

        assertEquals(1, savedSales().size());
        assertEquals(8_000, riceLeft());
        assertEquals(0, (int) edt(() -> basket(panel).getRowCount()));
        assertTrue(edt(() -> notice(panel)).contains(savedSales().getFirst().receiptNumber()));
    }

    @Test
    void ifTheDetailsCannotBeSavedNothingIsSentAndTheBasketStays() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("checkout-folder"));
        store = new PendingCheckoutStore(folder.resolve("pending-checkout.txt"));
        SalesPanel panel = open();
        addToBasket(panel, "rice", "2");
        // Fail storage after normal recovery/opening, as if the target became unavailable.
        Files.deleteIfExists(folder.resolve("pending-checkout.txt.lock"));
        Files.delete(folder);
        Files.writeString(folder,"not a directory anymore");
        confirmCash(panel);

        assertEquals(0, saleRepository.calls.get());
        assertEquals(0, savedSales().size());
        assertEquals(1, (int) edt(() -> basket(panel).getRowCount()));
        assertTrue(edt(() -> notice(panel)).contains("not sent"));
        assertTrue(edt(() -> button(panel, "salesCheckout").isEnabled()));
    }

    private PendingCheckout unfinishedSale() {
        return PendingCheckout.start(shop, device,
                List.of(BasketLine.oneOf(rice, null).withQuantity(2_000)), LocalDate.of(2026, 11, 5));
    }

    @Test
    void afterARestartAnUnfinishedSaleIsShownAndNeverSentByItself() throws Exception {
        PendingCheckout pending = unfinishedSale();
        store.save(pending);

        SalesPanel panel = open();

        assertEquals(0, saleRepository.calls.get());
        assertEquals(10_000, riceLeft());
        assertTrue(edt(() -> notice(panel)).contains(pending.receiptNumber()));
        assertEquals("2", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
        assertTrue(edt(() -> button(panel, "salesRetry").isVisible()));
        assertFalse(edt(() -> basket(panel).isCellEditable(0, SalesPanel.COLUMN_QUANTITY)));

        click(panel, "salesRetry");
        waitUntilIdle(panel);

        Sale sale = savedSales().getFirst();
        assertEquals(pending.saleId(), sale.id());
        assertEquals(pending.receiptNumber(), sale.receiptNumber());
        assertEquals(8_000, riceLeft());
        assertEquals(1, savedSales().size());
    }

    @Test
    void afterARestartAnUnfinishedSaleCanBeCancelled() throws Exception {
        store.save(unfinishedSale());
        SalesPanel panel = open();

        click(panel, "salesDiscard");
        waitUntilIdle(panel);

        assertEquals(0, savedSales().size());
        assertFalse(Files.exists(store.file()));
        assertTrue(edt(() -> basket(panel).isCellEditable(0, SalesPanel.COLUMN_QUANTITY)));
    }

    @Test
    void afterARestartASaleThatWasReallySavedIsReportedAndNothingIsLocked() throws Exception {
        saleRepository.failAfterSaving = true;
        SalesPanel before = open();
        addToBasket(before, "rice", "2");
        confirmCash(before);

        SalesPanel panel = open();

        assertEquals(1, savedSales().size());
        assertEquals(8_000, riceLeft());
        assertFalse(Files.exists(store.file()));
        assertEquals(0, (int) edt(() -> basket(panel).getRowCount()));
        assertTrue(edt(() -> notice(panel)).contains(savedSales().getFirst().receiptNumber()));
        assertTrue(edt(() -> button(panel, "salesAdd").isEnabled()));
    }

    @Test
    void aDamagedFileBlocksNewSalesUntilItIsSetAsideAndIsNeverDeleted() throws Exception {
        Files.writeString(store.file(), "not our file");
        SalesPanel panel = open();

        assertTrue(edt(() -> notice(panel)).contains(store.file().toString()));
        edt(() -> {
            assertTrue(button(panel, "salesSetAside").isVisible());
            assertFalse(button(panel, "salesAdd").isEnabled());
            assertFalse(button(panel, "salesCheckout").isVisible());
        });
        assertEquals("not our file", Files.readString(store.file()));

        click(panel, "salesSetAside");
        waitUntilIdle(panel);

        assertFalse(Files.exists(store.file()));
        try (var kept = Files.list(directory)) {
            Path copy = kept.filter(path -> path.getFileName().toString().startsWith("pending-checkout-damaged-"))
                    .findFirst().orElseThrow();
            assertEquals("not our file", Files.readString(copy));
            assertTrue(edt(() -> notice(panel)).contains(copy.getFileName().toString()));
        }
        assertTrue(edt(() -> button(panel, "salesAdd").isEnabled()));
    }

    @Test
    void aDamagedFileThatWasMovedByHandIsNoticedByRefresh() throws Exception {
        Files.writeString(store.file(), "not our file");
        SalesPanel panel = open();
        assertTrue(edt(() -> button(panel, "salesSetAside").isVisible()));

        Files.move(store.file(), directory.resolve("moved-by-hand.txt"));
        click(panel, "salesRefresh");
        waitUntilIdle(panel);

        assertFalse(edt(() -> button(panel, "salesSetAside").isVisible()));
        assertTrue(edt(() -> button(panel, "salesAdd").isEnabled()));
    }

    @Test
    void unknownStockIsNotShownAsZero() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(true);
        StockRepository real = new SqliteStockRepository(database);
        StockRepository flaky = new StockRepository() {
            @Override public StockResult record(StockMovement movement) throws SQLException { return real.record(movement); }
            @Override public StockLevel current(UUID s, UUID p) throws SQLException { return real.current(s, p); }
            @Override public List<StockLevel> list(UUID s) throws SQLException {
                if (failing.get()) throw new SQLException("Simulated stock failure");
                return real.list(s);
            }
            @Override public List<StockMovement> history(UUID s, UUID p) throws SQLException { return real.history(s, p); }
            @Override public StockLevel setMinimum(UUID s, UUID p, long q) throws SQLException { return real.setMinimum(s, p, q); }
        };
        SalesPanel panel = open(Locale.ENGLISH, new StockService(flaky), products);
        assertEquals("Unavailable", edt(() -> results(panel).getValueAt(0, 3)));

        failing.set(false);
        click(panel, "salesRefresh");
        waitUntilIdle(panel);
        assertEquals("10 pieces", edt(() -> results(panel).getValueAt(0, 3)));
    }

    @Test
    void aLoadingFailureIsNotShownAsAnEmptyShopAndRefreshRecovers() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(true);
        ProductRepository real = new SqliteProductRepository(database);
        ProductRepository flaky = new ProductRepository() {
            @Override public void insert(Product product) throws SQLException { real.insert(product); }
            @Override public List<Product> findByShop(UUID s) throws SQLException {
                if (failing.get()) throw new SQLException("Simulated load failure");
                return real.findByShop(s);
            }
        };
        SalesPanel panel = open(Locale.ENGLISH, stock, new ProductService(flaky));
        assertEquals(0, (int) edt(() -> results(panel).getRowCount()));
        assertTrue(edt(() -> find(panel, JTextArea.class, "salesResultsHint").getText()).contains("could not be loaded"));

        failing.set(false);
        click(panel, "salesRefresh");
        waitUntilIdle(panel);
        assertEquals(1, (int) edt(() -> results(panel).getRowCount()));
    }

    @Test
    void anEmptyShopSaysSo() throws Exception {
        UUID otherShop = UUID.randomUUID();
        ResourceBundle bundle = Messages.load(Locale.ENGLISH).bundle();
        SalesPanel panel = edt(() -> new SalesPanel(sales, products, families, stock, otherShop, device, store, bundle));
        edt(panel::reload);
        waitUntilIdle(panel);
        assertTrue(edt(() -> find(panel, JTextArea.class, "salesResultsHint").getText()).contains("No products yet"));
        assertFalse(edt(() -> button(panel, "salesCheckout").isEnabled()));
    }

    @Test
    void frenchShowsFrenchTextAndTheCommaDecimal() throws Exception {
        Product sugar = products.create(shop, "Sucre", 1_000, null, null, null, "kg", Map.of());
        receive(sugar, 5_000);
        SalesPanel panel = open(Locale.FRENCH, stock, products);
        addToBasket(panel, "sucre", "1,5");
        assertEquals("1,5", edt(() -> basket(panel).getValueAt(0, SalesPanel.COLUMN_QUANTITY)));
        assertTrue(edt(() -> total(panel)).startsWith("Total : 1"));
        assertEquals("Encaisser", edt(() -> button(panel, "salesCheckout").getText()));
    }

    @Test
    void theConstructorTouchesNoDatabase() throws Exception {
        SalesPanel panel = edt(() -> new SalesPanel(sales, products, families, stock, shop, device, store,
                Messages.load(Locale.ENGLISH).bundle()));
        assertFalse(edt(panel::isBusy));
        assertFalse(edt(() -> button(panel, "salesAdd").isEnabled()));
    }

    @Test
    void englishAndFrenchHaveTheSameSalesTexts() {
        ResourceBundle english = Messages.load(Locale.ENGLISH).bundle();
        ResourceBundle french = Messages.load(Locale.FRENCH).bundle();
        List<String> keys = english.keySet().stream().filter(key -> key.startsWith("sales.")).sorted().toList();
        assertFalse(keys.isEmpty());
        assertEquals(keys, french.keySet().stream().filter(key -> key.startsWith("sales.")).sorted().toList());
    }
}

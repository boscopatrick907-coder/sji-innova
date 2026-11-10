package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.desktop.ForcedClose;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import cm.sji.innova.desktop.shell.SalesSlot;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-02c forced-close drills for the cash sale.
 *
 * <p>A separate Java process opens the data folder the way Cella does at start-up, sells 2 of the 10
 * pieces of rice on the Sales screen, and is killed when the database has written the whole sale but
 * not committed it yet, or just after it committed. The test then opens the same folder again, as
 * restarting Cella does, and checks the stock, the number of sales, the sale's ID and what the pending
 * file holds. Disposable data only.
 */
class CheckoutForcedCloseDrillTest {
    private static final Messages ENGLISH = Messages.load(Locale.ENGLISH);

    @TempDir Path directory;

    private final List<SalesPanel> screens = new ArrayList<>();
    private LocalAppData location;
    private AppContext context;
    private Product rice;

    @BeforeEach
    void aShopWithTenPiecesOfRice() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        context = AppContextLoader.load(location);
        rice = context.products().create(context.shopId(), "Rice", 1_000);
        context.stock().receive(UUID.randomUUID(), context.shopId(), rice.id(), context.deviceId(), 10_000, "Delivery");
    }

    /** The other process: Cella's Sales screen, wired as the shell wires it, sells 2 pieces of rice. */
    public static class SalesScreen {
        public static void main(String[] args) throws Exception {
            ForcedClose.watchTheDatabase();
            AppContext context = AppContextLoader.load(new LocalAppData(Path.of(args[0])));
            SalesPanel panel = edt(() -> screen(context));
            edt(() -> { panel.reload(); return null; });
            waitUntilIdle(panel);
            edt(() -> {
                find(panel, JTextField.class, "salesSearch").setText("rice");
                button(panel, "salesAdd").doClick();
                find(panel, JTable.class, "salesBasket").setValueAt("2", 0, SalesPanel.COLUMN_QUANTITY);
                return null;
            });

            ForcedClose.stopAfter(args[1], Integer.parseInt(args[2]));
            edt(() -> {
                button(panel, "salesCheckout").doClick();
                button(panel, "salesConfirm").doClick();
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            boolean started = false;
            while (System.nanoTime() < deadline) {
                boolean busy = edt(panel::isBusy);
                if (busy) started = true;
                else if (started) break;
                Thread.sleep(10);
            }
            ForcedClose.finishedWithoutStopping();
        }
    }

    /** The Sales screen exactly as the shell builds it. */
    private static SalesPanel screen(AppContext context) {
        FlatLightLaf.setup();
        CatalogStyle.installDefaults();
        return SalesSlot.create(context, ENGLISH);
    }

    @Test
    void aSaleKilledJustBeforeTheDatabaseCommittedIsShownAfterTheRestartNotSentAndCountsOnce() throws Exception {
        // The sale's last statement before the commit: its stock change, lines and event are all written.
        ForcedClose.runAndKill(SalesScreen.class, location.directory(), "INSERT INTO sale_events", "1");

        PendingCheckout saved = savedSale();
        assertEquals(10_000, riceLeft(), "The uncommitted sale was rolled back, with its stock change");
        assertEquals(0, sales().size());

        byte[] before = Files.readAllBytes(pendingFile());
        SalesPanel restarted = restart();
        assertEquals(0, sales().size(), "Opening the screen sends nothing");
        assertEquals(10_000, riceLeft());
        assertArrayEquals(before, Files.readAllBytes(pendingFile()), "The saved sale is kept exactly");
        edt(() -> {
            assertTrue(notice(restarted).contains(saved.receiptNumber()), "The cashier is told which sale is unfinished");
            assertEquals("2", find(restarted, JTable.class, "salesBasket").getValueAt(0, SalesPanel.COLUMN_QUANTITY));
            assertTrue(button(restarted, "salesRetry").isVisible());
            return null;
        });

        edt(() -> { button(restarted, "salesRetry").doClick(); return null; });
        waitUntilIdle(restarted);

        assertEquals(List.of(saved.saleId()), sales().stream().map(Sale::id).toList(), "One sale, with the original ID");
        assertEquals(saved.receiptNumber(), sales().getFirst().receiptNumber());
        assertEquals(8_000, riceLeft());
        assertFalse(Files.exists(pendingFile()));
    }

    @Test
    void aSaleKilledJustAfterTheDatabaseCommittedItIsRecognisedByTheRestartAndCountsOnce() throws Exception {
        ForcedClose.runAndKill(SalesScreen.class, location.directory(), "COMMIT", "1");

        PendingCheckout saved = savedSale();
        assertEquals(8_000, riceLeft(), "The database committed it before the kill");
        assertEquals(List.of(saved.saleId()), sales().stream().map(Sale::id).toList());

        SalesPanel restarted = restart();

        assertFalse(Files.exists(pendingFile()), "Opening checked the database, found the sale and removed the file");
        assertEquals(List.of(saved.saleId()), sales().stream().map(Sale::id).toList(), "Still one sale");
        assertEquals(8_000, riceLeft());
        edt(() -> {
            assertTrue(notice(restarted).contains(saved.receiptNumber()), "The cashier is told the sale was saved");
            assertEquals(0, find(restarted, JTable.class, "salesBasket").getRowCount());
            assertTrue(button(restarted, "salesAdd").isEnabled(), "Selling can go on");
            return null;
        });
    }

    // ----- Helpers -----

    private Path pendingFile() { return location.pendingCheckoutFile(); }

    private PendingCheckout savedSale() throws Exception {
        assertTrue(Files.exists(pendingFile()), "The sale was saved on disk before the database was called");
        return new PendingCheckoutStore(pendingFile()).load().orElseThrow();
    }

    private long riceLeft() throws Exception { return context.stock().current(context.shopId(), rice.id()).quantityMilli(); }

    private List<Sale> sales() throws Exception { return context.sales().listSales(context.shopId()); }

    /** Starts Cella again on the same folder and opens the Sales screen, as the shell does. */
    private SalesPanel restart() throws Exception {
        AppContext restarted = AppContextLoader.load(location);
        SalesPanel panel = edt(() -> screen(restarted));
        screens.add(panel);
        edt(() -> { panel.reload(); return null; });
        waitUntilQuiet(panel);
        return panel;
    }

    /** Waits until the screen has stayed idle for a while, so anything opening would start has started. */
    private static void waitUntilQuiet(SalesPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int quietChecks = 0;
        while (quietChecks < 3) {
            assertTrue(System.nanoTime() < deadline, "The Sales screen never became quiet");
            Thread.sleep(100);
            quietChecks = edt(panel::isBusy) ? 0 : quietChecks + 1;
        }
    }

    @AfterEach
    void letEveryScreenFinish() throws Exception {
        for (SalesPanel panel : screens) waitUntilIdle(panel);
    }

    private static void waitUntilIdle(SalesPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (edt(panel::isBusy)) {
            assertTrue(System.nanoTime() < deadline, "The Sales screen is still busy");
            Thread.sleep(10);
        }
    }

    private static String notice(SalesPanel panel) { return find(panel, JTextArea.class, "salesNotice").getText(); }

    private static JButton button(SalesPanel panel, String name) {
        JButton button = find(panel, JButton.class, name);
        assertNotNull(button, name);
        return button;
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

    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }
}

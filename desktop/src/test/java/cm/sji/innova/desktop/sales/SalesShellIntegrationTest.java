package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.product.Product;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real start-up, the real shell and the real data folder, in a temporary folder. */
class SalesShellIntegrationTest {
    @TempDir Path directory;

    private static <T> T edt(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(work.get()));
        return result.get();
    }

    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }

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

    private static void waitUntilIdle(SalesPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (edt(panel::isBusy)) {
            assertTrue(System.nanoTime() < deadline, "The page is still busy");
            Thread.sleep(10);
        }
    }

    private record Window(ShellPanel shell, SalesPanel sales) { }

    /** Opens the shell, waits for Products, then selects Sales in the real sidebar. */
    private static Window openSales(AppContext context, Locale locale) throws Exception {
        ShellPanel shell = edt(() -> {
            ShellPanel panel = new ShellPanel(Messages.load(locale), context.location(), () -> context, () -> { });
            panel.showMain(context);
            return panel;
        });
        shell.productsReady().toCompletableFuture().get(10, TimeUnit.SECONDS);
        SalesPanel sales = edt(() -> find(shell, SalesPanel.class, null));
        assertNotNull(sales);
        assertFalse(edt(sales::isBusy));     // nothing is read until the page is opened
        assertEquals("products", edt(shell::selectedScreenId));
        edt(() -> shell.selectScreen("sales"));
        waitUntilIdle(sales);
        assertEquals("sales", edt(shell::selectedScreenId));
        return new Window(shell, sales);
    }

    @Test
    void sellThroughTheShellThenRestartAndSeeTheSameSaleAndBalance() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("shop"));
        AppContext first = AppContextLoader.load(location);
        Product rice = first.products().create(first.shopId(), "Rice", 1_000);
        first.stock().receive(UUID.randomUUID(), first.shopId(), rice.id(), first.deviceId(), 10_000, "Test delivery");

        Window window = openSales(first, Locale.ENGLISH);
        edt(() -> {
            find(window.sales(), JTextField.class, "salesSearch").setText("rice");
            find(window.sales(), JButton.class, "salesAdd").doClick();
            find(window.sales(), JTable.class, "salesBasket").setValueAt("2", 0, SalesPanel.COLUMN_QUANTITY);
            find(window.sales(), JButton.class, "salesCheckout").doClick();
            find(window.sales(), JButton.class, "salesConfirm").doClick();
        });
        waitUntilIdle(window.sales());

        assertFalse(Files.exists(location.pendingCheckoutFile()));
        assertEquals(8_000, first.stock().current(first.shopId(), rice.id()).quantityMilli());

        AppContext second = AppContextLoader.load(location);
        Window reopened = openSales(second, Locale.ENGLISH);
        assertEquals("8 pieces", edt(() -> find(reopened.sales(), JTable.class, "salesResults").getValueAt(0, 3)));
        var sales = second.sales().listSales(second.shopId());
        assertEquals(1, sales.size());
        assertEquals(2_000, sales.getFirst().totalAmount());
        assertEquals(first.deviceId(), sales.getFirst().deviceId());
    }

    @Test
    void theSidebarOffersProductsStockAndSalesAndTheHeadingFollows() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("shop"));
        AppContext context = AppContextLoader.load(location);
        Window french = openSales(context, Locale.FRENCH);
        var navigation = edt(() -> (JList<?>) find(french.shell(), JList.class, null));
        assertEquals(List.of("Produits", "Stock", "Ventes"), edt(() -> List.of(
                navigation.getModel().getElementAt(0).toString(),
                navigation.getModel().getElementAt(1).toString(),
                navigation.getModel().getElementAt(2).toString())));
        assertTrue(edt(() -> hasLabel(french.shell(), "Ventes")));
        assertFalse(edt(() -> hasLabel(french.shell(), "Catalogue")));
        assertEquals("Sales", Messages.load(Locale.ENGLISH).get("shell.nav.sales"));
    }

    private static boolean hasLabel(Component root, String text) {
        if (root instanceof JLabel label && text.equals(label.getText())) return true;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasLabel(child, text)) return true;
            }
        }
        return false;
    }
}

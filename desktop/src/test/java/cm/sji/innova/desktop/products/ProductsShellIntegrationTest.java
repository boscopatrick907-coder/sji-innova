package cm.sji.innova.desktop.products;

import cm.sji.innova.core.product.*;
import cm.sji.innova.desktop.shell.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductsShellIntegrationTest {
    @TempDir Path directory;

    private static ProductPanel findProducts(Component component) {
        if (component instanceof ProductPanel panel) return panel;
        if (component instanceof Container container) {
            for (var child : container.getComponents()) {
                ProductPanel found = findProducts(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private record Opened(ShellPanel shell, ProductPanel products) { }

    private Opened open(AppContext context) throws Exception {
        var opened = new AtomicReference<Opened>();
        SwingUtilities.invokeAndWait(() -> {
            var shell = new ShellPanel(Messages.load(Locale.ENGLISH), context.location(), () -> context, () -> { });
            shell.showMain(context);
            var panel = findProducts(shell);
            assertNotNull(panel, "The main window must contain the real Products panel, not a placeholder");
            assertEquals(ShellPanel.Page.MAIN, shell.currentPage());
            assertEquals("products", shell.selectedScreenId());
            opened.set(new Opened(shell, panel));
        });
        return opened.get();
    }

    @Test
    void savesThroughTheActualShellAndShowsTheSameProductAfterRestart() throws Exception {
        var location = new LocalAppData(directory.resolve("shop"));
        var first = AppContextLoader.load(location);
        var window = open(first);
        window.shell().productsReady().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var saved = new CountDownLatch(1);
        var reloaded = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            var panel = window.products();
            panel.setOnSaveWorkerDone(saved::countDown);
            panel.setOnLoadWorkerDone(reloaded::countDown);
            panel.nameField().setText("Blue T-shirt");
            panel.modelField().setText("Classic V2");
            panel.buyingPriceField().setText("2000");
            panel.sellingPriceField().setText("3000");
            panel.categoryField().setText("Clothing");
            panel.specificationRows().get(0).nameField().setText("color");
            panel.specificationRows().get(0).valueField().setText("blue");
            panel.addSpecRow();
            panel.specificationRows().get(1).nameField().setText("size");
            panel.specificationRows().get(1).valueField().setText("M");
            panel.saveButton().doClick();
        });
        assertTrue(saved.await(5, TimeUnit.SECONDS));
        assertTrue(reloaded.await(5, TimeUnit.SECONDS));
        Product original = first.products().list(first.shopId()).getFirst();
        assertEquals(3000, original.unitPrice());
        assertEquals(Long.valueOf(2000), original.costPrice());
        assertEquals("Classic V2", original.modelReference());
        assertEquals("Clothing", original.category());
        assertEquals("piece", original.unit());
        assertEquals(Map.of("color", "blue", "size", "M"), original.specifications());
        var reopened = AppContextLoader.load(location);
        assertEquals(first.shopId(), reopened.shopId());
        assertEquals(List.of(original), reopened.products().list(reopened.shopId()));
        var secondWindow = open(reopened);
        secondWindow.shell().productsReady().toCompletableFuture().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            var list = secondWindow.products().productListModel();
            assertEquals(1, list.size());
            assertTrue(list.get(0).contains("Blue T-shirt"));
            assertTrue(list.get(0).contains("3000 FCFA"));
            assertTrue(list.get(0).contains("2000 FCFA"));
            assertTrue(list.get(0).contains("color=blue"));
            assertTrue(list.get(0).contains("size=M"));
        });
    }

    @Test
    void startupReadinessReportsProductLoadingFailure() throws Exception {
        var failing = new ProductRepository() {
            @Override public void insert(Product product) { throw new UnsupportedOperationException(); }
            @Override public List<Product> findByShop(UUID shop) throws SQLException {
                throw new SQLException("Simulated load failure");
            }
        };
        var context = new AppContext(new LocalAppData(directory), UUID.randomUUID(), new ProductService(failing));
        var window = open(context);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> window.shell().productsReady().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(SQLException.class, failure.getCause());
        SwingUtilities.invokeAndWait(() -> assertEquals("Products could not be loaded",
                window.products().productListModel().get(0)));
    }
}

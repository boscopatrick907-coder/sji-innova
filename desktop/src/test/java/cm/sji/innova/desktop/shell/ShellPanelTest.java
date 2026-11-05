package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
import cm.sji.innova.core.product.ProductService;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Headless checks of the window logic, using a fake repository (no database, no screen). */
class ShellPanelTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    private static final ProductRepository EMPTY = new ProductRepository() {
        @Override public void insert(Product product) { }
        @Override public List<Product> findByShop(UUID shopId) { return List.of(); }
    };

    private ShellPanel newPanel(LocalAppData location) {
        return new ShellPanel(Messages.load(Locale.ENGLISH), location, () -> { throw new IllegalStateException("unused"); }, () -> { });
    }

    @Test
    void startsOnTheLoadingPage() {
        assertEquals(ShellPanel.Page.LOADING, newPanel(new LocalAppData(directory)).currentPage());
    }

    @Test
    void mainPageSelectsTheProductsEntry() throws Exception {
        LocalAppData location = new LocalAppData(directory);
        ShellPanel panel = newPanel(location);
        panel.showMain(TestAppContexts.withProducts(location, new ProductService(EMPTY)));
        assertEquals(ShellPanel.Page.MAIN, panel.currentPage());
        assertEquals("products", panel.selectedScreenId());
        panel.productsReady().handle((value,failure)->null).toCompletableFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void failureShowsTheErrorPageAndMainPageCanFollowAfterRetry() throws Exception {
        LocalAppData location = new LocalAppData(directory);
        ShellPanel panel = newPanel(location);
        panel.showError(new java.io.IOException("disk is full"));
        assertEquals(ShellPanel.Page.ERROR, panel.currentPage());
        panel.showMain(TestAppContexts.withProducts(location, new ProductService(EMPTY)));
        assertEquals(ShellPanel.Page.MAIN, panel.currentPage());
        panel.productsReady().handle((value,failure)->null).toCompletableFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
}

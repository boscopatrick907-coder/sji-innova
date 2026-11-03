package cm.sji.innova.desktop.products;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
import cm.sji.innova.core.product.ProductService;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductLoadOrderingTest {
    @TempDir Path directory;

    /** Holds real query snapshots after JDBC resources close; no timing-based sleeps. */
    private final class Loads implements AutoCloseable {
        final UUID shop = UUID.randomUUID();
        final SqliteProductRepository repository;
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);
        final CountDownLatch allDone = new CountDownLatch(2);
        final LinkedBlockingQueue<Boolean> completed = new LinkedBlockingQueue<>();
        final AtomicInteger errors = new AtomicInteger();
        final ProductPanel panel;

        Loads(boolean oldRequestFails, boolean holdSecond) throws Exception {
            var database = new SqliteDatabase(directory.resolve(UUID.randomUUID() + ".db"));
            database.initialize();
            repository = new SqliteProductRepository(database);
            var queries = new AtomicInteger();
            var controlled = new ProductRepository() {
                @Override public void insert(Product product) throws SQLException { repository.insert(product); }
                @Override public List<Product> findByShop(UUID shopId) throws SQLException {
                    var snapshot = repository.findByShop(shopId);
                    int request = queries.incrementAndGet();
                    if (request == 1) {
                        firstStarted.countDown();
                        waitFor(releaseFirst);
                        if (oldRequestFails) throw new SQLException("Older request failed");
                    } else if (request == 2) {
                        secondStarted.countDown();
                        if (holdSecond) waitFor(releaseSecond);
                    }
                    return snapshot;
                }
            };
            var reference = new AtomicReference<ProductPanel>();
            SwingUtilities.invokeAndWait(() -> {
                var created = new ProductPanel(new ProductService(controlled), shop, null, null, () -> {
                    completed.add(Boolean.TRUE);
                    allDone.countDown();
                });
                created.setErrorHandler(message -> errors.incrementAndGet());
                reference.set(created);
            });
            panel = reference.get();
        }

        void awaitCompletion() throws InterruptedException {
            assertNotNull(completed.poll(5, TimeUnit.SECONDS), "Load worker did not complete");
        }

        @Override public void close() throws Exception {
            releaseFirst.countDown();
            releaseSecond.countDown();
            allDone.await(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static void waitFor(CountDownLatch release) throws SQLException {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Test query release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException(interrupted);
        }
    }

    @Test
    void olderEmptyLoadCannotOverwriteThePostSaveRefresh() throws Exception {
        try (var loads = new Loads(false, false)) {
            assertTrue(loads.firstStarted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                loads.panel.nameField().setText("New product");
                loads.panel.sellingPriceField().setText("500");
                loads.panel.saveButton().doClick();
            });
            assertTrue(loads.secondStarted.await(5, TimeUnit.SECONDS));
            loads.awaitCompletion(); // New post-save load finishes first.
            SwingUtilities.invokeAndWait(() -> assertTrue(loads.panel.productListModel().get(0).contains("New product")));
            loads.releaseFirst.countDown();
            loads.awaitCompletion(); // Old empty snapshot finishes last.
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, loads.panel.productListModel().size());
                assertTrue(loads.panel.productListModel().get(0).contains("New product"));
                assertTrue(loads.panel.refreshButton().isEnabled());
            });
            assertEquals(1, loads.repository.findByShop(loads.shop).size());
            assertEquals(0, loads.errors.get());
        }
    }

    @Test
    void olderFailureCannotReplaceNewerSuccessOrDisplayAnObsoleteError() throws Exception {
        try (var loads = new Loads(true, false)) {
            assertTrue(loads.firstStarted.await(5, TimeUnit.SECONDS));
            new ProductService(loads.repository).create(loads.shop, "Existing product", 700);
            SwingUtilities.invokeAndWait(loads.panel::loadProducts);
            assertTrue(loads.secondStarted.await(5, TimeUnit.SECONDS));
            loads.awaitCompletion();
            loads.releaseFirst.countDown();
            loads.awaitCompletion();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(loads.panel.productListModel().get(0).contains("Existing product"));
                assertTrue(loads.panel.refreshButton().isEnabled());
            });
            assertEquals(0, loads.errors.get(), "A superseded failure must not show an error");
        }
    }

    @Test
    void olderCompletionCannotUnlockRefreshWhileCurrentLoadIsPending() throws Exception {
        try (var loads = new Loads(false, true)) {
            assertTrue(loads.firstStarted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(loads.panel::loadProducts);
            assertTrue(loads.secondStarted.await(5, TimeUnit.SECONDS));
            loads.releaseFirst.countDown();
            loads.awaitCompletion(); // Old request completes while the newer request is held.
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(loads.panel.refreshButton().isEnabled());
                assertEquals("Loading…", loads.panel.productListModel().get(0));
            });
            loads.releaseSecond.countDown();
            loads.awaitCompletion();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(loads.panel.refreshButton().isEnabled());
                assertEquals("No products yet", loads.panel.productListModel().get(0));
            });
        }
    }
}

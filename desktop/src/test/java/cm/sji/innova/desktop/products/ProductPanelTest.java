package cm.sji.innova.desktop.products;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
import cm.sji.innova.core.product.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProductPanelTest {

    @TempDir
    Path tempDir;

    private ProductService createService() throws Exception {
        SqliteDatabase database = new SqliteDatabase(tempDir.resolve("test-products-" + UUID.randomUUID() + ".db"));
        database.initialize();
        return new ProductService(new SqliteProductRepository(database));
    }

    private ProductPanel createPanelAndWaitForInitialLoad(ProductService service, UUID shopId) throws Exception {
        CountDownLatch initialLoadLatch = new CountDownLatch(1);
        AtomicReference<ProductPanel> panelRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ProductPanel panel = new ProductPanel(service, shopId, null, null, initialLoadLatch::countDown);
            panel.setErrorHandler(msg -> {});
            panelRef.set(panel);
        });

        assertTrue(initialLoadLatch.await(5, TimeUnit.SECONDS), "Initial load timed out");
        return panelRef.get();
    }

    @Test
    void panelInitializesAndRendersAllProductDetailsHeadlessly() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();

        // 1. Product with full details (known buying price)
        service.create(
                shopId,
                "T-shirt",
                3000,
                "Classic V2",
                2000L,
                "Clothing",
                "piece",
                Map.of("color", "blue", "size", "M")
        );

        // 2. Product with unknown buying price (null)
        service.create(
                shopId,
                "USB Cable",
                1500,
                null,
                null,
                "Electronics",
                "piece",
                Map.of()
        );

        // 3. Product with zero buying price (0 FCFA, not unknown)
        service.create(
                shopId,
                "Free Sample Sticker",
                0,
                "PROMO-01",
                0L,
                "Merchandise",
                "piece",
                Map.of("type", "vinyl")
        );

        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);
        assertNotNull(panel);

        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<String> model = panel.productListModel();
            assertEquals(3, model.size());

            String tshirtItem = null;
            String cableItem = null;
            String zeroBuyingItem = null;

            for (int i = 0; i < model.size(); i++) {
                String item = model.get(i);
                if (item.contains("T-shirt")) tshirtItem = item;
                if (item.contains("USB Cable")) cableItem = item;
                if (item.contains("Free Sample Sticker")) zeroBuyingItem = item;
            }

            // Verify full details rendered for T-shirt
            assertNotNull(tshirtItem);
            assertTrue(tshirtItem.contains("T-shirt"));
            assertTrue(tshirtItem.contains("Classic V2"));
            assertTrue(tshirtItem.contains("3000 FCFA"));
            assertTrue(tshirtItem.contains("2000 FCFA"));
            assertTrue(tshirtItem.contains("Clothing"));
            assertTrue(tshirtItem.contains("Piece"));
            assertTrue(tshirtItem.contains("color=blue"));
            assertTrue(tshirtItem.contains("size=M"));

            // Verify unknown buying price explicitly rendered as "Unknown"
            assertNotNull(cableItem);
            assertTrue(cableItem.contains("USB Cable"));
            assertTrue(cableItem.contains("1500 FCFA"));
            assertTrue(cableItem.contains("Unknown"));
            assertTrue(cableItem.contains("Electronics"));
            assertTrue(cableItem.contains("Piece"));

            // Verify zero buying price explicitly rendered as "0 FCFA" (NOT "Unknown")
            assertNotNull(zeroBuyingItem);
            assertTrue(zeroBuyingItem.contains("Free Sample Sticker"));
            assertTrue(zeroBuyingItem.contains("PROMO-01"));
            assertTrue(zeroBuyingItem.contains("0 FCFA"));
            assertFalse(zeroBuyingItem.contains("Unknown"));
            assertTrue(zeroBuyingItem.contains("Merchandise"));
        });
    }

    @Test
    void setFormEnabledControlsAllEditableComponents() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();
        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);

        SwingUtilities.invokeAndWait(() -> {
            panel.addSpecRow();
            assertEquals(2, panel.specificationRows().size());

            // Disable form
            panel.setFormEnabled(false);

            assertFalse(panel.nameField().isEnabled());
            assertFalse(panel.modelField().isEnabled());
            assertFalse(panel.buyingPriceField().isEnabled());
            assertFalse(panel.sellingPriceField().isEnabled());
            assertFalse(panel.categoryField().isEnabled());
            assertFalse(panel.unitBox().isEnabled());
            assertFalse(panel.addSpecificationButton().isEnabled());
            assertFalse(panel.saveButton().isEnabled());

            for (ProductPanel.SpecRow row : panel.specificationRows()) {
                assertFalse(row.nameField().isEnabled());
                assertFalse(row.valueField().isEnabled());
                assertFalse(row.removeButton().isEnabled());
            }

            // Re-enable form
            panel.setFormEnabled(true);

            assertTrue(panel.nameField().isEnabled());
            assertTrue(panel.modelField().isEnabled());
            assertTrue(panel.buyingPriceField().isEnabled());
            assertTrue(panel.sellingPriceField().isEnabled());
            assertTrue(panel.categoryField().isEnabled());
            assertTrue(panel.unitBox().isEnabled());
            assertTrue(panel.addSpecificationButton().isEnabled());
            assertTrue(panel.saveButton().isEnabled());

            for (ProductPanel.SpecRow row : panel.specificationRows()) {
                assertTrue(row.nameField().isEnabled());
                assertTrue(row.valueField().isEnabled());
                assertTrue(row.removeButton().isEnabled());
            }
        });
    }

    @Test
    void saveProductWithValidInputsPersistsClearsFormAndReloads() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();
        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);

        CountDownLatch saveLatch = new CountDownLatch(1);
        CountDownLatch reloadLatch = new CountDownLatch(1);
        panel.setOnSaveWorkerDone(saveLatch::countDown);
        panel.setOnLoadWorkerDone(reloadLatch::countDown);

        SwingUtilities.invokeAndWait(() -> {
            panel.nameField().setText("Classic Shirt");
            panel.modelField().setText("CS-100");
            panel.buyingPriceField().setText("1500");
            panel.sellingPriceField().setText("2500");
            panel.categoryField().setText("Fashion");
            panel.unitBox().setSelectedIndex(0);

            panel.specificationRows().get(0).nameField().setText("Color");
            panel.specificationRows().get(0).valueField().setText("White");

            panel.saveButton().doClick();
        });

        assertTrue(saveLatch.await(5, TimeUnit.SECONDS), "Save worker timed out");
        assertTrue(reloadLatch.await(5, TimeUnit.SECONDS), "Reload worker timed out");

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(panel.nameField().isEnabled());
            assertTrue(panel.saveButton().isEnabled());
            assertFalse(panel.isSavePending());

            // Form inputs cleared on success
            assertEquals("", panel.nameField().getText());
            assertEquals("", panel.modelField().getText());
            assertEquals("", panel.buyingPriceField().getText());
            assertEquals("", panel.sellingPriceField().getText());
            assertEquals("", panel.categoryField().getText());

            assertEquals("Product saved", panel.statusLabel().getText());
            assertEquals(1, panel.productListModel().size());
            assertTrue(panel.productListModel().get(0).contains("Classic Shirt"));
        });

        List<Product> products = service.list(shopId);
        assertEquals(1, products.size());
        Product saved = products.get(0);
        assertEquals("Classic Shirt", saved.name());
        assertEquals("CS-100", saved.modelReference());
        assertEquals(1500L, saved.costPrice());
        assertEquals(2500L, saved.unitPrice());
        assertEquals("Fashion", saved.category());
        assertEquals(Map.of("Color", "White"), saved.specifications());
    }

    @Test
    void slowSaveDisablesAllControlsAndPreventsDuplicateSubmission() throws Exception {
        SqliteDatabase database = new SqliteDatabase(tempDir.resolve("slow-save.db"));
        database.initialize();
        SqliteProductRepository realRepo = new SqliteProductRepository(database);

        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch allowSaveToProceed = new CountDownLatch(1);
        AtomicInteger insertCallCount = new AtomicInteger(0);

        ProductRepository blockingRepo = new ProductRepository() {
            @Override
            public void insert(Product product) throws SQLException {
                insertCallCount.incrementAndGet();
                saveStarted.countDown();
                try {
                    assertTrue(allowSaveToProceed.await(5, TimeUnit.SECONDS), "allowSaveToProceed timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
                realRepo.insert(product);
            }

            @Override
            public List<Product> findByShop(UUID shop) throws SQLException {
                return realRepo.findByShop(shop);
            }
        };

        ProductService service = new ProductService(blockingRepo);
        UUID shopId = UUID.randomUUID();
        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);

        CountDownLatch saveDoneLatch = new CountDownLatch(1);
        CountDownLatch reloadDoneLatch = new CountDownLatch(1);
        panel.setOnSaveWorkerDone(saveDoneLatch::countDown);
        panel.setOnLoadWorkerDone(reloadDoneLatch::countDown);

        SwingUtilities.invokeAndWait(() -> {
            panel.nameField().setText("Slow Save Product");
            panel.modelField().setText("SS-01");
            panel.buyingPriceField().setText("1000");
            panel.sellingPriceField().setText("2000");
            panel.categoryField().setText("Books");

            panel.specificationRows().get(0).nameField().setText("Author");
            panel.specificationRows().get(0).valueField().setText("Patrick");

            panel.saveButton().doClick();
        });

        // 1. Wait until save operation is running in background thread
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS), "Save operation did not start in time");

        // 2. While save is running, verify on EDT that every control is disabled and duplicate submits are blocked
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(panel.isSavePending(), "savePending must be true while saving");
            assertFalse(panel.nameField().isEnabled());
            assertFalse(panel.modelField().isEnabled());
            assertFalse(panel.buyingPriceField().isEnabled());
            assertFalse(panel.sellingPriceField().isEnabled());
            assertFalse(panel.categoryField().isEnabled());
            assertFalse(panel.unitBox().isEnabled());
            assertFalse(panel.addSpecificationButton().isEnabled());
            assertFalse(panel.saveButton().isEnabled());

            for (ProductPanel.SpecRow row : panel.specificationRows()) {
                assertFalse(row.nameField().isEnabled());
                assertFalse(row.valueField().isEnabled());
                assertFalse(row.removeButton().isEnabled());
            }

            // Attempt duplicate submission: click and invoke saveProduct
            panel.saveButton().doClick();
            panel.saveProduct();
        });

        // 3. Unblock the background thread
        allowSaveToProceed.countDown();

        // 4. Wait for save and reload to finish
        assertTrue(saveDoneLatch.await(5, TimeUnit.SECONDS), "Save worker did not finish");
        assertTrue(reloadDoneLatch.await(5, TimeUnit.SECONDS), "Reload worker did not finish");

        // 5. Verify on EDT that controls are restored and form is cleared
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(panel.isSavePending());
            assertTrue(panel.nameField().isEnabled());
            assertTrue(panel.saveButton().isEnabled());
            assertEquals("", panel.nameField().getText());
        });

        // 6. Verify duplicate submission was prevented: exactly 1 product inserted
        assertEquals(1, insertCallCount.get(), "Duplicate submission occurred");
        assertEquals(1, service.list(shopId).size());
    }

    @Test
    void saveProductPreservesFormAndRestoresControlsOnFailure() throws Exception {
        UUID shopId = UUID.randomUUID();

        ProductRepository failingRepo = new ProductRepository() {
            @Override
            public void insert(Product product) throws SQLException {
                throw new SQLException("Simulated database failure");
            }

            @Override
            public List<Product> findByShop(UUID shop) {
                return List.of();
            }
        };

        ProductService failingService = new ProductService(failingRepo);
        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicReference<ProductPanel> panelRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ProductPanel panel = new ProductPanel(failingService, shopId, null, null, initLatch::countDown);
            panel.setErrorHandler(msg -> {});
            panelRef.set(panel);
        });

        assertTrue(initLatch.await(5, TimeUnit.SECONDS), "Initial load timed out");
        ProductPanel panel = panelRef.get();

        CountDownLatch saveLatch = new CountDownLatch(1);
        panel.setOnSaveWorkerDone(saveLatch::countDown);

        SwingUtilities.invokeAndWait(() -> {
            panel.nameField().setText("Preserved Product");
            panel.modelField().setText("PR-99");
            panel.buyingPriceField().setText("1200");
            panel.sellingPriceField().setText("1800");
            panel.categoryField().setText("Hardware");

            panel.saveButton().doClick();
        });

        assertTrue(saveLatch.await(5, TimeUnit.SECONDS), "Save worker timed out");

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(panel.nameField().isEnabled());
            assertTrue(panel.saveButton().isEnabled());
            assertFalse(panel.isSavePending());

            // Inputs preserved on failure
            assertEquals("Preserved Product", panel.nameField().getText());
            assertEquals("PR-99", panel.modelField().getText());
            assertEquals("1200", panel.buyingPriceField().getText());
            assertEquals("1800", panel.sellingPriceField().getText());
            assertEquals("Hardware", panel.categoryField().getText());
        });
    }

    @Test
    void rejectsNegativeSellingPrice() throws Exception {
        assertPriceRejected("-500", "1000");
    }

    @Test
    void rejectsDecimalSellingPrice() throws Exception {
        assertPriceRejected("12.5", "1000");
    }

    @Test
    void rejectsNonNumericSellingPrice() throws Exception {
        assertPriceRejected("abc", "1000");
    }

    @Test
    void rejectsOverflowSellingPrice() throws Exception {
        assertPriceRejected("999999999999999999999999999999999999", "1000");
    }

    @Test
    void rejectsNegativeBuyingPrice() throws Exception {
        assertPriceRejected("2000", "-200");
    }

    @Test
    void rejectsDecimalBuyingPrice() throws Exception {
        assertPriceRejected("2000", "12.5");
    }

    @Test
    void rejectsNonNumericBuyingPrice() throws Exception {
        assertPriceRejected("2000", "xyz");
    }

    @Test
    void rejectsOverflowBuyingPrice() throws Exception {
        assertPriceRejected("2000", "999999999999999999999999999999999999");
    }

    private void assertPriceRejected(String sellingPrice, String buyingPrice) throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();
        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);

        AtomicReference<String> errorReceived = new AtomicReference<>();
        panel.setErrorHandler(errorReceived::set);

        SwingUtilities.invokeAndWait(() -> {
            panel.nameField().setText("Test Product");
            panel.sellingPriceField().setText(sellingPrice);
            panel.buyingPriceField().setText(buyingPrice);
            panel.saveButton().doClick();
        });

        // Error was triggered synchronously by validation before worker started
        assertNotNull(errorReceived.get(), "Validation error should have been reported");
        assertTrue(service.list(shopId).isEmpty(), "No product should be saved on validation failure");

        SwingUtilities.invokeAndWait(() -> {
            // Form inputs preserved
            assertEquals("Test Product", panel.nameField().getText());
            assertEquals(sellingPrice, panel.sellingPriceField().getText());
            assertEquals(buyingPrice, panel.buyingPriceField().getText());
        });
    }

    @Test
    void rejectsDuplicateSpecificationNames() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();
        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);

        AtomicReference<String> errorReceived = new AtomicReference<>();
        panel.setErrorHandler(errorReceived::set);

        SwingUtilities.invokeAndWait(() -> {
            panel.nameField().setText("Spec Test Product");
            panel.sellingPriceField().setText("3000");
            panel.buyingPriceField().setText("2000");

            // Row 1: Color = Blue
            panel.specificationRows().get(0).nameField().setText("Color");
            panel.specificationRows().get(0).valueField().setText("Blue");

            // Row 2: Color = Red (Duplicate trimmed key)
            panel.addSpecRow();
            panel.specificationRows().get(1).nameField().setText(" Color ");
            panel.specificationRows().get(1).valueField().setText("Red");

            panel.saveButton().doClick();
        });

        assertNotNull(errorReceived.get());
        assertTrue(errorReceived.get().toLowerCase().contains("duplicate")
                || errorReceived.get().toLowerCase().contains("caractéristique"));
        assertTrue(service.list(shopId).isEmpty(), "No product should be saved with duplicate specifications");

        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Spec Test Product", panel.nameField().getText());
            assertEquals(2, panel.specificationRows().size());
        });
    }

    @Test
    void refreshButtonReloadsProductsFromDatabase() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();
        ProductPanel panel = createPanelAndWaitForInitialLoad(service, shopId);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, panel.productListModel().size());
            assertEquals("No products yet", panel.productListModel().get(0));
        });

        // Add product directly to SQLite
        service.create(shopId, "Direct DB Product", 4000);

        CountDownLatch refreshLatch = new CountDownLatch(1);
        panel.setOnLoadWorkerDone(refreshLatch::countDown);

        SwingUtilities.invokeAndWait(() -> {
            panel.refreshButton().doClick();
        });

        assertTrue(refreshLatch.await(5, TimeUnit.SECONDS), "Refresh worker timed out");

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, panel.productListModel().size());
            assertTrue(panel.productListModel().get(0).contains("Direct DB Product"));
            assertTrue(panel.productListModel().get(0).contains("4000 FCFA"));
        });
    }

    @Test
    void panelWorksWithCustomResourceBundle() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();

        ResourceBundle frenchBundle = new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][]{
                        {"products.title", "Produits"},
                        {"product.name", "Nom"},
                        {"product.sellingPrice", "Prix de vente (FCFA)"},
                        {"product.buyingPrice", "Prix d’achat (FCFA)"},
                        {"unit.piece", "Pièce"},
                        {"action.save", "Enregistrer"},
                        {"action.refresh", "Actualiser"}
                };
            }
        };

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ProductPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ProductPanel panel = new ProductPanel(service, shopId, frenchBundle, null, latch::countDown);
            panelRef.set(panel);
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        ProductPanel panel = panelRef.get();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Enregistrer", panel.saveButton().getText());
            assertEquals("Actualiser", panel.refreshButton().getText());
        });
    }

    @Test
    void unitOptionPreservesCanonicalCodeWhileTranslatingDisplay() {
        ProductPanel.UnitOption option = new ProductPanel.UnitOption("piece", "Pièce");
        assertEquals("piece", option.code());
        assertEquals("Pièce", option.displayName());
        assertEquals("Pièce", option.toString());
    }
}

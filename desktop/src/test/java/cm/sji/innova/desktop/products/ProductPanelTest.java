package cm.sji.innova.desktop.products;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductPanelTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private ProductService createService() throws Exception {
        SqliteDatabase database = new SqliteDatabase(tempDir.resolve("test-products.db"));
        database.initialize();
        return new ProductService(new SqliteProductRepository(database));
    }

    @Test
    void panelInitializesAndLoadsExistingProductsHeadlessly() throws Exception {
        ProductService service = createService();
        UUID shopId = UUID.randomUUID();

        // Seed a product with full details
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

        // Seed another product with unknown buying price (null)
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

        SwingUtilities.invokeAndWait(() -> {
            ProductPanel panel = new ProductPanel(service, shopId);
            assertNotNull(panel);
        });

        List<Product> products = service.list(shopId);
        assertEquals(2, products.size());

        Product tshirt = products.stream()
                .filter(p -> p.name().equals("T-shirt"))
                .findFirst()
                .orElseThrow();

        assertEquals(3000, tshirt.unitPrice());
        assertEquals(2000L, tshirt.costPrice());
        assertEquals("Classic V2", tshirt.modelReference());
        assertEquals("Clothing", tshirt.category());
        assertEquals("piece", tshirt.unit());
        assertEquals(Map.of("color", "blue", "size", "M"), tshirt.specifications());

        Product cable = products.stream()
                .filter(p -> p.name().equals("USB Cable"))
                .findFirst()
                .orElseThrow();
        assertNull(cable.costPrice());
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

        SwingUtilities.invokeAndWait(() -> {
            ProductPanel panel = new ProductPanel(service, shopId, frenchBundle);
            assertNotNull(panel);
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

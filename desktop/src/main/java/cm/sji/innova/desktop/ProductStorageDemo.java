package cm.sji.innova.desktop;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.product.ProductService;
import java.nio.file.Path;
import java.util.UUID;

/** Explicit demonstration data, separate from the future cashier interface. */
final class ProductStorageDemo {
    private static final UUID DEMO_SHOP = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ProductStorageDemo() { }

    static void run(Path file) throws Exception {
        SqliteDatabase database = new SqliteDatabase(file);
        database.initialize();
        ProductService products = new ProductService(new SqliteProductRepository(database));
        if (products.list(DEMO_SHOP).isEmpty()) {
            products.create(DEMO_SHOP, "Demo product", 500, "Classic V2", 300L,
                    "Clothing", "piece", java.util.Map.of("color", "blue", "size", "M"));
            System.out.println("Created sample product");
        } else {
            System.out.println("Loaded existing products; no sample added");
        }
        for (var product : products.list(DEMO_SHOP)) {
            System.out.printf("%s | %s | selling: %d FCFA | buying: %s | model: %s | category: %s | unit: %s | %s%n",
                    product.id(), product.name(), product.unitPrice(),
                    product.costPrice() == null ? "unknown" : product.costPrice() + " FCFA",
                    product.modelReference(), product.category(), product.unit(), product.specifications());
        }
    }
}

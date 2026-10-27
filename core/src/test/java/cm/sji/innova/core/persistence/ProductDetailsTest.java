package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.ProductService;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductDetailsTest {
    @TempDir Path directory;

    private ProductService service(SqliteDatabase database) {
        return new ProductService(new SqliteProductRepository(database));
    }

    private void createVersionOne(SqliteDatabase database, UUID id, UUID shop) throws Exception {
        try (var input = getClass().getResourceAsStream("/schema-v1.sql");
             var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            statement.execute("CREATE INDEX products_shop_id ON products(shop_id)");
            statement.execute("PRAGMA user_version = 1");
            try (var insert = connection.prepareStatement("INSERT INTO products VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, id.toString());
                insert.setString(2, shop.toString());
                insert.setString(3, "Existing product");
                insert.setLong(4, 3500);
                insert.setString(5, "2026-10-20T00:00:00Z");
                insert.setString(6, "2026-10-21T00:00:00Z");
                insert.setLong(7, 4);
                insert.executeUpdate();
            }
        }
    }

    @Test
    void upgradesVersionOneWithoutInventingBuyingPriceOrChangingExistingData() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("old.db"));
        UUID id = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        createVersionOne(database, id, shop);
        database.initialize();
        database.initialize();
        var products = service(database).list(shop);
        assertEquals(1, products.size());
        var product = products.getFirst();
        assertEquals(id, product.id());
        assertEquals("Existing product", product.name());
        assertEquals(3500, product.unitPrice());
        assertEquals(4, product.version());
        assertEquals("2026-10-20T00:00:00Z", product.createdAt().toString());
        assertEquals("2026-10-21T00:00:00Z", product.updatedAt().toString());
        assertNull(product.costPrice());
        assertNull(product.modelReference());
        assertNull(product.category());
        assertEquals("piece", product.unit());
        assertTrue(product.specifications().isEmpty());
        try (var connection = database.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(SqliteDatabase.SCHEMA_VERSION, rows.getInt(1));
        }
    }

    @Test
    void preservesDetailsAndSeparateVariantsAcrossReopening() throws Exception {
        Path path = directory.resolve("products.db");
        SqliteDatabase database = new SqliteDatabase(path);
        database.initialize();
        UUID shop = UUID.randomUUID();
        var blue = service(database).create(shop, "T-shirt", 3000, "Classic V2", 2000L,
                "Clothing", "piece", Map.of("color", "blue", "size", "M", "material", "Coton d'Égypte"));
        var red = service(database).create(shop, "T-shirt", 3200, "Classic V2", 2100L,
                "Clothing", "piece", Map.of("color", "red", "size", "M"));
        SqliteDatabase reopened = new SqliteDatabase(path);
        reopened.initialize();
        var saved = service(reopened).list(shop);
        assertEquals(2, saved.size());
        assertTrue(saved.containsAll(List.of(blue, red)));
        assertNotEquals(blue.id(), red.id());
        assertThrows(UnsupportedOperationException.class, () -> blue.specifications().put("size", "L"));
    }

    @Test
    void acceptsWeightAndVolumeAndDistinguishesUnknownFromZeroBuyingPrice() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("products.db"));
        database.initialize();
        UUID shop = UUID.randomUUID();
        var rice = service(database).create(shop, "Rice", 500, null, null, "Food", "kg", Map.of());
        var oil = service(database).create(shop, "Oil", 1500, null, 0L, "Food", "litre", Map.of());
        assertTrue(service(database).list(shop).containsAll(List.of(rice, oil)));
    }

    @Test
    void rejectsInvalidDetailsWithoutSaving() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("products.db"));
        database.initialize();
        UUID shop = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service(database).create(shop, "Rice", 500,
                null, -1L, null, "piece", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service(database).create(shop, "Rice", 500,
                null, null, null, "bucket", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service(database).create(shop, "Rice", 500,
                null, null, null, "piece", Map.of("color", "  ")));
        assertThrows(IllegalArgumentException.class, () -> service(database).create(shop, "Rice", 500,
                null, null, null, "piece", Map.of("color", "red", " color ", "blue")));
        assertTrue(service(database).list(shop).isEmpty());
    }

    @Test
    void rollsBackProductAndEarlierSpecificationsIfLaterSpecificationFails() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("products.db"));
        database.initialize();
        UUID shop = UUID.randomUUID();
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TRIGGER fail_size BEFORE INSERT ON product_specifications
                WHEN NEW.name = 'size' BEGIN SELECT RAISE(ABORT, 'Simulated write failure'); END
                """);
        }
        assertThrows(java.sql.SQLException.class, () -> service(database).create(shop, "T-shirt", 3000,
                "Classic V2", 2000L, "Clothing", "piece", Map.of("color", "blue", "size", "M")));
        assertTrue(service(database).list(shop).isEmpty());
        try (var connection = database.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM product_specifications")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void failedMigrationRollsBackSchemaAndCanBeRetried() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("old.db"));
        UUID id = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        createVersionOne(database, id, shop);
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE product_specifications (unexpected TEXT)");
        }
        assertThrows(java.sql.SQLException.class, database::initialize);
        try (var connection = database.open(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM pragma_table_info('products') WHERE name = 'cost_price'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            statement.execute("DROP TABLE product_specifications");
        }
        database.initialize();
        assertEquals(id, service(database).list(shop).getFirst().id());
    }
}

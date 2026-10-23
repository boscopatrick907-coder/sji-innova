package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductStorageTest {
    @TempDir Path directory;

    private ProductService service(SqliteDatabase database) {
        return new ProductService(new SqliteProductRepository(database));
    }

    @Test
    void preservesExactProductAfterReopeningAndInitializingAgain() throws Exception {
        Path file = directory.resolve("nested/shop.db");
        UUID shop = UUID.randomUUID();
        SqliteDatabase database = new SqliteDatabase(file);
        database.initialize();
        Product saved = service(database).create(shop, "L'huile d'arachide — café", 5_000_000_000L);
        SqliteDatabase reopened = new SqliteDatabase(file);
        reopened.initialize();
        assertEquals(List.of(saved), service(reopened).list(shop));
        try (var connection = reopened.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rows.next());
            assertEquals("wal", rows.getString(1));
        }
    }

    @Test
    void shopQueriesDoNotReturnAnotherShopsProducts() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("shop.db"));
        database.initialize();
        UUID firstShop = UUID.randomUUID();
        UUID secondShop = UUID.randomUUID();
        Product first = service(database).create(firstShop, "Rice", 500);
        Product second = service(database).create(secondShop, "Rice", 700);
        assertEquals(List.of(first), service(database).list(firstShop));
        assertEquals(List.of(second), service(database).list(secondShop));
        assertTrue(service(database).list(UUID.randomUUID()).isEmpty());
    }

    @Test
    void rejectsInvalidInputWithoutPersistingIt() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("shop.db"));
        database.initialize();
        UUID shop = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service(database).create(shop, "  ", 500));
        assertThrows(IllegalArgumentException.class, () -> service(database).create(shop, "Rice", -1));
        assertTrue(service(database).list(shop).isEmpty());
        assertEquals(0, service(database).create(shop, "Free sample", 0).unitPrice());
    }

    @Test
    void duplicateIdFailsWithoutReplacingTheExistingProduct() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("shop.db"));
        database.initialize();
        UUID shop = UUID.randomUUID();
        Product original = service(database).create(shop, "Rice", 500);
        Product changed = new Product(original.id(), shop, "Changed", 900,
                original.createdAt(), original.updatedAt(), 1);
        assertThrows(SQLException.class, () -> new SqliteProductRepository(database).insert(changed));
        assertEquals(List.of(original), service(database).list(shop));
    }

    @Test
    void refusesNewerDatabaseWithoutChangingItsVersionOrData() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("shop.db"));
        database.initialize();
        UUID shop = UUID.randomUUID();
        Product original = service(database).create(shop, "Rice", 500);
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }
        assertThrows(SQLException.class, database::initialize);
        assertEquals(List.of(original), service(database).list(shop));
        try (var connection = database.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(99, rows.getInt(1));
        }
    }
}

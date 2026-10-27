package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.*;
import cm.sji.innova.core.stock.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StockStorageTest {
    @TempDir Path directory;
    SqliteDatabase database;
    StockService stock;
    ProductService products;
    final UUID shop = UUID.randomUUID();
    final UUID device = UUID.randomUUID();
    Product product;

    @BeforeEach
    void setup() throws Exception {
        database = new SqliteDatabase(directory.resolve("stock.db"));
        database.initialize();
        stock = new StockService(new SqliteStockRepository(database));
        products = new ProductService(new SqliteProductRepository(database));
        product = products.create(shop, "T-shirt", 3000);
    }

    StockResult receive(UUID operation, long quantity) throws Exception {
        return stock.receive(operation, shop, product.id(), device, quantity, "Delivery");
    }

    void assertConsistent(long expected) throws Exception {
        assertEquals(expected, stock.current(shop, product.id()).quantityMilli());
        assertEquals(expected, stock.history(shop, product.id()).stream().mapToLong(StockMovement::quantityMilli).sum());
    }

    @Test
    void receivesAndAdjustsWithHistoryAfterReopening() throws Exception {
        receive(UUID.randomUUID(), 10_000);
        receive(UUID.randomUUID(), 5_000);
        stock.adjust(UUID.randomUUID(), shop, product.id(), device, -2_000, "Damaged items");
        assertConsistent(13_000);
        database = new SqliteDatabase(directory.resolve("stock.db"));
        database.initialize();
        stock = new StockService(new SqliteStockRepository(database));
        assertConsistent(13_000);
        assertEquals(3, stock.history(shop, product.id()).size());
        assertEquals(List.of(new StockLevel(shop, product.id(), "T-shirt", "piece", 13_000, 0)), stock.list(shop));
    }

    @Test
    void exactRetryReturnsOriginalMovementWithoutCountingAgainEvenAfterLaterChanges() throws Exception {
        UUID operation = UUID.randomUUID();
        var original = receive(operation, 10_000);
        assertFalse(original.replayed());
        receive(UUID.randomUUID(), 5_000);
        var retry = receive(operation, 10_000);
        assertTrue(retry.replayed());
        assertEquals(original.movement(), retry.movement());
        assertEquals(15_000, retry.stock().quantityMilli());
        assertEquals(2, stock.history(shop, product.id()).size());
        assertConsistent(15_000);
    }

    @Test
    void rejectsReusedOperationWithChangedBusinessDetails() throws Exception {
        UUID operation = UUID.randomUUID();
        receive(operation, 10_000);
        assertThrows(IllegalArgumentException.class, () -> receive(operation, 20_000));
        assertThrows(IllegalArgumentException.class, () -> stock.receive(operation, shop, product.id(),
                UUID.randomUUID(), 10_000, "Delivery"));
        assertThrows(IllegalArgumentException.class, () -> stock.adjust(operation, shop, product.id(),
                device, 10_000, "Delivery"));
        assertConsistent(10_000);
    }

    @Test
    void rejectsInvalidQuantitiesReasonAndWrongShopWithoutChanges() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> receive(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> receive(UUID.randomUUID(), -1_000));
        assertThrows(IllegalArgumentException.class, () -> receive(UUID.randomUUID(), 500));
        assertThrows(IllegalArgumentException.class, () -> stock.receive(UUID.randomUUID(), shop,
                product.id(), device, 1_000, "  "));
        assertThrows(IllegalArgumentException.class, () -> stock.receive(UUID.randomUUID(), shop,
                UUID.randomUUID(), device, 1_000, "Delivery"));
        UUID other = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> stock.receive(UUID.randomUUID(), other,
                product.id(), device, 1_000, "Delivery"));
        assertThrows(IllegalArgumentException.class, () -> stock.current(other, product.id()));
        assertThrows(IllegalArgumentException.class, () -> stock.history(other, product.id()));
        assertThrows(IllegalArgumentException.class, () -> stock.setMinimum(other, product.id(), 1_000));
        assertTrue(stock.list(other).isEmpty());
        assertConsistent(0);
    }

    @Test
    void supportsFractionalKgAndLitresButCannotManuallyGoBelowZero() throws Exception {
        for (String unit : List.of("kg", "litre")) {
            var measured = products.create(shop, "Measured", 1000, null, null, null, unit, Map.of());
            stock.receive(UUID.randomUUID(), shop, measured.id(), device, 1_500, "Delivery");
            stock.adjust(UUID.randomUUID(), shop, measured.id(), device, -250, "Measured loss");
            assertEquals(1_250, stock.current(shop, measured.id()).quantityMilli());
            assertThrows(IllegalArgumentException.class, () -> stock.adjust(UUID.randomUUID(), shop,
                    measured.id(), device, -1_251, "Too much"));
            assertEquals(1_250, stock.current(shop, measured.id()).quantityMilli());
        }
        assertThrows(IllegalArgumentException.class, () -> stock.adjust(UUID.randomUUID(), shop,
                product.id(), device, -1_000, "Missing item"));
    }

    @Test
    void thresholdFlagsAtAndBelowMinimumAndDoesNotChangeMovementHistory() throws Exception {
        assertTrue(stock.current(shop, product.id()).lowStock());
        receive(UUID.randomUUID(), 10_000);
        assertFalse(stock.setMinimum(shop, product.id(), 2_000).lowStock());
        assertTrue(stock.setMinimum(shop, product.id(), 10_000).lowStock());
        assertTrue(stock.setMinimum(shop, product.id(), 11_000).lowStock());
        var catalog = products.list(shop).getFirst();
        stock.setMinimum(shop, product.id(), 11_000);
        assertEquals(catalog, products.list(shop).getFirst());
        assertTrue(catalog.version() > product.version());
        assertThrows(IllegalArgumentException.class, () -> stock.setMinimum(shop, product.id(), -1));
        assertThrows(IllegalArgumentException.class, () -> stock.setMinimum(shop, product.id(), 500));
        assertConsistent(10_000);
    }

    @Test
    void failureAfterMovementInsertRollsBackMovementAndStock() throws Exception {
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TRIGGER reject_cache_update BEFORE UPDATE OF current_stock_milli ON products
                BEGIN SELECT RAISE(ABORT, 'Simulated storage failure'); END
                """);
        }
        UUID operation = UUID.randomUUID();
        assertThrows(SQLException.class, () -> receive(operation, 10_000));
        assertConsistent(0);
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_cache_update");
        }
        assertFalse(receive(operation, 10_000).replayed());
        assertConsistent(10_000);
    }

    @Test
    void refusesIntegerOverflowWithoutChangingHistoryOrCache() throws Exception {
        var measured = products.create(shop, "Oil", 1000, null, null, null, "litre", Map.of());
        stock.receive(UUID.randomUUID(), shop, measured.id(), device, Long.MAX_VALUE, "Delivery");
        assertThrows(ArithmeticException.class, () -> stock.receive(UUID.randomUUID(), shop,
                measured.id(), device, 1, "Overflow"));
        assertEquals(Long.MAX_VALUE, stock.current(shop, measured.id()).quantityMilli());
        assertEquals(1, stock.history(shop, measured.id()).size());
    }

    @Test
    void immutableHistoryRejectsUpdatesAndDeletes() throws Exception {
        UUID operation = UUID.randomUUID();
        receive(operation, 10_000);
        try (var connection = database.open(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE stock_movements SET reason = 'changed'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM stock_movements"));
        }
        assertConsistent(10_000);
    }

    @Test
    void concurrentReceiptsDoNotLoseUpdatesAndConcurrentRetriesCountOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            UUID same = UUID.randomUUID();
            List<Callable<StockResult>> retries = List.of(() -> receive(same, 10_000), () -> receive(same, 10_000));
            int replayed = 0;
            for (var future : executor.invokeAll(retries)) if (future.get(10, TimeUnit.SECONDS).replayed()) replayed++;
            assertEquals(1, replayed);
            List<Callable<StockResult>> separate = List.of(() -> receive(UUID.randomUUID(), 1_000),
                    () -> receive(UUID.randomUUID(), 1_000));
            for (var future : executor.invokeAll(separate)) future.get(10, TimeUnit.SECONDS);
        }
        assertConsistent(12_000);
        assertEquals(3, stock.history(shop, product.id()).size());
    }

    @Test
    void migratesVersionTwoPreservingProductDetailsAndInitializesEmptyStock() throws Exception {
        SqliteDatabase old = new SqliteDatabase(directory.resolve("legacy.db"));
        try (var input = getClass().getResourceAsStream("/schema-v2.sql");
             var connection = old.open(); var statement = connection.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        var original = products.list(shop).getFirst();
        var legacyProducts = new ProductService(new SqliteProductRepository(old));
        legacyProducts.create(shop, "Legacy blue shirt", 3000, "Classic V2", 2000L,
                "Clothing", "piece", Map.of("color", "blue", "size", "M"));
        var before = legacyProducts.list(shop);
        old.initialize();
        old.initialize();
        assertEquals(before, legacyProducts.list(shop));
        var legacyStock = new StockService(new SqliteStockRepository(old));
        assertEquals(0, legacyStock.list(shop).getFirst().quantityMilli());
        assertEquals(0, legacyStock.list(shop).getFirst().minimumMilli());
        assertTrue(legacyStock.history(shop, before.getFirst().id()).isEmpty());
        assertEquals(original, products.list(shop).getFirst());
    }

    @Test
    void failedStockMigrationRollsBackColumnsAndVersionBeforeRetry() throws Exception {
        SqliteDatabase old = new SqliteDatabase(directory.resolve("legacy.db"));
        try (var input = getClass().getResourceAsStream("/schema-v2.sql");
             var connection = old.open(); var statement = connection.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            statement.execute("CREATE TABLE stock_movements (unexpected TEXT)");
        }
        assertThrows(SQLException.class, old::initialize);
        try (var connection = old.open(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM pragma_table_info('products') WHERE name = 'current_stock_milli'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            statement.execute("DROP TABLE stock_movements");
        }
        old.initialize();
        assertTrue(new StockService(new SqliteStockRepository(old)).list(shop).isEmpty());
    }
}

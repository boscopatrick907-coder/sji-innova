package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.core.sale.SaleResult;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockMovement;
import cm.sji.innova.core.stock.StockService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SaleStorageTest {
    @TempDir Path directory;
    SqliteDatabase database;
    ProductService products;
    StockService stock;
    SaleService sales;

    final UUID shopId = UUID.randomUUID();
    final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        database = new SqliteDatabase(directory.resolve("shop.db"));
        database.initialize();
        SqliteProductRepository productRepo = new SqliteProductRepository(database);
        products = new ProductService(productRepo);
        stock = new StockService(new SqliteStockRepository(database));
        sales = new SaleService(new SqliteSaleRepository(database), productRepo);
    }

    @Test
    void teamImmediateMilestone_createProduct_receiveTen_sellTwo_reopen_verifyEightRemain() throws Exception {
        // 1. Create a product: Polo T-shirt at 3000 FCFA
        Product product = products.create(shopId, "Polo T-shirt", 3000, "Ref-01", 2000L,
                "Vêtements", "piece", Map.of("size", "L"));

        // 2. Receive 10 pieces (10_000 milli-units)
        stock.receive(UUID.randomUUID(), shopId, product.id(), deviceId, 10_000L, "Initial stock reception");
        assertEquals(10_000L, stock.current(shopId, product.id()).quantityMilli());

        // 3. Make an offline cash sale of 2 pieces (2_000 milli-units)
        UUID saleId = UUID.randomUUID();
        SaleResult saleResult = sales.makeCashSale(saleId, shopId, deviceId, "REC-2026-001",
                List.of(new SaleItemRequest(product.id(), 2_000L)));

        assertFalse(saleResult.replayed());
        assertEquals(6000L, saleResult.sale().totalAmount()); // 2 pieces * 3000 FCFA
        assertEquals(1, saleResult.sale().items().size());
        assertEquals(8_000L, stock.current(shopId, product.id()).quantityMilli());

        // 4. Simulate application restart
        SqliteDatabase reopened = new SqliteDatabase(directory.resolve("shop.db"));
        reopened.initialize();
        SqliteProductRepository reopenedProductRepo = new SqliteProductRepository(reopened);
        StockService reopenedStock = new StockService(new SqliteStockRepository(reopened));
        SaleService reopenedSales = new SaleService(new SqliteSaleRepository(reopened), reopenedProductRepo);

        // 5. Verify exactly 8 remain and sale exists
        assertEquals(8_000L, reopenedStock.current(shopId, product.id()).quantityMilli());

        Optional<Sale> loadedSale = reopenedSales.getSale(shopId, saleId);
        assertTrue(loadedSale.isPresent());
        assertEquals("REC-2026-001", loadedSale.get().receiptNumber());
        assertEquals(6000L, loadedSale.get().totalAmount());

        // 6. Verify movement history has delivery (+10000) and sale (-2000)
        List<StockMovement> history = reopenedStock.history(shopId, product.id());
        assertEquals(2, history.size());
        // History is timestamp/ID ordered, not newest-first; inspect each type.
        // Equal clock timestamps must not make this test depend on random UUID order.
        assertEquals(-2_000L, history.stream().filter(m -> m.type() == StockMovement.Type.SALE)
                .findFirst().orElseThrow().quantityMilli());
        assertEquals(10_000L, history.stream().filter(m -> m.type() == StockMovement.Type.DELIVERY)
                .findFirst().orElseThrow().quantityMilli());
    }

    @Test
    void rejectsSaleWhenStockIsInsufficientAndRollsBackCleanly() throws Exception {
        Product product = products.create(shopId, "Savon", 500);
        stock.receive(UUID.randomUUID(), shopId, product.id(), deviceId, 1_000L, "Stock of 1 item");

        // Attempt to sell 2 items (2_000 milli-units) when only 1 is available
        UUID saleId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () ->
                sales.makeCashSale(saleId, shopId, deviceId, "REC-002",
                        List.of(new SaleItemRequest(product.id(), 2_000L))));

        // Verify stock is untouched (still 1 item)
        assertEquals(1_000L, stock.current(shopId, product.id()).quantityMilli());
        // Verify sale was NOT recorded
        assertTrue(sales.getSale(shopId, saleId).isEmpty());
        // Verify only initial delivery movement exists
        assertEquals(1, stock.history(shopId, product.id()).size());
    }

    @Test
    void retryWithSameSaleIdIsIdempotentAndDoesNotDeductStockTwice() throws Exception {
        Product product = products.create(shopId, "Huile", 1500);
        stock.receive(UUID.randomUUID(), shopId, product.id(), deviceId, 5_000L, "Stock of 5");

        UUID saleId = UUID.randomUUID();
        SaleResult firstAttempt = sales.makeCashSale(saleId, shopId, deviceId, "REC-003",
                List.of(new SaleItemRequest(product.id(), 1_000L)));
        assertFalse(firstAttempt.replayed());
        assertEquals(4_000L, stock.current(shopId, product.id()).quantityMilli());

        // Retry same saleId
        SaleResult secondAttempt = sales.makeCashSale(saleId, shopId, deviceId, "REC-003",
                List.of(new SaleItemRequest(product.id(), 1_000L)));
        assertTrue(secondAttempt.replayed());
        assertEquals(4_000L, stock.current(shopId, product.id()).quantityMilli());
        assertEquals(2, stock.history(shopId, product.id()).size());
    }

    @Test
    void laterItemFailureRollsBackEarlierStockDeductionAndMovement() throws Exception {
        Product available = products.create(shopId, "Available", 500);
        Product empty = products.create(shopId, "Empty", 700);
        stock.receive(UUID.randomUUID(), shopId, available.id(), deviceId, 5_000, "Delivery");
        UUID saleId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> sales.makeCashSale(saleId, shopId, deviceId,
                "ROLLBACK-001", List.of(new SaleItemRequest(available.id(), 1_000),
                        new SaleItemRequest(empty.id(), 1_000))));
        assertEquals(5_000, stock.current(shopId, available.id()).quantityMilli());
        assertEquals(1, stock.history(shopId, available.id()).size());
        assertTrue(stock.history(shopId, empty.id()).isEmpty());
        assertTrue(sales.getSale(shopId, saleId).isEmpty());
        try (var connection = database.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM sale_items")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void saleItemWriteFailureRollsBackSaleHeaderAndStockAndAllowsRetry() throws Exception {
        Product product = products.create(shopId, "Soap", 500);
        stock.receive(UUID.randomUUID(), shopId, product.id(), deviceId, 5_000, "Delivery");
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TRIGGER fail_sale_item BEFORE INSERT ON sale_items
                BEGIN SELECT RAISE(ABORT, 'Simulated item write failure'); END
                """);
        }
        UUID saleId = UUID.randomUUID();
        assertThrows(java.sql.SQLException.class, () -> sales.makeCashSale(saleId, shopId, deviceId,
                "ROLLBACK-002", List.of(new SaleItemRequest(product.id(), 1_000))));
        assertEquals(5_000, stock.current(shopId, product.id()).quantityMilli());
        assertEquals(1, stock.history(shopId, product.id()).size());
        assertTrue(sales.getSale(shopId, saleId).isEmpty());
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_sale_item");
        }
        assertFalse(sales.makeCashSale(saleId, shopId, deviceId, "ROLLBACK-002",
                List.of(new SaleItemRequest(product.id(), 1_000))).replayed());
        assertEquals(4_000, stock.current(shopId, product.id()).quantityMilli());
    }
}

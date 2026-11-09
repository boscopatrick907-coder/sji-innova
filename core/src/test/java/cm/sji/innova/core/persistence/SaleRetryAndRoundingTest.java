package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.*;
import cm.sji.innova.core.sale.*;
import cm.sji.innova.core.stock.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SaleRetryAndRoundingTest {
    @TempDir Path directory;
    SqliteDatabase database;
    SqliteSaleRepository repository;
    SaleService sales;
    ProductService products;
    StockService stock;
    Product product;
    final UUID shop = UUID.randomUUID();
    final UUID device = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        database = new SqliteDatabase(directory.resolve("sales.db"));
        database.initialize();
        var catalog = new SqliteProductRepository(database);
        repository = new SqliteSaleRepository(database);
        sales = new SaleService(repository, catalog);
        products = new ProductService(catalog);
        stock = new StockService(new SqliteStockRepository(database));
        product = products.create(shop, "Shirt", 1000);
        stock.receive(UUID.randomUUID(), shop, product.id(), device, 20_000, "Delivery");
    }

    SaleResult checkout(UUID id, List<SaleItemRequest> items) throws Exception {
        return sales.makeCashSale(id, shop, device, "RECEIPT", items);
    }

    Product measured(long price) throws Exception {
        var item = products.create(shop, "Rice", price, null, null, null, "kg", Map.of());
        stock.receive(UUID.randomUUID(), shop, item.id(), device, 10_000, "Delivery");
        return item;
    }

    @Test
    void changedQuantityPriceProductOrLineCountIsRejectedWithoutWrites() throws Exception {
        UUID id = UUID.randomUUID();
        var original = checkout(id, List.of(new SaleItemRequest(product.id(), 1000))).sale();
        var another = measured(500);
        for (var changed : List.of(
                List.of(new SaleItemRequest(product.id(), 2000)),
                List.of(new SaleItemRequest(product.id(), 1000, 999L)),
                List.of(new SaleItemRequest(another.id(), 1000)),
                List.of(new SaleItemRequest(product.id(), 1000), new SaleItemRequest(product.id(), 1000)))) {
            assertThrows(IllegalArgumentException.class, () -> checkout(id, changed));
        }
        assertEquals(original, sales.getSale(shop, id).orElseThrow());
        assertEquals(19_000, stock.current(shop, product.id()).quantityMilli());
        assertEquals(2, stock.history(shop, product.id()).size());
        assertEquals(1, sales.listSales(shop).size());
    }

    @Test
    void changedDeviceReceiptAndCrossShopIdReuseAreRejected() throws Exception {
        UUID id = UUID.randomUUID();
        var cart = List.of(new SaleItemRequest(product.id(), 1000));
        checkout(id, cart);
        assertThrows(IllegalArgumentException.class, () -> sales.makeCashSale(id, shop,
                UUID.randomUUID(), "RECEIPT", cart));
        assertThrows(IllegalArgumentException.class, () -> sales.makeCashSale(id, shop, device, "CHANGED", cart));
        UUID otherShop = UUID.randomUUID();
        var other = products.create(otherShop, "Other shop", 500);
        stock.receive(UUID.randomUUID(), otherShop, other.id(), device, 1000, "Other delivery");
        assertThrows(IllegalArgumentException.class, () -> sales.makeCashSale(id, otherShop, device,
                "RECEIPT", List.of(new SaleItemRequest(other.id(), 1000))));
        assertTrue(sales.listSales(otherShop).isEmpty());
        assertEquals(1000, stock.current(otherShop, other.id()).quantityMilli());
    }

    @Test
    void identicalRetrySurvivesReopenAndCatalogPriceChanges() throws Exception {
        UUID id = UUID.randomUUID();
        var cart = List.of(new SaleItemRequest(product.id(), 20_000));
        var original = checkout(id, cart).sale();
        try (var connection = database.open(); var update = connection.prepareStatement(
                "UPDATE products SET unit_price = ?, name = ? WHERE id = ?")) {
            update.setLong(1, 9000);
            update.setString(2, "New name");
            update.setString(3, product.id().toString());
            update.executeUpdate();
        }
        var reopened = new SqliteDatabase(directory.resolve("sales.db"));
        reopened.initialize();
        var service = new SaleService(new SqliteSaleRepository(reopened), new SqliteProductRepository(reopened));
        var retry = service.makeCashSale(id, shop, device, " RECEIPT ", cart);
        assertTrue(retry.replayed());
        assertEquals(original, retry.sale());
        assertEquals(0, stock.current(shop, product.id()).quantityMilli());
        assertEquals(2, stock.history(shop, product.id()).size());
    }

    @Test
    void reorderedCartPreservesDuplicatesAndMatchesExplicitPricesBeforeCatalogPrices() throws Exception {
        UUID id = UUID.randomUUID();
        var catalogLine = new SaleItemRequest(product.id(), 1000);
        var customLine = new SaleItemRequest(product.id(), 1000, 500L);
        var first = checkout(id, List.of(customLine, catalogLine)).sale();
        var retry = checkout(id, List.of(catalogLine, customLine));
        assertTrue(retry.replayed());
        assertEquals(first.totalAmount(), retry.sale().totalAmount());
        assertThrows(IllegalArgumentException.class, () -> checkout(id, List.of(customLine, customLine)));
        assertEquals(18_000, stock.current(shop, product.id()).quantityMilli());
        assertEquals(3, stock.history(shop, product.id()).size());
    }

    private Sale candidate(UUID id, long quantity, long price) {
        return new Sale(id, shop, "RECEIPT", device, PaymentMethod.CASH,
                Math.multiplyExact(quantity / 1000, price), Instant.now(),
                List.of(new SaleItem(UUID.randomUUID(), id, product.id(), product.reference(), product.name(), product.unit(),
                        quantity, price, Math.multiplyExact(quantity / 1000, price))));
    }

    @Test
    void repositoryRejectsChangedRetriesEvenWhenCalledWithoutService() throws Exception {
        UUID id = UUID.randomUUID();
        repository.recordSale(candidate(id, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> repository.recordSale(candidate(id, 2000, 1000)));
        assertThrows(IllegalArgumentException.class, () -> repository.recordSale(candidate(id, 1000, 2000)));
        assertEquals(19_000, stock.current(shop, product.id()).quantityMilli());
    }

    @Test
    void transactionRetryUsesOriginalCatalogSnapshotRatherThanAnInflightRecalculation() throws Exception {
        UUID id = UUID.randomUUID();
        var input = new CashSaleRequest(id, shop, device, "RECEIPT",
                List.of(new SaleItemRequest(product.id(), 1000)));
        var saved = repository.recordSale(candidate(id, 1000, 1000), input).sale();
        // A second caller already calculated a different catalog price before obtaining the write lock.
        var retry = repository.recordSale(candidate(id, 1000, 2000), input);
        assertTrue(retry.replayed());
        assertEquals(saved, retry.sale());
        assertEquals(19_000, stock.current(shop, product.id()).quantityMilli());
    }

    @Test
    void concurrentSameRequestIsCountedOnceAndConflictingRequestsCannotBothSucceed() throws Exception {
        UUID id = UUID.randomUUID();
        var input = new CashSaleRequest(id, shop, device, "RECEIPT",
                List.of(new SaleItemRequest(product.id(), 1000)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<SaleResult>>of(
                    () -> repository.recordSale(candidate(id, 1000, 1000), input),
                    () -> repository.recordSale(candidate(id, 1000, 1000), input));
            long replayed = 0;
            for (var future : executor.invokeAll(tasks)) if (future.get(10, TimeUnit.SECONDS).replayed()) replayed++;
            assertEquals(1, replayed);
            UUID conflictingId = UUID.randomUUID();
            var conflicting = List.<Callable<Boolean>>of(
                    () -> tryDifferentCandidate(conflictingId, 1000),
                    () -> tryDifferentCandidate(conflictingId, 2000));
            int successes = 0;
            for (var future : executor.invokeAll(conflicting)) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(2, sales.listSales(shop).size());
        assertEquals(3, stock.history(shop, product.id()).size());
        assertEquals(stock.current(shop, product.id()).quantityMilli(),
                stock.history(shop, product.id()).stream().mapToLong(StockMovement::quantityMilli).sum());
    }

    private boolean tryDifferentCandidate(UUID id, long quantity) throws Exception {
        try { repository.recordSale(candidate(id, quantity, 1000)); return true; }
        catch (IllegalArgumentException expectedConflict) { return false; }
    }

    @Test
    void roundsBelowHalfDownAndHalfOrAboveUpUsingExactDecimalArithmetic() throws Exception {
        var rice = measured(1);
        for (long[] example : List.of(new long[]{499, 0}, new long[]{500, 1}, new long[]{501, 1})) {
            var result = checkout(UUID.randomUUID(), List.of(new SaleItemRequest(rice.id(), example[0])));
            assertEquals(example[1], result.sale().totalAmount());
            assertEquals(example[1], result.sale().items().getFirst().subtotal());
        }
        var expensiveRice = measured(501);
        assertEquals(251, checkout(UUID.randomUUID(), List.of(new SaleItemRequest(expensiveRice.id(), 500)))
                .sale().totalAmount());
    }

    @Test
    void roundsEachLineBeforeSummingAndHonorsCustomPrices() throws Exception {
        var rice = measured(1);
        var result = checkout(UUID.randomUUID(), List.of(new SaleItemRequest(rice.id(), 500),
                new SaleItemRequest(rice.id(), 500), new SaleItemRequest(rice.id(), 500, 501L)));
        assertEquals(253, result.sale().totalAmount()); // 1 + 1 + 251, not rounded basket total.
        assertEquals(253, result.sale().items().stream().mapToLong(SaleItem::subtotal).sum());
    }

    @Test
    void validLargeLineDoesNotOverflowAnIntermediateLongMultiplication() throws Exception {
        var rice = measured(Long.MAX_VALUE);
        var result = checkout(UUID.randomUUID(), List.of(new SaleItemRequest(rice.id(), 1000)));
        assertEquals(Long.MAX_VALUE, result.sale().totalAmount());
        assertEquals(9000, stock.current(shop, rice.id()).quantityMilli());
    }

    @Test
    void actualLineAndBasketOverflowFailBeforeStockChanges() throws Exception {
        var rice = measured(Long.MAX_VALUE);
        UUID id = UUID.randomUUID();
        assertThrows(ArithmeticException.class, () -> checkout(id, List.of(new SaleItemRequest(rice.id(), 2000))));
        assertThrows(ArithmeticException.class, () -> checkout(id, List.of(new SaleItemRequest(rice.id(), 1000),
                new SaleItemRequest(rice.id(), 1000))));
        assertTrue(sales.getSale(shop, id).isEmpty());
        assertEquals(10_000, stock.current(shop, rice.id()).quantityMilli());
        assertEquals(1, stock.history(shop, rice.id()).size());
    }

    @Test
    void legacyTruncatedSaleIsReturnedUnchangedRatherThanRepriced() throws Exception {
        var rice = measured(501);
        UUID id = UUID.randomUUID();
        var oldItem = new SaleItem(UUID.randomUUID(), id, rice.id(), rice.reference(), rice.name(), rice.unit(), 500, 501, 250);
        var oldSale = new Sale(id, shop, "RECEIPT", device, PaymentMethod.CASH, 250, Instant.now(), List.of(oldItem));
        repository.recordSale(oldSale); // Represents an existing sale written by the old rounding implementation.
        var retry = checkout(id, List.of(new SaleItemRequest(rice.id(), 500)));
        assertTrue(retry.replayed());
        assertEquals(oldSale, retry.sale());
        assertEquals(9500, stock.current(shop, rice.id()).quantityMilli());
    }
}

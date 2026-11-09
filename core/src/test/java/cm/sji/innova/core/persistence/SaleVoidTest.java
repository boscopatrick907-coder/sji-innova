package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.*;
import cm.sji.innova.core.stock.StockMovement;
import cm.sji.innova.core.stock.StockService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SaleVoidTest {
    @TempDir Path directory;
    SqliteDatabase database;
    SqliteProductRepository productRepository;
    SqliteSaleRepository saleRepository;
    ProductService products;
    StockService stock;
    SaleService sales;
    Product shirt;

    final UUID shop = UUID.randomUUID();
    final UUID device = UUID.randomUUID();
    final UUID cashier = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        database = new SqliteDatabase(directory.resolve("voids.db"));
        database.initialize();
        productRepository = new SqliteProductRepository(database);
        saleRepository = new SqliteSaleRepository(database);
        products = new ProductService(productRepository);
        stock = new StockService(new SqliteStockRepository(database));
        sales = new SaleService(saleRepository, productRepository);

        shirt = products.create(shop, "Polo T-shirt", 3000, "Ref-01", 2000L,
                "Clothing", "piece", Map.of("size", "L"));
    }

    @Test
    void receiveTen_sellTwo_voidWithReason_restoreToTen_reopenAndVerifyOriginalSaleEventsAndHistory() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Initial stock reception");
        assertEquals(10_000L, stock.current(shop, shirt.id()).quantityMilli());

        UUID saleId = UUID.randomUUID();
        Sale originalSale = sales.makeCashSale(saleId, shop, device, "REC-2026-010",
                List.of(new SaleItemRequest(shirt.id(), 2_000L))).sale();
        assertEquals(8_000L, stock.current(shop, shirt.id()).quantityMilli());

        SaleState beforeVoidState = sales.getSaleState(shop, saleId).orElseThrow();
        assertEquals(SaleStatus.PAID, beforeVoidState.status());
        assertFalse(beforeVoidState.voided());
        assertEquals(1, beforeVoidState.events().size());
        assertEquals(SaleEvent.Type.CREATED, beforeVoidState.events().getFirst().type());

        // Change catalog price and product name before voiding to prove void uses saved sale item snapshots
        try (var connection = database.open(); var update = connection.prepareStatement(
                "UPDATE products SET unit_price = 9999, name = 'Renamed Shirt' WHERE id = ?")) {
            update.setString(1, shirt.id().toString());
            update.executeUpdate();
        }

        UUID voidOpId = UUID.randomUUID();
        VoidSaleResult voidResult = sales.voidCashSale(
                voidOpId, shop, saleId, device, cashier, "  Cashier rang wrong item  ");

        assertFalse(voidResult.replayed());
        assertEquals(voidOpId, voidResult.operationId());
        assertEquals(saleId, voidResult.saleId());
        assertEquals(SaleStatus.VOIDED, voidResult.status());
        assertEquals(originalSale, voidResult.sale());
        assertEquals("Cashier rang wrong item", voidResult.voidEvent().reason());
        assertEquals(cashier, voidResult.voidEvent().performedBy());
        assertEquals(1, voidResult.restoredMovements().size());
        assertEquals(2_000L, voidResult.restoredMovements().getFirst().quantityMilli());
        assertEquals(StockMovement.Type.VOID, voidResult.restoredMovements().getFirst().type());
        assertEquals("VOID_SALE", voidResult.auditRecord().action());
        assertEquals("sales", voidResult.auditRecord().targetTable());
        assertEquals(saleId, voidResult.auditRecord().targetId());
        assertEquals(voidOpId, voidResult.auditRecord().eventId());

        // Stock is restored to 10 pieces (10_000 milli)
        assertEquals(10_000L, stock.current(shop, shirt.id()).quantityMilli());

        // Simulate restart by reopening database
        SqliteDatabase reopened = new SqliteDatabase(directory.resolve("voids.db"));
        reopened.initialize();
        StockService reopenedStock = new StockService(new SqliteStockRepository(reopened));
        SaleService reopenedSales = new SaleService(
                new SqliteSaleRepository(reopened),
                new SqliteProductRepository(reopened));

        assertEquals(10_000L, reopenedStock.current(shop, shirt.id()).quantityMilli());
        // Original sale, items, prices, quantities, totals and timestamps are completely unchanged
        assertEquals(originalSale, reopenedSales.getSale(shop, saleId).orElseThrow());
        assertEquals(List.of(originalSale), reopenedSales.listSales(shop));

        SaleState afterVoidState = reopenedSales.getSaleState(shop, saleId).orElseThrow();
        assertEquals(SaleStatus.VOIDED, afterVoidState.status());
        assertTrue(afterVoidState.voided());
        assertEquals(originalSale, afterVoidState.sale());
        assertEquals(voidResult.voidEvent(), afterVoidState.voidEvent().orElseThrow());
        assertEquals(2, afterVoidState.events().size());
        assertEquals(SaleEvent.Type.CREATED, afterVoidState.events().get(0).type());
        assertEquals(SaleEvent.Type.VOIDED, afterVoidState.events().get(1).type());

        List<SaleAuditRecord> audits = reopenedSales.listAuditRecords(shop, saleId);
        assertEquals(List.of(voidResult.auditRecord()), audits);

        List<StockMovement> history = reopenedStock.history(shop, shirt.id());
        assertEquals(3, history.size());
        assertEquals(10_000L, history.stream().mapToLong(StockMovement::quantityMilli).sum());
        assertEquals(1, history.stream().filter(m -> m.type() == StockMovement.Type.DELIVERY && m.quantityMilli() == 10_000L).count());
        assertEquals(1, history.stream().filter(m -> m.type() == StockMovement.Type.SALE && m.quantityMilli() == -2_000L).count());
        assertEquals(1, history.stream().filter(m -> m.type() == StockMovement.Type.VOID && m.quantityMilli() == 2_000L).count());
    }

    @Test
    void identicalVoidRetryReturnsSavedVoidWithoutRestoringStockAgain() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 5_000L, "Delivery");
        UUID saleId = UUID.randomUUID();
        sales.makeCashSale(saleId, shop, device, "REC-011", List.of(new SaleItemRequest(shirt.id(), 2_000L)));
        assertEquals(3_000L, stock.current(shop, shirt.id()).quantityMilli());

        UUID voidOpId = UUID.randomUUID();
        VoidSaleResult first = sales.voidCashSale(voidOpId, shop, saleId, device, cashier, "Mistaken sale");
        assertFalse(first.replayed());
        assertEquals(5_000L, stock.current(shop, shirt.id()).quantityMilli());

        VoidSaleResult retry = sales.voidCashSale(voidOpId, shop, saleId, device, cashier, "  Mistaken sale ");
        assertTrue(retry.replayed());
        assertEquals(first.sale(), retry.sale());
        assertEquals(first.voidEvent(), retry.voidEvent());
        assertEquals(first.restoredMovements(), retry.restoredMovements());
        assertEquals(first.auditRecord(), retry.auditRecord());
        assertEquals(5_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(3, stock.history(shop, shirt.id()).size());
        assertEquals(2, sales.listSaleEvents(shop, saleId).size());
        assertEquals(1, sales.listAuditRecords(shop, saleId).size());
    }

    @Test
    void changedVoidOperationDetailsAndSecondVoidWithDifferentOperationIdAreRejected() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery");
        UUID firstSaleId = UUID.randomUUID();
        UUID secondSaleId = UUID.randomUUID();
        sales.makeCashSale(firstSaleId, shop, device, "REC-012A", List.of(new SaleItemRequest(shirt.id(), 2_000L)));
        sales.makeCashSale(secondSaleId, shop, device, "REC-012B", List.of(new SaleItemRequest(shirt.id(), 1_000L)));
        assertEquals(7_000L, stock.current(shop, shirt.id()).quantityMilli());

        UUID voidOpId = UUID.randomUUID();
        sales.voidCashSale(voidOpId, shop, firstSaleId, device, cashier, "Customer cancelled");
        assertEquals(9_000L, stock.current(shop, shirt.id()).quantityMilli());

        // Same operation ID with changed reason, actor, device, sale, or shop must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(voidOpId, shop, firstSaleId, device, cashier, "Different reason"));
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(voidOpId, shop, firstSaleId, device, UUID.randomUUID(), "Customer cancelled"));
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(voidOpId, shop, firstSaleId, UUID.randomUUID(), cashier, "Customer cancelled"));
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(voidOpId, shop, secondSaleId, device, cashier, "Customer cancelled"));
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(voidOpId, UUID.randomUUID(), firstSaleId, device, cashier, "Customer cancelled"));

        // Reusing a created-event ID as a void operation ID must also throw IllegalArgumentException
        UUID createdEventId = SaleEvent.createdEventId(secondSaleId);
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(createdEventId, shop, secondSaleId, device, cashier, "Customer cancelled"));

        // Different operation ID for an already-voided sale must throw IllegalStateException
        assertThrows(IllegalStateException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, firstSaleId, device, cashier, "Second void attempt"));

        // Stock and second sale remain untouched
        assertEquals(9_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(SaleStatus.PAID, sales.getSaleState(shop, secondSaleId).orElseThrow().status());
    }

    @Test
    void concurrentVoidRequestsWithSameOrDifferentOperationIdsRestoreStockExactlyOnce() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery");
        UUID firstSaleId = UUID.randomUUID();
        UUID secondSaleId = UUID.randomUUID();
        sales.makeCashSale(firstSaleId, shop, device, "REC-CONC-1", List.of(new SaleItemRequest(shirt.id(), 2_000L)));
        sales.makeCashSale(secondSaleId, shop, device, "REC-CONC-2", List.of(new SaleItemRequest(shirt.id(), 3_000L)));
        assertEquals(5_000L, stock.current(shop, shirt.id()).quantityMilli());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // Case 1: Two simultaneous callers with the SAME operationId
            UUID sharedOpId = UUID.randomUUID();
            CyclicBarrier sameOpBarrier = new CyclicBarrier(2);
            Callable<VoidSaleResult> sameOpTask = () -> {
                sameOpBarrier.await(5, TimeUnit.SECONDS);
                return sales.voidCashSale(sharedOpId, shop, firstSaleId, device, cashier, "Concurrent retry");
            };
            List<Future<VoidSaleResult>> sameOpFutures = executor.invokeAll(List.of(sameOpTask, sameOpTask));
            int replayedCount = 0;
            int freshCount = 0;
            for (Future<VoidSaleResult> future : sameOpFutures) {
                VoidSaleResult res = future.get(10, TimeUnit.SECONDS);
                if (res.replayed()) replayedCount++;
                else freshCount++;
            }
            assertEquals(1, freshCount);
            assertEquals(1, replayedCount);
            assertEquals(7_000L, stock.current(shop, shirt.id()).quantityMilli());

            // Case 2: Two simultaneous callers with DIFFERENT operationIds for the same sale
            CyclicBarrier diffOpBarrier = new CyclicBarrier(2);
            Callable<Boolean> diffOpTask1 = () -> {
                diffOpBarrier.await(5, TimeUnit.SECONDS);
                return tryVoidWithNewId(secondSaleId);
            };
            Callable<Boolean> diffOpTask2 = () -> {
                diffOpBarrier.await(5, TimeUnit.SECONDS);
                return tryVoidWithNewId(secondSaleId);
            };
            List<Future<Boolean>> diffOpFutures = executor.invokeAll(List.of(diffOpTask1, diffOpTask2));
            int successes = 0;
            for (Future<Boolean> future : diffOpFutures) {
                if (future.get(10, TimeUnit.SECONDS)) successes++;
            }
            assertEquals(1, successes);
        }

        assertEquals(10_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(10_000L, stock.history(shop, shirt.id()).stream().mapToLong(StockMovement::quantityMilli).sum());
        assertEquals(2, sales.listSaleEvents(shop, firstSaleId).size());
        assertEquals(2, sales.listSaleEvents(shop, secondSaleId).size());
        assertEquals(1, sales.listAuditRecords(shop, firstSaleId).size());
        assertEquals(1, sales.listAuditRecords(shop, secondSaleId).size());
    }

    private boolean tryVoidWithNewId(UUID saleId) throws Exception {
        try {
            sales.voidCashSale(UUID.randomUUID(), shop, saleId, device, cashier, "Simultaneous void");
            return true;
        } catch (IllegalStateException alreadyVoided) {
            return false;
        }
    }

    @Test
    void rejectsWrongShopNonexistentSaleBlankReasonNullsAndUnsupportedPaymentType() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery");
        UUID saleId = UUID.randomUUID();
        sales.makeCashSale(saleId, shop, device, "REC-013", List.of(new SaleItemRequest(shirt.id(), 1_000L)));

        // Null checks
        assertThrows(NullPointerException.class, () ->
                sales.voidCashSale(null, shop, saleId, device, cashier, "Reason"));
        assertThrows(NullPointerException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), null, saleId, device, cashier, "Reason"));
        assertThrows(NullPointerException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, null, device, cashier, "Reason"));
        assertThrows(NullPointerException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, saleId, null, cashier, "Reason"));
        assertThrows(NullPointerException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, saleId, device, null, "Reason"));
        assertThrows(NullPointerException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, saleId, device, cashier, null));

        // Blank reason
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, saleId, device, cashier, "   "));

        // Nonexistent sale
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, UUID.randomUUID(), device, cashier, "Wrong sale"));

        // Wrong shop
        UUID otherShop = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), otherShop, saleId, device, cashier, "Wrong shop"));
        assertThrows(IllegalArgumentException.class, () -> sales.listSaleEvents(otherShop, saleId));
        assertThrows(IllegalArgumentException.class, () -> sales.listAuditRecords(otherShop, saleId));
        assertTrue(sales.getSaleState(otherShop, saleId).isEmpty());

        // Unsupported payment type (e.g. MOMO or CREDIT inserted in sales table)
        UUID momoSaleId = UUID.randomUUID();
        UUID momoItemId = UUID.randomUUID();
        try (var connection = database.open(); var stmt = connection.createStatement()) {
            stmt.execute("""
                    INSERT INTO sales (id, shop_id, receipt_number, device_id, payment_method, total_amount, created_at)
                    VALUES ('%s', '%s', 'MOMO-001', '%s', 'momo', 3000, '2026-11-02T10:00:00Z')
                    """.formatted(momoSaleId, shop, device));
            stmt.execute("""
                    INSERT INTO sale_items (id, sale_id, product_id, product_reference, product_name, unit, quantity_milli, unit_price, subtotal)
                    VALUES ('%s', '%s', '%s', '%s', 'Polo T-shirt', 'piece', 1000, 3000, 3000)
                    """.formatted(momoItemId, momoSaleId, shirt.id(), shirt.reference()));
            stmt.execute("""
                    INSERT INTO sale_events (id, sale_id, shop_id, device_id, type, performed_by, reason, created_at)
                    VALUES ('%s', '%s', '%s', '%s', 'created', NULL, 'Sale MOMO-001', '2026-11-02T10:00:00Z')
                    """.formatted(SaleEvent.createdEventId(momoSaleId), momoSaleId, shop, device));
        }
        assertThrows(IllegalArgumentException.class, () ->
                sales.voidCashSale(UUID.randomUUID(), shop, momoSaleId, device, cashier, "Cannot void MoMo here"));

        // Original cash sale is still PAID and stock is unchanged at 9_000
        assertEquals(9_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(SaleStatus.PAID, sales.getSaleState(shop, saleId).orElseThrow().status());
    }

    @Test
    void multiItemRepeatedProductLinesAndFractionalKgLitreRestoreExactThousandthsEvenWithDuplicateReceiptNumbers() throws Exception {
        Product rice = products.create(shop, "Rice", 600, null, null, "Food", "kg", Map.of());
        Product oil = products.create(shop, "Cooking Oil", 1400, null, null, "Food", "litre", Map.of());
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery shirts");
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 5_000L, "Delivery rice");
        stock.receive(UUID.randomUUID(), shop, oil.id(), device, 3_000L, "Delivery oil");

        // Two different sales with the SAME receipt number to verify void uses relational links, not receipt strings
        UUID firstSaleId = UUID.randomUUID();
        sales.makeCashSale(firstSaleId, shop, device, "DUP-REC-001", List.of(
                new SaleItemRequest(shirt.id(), 1_000L),
                new SaleItemRequest(shirt.id(), 2_000L, 2500L), // repeated product line in same sale
                new SaleItemRequest(rice.id(), 1_375L),         // 1.375 kg
                new SaleItemRequest(oil.id(), 625L)             // 0.625 litre
        ));

        UUID secondSaleId = UUID.randomUUID();
        sales.makeCashSale(secondSaleId, shop, device, "DUP-REC-001", List.of(
                new SaleItemRequest(shirt.id(), 4_000L),
                new SaleItemRequest(rice.id(), 1_000L)
        ));

        assertEquals(3_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(2_625L, stock.current(shop, rice.id()).quantityMilli());
        assertEquals(2_375L, stock.current(shop, oil.id()).quantityMilli());

        // Void only the first sale
        VoidSaleResult voidResult = sales.voidCashSale(
                UUID.randomUUID(), shop, firstSaleId, device, cashier, "Void first duplicate-receipt sale");
        assertEquals(4, voidResult.restoredMovements().size());

        // First sale's exact quantities (3_000 shirt, 1_375 rice, 625 oil) are restored; second sale remains active
        assertEquals(6_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(4_000L, stock.current(shop, rice.id()).quantityMilli());
        assertEquals(3_000L, stock.current(shop, oil.id()).quantityMilli());
        assertEquals(SaleStatus.VOIDED, sales.getSaleState(shop, firstSaleId).orElseThrow().status());
        assertEquals(SaleStatus.PAID, sales.getSaleState(shop, secondSaleId).orElseThrow().status());
    }

    @Test
    void mixedUnitVoidSucceedsWhenEachProductStockFitsLongEvenIfCrossProductSumWouldOverflow() throws Exception {
        // 1. Create a kg product priced at 1 FCFA/kg and receive Long.MAX_VALUE thousandths
        Product bulkGrain = products.create(shop, "Bulk Grain", 1, null, null, "Food", "kg", Map.of());
        stock.receive(UUID.randomUUID(), shop, bulkGrain.id(), device, Long.MAX_VALUE, "Max bulk grain delivery");

        // 2. Create a piece product priced at 1000 FCFA and receive 1000 thousandths (1 piece)
        Product bag = products.create(shop, "Packaging Bag", 1000, null, null, "Supplies", "piece", Map.of());
        stock.receive(UUID.randomUUID(), shop, bag.id(), device, 1_000L, "Single bag delivery");

        // 3. Sell both in one cash sale; rounded total fits long and both stocks become zero
        UUID saleId = UUID.randomUUID();
        Sale originalSale = sales.makeCashSale(saleId, shop, device, "REC-MIXED-MAX", List.of(
                new SaleItemRequest(bulkGrain.id(), Long.MAX_VALUE),
                new SaleItemRequest(bag.id(), 1_000L)
        )).sale();
        assertEquals(0L, stock.current(shop, bulkGrain.id()).quantityMilli());
        assertEquals(0L, stock.current(shop, bag.id()).quantityMilli());

        // 4. Void that sale: restoring each individual stock fits long, even though summing across products overflows long
        UUID voidOpId = UUID.randomUUID();
        VoidSaleResult voidResult = sales.voidCashSale(
                voidOpId, shop, saleId, device, cashier, "Void mixed-unit max-quantity sale");

        assertFalse(voidResult.replayed());
        assertEquals(SaleStatus.VOIDED, voidResult.status());
        assertEquals(originalSale, voidResult.sale());
        assertEquals(originalSale, sales.getSale(shop, saleId).orElseThrow());

        // Each product's restored quantity is exact and matches movement history
        assertEquals(Long.MAX_VALUE, stock.current(shop, bulkGrain.id()).quantityMilli());
        assertEquals(Long.MAX_VALUE, stock.history(shop, bulkGrain.id()).stream().mapToLong(StockMovement::quantityMilli).sum());
        assertEquals(1_000L, stock.current(shop, bag.id()).quantityMilli());
        assertEquals(1_000L, stock.history(shop, bag.id()).stream().mapToLong(StockMovement::quantityMilli).sum());

        // One void event, one audit entry, and two item-restoration links committed
        SaleState state = sales.getSaleState(shop, saleId).orElseThrow();
        assertEquals(SaleStatus.VOIDED, state.status());
        assertEquals(2, state.events().size());
        assertEquals(1, sales.listAuditRecords(shop, saleId).size());
        assertEquals(2, voidResult.restoredMovements().size());

        try (var connection = database.open();
             var stmt = connection.prepareStatement("SELECT COUNT(*) FROM sale_void_items WHERE shop_id = ? AND sale_id = ?")) {
            stmt.setString(1, shop.toString());
            stmt.setString(2, saleId.toString());
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }

        // Audit output has no misleading cross-unit quantity sum and records per-item identities/units/quantities
        String newValueJson = voidResult.auditRecord().newValue();
        assertFalse(newValueJson.contains("restoredQuantityMilli"));
        assertTrue(newValueJson.contains("\"restoredItemCount\":2"));
        assertTrue(newValueJson.contains("\"restoredItems\":["));
        assertTrue(newValueJson.contains("\"productId\":\"" + bulkGrain.id() + "\""));
        assertTrue(newValueJson.contains("\"unit\":\"kg\""));
        assertTrue(newValueJson.contains("\"quantityMilli\":" + Long.MAX_VALUE));
        assertTrue(newValueJson.contains("\"productId\":\"" + bag.id() + "\""));
        assertTrue(newValueJson.contains("\"unit\":\"piece\""));
        assertTrue(newValueJson.contains("\"quantityMilli\":1000"));

        // 5. Retry the same void: returns the saved void result without restoring stock a second time
        VoidSaleResult retry = sales.voidCashSale(
                voidOpId, shop, saleId, device, cashier, "Void mixed-unit max-quantity sale");
        assertTrue(retry.replayed());
        assertEquals(voidResult.sale(), retry.sale());
        assertEquals(voidResult.voidEvent(), retry.voidEvent());
        assertEquals(voidResult.restoredMovements(), retry.restoredMovements());
        assertEquals(voidResult.auditRecord(), retry.auditRecord());
        assertEquals(Long.MAX_VALUE, stock.current(shop, bulkGrain.id()).quantityMilli());
        assertEquals(1_000L, stock.current(shop, bag.id()).quantityMilli());
    }

    @Test
    void midTransactionFailuresAndOverflowRollBackEveryChangeAndAllowSubsequentRetry() throws Exception {
        Product oil = products.create(shop, "Oil", 1000, null, null, "Food", "litre", Map.of());
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery shirts");
        stock.receive(UUID.randomUUID(), shop, oil.id(), device, 5_000L, "Delivery oil");

        UUID saleId = UUID.randomUUID();
        sales.makeCashSale(saleId, shop, device, "REC-ROLLBACK", List.of(
                new SaleItemRequest(shirt.id(), 2_000L),
                new SaleItemRequest(oil.id(), 1_500L)
        ));
        assertEquals(8_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(3_500L, stock.current(shop, oil.id()).quantityMilli());

        // 1. Simulate failure during audit_log insert (after event, movements, links, and stock updates ran)
        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_audit_insert BEFORE INSERT ON audit_log
                    BEGIN SELECT RAISE(ABORT, 'Simulated audit failure'); END
                    """);
        }
        UUID voidOpId = UUID.randomUUID();
        assertThrows(SQLException.class, () ->
                sales.voidCashSale(voidOpId, shop, saleId, device, cashier, "Should roll back"));

        assertEquals(8_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(3_500L, stock.current(shop, oil.id()).quantityMilli());
        assertEquals(SaleStatus.PAID, sales.getSaleState(shop, saleId).orElseThrow().status());
        assertEquals(1, sales.listSaleEvents(shop, saleId).size());
        assertTrue(sales.listAuditRecords(shop, saleId).isEmpty());
        assertEquals(2, stock.history(shop, shirt.id()).size());
        assertEquals(2, stock.history(shop, oil.id()).size());

        try (var connection = database.open(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_audit_insert");
        }

        // 2. Simulate integer overflow during stock restoration of the second item (oil)
        stock.receive(UUID.randomUUID(), shop, oil.id(), device, Long.MAX_VALUE - 3_500L, "Fill oil to MAX_VALUE");
        assertEquals(Long.MAX_VALUE, stock.current(shop, oil.id()).quantityMilli());

        assertThrows(ArithmeticException.class, () ->
                sales.voidCashSale(voidOpId, shop, saleId, device, cashier, "Overflow on second item"));

        // Earlier item (shirt) stock update, movement, and void event must be completely rolled back
        assertEquals(8_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(Long.MAX_VALUE, stock.current(shop, oil.id()).quantityMilli());
        assertEquals(SaleStatus.PAID, sales.getSaleState(shop, saleId).orElseThrow().status());
        assertEquals(1, sales.listSaleEvents(shop, saleId).size());
        assertTrue(sales.listAuditRecords(shop, saleId).isEmpty());

        // 3. Adjust oil stock down and retry with the same voidOpId: must now succeed cleanly
        stock.adjust(UUID.randomUUID(), shop, oil.id(), device, -10_000L, "Make room for restoration");
        VoidSaleResult recovered = sales.voidCashSale(voidOpId, shop, saleId, device, cashier, "Recovered retry");
        assertFalse(recovered.replayed());
        assertEquals(10_000L, stock.current(shop, shirt.id()).quantityMilli());
        assertEquals(Long.MAX_VALUE - 8_500L, stock.current(shop, oil.id()).quantityMilli());
        assertEquals(SaleStatus.VOIDED, sales.getSaleState(shop, saleId).orElseThrow().status());
    }

    @Test
    void replayingCheckoutAfterVoidLeavesVoidStateEventsAndStockUnchanged() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery");
        UUID saleId = UUID.randomUUID();
        List<SaleItemRequest> cart = List.of(new SaleItemRequest(shirt.id(), 2_000L));
        Sale original = sales.makeCashSale(saleId, shop, device, "REC-AFTER-VOID", cart).sale();
        assertEquals(8_000L, stock.current(shop, shirt.id()).quantityMilli());

        sales.voidCashSale(UUID.randomUUID(), shop, saleId, device, cashier, "Customer had no cash");
        assertEquals(10_000L, stock.current(shop, shirt.id()).quantityMilli());

        // Replay checkout through both SaleService and SqliteSaleRepository
        SaleResult serviceReplay = sales.makeCashSale(saleId, shop, device, "REC-AFTER-VOID", cart);
        assertTrue(serviceReplay.replayed());
        assertEquals(original, serviceReplay.sale());

        SaleResult repoReplay = saleRepository.recordSale(original, CashSaleRequest.fromSale(original));
        assertTrue(repoReplay.replayed());
        assertEquals(original, repoReplay.sale());

        // Stock is still 10_000, state is still VOIDED, and created event was not duplicated
        assertEquals(10_000L, stock.current(shop, shirt.id()).quantityMilli());
        SaleState state = sales.getSaleState(shop, saleId).orElseThrow();
        assertEquals(SaleStatus.VOIDED, state.status());
        assertEquals(2, state.events().size());
        assertEquals(3, stock.history(shop, shirt.id()).size());
    }

    @Test
    void appendOnlyTriggersProtectSalesItemsEventsVoidLinksAndAuditLog() throws Exception {
        stock.receive(UUID.randomUUID(), shop, shirt.id(), device, 10_000L, "Delivery");
        UUID saleId = UUID.randomUUID();
        sales.makeCashSale(saleId, shop, device, "REC-IMMUTABLE", List.of(new SaleItemRequest(shirt.id(), 1_000L)));
        sales.voidCashSale(UUID.randomUUID(), shop, saleId, device, cashier, "Audit immutability check");

        try (var connection = database.open(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sales SET total_amount = 0"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM sales"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sale_items SET subtotal = 0"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM sale_items"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sale_events SET reason = 'tampered'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM sale_events"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sale_void_items SET quantity_milli = 1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM sale_void_items"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE audit_log SET reason = 'tampered'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM audit_log"));
        }
    }

    @Test
    void populatedSchemaVersionFourUpgradesPreservingDataBackfillingCreatedEventsAndAllowingPostMigrationVoid() throws Exception {
        Path legacyPath = directory.resolve("legacy-v4.db");
        SqliteDatabase legacyDb = new SqliteDatabase(legacyPath);
        UUID legacyProductId = UUID.randomUUID();
        UUID legacySaleId = UUID.randomUUID();
        UUID legacyItemId = UUID.randomUUID();
        UUID legacyDeliveryId = UUID.randomUUID();
        UUID legacySaleMovId = UUID.randomUUID();

        populateSchemaVersionFour(legacyDb, legacyProductId, legacySaleId, legacyItemId, legacyDeliveryId, legacySaleMovId);

        // Upgrade to v5 and run initialize() a second time to verify repeatability
        legacyDb.initialize();
        legacyDb.initialize();

        try (var connection = legacyDb.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(SqliteDatabase.SCHEMA_VERSION, rows.getInt(1));
        }

        ProductService migratedProducts = new ProductService(new SqliteProductRepository(legacyDb));
        StockService migratedStock = new StockService(new SqliteStockRepository(legacyDb));
        SaleService migratedSales = new SaleService(
                new SqliteSaleRepository(legacyDb),
                new SqliteProductRepository(legacyDb));

        Product migratedProduct = migratedProducts.list(shop).getFirst();
        assertEquals(legacyProductId, migratedProduct.id());
        assertEquals(Map.of("color", "blue"), migratedProduct.specifications());
        assertEquals(8_000L, migratedStock.current(shop, legacyProductId).quantityMilli());

        SaleState migratedState = migratedSales.getSaleState(shop, legacySaleId).orElseThrow();
        assertEquals(SaleStatus.PAID, migratedState.status());
        assertEquals(1, migratedState.events().size());
        SaleEvent backfilledCreated = migratedState.events().getFirst();
        assertEquals(SaleEvent.createdEventId(legacySaleId), backfilledCreated.id());
        assertEquals(SaleEvent.Type.CREATED, backfilledCreated.type());
        assertNull(backfilledCreated.performedBy());
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"), backfilledCreated.createdAt());

        // Void the migrated schema-v4 sale under schema v5
        VoidSaleResult voidedLegacy = migratedSales.voidCashSale(
                UUID.randomUUID(), shop, legacySaleId, device, cashier, "Void legacy v4 sale");
        assertFalse(voidedLegacy.replayed());
        assertEquals(10_000L, migratedStock.current(shop, legacyProductId).quantityMilli());
        assertEquals(SaleStatus.VOIDED, migratedSales.getSaleState(shop, legacySaleId).orElseThrow().status());
    }

    @Test
    void failedSchemaVersionFourMigrationRollsBackTablesAndVersionBeforeSuccessfulRetry() throws Exception {
        Path legacyPath = directory.resolve("failed-v4.db");
        SqliteDatabase legacyDb = new SqliteDatabase(legacyPath);
        UUID legacyProductId = UUID.randomUUID();
        UUID legacySaleId = UUID.randomUUID();
        populateSchemaVersionFour(legacyDb, legacyProductId, legacySaleId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        // Pre-create a conflicting audit_log table so the v4 -> v5 migration fails partway through
        try (var connection = legacyDb.open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE audit_log (unexpected TEXT)");
        }

        assertThrows(SQLException.class, legacyDb::initialize);

        try (var connection = legacyDb.open(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
            }
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('sale_events', 'sale_void_items')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            statement.execute("DROP TABLE audit_log");
        }

        // Retry migration after removing obstacle
        legacyDb.initialize();
        SaleService migratedSales = new SaleService(
                new SqliteSaleRepository(legacyDb),
                new SqliteProductRepository(legacyDb));
        assertEquals(SaleStatus.PAID, migratedSales.getSaleState(shop, legacySaleId).orElseThrow().status());
    }

    private void populateSchemaVersionFour(SqliteDatabase db, UUID productId, UUID saleId,
                                           UUID itemId, UUID deliveryId, UUID saleMovId) throws Exception {
        try (var input = getClass().getResourceAsStream("/schema-v4.sql");
             var connection = db.open();
             var statement = connection.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) {
                    String trimmed = sql.strip();
                    if (trimmed.startsWith("CREATE TRIGGER")) {
                        statement.execute(trimmed + "; END");
                    } else if (!trimmed.equals("END")) {
                        statement.execute(trimmed);
                    }
                }
            }
            statement.execute("""
                    INSERT INTO products (id, shop_id, name, unit_price, created_at, updated_at, version, model_reference, cost_price, category, unit, current_stock_milli, min_stock_milli)
                    VALUES ('%s', '%s', 'Legacy Shirt', 3000, '2026-10-20T09:00:00Z', '2026-10-20T09:00:00Z', 1, 'M1', 2000, 'Clothing', 'piece', 8000, 1000)
                    """.formatted(productId, shop));
            statement.execute("""
                    INSERT INTO product_specifications (product_id, name, value)
                    VALUES ('%s', 'color', 'blue')
                    """.formatted(productId));
            statement.execute("""
                    INSERT INTO stock_movements (id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at)
                    VALUES ('%s', '%s', '%s', '%s', 'delivery', 10000, 'Initial delivery', '2026-10-21T10:00:00Z')
                    """.formatted(deliveryId, shop, productId, device));
            statement.execute("""
                    INSERT INTO stock_movements (id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at)
                    VALUES ('%s', '%s', '%s', '%s', 'sale', -2000, 'Sale LEGACY-001', '2026-10-25T12:00:00Z')
                    """.formatted(saleMovId, shop, productId, device));
            statement.execute("""
                    INSERT INTO sales (id, shop_id, receipt_number, device_id, payment_method, total_amount, created_at)
                    VALUES ('%s', '%s', 'LEGACY-001', '%s', 'cash', 6000, '2026-10-25T12:00:00Z')
                    """.formatted(saleId, shop, device));
            statement.execute("""
                    INSERT INTO sale_items (id, sale_id, product_id, product_name, unit, quantity_milli, unit_price, subtotal)
                    VALUES ('%s', '%s', '%s', 'Legacy Shirt', 'piece', 2000, 3000, 6000)
                    """.formatted(itemId, saleId, productId));
        }
    }
}

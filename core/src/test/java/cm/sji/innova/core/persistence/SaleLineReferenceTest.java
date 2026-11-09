package cm.sji.innova.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.PaymentMethod;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleEvent;
import cm.sji.innova.core.sale.SaleItem;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Schema 9: every saved sale line keeps the shop reference of the product it sold, such as P-0001. */
class SaleLineReferenceTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private SqliteDatabase database;
    private StockService stock;
    private SaleService sales;
    private Product rice;
    private Product oil;

    @BeforeEach
    void openShopWithRiceAndOil() throws Exception {
        database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        ProductService products = new ProductService(new SqliteProductRepository(database));
        stock = new StockService(new SqliteStockRepository(database));
        sales = new SaleService(new SqliteSaleRepository(database), new SqliteProductRepository(database));
        // The supplier's model reference must never be taken for the shop reference.
        rice = products.create(shop, "Rice", 1_000, "SUP-25", null, null, "kg", Map.of());
        oil = products.create(shop, "Palm oil", 1_500, null, null, null, "litre", Map.of());
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Delivery");
        stock.receive(UUID.randomUUID(), shop, oil.id(), device, 10_000, "Delivery");
    }

    private Sale sell(Product product, long quantityMilli) throws Exception {
        return sales.makeCashSale(UUID.randomUUID(), shop, device, "REC-" + UUID.randomUUID(),
                List.of(new SaleItemRequest(product.id(), quantityMilli))).sale();
    }

    private static List<String> references(Sale sale) {
        return sale.items().stream().map(SaleItem::productReference).toList();
    }

    @Test
    void aNewSaleKeepsEachLinesProductReferenceAfterARestart() throws Exception {
        assertEquals("P-0001", rice.reference());
        assertEquals("P-0002", oil.reference());

        Sale sale = sales.makeCashSale(UUID.randomUUID(), shop, device, "REC-1", List.of(
                new SaleItemRequest(oil.id(), 2_000), new SaleItemRequest(rice.id(), 1_500))).sale();
        assertEquals(List.of("P-0002", "P-0001"), references(sale));

        SqliteDatabase reopened = new SqliteDatabase(directory.resolve("cella.db"));
        reopened.initialize();
        SaleService afterRestart = new SaleService(
                new SqliteSaleRepository(reopened), new SqliteProductRepository(reopened));
        assertEquals(sale, afterRestart.getSale(shop, sale.id()).orElseThrow());
        assertEquals(List.of("P-0002", "P-0001"), references(afterRestart.listSaleStates(shop).getFirst().sale()));
    }

    @Test
    void aSavedLineKeepsItsReferenceWhenTheCatalogueChangesLater() throws Exception {
        Sale sale = sell(rice, 1_000);
        // No screen edits a reference today; a direct change stands in for a future edit or import.
        try (var connection = database.open(); var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE products SET reference = 'P-0999', name = 'Premium rice' WHERE id = '" + rice.id() + "'"));
        }

        SaleItem saved = sales.getSale(shop, sale.id()).orElseThrow().items().getFirst();
        assertEquals("P-0001", saved.productReference());
        assertEquals("Rice", saved.productName());
        assertEquals("P-0999", sell(rice, 1_000).items().getFirst().productReference());
    }

    @Test
    void aLineNamingAnotherProductsReferenceIsRefusedAndNothingIsSaved() throws Exception {
        UUID id = UUID.randomUUID();
        SaleItem wrong = new SaleItem(UUID.randomUUID(), id, rice.id(), oil.reference(), "Rice", "kg", 1_000, 1_000, 1_000);
        Sale sale = new Sale(id, shop, "REC-1", device, PaymentMethod.CASH, 1_000, Instant.now(), List.of(wrong));

        assertThrows(IllegalArgumentException.class, () -> new SqliteSaleRepository(database).recordSale(sale));

        assertTrue(sales.getSale(shop, id).isEmpty());
        assertEquals(10_000, stock.current(shop, rice.id()).quantityMilli());
        assertEquals(1, stock.history(shop, rice.id()).size());
        assertThrows(NullPointerException.class,
                () -> new SaleItem(UUID.randomUUID(), id, rice.id(), null, "Rice", "kg", 1_000, 1_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new SaleItem(UUID.randomUUID(), id, rice.id(), "  ", "Rice", "kg", 1_000, 1_000, 1_000));
    }

    @Test
    void theDatabaseRefusesALineWithoutAReferenceAndKeepsLinesAppendOnly() throws Exception {
        Sale sale = sell(rice, 1_000);
        String insert = "INSERT INTO sale_items (id, sale_id, product_id, product_reference, product_name, unit, "
                + "quantity_milli, unit_price, subtotal) VALUES ('%s', '" + sale.id() + "', '" + rice.id()
                + "', %s, 'Rice', 'kg', 1000, 1000, 1000)";
        try (var connection = database.open(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute(insert.formatted(UUID.randomUUID(), "NULL")));
            assertThrows(SQLException.class, () -> statement.execute(insert.formatted(UUID.randomUUID(), "'  '")));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sale_items SET product_reference = 'P-0002'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM sale_items"));
        }
        assertEquals(List.of("P-0001"), references(sales.getSale(shop, sale.id()).orElseThrow()));
    }

    // ----- Upgrading a shop saved by an older version -----

    private static final UUID OLD_RICE = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OLD_OIL = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    private static final UUID OLD_SALE = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    // Sold in this order; their IDs sort differently, so only the saved order can give it back.
    private static final UUID FIRST_LINE = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    private static final UUID SECOND_LINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD_LINE = UUID.fromString("88888888-0000-0000-0000-000000000003");

    /** A schema-5 shop, written row by row as that version wrote it: two products and a three-line sale. */
    private SqliteDatabase schemaFiveShop(String file) throws Exception {
        SqliteDatabase old = new SqliteDatabase(directory.resolve(file));
        try (var input = getClass().getResourceAsStream("/schema-v5.sql");
             var connection = old.open();
             var statement = connection.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R-- statement\\R")) {
                statement.execute(sql);
            }
            String product = "INSERT INTO products VALUES ('%s', '" + shop + "', '%s', %d, '%s', '%s', 1, %s, NULL, NULL, '%s', %d, 0)";
            statement.execute(product.formatted(OLD_RICE, "Old rice", 1_000, "2026-01-01T08:00:00Z", "2026-01-01T08:00:00Z", "'R-OLD'", "kg", 8_500));
            statement.execute(product.formatted(OLD_OIL, "Old oil", 1_500, "2026-01-02T08:00:00Z", "2026-01-02T08:00:00Z", "NULL", "litre", 7_500));
            statement.execute("INSERT INTO sales VALUES ('" + OLD_SALE + "', '" + shop + "', 'OLD-1', '" + device
                    + "', 'cash', 5250, '2026-01-03T10:00:00Z')");
            String line = "INSERT INTO sale_items VALUES ('%s', '" + OLD_SALE + "', '%s', '%s', '%s', %d, %d, %d)";
            statement.execute(line.formatted(FIRST_LINE, OLD_OIL, "Old oil", "litre", 2_000, 1_500, 3_000));
            statement.execute(line.formatted(SECOND_LINE, OLD_RICE, "Old rice", "kg", 1_500, 1_000, 1_500));
            statement.execute(line.formatted(THIRD_LINE, OLD_OIL, "Old oil", "litre", 500, 1_500, 750));
            statement.execute("INSERT INTO sale_events VALUES ('" + SaleEvent.createdEventId(OLD_SALE)
                    + "', '" + OLD_SALE + "', '" + shop + "', '" + device + "', 'created', NULL, 'Sale OLD-1', '2026-01-03T10:00:00Z')");
        }
        return old;
    }

    @Test
    void upgradingGivesOldLinesTheirProductsReferenceAndKeepsTheirOrderAndRules() throws Exception {
        SqliteDatabase old = schemaFiveShop("old.db");

        old.initialize();
        old.initialize();      // a second start changes nothing

        SaleService upgraded = new SaleService(new SqliteSaleRepository(old), new SqliteProductRepository(old));
        Sale expected = new Sale(OLD_SALE, shop, "OLD-1", device, PaymentMethod.CASH, 5_250,
                Instant.parse("2026-01-03T10:00:00Z"), List.of(
                new SaleItem(FIRST_LINE, OLD_SALE, OLD_OIL, "P-0002", "Old oil", "litre", 2_000, 1_500, 3_000),
                new SaleItem(SECOND_LINE, OLD_SALE, OLD_RICE, "P-0001", "Old rice", "kg", 1_500, 1_000, 1_500),
                new SaleItem(THIRD_LINE, OLD_SALE, OLD_OIL, "P-0002", "Old oil", "litre", 500, 1_500, 750)));
        assertEquals(expected, upgraded.getSale(shop, OLD_SALE).orElseThrow());

        try (var connection = old.open(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                assertEquals(SqliteDatabase.SCHEMA_VERSION, rows.getInt(1));
            }
            List<String> kept = new ArrayList<>();
            try (var rows = statement.executeQuery(
                    "SELECT name FROM sqlite_schema WHERE tbl_name = 'sale_items' AND sql IS NOT NULL AND type != 'table' ORDER BY name")) {
                while (rows.next()) kept.add(rows.getString(1));
            }
            assertEquals(List.of("sale_items_no_delete", "sale_items_no_update", "sale_items_sale",
                    "sale_items_sale_product_id"), kept);
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sale_items SET product_reference = 'X'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM sale_items"));
        }

        // Cancelling the old sale links each rebuilt line to its stock return.
        upgraded.voidCashSale(UUID.randomUUID(), shop, OLD_SALE, device, UUID.randomUUID(), "After the upgrade");
        StockService oldStock = new StockService(new SqliteStockRepository(old));
        assertEquals(10_000, oldStock.current(shop, OLD_RICE).quantityMilli());
        assertEquals(10_000, oldStock.current(shop, OLD_OIL).quantityMilli());
        try (var connection = old.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            assertFalse(rows.next());
        }
        Sale newSale = upgraded.makeCashSale(UUID.randomUUID(), shop, device, "NEW-1",
                List.of(new SaleItemRequest(OLD_RICE, 1_000))).sale();
        assertEquals(List.of("P-0001"), references(newSale));
    }

    @Test
    void aFailedUpgradeChangesNothingAndCanBeTriedAgain() throws Exception {
        SqliteDatabase old = schemaFiveShop("failed.db");
        try (var connection = old.open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sale_items_v9 (unexpected TEXT)");
        }

        assertThrows(SQLException.class, old::initialize);

        try (var connection = old.open(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                assertEquals(5, rows.getInt(1));
            }
            try (var rows = statement.executeQuery(
                    "SELECT count(*) FROM pragma_table_info('sale_items') WHERE name = 'product_reference'")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM sale_items")) {
                rows.next();
                assertEquals(3, rows.getInt(1));
            }
            statement.execute("DROP TABLE sale_items_v9");
        }

        old.initialize();
        SaleService upgraded = new SaleService(new SqliteSaleRepository(old), new SqliteProductRepository(old));
        assertEquals(List.of("P-0002", "P-0001", "P-0002"), references(upgraded.getSale(shop, OLD_SALE).orElseThrow()));
    }
}

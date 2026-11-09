package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.*;
import cm.sji.innova.core.sale.*;
import cm.sji.innova.core.stock.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductFamiliesTest {
    @TempDir Path directory;
    UUID shop = UUID.randomUUID(), device = UUID.randomUUID();
    SqliteDatabase database() throws Exception {
        var db = new SqliteDatabase(directory.resolve("catalog.db")); db.initialize(); return db;
    }
    ProductService products(SqliteDatabase db) { return new ProductService(new SqliteProductRepository(db)); }
    ProductFamilyService families(SqliteDatabase db) {
        return new ProductFamilyService(new SqliteProductFamilyRepository(db),products(db));
    }
    Product bag(ProductFamilyService service, UUID family, String label) throws Exception {
        return service.createVariant(shop,family,label,28_000,"SUP-25",null,null,"bag",
                Map.of("Type","Basmati","Poids","25 kg"));
    }
    @Test void riceVariantsHaveSeparateStockReferencesAndPersistentGrouping() throws Exception {
        var db=database(); var f=families(db); var family=f.create(shop,"Riz","Alimentation");
        var large=bag(f,family.id(),"Basmati — Sac de 25 kg");
        var small=f.createVariant(shop,family.id(),"Basmati — Sac de 5 kg",6000,null,4000L,null,"bag",Map.of("Poids","5 kg"));
        assertEquals("P-0001",large.reference()); assertEquals("P-0002",small.reference());
        var stock=new StockService(new SqliteStockRepository(db));
        stock.receive(UUID.randomUUID(),shop,large.id(),device,4000,"Four bags");
        assertEquals(4000,stock.current(shop,large.id()).quantityMilli());
        assertEquals(0,stock.current(shop,small.id()).quantityMilli());
        var reopened=database();
        assertEquals(List.of(large,small),families(reopened).variants(shop,family.id()));
        assertEquals(List.of(large),f.search(shop,"riz BASMATI 25"));
        assertEquals(List.of(small),f.search(shop,small.reference()));
        assertEquals(List.of("Alimentation"),f.categories(shop));
        assertEquals(List.of("25 kg","5 kg"),f.specificationSuggestions(shop,family.id()).get("Poids"));
    }
    @Test void standaloneProductsCanBeGroupedWithoutLosingStockOrSaleIdentity() throws Exception {
        var db=database();var p=products(db).create(shop,"Café",500);
        var stock=new StockService(new SqliteStockRepository(db));stock.receive(UUID.randomUUID(),shop,p.id(),device,10_000,"Opening count");
        var sales=new SaleService(new SqliteSaleRepository(db),new SqliteProductRepository(db));
        var sale=sales.makeCashSale(UUID.randomUUID(),shop,device,"R-1",List.of(new SaleItemRequest(p.id(),2000))).sale();
        var f=families(db);assertEquals(List.of(p),f.ungrouped(shop));
        var family=f.create(shop,"Boissons","Épicerie");f.assign(shop,p.id(),family.id());
        var assigned=f.variants(shop,family.id()).getFirst();
        assertEquals(p.id(),assigned.id());assertEquals(p.reference(),assigned.reference());
        assertEquals(p.version()+1,assigned.version());f.assign(shop,p.id(),family.id());
        assertEquals(assigned,f.variants(shop,family.id()).getFirst());
        assertEquals(List.of(assigned),f.search(shop,"epicerie cafe"));
        assertEquals(8000,stock.current(shop,p.id()).quantityMilli());
        assertEquals(sale,sales.getSale(shop,sale.id()).orElseThrow());
        assertTrue(f.ungrouped(shop).isEmpty());
    }
    @Test void familyLinksCannotCrossShopsEvenThroughDirectRepositoryInserts() throws Exception {
        var db=database();var f=families(db);var other=UUID.randomUUID();var family=f.create(other,"Other","Category");
        var p=products(db).create(shop,"Product",100);
        assertThrows(IllegalArgumentException.class,()->f.assign(shop,p.id(),family.id()));
        assertThrows(IllegalArgumentException.class,()->bag(f,family.id(),"Invalid"));
        assertThrows(SQLException.class,()->products(db).createVariant(shop,family.id(),"Wrong",100,null,null,null,"piece",Map.of()));
        assertEquals(List.of(p),products(db).list(shop));
        assertTrue(f.list(shop).isEmpty());assertTrue(f.search(shop,"Other").isEmpty());
    }
    @Test void bagsMustBeWholeInReceiptsSalesAndThresholdsAndVoidsRestoreBags() throws Exception {
        var db=database();var f=families(db);var p=bag(f,f.create(shop,"Rice",null).id(),"25 kg");
        var stock=new StockService(new SqliteStockRepository(db));
        assertThrows(IllegalArgumentException.class,()->stock.receive(UUID.randomUUID(),shop,p.id(),device,1500,"Invalid"));
        assertThrows(IllegalArgumentException.class,()->stock.setMinimum(shop,p.id(),500));
        stock.receive(UUID.randomUUID(),shop,p.id(),device,4000,"Four bags");
        var sales=new SaleService(new SqliteSaleRepository(db),new SqliteProductRepository(db));
        assertThrows(IllegalArgumentException.class,()->sales.makeCashSale(UUID.randomUUID(),shop,device,"bad",List.of(new SaleItemRequest(p.id(),1500))));
        var sale=sales.makeCashSale(UUID.randomUUID(),shop,device,"good",List.of(new SaleItemRequest(p.id(),1000))).sale();
        assertEquals("bag",sale.items().getFirst().unit());assertEquals(3000,stock.current(shop,p.id()).quantityMilli());
        sales.voidCashSale(UUID.randomUUID(),shop,sale.id(),device,UUID.randomUUID(),"Mistake");
        assertEquals(4000,stock.current(shop,p.id()).quantityMilli());
    }
    void legacy(SqliteDatabase db) throws Exception { legacy(db, "LF"); }
    void legacy(SqliteDatabase db, String lineEndings) throws Exception {
        try(var in=getClass().getResourceAsStream("/schema-v5.sql");var c=db.open();var s=c.createStatement()) {
            String schema = new String(in.readAllBytes(),StandardCharsets.UTF_8);
            if (lineEndings.equals("CRLF")) schema = schema.replace("\r\n", "\n").replace("\n", "\r\n");
            for(String sql:schema.split("\\R-- statement\\R"))s.execute(sql);
            s.execute("INSERT INTO products VALUES('"+UUID.randomUUID()+"','"+shop+"','Old rice',500,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',7,'R-OLD',NULL,'Food','piece',10000,2000)");
        }
    }
    /** A cash sale and its cancellation, written row by row as schema 5 wrote them, without today's code. */
    void legacySaleAndVoid(SqliteDatabase db, Sale sale) throws Exception {
        var item=sale.items().getFirst();UUID voided=UUID.randomUUID(),cashier=UUID.randomUUID(),saleMove=UUID.randomUUID(),voidMove=UUID.randomUUID();
        String at=sale.createdAt().toString(),later=sale.createdAt().plusSeconds(60).toString(),receipt=sale.receiptNumber();
        try(var c=db.open();var s=c.createStatement()) {
            s.execute("INSERT INTO sales VALUES('%s','%s','%s','%s','cash',%d,'%s')".formatted(sale.id(),shop,receipt,device,sale.totalAmount(),at));
            s.execute("INSERT INTO sale_items VALUES('%s','%s','%s','%s','%s',%d,%d,%d)".formatted(item.id(),sale.id(),item.productId(),item.productName(),item.unit(),item.quantityMilli(),item.unitPrice(),item.subtotal()));
            s.execute("INSERT INTO sale_events VALUES('%s','%s','%s','%s','created',NULL,'Sale %s','%s')".formatted(SaleEvent.createdEventId(sale.id()),sale.id(),shop,device,receipt,at));
            s.execute("INSERT INTO stock_movements VALUES('%s','%s','%s','%s','sale',%d,'Sale %s','%s')".formatted(saleMove,shop,item.productId(),device,-item.quantityMilli(),receipt,at));
            s.execute("INSERT INTO sale_events VALUES('%s','%s','%s','%s','voided','%s','Old void','%s')".formatted(voided,sale.id(),shop,device,cashier,later));
            s.execute("INSERT INTO stock_movements VALUES('%s','%s','%s','%s','void',%d,'Void sale %s: Old void','%s')".formatted(voidMove,shop,item.productId(),device,item.quantityMilli(),receipt,later));
            s.execute("INSERT INTO sale_void_items VALUES('%s','%s','%s','%s','%s','%s',%d)".formatted(item.id(),voided,shop,sale.id(),item.productId(),voidMove,item.quantityMilli()));
            s.execute("INSERT INTO audit_log VALUES('%s','%s','%s','%s','VOID_SALE','sales','%s','%s','Old void','{\"status\":\"paid\"}','{\"status\":\"voided\"}','%s')".formatted(UUID.randomUUID(),shop,device,cashier,sale.id(),voided,later));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"LF", "CRLF"})
    void schemaFiveUpgradePreservesHistoricalSalesVoidsAndReferencesOnRepeat(String lineEndings) throws Exception {
        var db=new SqliteDatabase(directory.resolve("legacy.db"));legacy(db, lineEndings);
        UUID id;
        try(var c=db.open();var s=c.createStatement();var rows=s.executeQuery("SELECT id FROM products")){rows.next();id=UUID.fromString(rows.getString(1));}
        // The schema-5 sale line has no reference; the upgrade gives it the product's new one, P-0001.
        var item=new SaleItem(UUID.randomUUID(),UUID.randomUUID(),id,"P-0001","Old rice","piece",2000,500,1000);
        var sale=new Sale(item.saleId(),shop,"OLD-1",device,PaymentMethod.CASH,1000,java.time.Instant.parse("2026-01-02T00:00:00Z"),List.of(item));
        legacySaleAndVoid(db,sale);
        var service=new SaleService(new SqliteSaleRepository(db),new SqliteProductRepository(db));
        db.initialize();var migrated=products(db).list(shop).getFirst();
        assertEquals(id,migrated.id());assertEquals(7,migrated.version());assertNull(migrated.familyId());assertEquals("P-0001",migrated.reference());
        assertNull(migrated.costPrice());assertEquals(10000,new StockService(new SqliteStockRepository(db)).current(shop,id).quantityMilli());
        assertTrue(service.getSaleState(shop,sale.id()).orElseThrow().voided());
        assertEquals(sale,service.getSale(shop,sale.id()).orElseThrow());
        assertEquals(1,service.listAuditRecords(shop,sale.id()).size());
        db.initialize();assertEquals(migrated,products(db).list(shop).getFirst());
        assertEquals("P-0002",products(db).create(shop,"Next",1).reference());
        try(var c=db.open();var s=c.createStatement();var rows=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
    }
    @Test void failedFamilyMigrationRollsBackWholeUpgradeAndCanBeRetried() throws Exception {
        var db=new SqliteDatabase(directory.resolve("failure.db"));legacy(db);
        try(var c=db.open();var s=c.createStatement()){s.execute("CREATE TABLE product_reference_sequences(unexpected TEXT)");}
        assertThrows(SQLException.class,db::initialize);
        try(var c=db.open();var s=c.createStatement()) {
            try(var rows=s.executeQuery("PRAGMA user_version")){rows.next();assertEquals(5,rows.getInt(1));}
            try(var rows=s.executeQuery("SELECT count(*) FROM products")){rows.next();assertEquals(1,rows.getInt(1));}
            try(var rows=s.executeQuery("SELECT count(*) FROM pragma_table_info('products') WHERE name='reference'")){rows.next();assertEquals(0,rows.getInt(1));}
            s.execute("DROP TABLE product_reference_sequences");
        }
        db.initialize();assertEquals("P-0001",products(db).list(shop).getFirst().reference());
    }
    @Test void concurrentCreatesGetUniqueShopReferencesAndFailureDoesNotConsumeOne() throws Exception {
        var db=database();var f=families(db);var family=f.create(shop,"Rice",null);
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<Product>> tasks=new ArrayList<>();
            for(int i=0;i<8;i++)tasks.add(()->bag(f,family.id(),"Bag"));
            Set<String> refs=new HashSet<>();
            for(var future:executor.invokeAll(tasks))refs.add(future.get(10,TimeUnit.SECONDS).reference());
            assertEquals(8,refs.size());
        }
        assertThrows(SQLException.class,()->products(db).createVariant(shop,UUID.randomUUID(),"Bad",1,null,null,null,"bag",Map.of()));
        assertEquals("P-0009",products(db).create(shop,"Next",1).reference());
        assertEquals("P-0001",products(db).create(UUID.randomUUID(),"Other shop",1).reference());
    }
}

package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.*;
import cm.sji.innova.core.product.importer.*;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductImportConfirmationTest {
    @TempDir Path directory;
    UUID shop=UUID.randomUUID();
    SqliteDatabase db;
    ProductImportService imports;
    ProductService products;
    @BeforeEach void setup()throws Exception{
        db=new SqliteDatabase(directory.resolve("cella.db"));db.initialize();
        imports=new ProductImportService(new SqliteProductImportRepository(db));products=new ProductService(new SqliteProductRepository(db));
    }
    ImportPreview preview(String text)throws Exception{return imports.preview(shop,new StringReader(text),ImportOptions.DEFAULT);}
    ConfirmedImportRequest request(UUID id,ImportPreview p,List<Integer> rows){return new ConfirmedImportRequest(id,p.previewId(),p.contentHash(),rows);}
    @Test void changedRowsOrShopOrPreviewAreRejectedBeforeOperationRegistration()throws Exception{
        var rice=preview("name,unit,selling_price\nRice,piece,1000\n");var sugar=preview("name,unit,selling_price\nSugar,piece,2000\n");
        UUID id=UUID.randomUUID();var request=request(id,rice,List.of(1));
        assertThrows(IllegalArgumentException.class,()->imports.confirmImport(shop,request,sugar.rows()));
        assertThrows(IllegalArgumentException.class,()->imports.confirmImport(UUID.randomUUID(),request,rice.rows()));
        var wrongId=new ConfirmedImportRequest(id,UUID.randomUUID(),rice.contentHash(),List.of(1));
        assertThrows(IllegalArgumentException.class,()->imports.confirmImport(shop,wrongId,rice));
        assertTrue(products.list(shop).isEmpty());assertTrue(new SqliteProductImportRepository(db).findOperation(id).isEmpty());
    }
    @Test void changedSelectionOrDuplicateConsentIsRejectedOnRetryAfterReopen()throws Exception{
        var p=preview("name,unit,selling_price\nRice,piece,1000\nMaize,piece,500\n");UUID id=UUID.randomUUID();
        imports.confirmImport(shop,request(id,p,List.of(1)),p);
        var reopened=new ProductImportService(new SqliteProductImportRepository(new SqliteDatabase(directory.resolve("cella.db"))));
        assertThrows(IllegalArgumentException.class,()->reopened.confirmImport(shop,request(id,p,List.of(2)),p));
        assertThrows(IllegalArgumentException.class,()->reopened.confirmImport(shop,request(id,p,List.of(1,2)),p));
        var changed=new ConfirmedImportRequest(id,p.previewId(),p.contentHash(),List.of(1),true);
        assertThrows(IllegalArgumentException.class,()->reopened.confirmImport(shop,changed,p));
        assertEquals(1,products.list(shop).size());assertTrue(reopened.confirmImport(shop,request(id,p,List.of(1)),p).replayed());
        assertThrows(IllegalArgumentException.class,()->request(UUID.randomUUID(),p,List.of(1,1)));
        assertThrows(IllegalArgumentException.class,()->request(UUID.randomUUID(),p,List.of(0)));
    }
    @Test void raggedRowsAndBlankHeadersAreErrorsButQuotedCommasRemainValid()throws Exception{
        var extra=preview("name,unit,selling_price\nOil,piece,1500,EXTRA\n");assertEquals(0,extra.validRows());assertTrue(extra.rows().getFirst().errors().getFirst().contains("columns"));
        var missing=preview("name,unit,selling_price,buying_price\nOil,piece,1500\n");assertEquals(0,missing.validRows());
        var blank=preview("name,unit,selling_price,\nOil,piece,1500,\n");assertFalse(blank.fileErrors().isEmpty());
        var quoted=preview("name,unit,selling_price\r\n\"Oil, large\",piece,1500\r\n");assertEquals(1,quoted.validRows());assertEquals("Oil, large",quoted.rows().getFirst().name());
    }
    @Test void fieldDelimitersAndNullsCannotAliasFingerprints()throws Exception{
        var a=preview("name,model_reference,unit,selling_price\nA|B,C,piece,100\n");
        var b=preview("name,model_reference,unit,selling_price\nA,B|C,piece,100\n");
        assertNotEquals(a.rows().getFirst().rowHash(),b.rows().getFirst().rowHash());assertNotEquals(a.contentHash(),b.contentHash());
        assertNotEquals(ImportFingerprint.hash(Arrays.asList("A",null)),ImportFingerprint.hash(List.of("A","")));
        assertNotEquals(ImportFingerprint.hash(List.of("A\nB","C")),ImportFingerprint.hash(List.of("A","B\nC")));
    }
    @Test void duplicatesAreSkippedByDefaultAndNeedExplicitConsentIncludingNewCatalogMatches()throws Exception{
        var p=preview("name,unit,selling_price\nRice,piece,1000\n");products.create(shop,"Rice",900);
        var result=imports.confirmImport(shop,request(UUID.randomUUID(),p,List.of(1)),p);
        assertEquals(1,result.skippedCount());assertEquals(1,products.list(shop).size());
        var consent=new ConfirmedImportRequest(UUID.randomUUID(),p.previewId(),p.contentHash(),List.of(1),true);
        assertEquals(1,imports.confirmImport(shop,consent,p).importedCount());assertEquals(2,products.list(shop).size());
        var again=preview("name,unit,selling_price\nRice,piece,1000\n");assertTrue(again.rows().getFirst().hasWarnings());
        assertEquals(1,imports.confirmImport(shop,request(UUID.randomUUID(),again,List.of(1)),again).skippedCount());
    }
    @Test void concurrentIdenticalConfirmationReturnsOneProductAndStableIdentity()throws Exception{
        var p=preview("name,unit,selling_price\nRice,piece,1000\n");var request=request(UUID.randomUUID(),p,List.of(1));CountDownLatch go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<ImportResult> task=()->{go.await();return new ProductImportService(new SqliteProductImportRepository(db)).confirmImport(shop,request,p);};
            var a=executor.submit(task);var b=executor.submit(task);go.countDown();var one=a.get(5,TimeUnit.SECONDS);var two=b.get(5,TimeUnit.SECONDS);
            assertEquals(0,one.failedCount());assertEquals(0,two.failedCount());assertEquals(one.rowResults().getFirst().productId(),two.rowResults().getFirst().productId());
        }
        assertEquals(1,products.list(shop).size());
    }
    @Test void concurrentDifferentSelectionsCannotExtendTheSameOperation()throws Exception{
        var p=preview("name,unit,selling_price\nRice,piece,1000\nMaize,piece,500\n");UUID id=UUID.randomUUID();CountDownLatch go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<Boolean>> outcomes=new ArrayList<>();
            for(int row:List.of(1,2))outcomes.add(executor.submit(()->{go.await();try{imports.confirmImport(shop,request(id,p,List.of(row)),p);return true;}catch(IllegalArgumentException expected){return false;}}));
            go.countDown();int succeeded=0;for(var future:outcomes)if(future.get(5,TimeUnit.SECONDS))succeeded++;
            assertEquals(1,succeeded);
        }
        assertEquals(1,products.list(shop).size());
    }
}

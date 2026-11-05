package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.*;
import cm.sji.innova.core.product.importer.*;
import cm.sji.innova.core.product.photo.*;
import cm.sji.innova.desktop.catalog.importing.*;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImportAndPhotoControlsTest {
    @TempDir Path directory;
    UUID shop=UUID.randomUUID();
    SqliteDatabase db;
    ProductService products;
    ProductImportService imports;
    Path data;
    ResourceBundle messages=Messages.load(Locale.FRENCH).bundle();
    @BeforeEach void setup()throws Exception{data=directory.resolve("shop");db=new SqliteDatabase(data.resolve("cella.db"));db.initialize();products=new ProductService(new SqliteProductRepository(db));imports=new ProductImportService(new SqliteProductImportRepository(db));}
    <T>T edt(Supplier<T> action)throws Exception{AtomicReference<T> result=new AtomicReference<>();SwingUtilities.invokeAndWait(()->result.set(action.get()));return result.get();}
    CsvImportPanel open(ProductImportService service)throws Exception{var p=edt(()->new CsvImportPanel(service,shop,data,messages,()->{},()->{},busy->{}));p.ready().toCompletableFuture().get(5,TimeUnit.SECONDS);return p;}
    @Test void previewDefaultsExcludeInvalidAndDuplicateRowsAndConfirmationImportsOnlySelectedRows()throws Exception{
        products.create(shop,"Riz",1000);Path csv=directory.resolve("catalog.csv");Files.writeString(csv,"name,unit,selling_price\nHuile,piece,1500\nRiz,bag,28000\nErreur,piece,abc\n");
        var panel=open(imports);edt(()->panel.previewFile(csv)).toCompletableFuture().get(5,TimeUnit.SECONDS);
        edt(()->{assertEquals(true,panel.previewTable().getValueAt(0,0));assertEquals(false,panel.previewTable().getValueAt(1,0));assertEquals(false,panel.previewTable().getValueAt(2,0));assertFalse(panel.previewTable().isCellEditable(2,0));return null;});
        assertEquals(1,products.list(shop).size());var result=edt(panel::submit).toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(1,result.importedCount());assertEquals(2,products.list(shop).size());assertFalse(Files.exists(data.resolve("pending-csv.properties")));edt(()->{panel.leave();return null;});
    }
    @Test void lostCommitResponseCanResumeAfterPanelRecreationWithoutDuplicatingProducts()throws Exception{
        var real=new SqliteProductImportRepository(db);
        ProductImportRepository lostResponse=new ProductImportRepository(){
            public boolean wasPreviouslyImported(UUID s,String h)throws SQLException{return real.wasPreviouslyImported(s,h);}
            public java.util.List<ExistingProductSummary> listExistingProducts(UUID s)throws SQLException{return real.listExistingProducts(s);}
            public Optional<ImportOperationRecord> findOperation(UUID id)throws SQLException{return real.findOperation(id);}
            public void recordOperation(UUID id,UUID s,String h,int total)throws SQLException{real.recordOperation(id,s,h,total);}
            public java.util.List<ImportedRowRecord> listImportedRows(UUID id)throws SQLException{return real.listImportedRows(id);}
            public Product insertImportedProduct(UUID id,int row,String hash,Product product)throws SQLException{real.insertImportedProduct(id,row,hash,product);throw new SQLException("Commit response lost");}
        };
        Path csv=directory.resolve("catalog.csv");Files.writeString(csv,"name,unit,selling_price\nHuile,litre,1500\n");
        var first=open(new ProductImportService(lostResponse));edt(()->first.previewFile(csv)).toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(1,edt(first::submit).toCompletableFuture().get(5,TimeUnit.SECONDS).failedCount());
        edt(()->{first.leave();return null;});Files.delete(csv);
        var restored=open(imports);edt(()->{assertEquals(1,restored.previewTable().getRowCount());assertFalse(restored.previewTable().isCellEditable(0,0));return null;});
        assertEquals(1,edt(restored::submit).toCompletableFuture().get(5,TimeUnit.SECONDS).importedCount());
        assertEquals(1,products.list(shop).size());assertFalse(Files.exists(data.resolve("pending-csv.properties")));edt(()->{restored.leave();return null;});
    }
    @Test void pendingStoreRejectsConflictingAccessAndDamagedOrWrongShopState()throws Exception{
        var p=imports.preview(shop,new StringReader("name,unit,selling_price\nRice,piece,100\n"),ImportOptions.DEFAULT);
        var pending=new PendingImportStore.Pending(new ConfirmedImportRequest(UUID.randomUUID(),p.previewId(),p.contentHash(),java.util.List.of(1)),p);
        try(var store=new PendingImportStore(data,shop)){store.save(pending);assertThrows(IOException.class,()->new PendingImportStore(data,shop));}
        try(var wrong=new PendingImportStore(data,UUID.randomUUID())){assertThrows(IOException.class,wrong::read);}
        Path file=data.resolve("pending-csv.properties");Properties changed=new Properties();try(var in=Files.newInputStream(file)){changed.load(in);}changed.setProperty("selection","2");try(var out=Files.newOutputStream(file)){changed.store(out,"Damaged selection");}
        byte[] before=Files.readAllBytes(file);try(var store=new PendingImportStore(data,shop)){assertThrows(IOException.class,store::read);}assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void photoControlsPersistChosenImageAndPreserveItAfterInvalidReplacement()throws Exception{
        var p=products.create(shop,"Riz",1000);var service=new ProductPhotoService(new SqliteProductPhotoRepository(db),data);
        var panel=edt(()->new ProductPhotoPanel(service,shop,p.id(),messages,busy->{}));panel.initialLoadCompletion().toCompletableFuture().get(5,TimeUnit.SECONDS);
        Path source=directory.resolve("image.png");ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",source.toFile());
        edt(()->panel.setPhoto(source)).toCompletableFuture().get(5,TimeUnit.SECONDS);Files.delete(source);
        var saved=service.getPhotoMetadata(shop,p.id()).orElseThrow();assertInstanceOf(PhotoLoadResult.Loaded.class,service.loadPhoto(shop,p.id()));
        Path bad=directory.resolve("bad.png");Files.writeString(bad,"not an image");
        var attempt=edt(()->panel.setPhoto(bad));assertThrows(ExecutionException.class,()->attempt.toCompletableFuture().get(5,TimeUnit.SECONDS));
        assertEquals(saved,service.getPhotoMetadata(shop,p.id()).orElseThrow());
        edt(panel::removePhoto).toCompletableFuture().get(5,TimeUnit.SECONDS);assertInstanceOf(PhotoLoadResult.NotAttached.class,service.loadPhoto(shop,p.id()));
    }
}

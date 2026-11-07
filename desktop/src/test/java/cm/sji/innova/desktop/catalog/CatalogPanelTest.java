package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.*;
import cm.sji.innova.core.stock.*;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPanelTest {
    @TempDir Path directory;
    UUID shop=UUID.randomUUID();
    <T>T edt(Supplier<T> work)throws Exception{AtomicReference<T> result=new AtomicReference<>();SwingUtilities.invokeAndWait(()->result.set(work.get()));return result.get();}
    CatalogPanel open(ProductService products,ProductFamilyService families,StockService stock)throws Exception{
        CatalogPanel panel=edt(()->new CatalogPanel(products,families,stock,shop,Messages.load(Locale.FRENCH).bundle()));
        panel.initialLoadCompletion().toCompletableFuture().get(5,TimeUnit.SECONDS);return panel;
    }
    @Test void standaloneProductIsVisibleImmediatelyAndCategoryFilterDoesNotHideItInAFamily()throws Exception{
        var db=new SqliteDatabase(directory.resolve("catalog.db"));db.initialize();
        var products=new ProductService(new SqliteProductRepository(db));
        var families=new ProductFamilyService(new SqliteProductFamilyRepository(db),products);
        families.create(shop,"Riz","Alimentation");
        var shirt=products.create(shop,"T-shirt",5000,null,null,"Vêtements","piece",Map.of());
        var panel=open(products,families,new StockService(new SqliteStockRepository(db)));
        edt(()->{assertEquals(1,panel.variantTable().getRowCount());
            assertEquals("T-shirt",panel.variantTable().getValueAt(0,0));
            panel.variantTable().setRowSelectionInterval(0,0);
            panel.variantTable().getActionMap().get("openProduct").actionPerformed(null);
            JButton back=(JButton)named(panel,"catalogBack");assertNotNull(back);back.doClick();
            assertEquals(shirt.reference(),panel.variantTable().getValueAt(0,1));
            return null;});
    }
    @Test void productMovementHistoryShowsLocalDatesAndSignedQuantitiesWithoutChangingStock()throws Exception{
        var db=new SqliteDatabase(directory.resolve("catalog.db"));db.initialize();
        var products=new ProductService(new SqliteProductRepository(db));var families=new ProductFamilyService(new SqliteProductFamilyRepository(db),products);
        var p=products.create(shop,"Riz 25 kg",28000,null,null,null,"bag",Map.of());var device=UUID.randomUUID();
        var repository=new SqliteStockRepository(db);var stock=new StockService(repository);
        repository.record(new StockMovement(UUID.randomUUID(),shop,p.id(),device,StockMovement.Type.DELIVERY,3000,"Livraison",java.time.Instant.parse("2026-11-06T23:30:00Z")));
        stock.adjust(UUID.randomUUID(),shop,p.id(),device,-1000,"Comptage");
        var panel=open(products,families,stock);
        edt(()->{panel.showProduct(p.id());((JButton)named(panel,"openProductMovements")).doClick();return null;});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(edt(()->named(panel,"productMovementHistory"))==null){assertTrue(System.nanoTime()<deadline);Thread.sleep(10);}
        edt(()->{
            JTable history=(JTable)named(panel,"productMovementHistory");assertEquals(2,history.getRowCount());
            boolean delivery=false,correction=false;
            for(int row=0;row<history.getRowCount();row++){
                if("Livraison".equals(history.getValueAt(row,3))){delivery=true;assertTrue(history.getValueAt(row,0).toString().contains("07/11/2026"));assertTrue(history.getValueAt(row,0).toString().contains("00:30"));assertEquals("+3 sacs",history.getValueAt(row,2));}
                if("Comptage".equals(history.getValueAt(row,3))){correction=true;assertEquals("-1 sac",history.getValueAt(row,2));}
            }
            assertTrue(delivery&&correction);((JButton)named(panel,"backFromProductMovements")).doClick();return null;
        });
        assertEquals(2000,stock.current(shop,p.id()).quantityMilli());assertEquals(2,stock.history(shop,p.id()).size());
    }
    @Test void searchFindsExactVariantByReferenceOrCharacteristicsAndSurvivesBackNavigation()throws Exception{
        var db=new SqliteDatabase(directory.resolve("catalog.db"));db.initialize();var products=new ProductService(new SqliteProductRepository(db));var families=new ProductFamilyService(new SqliteProductFamilyRepository(db),products);
        var rice=families.create(shop,"Riz","Épicerie");var p=families.createVariant(shop,rice.id(),"Basmati 25 kg",28000,null,null,null,"bag",Map.of("Couleur","Blanc"));
        families.createVariant(shop,rice.id(),"Jasmine 5 kg",5000,null,null,null,"bag",Map.of());
        Product old=products.create(shop,"Café moulu",500);
        var panel=open(products,families,new StockService(new SqliteStockRepository(db)));
        edt(()->{panel.searchField().setText("epicerie blanc 25");assertEquals(1,panel.variantTable().getRowCount());assertEquals(p.reference(),panel.variantTable().getValueAt(0,1));panel.showProduct(p.id());panel.showFamilies();assertEquals("epicerie blanc 25",panel.searchField().getText());assertEquals(1,panel.variantTable().getRowCount());panel.searchField().setText(old.reference());assertEquals("Café moulu",panel.variantTable().getValueAt(0,0));return null;});
    }
    @Test void stockErrorsAreNotDisplayedAsZeroAndRefreshRecovers()throws Exception{
        var db=new SqliteDatabase(directory.resolve("catalog.db"));db.initialize();var products=new ProductService(new SqliteProductRepository(db));products.create(shop,"Rice",500);
        var families=new ProductFamilyService(new SqliteProductFamilyRepository(db),products);AtomicBoolean failing=new AtomicBoolean(true);
        var real=new SqliteStockRepository(db);
        StockRepository repository=new StockRepository(){
            public List<StockLevel> list(UUID id)throws SQLException{if(failing.get())throw new SQLException("offline storage failure");return real.list(id);}
            public StockResult record(StockMovement m)throws SQLException{return real.record(m);}
            public StockLevel current(UUID s,UUID p)throws SQLException{return real.current(s,p);}
            public List<StockMovement> history(UUID s,UUID p)throws SQLException{return real.history(s,p);}
            public StockLevel setMinimum(UUID s,UUID p,long q)throws SQLException{return real.setMinimum(s,p,q);}
        };
        var panel=open(products,families,new StockService(repository));
        edt(()->{panel.showFamily(null);assertEquals("Indisponible",panel.variantTable().getValueAt(0,2));return null;});
        failing.set(false);edt(panel::reload).toCompletableFuture().get(5,TimeUnit.SECONDS);
        edt(()->{assertEquals("0 pièces",panel.variantTable().getValueAt(0,2));return null;});
    }
    static Component named(Component root,String name){if(name.equals(root.getName()))return root;if(root instanceof Container c)for(var child:c.getComponents()){var found=named(child,name);if(found!=null)return found;}return null;}
    @Test void failedVariantSaveRetainsInputAndDoesNotCreateTheFamilyAgainOnRetry()throws Exception{
        var db=new SqliteDatabase(directory.resolve("catalog.db"));db.initialize();var real=new SqliteProductRepository(db);AtomicBoolean fail=new AtomicBoolean(true);
        ProductRepository repository=new ProductRepository(){
            public void insert(Product p)throws SQLException{insertAndReturn(p);}
            public Product insertAndReturn(Product p)throws SQLException{if(fail.getAndSet(false))throw new SQLException("Simulated failure before commit");return real.insertAndReturn(p);}
            public List<Product> findByShop(UUID s)throws SQLException{return real.findByShop(s);}
        };
        var products=new ProductService(repository);var families=new ProductFamilyService(new SqliteProductFamilyRepository(db),products);
        CountDownLatch failed=new CountDownLatch(1),saved=new CountDownLatch(1);AtomicReference<VariantForm> ref=new AtomicReference<>();
        edt(()->{var form=new VariantForm(products,families,shop,Messages.load(Locale.ENGLISH).bundle(),List.of(),List.of(),null,()->{},p->saved.countDown(),pending->{if(!pending)failed.countDown();});ref.set(form);((JTextField)named(form,"variantName")).setText("Basmati 25 kg");((JTextField)named(form,"sellingPrice")).setText("28000");((JComboBox<?>)named(form,"productFamily")).getEditor().setItem("Riz");((JButton)named(form,"saveVariant")).doClick();return null;});
        assertTrue(failed.await(5,TimeUnit.SECONDS));assertEquals(1,families.list(shop).size());assertTrue(products.list(shop).isEmpty());
        edt(()->{assertEquals("Basmati 25 kg",((JTextField)named(ref.get(),"variantName")).getText());((JButton)named(ref.get(),"saveVariant")).doClick();return null;});
        assertTrue(saved.await(5,TimeUnit.SECONDS));assertEquals(1,families.list(shop).size());assertEquals(1,products.list(shop).size());
    }
}

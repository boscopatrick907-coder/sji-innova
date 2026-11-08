package cm.sji.innova.desktop.stock;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.*;
import cm.sji.innova.core.stock.*;
import cm.sji.innova.desktop.shell.Messages;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Regression checks for restored requests, layout, and competing stock operations. */
class StockRecoveryRegressionTest {
    @TempDir Path directory;
    UUID shop=UUID.randomUUID(),device=UUID.randomUUID();
    SqliteDatabase db;ProductService products;StockService stock;Product product;
    @BeforeEach void setup()throws Exception{
        db=new SqliteDatabase(directory.resolve("shop.db"));db.initialize();products=new ProductService(new SqliteProductRepository(db));
        stock=new StockService(new SqliteStockRepository(db));product=products.create(shop,"Rice",1000);
    }
    <T>T edt(Supplier<T> action)throws Exception{var result=new AtomicReference<T>();SwingUtilities.invokeAndWait(()->result.set(action.get()));return result.get();}
    StockPanel open(PendingReceiptStore r,PendingStockAdjustmentStore a,StockService service)throws Exception{
        return edt(()->{FlatLightLaf.setup();CatalogStyle.installDefaults();var panel=new StockPanel(service,products,shop,device,Messages.load(Locale.ENGLISH).bundle(),r,a);panel.setErrorHandler(text->{});return panel;});
    }
    FilePendingReceiptStore receipts(){return new FilePendingReceiptStore(directory.resolve("pending-stock-receipt.properties"));}
    FilePendingStockAdjustmentStore adjustments(){return new FilePendingStockAdjustmentStore(directory.resolve("pending-stock-adjustment.properties"));}
    static void await(CountDownLatch latch)throws IOException{try{if(!latch.await(10,TimeUnit.SECONDS))throw new IOException("Review barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
    static Component named(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container p)for(var child:p.getComponents()){var f=named(child,name);if(f!=null)return f;}return null;}
    static JButton button(Component c,String text){if(c instanceof JButton b&&text.equals(b.getText()))return b;if(c instanceof Container p)for(var child:p.getComponents()){var f=button(child,text);if(f!=null)return f;}return null;}
    static void layout(Component c){if(c instanceof JScrollPane sp&&sp.getViewport().getView() instanceof JTable t)sp.setColumnHeaderView(t.getTableHeader());if(c instanceof Container p){p.doLayout();for(var child:p.getComponents())layout(child);}}

    @Test void recoveredReceiptShouldExplainThatItIsPending()throws Exception{
        var store=receipts();store.save(new PendingReceipt(UUID.randomUUID(),shop,product.id(),device,"piece",1000,"Delivery"));
        var panel=open(store,adjustments(),stock);panel.initialLoadCompletion().toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertEquals(0,stock.current(shop,product.id()).quantityMilli(),"Opening recovery must not send anything");
        edt(()->{assertTrue(panel.hasPendingReceipt());assertTrue(panel.statusLabel().getText().toLowerCase(Locale.ROOT).contains("pending"),panel.statusLabel().getText());return null;});
    }
    @Test void refreshAfterTemporaryReadFailureShouldUnblockAHealthyStore()throws Exception{
        var real=receipts();AtomicBoolean fail=new AtomicBoolean(true);
        PendingReceiptStore flaky=new PendingReceiptStore(){
            public Optional<PendingReceipt> load()throws IOException{if(fail.get())throw new IOException("Temporary storage failure");return real.load();}
            public void save(PendingReceipt r)throws IOException{real.save(r);}public void clear(UUID id)throws IOException{real.clear(id);}public Path file(){return real.file();}
        };
        var panel=open(flaky,adjustments(),stock);assertThrows(ExecutionException.class,()->panel.initialLoadCompletion().toCompletableFuture().get(10,TimeUnit.SECONDS));
        fail.set(false);CountDownLatch loaded=new CountDownLatch(1);
        edt(()->{panel.setOnLoadWorkerDone(loaded::countDown);panel.refreshButton().doClick();return null;});await(loaded);
        edt(()->{assertTrue(panel.receiveButton().isEnabled(),"A successful refresh must clear the temporary recovery block");return null;});
    }
    @Test void quantitiesAndHistoryShouldRemainReachableBelowTheThreeForms()throws Exception{
        var panel=open(receipts(),adjustments(),stock);panel.initialLoadCompletion().toCompletableFuture().get(10,TimeUnit.SECONDS);
        edt(()->{panel.setSize(918,720);for(int i=0;i<5;i++)layout(panel);
            assertTrue(panel.stockTable().getParent().getHeight()>=40,"Stock table has height "+panel.stockTable().getHeight());
            assertTrue(panel.historyTable().getParent().getHeight()>=40,"History table has height "+panel.historyTable().getHeight());return null;});
    }
    @Test void receiptAndAdjustmentMustNotBothBecomePendingAfterStaleOppositeStoreChecks()throws Exception{
        var r=receipts();var a=adjustments();AtomicBoolean armed=new AtomicBoolean();
        CountDownLatch receiptChecked=new CountDownLatch(1),adjustmentChecked=new CountDownLatch(1),receiptSaved=new CountDownLatch(1);
        PendingReceiptStore receiptWriter=new PendingReceiptStore(){
            public Optional<PendingReceipt> load()throws IOException{return r.load();}
            public void save(PendingReceipt request)throws IOException{r.save(request);receiptSaved.countDown();}
            public void clear(UUID id)throws IOException{r.clear(id);}public Path file(){return r.file();}
        };
        PendingStockAdjustmentStore oppositeAdjustmentCheck=new PendingStockAdjustmentStore(){
            public Optional<PendingStockAdjustment> load()throws IOException{var found=a.load();if(armed.get()){receiptChecked.countDown();await(adjustmentChecked);}return found;}
            public void save(PendingStockAdjustment request)throws IOException{a.save(request);}public void clear(UUID id)throws IOException{a.clear(id);}public Path file(){return a.file();}
        };
        PendingReceiptStore oppositeReceiptCheck=new PendingReceiptStore(){
            public Optional<PendingReceipt> load()throws IOException{var found=r.load();if(armed.get()){await(receiptSaved);}return found;}
            public void save(PendingReceipt request)throws IOException{r.save(request);}public void clear(UUID id)throws IOException{r.clear(id);}public Path file(){return r.file();}
        };
        var real=new SqliteStockRepository(db);
        StockRepository uncertain=new StockRepository(){
            public StockResult record(StockMovement request)throws SQLException{throw new SQLException("Response uncertain");}
            public StockLevel current(UUID s,UUID p)throws SQLException{return real.current(s,p);}
            public java.util.List<StockLevel> list(UUID s)throws SQLException{return real.list(s);}
            public java.util.List<StockMovement> history(UUID s,UUID p)throws SQLException{return real.history(s,p);}
            public StockLevel setMinimum(UUID s,UUID p,long q)throws SQLException{return real.setMinimum(s,p,q);}
        };
        var first=open(receiptWriter,oppositeAdjustmentCheck,new StockService(uncertain));first.initialLoadCompletion().toCompletableFuture().get(10,TimeUnit.SECONDS);
        var second=open(oppositeReceiptCheck,a,new StockService(uncertain));second.initialLoadCompletion().toCompletableFuture().get(10,TimeUnit.SECONDS);
        CountDownLatch firstDone=new CountDownLatch(1),secondDone=new CountDownLatch(1);armed.set(true);
        edt(()->{first.setOnReceiptWorkerDone(firstDone::countDown);first.quantityField().setText("1");first.reasonField().setText("Delivery");first.receiveButton().doClick();return null;});await(receiptChecked);
        edt(()->{second.setOnReceiptWorkerDone(secondDone::countDown);((JTextField)named(second,"stockAdjustmentQuantity")).setText("1");((JTextField)named(second,"stockAdjustmentReason")).setText("Count");button(second,"Apply Adjustment").doClick();return null;});
        await(secondDone);adjustmentChecked.countDown();await(firstDone);
        assertTrue(r.load().isPresent(), "The uncertain delivery must remain recoverable");
        assertTrue(a.load().isEmpty(), "The competing correction must not be persisted");
    }
    @Test void rejectedReceiptCleanupMustRunOffTheEventThread() throws Exception {
        stock.receive(UUID.randomUUID(), shop, product.id(), device,
                Long.MAX_VALUE / 1000 * 1000, "Opening quantity");
        var real = receipts();
        AtomicBoolean clearedOnEdt = new AtomicBoolean();
        PendingReceiptStore observed = new PendingReceiptStore() {
            public Optional<PendingReceipt> load() throws IOException { return real.load(); }
            public void save(PendingReceipt request) throws IOException { real.save(request); }
            public void clear(UUID id) throws IOException {
                clearedOnEdt.set(SwingUtilities.isEventDispatchThread()); real.clear(id);
            }
            public Path file() { return real.file(); }
        };
        var panel = open(observed, adjustments(), stock);
        panel.initialLoadCompletion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        CountDownLatch done = new CountDownLatch(1);
        edt(() -> { panel.setOnReceiptWorkerDone(done::countDown);
            panel.quantityField().setText("1"); panel.reasonField().setText("Delivery");
            panel.receiveButton().doClick(); return null; });
        await(done);
        assertFalse(clearedOnEdt.get());
        assertTrue(real.load().isEmpty());
        edt(() -> { assertFalse(panel.hasPendingReceipt()); return null; });
    }

}

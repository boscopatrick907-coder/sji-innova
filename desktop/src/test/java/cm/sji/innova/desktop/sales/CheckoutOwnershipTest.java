package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.*;
import cm.sji.innova.core.sale.*;
import cm.sji.innova.core.stock.*;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutOwnershipTest {
    @TempDir Path directory;
    UUID shop=UUID.randomUUID(),device=UUID.randomUUID();
    SqliteDatabase db;
    Product product;
    StockService stock;
    FlakySaleRepository repository;
    SaleService sales;
    Path pending;
    @BeforeEach void setup()throws Exception{
        db=new SqliteDatabase(directory.resolve("cella.db"));db.initialize();
        var products=new ProductService(new SqliteProductRepository(db));product=products.create(shop,"Rice",1000);
        stock=new StockService(new SqliteStockRepository(db));stock.receive(UUID.randomUUID(),shop,product.id(),device,10000,"Opening");
        repository=new FlakySaleRepository(new SqliteSaleRepository(db));sales=new SaleService(repository,new SqliteProductRepository(db));pending=directory.resolve("pending-checkout.txt");
    }
    PendingCheckout sale(long quantity){return PendingCheckout.start(shop,device,List.of(BasketLine.oneOf(product,null).withQuantity(quantity)),LocalDate.of(2026,11,6));}
    PendingCheckoutStore store(){return new PendingCheckoutStore(pending);}
    Checkout checkout(){return new Checkout(sales,store(),shop);}

    @Test void conflictingRequestCannotOverwriteOrClearTheOriginalRecoveryRecord()throws Exception{
        var a=sale(1000);var b=sale(2000);store().save(a);byte[] before=Files.readAllBytes(pending);
        assertThrows(PendingCheckoutStore.ConflictException.class,()->store().save(b));
        assertThrows(PendingCheckoutStore.ConflictException.class,()->store().clear(b.saleId()));
        assertArrayEquals(before,Files.readAllBytes(pending));assertEquals(Optional.of(a),store().load());
    }
    @Test void uncertainCheckoutBlocksAnotherSubmissionUntilTheOriginalIsResolved()throws Exception{
        var a=sale(1000);var b=sale(2000);repository.failBeforeSaving=true;
        assertInstanceOf(Checkout.Outcome.Uncertain.class,checkout().submit(a));
        assertInstanceOf(Checkout.Outcome.NotSent.class,checkout().submit(b));
        assertInstanceOf(Checkout.Outcome.Uncertain.class,checkout().discard(b));
        assertEquals(Optional.of(a),store().load());assertEquals(1,repository.calls.get());
        assertInstanceOf(Checkout.Outcome.Saved.class,checkout().retry(a));
        assertInstanceOf(Checkout.Outcome.Saved.class,checkout().submit(b));
        assertEquals(7000,stock.current(shop,product.id()).quantityMilli());assertEquals(2,sales.listSales(shop).size());
    }
    @Test void anOldWindowCannotClearOrDiscardANewerPendingSale()throws Exception{
        var a=sale(1000);var b=sale(2000);assertInstanceOf(Checkout.Outcome.Saved.class,checkout().submit(a));store().save(b);
        assertInstanceOf(Checkout.Outcome.Uncertain.class,checkout().discard(a));
        assertInstanceOf(Checkout.Outcome.Uncertain.class,checkout().retry(a));
        assertEquals(Optional.of(b),store().load());
        assertEquals(1,sales.listSales(shop).size());
    }
    @Test void activeSubmissionHoldsTheLeaseThroughDatabaseConfirmation()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        SaleRepository paused=new SaleRepository(){
            public SaleResult recordSale(Sale s)throws SQLException{return repository.recordSale(s);}
            public SaleResult recordSale(Sale s,CashSaleRequest r)throws SQLException{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("Timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new SQLException(ex);}return repository.recordSale(s,r);}
            public Optional<Sale> findById(UUID s,UUID id)throws SQLException{return repository.findById(s,id);}
            public List<Sale> listByShop(UUID s)throws SQLException{return repository.listByShop(s);}
        };
        var a=sale(1000);var b=sale(2000);var first=new Checkout(new SaleService(paused,new SqliteProductRepository(db)),store(),shop);
        try(var executor=Executors.newSingleThreadExecutor()){
            var running=executor.submit(()->first.submit(a));assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{
                assertInstanceOf(Checkout.Outcome.NotSent.class,checkout().submit(b));
                assertInstanceOf(Checkout.Recovery.Busy.class,checkout().recover());
                assertThrows(PendingCheckoutStore.BusyException.class,()->store().clear(a.saleId()));
            }finally{release.countDown();}
            assertInstanceOf(Checkout.Outcome.Saved.class,running.get(5,TimeUnit.SECONDS));
        }
        assertEquals(9000,stock.current(shop,product.id()).quantityMilli());assertFalse(Files.exists(pending));
    }
    @Test void staleDamagedFileActionCannotMoveAValidPendingRequest()throws Exception{
        var a=sale(1000);store().save(a);
        assertThrows(PendingCheckoutStore.ConflictException.class,()->store().setAside());
        assertEquals(Optional.of(a),store().load());
    }
    public static class LockHolder {
        public static void main(String[] args)throws Exception{
            try(var lease=new PendingCheckoutStore(Path.of(args[0])).acquire()){
                System.out.println("LOCKED");System.out.flush();System.in.read();
            }
        }
    }
    @Test void aSecondJvmCannotSubmitOrRecoverWhileTheCheckoutLeaseIsHeld()throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),LockHolder.class.getName(),pending.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(var executor=Executors.newSingleThreadExecutor()){
            try{
                var signal=executor.submit(()->new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());
                assertEquals("LOCKED",signal.get(10,TimeUnit.SECONDS));
                assertInstanceOf(Checkout.Outcome.NotSent.class,checkout().submit(sale(1000)));
                assertInstanceOf(Checkout.Recovery.Busy.class,checkout().recover());assertFalse(Files.exists(pending));
                child.getOutputStream().write(1);child.getOutputStream().flush();assertTrue(child.waitFor(5,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
                assertInstanceOf(Checkout.Outcome.Saved.class,checkout().submit(sale(1000)));
            }finally{child.destroyForcibly();}
        }
    }
}

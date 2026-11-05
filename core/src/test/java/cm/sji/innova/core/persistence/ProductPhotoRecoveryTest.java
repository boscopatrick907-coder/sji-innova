package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.*;
import cm.sji.innova.core.product.photo.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductPhotoRecoveryTest {
    @TempDir Path directory;
    UUID shop = UUID.randomUUID();
    Product product;
    SqliteProductPhotoRepository repository;
    ProductPhotoService service;
    Path source;

    @BeforeEach void setup() throws Exception {
        var database = new SqliteDatabase(directory.resolve("cella.db"));
        database.initialize();
        product = new ProductService(new SqliteProductRepository(database)).create(shop,"Rice",100);
        repository = new SqliteProductPhotoRepository(database);
        service = new ProductPhotoService(repository,directory);
        source = directory.resolve("source.png");
        ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",source.toFile());
    }

    static class Delegate implements ProductPhotoRepository {
        final ProductPhotoRepository real;
        Delegate(ProductPhotoRepository real) { this.real=real; }
        public boolean productExists(UUID s,UUID p)throws SQLException{return real.productExists(s,p);}
        public Optional<ProductPhoto> findPhoto(UUID s,UUID p)throws SQLException{return real.findPhoto(s,p);}
        public void savePhoto(ProductPhoto p)throws SQLException{real.savePhoto(p);}
        public Optional<ProductPhoto> deletePhoto(UUID s,UUID p)throws SQLException{return real.deletePhoto(s,p);}
        public List<ProductPhoto> listPhotos(UUID s)throws SQLException{return real.listPhotos(s);}
    }

    @Test void cleanupWaitsForPublishedButUncommittedSaveAcrossServiceInstances() throws Exception {
        CountDownLatch published = new CountDownLatch(1), release = new CountDownLatch(1), cleanupStarted = new CountDownLatch(1);
        var paused = new Delegate(repository) {
            public void savePhoto(ProductPhoto photo) throws SQLException {
                published.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("Test timed out"); }
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new SQLException(ex);}
                super.savePhoto(photo);
            }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var saving=executor.submit(()->new ProductPhotoService(paused,directory).setPhoto(shop,product.id(),source));
            assertTrue(published.await(5,TimeUnit.SECONDS));
            var cleanup=executor.submit(()->{cleanupStarted.countDown();return service.cleanupOrphans(shop);});
            try {
                assertTrue(cleanupStarted.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->cleanup.get(150,TimeUnit.MILLISECONDS),"Cleanup must wait for active writer");
            } finally { release.countDown(); }
            var saved=saving.get(5,TimeUnit.SECONDS);cleanup.get(5,TimeUnit.SECONDS);
            assertTrue(Files.exists(service.resolvePath(saved.relativePath())));
            assertTrue(Files.exists(service.resolvePath(saved.thumbnailPath())));
            assertInstanceOf(PhotoLoadResult.Loaded.class,service.loadPhoto(shop,product.id()));
        }
    }

    @Test void lostSuccessfulCommitResponseIsReconciledWithoutDeletingCommittedFiles() throws Exception {
        service.setPhoto(shop,product.id(),source);
        var uncertain=new Delegate(repository) {
            public void savePhoto(ProductPhoto photo)throws SQLException {
                super.savePhoto(photo);throw new SQLException("Response lost after commit");
            }
        };
        var recovered=new ProductPhotoService(uncertain,directory).setPhoto(shop,product.id(),source);
        assertEquals(recovered,service.getPhotoMetadata(shop,product.id()).orElseThrow());
        assertInstanceOf(PhotoLoadResult.Loaded.class,service.loadPhoto(shop,product.id()));
    }

    @Test void unknownCommitOutcomeKeepsPotentiallyCommittedFilesForLaterReconciliation() throws Exception {
        var uncertain=new Delegate(repository) {
            boolean committed;
            public void savePhoto(ProductPhoto photo)throws SQLException{super.savePhoto(photo);committed=true;throw new SQLException("Lost commit response");}
            public Optional<ProductPhoto> findPhoto(UUID s,UUID p)throws SQLException{if(committed)throw new SQLException("Cannot reconnect");return super.findPhoto(s,p);}
        };
        assertThrows(SQLException.class,()->new ProductPhotoService(uncertain,directory).setPhoto(shop,product.id(),source));
        assertInstanceOf(PhotoLoadResult.Loaded.class,service.loadPhoto(shop,product.id()));
        service.cleanupOrphans(shop);
        assertInstanceOf(PhotoLoadResult.Loaded.class,service.loadPhoto(shop,product.id()));
    }

    @Test void truncatedPixelsAndDamagedThumbnailReturnRecoverableCorruptResults() throws Exception {
        var photo=service.setPhoto(shop,product.id(),source);
        Files.write(service.resolvePath(photo.relativePath()),Arrays.copyOf(Files.readAllBytes(source),33));
        assertInstanceOf(PhotoLoadResult.CorruptFile.class,service.loadPhoto(shop,product.id()));
        photo=service.setPhoto(shop,product.id(),source);
        Files.writeString(service.resolvePath(photo.thumbnailPath()),"not an image");
        assertInstanceOf(PhotoLoadResult.CorruptFile.class,service.loadPhoto(shop,product.id()));
    }

    @Test void streamUploadStopsAtTheLimitInsteadOfCopyingAnUnboundedInput() throws Exception {
        AtomicLong read=new AtomicLong();
        InputStream unlimited=new InputStream(){
            public int read(){read.incrementAndGet();return 1;}
            public int read(byte[] bytes,int offset,int length){Arrays.fill(bytes,offset,offset+length,(byte)1);read.addAndGet(length);return length;}
        };
        assertThrows(PhotoValidationException.class,()->service.setPhoto(shop,product.id(),unlimited,"../../untrusted.png"));
        assertTrue(read.get()<=PhotoValidator.MAX_FILE_SIZE_BYTES+8192);
        assertInstanceOf(PhotoLoadResult.NotAttached.class,service.loadPhoto(shop,product.id()));
    }
    /** Separate JVM fixture: holds the same on-disk lock as a second Cella process. */
    public static class LockHolder {
        public static void main(String[] args) throws Exception {
            try (var channel = java.nio.channels.FileChannel.open(Path.of(args[0]).resolve(".photo-store.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                System.out.println("LOCKED"); System.out.flush();
                System.in.read();
            }
        }
    }

    @Test void cleanupAlsoWaitsForAnotherJvmUsingTheSameDataDirectory() throws Exception {
        String java = Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),
                LockHolder.class.getName(),directory.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var signal = executor.submit(() -> new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());
                assertEquals("LOCKED",signal.get(5,TimeUnit.SECONDS));
                var cleanup = executor.submit(() -> service.cleanupOrphans(shop));
                assertThrows(TimeoutException.class,() -> cleanup.get(150,TimeUnit.MILLISECONDS));
                child.getOutputStream().write(1); child.getOutputStream().flush();
                cleanup.get(5,TimeUnit.SECONDS);
                assertTrue(child.waitFor(5,TimeUnit.SECONDS));
                assertEquals(0,child.exitValue());
            } finally {
                child.destroyForcibly();
            }
        }
    }

}

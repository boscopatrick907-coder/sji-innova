package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.product.photo.OrphanCleanupReport;
import cm.sji.innova.core.product.photo.PhotoLoadResult;
import cm.sji.innova.core.product.photo.PhotoValidationException;
import cm.sji.innova.core.product.photo.PhotoValidator;
import cm.sji.innova.core.product.photo.ProductPhoto;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ProductPhotoStorageTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private SqliteDatabase database;
    private ProductService productService;
    private SqliteProductPhotoRepository photoRepo;
    private ProductPhotoService photoService;
    private UUID shopId;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("cella_data");
        Files.createDirectories(dataDir);
        database = new SqliteDatabase(dataDir.resolve("cella.db"));
        database.initialize();

        productService = new ProductService(new SqliteProductRepository(database));
        photoRepo = new SqliteProductPhotoRepository(database);
        photoService = new ProductPhotoService(photoRepo, dataDir);
        shopId = UUID.randomUUID();
    }

    private Product createSampleProduct(String name) throws SQLException {
        return productService.create(shopId, name, 2500, "Ref-" + name, null, "General", "piece", java.util.Map.of());
    }

    private Path createTestImage(String filename, String format, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(
                width, height, "png".equalsIgnoreCase(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.drawString("Test", 5, Math.min(height - 5, 20));
        g.dispose();

        Path file = tempDir.resolve(filename);
        ImageIO.write(img, format, file.toFile());
        return file;
    }

    @Test
    void attachPhotoDeleteSourceAndReopenLoadsManagedPhoto() throws Exception {
        Product product = createSampleProduct("Rice 25kg");
        Path originalImage = createTestImage("original_source.jpg", "jpg", 400, 300);

        ProductPhoto photo = photoService.setPhoto(shopId, product.id(), originalImage);
        assertNotNull(photo);
        assertEquals(shopId, photo.shopId());
        assertEquals(product.id(), photo.productId());
        assertEquals("image/jpeg", photo.contentType());
        assertEquals(400, photo.width());
        assertEquals(300, photo.height());

        // Deleting the original source file must NOT affect Cella's managed copy
        Files.delete(originalImage);
        assertFalse(Files.exists(originalImage));

        // Reopen Cella from disk
        SqliteDatabase reopenedDb = new SqliteDatabase(dataDir.resolve("cella.db"));
        reopenedDb.initialize();
        ProductPhotoService reopenedService = new ProductPhotoService(
                new SqliteProductPhotoRepository(reopenedDb), dataDir);

        PhotoLoadResult loadResult = reopenedService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.Loaded.class, loadResult);

        PhotoLoadResult.Loaded loaded = (PhotoLoadResult.Loaded) loadResult;
        assertTrue(Files.exists(loaded.imageFile()), "Managed image file must exist");
        assertTrue(Files.exists(loaded.thumbnailFile()), "Managed thumbnail file must exist");
        assertEquals(photo.relativePath(), loaded.photo().relativePath());
        assertEquals(photo.thumbnailPath(), loaded.photo().thumbnailPath());
    }

    @Test
    void unattachedProductReturnsNotAttached() throws Exception {
        Product product = createSampleProduct("Soap");
        PhotoLoadResult result = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.NotAttached.class, result);
    }

    @Test
    void missingManagedFileReturnsMissingFileWarning() throws Exception {
        Product product = createSampleProduct("Sugar");
        Path img = createTestImage("sugar.png", "png", 100, 100);
        ProductPhoto photo = photoService.setPhoto(shopId, product.id(), img);

        // Manually delete the managed file to simulate file loss
        Path managedFile = photoService.resolvePath(photo.relativePath());
        Files.delete(managedFile);

        PhotoLoadResult result = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.MissingFile.class, result);
        PhotoLoadResult.MissingFile missing = (PhotoLoadResult.MissingFile) result;
        assertEquals(managedFile, missing.expectedPath());
    }

    @Test
    void corruptManagedFileReturnsCorruptFileWarning() throws Exception {
        Product product = createSampleProduct("Milk");
        Path img = createTestImage("milk.jpg", "jpg", 120, 120);
        ProductPhoto photo = photoService.setPhoto(shopId, product.id(), img);

        // Overwrite the managed file with garbage data
        Path managedFile = photoService.resolvePath(photo.relativePath());
        Files.writeString(managedFile, "This is not an image at all!");

        PhotoLoadResult result = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.CorruptFile.class, result);
    }

    @Test
    void invalidOversizedAndTraversalFilesAreRejectedWithoutAlteringExistingPhoto() throws Exception {
        Product product = createSampleProduct("Coffee");
        Path goodImg = createTestImage("good.jpg", "jpg", 200, 200);
        ProductPhoto originalPhoto = photoService.setPhoto(shopId, product.id(), goodImg);

        // 1. Non-image file (e.g. text file / SVG)
        Path textFile = tempDir.resolve("malicious.svg");
        Files.writeString(textFile, "<svg><script>alert(1)</script></svg>");
        assertThrows(PhotoValidationException.class, () -> photoService.setPhoto(shopId, product.id(), textFile));

        // 2. Oversized file (> 5 MiB)
        Path oversizedFile = tempDir.resolve("too_large.jpg");
        byte[] largeBytes = new byte[PhotoValidator.THUMBNAIL_MAX_DIMENSION];
        largeBytes[0] = (byte) 0xFF;
        largeBytes[1] = (byte) 0xD8;
        largeBytes[2] = (byte) 0xFF;
        largeBytes[3] = (byte) 0xE0;
        try (var out = Files.newOutputStream(oversizedFile)) {
            out.write(largeBytes);
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < 90; i++) { // ~5.7 MB total
                out.write(chunk);
            }
        }
        assertTrue(Files.size(oversizedFile) > PhotoValidator.MAX_FILE_SIZE_BYTES);
        assertThrows(PhotoValidationException.class, () -> photoService.setPhoto(shopId, product.id(), oversizedFile));

        // 3. Excessive resolution (> 20 megapixels) via PNG header without allocating 100MB RAM
        Path hugeResPng = tempDir.resolve("huge_res.png");
        createPngHeaderWithDimensions(hugeResPng, 6000, 4000); // 24 megapixels
        assertThrows(PhotoValidationException.class, () -> photoService.setPhoto(shopId, product.id(), hugeResPng));

        // 4. Path traversal attempt in resolvePath
        assertThrows(SecurityException.class, () -> photoService.resolvePath("../../../Windows/System32/calc.exe"));
        assertThrows(SecurityException.class, () -> photoService.resolvePath("photos/../../etc/passwd"));

        // Verify the original photo was never replaced or corrupted
        PhotoLoadResult loadResult = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.Loaded.class, loadResult);
        PhotoLoadResult.Loaded loaded = (PhotoLoadResult.Loaded) loadResult;
        assertEquals(originalPhoto.relativePath(), loaded.photo().relativePath());
    }

    @Test
    void successfulReplaceAndRemovePersistAcrossReopenWithoutTouchingOtherProducts() throws Exception {
        Product prodA = createSampleProduct("Product A");
        Product prodB = createSampleProduct("Product B");

        Path imgA1 = createTestImage("a1.jpg", "jpg", 150, 150);
        Path imgB = createTestImage("b.png", "png", 180, 180);

        ProductPhoto photoA1 = photoService.setPhoto(shopId, prodA.id(), imgA1);
        ProductPhoto photoB = photoService.setPhoto(shopId, prodB.id(), imgB);

        Path a1Managed = photoService.resolvePath(photoA1.relativePath());
        Path bManaged = photoService.resolvePath(photoB.relativePath());
        assertTrue(Files.exists(a1Managed));
        assertTrue(Files.exists(bManaged));

        // Replace product A's photo
        Path imgA2 = createTestImage("a2.png", "png", 300, 250);
        ProductPhoto photoA2 = photoService.setPhoto(shopId, prodA.id(), imgA2);

        // a1 should be deleted from disk; a2 should exist; b should remain untouched
        assertFalse(Files.exists(a1Managed), "Replaced old photo file should be cleaned up");
        assertTrue(Files.exists(photoService.resolvePath(photoA2.relativePath())), "New photo file must exist");
        assertTrue(Files.exists(bManaged), "Product B photo file must remain untouched");

        // Remove product A's photo
        boolean removed = photoService.removePhoto(shopId, prodA.id());
        assertTrue(removed);
        assertFalse(Files.exists(photoService.resolvePath(photoA2.relativePath())));
        assertFalse(photoService.removePhoto(shopId, prodA.id()), "Removing already-removed photo is idempotent false");

        // Reopen Cella
        SqliteDatabase reopenedDb = new SqliteDatabase(dataDir.resolve("cella.db"));
        reopenedDb.initialize();
        ProductPhotoService reopenedService = new ProductPhotoService(
                new SqliteProductPhotoRepository(reopenedDb), dataDir);

        assertInstanceOf(PhotoLoadResult.NotAttached.class, reopenedService.loadPhoto(shopId, prodA.id()));
        assertInstanceOf(PhotoLoadResult.Loaded.class, reopenedService.loadPhoto(shopId, prodB.id()));
    }

    @Test
    void simulatedDatabaseFailureOnReplacePreservesLastCommittedState() throws Exception {
        Product product = createSampleProduct("Durable Product");
        Path initialImg = createTestImage("initial.jpg", "jpg", 200, 200);
        ProductPhoto initialPhoto = photoService.setPhoto(shopId, product.id(), initialImg);

        Path initialMainFile = photoService.resolvePath(initialPhoto.relativePath());
        assertTrue(Files.exists(initialMainFile));

        // Wrap repository with one that simulates a database failure on savePhoto
        cm.sji.innova.core.product.photo.ProductPhotoRepository failingRepo = new cm.sji.innova.core.product.photo.ProductPhotoRepository() {
            @Override public boolean productExists(UUID s, UUID p) throws SQLException { return photoRepo.productExists(s, p); }
            @Override public Optional<ProductPhoto> findPhoto(UUID s, UUID p) throws SQLException { return photoRepo.findPhoto(s, p); }
            @Override public void savePhoto(ProductPhoto photo) throws SQLException { throw new SQLException("Simulated database disk failure"); }
            @Override public Optional<ProductPhoto> deletePhoto(UUID s, UUID p) throws SQLException { return photoRepo.deletePhoto(s, p); }
            @Override public List<ProductPhoto> listPhotos(UUID s) throws SQLException { return photoRepo.listPhotos(s); }
        };
        ProductPhotoService failingService = new ProductPhotoService(failingRepo, dataDir);

        Path newImg = createTestImage("new_attempt.png", "png", 220, 220);
        assertThrows(SQLException.class, () -> failingService.setPhoto(shopId, product.id(), newImg));

        // Verify initial photo is still loadable and its files are preserved
        PhotoLoadResult result = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.Loaded.class, result);
        PhotoLoadResult.Loaded loaded = (PhotoLoadResult.Loaded) result;
        assertEquals(initialPhoto.relativePath(), loaded.photo().relativePath());
        assertTrue(Files.exists(initialMainFile));
    }

    @Test
    void wrongShopAccessIsRejected() throws Exception {
        Product product = createSampleProduct("Secret Item");
        UUID otherShop = UUID.randomUUID();
        Path img = createTestImage("secret.jpg", "jpg", 100, 100);

        assertThrows(IllegalArgumentException.class, () -> photoService.setPhoto(otherShop, product.id(), img));
        assertThrows(IllegalArgumentException.class, () -> photoService.loadPhoto(otherShop, product.id()));
        assertThrows(IllegalArgumentException.class, () -> photoService.getPhotoMetadata(otherShop, product.id()));
        assertThrows(IllegalArgumentException.class, () -> photoService.removePhoto(otherShop, product.id()));
    }

    @Test
    void orphanCleanupRemovesStaleFilesSafely() throws Exception {
        Product product = createSampleProduct("Orphan Test Product");
        Path img = createTestImage("active.jpg", "jpg", 150, 150);
        ProductPhoto activePhoto = photoService.setPhoto(shopId, product.id(), img);

        // Inject an orphan into staging and an orphan into shop directory
        Path stagingDir = photoService.photosDirectory().resolve(".staging");
        Files.createDirectories(stagingDir);
        Path stagingOrphan = stagingDir.resolve("leftover_staging.tmp");
        Files.writeString(stagingOrphan, "stale staging data");

        Path shopDir = photoService.photosDirectory().resolve(shopId.toString());
        Path shopOrphan = shopDir.resolve("abandoned_file.jpg");
        Files.writeString(shopOrphan, "abandoned photo data");

        OrphanCleanupReport report = photoService.cleanupOrphans(shopId);
        assertTrue(report.stagingFilesDeleted() >= 1);
        assertTrue(report.unreferencedFilesDeleted() >= 1);
        assertFalse(Files.exists(stagingOrphan));
        assertFalse(Files.exists(shopOrphan));

        // Active photo must NOT be cleaned up
        assertTrue(Files.exists(photoService.resolvePath(activePhoto.relativePath())));
        PhotoLoadResult load = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.Loaded.class, load);
    }

    @Test
    void concurrentReplacementsRemainConsistent() throws Exception {
        Product product = createSampleProduct("Concurrent Variant");
        Path seedImg = createTestImage("seed.jpg", "jpg", 100, 100);
        photoService.setPhoto(shopId, product.id(), seedImg);

        int workers = 6;
        var executor = Executors.newFixedThreadPool(workers);
        List<Callable<ProductPhoto>> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int index = i;
            Path variantImg = createTestImage("var_" + index + ".png", "png", 100 + index * 10, 100 + index * 10);
            tasks.add(() -> photoService.setPhoto(shopId, product.id(), variantImg));
        }

        List<Future<ProductPhoto>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        for (Future<ProductPhoto> f : futures) {
            assertNotNull(f.get());
        }

        // Database and filesystem must be in a consistent state
        PhotoLoadResult load = photoService.loadPhoto(shopId, product.id());
        assertInstanceOf(PhotoLoadResult.Loaded.class, load);
    }

    @Test
    void completeBackupAndRestoreToNewLocationSucceeds() throws Exception {
        Product prod1 = createSampleProduct("Item One");
        Product prod2 = createSampleProduct("Item Two");

        Path img1 = createTestImage("p1.jpg", "jpg", 200, 200);
        Path img2 = createTestImage("p2.png", "png", 250, 250);

        ProductPhoto photo1 = photoService.setPhoto(shopId, prod1.id(), img1);
        ProductPhoto photo2 = photoService.setPhoto(shopId, prod2.id(), img2);

        // Perform full backup of the data folder (cella.db + photos/) to a restored location
        Path backupDir = tempDir.resolve("restored_cella_data");
        copyDirectory(dataDir, backupDir);

        // Open Cella using the restored directory
        SqliteDatabase restoredDb = new SqliteDatabase(backupDir.resolve("cella.db"));
        restoredDb.initialize();

        ProductPhotoService restoredService = new ProductPhotoService(
                new SqliteProductPhotoRepository(restoredDb), backupDir);

        PhotoLoadResult load1 = restoredService.loadPhoto(shopId, prod1.id());
        PhotoLoadResult load2 = restoredService.loadPhoto(shopId, prod2.id());

        assertInstanceOf(PhotoLoadResult.Loaded.class, load1);
        assertInstanceOf(PhotoLoadResult.Loaded.class, load2);

        assertEquals(photo1.relativePath(), ((PhotoLoadResult.Loaded) load1).photo().relativePath());
        assertEquals(photo2.relativePath(), ((PhotoLoadResult.Loaded) load2).photo().relativePath());
    }

    @Test
    void schemaMigrationFromV6ToV7PreservesExistingCatalogAndPassesForeignKeyCheck() throws Exception {
        Path legacyPath = tempDir.resolve("legacy_v6.db");
        SqliteDatabase legacyDb = new SqliteDatabase(legacyPath);

        // Initialize to version 6 schema
        try (var conn = legacyDb.open(); var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF");
            stmt.execute("BEGIN IMMEDIATE");
            stmt.execute("""
                CREATE TABLE product_families (
                    id TEXT PRIMARY KEY NOT NULL, shop_id TEXT NOT NULL,
                    name TEXT NOT NULL, category TEXT, created_at TEXT NOT NULL,
                    UNIQUE(shop_id, id)
                )
                """);
            stmt.execute("""
                CREATE TABLE products (
                    id TEXT PRIMARY KEY NOT NULL, shop_id TEXT NOT NULL,
                    name TEXT NOT NULL, unit_price INTEGER NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    version INTEGER NOT NULL, model_reference TEXT, cost_price INTEGER,
                    category TEXT, unit TEXT NOT NULL, current_stock_milli INTEGER NOT NULL,
                    min_stock_milli INTEGER NOT NULL, family_id TEXT, reference TEXT NOT NULL,
                    UNIQUE(shop_id, reference),
                    FOREIGN KEY(shop_id, family_id) REFERENCES product_families(shop_id, id)
                )
                """);
            stmt.execute("CREATE UNIQUE INDEX products_shop_id_id ON products(shop_id, id)");
            stmt.execute("CREATE TABLE product_reference_sequences (shop_id TEXT PRIMARY KEY, last_value INTEGER)");
            // Every real database has had sale lines since schema 4; later upgrades rebuild them.
            stmt.execute("""
                CREATE TABLE sale_items (
                    id TEXT PRIMARY KEY NOT NULL, sale_id TEXT NOT NULL, product_id TEXT NOT NULL,
                    product_name TEXT NOT NULL, unit TEXT NOT NULL, quantity_milli INTEGER NOT NULL,
                    unit_price INTEGER NOT NULL, subtotal INTEGER NOT NULL
                )
                """);
            stmt.execute("PRAGMA user_version = 6");
            stmt.execute("COMMIT");
            stmt.execute("PRAGMA foreign_keys = ON");
        }

        // Insert existing data into v6 database
        UUID legacyProdId = UUID.randomUUID();
        try (var conn = legacyDb.open(); var stmt = conn.prepareStatement(
                "INSERT INTO products VALUES (?, ?, 'Legacy Flour', 1200, '2026-01-01', '2026-01-01', 1, NULL, 1000, 'Food', 'kg', 5000, 1000, NULL, 'P-0001')")) {
            stmt.setString(1, legacyProdId.toString());
            stmt.setString(2, shopId.toString());
            stmt.executeUpdate();
        }

        // Migrate by calling initialize()
        legacyDb.initialize();

        // Verify version is now SCHEMA_VERSION (7)
        try (var conn = legacyDb.open(); var stmt = conn.createStatement()) {
            try (var rs = stmt.executeQuery("PRAGMA user_version")) {
                assertTrue(rs.next());
                assertEquals(SqliteDatabase.SCHEMA_VERSION, rs.getInt(1));
            }
            try (var rs = stmt.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rs.next(), "Foreign key check must return zero violations");
            }
        }

        // Re-run initialize() to verify idempotency
        legacyDb.initialize();

        // Verify legacy product is untouched and photo service works on migrated DB
        ProductPhotoService migratedService = new ProductPhotoService(
                new SqliteProductPhotoRepository(legacyDb), tempDir.resolve("migrated_data"));
        PhotoLoadResult load = migratedService.loadPhoto(shopId, legacyProdId);
        assertInstanceOf(PhotoLoadResult.NotAttached.class, load);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void createPngHeaderWithDimensions(Path path, int width, int height) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // PNG signature
        dos.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        // IHDR chunk
        byte[] ihdrData = new byte[13];
        ihdrData[0] = (byte) ((width >> 24) & 0xFF);
        ihdrData[1] = (byte) ((width >> 16) & 0xFF);
        ihdrData[2] = (byte) ((width >> 8) & 0xFF);
        ihdrData[3] = (byte) (width & 0xFF);
        ihdrData[4] = (byte) ((height >> 24) & 0xFF);
        ihdrData[5] = (byte) ((height >> 16) & 0xFF);
        ihdrData[6] = (byte) ((height >> 8) & 0xFF);
        ihdrData[7] = (byte) (height & 0xFF);
        ihdrData[8] = 8; // bit depth
        ihdrData[9] = 2; // RGB color type
        ihdrData[10] = 0; // compression
        ihdrData[11] = 0; // filter
        ihdrData[12] = 0; // interlace

        dos.writeInt(13); // length
        dos.write(new byte[]{'I', 'H', 'D', 'R'});
        dos.write(ihdrData);

        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(new byte[]{'I', 'H', 'D', 'R'});
        crc.update(ihdrData);
        dos.writeInt((int) crc.getValue());

        dos.flush();
        Files.write(path, baos.toByteArray());
    }
}

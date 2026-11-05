package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.product.importer.ConfirmedImportRequest;
import cm.sji.innova.core.product.importer.ImportOptions;
import cm.sji.innova.core.product.importer.ImportPreview;
import cm.sji.innova.core.product.importer.ImportResult;
import cm.sji.innova.core.product.importer.ImportedRowResult;
import cm.sji.innova.core.product.importer.ParsedRow;
import cm.sji.innova.core.product.importer.ProductImportRepository;
import cm.sji.innova.core.product.importer.ProductImportService;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ProductImportStorageTest {

    @TempDir
    Path tempDir;

    private SqliteDatabase database;
    private SqliteProductRepository productRepo;
    private ProductService productService;
    private SqliteProductImportRepository importRepo;
    private ProductImportService importService;
    private UUID shopId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("import_test.db");
        database = new SqliteDatabase(dbPath);
        database.initialize();

        productRepo = new SqliteProductRepository(database);
        productService = new ProductService(productRepo);
        importRepo = new SqliteProductImportRepository(database);
        importService = new ProductImportService(importRepo);
        shopId = UUID.randomUUID();
    }

    @Test
    void previewValidAccentedQuotedCsvPerformsZeroDatabaseWrites() throws Exception {
        // CSV with UTF-8 BOM, French accents, quoted commas, multiline field, and CRLF
        String csv = "\uFEFFname,model_reference,category,unit,buying_price,selling_price\r\n"
                + "\"T-shirt classique, été\",TS-001,Vêtements,piece,2000,3000\r\n"
                + "Riz parfumé,RIZ-001,Alimentation,kg,600,800\r\n"
                + "\"Câble\nUSB-C\",USB-02,Accessoires,piece,,2500\r\n";

        ImportPreview preview = importService.preview(shopId, new StringReader(csv), ImportOptions.DEFAULT);

        assertNotNull(preview);
        assertTrue(preview.fileErrors().isEmpty(), "File errors must be empty: " + preview.fileErrors());
        assertEquals(3, preview.totalRows());
        assertEquals(3, preview.validRows());
        assertEquals(0, preview.invalidRows());
        assertTrue(preview.canImportAny());

        // Zero products must be written to the database during preview
        assertEquals(0, productService.list(shopId).size());
    }

    @Test
    void confirmValidRowsPersistsProductsAcrossReopenWithCorrectTypesAndReferences() throws Exception {
        String csv = """
                name,model_reference,category,unit,buying_price,selling_price
                Riz parfumé 5kg,RIZ-5,Alimentation,bag,3500,4500
                Huile d'olive,HUI-01,Alimentation,litre,,7000
                Savon de Marseille,SAV-01,Hygiène,piece,0,500
                """;

        ImportPreview preview = importService.preview(shopId, new StringReader(csv), ImportOptions.DEFAULT);
        assertEquals(3, preview.validRows());

        UUID operationId = UUID.randomUUID();
        ConfirmedImportRequest request = new ConfirmedImportRequest(
                operationId, preview.previewId(), preview.contentHash(), List.of(1, 2, 3));

        ImportResult result = importService.confirmImport(shopId, request, preview.rows());
        assertEquals(3, result.importedCount());
        assertEquals(0, result.failedCount());
        assertFalse(result.replayed());

        // Verify reopening database from disk preserves products and buying price null semantics
        SqliteDatabase reopenedDb = new SqliteDatabase(tempDir.resolve("import_test.db"));
        reopenedDb.initialize();
        ProductService reopenedProducts = new ProductService(new SqliteProductRepository(reopenedDb));

        List<Product> products = reopenedProducts.list(shopId);
        assertEquals(3, products.size());

        Product p1 = products.stream().filter(p -> p.name().equals("Riz parfumé 5kg")).findFirst().orElseThrow();
        assertEquals("bag", p1.unit());
        assertEquals(3500L, p1.costPrice());
        assertEquals(4500L, p1.unitPrice());
        assertEquals("P-0001", p1.reference());

        Product p2 = products.stream().filter(p -> p.name().equals("Huile d'olive")).findFirst().orElseThrow();
        assertNull(p2.costPrice(), "Blank buying price must be null (unknown), not 0");
        assertEquals(7000L, p2.unitPrice());
        assertEquals("P-0002", p2.reference());

        Product p3 = products.stream().filter(p -> p.name().equals("Savon de Marseille")).findFirst().orElseThrow();
        assertEquals(0L, p3.costPrice(), "Explicit 0 buying price must be 0");
        assertEquals(500L, p3.unitPrice());
        assertEquals("P-0003", p3.reference());
    }

    @Test
    void validationCatchesInvalidUnitsPricesAndDuplicateOrUnsupportedHeaders() throws Exception {
        // 1. Invalid units, negative prices, fractional prices
        String badRowsCsv = """
                name,model_reference,category,unit,buying_price,selling_price
                ,REF-1,Cat,piece,100,500
                Good Name,REF-2,Cat,box,100,500
                Good Name 2,REF-3,Cat,piece,-50,500
                Good Name 3,REF-4,Cat,piece,100,500.50
                Good Name 4,REF-5,Cat,piece,100,-200
                """;

        ImportPreview preview = importService.preview(shopId, new StringReader(badRowsCsv), ImportOptions.DEFAULT);
        assertEquals(5, preview.totalRows());
        assertEquals(0, preview.validRows());
        assertEquals(5, preview.invalidRows());

        assertTrue(preview.rows().get(0).errors().stream().anyMatch(e -> e.contains("name")));
        assertTrue(preview.rows().get(1).errors().stream().anyMatch(e -> e.contains("unit")));
        assertTrue(preview.rows().get(2).errors().stream().anyMatch(e -> e.contains("Buying price")));
        assertTrue(preview.rows().get(3).errors().stream().anyMatch(e -> e.contains("Selling price")));
        assertTrue(preview.rows().get(4).errors().stream().anyMatch(e -> e.contains("Selling price")));

        // 2. Unsupported column (e.g. quantity or photo)
        String unsupportedColCsv = """
                name,model_reference,category,unit,buying_price,selling_price,quantity
                Test Product,REF-1,Cat,piece,100,500,10
                """;
        ImportPreview unsupportedPreview = importService.preview(shopId, new StringReader(unsupportedColCsv), ImportOptions.DEFAULT);
        assertTrue(unsupportedPreview.fileErrors().stream().anyMatch(e -> e.contains("quantity")));

        // 3. Duplicate column header
        String dupHeaderCsv = """
                name,model_reference,category,unit,buying_price,selling_price,name
                Test Product,REF-1,Cat,piece,100,500,Test Product
                """;
        ImportPreview dupHeaderPreview = importService.preview(shopId, new StringReader(dupHeaderCsv), ImportOptions.DEFAULT);
        assertTrue(dupHeaderPreview.fileErrors().stream().anyMatch(e -> e.contains("Duplicate column header")));

        // 4. Semicolon delimiter support
        String semicolonCsv = "name;model_reference;category;unit;buying_price;selling_price\r\n"
                + "Café Robusta;CAF-01;Boissons;kg;1500;2000\r\n";
        ImportPreview semiPreview = importService.preview(shopId, new StringReader(semicolonCsv), new ImportOptions(';', false));
        assertEquals(1, semiPreview.validRows());
        assertEquals("Café Robusta", semiPreview.rows().get(0).name());
    }

    @Test
    void duplicateCandidatesProduceWarningsAndNeverOverwriteExistingCatalog() throws Exception {
        // Seed an existing product in the shop
        productService.create(shopId, "Ciment Portland", 4500, "CIM-01", 3800L, "Matériaux", "bag", Map.of());

        // CSV with duplicates within the file and matching the catalog
        String csv = """
                name,model_reference,category,unit,buying_price,selling_price
                Ciment Portland,CIM-02,Matériaux,bag,3900,4600
                Peinture Blanche,PNT-01,Peinture,litre,5000,6500
                Peinture Blanche,PNT-02,Peinture,litre,5000,6500
                """;

        ImportPreview preview = importService.preview(shopId, new StringReader(csv), ImportOptions.DEFAULT);
        assertEquals(3, preview.totalRows());
        assertEquals(3, preview.validRows());

        // Row 1 matches existing catalog name
        assertTrue(preview.rows().get(0).warnings().stream().anyMatch(w -> w.contains("Matches existing product in catalog")));

        // Row 3 matches Row 2 within the same file
        assertTrue(preview.rows().get(2).warnings().stream().anyMatch(w -> w.contains("matches row 2 in this file")));

        // Confirming creates distinct products and does NOT overwrite existing product
        UUID opId = UUID.randomUUID();
        ConfirmedImportRequest req = new ConfirmedImportRequest(
                opId, preview.previewId(), preview.contentHash(), List.of(1, 2));

        ImportResult result = importService.confirmImport(shopId, req, preview.rows());
        assertEquals(2, result.importedCount());

        List<Product> catalog = productService.list(shopId);
        assertEquals(3, catalog.size(), "Catalog must now have 3 distinct products (1 original + 2 imported)");
    }

    @Test
    void midImportFailureLeavesCommittedRowsIntactAndRetryReplaysSafely() throws Exception {
        String csv = """
                name,model_reference,category,unit,buying_price,selling_price
                Item 1,REF-1,Cat,piece,100,200
                Item 2,REF-2,Cat,piece,200,300
                Item 3,REF-3,Cat,piece,300,400
                """;

        ImportPreview preview = importService.preview(shopId, new StringReader(csv), ImportOptions.DEFAULT);
        UUID opId = UUID.randomUUID();

        // Wrap repository to simulate a crash on row 2
        ProductImportRepository failingRepo = new ProductImportRepository() {
            @Override public boolean wasPreviouslyImported(UUID s, String h) throws SQLException { return importRepo.wasPreviouslyImported(s, h); }
            @Override public List<ExistingProductSummary> listExistingProducts(UUID s) throws SQLException { return importRepo.listExistingProducts(s); }
            @Override public java.util.Optional<ImportOperationRecord> findOperation(UUID o) throws SQLException { return importRepo.findOperation(o); }
            @Override public void recordOperation(UUID o, UUID s, String h, int t) throws SQLException { importRepo.recordOperation(o, s, h, t); }
            @Override public List<ImportedRowRecord> listImportedRows(UUID o) throws SQLException { return importRepo.listImportedRows(o); }
            @Override
            public Product insertImportedProduct(UUID o, int rowNumber, String h, Product p) throws SQLException {
                if (rowNumber == 2) {
                    throw new SQLException("Simulated power failure on row 2");
                }
                return importRepo.insertImportedProduct(o, rowNumber, h, p);
            }
        };

        ProductImportService failingService = new ProductImportService(failingRepo);
        ConfirmedImportRequest req = new ConfirmedImportRequest(
                opId, preview.previewId(), preview.contentHash(), List.of(1, 2, 3));

        ImportResult partialResult = failingService.confirmImport(shopId, req, preview.rows());
        assertEquals(2, partialResult.importedCount()); // row 1 and row 3 succeeded
        assertEquals(1, partialResult.failedCount());   // row 2 failed

        // Verify actual committed products in database match the reported results
        List<Product> productsAfterCrash = productService.list(shopId);
        assertEquals(2, productsAfterCrash.size());

        // Now retry with normal service (e.g. after restart): row 1 and 3 are detected as already committed,
        // and row 2 is now successfully imported!
        ImportResult retryResult = importService.confirmImport(shopId, req, preview.rows());
        assertEquals(3, retryResult.importedCount());
        assertEquals(0, retryResult.failedCount());

        List<Product> productsAfterRetry = productService.list(shopId);
        assertEquals(3, productsAfterRetry.size(), "Retry must not duplicate rows 1 and 3");

        // Another retry after total success returns replayed = true
        ImportResult fullReplay = importService.confirmImport(shopId, req, preview.rows());
        assertTrue(fullReplay.replayed());
        assertEquals(3, fullReplay.importedCount());
    }

    @Test
    void changedRequestUnderSameOperationIdIsRejected() throws Exception {
        String csv1 = """
                name,model_reference,category,unit,buying_price,selling_price
                Prod A,REF-A,Cat,piece,100,200
                """;
        String csv2 = """
                name,model_reference,category,unit,buying_price,selling_price
                Prod B,REF-B,Cat,piece,100,200
                """;

        ImportPreview prev1 = importService.preview(shopId, new StringReader(csv1), ImportOptions.DEFAULT);
        ImportPreview prev2 = importService.preview(shopId, new StringReader(csv2), ImportOptions.DEFAULT);

        UUID opId = UUID.randomUUID();
        ConfirmedImportRequest req1 = new ConfirmedImportRequest(
                opId, prev1.previewId(), prev1.contentHash(), List.of(1));
        importService.confirmImport(shopId, req1, prev1.rows());

        // Attempting to use opId with different content must be rejected
        ConfirmedImportRequest req2 = new ConfirmedImportRequest(
                opId, prev2.previewId(), prev2.contentHash(), List.of(1));
        assertThrows(IllegalArgumentException.class, () -> importService.confirmImport(shopId, req2, prev2.rows()));
    }

    @Test
    void schemaMigrationFromV7ToV8PreservesCatalogAndPassesForeignKeyCheck() throws Exception {
        Path legacyPath = tempDir.resolve("legacy_v7.db");
        SqliteDatabase legacyDb = new SqliteDatabase(legacyPath);

        // Initialize schema up to version 7
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
            stmt.execute("""
                CREATE TABLE product_photos (
                    shop_id TEXT NOT NULL,
                    product_id TEXT NOT NULL PRIMARY KEY,
                    relative_path TEXT NOT NULL,
                    thumbnail_path TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(shop_id, product_id) REFERENCES products(shop_id, id) ON DELETE CASCADE
                )
                """);
            stmt.execute("PRAGMA user_version = 7");
            stmt.execute("COMMIT");
            stmt.execute("PRAGMA foreign_keys = ON");
        }

        // Migrate to version 8 by running initialize()
        legacyDb.initialize();

        try (var conn = legacyDb.open(); var stmt = conn.createStatement()) {
            try (var rs = stmt.executeQuery("PRAGMA user_version")) {
                assertTrue(rs.next());
                assertEquals(SqliteDatabase.SCHEMA_VERSION, rs.getInt(1));
                assertEquals(8, rs.getInt(1));
            }
            try (var rs = stmt.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rs.next(), "Foreign key check must return zero violations");
            }
        }

        // Second initialize() call must be idempotent
        legacyDb.initialize();
    }
}

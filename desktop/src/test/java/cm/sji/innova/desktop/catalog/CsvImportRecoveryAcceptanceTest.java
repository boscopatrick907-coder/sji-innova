package cm.sji.innova.desktop.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.product.importer.ImportResult;
import cm.sji.innova.core.product.importer.ProductImportRepository;
import cm.sji.innova.core.product.importer.ProductImportService;
import cm.sji.innova.desktop.UndeletableFile;
import cm.sji.innova.desktop.catalog.importing.CsvImportPanel;
import cm.sji.innova.desktop.catalog.importing.PendingImportStore;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.Component;
import java.awt.Container;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-02 acceptance for the CSV import: the cases {@link ImportAndPhotoControlsTest} does not already
 * cover. The database and the import's pending file are in separate folders, so the pending file can be
 * protected from deletion without touching SQLite.
 */
class CsvImportRecoveryAcceptanceTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final ResourceBundle english = Messages.load(Locale.ENGLISH).bundle();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger inserts = new AtomicInteger();
    private volatile Callable<?> afterInsert;
    private ProductService products;
    private SqliteProductImportRepository real;
    private Path data;
    private Path pending;
    private Path csv;

    @BeforeEach
    void openEmptyShop() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("database").resolve("cella.db"));
        database.initialize();
        products = new ProductService(new SqliteProductRepository(database));
        real = new SqliteProductImportRepository(database);
        data = directory.resolve("shop");
        pending = data.resolve("pending-csv.properties");
        csv = directory.resolve("catalog.csv");
        Files.writeString(csv, "name,unit,selling_price\nHuile,litre,1500\n");
    }

    @Test
    void aConfirmedImportWhosePendingFileCannotBeRemovedIsNotSentAtOpeningAndAddsNothingTwice() throws Exception {
        UndeletableFile.assumeSupported(data);
        AtomicReference<UndeletableFile> protection = new AtomicReference<>();
        afterInsert = () -> { protection.set(new UndeletableFile(pending)); return null; };

        CsvImportPanel first = open();
        edt(() -> first.previewFile(csv)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        CompletableFuture<ImportResult> attempt = edt(first::submit).toCompletableFuture();
        try {
            assertThrows(ExecutionException.class, () -> attempt.get(10, TimeUnit.SECONDS),
                    "The request could not be removed, so the import is not reported as finished");
            assertTrue(Files.exists(pending));
        } finally {
            if (protection.get() != null) protection.get().close();
        }
        assertEquals(1, products.list(shop).size(), "The database saved the product once");
        assertEquals(english.getString("csv.retryHint"), edt(() -> feedback(first)));
        edt(() -> { first.leave(); return null; });

        // Restart: the saved request comes back, and opening sends nothing.
        int attemptsBefore = attempts.get();
        CsvImportPanel restarted = open();
        assertEquals(attemptsBefore, attempts.get(), "Opening the import sends nothing");
        assertEquals(1, products.list(shop).size());
        edt(() -> {
            assertEquals(1, restarted.previewTable().getRowCount());
            assertFalse(restarted.previewTable().isCellEditable(0, 0), "The saved selection cannot be changed");
            assertEquals(english.getString("csv.resume"), feedback(restarted));
            return null;
        });

        ImportResult retried = edt(restarted::submit).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(0, retried.failedCount());
        assertEquals(1, inserts.get(), "The retry was answered from the saved import");
        assertEquals(List.of("Huile"), products.list(shop).stream().map(Product::name).toList());
        assertFalse(Files.exists(pending));
        edt(() -> { restarted.leave(); return null; });
    }

    @Test
    void anImportWhosePendingFileCannotBeWrittenIsNeverSent() throws Exception {
        CsvImportPanel panel = open();
        edt(() -> panel.previewFile(csv)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        // A folder now holds the request's name, so the request cannot be written there.
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("keep.txt"), "taken");

        CompletableFuture<ImportResult> attempt = edt(panel::submit).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> attempt.get(10, TimeUnit.SECONDS));
        assertEquals(0, attempts.get(), "The import was never attempted");
        assertTrue(products.list(shop).isEmpty());
        assertEquals(english.getString("csv.retryHint"), edt(() -> feedback(panel)));

        Files.delete(pending.resolve("keep.txt"));
        Files.delete(pending);
        ImportResult retried = edt(panel::submit).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, retried.importedCount());
        assertEquals(1, attempts.get());
        assertEquals(1, products.list(shop).size());
        assertFalse(Files.exists(pending));
        try (var leftovers = Files.list(data)) {
            assertTrue(leftovers.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")), "No temporary file is left behind");
        }
        edt(() -> { panel.leave(); return null; });
    }

    /** Started as a separate Java process: keeps an import open on the folder until it reads a byte. */
    public static class ImportHolder {
        public static void main(String[] args) throws Exception {
            try (var store = new PendingImportStore(Path.of(args[0]), UUID.fromString(args[1]))) {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test
    void whileAnotherProcessHasAnImportOpenThisWindowCannotImportAndWritesNothing() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ImportHolder.class.getName(), data.toString(), shop.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var signal = executor.submit(() -> new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());
            assertEquals("LOCKED", signal.get(30, TimeUnit.SECONDS));

            CsvImportPanel blocked = edt(() -> new CsvImportPanel(new ProductImportService(counted()), shop, data,
                    english, () -> { }, () -> { }, busy -> { }));
            assertThrows(ExecutionException.class, () -> blocked.ready().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(english.getString("csv.recoveryError"), edt(() -> feedback(blocked)));
            assertThrows(ExecutionException.class,
                    () -> edt(() -> blocked.previewFile(csv)).toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "Nothing can be imported from this window");
            assertFalse(Files.exists(pending));
            assertTrue(products.list(shop).isEmpty());

            child.getOutputStream().write(1);
            child.getOutputStream().flush();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }

        CsvImportPanel after = open();
        edt(() -> after.previewFile(csv)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(1, edt(after::submit).toCompletableFuture().get(10, TimeUnit.SECONDS).importedCount());
        assertEquals(1, products.list(shop).size());
        assertFalse(Files.exists(pending));
        edt(() -> { after.leave(); return null; });
    }

    // ----- Helpers -----

    private CsvImportPanel open() throws Exception {
        CsvImportPanel panel = edt(() -> new CsvImportPanel(new ProductImportService(counted()), shop, data,
                english, () -> { }, () -> { }, busy -> { }));
        panel.ready().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return panel;
    }

    /** The real import repository, counting inserts and running the test's step after the first one. */
    private ProductImportRepository counted() {
        return new ProductImportRepository() {
            public boolean wasPreviouslyImported(UUID s, String h) throws SQLException { return real.wasPreviouslyImported(s, h); }
            public List<ExistingProductSummary> listExistingProducts(UUID s) throws SQLException { return real.listExistingProducts(s); }
            public Optional<ImportOperationRecord> findOperation(UUID id) throws SQLException { return real.findOperation(id); }
            // Every confirmation starts here, even one answered from an import already saved.
            public void recordOperation(UUID id, UUID s, String h, int total) throws SQLException {
                attempts.incrementAndGet();
                real.recordOperation(id, s, h, total);
            }
            public List<ImportedRowRecord> listImportedRows(UUID id) throws SQLException { return real.listImportedRows(id); }
            public Product insertImportedProduct(UUID id, int row, String hash, Product product) throws SQLException {
                Product saved = real.insertImportedProduct(id, row, hash, product);
                inserts.incrementAndGet();
                Callable<?> step = afterInsert;
                if (step != null) {
                    afterInsert = null;
                    try {
                        step.call();
                    } catch (Exception failure) {
                        throw new SQLException("The test's after-insert step failed", failure);
                    }
                }
                return saved;
            }
        };
    }

    private static String feedback(Component panel) {
        return ((JTextArea) named(panel, "csvFeedback")).getText();
    }

    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = named(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }
}

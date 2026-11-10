package cm.sji.innova.desktop.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.importer.ImportResult;
import cm.sji.innova.core.product.importer.ProductImportService;
import cm.sji.innova.desktop.ForcedClose;
import cm.sji.innova.desktop.catalog.importing.CsvImportPanel;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-02c forced-close drills for the CSV import.
 *
 * <p>A separate Java process opens the data folder the way Cella does at start-up and imports a file of
 * three products. It is killed between two rows (the first committed, the second not yet), or just
 * after the last row was committed. The test then opens the import again, as restarting Cella does, and
 * checks that nothing was sent by itself and that retrying adds each product exactly once. Disposable
 * data only.
 */
class CsvImportForcedCloseDrillTest {
    private static final ResourceBundle ENGLISH = Messages.load(Locale.ENGLISH).bundle();
    private static final List<String> ALL_THREE = List.of("Huile", "Savon", "Sucre");

    @TempDir Path directory;

    private final List<CsvImportPanel> screens = new ArrayList<>();
    private LocalAppData location;
    private AppContext context;
    private Path csv;

    @BeforeEach
    void anEmptyShopAndAFileOfThreeProducts() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        context = AppContextLoader.load(location);
        csv = directory.resolve("catalog.csv");
        Files.writeString(csv, "name,unit,selling_price\nHuile,litre,1500\nSucre,kg,800\nSavon,piece,250\n");
    }

    /** The other process: Cella's import screen, wired as the Products screen wires it, imports the file. */
    public static class ImportScreen {
        public static void main(String[] args) throws Exception {
            ForcedClose.watchTheDatabase();
            AppContext context = AppContextLoader.load(new LocalAppData(Path.of(args[0])));
            CsvImportPanel panel = edt(() -> screen(context));
            panel.ready().toCompletableFuture().get(60, TimeUnit.SECONDS);
            edt(() -> panel.previewFile(Path.of(args[1]))).toCompletableFuture().get(60, TimeUnit.SECONDS);

            ForcedClose.stopAfter(args[2], Integer.parseInt(args[3]));
            var result = edt(panel::submit).toCompletableFuture();
            try {
                result.get(60, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Whatever happened, the import ended without reaching its moment.
            }
            ForcedClose.finishedWithoutStopping();
        }
    }

    /** The import screen as the Products screen builds it: real services and the data folder. */
    private static CsvImportPanel screen(AppContext context) {
        var imports = new ProductImportService(new SqliteProductImportRepository(
                new SqliteDatabase(context.location().databaseFile())));
        return new CsvImportPanel(imports, context.shopId(), context.location().directory(), ENGLISH,
                () -> { }, () -> { }, saving -> { });
    }

    @Test
    void anImportKilledBetweenTwoRowsKeepsTheFirstIsNotSentByTheRestartAndAddsEachProductOnce() throws Exception {
        // Row 1 is committed; row 2's product and its import record are written, not committed.
        ForcedClose.runAndKill(ImportScreen.class, location.directory(), csv.toString(), "INSERT INTO product_import_rows", "2");

        assertTrue(Files.exists(pendingFile()), "The import was saved on disk before the database was called");
        assertEquals(List.of("Huile"), productNames(), "Only the committed row is there; row 2 was rolled back");

        CsvImportPanel restarted = restartWithTheImportOffered(List.of("Huile"));
        ImportResult retried = edt(restarted::submit).toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(0, retried.failedCount());
        assertEquals(ALL_THREE, productNames(), "Each product exactly once");
        assertFalse(Files.exists(pendingFile()));
    }

    @Test
    void anImportKilledJustAfterItsLastRowWasCommittedIsNotSentByTheRestartAndAddsNothingTwice() throws Exception {
        // The operation is recorded, then each of the three rows is committed: the fourth commit is the last.
        ForcedClose.runAndKill(ImportScreen.class, location.directory(), csv.toString(), "COMMIT", "4");

        assertTrue(Files.exists(pendingFile()), "The import was saved on disk before the database was called");
        assertEquals(ALL_THREE, productNames(), "The database committed all three before the kill");

        CsvImportPanel restarted = restartWithTheImportOffered(ALL_THREE);
        ImportResult retried = edt(restarted::submit).toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(0, retried.failedCount());
        assertEquals(ALL_THREE, productNames(), "The retry was answered from the saved import");
        assertFalse(Files.exists(pendingFile()));
    }

    /** Starts Cella again on the same folder and opens the import, which must offer the saved request. */
    private CsvImportPanel restartWithTheImportOffered(List<String> productsBefore) throws Exception {
        byte[] before = Files.readAllBytes(pendingFile());
        AppContext restarted = AppContextLoader.load(location);
        CsvImportPanel panel = edt(() -> screen(restarted));
        screens.add(panel);
        panel.ready().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(productsBefore, productNames(), "Opening the import sends nothing");
        assertArrayEquals(before, Files.readAllBytes(pendingFile()), "The saved import is kept exactly");
        edt(() -> {
            assertEquals(3, panel.previewTable().getRowCount(), "The saved rows are shown");
            assertFalse(panel.previewTable().isCellEditable(0, 0), "The saved selection cannot be changed");
            assertEquals(ENGLISH.getString("csv.resume"), ((JTextArea) named(panel, "csvFeedback")).getText());
            return null;
        });
        return panel;
    }

    @AfterEach
    void closeEveryImport() throws Exception {
        for (CsvImportPanel panel : screens) edt(() -> { panel.leave(); return null; });
    }

    private Path pendingFile() { return location.directory().resolve("pending-csv.properties"); }

    private List<String> productNames() throws Exception {
        return context.products().list(context.shopId()).stream().map(Product::name).sorted().toList();
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

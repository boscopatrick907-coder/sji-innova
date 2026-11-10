package cm.sji.innova.desktop.stock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.stock.StockLevel;
import cm.sji.innova.core.stock.StockMovement;
import cm.sji.innova.core.stock.StockRepository;
import cm.sji.innova.core.stock.StockResult;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.ForcedClose;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-02c forced-close drills for stock receipts and corrections.
 *
 * <p>A separate Java process opens the data folder the way Cella does at start-up, starts a receipt or
 * a correction on the Stock screen, and is killed at the worst moment: when the database has written
 * everything but not committed it yet, or just after it committed. The test then opens the same folder
 * again, as restarting Cella does, and checks that nothing was sent by itself, that the original request
 * is offered unchanged, and that retrying it counts exactly once. Disposable data only.
 */
class StockForcedCloseDrillTest {
    private static final ResourceBundle ENGLISH = Messages.load(Locale.ENGLISH).bundle();
    /** The last statement before the commit: the movement and the new balance are written, not committed. */
    private static final String WRITTEN = "UPDATE products SET current_stock_milli";
    private static final String COMMITTED = "COMMIT";

    @TempDir Path directory;

    private final Map<StockPanel, Events> events = new IdentityHashMap<>();
    private final Counter counter = new Counter();
    private LocalAppData location;
    private AppContext context;
    private Product rice;

    @BeforeEach
    void aShopWithRice() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        context = AppContextLoader.load(location);
        rice = context.products().create(context.shopId(), "Rice", 1_000);
    }

    /** The other process: Cella's Stock screen, wired as the shell wires it, starts one operation. */
    public static class StockScreen {
        public static void main(String[] args) throws Exception {
            ForcedClose.watchTheDatabase();
            AppContext context = AppContextLoader.load(new LocalAppData(Path.of(args[0])));
            Semaphore loaded = new Semaphore(0);
            Semaphore finished = new Semaphore(0);
            StockPanel panel = edt(() -> screen(context, context.stock(), loaded::release, finished::release, () -> { }));
            if (!loaded.tryAcquire(60, TimeUnit.SECONDS)) throw new IllegalStateException("The Stock screen never loaded");

            ForcedClose.stopAfter(args[2], Integer.parseInt(args[3]));
            edt(() -> {
                if ("receipt".equals(args[1])) {
                    panel.quantityField().setText("7");
                    panel.reasonField().setText("Delivery 12");
                    panel.receiveButton().doClick();
                } else {
                    ((JComboBox<?>) named(panel, "stockAdjustmentDirection")).setSelectedIndex(0);   // add stock
                    field(panel, "stockAdjustmentQuantity").setText("3");
                    field(panel, "stockAdjustmentReason").setText("Found in the back room");
                    button(panel, "Apply Adjustment").doClick();
                }
                return null;
            });
            finished.tryAcquire(60, TimeUnit.SECONDS);
            ForcedClose.finishedWithoutStopping();
        }
    }

    /** The Stock screen as {@code ShellPanel} builds it: real services and the real request files. */
    private static StockPanel screen(AppContext context, StockService stock, Runnable onLoad, Runnable onOperation,
                                     Runnable onHistory) {
        FlatLightLaf.setup();
        CatalogStyle.installDefaults();
        Path folder = context.location().directory();
        StockPanel panel = new StockPanel(stock, context.products(), context.shopId(), context.deviceId(), ENGLISH,
                new FilePendingReceiptStore(folder.resolve("pending-stock-receipt.properties")),
                new FilePendingStockAdjustmentStore(folder.resolve("pending-stock-adjustment.properties")),
                onLoad, onOperation);
        panel.setOnHistoryWorkerDone(onHistory);
        return panel;
    }

    // ----- Receipts -----

    @Test
    void aReceiptKilledJustBeforeTheDatabaseCommittedIsKeptNotSentByTheRestartAndCountsOnce() throws Exception {
        ForcedClose.runAndKill(StockScreen.class, location.directory(), "receipt", WRITTEN, "1");

        UUID operation = savedReceipt();
        assertEquals(0, balance(), "The uncommitted receipt was rolled back, movement and balance together");
        assertEquals(List.of(), movementIds());

        StockPanel restarted = restartWithTheReceiptOffered();
        assertEquals(0, balance());

        retry(restarted, "Retry Original Receipt");

        assertEquals(7_000, balance());
        assertEquals(List.of(operation), movementIds(), "One movement, with the original UUID");
        assertFalse(Files.exists(receiptFile()));
        assertEquals(1, counter.records.get());
    }

    @Test
    void aReceiptKilledJustAfterTheDatabaseCommittedItIsKeptNotSentByTheRestartAndCountsOnce() throws Exception {
        ForcedClose.runAndKill(StockScreen.class, location.directory(), "receipt", COMMITTED, "1");

        UUID operation = savedReceipt();
        assertEquals(7_000, balance(), "The database committed it before the kill");
        assertEquals(List.of(operation), movementIds());

        StockPanel restarted = restartWithTheReceiptOffered();
        assertEquals(7_000, balance());

        retry(restarted, "Retry Original Receipt");

        assertEquals(7_000, balance(), "The retry was recognised and added nothing");
        assertEquals(List.of(operation), movementIds());
        assertFalse(Files.exists(receiptFile()));
        assertEquals("Receipt confirmed (retried operation).", edt(() -> restarted.statusLabel().getText()));
    }

    private UUID savedReceipt() throws Exception {
        assertTrue(Files.exists(receiptFile()), "The receipt was saved on disk before the database was called");
        return new FilePendingReceiptStore(receiptFile()).load().orElseThrow().operationId();
    }

    private StockPanel restartWithTheReceiptOffered() throws Exception {
        byte[] before = Files.readAllBytes(receiptFile());
        StockPanel restarted = restart();
        assertEquals(0, counter.records.get(), "Opening the screen sends nothing");
        assertArrayEquals(before, Files.readAllBytes(receiptFile()), "The saved receipt is kept exactly");
        edt(() -> {
            assertTrue(restarted.hasPendingReceipt());
            assertEquals("7", restarted.quantityField().getText());
            assertFalse(restarted.quantityField().isEnabled(), "The original details cannot be changed");
            return null;
        });
        return restarted;
    }

    // ----- Corrections -----

    @Test
    void aCorrectionKilledJustBeforeTheDatabaseCommittedIsKeptNotSentByTheRestartAndCountsOnce() throws Exception {
        context.stock().receive(UUID.randomUUID(), context.shopId(), rice.id(), context.deviceId(), 10_000, "Opening count");
        ForcedClose.runAndKill(StockScreen.class, location.directory(), "correction", WRITTEN, "1");

        UUID operation = savedCorrection();
        assertEquals(10_000, balance(), "The uncommitted correction was rolled back, movement and balance together");
        assertEquals(1, movementIds().size());

        StockPanel restarted = restartWithTheCorrectionOffered();
        assertEquals(10_000, balance());

        retry(restarted, "Retry Original Adjustment");

        assertEquals(13_000, balance());
        assertEquals(2, movementIds().size());
        assertTrue(movementIds().contains(operation), "The retry used the original UUID");
        assertFalse(Files.exists(correctionFile()));
        assertEquals(1, counter.records.get());
    }

    @Test
    void aCorrectionKilledJustAfterTheDatabaseCommittedItIsKeptNotSentByTheRestartAndCountsOnce() throws Exception {
        context.stock().receive(UUID.randomUUID(), context.shopId(), rice.id(), context.deviceId(), 10_000, "Opening count");
        ForcedClose.runAndKill(StockScreen.class, location.directory(), "correction", COMMITTED, "1");

        UUID operation = savedCorrection();
        assertEquals(13_000, balance(), "The database committed it before the kill");
        assertTrue(movementIds().contains(operation));

        StockPanel restarted = restartWithTheCorrectionOffered();
        assertEquals(13_000, balance());

        retry(restarted, "Retry Original Adjustment");

        assertEquals(13_000, balance(), "The retry was recognised and added nothing");
        assertEquals(2, movementIds().size());
        assertTrue(movementIds().contains(operation));
        assertFalse(Files.exists(correctionFile()));
    }

    private UUID savedCorrection() throws Exception {
        assertTrue(Files.exists(correctionFile()), "The correction was saved on disk before the database was called");
        return new FilePendingStockAdjustmentStore(correctionFile()).load().orElseThrow().operationId();
    }

    private StockPanel restartWithTheCorrectionOffered() throws Exception {
        byte[] before = Files.readAllBytes(correctionFile());
        StockPanel restarted = restart();
        assertEquals(0, counter.records.get(), "Opening the screen sends nothing");
        assertArrayEquals(before, Files.readAllBytes(correctionFile()), "The saved correction is kept exactly");
        edt(() -> {
            assertEquals("3", field(restarted, "stockAdjustmentQuantity").getText());
            assertFalse(field(restarted, "stockAdjustmentQuantity").isEnabled(), "The original details cannot be changed");
            assertFalse(restarted.receiveButton().isEnabled(), "No new receipt while a correction is pending");
            return null;
        });
        return restarted;
    }

    // ----- Helpers -----

    private Path receiptFile() { return location.directory().resolve("pending-stock-receipt.properties"); }

    private Path correctionFile() { return location.directory().resolve("pending-stock-adjustment.properties"); }

    private long balance() throws SQLException { return context.stock().current(context.shopId(), rice.id()).quantityMilli(); }

    private List<UUID> movementIds() throws SQLException {
        return context.stock().history(context.shopId(), rice.id()).stream().map(StockMovement::id).toList();
    }

    /**
     * Starts Cella again on the same folder and opens the Stock screen. Its stock repository is the real
     * one with a counter around it, so the test knows what was sent and when every call has returned.
     */
    private StockPanel restart() throws Exception {
        AppContext restarted = AppContextLoader.load(location);
        counter.real = new SqliteStockRepository(new SqliteDatabase(location.databaseFile()));
        Events seen = new Events();
        StockPanel panel = edt(() -> screen(restarted, new StockService(counter),
                () -> { seen.all.incrementAndGet(); seen.loads.release(); },
                () -> { seen.all.incrementAndGet(); seen.operations.release(); },
                seen.all::incrementAndGet));
        events.put(panel, seen);
        take(seen.loads, "Reading products and stock");
        settle(panel);
        return panel;
    }

    private void retry(StockPanel panel, String label) throws Exception {
        edt(() -> {
            JButton retry = button(panel, label);
            assertNotNull(retry, label);
            assertTrue(retry.isEnabled());
            retry.doClick();
            return null;
        });
        take(events.get(panel).operations, "The retry");
        settle(panel);
    }

    /** Waits until the screen has nothing left running: no event for a while and no database call open. */
    private void settle(StockPanel panel) throws Exception {
        Events seen = events.get(panel);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int quietChecks = 0;
        while (quietChecks < 3) {
            assertTrue(System.nanoTime() < deadline, "The Stock screen never became quiet");
            int before = seen.all.get();
            Thread.sleep(100);
            edt(() -> null);
            quietChecks = seen.all.get() == before && counter.running.get() == 0 ? quietChecks + 1 : 0;
        }
        seen.loads.drainPermits();
        seen.operations.drainPermits();
    }

    @AfterEach
    void letEveryScreenFinish() throws Exception {
        for (StockPanel panel : events.keySet()) settle(panel);
    }

    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }

    private static void take(Semaphore semaphore, String what) throws InterruptedException {
        assertTrue(semaphore.tryAcquire(20, TimeUnit.SECONDS), what + " did not finish");
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

    private static JTextField field(Component root, String name) { return (JTextField) named(root, name); }

    private static JButton button(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) return button;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = button(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The screen's background work, counted as it finishes. */
    private static final class Events {
        final AtomicInteger all = new AtomicInteger();
        final Semaphore loads = new Semaphore(0);
        final Semaphore operations = new Semaphore(0);
    }

    /** The real stock repository, counting operations sent and calls not yet returned. */
    private static final class Counter implements StockRepository {
        volatile StockRepository real;
        final AtomicInteger records = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();

        @Override public StockResult record(StockMovement movement) throws SQLException {
            records.incrementAndGet();
            return counted(() -> real.record(movement));
        }
        @Override public StockLevel current(UUID shopId, UUID productId) throws SQLException {
            return counted(() -> real.current(shopId, productId));
        }
        @Override public List<StockLevel> list(UUID shopId) throws SQLException { return counted(() -> real.list(shopId)); }
        @Override public List<StockMovement> history(UUID shopId, UUID productId) throws SQLException {
            return counted(() -> real.history(shopId, productId));
        }
        @Override public StockLevel setMinimum(UUID shopId, UUID productId, long minimumMilli) throws SQLException {
            return counted(() -> real.setMinimum(shopId, productId, minimumMilli));
        }

        private interface Call<T> { T run() throws SQLException; }

        private <T> T counted(Call<T> call) throws SQLException {
            running.incrementAndGet();
            try {
                return call.run();
            } finally {
                running.decrementAndGet();
            }
        }
    }
}

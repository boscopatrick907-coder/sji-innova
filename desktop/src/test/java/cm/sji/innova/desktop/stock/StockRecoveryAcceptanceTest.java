package cm.sji.innova.desktop.stock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.stock.StockLevel;
import cm.sji.innova.core.stock.StockMovement;
import cm.sji.innova.core.stock.StockRepository;
import cm.sji.innova.core.stock.StockResult;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.shell.Messages;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Container;
import cm.sji.innova.desktop.TestChildProcess;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * OFF-02 acceptance: stock receipts and corrections when something fails at the worst moment.
 *
 * <p>Real SQLite and real pending files in a temporary folder; failures are injected around them. Each
 * scenario checks the starting balance, the operation UUID, the final balance, the number of movements
 * and what the pending file holds. Corrections only add stock here: removing stock asks for confirmation
 * in a dialog, which a test cannot answer.
 */
class StockRecoveryAcceptanceTest {
    @TempDir Path directory;

    private final UUID shop = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private final ResourceBundle english = Messages.load(Locale.ENGLISH).bundle();
    private final ScriptedRepository repository = new ScriptedRepository();
    private final Map<StockPanel, Events> events = new IdentityHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private ProductService products;
    private StockService stock;
    private FlakyReceipts receipts;
    private FlakyCorrections corrections;
    private Product rice;

    @BeforeEach
    void openShopWithRice() throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("shop.db"));
        database.initialize();
        repository.real = new SqliteStockRepository(database);
        products = new ProductService(new SqliteProductRepository(database));
        stock = new StockService(new SqliteStockRepository(database));
        receipts = new FlakyReceipts(directory.resolve("pending-stock-receipt.properties"));
        corrections = new FlakyCorrections(directory.resolve("pending-stock-adjustment.properties"));
        rice = products.create(shop, "Rice", 1_000);
    }

    // ----- Database saved it, but the answer was lost -----

    @Test
    void aReceiptSavedByTheDatabaseWhoseAnswerWasLostIsRecoveredAfterARestartAndCountsOnce() throws Exception {
        StockPanel first = open(true);
        assertEquals(0, balance());

        repository.next.set(Next.SAVE_THEN_LOSE_THE_ANSWER);
        receive(first, "7", "Delivery 12", false);

        UUID operation = receipts.load().orElseThrow().operationId();
        assertEquals(7_000, balance(), "The database saved it once");
        assertEquals(List.of(operation), movementIds());
        assertTrue(edt(first::hasPendingReceipt), "The screen keeps the original request");

        StockPanel restarted = open(true);
        assertEquals(1, repository.attempts.get(), "Opening the screen sends nothing");
        assertEquals(7_000, balance());
        edt(() -> {
            assertTrue(restarted.hasPendingReceipt());
            assertEquals("7", restarted.quantityField().getText());
            assertFalse(restarted.quantityField().isEnabled(), "The original details cannot be changed");
            return null;
        });

        retryReceipt(restarted, true);

        assertEquals(7_000, balance());
        assertEquals(List.of(operation), movementIds(), "The retry reused the original UUID and added nothing");
        assertTrue(receipts.load().isEmpty());
        assertEquals("Receipt confirmed (retried operation).", edt(() -> restarted.statusLabel().getText()));
    }

    @Test
    void aCorrectionSavedByTheDatabaseWhoseAnswerWasLostIsRecoveredAfterARestartAndCountsOnce() throws Exception {
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Opening count");
        StockPanel first = open(true);

        repository.next.set(Next.SAVE_THEN_LOSE_THE_ANSWER);
        correct(first, "3", "Found in the back room", false);

        UUID operation = corrections.load().orElseThrow().operationId();
        assertEquals(13_000, balance(), "The database saved it once");
        assertEquals(2, movementIds().size());
        assertTrue(movementIds().contains(operation));
        assertTrue(edt(() -> button(first, "Retry Original Adjustment").isEnabled()));

        StockPanel restarted = open(true);
        assertEquals(1, repository.attempts.get(), "Opening the screen sends nothing");
        assertEquals(13_000, balance());
        edt(() -> {
            assertEquals("3", field(restarted, "stockAdjustmentQuantity").getText());
            assertFalse(field(restarted, "stockAdjustmentQuantity").isEnabled(), "The original details cannot be changed");
            assertFalse(restarted.receiveButton().isEnabled(), "No new receipt while a correction is pending");
            return null;
        });

        retryCorrection(restarted, true);

        assertEquals(13_000, balance());
        assertEquals(2, movementIds().size(), "The retry reused the original UUID and added nothing");
        assertTrue(movementIds().contains(operation));
        assertTrue(corrections.load().isEmpty());
    }

    // ----- Failure before the database saves -----

    @Test
    void aCorrectionThatFailsBeforeTheDatabaseSavesKeepsItsRequestAndCountsOnceWhenRetried() throws Exception {
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Opening count");
        StockPanel panel = open(true);

        repository.next.set(Next.FAIL_BEFORE_SAVING);
        correct(panel, "3", "Found in the back room", false);

        UUID operation = corrections.load().orElseThrow().operationId();
        assertEquals(10_000, balance(), "Nothing was saved");
        assertEquals(1, movementIds().size());

        retryCorrection(panel, true);

        assertEquals(13_000, balance());
        assertEquals(2, movementIds().size());
        assertTrue(movementIds().contains(operation), "The retry used the original UUID");
        assertTrue(corrections.load().isEmpty());
    }

    // ----- The pending file cannot be written -----

    @Test
    void aReceiptWhosePendingFileCannotBeWrittenIsNeverSent() throws Exception {
        StockPanel panel = open(true);

        receipts.failSave = true;
        receive(panel, "7", "Delivery 12", false);

        assertEquals(0, repository.attempts.get(), "Nothing reached the database");
        assertEquals(0, balance());
        assertFalse(Files.exists(receipts.file()));

        receipts.failSave = false;
        retryReceipt(panel, true);

        assertEquals(1, repository.attempts.get());
        assertEquals(7_000, balance());
        assertEquals(1, movementIds().size());
        assertFalse(Files.exists(receipts.file()));
    }

    @Test
    void aCorrectionWhosePendingFileCannotBeWrittenIsNeverSent() throws Exception {
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Opening count");
        StockPanel panel = open(true);

        corrections.failSave = true;
        correct(panel, "3", "Found in the back room", false);

        assertEquals(0, repository.attempts.get(), "Nothing reached the database");
        assertEquals(10_000, balance());
        assertFalse(Files.exists(corrections.file()));

        corrections.failSave = false;
        retryCorrection(panel, true);

        assertEquals(1, repository.attempts.get());
        assertEquals(13_000, balance());
        assertEquals(2, movementIds().size());
        assertFalse(Files.exists(corrections.file()));
    }

    // ----- Confirmed, but the pending file cannot be removed -----

    @Test
    void aConfirmedReceiptWhosePendingFileCannotBeRemovedStaysRecoverableAndCountsOnce() throws Exception {
        StockPanel panel = open(true);

        receipts.failClear = true;
        receive(panel, "7", "Delivery 12", true);

        UUID operation = receipts.load().orElseThrow().operationId();
        assertEquals(7_000, balance());
        assertEquals(List.of(operation), movementIds());
        assertEquals("Receipt was confirmed, but recovery cleanup is pending. Retry the same receipt.",
                edt(() -> panel.statusLabel().getText()));

        receipts.failClear = false;
        retryReceipt(panel, true);

        assertEquals(7_000, balance());
        assertEquals(List.of(operation), movementIds());
        assertTrue(receipts.load().isEmpty());
    }

    @Test
    void aConfirmedCorrectionWhosePendingFileCannotBeRemovedStaysRecoverableAndCountsOnce() throws Exception {
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Opening count");
        StockPanel panel = open(true);

        corrections.failClear = true;
        correct(panel, "3", "Found in the back room", false);

        UUID operation = corrections.load().orElseThrow().operationId();
        assertEquals(13_000, balance(), "The database confirmed it");
        assertEquals(2, movementIds().size());
        assertEquals("Adjustment was confirmed, but recovery cleanup is pending. Retry the same adjustment.",
                edt(() -> panel.statusLabel().getText()), "Said like a receipt's, not as a failure");

        corrections.failClear = false;
        retryCorrection(panel, true);

        assertEquals(13_000, balance());
        assertEquals(2, movementIds().size());
        assertTrue(movementIds().contains(operation));
        assertTrue(corrections.load().isEmpty());
    }

    // ----- Saved requests that cannot be used: nothing is sent and the file is kept -----

    @Test
    void savedRequestsFromAnotherShopOrDeviceBlockTheScreenAndAreKept() throws Exception {
        receipts.real.save(new PendingReceipt(UUID.randomUUID(), UUID.randomUUID(), rice.id(), device, "piece", 1_000, "Other shop"));
        assertBlockedAndKept(receipts.file(), true);

        Files.delete(receipts.file());
        corrections.real.save(new PendingStockAdjustment(UUID.randomUUID(), shop, rice.id(), UUID.randomUUID(), "piece", 1_000, "Other device"));
        assertBlockedAndKept(corrections.file(), true);
    }

    @Test
    void savedRequestsForAChangedProductOrUnitBlockTheScreenAndAreKept() throws Exception {
        receipts.real.save(new PendingReceipt(UUID.randomUUID(), shop, rice.id(), device, "kg", 1_500, "Unit changed"));
        assertBlockedAndKept(receipts.file(), true);

        Files.delete(receipts.file());
        corrections.real.save(new PendingStockAdjustment(UUID.randomUUID(), shop, UUID.randomUUID(), device, "piece", 1_000, "Unknown product"));
        assertBlockedAndKept(corrections.file(), true);
    }

    @Test
    void damagedPendingFilesBlockTheScreenAreKeptAndRefreshKeepsThemBlocked() throws Exception {
        Files.writeString(receipts.file(), "format=1\noperationId=not-a-uuid\n", StandardCharsets.UTF_8);
        StockPanel panel = assertBlockedAndKept(receipts.file(), false);
        edt(() -> { panel.refreshButton().doClick(); return null; });
        afterRead(panel, false);
        assertFalse(edt(() -> panel.receiveButton().isEnabled()));

        Files.delete(receipts.file());
        Files.writeString(corrections.file(), "format=1\ndeltaMilli=12\n", StandardCharsets.UTF_8);
        assertBlockedAndKept(corrections.file(), false);
    }

    // ----- OFF-02b: a saved request the screen cannot use can be set aside, never deleted -----

    @Test
    void aDamagedReceiptStillShowsTheStockAndCanBeSetAsideWithoutLosingTheFile() throws Exception {
        stock.receive(UUID.randomUUID(), shop, rice.id(), device, 10_000, "Opening count");
        Files.writeString(receipts.file(), "format=1\noperationId=not-a-uuid\n", StandardCharsets.UTF_8);
        byte[] damaged = Files.readAllBytes(receipts.file());

        StockPanel panel = open(true);
        edt(() -> {
            assertEquals(1, panel.stockTable().getRowCount(), "The stock is shown, not hidden by the damaged file");
            assertEquals(english.getString("stock.receipt.damaged"), panel.statusLabel().getText());
            assertTrue(setAsideButton(panel).isVisible() && setAsideButton(panel).isEnabled());
            assertFalse(panel.receiveButton().isEnabled());
            return null;
        });

        Path kept = setAside(panel);

        assertTrue(kept.getFileName().toString().startsWith("pending-stock-receipt-damaged-"), kept.toString());
        assertArrayEquals(damaged, Files.readAllBytes(kept), "The damaged file is kept byte for byte");
        assertFalse(Files.exists(receipts.file()));
        assertEquals(english.getString("stock.setAside.check"),
                edt(() -> panel.statusLabel().getText()));
        assertUnblockedAndNothingSent(panel, 10_000, 1);
    }

    @Test
    void aDamagedCorrectionCanBeSetAsideWithoutLosingTheFile() throws Exception {
        Files.writeString(corrections.file(), "format=1\ndeltaMilli=12\n", StandardCharsets.UTF_8);
        byte[] damaged = Files.readAllBytes(corrections.file());

        StockPanel panel = open(true);
        assertEquals(english.getString("stock.adjust.damaged"), edt(() -> panel.statusLabel().getText()));
        Path kept = setAside(panel);

        assertTrue(kept.getFileName().toString().startsWith("pending-stock-adjustment-damaged-"), kept.toString());
        assertArrayEquals(damaged, Files.readAllBytes(kept));
        assertFalse(Files.exists(corrections.file()));
        assertUnblockedAndNothingSent(panel, 0, 0);
    }

    @Test
    void aReceiptFromAnotherComputerThatWasNeverSavedIsSetAsideAndReportedAsNotAdded() throws Exception {
        receipts.real.save(new PendingReceipt(UUID.randomUUID(), shop, rice.id(), UUID.randomUUID(), "piece", 7_000, "Other computer"));
        byte[] saved = Files.readAllBytes(receipts.file());

        StockPanel panel = open(true);
        assertEquals(english.getString("stock.receipt.wrongOwner"), edt(() -> panel.statusLabel().getText()));
        Path kept = setAside(panel);

        assertTrue(kept.getFileName().toString().startsWith("pending-stock-receipt-set-aside-"), kept.toString());
        assertArrayEquals(saved, Files.readAllBytes(kept));
        assertEquals(english.getString("stock.setAside.notSaved"),
                edt(() -> panel.statusLabel().getText()));
        assertUnblockedAndNothingSent(panel, 0, 0);
    }

    @Test
    void aReceiptFromAnotherComputerAlreadyInTheHistoryIsSetAsideAndReportedAsSaved() throws Exception {
        UUID operation = UUID.randomUUID();
        UUID otherComputer = UUID.randomUUID();
        stock.receive(operation, shop, rice.id(), otherComputer, 7_000, "Delivery 12");
        receipts.real.save(new PendingReceipt(operation, shop, rice.id(), otherComputer, "piece", 7_000, "Delivery 12"));

        StockPanel panel = open(true);
        Path kept = setAside(panel);

        assertEquals(english.getString("stock.setAside.saved"),
                edt(() -> panel.statusLabel().getText()));
        assertUnblockedAndNothingSent(panel, 7_000, 1);
    }

    @Test
    void aReceiptFromAnotherShopIsSetAsideAndReportedAsNotAddedToThisShop() throws Exception {
        receipts.real.save(new PendingReceipt(UUID.randomUUID(), UUID.randomUUID(), rice.id(), device, "piece", 1_000, "Other shop"));

        StockPanel panel = open(true);
        Path kept = setAside(panel);

        assertEquals(english.getString("stock.setAside.notSaved"),
                edt(() -> panel.statusLabel().getText()));
        assertUnblockedAndNothingSent(panel, 0, 0);
    }

    @Test
    void aCorrectionForAProductThatNoLongerExistsIsSetAsideWithAnInvitationToCheckTheHistory() throws Exception {
        corrections.real.save(new PendingStockAdjustment(UUID.randomUUID(), shop, UUID.randomUUID(), device, "piece", 1_000, "Unknown product"));

        StockPanel panel = open(true);
        assertEquals(english.getString("stock.adjust.invalidProduct"), edt(() -> panel.statusLabel().getText()));
        Path kept = setAside(panel);

        assertTrue(kept.getFileName().toString().startsWith("pending-stock-adjustment-set-aside-"), kept.toString());
        assertEquals(english.getString("stock.setAside.check"),
                edt(() -> panel.statusLabel().getText()));
        assertUnblockedAndNothingSent(panel, 0, 0);
    }

    @Test
    void aRequestThisScreenCanRetryIsNeverOfferedToBeSetAside() throws Exception {
        receipts.real.save(new PendingReceipt(UUID.randomUUID(), shop, rice.id(), device, "piece", 7_000, "Delivery 12"));

        StockPanel panel = open(true);

        edt(() -> {
            assertFalse(setAsideButton(panel).isVisible(), "An uncertain request is retried, never set aside");
            assertEquals("Retry Original Receipt", panel.receiveButton().getText());
            return null;
        });
        assertThrows(FilePendingReceiptStore.ConflictException.class, () -> receipts.real.setAsideDamaged(),
                "The store refuses to set a readable request aside as damaged");
        assertThrows(FilePendingReceiptStore.ConflictException.class, () -> receipts.real.setAside(UUID.randomUUID()),
                "The store refuses to set aside a request other than the one the screen showed");
        assertTrue(Files.exists(receipts.file()));
    }

    private static JButton setAsideButton(StockPanel panel) { return (JButton) named(panel, "stockSetAside"); }

    /** Clicks Set the file aside, waits for it and the reload after it, and returns the kept file. */
    private Path setAside(StockPanel panel) throws Exception {
        List<Path> before;
        try (var files = Files.list(directory)) { before = files.toList(); }
        edt(() -> {
            assertTrue(setAsideButton(panel).isVisible() && setAsideButton(panel).isEnabled());
            setAsideButton(panel).doClick();
            return null;
        });
        afterOperation(panel, true);
        try (var files = Files.list(directory)) {
            List<Path> added = files.filter(file -> !before.contains(file)).toList();
            assertEquals(1, added.size(), "One file was kept under a new name: " + added);
            return added.getFirst();
        }
    }

    private void assertUnblockedAndNothingSent(StockPanel panel, long balance, int movements) throws Exception {
        edt(() -> {
            assertFalse(setAsideButton(panel).isVisible());
            assertTrue(panel.receiveButton().isEnabled(), "Receiving works again");
            assertEquals("Add to stock", panel.receiveButton().getText());
            return null;
        });
        assertEquals(0, repository.attempts.get(), "Nothing was sent");
        assertEquals(balance, balance());
        assertEquals(movements, movementIds().size());
    }

    /** Opens the screen on a saved request it must not use, and checks that nothing moved. */
    private StockPanel assertBlockedAndKept(Path file, boolean readable) throws Exception {
        byte[] before = Files.readAllBytes(file);
        long balanceBefore = balance();
        int movementsBefore = movementIds().size();
        int errorsBefore = errors.size();

        StockPanel panel = open(readable);

        edt(() -> {
            assertFalse(panel.receiveButton().isEnabled(), "Receiving is blocked");
            JButton adjust = button(panel, "Apply Adjustment");
            assertNotNull(adjust);
            assertFalse(adjust.isEnabled(), "Correcting is blocked");
            assertFalse(panel.saveMinimumButton().isEnabled(), "Saving a minimum is blocked");
            return null;
        });
        assertTrue(errors.size() > errorsBefore, "The screen says why it is blocked");
        assertEquals(0, repository.attempts.get(), "Nothing was sent");
        assertEquals(balanceBefore, balance());
        assertEquals(movementsBefore, movementIds().size());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "The saved request is kept exactly");
        return panel;
    }

    // ----- Another Cella process uses the same data folder -----

    /** Started as a separate Java process: holds the stock folder's lease until it reads a byte. */
    public static class LeaseHolder {
        public static void main(String[] args) throws Exception {
            try (var lease = PendingReceiptFileLease.acquire(Path.of(args[0]))) {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
        }
    }

    /** Started as a separate Java process: tries the stock folder's lease once and says what happened. */
    public static class LeaseProbe {
        public static void main(String[] args) throws Exception {
            try (var lease = PendingReceiptFileLease.acquire(Path.of(args[0]))) {
                System.out.println("LOCKED");
            } catch (FilePendingReceiptStore.BusyException busy) {
                System.out.println("BUSY");
            }
        }
    }

    @Test
    void aReceiptKeepsTheStockFolderFromSavingItsRequestUntilTheAnswerComes() throws Exception {
        StockPanel panel = open(true);
        var receiptReached = new java.util.concurrent.CountDownLatch(1);
        repository.reached = receiptReached;
        repository.release = new java.util.concurrent.CountDownLatch(1);

        edt(() -> {
            panel.quantityField().setText("7");
            panel.reasonField().setText("Delivery 12");
            panel.receiveButton().doClick();
            return null;
        });
        assertTrue(receiptReached.await(20, TimeUnit.SECONDS), "The receipt reached the database");
        boolean savedFirst;
        String otherProcess;
        try {
            savedFirst = Files.exists(receipts.file());
            otherProcess = runJava(LeaseProbe.class, receipts.file().toString());
        } finally {
            // Let the receipt finish whatever happens, so no background work outlives the test.
            repository.release.countDown();
            afterOperation(panel, true);
        }

        assertTrue(savedFirst, "Its request was saved first");
        assertEquals("BUSY", otherProcess, "Another process cannot take the folder between saving and the answer");
        assertEquals(7_000, balance());
        assertFalse(Files.exists(receipts.file()));
        assertEquals("LOCKED", runJava(LeaseProbe.class, receipts.file().toString()), "Free again afterwards");
    }

    /** Runs a class of this test in its own Java process and returns the first line it prints. */
    private static String runJava(Class<?> main, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaCommand(), "-cp", System.getProperty("java.class.path"), main.getName()));
        command.addAll(List.of(args));
        Process child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var process = new TestChildProcess(child)) {
            String line = process.readOutput(() -> new BufferedReader(new InputStreamReader(child.getInputStream())).readLine())
                    .get(30, TimeUnit.SECONDS);
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            return line;
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
    }

    @Test
    void whileAnotherProcessHoldsTheStockFolderNothingIsSavedOrSentThenTheSameRequestWorks() throws Exception {
        StockPanel panel = open(true);
        Process child = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("java.class.path"),
                LeaseHolder.class.getName(), receipts.file().toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var process = new TestChildProcess(child)) {
            var signal = process.readOutput(() -> new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());
            assertEquals("LOCKED", signal.get(30, TimeUnit.SECONDS));

            receive(panel, "7", "Delivery 12", false);
            assertEquals(0, repository.attempts.get(), "Nothing reached the database");
            assertFalse(Files.exists(receipts.file()), "Nothing was saved over the other process's work");
            assertTrue(edt(panel::hasPendingReceipt), "The screen keeps the request to try again");

            child.getOutputStream().write(1);
            child.getOutputStream().flush();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());

            retryReceipt(panel, true);
            assertEquals(1, repository.attempts.get());
            assertEquals(7_000, balance());
            assertFalse(Files.exists(receipts.file()));
        }
    }

    // ----- Helpers -----

    private long balance() throws SQLException { return stock.current(shop, rice.id()).quantityMilli(); }

    private List<UUID> movementIds() throws SQLException {
        return stock.history(shop, rice.id()).stream().map(StockMovement::id).toList();
    }

    /** Opens the Stock screen on this folder, as starting Cella does, and waits for its first read. */
    private StockPanel open(boolean readSucceeds) throws Exception {
        Events seen = new Events();
        StockPanel panel = edt(() -> {
            FlatLightLaf.setup();
            CatalogStyle.installDefaults();
            StockPanel created = new StockPanel(new StockService(repository), products, shop, device, english,
                    receipts, corrections);
            created.setErrorHandler(errors::add);
            created.setOnLoadWorkerDone(() -> { seen.all.incrementAndGet(); seen.loads.release(); });
            created.setOnReceiptWorkerDone(() -> { seen.all.incrementAndGet(); seen.operations.release(); });
            created.setOnHistoryWorkerDone(() -> { seen.all.incrementAndGet(); seen.histories.release(); });
            return created;
        });
        events.put(panel, seen);
        afterRead(panel, readSucceeds);
        return panel;
    }

    /** A read of products and stock. When it succeeds it also reads the history, which must end too. */
    private void afterRead(StockPanel panel, boolean succeeds) throws Exception {
        take(events.get(panel).loads, "Reading products and stock");
        if (succeeds) take(events.get(panel).histories, "Reading the movement history");
        settle(panel);
    }

    /**
     * Waits until the screen has nothing left running: no event for a while and no database call open.
     * Restoring a saved request selects its product, which reads the history a second time; this also
     * waits for that read, so no read outlives a step or the test (Windows cannot delete an open file).
     */
    private void settle(StockPanel panel) throws Exception {
        Events seen = events.get(panel);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int quietChecks = 0;
        while (quietChecks < 3) {
            assertTrue(System.nanoTime() < deadline, "The Stock screen never became quiet");
            int before = seen.all.get();
            Thread.sleep(100);
            edt(() -> null);      // runs any answer already waiting for the event thread
            quietChecks = seen.all.get() == before && repository.running.get() == 0 ? quietChecks + 1 : 0;
        }
        seen.loads.drainPermits();
        seen.histories.drainPermits();
        seen.operations.drainPermits();
    }

    @AfterEach
    void letEveryScreenFinish() throws Exception {
        for (StockPanel panel : events.keySet()) settle(panel);
    }

    /** Waits for the operation's answer and, after a confirmed one, for the read that follows. */
    private void afterOperation(StockPanel panel, boolean confirmed) throws Exception {
        take(events.get(panel).operations, "The operation");
        if (confirmed) afterRead(panel, true);
        else settle(panel);
    }

    private void receive(StockPanel panel, String quantity, String reason, boolean confirmed) throws Exception {
        edt(() -> {
            panel.quantityField().setText(quantity);
            panel.reasonField().setText(reason);
            panel.receiveButton().doClick();
            return null;
        });
        afterOperation(panel, confirmed);
    }

    private void retryReceipt(StockPanel panel, boolean confirmed) throws Exception {
        edt(() -> {
            assertEquals("Retry Original Receipt", panel.receiveButton().getText());
            assertTrue(panel.receiveButton().isEnabled());
            panel.receiveButton().doClick();
            return null;
        });
        afterOperation(panel, confirmed);
    }

    private void correct(StockPanel panel, String quantity, String reason, boolean confirmed) throws Exception {
        edt(() -> {
            ((JComboBox<?>) named(panel, "stockAdjustmentDirection")).setSelectedIndex(0);   // add stock
            field(panel, "stockAdjustmentQuantity").setText(quantity);
            field(panel, "stockAdjustmentReason").setText(reason);
            button(panel, "Apply Adjustment").doClick();
            return null;
        });
        afterOperation(panel, confirmed);
    }

    private void retryCorrection(StockPanel panel, boolean confirmed) throws Exception {
        edt(() -> {
            JButton retry = button(panel, "Retry Original Adjustment");
            assertNotNull(retry);
            assertTrue(retry.isEnabled());
            retry.doClick();
            return null;
        });
        afterOperation(panel, confirmed);
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
        final Semaphore histories = new Semaphore(0);
    }

    /** What the database does with the next movement it is given. */
    enum Next { SAVE, FAIL_BEFORE_SAVING, SAVE_THEN_LOSE_THE_ANSWER }

    /** The real repository, which can fail once just before, or just after, really saving. */
    static final class ScriptedRepository implements StockRepository {
        volatile StockRepository real;
        final AtomicReference<Next> next = new AtomicReference<>(Next.SAVE);
        final AtomicInteger attempts = new AtomicInteger();
        /** Database calls from the screen that have not returned yet. */
        final AtomicInteger running = new AtomicInteger();
        /** When set, the next movement reports here, then waits for {@link #release} before saving. */
        volatile java.util.concurrent.CountDownLatch reached;
        volatile java.util.concurrent.CountDownLatch release;

        @Override public StockResult record(StockMovement movement) throws SQLException {
            return counted(() -> {
                attempts.incrementAndGet();
                var pause = reached;
                if (pause != null) {
                    reached = null;
                    pause.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) throw new SQLException("The test never released the movement");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException(interrupted);
                    }
                }
                Next now = next.getAndSet(Next.SAVE);
                if (now == Next.FAIL_BEFORE_SAVING) throw new SQLException("Simulated failure before saving");
                StockResult result = real.record(movement);
                if (now == Next.SAVE_THEN_LOSE_THE_ANSWER) throw new SQLException("Simulated lost answer after saving");
                return result;
            });
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

    /** The real pending-receipt file, whose writing or removal can be made to fail. */
    static final class FlakyReceipts implements PendingReceiptStore {
        final FilePendingReceiptStore real;
        volatile boolean failSave;
        volatile boolean failClear;

        FlakyReceipts(Path file) { real = new FilePendingReceiptStore(file); }

        @Override public Optional<PendingReceipt> load() throws IOException { return real.load(); }
        @Override public void save(PendingReceipt receipt) throws IOException {
            if (failSave) throw new IOException("Simulated: the pending file cannot be written");
            real.save(receipt);
        }
        @Override public void clear(UUID expectedOperationId) throws IOException {
            if (failClear) throw new IOException("Simulated: the pending file cannot be removed");
            real.clear(expectedOperationId);
        }
        @Override public Path file() { return real.file(); }
        @Override public Path setAsideDamaged() throws IOException { return real.setAsideDamaged(); }
        @Override public Path setAside(UUID expectedOperationId) throws IOException { return real.setAside(expectedOperationId); }
    }

    /** The real pending-correction file, whose writing or removal can be made to fail. */
    static final class FlakyCorrections implements PendingStockAdjustmentStore {
        final FilePendingStockAdjustmentStore real;
        volatile boolean failSave;
        volatile boolean failClear;

        FlakyCorrections(Path file) { real = new FilePendingStockAdjustmentStore(file); }

        @Override public Optional<PendingStockAdjustment> load() throws IOException { return real.load(); }
        @Override public void save(PendingStockAdjustment adjustment) throws IOException {
            if (failSave) throw new IOException("Simulated: the pending file cannot be written");
            real.save(adjustment);
        }
        @Override public void clear(UUID expectedOperationId) throws IOException {
            if (failClear) throw new IOException("Simulated: the pending file cannot be removed");
            real.clear(expectedOperationId);
        }
        @Override public Path file() { return real.file(); }
        @Override public Path setAsideDamaged() throws IOException { return real.setAsideDamaged(); }
        @Override public Path setAside(UUID expectedOperationId) throws IOException { return real.setAside(expectedOperationId); }
    }
}

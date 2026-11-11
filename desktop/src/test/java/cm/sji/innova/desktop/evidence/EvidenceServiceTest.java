package cm.sji.innova.desktop.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.desktop.TestChildProcess;
import cm.sji.innova.desktop.catalog.importing.PendingImportStore;
import cm.sji.innova.desktop.evidence.EvidenceDecision.Outcome;
import cm.sji.innova.desktop.sales.PendingCheckoutStore;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.stock.FilePendingReceiptStore;
import cm.sji.innova.desktop.stock.FilePendingStockAdjustmentStore;
import cm.sji.innova.desktop.stock.PendingReceipt;
import cm.sji.innova.desktop.stock.PendingStockAdjustment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03a2: set-aside files and the decisions about them, on a real shop. Each kind of set-aside file is
 * made the way the screens make it. Disposable data only.
 */
class EvidenceServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);

    @TempDir Path directory;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private LocalAppData location;
    private AppContext shop;
    private Product rice;
    private EvidenceService evidence;
    /** The six set-aside files, by what they are. */
    private final Map<String, String> made = new HashMap<>();
    private UUID recordedReceipt;

    @BeforeEach
    void aShopWithOneSetAsideFileOfEachKind() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        shop = AppContextLoader.load(location);
        rice = shop.products().create(shop.shopId(), "Rice", 1_000);
        evidence = new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), CLOCK);
        Path folder = location.directory();
        UUID otherComputer = UUID.randomUUID();

        // A damaged receipt.
        Path receipts = folder.resolve("pending-stock-receipt.properties");
        Files.writeString(receipts, "format=1\noperationId=not-a-uuid\n");
        made.put("damaged receipt", new FilePendingReceiptStore(receipts).setAsideDamaged().getFileName().toString());

        // A receipt from another computer of this shop, which the database already has.
        recordedReceipt = UUID.randomUUID();
        shop.stock().receive(recordedReceipt, shop.shopId(), rice.id(), otherComputer, 7_000, "Delivery 12");
        new FilePendingReceiptStore(receipts).save(new PendingReceipt(recordedReceipt, shop.shopId(), rice.id(), otherComputer, "piece", 7_000, "Delivery 12"));
        made.put("recorded receipt", new FilePendingReceiptStore(receipts).setAside(recordedReceipt).getFileName().toString());

        // A correction for a product that no longer exists.
        Path corrections = folder.resolve("pending-stock-adjustment.properties");
        UUID lostProduct = UUID.randomUUID();
        UUID correction = UUID.randomUUID();
        new FilePendingStockAdjustmentStore(corrections).save(new PendingStockAdjustment(correction, shop.shopId(), lostProduct, shop.deviceId(), "piece", 1_000, "Found"));
        made.put("unknown product", new FilePendingStockAdjustmentStore(corrections).setAside(correction).getFileName().toString());

        // A correction from another shop.
        UUID foreign = UUID.randomUUID();
        new FilePendingStockAdjustmentStore(corrections).save(new PendingStockAdjustment(foreign, UUID.randomUUID(), rice.id(), shop.deviceId(), "piece", 1_000, "Other shop"));
        made.put("other shop", new FilePendingStockAdjustmentStore(corrections).setAside(foreign).getFileName().toString());

        // A damaged cash sale.
        Path checkout = location.pendingCheckoutFile();
        Files.writeString(checkout, "not our file");
        made.put("damaged sale", new PendingCheckoutStore(checkout).setAside().getFileName().toString());

        // A damaged import whose operation can still be read.
        Files.writeString(folder.resolve("pending-csv.properties"),
                "version=1\nshop=" + shop.shopId() + "\noperation=" + UUID.randomUUID() + "\nrows=x\n");
        try (var importStore = new PendingImportStore(folder, shop.shopId())) {
            made.put("damaged import", importStore.setAside().getFileName().toString());
        }
    }

    @AfterEach
    void letGoOfEverything() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) toClose.get(i).close();
    }

    // ----- What is found -----

    @Test
    void everyKindIsFoundWithItsFingerprintWhatItSaysAndAHint() throws Exception {
        Map<String, Evidence> found = byName(evidence.inventory());

        assertEquals(6, found.size());
        for (Evidence one : found.values()) {
            Path file = location.directory().resolve(one.fileName());
            assertEquals(Files.size(file), one.size());
            assertEquals(EvidenceService.sha256(file), one.sha256());
            assertEquals(Evidence.Status.UNDECIDED, one.status());
            assertTrue(one.blocking(), "Undecided files block backup and restore");
        }

        Evidence damaged = found.get(made.get("damaged receipt"));
        assertEquals(EvidenceKind.STOCK_RECEIPT, damaged.kind());
        assertTrue(damaged.damagedWhenSetAside());
        assertNull(damaged.details(), "Nothing is guessed from a damaged file");
        assertEquals(Evidence.Hint.UNKNOWN, damaged.hint());

        Evidence recorded = found.get(made.get("recorded receipt"));
        assertEquals(recordedReceipt, recorded.details().operationId());
        assertEquals(rice.id(), recorded.details().productId());
        assertEquals(7_000L, recorded.details().quantityMilli());
        assertEquals(Evidence.Hint.FOUND, recorded.hint(), "Its movement is in the history");

        assertEquals(EvidenceKind.STOCK_CORRECTION, found.get(made.get("unknown product")).kind());
        assertEquals(Evidence.Hint.UNKNOWN, found.get(made.get("unknown product")).hint(), "The history cannot be checked");
        assertEquals(Evidence.Hint.OTHER_SHOP, found.get(made.get("other shop")).hint());
        assertEquals(EvidenceKind.CASH_SALE, found.get(made.get("damaged sale")).kind());
        assertEquals(Evidence.Hint.UNKNOWN, found.get(made.get("damaged sale")).hint());
        assertEquals(EvidenceKind.CSV_IMPORT, found.get(made.get("damaged import")).kind());
        assertEquals(Evidence.Hint.NOT_FOUND, found.get(made.get("damaged import")).hint(), "Its import was never recorded");

        assertEquals(6, evidence.unresolved().size(), "All six block backup and restore");
    }

    // ----- Decisions -----

    @Test
    void aDecisionCoversThoseExactBytesIsWrittenOnceAndUnblocksOnlyThatFile() throws Exception {
        Evidence recorded = byName(evidence.inventory()).get(made.get("recorded receipt"));

        EvidenceDecision decision = evidence.decide(recorded.fileName(), recorded.sha256(), "  Charles  ",
                Outcome.ALREADY_RECORDED, recordedReceipt, "Delivery 12 is in the history of Rice");

        assertEquals("Charles", decision.decidedBy());
        assertEquals(CLOCK.instant(), decision.decidedAt());
        Evidence after = byName(evidence.inventory()).get(recorded.fileName());
        assertEquals(Evidence.Status.DECIDED, after.status());
        assertEquals(decision, after.decision(), "It is read back exactly");
        assertEquals(5, evidence.unresolved().size());
        assertTrue(evidence.unresolved().stream().noneMatch(one -> one.fileName().equals(recorded.fileName())));

        DecisionRefusedException again = assertThrows(DecisionRefusedException.class, () -> evidence.decide(recorded.fileName(),
                recorded.sha256(), "Someone else", Outcome.DID_NOT_HAPPEN, null, "Changed my mind"));
        assertEquals(DecisionRefusedException.Reason.ALREADY_DECIDED, again.reason());
        assertEquals(decision, byName(evidence.inventory()).get(recorded.fileName()).decision(), "The first decision stays");
    }

    @Test
    void aFileThatChangesAfterItsDecisionNeedsANewOne() throws Exception {
        Evidence sale = byName(evidence.inventory()).get(made.get("damaged sale"));
        EvidenceDecision first = evidence.decide(sale.fileName(), sale.sha256(), "Charles", Outcome.DID_NOT_HAPPEN, null,
                "The basket was cancelled at the till");
        Files.writeString(location.directory().resolve(sale.fileName()), "someone edited it");

        Evidence changed = byName(evidence.inventory()).get(sale.fileName());

        assertEquals(Evidence.Status.CHANGED_SINCE_DECISION, changed.status());
        assertTrue(changed.blocking());
        assertEquals(first, changed.decision(), "The older decision is shown, not applied");

        evidence.decide(sale.fileName(), changed.sha256(), "Charles", Outcome.OTHER, null, "Looked at the edited file too");
        assertEquals(Evidence.Status.DECIDED, byName(evidence.inventory()).get(sale.fileName()).status());
    }

    @Test
    void aDecisionAboutBytesThePersonDidNotSeeIsRefusedAndNothingIsWritten() throws Exception {
        Evidence sale = byName(evidence.inventory()).get(made.get("damaged sale"));
        Files.writeString(location.directory().resolve(sale.fileName()), "changed after it was shown");

        DecisionRefusedException refused = assertThrows(DecisionRefusedException.class, () -> evidence.decide(sale.fileName(),
                sale.sha256(), "Charles", Outcome.DID_NOT_HAPPEN, null, "Cancelled"));

        assertEquals(DecisionRefusedException.Reason.EVIDENCE_CHANGED, refused.reason());
        assertEquals(Evidence.Status.UNDECIDED, byName(evidence.inventory()).get(sale.fileName()).status());
    }

    @Test
    void aDecisionNeedsAnEvidenceFileANameAReasonAndForAlreadyRecordedTheOperation() throws Exception {
        Evidence any = byName(evidence.inventory()).get(made.get("damaged import"));
        assertThrows(IllegalArgumentException.class, () -> evidence.decide(any.fileName(), any.sha256(), " ", Outcome.OTHER, null, "Why"));
        assertThrows(IllegalArgumentException.class, () -> evidence.decide(any.fileName(), any.sha256(), "Charles", Outcome.OTHER, null, ""));
        assertThrows(IllegalArgumentException.class, () -> evidence.decide(any.fileName(), any.sha256(), "Charles", Outcome.ALREADY_RECORDED, null, "In the history"));
        for (String notEvidence : List.of("cella.db", "shop-id.txt", "pending-stock-receipt.properties", "../" + any.fileName())) {
            DecisionRefusedException refused = assertThrows(DecisionRefusedException.class,
                    () -> evidence.decide(notEvidence, any.sha256(), "Charles", Outcome.OTHER, null, "Why"), notEvidence);
            assertEquals(DecisionRefusedException.Reason.NOT_EVIDENCE, refused.reason());
        }
        assertEquals(6, evidence.unresolved().size(), "Nothing was decided");
    }

    @Test
    void setAsideFilesAreOnlyEverRead() throws Exception {
        Map<String, byte[]> bytes = new HashMap<>();
        Map<String, FileTime> dates = new HashMap<>();
        for (String name : made.values()) {
            bytes.put(name, Files.readAllBytes(location.directory().resolve(name)));
            dates.put(name, Files.getLastModifiedTime(location.directory().resolve(name)));
        }
        for (Evidence one : evidence.inventory()) {
            evidence.decide(one.fileName(), one.sha256(), "Charles", Outcome.OTHER, null, "Checked by hand");
        }

        assertEquals(List.of(), evidence.unresolved());
        for (String name : made.values()) {
            assertArrayEquals(bytes.get(name), Files.readAllBytes(location.directory().resolve(name)), name);
            assertEquals(dates.get(name), Files.getLastModifiedTime(location.directory().resolve(name)), name);
        }
    }

    @Test
    void anUnreadableDecisionBlocksAndIsNeverReplaced() throws Exception {
        Evidence sale = byName(evidence.inventory()).get(made.get("damaged sale"));
        Path decisions = location.directory().resolve(EvidenceService.DECISIONS_FOLDER);
        Files.createDirectories(decisions);
        Path tampered = decisions.resolve(sale.fileName() + "." + sale.sha256().substring(0, 16) + ".decision.properties");
        Files.writeString(tampered, "not a decision");

        assertEquals(Evidence.Status.DECISION_UNREADABLE, byName(evidence.inventory()).get(sale.fileName()).status());
        DecisionRefusedException refused = assertThrows(DecisionRefusedException.class, () -> evidence.decide(sale.fileName(),
                sale.sha256(), "Charles", Outcome.OTHER, null, "Overwrite it"));
        assertEquals(DecisionRefusedException.Reason.ALREADY_DECIDED, refused.reason());
        assertEquals("not a decision", Files.readString(tampered));
    }

    // ----- With other writers -----

    @Test
    void recordingADecisionWaitsDuringMaintenanceThenGoesOn() throws Exception {
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        toClose.add(coordinator.join());
        Evidence sale = byName(evidence.inventory()).get(made.get("damaged sale"));
        ExecutorService workers = stoppableWorkers(2);
        CompletableFuture<Void> started = new CompletableFuture<>(), finish = new CompletableFuture<>();
        workers.submit(() -> {
            try (DataFolderCoordinator.Maintenance ignored = coordinator.beginMaintenance(Duration.ofSeconds(10))) {
                started.complete(null);
                finish.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                started.completeExceptionally(failure);
            }
            return null;
        });
        try {
            started.get(10, TimeUnit.SECONDS);
            Future<EvidenceDecision> waiting = workers.submit(() -> evidence.decide(sale.fileName(), sale.sha256(),
                    "Charles", Outcome.DID_NOT_HAPPEN, null, "Cancelled"));
            assertThrows(TimeoutException.class, () -> waiting.get(300, TimeUnit.MILLISECONDS), "It waits during maintenance");
            assertEquals(0, decisionFiles(), "Nothing written meanwhile");
            finish.complete(null);
            assertEquals(Outcome.DID_NOT_HAPPEN, waiting.get(10, TimeUnit.SECONDS).outcome(), "It goes on afterwards");
        } finally {
            finish.complete(null);
        }
    }

    @Test
    void twoPeopleDecidingTheSameFileAtOnceGetOneDecision() throws Exception {
        Evidence sale = byName(evidence.inventory()).get(made.get("damaged sale"));
        ExecutorService workers = stoppableWorkers(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (String who : List.of("Charles", "Patrick")) {
            attempts.add(workers.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    evidence.decide(sale.fileName(), sale.sha256(), who, Outcome.OTHER, null, "Checked");
                    return "decided";
                } catch (DecisionRefusedException refused) {
                    return refused.reason().name();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> attempt : attempts) results.add(attempt.get(20, TimeUnit.SECONDS));

        assertEquals(List.of("ALREADY_DECIDED", "decided"), results.stream().sorted().toList());
    }

    /** Started as a separate Java process: holds the decision lock until it reads a byte. */
    public static class DecisionHolder {
        public static void main(String[] args) throws Exception {
            try (var lock = DecisionLock.acquire(Path.of(args[0]))) {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
        }
    }

    @Test
    void anotherCellaRecordingADecisionMakesThisOneWaitItsTurn() throws Exception {
        Evidence sale = byName(evidence.inventory()).get(made.get("damaged sale"));
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), DecisionHolder.class.getName(),
                location.directory().resolve(EvidenceService.DECISIONS_FOLDER).toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (TestChildProcess child = new TestChildProcess(process)) {
            assertEquals("LOCKED", child.readOutput(() -> new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8)).readLine()).get(60, TimeUnit.SECONDS));
            ExecutorService workers = stoppableWorkers(1);
            Future<EvidenceDecision> waiting = workers.submit(() -> evidence.decide(sale.fileName(), sale.sha256(),
                    "Charles", Outcome.OTHER, null, "Checked"));
            assertThrows(TimeoutException.class, () -> waiting.get(500, TimeUnit.MILLISECONDS), "It waits for the other Cella");

            process.getOutputStream().write(1);
            process.getOutputStream().flush();
            assertEquals(Outcome.OTHER, waiting.get(20, TimeUnit.SECONDS).outcome(), "Then it records its decision");
        }
    }

    // ----- Helpers -----

    private long decisionFiles() throws Exception {
        Path decisions = location.directory().resolve(EvidenceService.DECISIONS_FOLDER);
        if (!Files.exists(decisions)) return 0;
        try (var listed = Files.list(decisions)) {
            return listed.filter(path -> path.toString().endsWith(".decision.properties")).count();
        }
    }

    private static Map<String, Evidence> byName(List<Evidence> list) {
        Map<String, Evidence> map = new HashMap<>();
        for (Evidence one : list) map.put(one.fileName(), one);
        return map;
    }

    /** Worker threads the test always stops at the end, so a stuck one fails the test instead of hanging it. */
    private ExecutorService stoppableWorkers(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "evidence-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        toClose.add(pool::shutdownNow);
        return pool;
    }
}

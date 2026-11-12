package cm.sji.innova.desktop.evidence;

import cm.sji.innova.core.persistence.DatabaseGate;
import cm.sji.innova.core.product.importer.ProductImportRepository;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.evidence.DecisionRefusedException.Reason;
import cm.sji.innova.desktop.evidence.EvidenceDecision.Outcome;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * OFF-03a2: set-aside files and the decisions about them. See "Saved requests and preserved evidence" in
 * {@code docs/design/BACKUP_RESTORE.md}. Setting a file aside does not settle its operation: until a
 * person records a decision covering its exact bytes, backup and restore stay blocked.
 *
 * <ul>
 *   <li>Set-aside files are only read: never moved, changed or deleted.</li>
 *   <li>Hints say whether an operation is already recorded, but are never applied by themselves; an
 *       unreadable file gets no guess.</li>
 *   <li>A decision is written once, in {@code recovery-evidence/}, and never replaced. It names the file's
 *       SHA-256; a file that changes afterwards needs a new decision.</li>
 *   <li>Decisions are written through the data folder's gate, so they wait during a backup or restore,
 *       and one at a time across Cella processes.</li>
 * </ul>
 * File and database work: never call it on the Swing event thread.
 */
public final class EvidenceService {
    public static final String DECISIONS_FOLDER = "recovery-evidence";
    private static final String SUFFIX = ".decision.properties";

    private final LocalAppData location;
    private final UUID shopId;
    private final StockService stock;
    private final SaleService sales;
    private final ProductImportRepository imports;
    private final Clock clock;

    public EvidenceService(LocalAppData location, UUID shopId, StockService stock, SaleService sales,
                           ProductImportRepository imports, Clock clock) {
        this.location = Objects.requireNonNull(location, "location");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Every set-aside file in the data folder, oldest first, with its hint and decision status. */
    public List<Evidence> inventory() throws IOException {
        List<Path> files;
        try (var listed = Files.list(location.directory())) {
            files = listed.filter(Files::isRegularFile)
                    .filter(file -> EvidenceKind.of(file.getFileName().toString()).isPresent()).toList();
        }
        List<Evidence> found = new ArrayList<>();
        for (Path file : files) found.add(describe(file));
        found.sort(Comparator.comparing(Evidence::savedAt).thenComparing(Evidence::fileName));
        return List.copyOf(found);
    }

    /** The set-aside files that block backup and restore: no decision covers their exact bytes. */
    public List<Evidence> unresolved() throws IOException {
        return inventory().stream().filter(Evidence::blocking).toList();
    }

    /**
     * Records a decision about one set-aside file. {@code expectedSha256} is the fingerprint the person saw:
     * if the file changed since, nothing is written. A decision already covering these bytes is never
     * replaced.
     */
    public EvidenceDecision decide(String fileName, String expectedSha256, String decidedBy, Outcome outcome,
                                   UUID operationId, String reason) throws IOException, SQLException {
        if (EvidenceKind.of(fileName).isEmpty()) {
            throw new DecisionRefusedException(Reason.NOT_EVIDENCE, "Not a set-aside file: " + fileName);
        }
        EvidenceDecision decision = new EvidenceDecision(fileName, expectedSha256, decidedBy, clock.instant(),
                outcome, operationId, reason);
        Path evidence = location.directory().resolve(fileName);
        Path decisions = location.directory().resolve(DECISIONS_FOLDER);
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory());
             DecisionLock lock = DecisionLock.acquire(decisions)) {
            if (!Files.isRegularFile(evidence)) {
                throw new DecisionRefusedException(Reason.NOT_EVIDENCE, "No such set-aside file: " + fileName);
            }
            if (!sha256(evidence).equals(expectedSha256)) {
                throw new DecisionRefusedException(Reason.EVIDENCE_CHANGED,
                        "The file changed since it was shown. Look at it again before deciding: " + fileName);
            }
            Path target = decisions.resolve(decisionName(fileName, expectedSha256));
            if (Files.exists(target)) {
                throw new DecisionRefusedException(Reason.ALREADY_DECIDED, "A decision already covers this file: " + fileName);
            }
            write(decision, decisions, target);
            return decision;
        }
    }

    private Evidence describe(Path file) throws IOException {
        String name = file.getFileName().toString();
        EvidenceKind kind = EvidenceKind.of(name).orElseThrow();
        String sha = sha256(file);
        Evidence.Details details = read(kind, file);
        Evidence.Hint hint = hint(kind, details);
        Evidence.Status status = Evidence.Status.UNDECIDED;
        EvidenceDecision decision = null;
        Path decisions = location.directory().resolve(DECISIONS_FOLDER);
        if (Files.isDirectory(decisions)) {
            Path own = decisions.resolve(decisionName(name, sha));
            if (Files.exists(own)) {
                Optional<EvidenceDecision> loaded = load(own);
                if (loaded.isPresent() && loaded.get().evidenceFile().equals(name) && loaded.get().evidenceSha256().equals(sha)) {
                    status = Evidence.Status.DECIDED;
                    decision = loaded.get();
                } else {
                    status = Evidence.Status.DECISION_UNREADABLE;
                }
            } else {
                try (var older = Files.list(decisions)) {
                    Optional<Path> earlier = older.filter(path -> path.getFileName().toString().startsWith(name + "."))
                            .filter(path -> path.getFileName().toString().endsWith(SUFFIX)).findFirst();
                    if (earlier.isPresent()) {
                        status = Evidence.Status.CHANGED_SINCE_DECISION;
                        decision = load(earlier.get()).orElse(null);
                    }
                }
            }
        }
        return new Evidence(name, kind, EvidenceKind.damagedWhenSetAside(name), Files.size(file), sha,
                Files.getLastModifiedTime(file).toInstant(), details, hint, status, decision);
    }

    /** What can be read from the file, or {@code null}. Reading never guesses: a missing value stays missing. */
    private static Evidence.Details read(EvidenceKind kind, Path file) {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
        Evidence.Details details = switch (kind) {
            case STOCK_RECEIPT -> new Evidence.Details(uuid(p, "operationId"), uuid(p, "shopId"), uuid(p, "deviceId"),
                    uuid(p, "productId"), p.getProperty("unit"), number(p, "quantityMilli"), p.getProperty("reason"));
            case STOCK_CORRECTION -> new Evidence.Details(uuid(p, "operationId"), uuid(p, "shopId"), uuid(p, "deviceId"),
                    uuid(p, "productId"), p.getProperty("unit"), number(p, "deltaMilli"), p.getProperty("reason"));
            case CASH_SALE -> new Evidence.Details(uuid(p, "saleId"), uuid(p, "shopId"), uuid(p, "deviceId"),
                    null, null, null, p.getProperty("receiptNumber"));
            case CSV_IMPORT -> new Evidence.Details(uuid(p, "operation"), uuid(p, "shop"), null,
                    null, null, null, p.getProperty("rows") == null ? null : p.getProperty("rows") + " rows");
        };
        return details.operationId() == null && details.shopId() == null ? null : details;
    }

    private Evidence.Hint hint(EvidenceKind kind, Evidence.Details details) {
        if (details == null || details.operationId() == null || details.shopId() == null) return Evidence.Hint.UNKNOWN;
        if (!shopId.equals(details.shopId())) return Evidence.Hint.OTHER_SHOP;
        try {
            boolean found = switch (kind) {
                case STOCK_RECEIPT, STOCK_CORRECTION -> {
                    if (details.productId() == null) throw new IllegalStateException("No product");
                    yield stock.history(shopId, details.productId()).stream()
                            .anyMatch(movement -> movement.id().equals(details.operationId()));
                }
                case CASH_SALE -> sales.getSale(shopId, details.operationId()).isPresent();
                case CSV_IMPORT -> imports.findOperation(details.operationId())
                        .filter(operation -> shopId.equals(operation.shopId())).isPresent();
            };
            return found ? Evidence.Hint.FOUND : Evidence.Hint.NOT_FOUND;
        } catch (SQLException | RuntimeException cannotTell) {
            return Evidence.Hint.UNKNOWN;    // for example, the product no longer exists
        }
    }

    private static void write(EvidenceDecision decision, Path decisions, Path target) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("evidence", decision.evidenceFile());
        p.setProperty("sha256", decision.evidenceSha256());
        p.setProperty("decidedBy", decision.decidedBy());
        p.setProperty("decidedAt", decision.decidedAt().toString());
        p.setProperty("outcome", decision.outcome().name());
        if (decision.operationId() != null) p.setProperty("operationId", decision.operationId().toString());
        p.setProperty("reason", decision.reason());
        Path temporary = Files.createTempFile(decisions, ".decision-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                p.store(writer, "Cella: a decision about a set-aside file. Never edit it.");
                writer.flush();
                channel.force(true);
            }
            // The lock is held and the target was checked absent: the rename cannot replace another decision.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Optional<EvidenceDecision> load(Path file) {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            if (!"1".equals(p.getProperty("format"))) return Optional.empty();
            String operation = p.getProperty("operationId");
            return Optional.of(new EvidenceDecision(p.getProperty("evidence"), p.getProperty("sha256"),
                    p.getProperty("decidedBy"), Instant.parse(p.getProperty("decidedAt")),
                    Outcome.valueOf(p.getProperty("outcome")), operation == null ? null : UUID.fromString(operation),
                    p.getProperty("reason")));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** One decision file per set-aside file and fingerprint: a changed file gets its own decision. */
    private static String decisionName(String evidenceFile, String sha256) {
        return evidenceFile + "." + sha256.substring(0, 16) + SUFFIX;
    }

    private static UUID uuid(Properties p, String key) {
        try {
            String value = p.getProperty(key);
            return value == null ? null : UUID.fromString(value.strip());
        } catch (IllegalArgumentException damaged) {
            return null;
        }
    }

    private static Long number(Properties p, String key) {
        try {
            String value = p.getProperty(key);
            return value == null ? null : Long.valueOf(value.strip());
        } catch (NumberFormatException damaged) {
            return null;
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) sha.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}

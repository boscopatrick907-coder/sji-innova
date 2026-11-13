package cm.sji.innova.desktop.backup;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.persistence.SqliteSaleRepository;
import cm.sji.innova.core.persistence.SqliteStockRepository;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.backup.RestoreJournal.Item;
import cm.sji.innova.desktop.backup.RestoreJournal.Phase;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.swing.SwingUtilities;

/**
 * OFF-03b2: installs a scheduled restore, and finishes or undoes one that a kill interrupted, following
 * "Exclusive restore with a durable recovery journal" in {@code docs/design/BACKUP_RESTORE.md}. Cella calls
 * {@link #atStart()} at start-up, after joining the data folder and before any shop service reads it (OFF-03b3).
 *
 * <ul>
 *   <li>With no journal and no scheduled note, it does nothing and takes nothing.</li>
 *   <li>Otherwise it takes the folder alone (folder maintenance). <b>A journal</b> means a restore was
 *       interrupted: before {@code COMMITTED} the whole old shop is put back; after it, the new shop is checked
 *       and the restore finished.</li>
 *   <li><b>A scheduled note:</b> the prepared files must be exactly as confirmed and made for this Cella. A
 *       pre-restore backup is made and checked, which also refuses saved requests and undecided set-aside files.
 *       Then the journal is written, and the items move one by one: the old ones aside to
 *       {@code restore/<restore ID>/old/}, then the new ones in. The installed shop is checked before
 *       {@code COMMITTED}. A failure once the journal exists puts the whole old shop back.</li>
 * </ul>
 * {@code device-id.txt}, logs, lock files and {@code restore/} never move. Nothing old is deleted: the old shop,
 * with any {@code -wal} and {@code -shm} beside its database, stays in {@code old/} next to the pre-restore backup.
 */
public final class RestoreInstaller {
    static final String OLD = "old";
    static final String PRE_RESTORE = "pre-restore";
    private static final Duration DRAIN = Duration.ofSeconds(30);

    public enum Outcome { NOTHING_SCHEDULED, RESTORED, ROLLED_BACK }

    /** What happened at start-up. {@code detail} says why a restore was undone. */
    public record Result(Outcome outcome, UUID restoreId, String detail) { }

    /** Told after each durable step, so a test can stop Cella between any two of them. */
    @FunctionalInterface
    interface Steps {
        void reached(String step) throws IOException;
    }

    private final LocalAppData location;
    private final DataFolderCoordinator coordinator;
    private final Clock clock;
    private final Steps steps;

    /**
     * Needs no shop identity: after an interrupted restore, {@code shop-id.txt} may be set aside. It reads the
     * shop's and this computer's IDs itself, only when it starts a scheduled restore.
     */
    public RestoreInstaller(LocalAppData location, DataFolderCoordinator coordinator, Clock clock) {
        this(location, coordinator, clock, step -> { });
    }

    /** For tests: {@code steps} hears of every durable step. */
    RestoreInstaller(LocalAppData location, DataFolderCoordinator coordinator, Clock clock, Steps steps) {
        this.location = Objects.requireNonNull(location, "location");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.steps = Objects.requireNonNull(steps, "steps");
    }

    /**
     * At start-up: finishes or undoes an interrupted restore, otherwise runs the scheduled one, if any. Call it
     * as a member of the folder, off the Swing event thread, before anything reads the shop's identity or opens
     * its services.
     */
    public Result atStart() throws IOException, SQLException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Never run a restore on the Swing event thread");
        if (!Files.exists(location.directory().resolve(RestoreJournal.FILE)) && !Files.exists(note())) {
            return new Result(Outcome.NOTHING_SCHEDULED, null, "");
        }
        try (DataFolderCoordinator.Maintenance maintenance = coordinator.beginMaintenance(DRAIN)) {
            Optional<RestoreJournal> journal = RestoreJournal.read(location.directory());   // again, now that the folder is ours
            if (journal.isPresent()) return recover(maintenance, journal.get());
            UUID shopId = id(location.shopIdFile());
            Optional<RestorePreparation.Summary> scheduled = new RestorePreparation(location, shopId, clock).readScheduled();
            if (scheduled.isEmpty()) return new Result(Outcome.NOTHING_SCHEDULED, null, "");
            return run(maintenance, scheduled.get(), shopId, id(location.deviceIdFile()));
        }
    }

    // ----- Running a scheduled restore -----

    private Result run(DataFolderCoordinator.Maintenance maintenance, RestorePreparation.Summary note, UUID shopId, UUID deviceId)
            throws IOException, SQLException {
        Path folder = restoreFolder(note.restoreId());
        Path staged = folder.resolve(RestorePreparation.STAGED);
        Path archive = folder.resolve(RestorePreparation.ARCHIVE);
        if (!Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || !note.archiveSha256().equals(BackupService.sha256(archive))
                || !note.stagedFiles().equals(RestorePreparation.fingerprint(staged))) {
            throw new RestoreException(RestoreException.Reason.STAGING_CHANGED,
                    "The prepared restore changed after it was confirmed. Nothing was changed; choose the backup again.");
        }
        if (schemaVersion(staged.resolve("cella.db")) != SqliteDatabase.SCHEMA_VERSION) {
            throw new RestoreException(RestoreException.Reason.STAGED_CHECK_FAILED,
                    "This restore was prepared by another version of Cella. Nothing was changed; choose the backup again.");
        }
        BackupService.Backup before = new BackupService(location, shopId, deviceId, coordinator, evidence(shopId), clock)
                .createDuring(maintenance, folder.resolve(PRE_RESTORE));
        step("pre-restore backup made");

        RestoreJournal journal = new RestoreJournal(note.restoreId(), Phase.PREPARED, shopId, note.shopId(), note.products(),
                note.sales(), note.stockMovements(), before.file().getFileName().toString(), before.sha256(), items(staged));
        journal.publish(location.directory());
        step("journal " + Phase.PREPARED);
        try {
            Path old = folder.resolve(OLD);
            for (Item item : journal.items()) {
                if (!item.hadOld()) continue;
                moveNew(active(item), old.resolve(item.name()));
                step("quarantined " + item.name());
            }
            journal = publish(journal, Phase.OLD_QUARANTINED);
            for (Item item : journal.items()) {
                if (!item.hasNew()) continue;
                moveNew(staged.resolve(item.name()), active(item));
                step("installed " + item.name());
            }
            journal = publish(journal, Phase.NEW_INSTALLED);
            verifyInstalled(maintenance, journal);
            journal = publish(journal, Phase.VERIFIED);
        } catch (IOException | SQLException | RuntimeException failure) {
            rollback(journal);
            return new Result(Outcome.ROLLED_BACK, note.restoreId(),
                    "The restore stopped, and the shop was put back as it was: " + failure.getMessage());
        }
        journal = publish(journal, Phase.COMMITTED);
        finish(journal);
        return new Result(Outcome.RESTORED, note.restoreId(), "");
    }

    /** Every item the restore moves, with the fingerprint of its old version here and its new prepared version. */
    private List<Item> items(Path staged) throws IOException {
        List<String> files = new ArrayList<>(RestoreJournal.FILES);
        try (var listed = Files.list(location.directory())) {
            files.addAll(listed.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> !RestoreJournal.FILES.contains(name) && RestoreJournal.allowed(name, false)).sorted().toList());
        }
        List<Item> items = new ArrayList<>();
        for (String name : files) add(items, name, false, staged);
        for (String name : RestoreJournal.FOLDERS) add(items, name, true, staged);
        for (String path : RestorePreparation.fingerprint(staged).keySet()) {
            String top = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            if (items.stream().noneMatch(item -> item.name().equals(top) && item.hasNew())) {
                throw new RestoreException(RestoreException.Reason.STAGED_CHECK_FAILED, "The prepared restore holds an unexpected file: " + path);
            }
        }
        for (String needed : List.of("cella.db", "shop-id.txt")) {
            if (items.stream().noneMatch(item -> item.name().equals(needed) && item.hasNew())) {
                throw new RestoreException(RestoreException.Reason.STAGED_CHECK_FAILED, "The prepared restore lacks " + needed);
            }
        }
        return items;
    }

    private void add(List<Item> items, String name, boolean folder, Path staged) throws IOException {
        Path old = location.directory().resolve(name);
        Path fresh = staged.resolve(name);
        String oldFingerprint = Files.exists(old, LinkOption.NOFOLLOW_LINKS) ? fingerprint(old) : null;
        String newFingerprint = Files.exists(fresh, LinkOption.NOFOLLOW_LINKS) ? fingerprint(fresh) : null;
        if (oldFingerprint != null || newFingerprint != null) items.add(new Item(name, folder, oldFingerprint, newFingerprint));
    }

    // ----- After a kill, or when a step failed -----

    private Result recover(DataFolderCoordinator.Maintenance maintenance, RestoreJournal journal) throws IOException, SQLException {
        if (journal.phase() == Phase.COMMITTED) {
            try {
                verifyInstalled(maintenance, journal);
            } catch (IOException | SQLException failed) {
                throw needsRepair("the restore was committed, but " + failed.getMessage());
            }
            finish(journal);
            return new Result(Outcome.RESTORED, journal.restoreId(), "");
        }
        rollback(journal);
        return new Result(Outcome.ROLLED_BACK, journal.restoreId(),
                "Cella stopped during a restore, and the shop was put back as it was.");
    }

    /**
     * Puts the whole old shop back, from where each item actually is: a new version in the data folder goes
     * back to the prepared files, and an old version set aside comes back. Then every old item must match its
     * fingerprint exactly. Safe to repeat after another kill.
     */
    private void rollback(RestoreJournal journal) throws IOException {
        Path folder = restoreFolder(journal.restoreId());
        Path old = folder.resolve(OLD);
        Path staged = folder.resolve(RestorePreparation.STAGED);
        List<Item> items = new ArrayList<>(journal.items());
        Collections.reverse(items);
        for (Item item : items) {
            Path active = active(item);
            Path aside = old.resolve(item.name());
            boolean oldAside = Files.exists(aside, LinkOption.NOFOLLOW_LINKS);
            if (Files.exists(active, LinkOption.NOFOLLOW_LINKS) && (oldAside || !item.hadOld())) {
                if (!item.hasNew()) throw needsRepair("an unexpected " + item.name() + " is in the data folder");
                moveNew(active, staged.resolve(item.name()));
                step("returned " + item.name());
            }
            if (oldAside) {
                moveNew(aside, active);
                step("restored " + item.name());
            }
        }
        for (Item item : journal.items()) {
            Path active = active(item);
            boolean whole = item.hadOld()
                    ? Files.exists(active, LinkOption.NOFOLLOW_LINKS) && item.oldFingerprint().equals(fingerprint(active))
                    : !Files.exists(active, LinkOption.NOFOLLOW_LINKS);
            if (!whole) throw needsRepair("the old " + item.name() + " cannot be proved whole");
        }
        finish(publish(journal, Phase.ROLLED_BACK));
    }

    /** The installed shop: each item exactly as prepared, the restored shop's ID, and a sound, current database. */
    private void verifyInstalled(DataFolderCoordinator.Maintenance maintenance, RestoreJournal journal) throws IOException, SQLException {
        for (Item item : journal.items()) {
            Path active = active(item);
            boolean right = item.hasNew()
                    ? Files.exists(active, LinkOption.NOFOLLOW_LINKS) && item.newFingerprint().equals(fingerprint(active))
                    : !Files.exists(active, LinkOption.NOFOLLOW_LINKS);
            if (!right) throw installedFailed("the installed " + item.name() + " is not the prepared one");
        }
        String shop = Files.readString(location.shopIdFile(), StandardCharsets.UTF_8).replace("\uFEFF", "").strip();
        if (!journal.newShopId().toString().equals(shop)) throw installedFailed("the shop ID file does not name the restored shop");
        try (Connection connection = maintenance.openDatabase(); Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
                rows.next();
                if (!"ok".equals(rows.getString(1))) throw installedFailed("the database failed SQLite's integrity check");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw installedFailed("the database has broken references");
            }
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                if (rows.getInt(1) != SqliteDatabase.SCHEMA_VERSION) throw installedFailed("the database is not at this Cella's version");
            }
            if (!BackupArchive.tablesWithOtherShops(connection, journal.newShopId()).isEmpty()) {
                throw installedFailed("the database holds another shop's records");
            }
            if (count(statement, "products") != journal.products() || count(statement, "sales") != journal.sales()
                    || count(statement, "stock_movements") != journal.stockMovements()) {
                throw installedFailed("the database does not hold the backup's records");
            }
        }
    }

    /** The scheduled note goes into the restore's folder, then the journal: the restore is over. */
    private void finish(RestoreJournal journal) throws IOException {
        Path folder = restoreFolder(journal.restoreId());
        if (Files.exists(note()) && noteIsFor(journal)) {
            moveNew(note(), folder.resolve(RestorePreparation.SCHEDULED));
            step("note retired");
        }
        moveNew(location.directory().resolve(RestoreJournal.FILE), folder.resolve(RestoreJournal.FILE));
        step("journal archived");
    }

    // ----- Helpers -----

    private RestoreJournal publish(RestoreJournal journal, Phase phase) throws IOException {
        RestoreJournal next = journal.at(phase);
        next.publish(location.directory());
        step("journal " + phase);
        return next;
    }

    private void step(String step) throws IOException {
        steps.reached("RESTORE STEP " + step);
    }

    private boolean noteIsFor(RestoreJournal journal) {
        try {
            return new RestorePreparation(location, journal.newShopId(), clock).readScheduled()
                    .map(s -> s.restoreId().equals(journal.restoreId())).orElse(false);
        } catch (IOException unreadable) {
            return false;   // a note that cannot be read stays where it is, for someone to look at
        }
    }

    /**
     * The set-aside files service for the pre-restore backup's checks. During maintenance its hints cannot look
     * at the database and say "unknown"; only the decisions matter here.
     */
    private EvidenceService evidence(UUID shopId) {
        SqliteDatabase database = new SqliteDatabase(location.databaseFile());
        return new EvidenceService(location, shopId, new StockService(new SqliteStockRepository(database)),
                new SaleService(new SqliteSaleRepository(database), new SqliteProductRepository(database)),
                new SqliteProductImportRepository(database), clock);
    }

    private static UUID id(Path file) throws IOException {
        try {
            return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "").strip());
        } catch (IllegalArgumentException unreadable) {
            throw new IOException("Cannot read the ID in " + file + ". Nothing was changed.", unreadable);
        }
    }

    private Path note() {
        return location.directory().resolve(RestorePreparation.RESTORE_FOLDER).resolve(RestorePreparation.SCHEDULED);
    }

    private Path restoreFolder(UUID restoreId) {
        return location.directory().resolve(RestorePreparation.RESTORE_FOLDER).resolve(restoreId.toString());
    }

    private Path active(Item item) {
        return location.directory().resolve(item.name());
    }

    /** A rename that never replaces: the target must not exist. Within the data folder, so on one disk. */
    private static void moveNew(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw needsRepair(target.getFileName() + " is already in " + target.getParent());
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A file's SHA-256, or for a folder, the SHA-256 of every file in it with its path. */
    static String fingerprint(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw needsRepair("a restore item is a symbolic link: " + path.getFileName());
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw needsRepair("a restore item is not an ordinary file: " + path.getFileName());
            }
            return BackupService.sha256(path);
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (var file : RestorePreparation.fingerprint(path).entrySet()) {
            sha.update((file.getKey() + "\t" + file.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static int schemaVersion(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    }

    private static RestoreException installedFailed(String why) {
        return new RestoreException(RestoreException.Reason.STAGED_CHECK_FAILED, "The installed shop failed its check: " + why);
    }

    private static RestoreException needsRepair(String why) {
        return new RestoreException(RestoreException.Reason.NEEDS_REPAIR,
                "A restore cannot be finished or undone safely: " + why + ". Nothing more was moved; every file is kept.");
    }
}

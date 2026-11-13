package cm.sji.innova.desktop.backup;

import cm.sji.innova.core.persistence.DatabaseGate;

import cm.sji.innova.desktop.evidence.Evidence;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * OFF-03a3: makes one checked backup ZIP of a shop, following "Making a backup" in
 * {@code docs/design/BACKUP_RESTORE.md}. File and database work: never call it on the Swing event thread.
 *
 * <ol>
 *   <li>Enters exclusive folder maintenance (OFF-03a1), which also holds the stock, checkout and import
 *       leases, then checks under that hold that no request is saved and every set-aside file has a
 *       decision (OFF-03a2).</li>
 *   <li>Snapshots the database with SQLite {@code VACUUM INTO} through maintenance's own connection, and
 *       checks the snapshot: integrity, references, version, one shop.</li>
 *   <li>Copies the shop ID, the photos the snapshot refers to, and the set-aside files with their
 *       decisions. A missing photo fails the backup.</li>
 *   <li>Writes the ZIP under a temporary name in the destination, forces it to the disk, checks it with
 *       {@link BackupArchive} (the same checker restore uses), and only then gives it its final name,
 *       which holds part of the backup's ID. It never replaces an existing file.</li>
 *   <li>Records the last successful backup, after publication only.</li>
 * </ol>
 * A file named {@code cella-backup-*.zip} is always complete and checked. Temporary files carry the
 * backup's ID, so an interrupted one can be recognised and removed later; it is never a success.
 */
public final class BackupService {
    public static final String EVIDENCE_FOLDER = EvidenceService.DECISIONS_FOLDER;
    public static final String LAST_BACKUP = "last-backup.properties";
    /** Requests saved before submission. While one exists, its operation's result is unknown. */
    static final List<String> SAVED_REQUESTS = List.of("pending-stock-receipt.properties",
            "pending-stock-adjustment.properties", "pending-checkout.txt", "pending-csv.properties");
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final Duration DRAIN = Duration.ofSeconds(30);

    /** A finished, checked, published backup. */
    public record Backup(Path file, String sha256, UUID backupId, Instant createdAt, UUID shopId, int schemaVersion,
                         long products, long sales, long stockMovements, int photos, int evidence) { }

    private record Facts(int schemaVersion, long products, long sales, long stockMovements, List<String> photos) { }

    private record Entry(long size, String sha256) { }

    /**
     * Gives the checked archive its final name. The real one is {@link #RENAME}; tests use others to act
     * like a disk that refuses, and check that a failed publication records no success.
     */
    @FunctionalInterface
    interface Publication {
        void publish(Path partial, Path published) throws IOException;
    }

    /**
     * An atomic rename that never replaces a file. The final name holds part of the backup's ID, so no other
     * backup has it; a file that has it anyway is refused, not replaced.
     */
    static final Publication RENAME = (partial, published) -> {
        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(published.toString());
        Files.move(partial, published, StandardCopyOption.ATOMIC_MOVE);
    };

    private final LocalAppData location;
    private final UUID shopId;
    private final UUID deviceId;
    private final DataFolderCoordinator coordinator;
    private final EvidenceService evidence;
    private final Clock clock;
    private final Publication publication;

    public BackupService(LocalAppData location, UUID shopId, UUID deviceId, DataFolderCoordinator coordinator,
                         EvidenceService evidence, Clock clock) {
        this(location, shopId, deviceId, coordinator, evidence, clock, RENAME);
    }

    /** For tests: {@code publication} takes the place of the final rename. */
    BackupService(LocalAppData location, UUID shopId, UUID deviceId, DataFolderCoordinator coordinator,
                  EvidenceService evidence, Clock clock, Publication publication) {
        this.location = Objects.requireNonNull(location, "location");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    /** Makes, checks and publishes a backup in {@code destination}, then returns it. */
    public Backup create(Path destination) throws IOException, SQLException {
        Files.createDirectories(destination);
        Path target = destination.toRealPath();
        if (target.startsWith(location.directory().toRealPath())) {
            throw new BackupException(BackupException.Reason.DESTINATION_REFUSED, List.of(destination.toString()),
                    "Choose a backup folder outside the shop's data folder, ideally on a USB key or another disk.");
        }
        try (DataFolderCoordinator.Maintenance maintenance = coordinator.beginMaintenance(DRAIN)) {
            return capture(maintenance, target, true);
        }
    }

    /**
     * The pre-restore backup (OFF-03b2): made under the restore's own maintenance, into the restore's folder
     * inside the data folder, with the same checks and read-back as {@link #create}. It is not recorded as the
     * shop's last backup, because a copy on the same disk does not protect against losing that disk.
     */
    Backup createDuring(DataFolderCoordinator.Maintenance maintenance, Path folder) throws IOException, SQLException {
        Files.createDirectories(folder);
        return capture(maintenance, folder.toRealPath(), false);
    }

    private Backup capture(DataFolderCoordinator.Maintenance maintenance, Path target, boolean recordSuccess)
            throws IOException, SQLException {
        refuseUnresolvedWork();
        UUID backupId = UUID.randomUUID();
        Instant now = clock.instant();
        Path work = Files.createDirectory(target.resolve(".cella-backup-" + backupId));
        Path partial = null;
        try {
            Path database = work.resolve("cella.db");
            snapshot(maintenance, database);
            Facts facts = checkSnapshot(database);
            Files.copy(location.shopIdFile(), work.resolve("shop-id.txt"));
            for (String photo : facts.photos()) copyPhoto(photo, work);
            List<Evidence> decided = copyEvidence(work);

            Map<String, Entry> entries = describe(work);
            byte[] manifest = manifest(backupId, now, facts, decided, entries);
            Path published = target.resolve("cella-backup-" + NAME.format(now.atZone(clock.getZone())) + "-"
                    + backupId.toString().substring(0, 8) + ".zip");
            partial = target.resolve(published.getFileName() + "." + backupId + ".partial");
            writeZip(partial, manifest, work, entries);

            Path scratch = Files.createDirectory(work.resolve("check"));
            BackupArchive.Contents read = BackupArchive.check(partial, scratch);
            if (!read.backupId().equals(backupId) || !read.shopId().equals(shopId) || read.products() != facts.products()
                    || read.sales() != facts.sales() || read.stockMovements() != facts.stockMovements()) {
                throw new BackupException(BackupException.Reason.CHECK_FAILED, List.of(partial.getFileName().toString()),
                        "The backup read back does not match the snapshot");
            }
            String sha = sha256(partial);
            try {
                publication.publish(partial, published);
            } catch (AtomicMoveNotSupportedException unsafe) {
                throw new BackupException(BackupException.Reason.DESTINATION_REFUSED, List.of(target.toString()),
                        "This backup folder cannot rename files safely. Choose another folder or disk.");
            } catch (FileAlreadyExistsException taken) {
                throw new BackupException(BackupException.Reason.DESTINATION_REFUSED, List.of(published.getFileName().toString()),
                        "Another file already has this backup's name. Nothing was replaced; make the backup again.");
            }
            partial = null;
            if (recordSuccess) recordLastBackup(backupId, published, sha, now);
            return new Backup(published, sha, backupId, now, shopId, facts.schemaVersion(), facts.products(),
                    facts.sales(), facts.stockMovements(), facts.photos().size(), decided.size());
        } finally {
            if (partial != null) Files.deleteIfExists(partial);
            deleteTree(work);
        }
    }

    /** The last backup this shop published, if one was recorded. */
    public Optional<Properties> lastBackup() throws IOException {
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            Path record = location.directory().resolve(LAST_BACKUP);
            if (!Files.exists(record)) return Optional.empty();
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(record, StandardCharsets.UTF_8)) { p.load(reader); }
            return Optional.of(p);
        } catch (SQLException blocked) {
            throw new IOException("Cannot read backup metadata while the folder is paused", blocked);
        }
    }

    /** The last backup this shop published, as recorded right after its publication (OFF-03c). */
    public record LastBackup(UUID backupId, Path file, String sha256, Instant createdAt) {
        /** How long ago it was made, by {@code clock}. */
        public Duration age(Clock clock) {
            return Duration.between(createdAt, clock.instant());
        }
    }

    /**
     * The last backup this shop published, if one was recorded. A record that cannot be read counts as none:
     * a reminder then asks for a backup, which is the safe side.
     */
    public Optional<LastBackup> lastSuccess() {
        try {
            return lastBackup().map(p -> new LastBackup(UUID.fromString(p.getProperty("backupId")), Path.of(p.getProperty("file")),
                    Objects.requireNonNull(p.getProperty("sha256")), Instant.parse(p.getProperty("createdAt"))));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Under maintenance: no saved request, and a decision for every set-aside file. */
    private void refuseUnresolvedWork() throws IOException {
        List<String> saved = SAVED_REQUESTS.stream().filter(name -> Files.exists(location.directory().resolve(name))).toList();
        if (!saved.isEmpty()) {
            throw new BackupException(BackupException.Reason.REQUEST_PENDING, saved,
                    "Finish or retry the saved request first: " + String.join(", ", saved));
        }
        List<String> undecided = evidence.unresolved().stream().map(Evidence::fileName).toList();
        if (!undecided.isEmpty()) {
            throw new BackupException(BackupException.Reason.EVIDENCE_UNRESOLVED, undecided,
                    "Record a decision for each set-aside file first: " + String.join(", ", undecided));
        }
    }

    /** SQLite writes the copy in one read transaction, from maintenance's own connection, outside a transaction. */
    private static void snapshot(DataFolderCoordinator.Maintenance maintenance, Path copy) throws SQLException {
        try (Connection connection = maintenance.openDatabase(); Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + copy.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }

    /** The snapshot is self-contained, intact, consistent, not newer than this Cella, and holds only this shop. */
    private Facts checkSnapshot(Path database) throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
                rows.next();
                if (!"ok".equals(rows.getString(1))) throw checkFailed("The snapshot failed SQLite's integrity check");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw checkFailed("The snapshot has broken references");
            }
            int version;
            try (var rows = statement.executeQuery("PRAGMA user_version")) { rows.next(); version = rows.getInt(1); }
            List<String> others = BackupArchive.tablesWithOtherShops(connection, shopId);
            if (!others.isEmpty()) throw checkFailed("The snapshot holds another shop's records in " + others);
            List<String> photos = new ArrayList<>();
            try (var rows = statement.executeQuery("SELECT relative_path, thumbnail_path FROM product_photos ORDER BY product_id")) {
                while (rows.next()) {
                    photos.add(rows.getString(1));
                    photos.add(rows.getString(2));
                }
            }
            return new Facts(version, count(statement, "products"), count(statement, "sales"),
                    count(statement, "stock_movements"), List.copyOf(photos));
        }
        // Closing the last connection to the copy leaves one self-contained file, without a log beside it.
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    }

    /** Copies one photo the snapshot refers to; it must be inside the data folder's photos. */
    private void copyPhoto(String relative, Path work) throws IOException {
        Path photos = location.directory().resolve("photos").normalize();
        Path source = location.directory().resolve(relative).normalize();
        if (!relative.startsWith("photos/") || !source.startsWith(photos) || relative.split("/").length != 3) {
            throw checkFailed("The database refers to a photo outside the photos folder: " + relative);
        }
        Path copy = work.resolve(relative).normalize();
        Files.createDirectories(copy.getParent());
        try {
            Files.copy(source, copy);
        } catch (NoSuchFileException missing) {
            throw new BackupException(BackupException.Reason.PHOTO_CHANGED, List.of(relative),
                    "A photo the shop's records refer to is missing: " + relative);
        }
    }

    /** The decided set-aside files and every decision file, under recovery-evidence/. */
    private List<Evidence> copyEvidence(Path work) throws IOException {
        List<Evidence> decided = evidence.inventory();
        Path folder = work.resolve(EVIDENCE_FOLDER);
        Files.createDirectories(folder);
        for (Evidence one : decided) {
            Files.copy(location.directory().resolve(one.fileName()), folder.resolve(one.fileName()));
        }
        Path decisions = location.directory().resolve(EVIDENCE_FOLDER);
        if (Files.isDirectory(decisions)) {
            try (var files = Files.list(decisions)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".decision.properties")).toList()) {
                    Files.copy(file, folder.resolve(file.getFileName()));
                }
            }
        }
        return decided;
    }

    /** Every file in the work folder, by its path inside the ZIP, with its size and SHA-256. */
    private static Map<String, Entry> describe(Path work) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        List<Path> files;
        try (var walk = Files.walk(work)) { files = walk.filter(Files::isRegularFile).toList(); }
        for (Path file : files) {
            entries.put(work.relativize(file).toString().replace('\\', '/'), new Entry(Files.size(file), sha256(file)));
        }
        return entries;
    }

    private byte[] manifest(UUID backupId, Instant now, Facts facts, List<Evidence> decided, Map<String, Entry> entries)
            throws IOException {
        Properties p = new Properties();
        p.setProperty("format", BackupArchive.FORMAT);
        p.setProperty("backupId", backupId.toString());
        p.setProperty("schemaVersion", Integer.toString(facts.schemaVersion()));
        p.setProperty("shopId", shopId.toString());
        p.setProperty("sourceDeviceId", deviceId.toString());   // where it came from; never installed as an active ID
        p.setProperty("createdAt", now.toString());
        p.setProperty("products", Long.toString(facts.products()));
        p.setProperty("sales", Long.toString(facts.sales()));
        p.setProperty("stockMovements", Long.toString(facts.stockMovements()));
        p.setProperty("photos", Integer.toString(facts.photos().size()));
        p.setProperty("evidence", Integer.toString(decided.size()));
        for (int i = 0; i < decided.size(); i++) {
            Evidence one = decided.get(i);
            p.setProperty("evidence." + i + ".file", one.fileName());
            p.setProperty("evidence." + i + ".sha256", one.sha256());
            p.setProperty("evidence." + i + ".outcome", one.decision().outcome().name());
        }
        p.setProperty("files", Integer.toString(entries.size()));
        int i = 0;
        for (var entry : entries.entrySet()) {
            p.setProperty("file." + i + ".path", entry.getKey());
            p.setProperty("file." + i + ".size", Long.toString(entry.getValue().size()));
            p.setProperty("file." + i + ".sha256", entry.getValue().sha256());
            i++;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        p.store(bytes, "Cella backup manifest");
        // Properties writes a date comment and keys in no fixed order: keep the lines sorted, without the date.
        List<String> lines = new ArrayList<>(List.of(bytes.toString(StandardCharsets.ISO_8859_1).split("\\R")));
        lines.removeIf(line -> line.startsWith("#") && !line.equals("#Cella backup manifest"));
        lines.sort(Comparator.comparing((String line) -> !line.startsWith("#")).thenComparing(line -> line));
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void writeZip(Path partial, byte[] manifest, Path work, Map<String, Entry> entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(BackupArchive.MANIFEST));
            zip.write(manifest);
            zip.closeEntry();
            for (String name : entries.keySet()) {
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(work.resolve(name), zip);
                zip.closeEntry();
            }
        }
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
            channel.force(true);   // on the disk or USB key before it gets its final name
        }
    }

    /** Written after publication only, tied to the archive's ID and fingerprint. */
    private void recordLastBackup(UUID backupId, Path published, String sha, Instant at) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("backupId", backupId.toString());
        p.setProperty("file", published.toString());
        p.setProperty("sha256", sha);
        p.setProperty("createdAt", at.toString());
        Path record = location.directory().resolve(LAST_BACKUP);
        Path temporary = Files.createTempFile(location.directory(), ".last-backup-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                p.store(writer, "Cella: the last backup this shop published");
                writer.flush();
                channel.force(true);
            }
            Files.move(temporary, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
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

    private static BackupException checkFailed(String why) {
        return new BackupException(BackupException.Reason.CHECK_FAILED, List.of(), why);
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths;
        try (var walk = Files.walk(root)) { paths = walk.sorted(Comparator.reverseOrder()).toList(); }
        for (Path path : paths) Files.deleteIfExists(path);
    }
}

package cm.sji.innova.desktop.backup;

import cm.sji.innova.core.persistence.DatabaseGate;
import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import javax.swing.SwingUtilities;

/**
 * OFF-03b1: the first half of a restore, following "Validate and stage before touching active shop data" in
 * {@code docs/design/BACKUP_RESTORE.md}. Nothing here changes the shop. Everything it writes is under
 * {@code restore/} in the data folder, and nothing there is used until a scheduled restore runs at the next
 * start (OFF-03b2).
 *
 * <ol>
 *   <li>{@link #prepare} copies the chosen file to {@code restore/<restore ID>/archive.zip}. That is the only
 *       time the chosen file is read, so replacing it afterwards changes nothing. It unpacks the copy with
 *       {@link BackupArchive#extract} into {@code new/}, brings a backup from an older Cella up to date there
 *       only, checks the result again, and says what it holds.</li>
 *   <li>{@link #schedule}, once the person has confirmed, checks that the prepared files are exactly the ones
 *       that were summarised, forces them to the disk, and writes {@code restore/scheduled.properties} once.</li>
 * </ol>
 * Its file and database work passes through the folder's gate, so it waits while a backup or restore holds
 * the folder. Never call it on the Swing event thread.
 */
public final class RestorePreparation {
    public static final String RESTORE_FOLDER = "restore";
    public static final String SCHEDULED = "scheduled.properties";
    static final String ARCHIVE = "archive.zip";
    static final String STAGED = "new";
    private static final String FORMAT = "1";
    /** The largest payload a backup may hold, its manifest, and room for the ZIP's own records. */
    static final long MAX_ARCHIVE_BYTES = BackupArchive.MAX_TOTAL_BYTES + BackupArchive.MAX_MANIFEST_BYTES + 64L * 1024 * 1024;
    private static final long MAX_NOTE_BYTES = 16L * 1024 * 1024;

    /** What a prepared restore holds, and the SHA-256 of every prepared file, by its path under {@code new/}. */
    public record Summary(UUID restoreId, String archiveSha256, UUID shopId, boolean sameShop, UUID sourceDeviceId,
                          Instant createdAt, int backupSchemaVersion, long products, long sales, long stockMovements,
                          int photos, int evidence, Map<String, String> stagedFiles) {
        public Summary {
            stagedFiles = Map.copyOf(stagedFiles);
        }
    }

    private final LocalAppData location;
    private final UUID shopId;
    private final Clock clock;

    /** {@code shopId} is the shop open in this Cella, to say whether a backup is of the same shop. */
    public RestorePreparation(LocalAppData location, UUID shopId, Clock clock) {
        this.location = Objects.requireNonNull(location, "location");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Copies, checks, unpacks and updates the chosen backup, and says what it holds. On any failure the
     * restore's folder is removed again. Refused while a restore is scheduled.
     */
    public Summary prepare(Path chosen) throws IOException, SQLException {
        refuseTheSwingThread();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            refuseIfScheduled();
            UUID restoreId = UUID.randomUUID();
            Path folder = Files.createDirectories(root()).resolve(restoreId.toString());
            Files.createDirectory(folder);
            boolean prepared = false;
            try {
                Path archive = folder.resolve(ARCHIVE);
                String archiveSha = copy(chosen, archive);
                Path staged = Files.createDirectory(folder.resolve(STAGED));
                BackupArchive.Contents contents = BackupArchive.extract(archive, staged);
                upgrade(staged.resolve("cella.db"));
                verify(staged, contents);
                Summary summary = new Summary(restoreId, archiveSha, contents.shopId(), contents.shopId().equals(shopId),
                        contents.sourceDeviceId(), contents.createdAt(), contents.schemaVersion(), contents.products(),
                        contents.sales(), contents.stockMovements(), contents.photos(), contents.evidence().size(),
                        fingerprint(staged));
                prepared = true;
                return summary;
            } finally {
                if (!prepared) BackupService.deleteTree(folder);
            }
        }
    }

    /**
     * The person confirmed {@code summary}: schedules it for the next start. Refused if any prepared file is no
     * longer exactly as it was summarised, or if a restore is already scheduled (the note is never replaced).
     */
    public void schedule(Summary summary) throws IOException, SQLException {
        refuseTheSwingThread();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            Path folder = root().resolve(summary.restoreId().toString());
            Path archive = folder.resolve(ARCHIVE);
            Path staged = folder.resolve(STAGED);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS)
                    || !summary.archiveSha256().equals(BackupService.sha256(archive))
                    || !summary.stagedFiles().equals(fingerprint(staged))) {
                throw new RestoreException(RestoreException.Reason.STAGING_CHANGED,
                        "The prepared restore changed after it was checked. Choose the backup again.");
            }
            forceAll(folder);
            publishOnce(root().resolve(SCHEDULED), note(summary));
        }
    }

    /** The restore scheduled for the next start, if any. A note that is damaged, or for another folder, is refused. */
    public Optional<Summary> scheduled() throws IOException {
        refuseTheSwingThread();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            return readScheduled();
        } catch (SQLException blocked) {
            throw new IOException("Cannot read the scheduled restore while the folder is paused", blocked);
        }
    }

    /** Internal startup reader: the installer already holds exclusive maintenance ownership. */
    Optional<Summary> readScheduled() throws IOException {
        Path note = root().resolve(SCHEDULED);
        if (!Files.exists(note)) return Optional.empty();
        Properties p;
        try {
            p = ChecksummedProperties.read(note, MAX_NOTE_BYTES);
        } catch (IllegalArgumentException unusable) {
            throw damaged(unusable.getMessage());
        }
        try {
            if (!FORMAT.equals(p.getProperty("format"))) throw damaged("its format is unknown");
            if (!location.directory().toRealPath().toString().equals(p.getProperty("folder"))) throw damaged("it belongs to another data folder");
            Map<String, String> files = new TreeMap<>();
            int count = Integer.parseInt(p.getProperty("files"));
            for (int i = 0; i < count; i++) files.put(p.getProperty("file." + i + ".path"), p.getProperty("file." + i + ".sha256"));
            UUID shop = UUID.fromString(p.getProperty("shopId"));
            return Optional.of(new Summary(UUID.fromString(p.getProperty("restoreId")), p.getProperty("archiveSha256"), shop,
                    shop.equals(shopId), UUID.fromString(p.getProperty("sourceDeviceId")), Instant.parse(p.getProperty("createdAt")),
                    Integer.parseInt(p.getProperty("backupSchemaVersion")), Long.parseLong(p.getProperty("products")),
                    Long.parseLong(p.getProperty("sales")), Long.parseLong(p.getProperty("stockMovements")),
                    Integer.parseInt(p.getProperty("photos")), Integer.parseInt(p.getProperty("evidence")), files));
        } catch (RuntimeException unreadable) {
            throw damaged("it cannot be read: " + unreadable.getMessage());
        }
    }

    /**
     * Removes a prepared restore that was not scheduled, for example when the person cancels. Never a scheduled
     * one, and never one that ran: its folder keeps the old shop and the pre-restore backup (OFF-03b2).
     */
    public void discard(UUID restoreId) throws IOException, SQLException {
        refuseTheSwingThread();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            Optional<Summary> scheduled = scheduled();
            if (scheduled.isPresent() && scheduled.get().restoreId().equals(restoreId)) {
                throw new RestoreException(RestoreException.Reason.ALREADY_SCHEDULED, "This restore is scheduled and is not removed here.");
            }
            Path folder = root().resolve(restoreId.toString());
            if (Files.exists(location.directory().resolve(RestoreJournal.FILE)) || Files.exists(folder.resolve(RestoreJournal.FILE))
                    || Files.exists(folder.resolve(RestoreInstaller.OLD)) || Files.exists(folder.resolve(RestoreInstaller.PRE_RESTORE))) {
                throw new RestoreException(RestoreException.Reason.ALREADY_SCHEDULED,
                        "This restore has run, or is running: its folder keeps the old shop and is not removed here.");
            }
            BackupService.deleteTree(folder);
        }
    }

    /**
     * The person changed their mind before the next start (OFF-03c): the scheduled restore will not run. The
     * note goes first, so from then on nothing is scheduled; then the prepared copy is removed, unless its
     * folder also holds a pre-restore backup from an attempt that stopped, which is kept. A note that cannot be
     * read is removed too: cancelling is the way out of it. Refused once the restore has started (a journal
     * exists). Returns whether a restore was scheduled.
     */
    public boolean cancelScheduled() throws IOException, SQLException {
        refuseTheSwingThread();
        try (DatabaseGate.Work ignored = DatabaseGate.enterFolder(location.directory())) {
            if (Files.exists(location.directory().resolve(RestoreJournal.FILE))) {
                throw new RestoreException(RestoreException.Reason.ALREADY_SCHEDULED,
                        "This restore has started and can no longer be cancelled.");
            }
            Path note = root().resolve(SCHEDULED);
            if (!Files.exists(note)) return false;
            Optional<UUID> restoreId;
            try {
                restoreId = scheduled().map(Summary::restoreId);
            } catch (RestoreException unreadable) {
                restoreId = Optional.empty();   // its folder cannot be named; it stays, like any prepared restore
            }
            Files.delete(note);
            if (restoreId.isPresent()) {
                Path folder = root().resolve(restoreId.get().toString());
                if (!Files.exists(folder.resolve(RestoreInstaller.PRE_RESTORE)) && !Files.exists(folder.resolve(RestoreInstaller.OLD))) {
                    BackupService.deleteTree(folder);
                }
            }
            return true;
        }
    }

    // ----- Steps -----

    private Path root() {
        return location.directory().resolve(RESTORE_FOLDER);
    }

    private static void refuseTheSwingThread() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Never prepare a restore on the Swing event thread");
    }

    private void refuseIfScheduled() throws RestoreException {
        if (Files.exists(root().resolve(SCHEDULED)) || Files.exists(location.directory().resolve(RestoreJournal.FILE))) {
            throw new RestoreException(RestoreException.Reason.ALREADY_SCHEDULED,
                    "A restore is already scheduled for the next start of Cella.");
        }
    }

    /** The only read of the chosen file: a bounded copy, and the SHA-256 of what was copied. */
    private static String copy(Path chosen, Path archive) throws IOException {
        MessageDigest sha = digest();
        try (InputStream in = Files.newInputStream(chosen);
             OutputStream out = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                total += n;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new BackupException(BackupException.Reason.CHECK_FAILED, List.of(chosen.getFileName().toString()),
                            "This file is too large to be a Cella backup");
                }
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }
        return hex(sha.digest());
    }

    /** Brings the unpacked database up to date, as Cella does at start-up. Only this copy is changed. */
    private static void upgrade(Path database) throws RestoreException {
        try {
            new SqliteDatabase(database).initialize();
        } catch (SQLException | IOException | RuntimeException failure) {
            throw new RestoreException(RestoreException.Reason.UPGRADE_FAILED,
                    "This backup could not be brought up to date: " + failure.getMessage());
        }
    }

    /**
     * The prepared shop as it would be installed: up to date, intact, consistent, one shop, as many records as
     * the backup, every photo present, and one self-contained database file without a log beside it.
     */
    static void verify(Path staged, BackupArchive.Contents contents) throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + staged.resolve("cella.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
                rows.next();
                if (!"ok".equals(rows.getString(1))) throw stagedFailed("it failed SQLite's integrity check");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw stagedFailed("it has broken references");
            }
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                if (rows.getInt(1) != SqliteDatabase.SCHEMA_VERSION) throw stagedFailed("it is not up to date");
            }
            List<String> others = BackupArchive.tablesWithOtherShops(connection, contents.shopId());
            if (!others.isEmpty()) throw stagedFailed("it holds another shop's records in " + others);
            if (count(statement, "products") != contents.products() || count(statement, "sales") != contents.sales()
                    || count(statement, "stock_movements") != contents.stockMovements()) {
                throw stagedFailed("it does not hold the backup's records");
            }
            Path photos = staged.resolve("photos").toAbsolutePath().normalize();
            try (var rows = statement.executeQuery("SELECT relative_path, thumbnail_path FROM product_photos")) {
                while (rows.next()) {
                    for (int column = 1; column <= 2; column++) {
                        Path photo = staged.toAbsolutePath().resolve(rows.getString(column)).normalize();
                        if (!photo.startsWith(photos) || !Files.isRegularFile(photo)) throw stagedFailed("a photo is missing: " + rows.getString(column));
                    }
                }
            }
            statement.execute("PRAGMA journal_mode = DELETE");   // one self-contained file, as it will be installed
        }
        for (String log : List.of("cella.db-wal", "cella.db-shm", "cella.db-journal")) {
            if (Files.exists(staged.resolve(log))) throw stagedFailed("a log file is left beside its database: " + log);
        }
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    }

    /** Every prepared file, by its path under {@code new/} with forward slashes, and its SHA-256. */
    static Map<String, String> fingerprint(Path staged) throws IOException {
        Map<String, String> files = new TreeMap<>();
        List<Path> found;
        if (!Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw stagedFailed("a prepared folder is missing or is a symbolic link");
        }
        try (var walk = Files.walk(staged)) { found = walk.toList(); }
        for (Path file : found) {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                throw stagedFailed("a prepared path is not an ordinary file or folder: " + file.getFileName());
            }
            if (attributes.isRegularFile()) {
                files.put(staged.relativize(file).toString().replace('\\', '/'), BackupService.sha256(file));
            }
        }
        return files;
    }

    /** Puts every prepared file on the disk before the restore is scheduled. */
    private static void forceAll(Path folder) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(folder)) { files = walk.filter(Files::isRegularFile).toList(); }
        for (Path file : files) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
        }
    }

    /** The note, in the format of every restore record: sorted lines, then a SHA-256 of all of them. */
    private byte[] note(Summary s) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", FORMAT);
        p.setProperty("folder", location.directory().toRealPath().toString());
        p.setProperty("restoreId", s.restoreId().toString());
        p.setProperty("archiveSha256", s.archiveSha256());
        p.setProperty("shopId", s.shopId().toString());
        p.setProperty("sourceDeviceId", s.sourceDeviceId().toString());
        p.setProperty("createdAt", s.createdAt().toString());
        p.setProperty("backupSchemaVersion", Integer.toString(s.backupSchemaVersion()));
        p.setProperty("products", Long.toString(s.products()));
        p.setProperty("sales", Long.toString(s.sales()));
        p.setProperty("stockMovements", Long.toString(s.stockMovements()));
        p.setProperty("photos", Integer.toString(s.photos()));
        p.setProperty("evidence", Integer.toString(s.evidence()));
        p.setProperty("scheduledAt", clock.instant().toString());
        p.setProperty("files", Integer.toString(s.stagedFiles().size()));
        int i = 0;
        for (var file : new TreeMap<>(s.stagedFiles()).entrySet()) {
            p.setProperty("file." + i + ".path", file.getKey());
            p.setProperty("file." + i + ".sha256", file.getValue());
            i++;
        }
        return ChecksummedProperties.write(p, "Cella: a restore scheduled for the next start");
    }

    /** Writes the note completely, then gives it its name in one step that never replaces an existing note. */
    private static void publishOnce(Path note, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(note.getParent(), ".scheduled-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.createLink(note, temporary);   // atomic, and refused if a note is already there
            } catch (FileAlreadyExistsException taken) {
                throw new RestoreException(RestoreException.Reason.ALREADY_SCHEDULED, "A restore is already scheduled for the next start of Cella.");
            } catch (UnsupportedOperationException | FileSystemException unsupported) {
                throw new RestoreException(RestoreException.Reason.FOLDER_UNSUPPORTED,
                        "This data folder cannot record a restore safely: " + unsupported.getMessage());
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static RestoreException stagedFailed(String why) {
        return new RestoreException(RestoreException.Reason.STAGED_CHECK_FAILED, "The updated copy of the backup cannot be used: " + why);
    }

    private static RestoreException damaged(String why) {
        return new RestoreException(RestoreException.Reason.NOTE_DAMAGED,
                "The note that schedules a restore cannot be used: " + why + ". Nothing was changed.");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}

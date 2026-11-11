package cm.sji.innova.desktop.backup;

import cm.sji.innova.core.persistence.SqliteDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads and checks a Cella backup ZIP. The backup checks every archive with it before giving it its final
 * name, and restore checks and unpacks a chosen file with it ({@link #extract}) before touching anything.
 * Nothing here trusts the archive: every entry name, size and fingerprint is checked, and every read is
 * bounded.
 */
public final class BackupArchive {
    public static final String MANIFEST = "manifest.properties";
    public static final String FORMAT = "1";
    public static final int MAX_ENTRIES = 50_000;
    public static final long MAX_MANIFEST_BYTES = 8L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024;

    /** What a checked archive holds, read from its manifest and confirmed against its payload. */
    public record Contents(UUID backupId, UUID shopId, UUID sourceDeviceId, Instant createdAt, int schemaVersion,
                           long products, long sales, long stockMovements, int photos, List<String> evidence,
                           Map<String, String> files) { }

    private BackupArchive() { }

    /**
     * Checks the archive completely. {@code scratch} is an empty folder for the extracted database copy;
     * the caller removes it afterwards. Throws {@link BackupException} with {@code CHECK_FAILED} on any
     * problem.
     */
    public static Contents check(Path archive, Path scratch) throws IOException {
        return read(archive, scratch, false);
    }

    /**
     * Checks the archive as {@link #check} does and unpacks every listed file into {@code folder}, an empty
     * folder: {@code cella.db}, {@code shop-id.txt}, {@code photos/...} and {@code recovery-evidence/...}. Each
     * file is written only under its checked name, and compared with its size and SHA-256 as it is written.
     * After a refusal the folder may hold part of the files: the caller removes it.
     */
    public static Contents extract(Path archive, Path folder) throws IOException {
        return read(archive, folder, true);
    }

    private static Contents read(Path archive, Path folder, boolean everything) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Map<String, ZipEntry> entries = entries(zip);
            Properties manifest = manifest(zip, entries);
            Map<String, String> listed = listed(manifest);
            if (!listed.keySet().equals(withoutManifest(entries.keySet()))) {
                throw refused("The archive holds files its manifest does not list, or lacks listed ones");
            }
            UUID shopId = uuid(manifest, "shopId");
            long total = 0;
            for (var item : listed.entrySet()) {
                if (item.getKey().startsWith("photos/") && !item.getKey().startsWith("photos/" + shopId + "/")) {
                    throw refused("A photo belongs to another shop: " + item.getKey());
                }
                long size = Long.parseLong(manifest.getProperty(item.getValue() + ".size"));
                if (size < 0 || size > MAX_ENTRY_BYTES) throw refused("An entry is too large: " + item.getKey());
                total += size;
                if (total > MAX_TOTAL_BYTES) throw refused("The archive is too large");
                String sha = manifest.getProperty(item.getValue() + ".sha256");
                ZipEntry entry = entries.get(item.getKey());
                OutputStream copy = everything || item.getKey().equals("cella.db")
                        ? create(folder, item.getKey()) : OutputStream.nullOutputStream();
                try (InputStream in = zip.getInputStream(entry); copy) {
                    String found = digest(in, size, copy);
                    if (!found.equals(sha)) throw refused("An entry does not match its fingerprint: " + item.getKey());
                }
            }
            byte[] shopIdBytes;
            try (InputStream in = zip.getInputStream(entries.get("shop-id.txt"))) { shopIdBytes = bounded(in, 1024); }
            if (!shopId.toString().equals(new String(shopIdBytes, StandardCharsets.UTF_8).replace("\uFEFF", "").strip())) {
                throw refused("The shop ID file does not match the manifest");
            }
            Contents contents = new Contents(uuid(manifest, "backupId"), shopId, uuid(manifest, "sourceDeviceId"),
                    Instant.parse(required(manifest, "createdAt")), Integer.parseInt(required(manifest, "schemaVersion")),
                    Long.parseLong(required(manifest, "products")), Long.parseLong(required(manifest, "sales")),
                    Long.parseLong(required(manifest, "stockMovements")), Integer.parseInt(required(manifest, "photos")),
                    evidence(manifest, listed.keySet()), Map.copyOf(listedShas(manifest, listed)));
            checkDatabase(folder.resolve("cella.db"), contents, listed.keySet());
            return contents;
        } catch (BackupException refused) {
            throw refused;
        } catch (IOException | RuntimeException | SQLException damaged) {
            throw new BackupException(BackupException.Reason.CHECK_FAILED, List.of(archive.getFileName().toString()),
                    "The backup cannot be used: " + damaged.getMessage());
        }
    }

    /** Every entry, after refusing unsafe or duplicate names and folders. */
    private static Map<String, ZipEntry> entries(ZipFile zip) throws BackupException {
        Map<String, ZipEntry> entries = new HashMap<>();
        Set<String> folded = new HashSet<>();
        int count = 0;
        for (var it = zip.entries(); it.hasMoreElements(); ) {
            ZipEntry entry = it.nextElement();
            if (++count > MAX_ENTRIES) throw refused("The archive has too many entries");
            String name = entry.getName();
            if (!safeName(name) || entry.isDirectory()) throw refused("Unsafe entry name: " + name);
            if (!folded.add(name.toLowerCase(Locale.ROOT)) || entries.put(name, entry) != null) {
                throw refused("Two entries have the same name: " + name);
            }
        }
        if (!entries.containsKey(MANIFEST) || !entries.containsKey("cella.db") || !entries.containsKey("shop-id.txt")) {
            throw refused("The archive lacks its manifest, database or shop ID");
        }
        return entries;
    }

    /** Only the known structure, relative, without "..", drive letters, alternate streams or backslashes. */
    static boolean safeName(String name) {
        if (name.isEmpty() || name.length() > 400 || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.contains("\0")) return false;
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) return false;
        }
        return name.equals(MANIFEST) || name.equals("cella.db") || name.equals("shop-id.txt")
                || (name.startsWith("photos/") && name.split("/").length == 3)
                || (name.startsWith(BackupService.EVIDENCE_FOLDER + "/") && name.split("/").length == 2);
    }

    /** A new file for a checked entry name, never outside {@code folder} and never over an existing file. */
    private static OutputStream create(Path folder, String name) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || file.equals(root)) throw refused("Unsafe entry name: " + name);
        Files.createDirectories(file.getParent());
        return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW);
    }

    private static Properties manifest(ZipFile zip, Map<String, ZipEntry> entries) throws IOException {
        Properties manifest = new Properties();
        try (InputStream in = zip.getInputStream(entries.get(MANIFEST))) {
            manifest.load(new java.io.ByteArrayInputStream(bounded(in, MAX_MANIFEST_BYTES)));
        }
        if (!FORMAT.equals(manifest.getProperty("format"))) throw refused("Unknown backup format");
        return manifest;
    }

    /** Each listed path, with the manifest key prefix that describes it. */
    private static Map<String, String> listed(Properties manifest) throws BackupException {
        int files = Integer.parseInt(required(manifest, "files"));
        if (files < 0 || files > MAX_ENTRIES) throw refused("Invalid file count");
        Map<String, String> listed = new HashMap<>();
        for (int i = 0; i < files; i++) {
            String path = required(manifest, "file." + i + ".path");
            if (path.equals(MANIFEST) || !safeName(path) || listed.put(path, "file." + i) != null) {
                throw refused("Invalid listed file: " + path);
            }
            required(manifest, "file." + i + ".size");
            required(manifest, "file." + i + ".sha256");
        }
        return listed;
    }

    private static Map<String, String> listedShas(Properties manifest, Map<String, String> listed) {
        Map<String, String> shas = new HashMap<>();
        for (var item : listed.entrySet()) shas.put(item.getKey(), manifest.getProperty(item.getValue() + ".sha256"));
        return shas;
    }

    private static List<String> evidence(Properties manifest, Set<String> payload) throws BackupException {
        int count = Integer.parseInt(required(manifest, "evidence"));
        List<String> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String file = required(manifest, "evidence." + i + ".file");
            if (!payload.contains(BackupService.EVIDENCE_FOLDER + "/" + file)) throw refused("Listed evidence is missing: " + file);
            files.add(file);
        }
        return List.copyOf(files);
    }

    /** The copied database: intact, consistent, not newer than this Cella, one shop, matching the manifest. */
    private static void checkDatabase(Path database, Contents contents, Set<String> payload) throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
                rows.next();
                if (!"ok".equals(rows.getString(1))) throw refused("The database failed SQLite's integrity check");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw refused("The database has broken references");
            }
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                int version = rows.getInt(1);
                if (version != contents.schemaVersion()) throw refused("The database version does not match the manifest");
                if (version > SqliteDatabase.SCHEMA_VERSION) throw refused("The backup comes from a newer Cella");
            }
            List<String> others = tablesWithOtherShops(connection, contents.shopId());
            if (!others.isEmpty()) throw refused("The database holds another shop's records in " + others);
            if (count(statement, "products") != contents.products() || count(statement, "sales") != contents.sales()
                    || count(statement, "stock_movements") != contents.stockMovements()) {
                throw refused("The database counts do not match the manifest");
            }
            int photos = 0;
            try (var rows = statement.executeQuery("SELECT relative_path, thumbnail_path FROM product_photos")) {
                while (rows.next()) {
                    for (int column = 1; column <= 2; column++) {
                        if (!payload.contains(rows.getString(column))) throw refused("A photo is missing: " + rows.getString(column));
                        photos++;
                    }
                }
            }
            if (photos != contents.photos()) throw refused("The photo count does not match the manifest");
        }
    }

    /**
     * Every table with a {@code shop_id} column that holds a row of another shop. Used on the backup's
     * snapshot and on an archive's database, so a backup only ever holds one shop.
     */
    static List<String> tablesWithOtherShops(Connection connection, UUID shopId) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT m.name FROM sqlite_master m JOIN pragma_table_info(m.name) c"
                     + " WHERE m.type = 'table' AND c.name = 'shop_id' ORDER BY m.name")) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        List<String> others = new ArrayList<>();
        for (String table : tables) {
            // The name comes from the file itself, so it is quoted, never trusted.
            try (PreparedStatement other = connection.prepareStatement(
                    "SELECT count(*) FROM \"" + table.replace("\"", "\"\"") + "\" WHERE shop_id IS NOT ?")) {
                other.setString(1, shopId.toString());
                try (var rows = other.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) != 0) others.add(table);
                }
            }
        }
        return others;
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    }

    /** Reads the whole stream, but stops and refuses as soon as it passes {@code limit} bytes. */
    private static byte[] bounded(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            total += n;
            if (total > limit) throw refused("An entry is larger than allowed");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** SHA-256 of exactly {@code size} bytes; more or fewer is refused. Also copies them to {@code copy}. */
    private static String digest(InputStream in, long size, OutputStream copy) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            total += n;
            if (total > size) throw refused("An entry is larger than its manifest says");
            sha.update(buffer, 0, n);
            copy.write(buffer, 0, n);
        }
        if (total != size) throw refused("An entry is smaller than its manifest says");
        return HexFormat.of().formatHex(sha.digest());
    }

    private static Set<String> withoutManifest(Set<String> names) {
        Set<String> payload = new HashSet<>(names);
        payload.remove(MANIFEST);
        return payload;
    }

    private static String required(Properties manifest, String key) throws BackupException {
        String value = manifest.getProperty(key);
        if (value == null || value.isBlank()) throw refused("The manifest lacks " + key);
        return value;
    }

    private static UUID uuid(Properties manifest, String key) throws BackupException {
        return UUID.fromString(required(manifest, key));
    }

    private static BackupException refused(String why) {
        return new BackupException(BackupException.Reason.CHECK_FAILED, List.of(), "The backup cannot be used: " + why);
    }
}

package cm.sji.innova.desktop.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Tests only: copies of a real backup with one thing changed, to prove each check made on an archive. */
final class ArchiveEdits {
    private ArchiveEdits() { }

    /** A copy of {@code backup} written to {@code changed}, its entries changed by {@code change} (the manifest is an entry too). */
    static Path rewrite(Path backup, Path changed, Consumer<Map<String, byte[]>> change) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                try (InputStream in = zip.getInputStream(entry)) { entries.put(entry.getName(), in.readAllBytes()); }
            }
        }
        change.accept(entries);
        try (OutputStream file = Files.newOutputStream(changed); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return changed;
    }

    /** Lists every entry again in the manifest with its real size and SHA-256, after {@code edit}. */
    static void relist(Map<String, byte[]> entries, Consumer<Properties> edit) {
        try {
            Properties manifest = new Properties();
            manifest.load(new ByteArrayInputStream(entries.get("manifest.properties")));
            manifest.stringPropertyNames().stream().filter(key -> key.startsWith("file.")).forEach(manifest::remove);
            int i = 0;
            for (var entry : entries.entrySet()) {
                if (entry.getKey().equals("manifest.properties")) continue;
                manifest.setProperty("file." + i + ".path", entry.getKey());
                manifest.setProperty("file." + i + ".size", Integer.toString(entry.getValue().length));
                manifest.setProperty("file." + i + ".sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())));
                i++;
            }
            manifest.setProperty("files", Integer.toString(i));
            edit.accept(manifest);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manifest.store(out, null);
            entries.put("manifest.properties", out.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The bytes of {@code database} after running each statement of {@code sql} on a copy of it in {@code scratch}. */
    static byte[] editDatabase(byte[] database, Path scratch, String... sql) {
        try {
            Path copy = Files.createTempFile(scratch, "edit-", ".db");
            Files.write(copy, database);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy); var statement = connection.createStatement()) {
                for (String one : sql) statement.execute(one);
            }
            return Files.readAllBytes(copy);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

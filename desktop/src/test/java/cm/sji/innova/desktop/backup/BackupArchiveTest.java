package cm.sji.innova.desktop.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.core.persistence.SqliteProductPhotoRepository;
import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03a3: the archive checker that the backup uses before publishing, and restore (OFF-03b) will use
 * before touching anything. Each damaged archive is made from a real backup.
 */
class BackupArchiveTest {
    @TempDir Path directory;

    private DataFolderCoordinator.Membership membership;
    private AppContext shop;
    private Path good;
    private int scratches;

    @BeforeEach
    void aRealBackup() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("data"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        membership = coordinator.join();
        shop = AppContextLoader.load(location);
        Product rice = shop.products().create(shop.shopId(), "Rice", 1_000);
        shop.products().create(shop.shopId(), "Oil", 1_500);
        Path picture = directory.resolve("rice.png");
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
        new ProductPhotoService(new SqliteProductPhotoRepository(new SqliteDatabase(location.databaseFile())), location.directory())
                .setPhoto(shop.shopId(), rice.id(), picture);
        EvidenceService evidence = new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), Clock.systemUTC());
        good = new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidence, Clock.systemUTC())
                .create(directory.resolve("usb")).file();
    }

    @AfterEach
    void leave() throws Exception { membership.close(); }

    @Test
    void aGoodBackupPassesAndSaysWhatItHolds() throws Exception {
        BackupArchive.Contents contents = BackupArchive.check(good, scratch());

        assertEquals(shop.shopId(), contents.shopId());
        assertEquals(shop.deviceId(), contents.sourceDeviceId());
        assertEquals(2, contents.products());
        assertEquals(2, contents.photos());
        assertEquals(List.of(), contents.evidence());
    }

    @Test
    void aChangedByteIsRefused() throws Exception {
        String photo = photoEntry();
        Path changedPhoto = rewrite(entries -> {
            byte[] bytes = entries.get(photo).clone();
            bytes[bytes.length / 2] ^= 1;
            entries.put(photo, bytes);
        });
        assertRefused(changedPhoto, "fingerprint");   // only its SHA-256 can tell: a photo has no other check

        Path changedId = rewrite(entries -> {
            byte[] id = entries.get("shop-id.txt").clone();
            id[0] = (byte) (id[0] == 'a' ? 'b' : 'a');
            entries.put("shop-id.txt", id);
        });
        assertRefused(changedId, "fingerprint");
    }

    @Test
    void anEntryTheManifestDoesNotListOrAListedEntryThatIsMissingIsRefused() throws Exception {
        String photo = photoEntry();
        assertRefused(rewrite(entries -> entries.put(photo.replace(".png", "-extra.png"), new byte[]{1, 2, 3})), "does not list");
        assertRefused(rewrite(entries -> entries.remove(photo)), "lacks listed");
    }

    @Test
    void unsafeOrDuplicateNamesAreRefused() throws Exception {
        for (String unsafe : List.of("../cella.db", "/cella.db", "C:/cella.db", "photos\\a\\b.png", "photos/a/../b.png",
                "photos/../b.png", "photos/./b.png", "photos/a/..", "recovery-evidence/..", "recovery-evidence/.",
                "cella.db:hidden", "photos/a/b/c.png", "elsewhere/file", "photos/a/b.png.", "photos/a/b.png ", "recovery-evidence/x/y")) {
            assertFalse(BackupArchive.safeName(unsafe), unsafe);
        }
        assertTrue(BackupArchive.safeName("photos/" + shop.shopId() + "/a.png"));
        assertRefused(rewrite(entries -> entries.put("../outside.txt", new byte[]{1})), "Unsafe entry name");

        String photo = photoEntry();
        String shouted = photo.substring(0, photo.lastIndexOf('/') + 1) + photo.substring(photo.lastIndexOf('/') + 1).toUpperCase();
        // Listed in the manifest too, so only the case check can stop it.
        assertRefused(rewrite(entries -> {
            entries.put(shouted, entries.get(photo));
            relist(entries, manifest -> { });
        }), "same name");
    }

    @Test
    void aBackupFromANewerCellaIsRefused() throws Exception {
        Path newer = rewrite(entries -> {
            entries.put("cella.db", editDatabase(entries.get("cella.db"), "PRAGMA user_version = 99"));
            relist(entries, manifest -> manifest.setProperty("schemaVersion", "99"));
        });
        assertRefused(newer, "newer Cella");
    }

    @Test
    void aDatabaseThatDoesNotMatchItsManifestIsRefused() throws Exception {
        Path counts = rewrite(entries -> relist(entries, manifest -> manifest.setProperty("products", "3")));
        assertRefused(counts, "counts");
        Path otherShop = rewrite(entries -> relist(entries, manifest -> manifest.setProperty("shopId", UUID.randomUUID().toString())));
        assertRefused(otherShop, "shop ID");

        Path anotherShopsSale = rewrite(entries -> {
            entries.put("cella.db", editDatabase(entries.get("cella.db"), "INSERT INTO sales (id, shop_id, receipt_number, device_id,"
                    + " payment_method, total_amount, created_at) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                    + "', 'REC-9', '" + UUID.randomUUID() + "', 'cash', 0, '2026-11-11T00:00:00Z')"));
            relist(entries, manifest -> manifest.setProperty("sales", "1"));   // the count matches, so only the shop check can tell
        });
        assertRefused(anotherShopsSale, "another shop's records in [sales]");
    }

    @Test
    void aDamagedManifestOrSomethingThatIsNotABackupIsRefused() throws Exception {
        assertRefused(rewrite(entries -> relist(entries, manifest -> manifest.setProperty("format", "2"))), "format");
        assertRefused(rewrite(entries -> entries.remove("manifest.properties")), "lacks its manifest");
        Path notZip = directory.resolve("notes.zip");
        Files.writeString(notZip, "not a zip at all");
        assertRefused(notZip, "cannot be used");
    }

    // ----- Helpers -----

    private void assertRefused(Path archive, String why) throws Exception {
        BackupException refused = assertThrows(BackupException.class, () -> BackupArchive.check(archive, scratch()));
        assertEquals(BackupException.Reason.CHECK_FAILED, refused.reason());
        assertTrue(refused.getMessage().contains(why), refused.getMessage());
    }

    private Path scratch() throws Exception {
        return Files.createDirectories(directory.resolve("scratch-" + (++scratches)));
    }

    private String photoEntry() throws Exception {
        try (ZipFile zip = new ZipFile(good.toFile())) {
            return zip.stream().map(ZipEntry::getName).filter(name -> name.startsWith("photos/")).findFirst().orElseThrow();
        }
    }

    /** A copy of the good backup with its entries changed by {@code change} (the manifest is an entry too). */
    private Path rewrite(Consumer<Map<String, byte[]>> change) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(good.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                try (InputStream in = zip.getInputStream(entry)) { entries.put(entry.getName(), in.readAllBytes()); }
            }
        }
        change.accept(entries);
        Path changed = directory.resolve("changed-" + UUID.randomUUID() + ".zip");
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
    private static void relist(Map<String, byte[]> entries, Consumer<Properties> edit) {
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

    private byte[] editDatabase(byte[] database, String sql) {
        try {
            Path copy = Files.createTempFile(directory, "edit-", ".db");
            Files.write(copy, database);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy); var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return Files.readAllBytes(copy);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

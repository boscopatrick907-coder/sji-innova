package cm.sji.innova.desktop.backup;

import cm.sji.innova.desktop.evidence.EvidenceKind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * OFF-03b2: the durable record of one restore while it moves files, kept as {@code restore-journal.properties}
 * in the data folder, outside every item it moves. It lists each item (a file or folder directly in the data
 * folder) with the fingerprint of its old and its new version, so a restart can tell where each one is even
 * after a kill between two moves. Each phase replaces the whole file in one atomic step, and the file carries
 * its own SHA-256. Only the names below are ever accepted from it.
 */
record RestoreJournal(UUID restoreId, Phase phase, UUID oldShopId, UUID newShopId, long products, long sales,
                      long stockMovements, String preRestoreBackup, String preRestoreSha256, List<Item> items) {
    static final String FILE = "restore-journal.properties";
    private static final String FORMAT = "1";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final String NONE = "-";
    /** The files a restore moves, besides the old shop's set-aside files. */
    static final List<String> FILES = List.of("cella.db", "cella.db-wal", "cella.db-shm", "cella.db-journal", "shop-id.txt",
            BackupService.LAST_BACKUP);
    /** The folders a restore moves. */
    static final List<String> FOLDERS = List.of("photos", BackupService.EVIDENCE_FOLDER);

    /** {@code PREPARED → OLD_QUARANTINED → NEW_INSTALLED → VERIFIED → COMMITTED}, or {@code ROLLED_BACK}. */
    enum Phase { PREPARED, OLD_QUARANTINED, NEW_INSTALLED, VERIFIED, COMMITTED, ROLLED_BACK }

    /** One item: its name in the data folder, and the fingerprint of its old and new version ({@code null}: none). */
    record Item(String name, boolean folder, String oldFingerprint, String newFingerprint) {
        boolean hadOld() { return oldFingerprint != null; }
        boolean hasNew() { return newFingerprint != null; }
    }

    RestoreJournal {
        items = List.copyOf(items);
    }

    RestoreJournal at(Phase next) {
        return new RestoreJournal(restoreId, next, oldShopId, newShopId, products, sales, stockMovements,
                preRestoreBackup, preRestoreSha256, items);
    }

    /** A name a journal may hold: one of the fixed files or folders, or a set-aside file's plain name. */
    static boolean allowed(String name, boolean folder) {
        if (folder) return FOLDERS.contains(name);
        return FILES.contains(name) || (name.matches("[A-Za-z0-9._-]+") && EvidenceKind.of(name).isPresent());
    }

    /** Replaces the journal in {@code dataFolder} with this one, in one atomic step, after forcing it to the disk. */
    void publish(Path dataFolder) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", FORMAT);
        p.setProperty("folder", dataFolder.toRealPath().toString());
        p.setProperty("restoreId", restoreId.toString());
        p.setProperty("phase", phase.name());
        p.setProperty("oldShopId", oldShopId.toString());
        p.setProperty("newShopId", newShopId.toString());
        p.setProperty("products", Long.toString(products));
        p.setProperty("sales", Long.toString(sales));
        p.setProperty("stockMovements", Long.toString(stockMovements));
        p.setProperty("preRestoreBackup", preRestoreBackup);
        p.setProperty("preRestoreSha256", preRestoreSha256);
        p.setProperty("items", Integer.toString(items.size()));
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            p.setProperty("item." + i + ".name", item.name());
            p.setProperty("item." + i + ".folder", Boolean.toString(item.folder()));
            p.setProperty("item." + i + ".old", item.hadOld() ? item.oldFingerprint() : NONE);
            p.setProperty("item." + i + ".new", item.hasNew() ? item.newFingerprint() : NONE);
        }
        byte[] bytes = ChecksummedProperties.write(p, "Cella: a restore in progress");
        Path temporary = Files.createTempFile(dataFolder, ".restore-journal-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, dataFolder.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The journal of a restore in progress in {@code dataFolder}, if any. One that cannot be trusted is refused. */
    static Optional<RestoreJournal> read(Path dataFolder) throws IOException {
        Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) return Optional.empty();
        Properties p;
        try {
            p = ChecksummedProperties.read(file, MAX_BYTES);
        } catch (IllegalArgumentException unusable) {
            throw damaged(unusable.getMessage());
        }
        try {
            if (!FORMAT.equals(p.getProperty("format"))) throw damaged("its format is unknown");
            if (!dataFolder.toRealPath().toString().equals(p.getProperty("folder"))) throw damaged("it belongs to another data folder");
            int count = Integer.parseInt(p.getProperty("items"));
            if (count < 1 || count > 10_000) throw damaged("its list of items is invalid");
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String name = p.getProperty("item." + i + ".name");
                boolean folder = Boolean.parseBoolean(p.getProperty("item." + i + ".folder"));
                if (name == null || !allowed(name, folder)) throw damaged("it names an item a restore never moves: " + name);
                items.add(new Item(name, folder, fingerprint(p, "item." + i + ".old"), fingerprint(p, "item." + i + ".new")));
            }
            return Optional.of(new RestoreJournal(UUID.fromString(p.getProperty("restoreId")), Phase.valueOf(p.getProperty("phase")),
                    UUID.fromString(p.getProperty("oldShopId")), UUID.fromString(p.getProperty("newShopId")),
                    Long.parseLong(p.getProperty("products")), Long.parseLong(p.getProperty("sales")),
                    Long.parseLong(p.getProperty("stockMovements")), p.getProperty("preRestoreBackup"),
                    p.getProperty("preRestoreSha256"), items));
        } catch (RuntimeException unreadable) {
            throw damaged("it cannot be read: " + unreadable.getMessage());
        }
    }

    private static String fingerprint(Properties p, String key) throws RestoreException {
        String value = p.getProperty(key);
        if (NONE.equals(value)) return null;
        if (value == null || !value.matches("[0-9a-f]{64}")) throw damaged("a fingerprint is invalid");
        return value;
    }

    private static RestoreException damaged(String why) {
        return new RestoreException(RestoreException.Reason.JOURNAL_DAMAGED,
                "The journal of a restore in progress cannot be used: " + why + ". Nothing was moved; every file is kept.");
    }
}

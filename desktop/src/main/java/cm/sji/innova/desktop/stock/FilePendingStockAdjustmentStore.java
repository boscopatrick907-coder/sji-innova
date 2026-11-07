package cm.sji.innova.desktop.stock;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Atomic file-backed store. Uses the same directory lease as pending receipts. */
public final class FilePendingStockAdjustmentStore implements PendingStockAdjustmentStore {
    private static final String FORMAT = "1";
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final String COMMENT = "Cella: a stock adjustment awaiting confirmation. Do not edit.";
    private final Path file;

    public FilePendingStockAdjustmentStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override public Path file() { return file; }

    public static final class ConflictException extends IOException {
        public ConflictException() { super("A different stock operation is pending; recover it first"); }
    }

    @Override public Optional<PendingStockAdjustment> load() throws IOException {
        try (var ignored = PendingReceiptFileLease.acquire(file)) { return loadLocked(); }
    }

    @Override public void save(PendingStockAdjustment adjustment) throws IOException {
        Objects.requireNonNull(adjustment, "adjustment");
        try (var ignored = PendingReceiptFileLease.acquire(file)) {
            Optional<PendingStockAdjustment> existing = loadLocked();
            if (existing.isPresent()) {
                if (!existing.get().equals(adjustment)) throw new ConflictException();
                return;
            }
            writeNewLocked(adjustment);
        }
    }

    @Override public void clear(UUID expectedOperationId) throws IOException {
        Objects.requireNonNull(expectedOperationId, "expectedOperationId");
        try (var ignored = PendingReceiptFileLease.acquire(file)) {
            Optional<PendingStockAdjustment> existing = loadLocked();
            if (existing.isEmpty()) return;
            if (!existing.get().operationId().equals(expectedOperationId)) throw new ConflictException();
            Files.delete(file);
        }
    }

    private Optional<PendingStockAdjustment> loadLocked() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Pending adjustment file is too large; original preserved");
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            if (!FORMAT.equals(required(p, "format"))) throw new IOException("Unknown pending adjustment format");
            return Optional.of(new PendingStockAdjustment(
                    UUID.fromString(required(p, "operationId")), UUID.fromString(required(p, "shopId")),
                    UUID.fromString(required(p, "productId")), UUID.fromString(required(p, "deviceId")),
                    required(p, "unit"), Long.parseLong(required(p, "deltaMilli")), required(p, "reason")));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Pending stock adjustment file is damaged; original preserved", ex);
        }
    }

    private void writeNewLocked(PendingStockAdjustment a) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("Pending adjustment path has no parent directory");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, file.getFileName() + "-", ".tmp");
        try {
            Properties p = new Properties();
            p.setProperty("format", FORMAT);
            p.setProperty("operationId", a.operationId().toString());
            p.setProperty("shopId", a.shopId().toString());
            p.setProperty("productId", a.productId().toString());
            p.setProperty("deviceId", a.deviceId().toString());
            p.setProperty("unit", a.unit());
            p.setProperty("deltaMilli", Long.toString(a.deltaMilli()));
            p.setProperty("reason", a.reason());
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                p.store(writer, COMMENT);
                writer.flush();
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing pending adjustment value: " + key);
        return value;
    }
}

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

/** File-backed durable store for a single pending stock receipt. All methods must run off the EDT. */
public final class FilePendingReceiptStore implements PendingReceiptStore {
    private static final String FORMAT = "1";
    private static final String COMMENT = "Cella: a stock receipt awaiting confirmation. Do not edit.";
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private final Path file;

    public FilePendingReceiptStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override public Path file() { return file; }

    public static final class BusyException extends IOException {
        public BusyException() { super("Another Cella window is using the pending stock receipt file"); }
        public BusyException(Throwable cause) { super("Another Cella window is using the pending stock receipt file", cause); }
    }

    public static final class ConflictException extends IOException {
        public ConflictException() { super("A different stock receipt is pending; recover it before starting another"); }
    }

    @Override public Optional<PendingReceipt> load() throws IOException {
        try (var ignored = PendingReceiptFileLease.acquire(file)) {
            return loadLocked();
        }
    }

    @Override public void save(PendingReceipt receipt) throws IOException {
        Objects.requireNonNull(receipt, "receipt");
        try (var ignored = PendingReceiptFileLease.acquire(file)) {
            Optional<PendingReceipt> existing = loadLocked();
            if (existing.isPresent()) {
                if (!existing.get().equals(receipt)) throw new ConflictException();
                return; // Identical retry: keep the original durable record unchanged.
            }
            writeNewLocked(receipt);
        }
    }

    @Override public void clear(UUID expectedOperationId) throws IOException {
        Objects.requireNonNull(expectedOperationId, "expectedOperationId");
        try (var ignored = PendingReceiptFileLease.acquire(file)) {
            Optional<PendingReceipt> current = loadLocked();
            if (current.isEmpty()) return; // Idempotent cleanup after an earlier successful clear.
            if (!current.get().operationId().equals(expectedOperationId)) throw new ConflictException();
            Files.delete(file);
        }
    }

    /** Moves a damaged record aside without deleting it. Valid records are never moved by this action. */
    public Path setAsideDamaged() throws IOException {
        try (var ignored = PendingReceiptFileLease.acquire(file)) {
            if (!Files.exists(file)) throw new IOException("There is no pending receipt file to preserve");
            try {
                loadLocked();
                throw new ConflictException();
            } catch (ConflictException validRecord) {
                throw validRecord;
            } catch (IOException damaged) {
                Path preserved = file.resolveSibling("pending-stock-receipt-damaged-" + UUID.randomUUID() + ".properties");
                return Files.move(file, preserved, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    private Optional<PendingReceipt> loadLocked() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Pending stock receipt file is too large; original file was preserved");

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            if (!FORMAT.equals(required(properties, "format"))) throw new IOException("Unknown pending stock receipt format");
            PendingReceipt receipt = new PendingReceipt(
                    UUID.fromString(required(properties, "operationId")),
                    UUID.fromString(required(properties, "shopId")),
                    UUID.fromString(required(properties, "productId")),
                    UUID.fromString(required(properties, "deviceId")),
                    required(properties, "unit"),
                    Long.parseLong(required(properties, "quantityMilli")),
                    required(properties, "reason"));
            return Optional.of(receipt);
        } catch (IllegalArgumentException | ArithmeticException damaged) {
            throw new IOException("Pending stock receipt file is damaged; original file was preserved", damaged);
        }
    }

    private void writeNewLocked(PendingReceipt receipt) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("Pending receipt path has no parent directory");
        Path temporary = Files.createTempFile(parent, file.getFileName() + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                toProperties(receipt).store(writer, COMMENT);
                writer.flush();
                channel.force(true);
            }
            // Do not replace an existing file: save() already established that none exists under the lease.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Properties toProperties(PendingReceipt receipt) {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("operationId", receipt.operationId().toString());
        properties.setProperty("shopId", receipt.shopId().toString());
        properties.setProperty("productId", receipt.productId().toString());
        properties.setProperty("deviceId", receipt.deviceId().toString());
        properties.setProperty("unit", receipt.unit());
        properties.setProperty("quantityMilli", Long.toString(receipt.quantityMilli()));
        properties.setProperty("reason", receipt.reason());
        return properties;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing pending receipt value: " + key);
        return value;
    }
}

package cm.sji.innova.desktop.sales;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Keeps the one sale being sent in a small text file, so its details survive a crash or a restart.
 * The format is described in docs/desktop/SALES_SCREEN.md.
 *
 * <p>Saving writes a temporary file, forces it onto the disk, then moves it over the real file in
 * one step: an interrupted process leaves either the old file or the new one, never half a file. Call these
 * methods off the Swing event thread.
 */
public final class PendingCheckoutStore {
    private static final String FORMAT = "1";
    private static final String COMMENT = "Cella: a cash sale waiting for confirmation. Do not edit.";

    private final Path file;

    public PendingCheckoutStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public Path file() { return file; }

    public static final class BusyException extends IOException {
        public BusyException(){super("Another checkout is using this data folder");}
        public BusyException(Throwable cause){super("Another checkout is using this data folder",cause);}
    }
    public static final class ConflictException extends IOException {
        public ConflictException(){super("A different checkout is pending; recover it before starting another");}
    }
    CheckoutFileLease acquire() throws IOException { return CheckoutFileLease.acquire(file); }

    public void save(PendingCheckout pending) throws IOException {
        try(var lease=acquire()) {
            Optional<PendingCheckout> existing=load();
            if(existing.isPresent()) {
                if(!existing.get().equals(pending))throw new ConflictException();
                return; // Identical retry; never rewrite a good recovery record unnecessarily.
            }
            saveNew(pending);
        }
    }
    private void saveNew(PendingCheckout pending)throws IOException {
        Path temporary = Files.createTempFile(file.getParent(),file.getFileName()+"-", ".tmp");
        try {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
            toProperties(pending).store(writer, COMMENT);
            writer.flush();
            channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * @return the saved sale, or empty if there is none
     * @throws IOException if the file cannot be read or is damaged; the file is left untouched
     */
    public Optional<PendingCheckout> load() throws IOException {
        try(var lease=acquire()) { return loadLocked(); }
    }
    private Optional<PendingCheckout> loadLocked() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file)>4*1024*1024)throw new IOException("The unfinished sale file is too large");
        Properties saved = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            saved.load(reader);
            return Optional.of(fromProperties(saved));
        } catch (IllegalArgumentException | ArithmeticException damaged) {
            throw new IOException("The unfinished sale file is damaged: " + damaged.getMessage(), damaged);
        }
    }

    /** Compatibility for manual recovery/tests. Checkout always uses the owner-aware overload. */
    public void clear() throws IOException {
        try(var lease=acquire()){Files.deleteIfExists(file);}
    }
    public void clear(UUID expectedSaleId) throws IOException {
        try(var lease=acquire()) {
            var current=load();
            if(current.isPresent()&&!current.get().saleId().equals(expectedSaleId))throw new ConflictException();
            Files.deleteIfExists(file);
        }
    }
    void requireOwner(PendingCheckout expected) throws IOException {
        var current=load();
        if(current.isPresent()&&!current.get().equals(expected))throw new ConflictException();
    }

    /** Keeps a damaged file under a new name, so nothing is deleted, and returns that name. */
    public Path setAside() throws IOException {
        try(var lease=acquire()) {
            boolean readable=false;
            try{readable=load().isPresent();}catch(IOException damaged){/* preserve under a new name below */}
            if(readable)throw new ConflictException(); // A stale damaged-page action must not move a new valid request.
            String name = "pending-checkout-damaged-" + UUID.randomUUID() + ".txt";
            return Files.move(file, file.resolveSibling(name));
        }
    }

    private static Properties toProperties(PendingCheckout pending) {
        Properties saved = new Properties();
        saved.setProperty("format", FORMAT);
        saved.setProperty("saleId", pending.saleId().toString());
        saved.setProperty("shopId", pending.shopId().toString());
        saved.setProperty("deviceId", pending.deviceId().toString());
        saved.setProperty("receiptNumber", pending.receiptNumber());
        saved.setProperty("lineCount", String.valueOf(pending.lines().size()));
        for (int i = 0; i < pending.lines().size(); i++) {
            BasketLine line = pending.lines().get(i);
            String prefix = "line." + i + ".";
            saved.setProperty(prefix + "productId", line.productId().toString());
            saved.setProperty(prefix + "label", line.label());
            saved.setProperty(prefix + "reference", line.reference());
            saved.setProperty(prefix + "unit", line.unit());
            saved.setProperty(prefix + "quantityMilli", String.valueOf(line.quantityMilli()));
            saved.setProperty(prefix + "unitPrice", String.valueOf(line.unitPrice()));
        }
        return saved;
    }

    private static PendingCheckout fromProperties(Properties saved) throws IOException {
        if (!FORMAT.equals(saved.getProperty("format"))) throw new IOException("Unknown or missing format");
        int lineCount = Integer.parseInt(required(saved, "lineCount"));
        if(lineCount<1||lineCount>10000)throw new IOException("Invalid checkout line count");
        List<BasketLine> lines = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            String prefix = "line." + i + ".";
            lines.add(new BasketLine(
                    UUID.fromString(required(saved, prefix + "productId")),
                    required(saved, prefix + "label"),
                    required(saved, prefix + "reference"),
                    required(saved, prefix + "unit"),
                    Long.parseLong(required(saved, prefix + "quantityMilli")),
                    Long.parseLong(required(saved, prefix + "unitPrice"))));
        }
        return new PendingCheckout(
                UUID.fromString(required(saved, "saleId")),
                UUID.fromString(required(saved, "shopId")),
                UUID.fromString(required(saved, "deviceId")),
                required(saved, "receiptNumber"),
                lines);
    }

    private static String required(Properties saved, String key) throws IOException {
        String value = saved.getProperty(key);
        if (value == null) throw new IOException("Missing value: " + key);
        return value;
    }
}

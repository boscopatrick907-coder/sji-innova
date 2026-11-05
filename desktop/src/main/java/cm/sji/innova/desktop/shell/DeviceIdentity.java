package cm.sji.innova.desktop.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * The development device ID: one UUID for this computer's copy of Cella (one per data folder).
 * Stock movements and sales record it, so each operation can be traced to the computer that made it.
 * It is NOT the shop ID and NOT a user login.
 *
 * <p>It is created the first time Cella starts and then reused on every launch.
 * Unlike the shop ID, a MISSING file is simply created again, even next to an existing database:
 * products, stock and sales belong to the shop, not to the device, so nothing becomes invisible.
 * A DAMAGED file stops start-up and is never replaced, so nobody loses the old ID by accident.
 */
public final class DeviceIdentity {
    private DeviceIdentity() { }

    public static UUID loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) return read(file);
        Files.createDirectories(file.getParent());
        UUID id = UUID.randomUUID();
        try {
            // CREATE_NEW refuses to overwrite a file that already exists.
            Files.writeString(file, id + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return id;
        } catch (FileAlreadyExistsException createdByAnotherStart) {
            return read(file);
        }
    }

    private static UUID read(Path file) throws IOException {
        // Notepad may add an invisible "byte order mark" (\uFEFF) at the start: ignore it.
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "").strip();
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnId) {
            throw new IOException("The device identity file is damaged and was left untouched: " + file
                    + ". Fix it, or move it away yourself: Cella then creates a new device ID at the next start.",
                    notAnId);
        }
    }
}

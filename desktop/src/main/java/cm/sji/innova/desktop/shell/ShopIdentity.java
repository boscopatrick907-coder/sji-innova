package cm.sji.innova.desktop.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * The temporary development shop ID. It is NOT a login and NOT a shop registration.
 *
 * <p>It is created the first time Cella starts and then reused on every launch, so the
 * products saved yesterday still belong to "this shop" today.
 * A damaged file is reported and never replaced: replacing it would hide the old data.
 */
public final class ShopIdentity {
    private ShopIdentity() { }

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
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("﻿", "").strip();
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnId) {
            throw new IOException("The shop identity file is damaged and was left untouched: " + file
                    + ". Fix it, or move it away yourself if you accept to start a new shop identity.",
                    notAnId);
        }
    }
}

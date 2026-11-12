package cm.sji.innova.desktop.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * The small records a restore keeps (the scheduled note, the journal): properties with their lines sorted, no
 * date comment, and a last line {@code checksum=} holding the SHA-256 of everything before it. A record whose
 * checksum does not match is never trusted.
 */
final class ChecksummedProperties {
    private ChecksummedProperties() { }

    static byte[] write(Properties p, String comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        p.store(bytes, comment);
        List<String> lines = new ArrayList<>(List.of(bytes.toString(StandardCharsets.ISO_8859_1).split("\\R")));
        lines.removeIf(line -> line.startsWith("#") && !line.equals("#" + comment));
        lines.sort(Comparator.comparing((String line) -> !line.startsWith("#")).thenComparing(line -> line));
        String body = String.join("\n", lines) + "\n";
        return (body + "checksum=" + sha256(body) + "\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * The properties in {@code file}, read at most {@code maxBytes}. Throws {@link IllegalArgumentException}
     * saying why when the file is too large or its checksum does not match.
     */
    static Properties read(Path file, long maxBytes) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) { bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1)); }
        if (bytes.length > maxBytes) throw new IllegalArgumentException("it is too large");
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int last = text.lastIndexOf('\n', text.length() - 2);
        String body = text.substring(0, last + 1);
        if (!text.endsWith("\n") || !text.substring(last + 1).equals("checksum=" + sha256(body) + "\n")) {
            throw new IllegalArgumentException("its checksum does not match");
        }
        Properties p = new Properties();
        p.load(new StringReader(body));
        return p;
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

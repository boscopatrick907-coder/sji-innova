package cm.sji.innova.core.product.importer;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict, robust RFC-4180 compliant CSV parser.
 *
 * <p>Features:
 * <ul>
 *   <li>Supports comma (,) and semicolon (;) delimiters.</li>
 *   <li>Handles UTF-8 Byte Order Mark (BOM: \uFEFF).</li>
 *   <li>Handles multiline fields with embedded newlines (\r\n and \n).</li>
 *   <li>Handles escaped double quotes ("").</li>
 *   <li>Tracks 1-based physical line numbers for accurate error reporting.</li>
 * </ul>
 */
public final class Rfc4180CsvReader {

    public record CsvRecord(int lineNumber, List<String> fields, String rawText) {}

    private Rfc4180CsvReader() { }

    public static List<CsvRecord> readAll(Reader reader, Character explicitDelimiter) throws IOException {
        List<CsvRecord> records = new ArrayList<>();
        char delimiter = explicitDelimiter != null ? explicitDelimiter : ',';

        PushbackReader in = new PushbackReader(reader);
        int firstChar = in.read();
        if (firstChar == -1) {
            return records;
        }

        // Strip UTF-8 BOM if present at start of stream
        if (firstChar != 0xFEFF) {
            in.unread(firstChar);
        }

        // Auto-detect delimiter if not explicitly specified
        if (explicitDelimiter == null) {
            delimiter = detectDelimiter(in);
        }

        StringBuilder field = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        List<String> currentFields = new ArrayList<>();

        int currentLine = 1;
        int recordStartLine = 1;
        boolean inQuotes = false;
        boolean afterQuote = false;
        boolean recordHasData = false;

        int ch;
        while ((ch = in.read()) != -1) {
            char c = (char) ch;
            raw.append(c);

            if (inQuotes) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') {
                        // Escaped quote: "" -> "
                        raw.append('"');
                        field.append('"');
                    } else {
                        // Closing quote
                        if (next != -1) {
                            in.unread(next);
                        }
                        inQuotes = false;
                        afterQuote = true;
                    }
                } else {
                    if (c == '\r') {
                        int next = in.read();
                        if (next == '\n') {
                            raw.append('\n');
                        } else if (next != -1) {
                            in.unread(next);
                        }
                        currentLine++;
                        field.append('\n');
                    } else if (c == '\n') {
                        currentLine++;
                        field.append('\n');
                    } else {
                        field.append(c);
                    }
                }
                recordHasData = true;
            } else {
                if (c == '"') {
                    if (afterQuote) {
                        throw new CsvParseException("Unexpected quote character after closing quote at line " + currentLine);
                    }
                    if (field.length() > 0 && !field.toString().isBlank()) {
                        throw new CsvParseException("Quote character inside unquoted field at line " + currentLine);
                    }
                    inQuotes = true;
                    recordHasData = true;
                } else if (c == delimiter) {
                    currentFields.add(cleanField(field, afterQuote));
                    field.setLength(0);
                    afterQuote = false;
                    recordHasData = true;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r') {
                        int next = in.read();
                        if (next == '\n') {
                            raw.append('\n');
                        } else if (next != -1) {
                            in.unread(next);
                        }
                    }

                    if (recordHasData || !currentFields.isEmpty()) {
                        currentFields.add(cleanField(field, afterQuote));
                        records.add(new CsvRecord(recordStartLine, List.copyOf(currentFields), raw.toString().trim()));
                        currentFields.clear();
                        field.setLength(0);
                        raw.setLength(0);
                        recordHasData = false;
                        afterQuote = false;
                    }
                    currentLine++;
                    recordStartLine = currentLine;
                } else {
                    if (afterQuote && !Character.isWhitespace(c)) {
                        throw new CsvParseException("Unexpected character '" + c + "' after closing quote at line " + currentLine);
                    }
                    field.append(c);
                    if (!Character.isWhitespace(c)) {
                        recordHasData = true;
                    }
                }
            }
        }

        if (inQuotes) {
            throw new CsvParseException("Unclosed quoted field starting at line " + recordStartLine);
        }

        if (recordHasData || !currentFields.isEmpty()) {
            currentFields.add(cleanField(field, afterQuote));
            records.add(new CsvRecord(recordStartLine, List.copyOf(currentFields), raw.toString().trim()));
        }

        return records;
    }

    private static String cleanField(StringBuilder sb, boolean wasQuoted) {
        String val = sb.toString();
        return wasQuoted ? val : val.trim();
    }

    private static char detectDelimiter(PushbackReader in) throws IOException {
        StringBuilder sample = new StringBuilder();
        int ch;
        int count = 0;
        while ((ch = in.read()) != -1 && count < 2048) {
            sample.append((char) ch);
            count++;
            if (ch == '\n') {
                break;
            }
        }

        for (int i = sample.length() - 1; i >= 0; i--) {
            in.unread(sample.charAt(i));
        }

        String line = sample.toString();
        long commas = line.chars().filter(c -> c == ',').count();
        long semicolons = line.chars().filter(c -> c == ';').count();

        return semicolons > commas ? ';' : ',';
    }

    private static final class PushbackReader {
        private final Reader reader;
        private final int[] buf = new int[4096];
        private int pos = 0;

        PushbackReader(Reader reader) {
            this.reader = reader;
        }

        int read() throws IOException {
            if (pos > 0) {
                return buf[--pos];
            }
            return reader.read();
        }

        void unread(int c) {
            if (pos >= buf.length) {
                throw new IllegalStateException("Pushback buffer overflow");
            }
            buf[pos++] = c;
        }
    }
}

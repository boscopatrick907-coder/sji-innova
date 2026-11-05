package cm.sji.innova.core.product.importer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parsed representation of one data line in the CSV file.
 *
 * @param rowNumber 1-based index (row 1 is the first data row after the header)
 * @param rawLine Original line text for debugging/UI preview
 * @param name Product name
 * @param modelReference Optional SKU or reference code (normalized to null if blank)
 * @param category Optional category (normalized to null if blank)
 * @param unit Canonical unit: "piece", "kg", "litre", or "bag"
 * @param buyingPrice Optional buying price in whole FCFA (null if blank)
 * @param sellingPrice Required selling price in whole FCFA
 * @param errors Validation errors that prevent importing this row
 * @param warnings Potential issues such as candidate duplicates in the file or existing catalog
 */
public record ParsedRow(
        int rowNumber,
        String rawLine,
        String name,
        String modelReference,
        String category,
        String unit,
        Long buyingPrice,
        Long sellingPrice,
        List<String> errors,
        List<String> warnings
) {
    public ParsedRow {
        rawLine = Objects.requireNonNull(rawLine, "rawLine");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * Computes a deterministic SHA-256 hash of the normalized product fields for duplicate tracking.
     */
    public String rowHash() {
        return ImportFingerprint.hash(java.util.Arrays.asList(
                normalized(name),normalized(modelReference),normalized(category),normalized(unit),
                buyingPrice==null?null:buyingPrice.toString(),sellingPrice==null?null:sellingPrice.toString()));
    }
    private static String normalized(String value) {
        return value==null?null:value.strip().toLowerCase(Locale.ROOT);
    }
}

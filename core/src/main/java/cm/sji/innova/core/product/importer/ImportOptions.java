package cm.sji.innova.core.product.importer;

/**
 * Options for CSV parsing and duplicate handling.
 *
 * @param delimiter Explicit delimiter (',' or ';'), or null for auto-detection
 * @param allowDuplicates If true, duplicate candidates are not automatically skipped
 */
public record ImportOptions(
        Character delimiter,
        boolean allowDuplicates
) {
    public static final ImportOptions DEFAULT = new ImportOptions(null, false);
}

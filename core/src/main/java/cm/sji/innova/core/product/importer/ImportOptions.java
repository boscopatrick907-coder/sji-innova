package cm.sji.innova.core.product.importer;

/**
 * Options for CSV parsing and duplicate handling.
 *
 * @param delimiter Explicit delimiter (',' or ';'), or null for auto-detection
 * @param allowDuplicates Suggested UI preference only; confirmed requests must explicitly grant duplicate consent
 */
public record ImportOptions(
        Character delimiter,
        boolean allowDuplicates
) {
    public ImportOptions {
        if(delimiter!=null && delimiter!=',' && delimiter!=';')throw new IllegalArgumentException("Delimiter must be comma or semicolon");
    }
    public static final ImportOptions DEFAULT = new ImportOptions(null, false);
}

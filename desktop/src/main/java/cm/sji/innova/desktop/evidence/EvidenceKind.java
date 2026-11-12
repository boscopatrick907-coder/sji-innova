package cm.sji.innova.desktop.evidence;

import java.util.List;
import java.util.Optional;

/**
 * The kinds of set-aside file Cella creates (OFF-02b). Each is a saved request a screen could not use,
 * renamed in the data folder instead of deleted.
 */
public enum EvidenceKind {
    STOCK_RECEIPT(".properties", "pending-stock-receipt-damaged-", "pending-stock-receipt-set-aside-"),
    STOCK_CORRECTION(".properties", "pending-stock-adjustment-damaged-", "pending-stock-adjustment-set-aside-"),
    CASH_SALE(".txt", "pending-checkout-damaged-"),
    CSV_IMPORT(".properties", "pending-csv-set-aside-");

    private final String suffix;
    private final List<String> prefixes;

    EvidenceKind(String suffix, String... prefixes) {
        this.suffix = suffix;
        this.prefixes = List.of(prefixes);
    }

    /** The kind of set-aside file with this name, if it is one. Only plain file names match. */
    public static Optional<EvidenceKind> of(String fileName) {
        if (fileName.contains("/") || fileName.contains("\\")) return Optional.empty();
        for (EvidenceKind kind : values()) {
            for (String prefix : kind.prefixes) {
                if (fileName.startsWith(prefix) && fileName.endsWith(kind.suffix)
                        && fileName.length() > prefix.length() + kind.suffix.length()) {
                    return Optional.of(kind);
                }
            }
        }
        return Optional.empty();
    }

    /** True if the file was set aside because it could not be read, not because it could not be used. */
    public static boolean damagedWhenSetAside(String fileName) {
        return fileName.contains("-damaged-");
    }
}

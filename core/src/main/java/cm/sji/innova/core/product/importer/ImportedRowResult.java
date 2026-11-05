package cm.sji.innova.core.product.importer;

import java.util.UUID;

/**
 * Outcome of an individual row during confirmed import.
 */
public record ImportedRowResult(
        int rowNumber,
        UUID productId,
        Status status,
        String message
) {
    public enum Status {
        SUCCESS,
        SKIPPED,
        FAILED
    }
}

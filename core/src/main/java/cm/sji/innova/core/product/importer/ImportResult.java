package cm.sji.innova.core.product.importer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of a confirmed import operation.
 */
public record ImportResult(
        UUID operationId,
        UUID shopId,
        int importedCount,
        int skippedCount,
        int failedCount,
        List<ImportedRowResult> rowResults,
        boolean replayed
) {
    public ImportResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(shopId, "shopId");
        rowResults = List.copyOf(Objects.requireNonNull(rowResults, "rowResults"));
    }
}

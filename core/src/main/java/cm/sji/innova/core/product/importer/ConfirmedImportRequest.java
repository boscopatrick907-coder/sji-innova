package cm.sji.innova.core.product.importer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reviewed request containing user-confirmed row selections.
 *
 * @param operationId Client-generated idempotency token (safe across retries)
 * @param previewId Identifier of the reviewed preview
 * @param contentHash SHA-256 hash of the previewed content, to prevent importing changed file contents
 * @param selectedRowNumbers List of 1-based row numbers explicitly confirmed by the user
 */
public record ConfirmedImportRequest(
        UUID operationId,
        UUID previewId,
        String contentHash,
        List<Integer> selectedRowNumbers
) {
    public ConfirmedImportRequest {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(previewId, "previewId must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        selectedRowNumbers = List.copyOf(Objects.requireNonNull(selectedRowNumbers, "selectedRowNumbers must not be null"));
    }
}

package cm.sji.innova.core.product.importer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable preview result returned by the import service.
 *
 * <p>Calling preview performs zero database writes and provides structured
 * row diagnostics and candidate duplicate warnings for Patrick's UI.
 */
public record ImportPreview(
        UUID previewId,
        UUID shopId,
        int totalRows,
        int validRows,
        int invalidRows,
        int duplicateWarningRows,
        List<ParsedRow> rows,
        List<String> fileErrors,
        String contentHash
) {
    public ImportPreview {
        Objects.requireNonNull(previewId, "previewId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(contentHash, "contentHash");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        fileErrors = List.copyOf(Objects.requireNonNull(fileErrors, "fileErrors"));
    }

    public boolean canImportAny() {
        return validRows > 0 && fileErrors.isEmpty();
    }
}

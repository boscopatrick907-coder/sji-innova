package cm.sji.innova.core.product.importer;

import cm.sji.innova.core.product.Product;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for CSV product imports.
 */
public interface ProductImportRepository {

    record ExistingProductSummary(UUID id, String name, String modelReference, String reference) {}

    record ImportOperationRecord(
            UUID id,
            UUID shopId,
            String contentHash,
            int totalSelected,
            Instant createdAt
    ) {}

    record ImportedRowRecord(
            UUID operationId,
            int rowNumber,
            UUID productId,
            String rowHash,
            Instant createdAt
    ) {}

    /**
     * Checks if a row with the exact same content hash was previously imported in this shop.
     */
    boolean wasPreviouslyImported(UUID shopId, String rowHash) throws SQLException;

    /**
     * Lists existing products for candidate duplicate checking within the shop.
     */
    List<ExistingProductSummary> listExistingProducts(UUID shopId) throws SQLException;

    /**
     * Finds an import operation by its client-provided operation ID.
     */
    Optional<ImportOperationRecord> findOperation(UUID operationId) throws SQLException;

    /**
     * Records the header of an import operation.
     */
    void recordOperation(UUID operationId, UUID shopId, String contentHash, int totalSelected) throws SQLException;

    /**
     * Lists rows already committed under this operation ID.
     */
    List<ImportedRowRecord> listImportedRows(UUID operationId) throws SQLException;

    /**
     * Transactionally creates a product and records its import row mapping in the same commit.
     */
    Product insertImportedProduct(UUID operationId, int rowNumber, String rowHash, Product product) throws SQLException;
}

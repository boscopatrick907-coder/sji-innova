package cm.sji.innova.core.sale;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaleRepository {
    SaleResult recordSale(Sale sale) throws SQLException;

    /** Recheck the original request under the storage write lock when resolving concurrent retries. */
    default SaleResult recordSale(Sale sale, CashSaleRequest request) throws SQLException {
        request.requireMatches(sale);
        return recordSale(sale);
    }

    Optional<Sale> findById(UUID shopId, UUID saleId) throws SQLException;
    List<Sale> listByShop(UUID shopId) throws SQLException;

    default VoidSaleResult voidSale(VoidSaleRequest request) throws SQLException {
        throw new UnsupportedOperationException("Void operations are not supported by this repository");
    }

    default Optional<SaleState> findStateById(UUID shopId, UUID saleId) throws SQLException {
        throw new UnsupportedOperationException("Sale state queries are not supported by this repository");
    }

    default List<SaleState> listStatesByShop(UUID shopId) throws SQLException {
        throw new UnsupportedOperationException("Sale state queries are not supported by this repository");
    }

    default List<SaleEvent> listEvents(UUID shopId, UUID saleId) throws SQLException {
        throw new UnsupportedOperationException("Sale event queries are not supported by this repository");
    }

    default List<SaleAuditRecord> listAuditRecords(UUID shopId, UUID saleId) throws SQLException {
        throw new UnsupportedOperationException("Sale audit queries are not supported by this repository");
    }
}

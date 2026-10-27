package cm.sji.innova.core.sale;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaleRepository {
    SaleResult recordSale(Sale sale) throws SQLException;
    Optional<Sale> findById(UUID shopId, UUID saleId) throws SQLException;
    List<Sale> listByShop(UUID shopId) throws SQLException;
}

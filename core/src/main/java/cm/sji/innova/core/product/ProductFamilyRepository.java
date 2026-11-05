package cm.sji.innova.core.product;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface ProductFamilyRepository {
    void insert(ProductFamily family) throws SQLException;
    List<ProductFamily> findByShop(UUID shopId) throws SQLException;
    /** Assign an existing product without changing its identity, price, stock or history. */
    void assign(UUID shopId, UUID productId, UUID familyId) throws SQLException;
}

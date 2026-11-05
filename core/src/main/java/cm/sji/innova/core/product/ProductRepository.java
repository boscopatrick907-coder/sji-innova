package cm.sji.innova.core.product;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface ProductRepository {
    void insert(Product product) throws SQLException;
    /** Return persistence-assigned fields without a second, post-commit database read. */
    default Product insertAndReturn(Product product) throws SQLException {
        insert(product);
        return product;
    }
    List<Product> findByShop(UUID shopId) throws SQLException;
}

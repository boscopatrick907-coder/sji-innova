package cm.sji.innova.core.product;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface ProductRepository {
    void insert(Product product) throws SQLException;
    List<Product> findByShop(UUID shopId) throws SQLException;
}

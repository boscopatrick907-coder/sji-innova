package cm.sji.innova.core.product;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The desktop and future API can use these rules without writing SQL. */
public final class ProductService {
    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Product create(UUID shopId, String name, long unitPrice) throws SQLException {
        Instant now = Instant.now();
        Product product = new Product(UUID.randomUUID(), shopId, name, unitPrice, now, now, 1);
        repository.insert(product);
        return product;
    }

    public List<Product> list(UUID shopId) throws SQLException {
        return repository.findByShop(Objects.requireNonNull(shopId));
    }
}

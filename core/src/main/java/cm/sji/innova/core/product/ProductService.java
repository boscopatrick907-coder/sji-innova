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
        return create(shopId, name, unitPrice, null, null, null, "piece", java.util.Map.of());
    }

    public Product create(UUID shopId, String name, long sellingPrice, String modelReference,
                          Long buyingPrice, String category, String unit,
                          java.util.Map<String, String> specifications) throws SQLException {
        Instant now = Instant.now();
        Product product = new Product(UUID.randomUUID(), shopId, name, sellingPrice, now, now, 1,
                modelReference, buyingPrice, category, unit, specifications);
        repository.insert(product);
        return product;
    }

    public List<Product> list(UUID shopId) throws SQLException {
        return repository.findByShop(Objects.requireNonNull(shopId));
    }
}

package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SqliteProductRepository implements ProductRepository {
    private final SqliteDatabase database;

    public SqliteProductRepository(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(Product product) throws SQLException {
        try (var connection = database.open(); var statement = connection.prepareStatement("""
                INSERT INTO products (id, shop_id, name, unit_price, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, product.id().toString());
            statement.setString(2, product.shopId().toString());
            statement.setString(3, product.name());
            statement.setLong(4, product.unitPrice());
            statement.setString(5, product.createdAt().toString());
            statement.setString(6, product.updatedAt().toString());
            statement.setLong(7, product.version());
            statement.executeUpdate();
        }
    }

    @Override
    public List<Product> findByShop(UUID shopId) throws SQLException {
        try (var connection = database.open(); var statement = connection.prepareStatement(
                "SELECT * FROM products WHERE shop_id = ? ORDER BY name, id")) {
            statement.setString(1, shopId.toString());
            try (var result = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (result.next()) {
                    products.add(new Product(UUID.fromString(result.getString("id")),
                            UUID.fromString(result.getString("shop_id")), result.getString("name"),
                            result.getLong("unit_price"), Instant.parse(result.getString("created_at")),
                            Instant.parse(result.getString("updated_at")), result.getLong("version")));
                }
                return List.copyOf(products);
            }
        }
    }
}

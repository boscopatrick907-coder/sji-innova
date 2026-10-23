package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SqliteProductRepository implements ProductRepository {
    private final SqliteDatabase database;

    public SqliteProductRepository(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(Product product) throws SQLException {
        try (var connection = database.open()) {
            // Save the product and every specification together, or none of them.
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO products (id, shop_id, name, unit_price, created_at, updated_at,
                                              version, model_reference, cost_price, category, unit)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, product.id().toString());
                    statement.setString(2, product.shopId().toString());
                    statement.setString(3, product.name());
                    statement.setLong(4, product.unitPrice());
                    statement.setString(5, product.createdAt().toString());
                    statement.setString(6, product.updatedAt().toString());
                    statement.setLong(7, product.version());
                    statement.setString(8, product.modelReference());
                    if (product.costPrice() == null) statement.setNull(9, Types.BIGINT);
                    else statement.setLong(9, product.costPrice());
                    statement.setString(10, product.category());
                    statement.setString(11, product.unit());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                        "INSERT INTO product_specifications (product_id, name, value) VALUES (?, ?, ?)")) {
                    for (var entry : product.specifications().entrySet()) {
                        statement.setString(1, product.id().toString());
                        statement.setString(2, entry.getKey());
                        statement.setString(3, entry.getValue());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    @Override
    public List<Product> findByShop(UUID shopId) throws SQLException {
        try (var connection = database.open(); var statement = connection.prepareStatement("""
                SELECT p.*, s.name AS specification_name, s.value AS specification_value
                FROM products p LEFT JOIN product_specifications s ON s.product_id = p.id
                WHERE p.shop_id = ? ORDER BY p.name, p.id, s.name
                """)) {
            statement.setString(1, shopId.toString());
            try (var result = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                Map<UUID, Map<String, String>> specifications = new HashMap<>();
                while (result.next()) {
                    UUID id = UUID.fromString(result.getString("id"));
                    if (!specifications.containsKey(id)) {
                        specifications.put(id, new java.util.TreeMap<>());
                        long cost = result.getLong("cost_price");
                        Long buyingPrice = result.wasNull() ? null : cost;
                        products.add(new Product(id, UUID.fromString(result.getString("shop_id")),
                                result.getString("name"), result.getLong("unit_price"),
                                Instant.parse(result.getString("created_at")),
                                Instant.parse(result.getString("updated_at")), result.getLong("version"),
                                result.getString("model_reference"), buyingPrice,
                                result.getString("category"), result.getString("unit"), Map.of()));
                    }
                    String key = result.getString("specification_name");
                    if (key != null) specifications.get(id).put(key, result.getString("specification_value"));
                }
                return products.stream().map(product -> new Product(product.id(), product.shopId(),
                        product.name(), product.unitPrice(), product.createdAt(), product.updatedAt(),
                        product.version(), product.modelReference(), product.costPrice(),
                        product.category(), product.unit(), specifications.get(product.id()))).toList();
            }
        }
    }
}

package cm.sji.innova.core.persistence;

import cm.sji.innova.core.stock.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Serializes stock writes before reading the cache, preventing lost updates. */
public final class SqliteStockRepository implements StockRepository {
    private final SqliteDatabase database;

    public SqliteStockRepository(SqliteDatabase database) { this.database = Objects.requireNonNull(database); }

    @FunctionalInterface
    private interface Write<T> { T run(Connection connection) throws SQLException; }

    private <T> T write(Write<T> action) throws SQLException {
        try (var connection = database.open(); var transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                T result = action.run(connection);
                transaction.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    private static StockLevel level(ResultSet row) throws SQLException {
        return new StockLevel(UUID.fromString(row.getString("shop_id")),
                UUID.fromString(row.getString("id")), row.getString("name"), row.getString("unit"),
                row.getLong("current_stock_milli"), row.getLong("min_stock_milli"));
    }

    private StockLevel current(Connection connection, UUID shopId, UUID productId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM products WHERE shop_id = ? AND id = ?")) {
            statement.setString(1, shopId.toString());
            statement.setString(2, productId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Product not found in this shop");
                return level(rows);
            }
        }
    }

    private static void checkUnit(String unit, long quantity) {
        if ("piece".equals(unit) && quantity % 1000 != 0) {
            throw new IllegalArgumentException("Piece quantities must be whole pieces (multiples of 1000)");
        }
    }

    private static StockMovement movement(ResultSet row) throws SQLException {
        return new StockMovement(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("shop_id")),
                UUID.fromString(row.getString("product_id")), UUID.fromString(row.getString("device_id")),
                StockMovement.Type.valueOf(row.getString("type").toUpperCase(Locale.ROOT)),
                row.getLong("quantity_milli"), row.getString("reason"), Instant.parse(row.getString("created_at")));
    }

    @Override
    public StockResult record(StockMovement request) throws SQLException {
        return write(connection -> {
            // Read/validate the shop before returning any operation history.
            StockLevel before = current(connection, request.shopId(), request.productId());
            try (var statement = connection.prepareStatement("SELECT * FROM stock_movements WHERE id = ?")) {
                statement.setString(1, request.id().toString());
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        StockMovement original = movement(rows);
                        if (!original.sameOperation(request)) {
                            throw new IllegalArgumentException("Operation ID was already used with different details");
                        }
                        return new StockResult(original, before, true);
                    }
                }
            }
            checkUnit(before.unit(), request.quantityMilli());
            long after = Math.addExact(before.quantityMilli(), request.quantityMilli());
            // Manual local receipts/adjustments cannot create negative stock.
            // Future multi-device synchronization needs its own conflict-handling operation.
            if (after < 0) throw new IllegalArgumentException("Adjustment exceeds available stock");
            try (var statement = connection.prepareStatement("""
                    INSERT INTO stock_movements (id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, request.id().toString());
                statement.setString(2, request.shopId().toString());
                statement.setString(3, request.productId().toString());
                statement.setString(4, request.deviceId().toString());
                statement.setString(5, request.type().name().toLowerCase(Locale.ROOT));
                statement.setLong(6, request.quantityMilli());
                statement.setString(7, request.reason());
                statement.setString(8, request.createdAt().toString());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE products SET current_stock_milli = ? WHERE shop_id = ? AND id = ?")) {
                statement.setLong(1, after);
                statement.setString(2, request.shopId().toString());
                statement.setString(3, request.productId().toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Product disappeared during stock write");
            }
            return new StockResult(request, current(connection, request.shopId(), request.productId()), false);
        });
    }

    @Override
    public StockLevel current(UUID shopId, UUID productId) throws SQLException {
        try (var connection = database.open()) { return current(connection, shopId, productId); }
    }

    @Override
    public List<StockLevel> list(UUID shopId) throws SQLException {
        try (var connection = database.open(); var statement = connection.prepareStatement(
                "SELECT * FROM products WHERE shop_id = ? ORDER BY name, id")) {
            statement.setString(1, shopId.toString());
            try (var rows = statement.executeQuery()) {
                List<StockLevel> levels = new ArrayList<>();
                while (rows.next()) levels.add(level(rows));
                return List.copyOf(levels);
            }
        }
    }

    @Override
    public List<StockMovement> history(UUID shopId, UUID productId) throws SQLException {
        try (var connection = database.open()) {
            current(connection, shopId, productId);
            try (var statement = connection.prepareStatement("""
                    SELECT * FROM stock_movements WHERE shop_id = ? AND product_id = ? ORDER BY created_at, id
                    """)) {
                statement.setString(1, shopId.toString());
                statement.setString(2, productId.toString());
                try (var rows = statement.executeQuery()) {
                    List<StockMovement> movements = new ArrayList<>();
                    while (rows.next()) movements.add(movement(rows));
                    return List.copyOf(movements);
                }
            }
        }
    }

    @Override
    public StockLevel setMinimum(UUID shopId, UUID productId, long minimumMilli) throws SQLException {
        if (minimumMilli < 0) throw new IllegalArgumentException("Minimum stock must not be negative");
        return write(connection -> {
            StockLevel before = current(connection, shopId, productId);
            checkUnit(before.unit(), minimumMilli);
            if (before.minimumMilli() != minimumMilli) {
                try (var statement = connection.prepareStatement("SELECT version FROM products WHERE id = ?")) {
                    statement.setString(1, productId.toString());
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        long version = Math.addExact(rows.getLong(1), 1);
                        try (var update = connection.prepareStatement(
                                "UPDATE products SET min_stock_milli = ?, version = ?, updated_at = ? WHERE shop_id = ? AND id = ?")) {
                            update.setLong(1, minimumMilli);
                            update.setLong(2, version);
                            update.setString(3, Instant.now().toString());
                            update.setString(4, shopId.toString());
                            update.setString(5, productId.toString());
                            update.executeUpdate();
                        }
                    }
                }
            }
            return current(connection, shopId, productId);
        });
    }
}

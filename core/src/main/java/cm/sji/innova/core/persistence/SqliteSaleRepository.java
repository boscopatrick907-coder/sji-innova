package cm.sji.innova.core.persistence;

import cm.sji.innova.core.sale.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class SqliteSaleRepository implements SaleRepository {
    private final SqliteDatabase database;

    public SqliteSaleRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public SaleResult recordSale(Sale sale) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                // Check idempotent retry
                try (PreparedStatement check = connection.prepareStatement("SELECT * FROM sales WHERE id = ?")) {
                    check.setString(1, sale.id().toString());
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            Optional<Sale> existing = loadSale(connection, sale.shopId(), sale.id());
                            if (existing.isPresent()) {
                                connection.commit();
                                return new SaleResult(existing.get(), true);
                            }
                        }
                    }
                }

                // Process each item: verify product, check stock, record movement, decrement stock
                for (SaleItem item : sale.items()) {
                    long currentStock;
                    String unit;
                    try (PreparedStatement prodStmt = connection.prepareStatement(
                            "SELECT unit, current_stock_milli FROM products WHERE shop_id = ? AND id = ?")) {
                        prodStmt.setString(1, sale.shopId().toString());
                        prodStmt.setString(2, item.productId().toString());
                        try (ResultSet rs = prodStmt.executeQuery()) {
                            if (!rs.next()) throw new IllegalArgumentException("Product not found: " + item.productId());
                            unit = rs.getString("unit");
                            currentStock = rs.getLong("current_stock_milli");
                        }
                    }

                    if ("piece".equals(unit) && item.quantityMilli() % 1000 != 0) {
                        throw new IllegalArgumentException("Piece quantities must be whole pieces (multiples of 1000)");
                    }

                    if (currentStock < item.quantityMilli()) {
                        throw new IllegalStateException("Insufficient stock for product " + item.productName()
                                + ": available=" + currentStock + ", required=" + item.quantityMilli());
                    }

                    long afterStock = Math.subtractExact(currentStock, item.quantityMilli());

                    // Record negative stock movement
                    UUID movementId = UUID.randomUUID();
                    try (PreparedStatement movStmt = connection.prepareStatement("""
                            INSERT INTO stock_movements (id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at)
                            VALUES (?, ?, ?, ?, 'sale', ?, ?, ?)
                            """)) {
                        movStmt.setString(1, movementId.toString());
                        movStmt.setString(2, sale.shopId().toString());
                        movStmt.setString(3, item.productId().toString());
                        movStmt.setString(4, sale.deviceId().toString());
                        movStmt.setLong(5, -item.quantityMilli()); // NEGATIVE quantity for sale
                        movStmt.setString(6, "Sale " + sale.receiptNumber());
                        movStmt.setString(7, sale.createdAt().toString());
                        movStmt.executeUpdate();
                    }

                    // Update product cached stock
                    try (PreparedStatement updateStmt = connection.prepareStatement(
                            "UPDATE products SET current_stock_milli = ? WHERE shop_id = ? AND id = ?")) {
                        updateStmt.setLong(1, afterStock);
                        updateStmt.setString(2, sale.shopId().toString());
                        updateStmt.setString(3, item.productId().toString());
                        if (updateStmt.executeUpdate() != 1) {
                            throw new SQLException("Failed to update product stock");
                        }
                    }
                }

                // Insert sale header
                try (PreparedStatement saleStmt = connection.prepareStatement("""
                        INSERT INTO sales (id, shop_id, receipt_number, device_id, payment_method, total_amount, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    saleStmt.setString(1, sale.id().toString());
                    saleStmt.setString(2, sale.shopId().toString());
                    saleStmt.setString(3, sale.receiptNumber());
                    saleStmt.setString(4, sale.deviceId().toString());
                    saleStmt.setString(5, sale.paymentMethod().code());
                    saleStmt.setLong(6, sale.totalAmount());
                    saleStmt.setString(7, sale.createdAt().toString());
                    saleStmt.executeUpdate();
                }

                // Insert sale items
                try (PreparedStatement itemStmt = connection.prepareStatement("""
                        INSERT INTO sale_items (id, sale_id, product_id, product_name, unit, quantity_milli, unit_price, subtotal)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (SaleItem item : sale.items()) {
                        itemStmt.setString(1, item.id().toString());
                        itemStmt.setString(2, sale.id().toString());
                        itemStmt.setString(3, item.productId().toString());
                        itemStmt.setString(4, item.productName());
                        itemStmt.setString(5, item.unit());
                        itemStmt.setLong(6, item.quantityMilli());
                        itemStmt.setLong(7, item.unitPrice());
                        itemStmt.setLong(8, item.subtotal());
                        itemStmt.addBatch();
                    }
                    itemStmt.executeBatch();
                }

                statement.execute("COMMIT");
                connection.commit();
                return new SaleResult(sale, false);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @Override
    public Optional<Sale> findById(UUID shopId, UUID saleId) throws SQLException {
        try (Connection connection = database.open()) {
            return loadSale(connection, shopId, saleId);
        }
    }

    @Override
    public List<Sale> listByShop(UUID shopId) throws SQLException {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, shop_id, receipt_number, device_id, payment_method, total_amount, created_at
                     FROM sales
                     WHERE shop_id = ?
                     ORDER BY created_at DESC, id ASC
                     """)) {
            statement.setString(1, shopId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                List<Sale> sales = new ArrayList<>();
                while (rs.next()) {
                    UUID saleId = UUID.fromString(rs.getString("id"));
                    List<SaleItem> items = loadSaleItems(connection, saleId);
                    sales.add(new Sale(saleId, shopId, rs.getString("receipt_number"),
                            UUID.fromString(rs.getString("device_id")),
                            PaymentMethod.fromCode(rs.getString("payment_method")),
                            rs.getLong("total_amount"),
                            Instant.parse(rs.getString("created_at")),
                            items));
                }
                return sales;
            }
        }
    }

    private Optional<Sale> loadSale(Connection connection, UUID shopId, UUID saleId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                SELECT id, shop_id, receipt_number, device_id, payment_method, total_amount, created_at
                FROM sales
                WHERE shop_id = ? AND id = ?
                """)) {
            stmt.setString(1, shopId.toString());
            stmt.setString(2, saleId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                List<SaleItem> items = loadSaleItems(connection, saleId);
                return Optional.of(new Sale(saleId, shopId, rs.getString("receipt_number"),
                        UUID.fromString(rs.getString("device_id")),
                        PaymentMethod.fromCode(rs.getString("payment_method")),
                        rs.getLong("total_amount"),
                        Instant.parse(rs.getString("created_at")),
                        items));
            }
        }
    }

    private List<SaleItem> loadSaleItems(Connection connection, UUID saleId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                SELECT id, sale_id, product_id, product_name, unit, quantity_milli, unit_price, subtotal
                FROM sale_items
                WHERE sale_id = ?
                """)) {
            stmt.setString(1, saleId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<SaleItem> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new SaleItem(UUID.fromString(rs.getString("id")), saleId,
                            UUID.fromString(rs.getString("product_id")),
                            rs.getString("product_name"),
                            rs.getString("unit"),
                            rs.getLong("quantity_milli"),
                            rs.getLong("unit_price"),
                            rs.getLong("subtotal")));
                }
                return items;
            }
        }
    }
}

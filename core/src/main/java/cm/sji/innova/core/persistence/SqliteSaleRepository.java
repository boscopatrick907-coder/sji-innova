package cm.sji.innova.core.persistence;

import cm.sji.innova.core.sale.*;
import cm.sji.innova.core.stock.StockMovement;
import java.nio.charset.StandardCharsets;
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
        return recordSale(sale, CashSaleRequest.fromSale(sale));
    }

    @Override
    public SaleResult recordSale(Sale sale, CashSaleRequest request) throws SQLException {
        request.requireMatches(sale);
        try (Connection connection = database.open()) {
            try (Statement statement = connection.createStatement()) {
                // Use SQL-managed transactions consistently, as in the stock repository.
                // setAutoCommit(false) would already BEGIN and make this a nested BEGIN.
                statement.execute("BEGIN IMMEDIATE");
                // Check idempotent retry
                try (PreparedStatement check = connection.prepareStatement("SELECT * FROM sales WHERE id = ?")) {
                    check.setString(1, sale.id().toString());
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            Optional<Sale> existing = loadSale(connection, sale.shopId(), sale.id());
                            if (existing.isPresent()) {
                                request.requireMatches(existing.get());
                                statement.execute("COMMIT");
                                return new SaleResult(existing.get(), true);
                            }
                            throw new IllegalArgumentException("Sale ID was already used with different checkout details");
                        }
                    }
                }

                // Process each item: verify product, check stock, record movement, decrement stock
                for (SaleItem item : sale.items()) {
                    long currentStock;
                    String unit;
                    String reference;
                    try (PreparedStatement prodStmt = connection.prepareStatement(
                            "SELECT unit, current_stock_milli, reference FROM products WHERE shop_id = ? AND id = ?")) {
                        prodStmt.setString(1, sale.shopId().toString());
                        prodStmt.setString(2, item.productId().toString());
                        try (ResultSet rs = prodStmt.executeQuery()) {
                            if (!rs.next()) throw new IllegalArgumentException("Product not found: " + item.productId());
                            unit = rs.getString("unit");
                            currentStock = rs.getLong("current_stock_milli");
                            reference = rs.getString("reference");
                        }
                    }

                    if (("piece".equals(unit) || "bag".equals(unit)) && item.quantityMilli() % 1000 != 0) {
                        throw new IllegalArgumentException("Piece and bag quantities must be whole items (multiples of 1000)");
                    }

                    // A saved line must name the product it sold: its reference is the product's own.
                    if (!reference.equals(item.productReference())) {
                        throw new IllegalArgumentException("Sale line reference " + item.productReference()
                                + " is not the reference of product " + item.productId());
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
                        INSERT INTO sale_items (id, sale_id, product_id, product_reference, product_name, unit, quantity_milli, unit_price, subtotal)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (SaleItem item : sale.items()) {
                        itemStmt.setString(1, item.id().toString());
                        itemStmt.setString(2, sale.id().toString());
                        itemStmt.setString(3, item.productId().toString());
                        itemStmt.setString(4, item.productReference());
                        itemStmt.setString(5, item.productName());
                        itemStmt.setString(6, item.unit());
                        itemStmt.setLong(7, item.quantityMilli());
                        itemStmt.setLong(8, item.unitPrice());
                        itemStmt.setLong(9, item.subtotal());
                        itemStmt.addBatch();
                    }
                    itemStmt.executeBatch();
                }

                // Append creation event in the same atomic transaction
                try (PreparedStatement eventStmt = connection.prepareStatement("""
                        INSERT INTO sale_events (id, sale_id, shop_id, device_id, type, performed_by, reason, created_at)
                        VALUES (?, ?, ?, ?, 'created', NULL, ?, ?)
                        """)) {
                    eventStmt.setString(1, SaleEvent.createdEventId(sale.id()).toString());
                    eventStmt.setString(2, sale.id().toString());
                    eventStmt.setString(3, sale.shopId().toString());
                    eventStmt.setString(4, sale.deviceId().toString());
                    eventStmt.setString(5, "Sale " + sale.receiptNumber());
                    eventStmt.setString(6, sale.createdAt().toString());
                    eventStmt.executeUpdate();
                }

                statement.execute("COMMIT");
                return new SaleResult(sale, false);
            } catch (SQLException | RuntimeException failure) {
                try {
                    try (Statement rollback = connection.createStatement()) {
                        rollback.execute("ROLLBACK");
                    }
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @Override
    public VoidSaleResult voidSale(VoidSaleRequest request) throws SQLException {
        Objects.requireNonNull(request, "request");
        try (Connection connection = database.open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");

                // 1. Check idempotent retry or conflicting operation ID reuse under the write lock
                try (PreparedStatement eventCheck = connection.prepareStatement("""
                        SELECT id, sale_id, shop_id, device_id, type, performed_by, reason, created_at
                        FROM sale_events
                        WHERE id = ?
                        """)) {
                    eventCheck.setString(1, request.operationId().toString());
                    try (ResultSet rs = eventCheck.executeQuery()) {
                        if (rs.next()) {
                            SaleEvent existingEvent = readSaleEvent(rs);
                            request.requireMatches(existingEvent);
                            Sale sale = loadSale(connection, request.shopId(), request.saleId())
                                    .orElseThrow(() -> new SQLException("Original sale missing for void event: " + request.saleId()));
                            List<StockMovement> restoredMovements = loadVoidMovements(connection, request.shopId(), request.saleId(), existingEvent.id());
                            SaleAuditRecord auditRecord = loadAuditForEvent(connection, request.shopId(), request.saleId(), existingEvent.id())
                                    .orElseThrow(() -> new SQLException("Audit record missing for void event: " + existingEvent.id()));
                            statement.execute("COMMIT");
                            return new VoidSaleResult(sale, existingEvent, restoredMovements, auditRecord, true);
                        }
                    }
                }

                // 2. Verify sale ownership/type and that no successful void already exists
                Sale sale = loadSale(connection, request.shopId(), request.saleId())
                        .orElseThrow(() -> new IllegalArgumentException("Sale not found in this shop: " + request.saleId()));
                if (sale.paymentMethod() != PaymentMethod.CASH) {
                    throw new IllegalArgumentException("Only cash sales can be voided: " + sale.paymentMethod());
                }

                try (PreparedStatement voidCheck = connection.prepareStatement("""
                        SELECT id FROM sale_events WHERE shop_id = ? AND sale_id = ? AND type = 'voided'
                        """)) {
                    voidCheck.setString(1, request.shopId().toString());
                    voidCheck.setString(2, request.saleId().toString());
                    try (ResultSet rs = voidCheck.executeQuery()) {
                        if (rs.next()) {
                            throw new IllegalStateException("Sale is already voided: " + request.saleId());
                        }
                    }
                }

                // 3. Append the void event
                Instant voidedAt = Instant.now();
                SaleEvent voidEvent = new SaleEvent(
                        request.operationId(),
                        request.saleId(),
                        request.shopId(),
                        SaleEvent.Type.VOIDED,
                        request.deviceId(),
                        request.performedBy(),
                        request.reason(),
                        voidedAt);

                try (PreparedStatement insertEvent = connection.prepareStatement("""
                        INSERT INTO sale_events (id, sale_id, shop_id, device_id, type, performed_by, reason, created_at)
                        VALUES (?, ?, ?, ?, 'voided', ?, ?, ?)
                        """)) {
                    insertEvent.setString(1, voidEvent.id().toString());
                    insertEvent.setString(2, voidEvent.saleId().toString());
                    insertEvent.setString(3, voidEvent.shopId().toString());
                    insertEvent.setString(4, voidEvent.deviceId().toString());
                    insertEvent.setString(5, voidEvent.performedBy().toString());
                    insertEvent.setString(6, voidEvent.reason());
                    insertEvent.setString(7, voidEvent.createdAt().toString());
                    insertEvent.executeUpdate();
                }

                // 4 & 5. Restore quantities from saved sale items, insert VOID movements + relational links, and update cached stock
                List<StockMovement> restoredMovements = new ArrayList<>(sale.items().size());
                StringBuilder restoredItemsJson = new StringBuilder("[");
                String movementReason = "Void sale " + sale.receiptNumber() + ": " + request.reason();

                for (SaleItem item : sale.items()) {
                    long currentStock;
                    String unit;
                    try (PreparedStatement prodStmt = connection.prepareStatement(
                            "SELECT unit, current_stock_milli FROM products WHERE shop_id = ? AND id = ?")) {
                        prodStmt.setString(1, sale.shopId().toString());
                        prodStmt.setString(2, item.productId().toString());
                        try (ResultSet rs = prodStmt.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException("Product not found: " + item.productId());
                            }
                            unit = rs.getString("unit");
                            currentStock = rs.getLong("current_stock_milli");
                        }
                    }

                    if (("piece".equals(unit) || "bag".equals(unit)) && item.quantityMilli() % 1000 != 0) {
                        throw new IllegalArgumentException("Piece and bag quantities must be whole items (multiples of 1000)");
                    }

                    long afterStock = Math.addExact(currentStock, item.quantityMilli());

                    UUID movementId = UUID.nameUUIDFromBytes(
                            ("void-movement:" + request.operationId() + ":" + item.id()).getBytes(StandardCharsets.UTF_8));
                    StockMovement movement = new StockMovement(
                            movementId,
                            sale.shopId(),
                            item.productId(),
                            request.deviceId(),
                            StockMovement.Type.VOID,
                            item.quantityMilli(),
                            movementReason,
                            voidedAt);

                    try (PreparedStatement movStmt = connection.prepareStatement("""
                            INSERT INTO stock_movements (id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at)
                            VALUES (?, ?, ?, ?, 'void', ?, ?, ?)
                            """)) {
                        movStmt.setString(1, movement.id().toString());
                        movStmt.setString(2, movement.shopId().toString());
                        movStmt.setString(3, movement.productId().toString());
                        movStmt.setString(4, movement.deviceId().toString());
                        movStmt.setLong(5, movement.quantityMilli());
                        movStmt.setString(6, movement.reason());
                        movStmt.setString(7, movement.createdAt().toString());
                        movStmt.executeUpdate();
                    }

                    try (PreparedStatement linkStmt = connection.prepareStatement("""
                            INSERT INTO sale_void_items (sale_item_id, void_event_id, shop_id, sale_id, product_id, stock_movement_id, quantity_milli)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        linkStmt.setString(1, item.id().toString());
                        linkStmt.setString(2, voidEvent.id().toString());
                        linkStmt.setString(3, sale.shopId().toString());
                        linkStmt.setString(4, sale.id().toString());
                        linkStmt.setString(5, item.productId().toString());
                        linkStmt.setString(6, movement.id().toString());
                        linkStmt.setLong(7, item.quantityMilli());
                        linkStmt.executeUpdate();
                    }

                    try (PreparedStatement updateStmt = connection.prepareStatement(
                            "UPDATE products SET current_stock_milli = ? WHERE shop_id = ? AND id = ?")) {
                        updateStmt.setLong(1, afterStock);
                        updateStmt.setString(2, sale.shopId().toString());
                        updateStmt.setString(3, item.productId().toString());
                        if (updateStmt.executeUpdate() != 1) {
                            throw new SQLException("Failed to update product stock during sale void");
                        }
                    }

                    if (restoredItemsJson.length() > 1) {
                        restoredItemsJson.append(',');
                    }
                    restoredItemsJson.append("{\"saleItemId\":\"").append(item.id())
                            .append("\",\"productId\":\"").append(item.productId())
                            .append("\",\"unit\":").append(jsonQuote(item.unit()))
                            .append(",\"quantityMilli\":").append(item.quantityMilli())
                            .append(",\"stockMovementId\":\"").append(movement.id())
                            .append("\"}");

                    restoredMovements.add(movement);
                }
                restoredItemsJson.append(']');

                // 6. Append the immutable audit entry with per-item quantities and units rather than a cross-unit sum
                UUID auditId = UUID.nameUUIDFromBytes(
                        ("void-audit:" + request.operationId()).getBytes(StandardCharsets.UTF_8));
                String oldValue = "{\"status\":\"paid\",\"receiptNumber\":" + jsonQuote(sale.receiptNumber())
                        + ",\"totalAmount\":" + sale.totalAmount() + "}";
                String newValue = "{\"status\":\"voided\",\"voidEventId\":\"" + voidEvent.id()
                        + "\",\"performedBy\":\"" + request.performedBy()
                        + "\",\"restoredItemCount\":" + sale.items().size()
                        + ",\"restoredItems\":" + restoredItemsJson + "}";

                SaleAuditRecord auditRecord = new SaleAuditRecord(
                        auditId,
                        sale.shopId(),
                        request.deviceId(),
                        request.performedBy(),
                        "VOID_SALE",
                        "sales",
                        sale.id(),
                        voidEvent.id(),
                        request.reason(),
                        oldValue,
                        newValue,
                        voidedAt);

                try (PreparedStatement auditStmt = connection.prepareStatement("""
                        INSERT INTO audit_log (id, shop_id, device_id, performed_by, action, target_table, target_id, event_id, reason, old_value, new_value, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    auditStmt.setString(1, auditRecord.id().toString());
                    auditStmt.setString(2, auditRecord.shopId().toString());
                    auditStmt.setString(3, auditRecord.deviceId().toString());
                    auditStmt.setString(4, auditRecord.performedBy().toString());
                    auditStmt.setString(5, auditRecord.action());
                    auditStmt.setString(6, auditRecord.targetTable());
                    auditStmt.setString(7, auditRecord.targetId().toString());
                    auditStmt.setString(8, auditRecord.eventId().toString());
                    auditStmt.setString(9, auditRecord.reason());
                    auditStmt.setString(10, auditRecord.oldValue());
                    auditStmt.setString(11, auditRecord.newValue());
                    auditStmt.setString(12, auditRecord.createdAt().toString());
                    auditStmt.executeUpdate();
                }

                statement.execute("COMMIT");
                return new VoidSaleResult(sale, voidEvent, List.copyOf(restoredMovements), auditRecord, false);
            } catch (SQLException | RuntimeException failure) {
                try {
                    try (Statement rollback = connection.createStatement()) {
                        rollback.execute("ROLLBACK");
                    }
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

    @Override
    public Optional<SaleState> findStateById(UUID shopId, UUID saleId) throws SQLException {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(saleId, "saleId");
        try (Connection connection = database.open()) {
            Optional<Sale> sale = loadSale(connection, shopId, saleId);
            if (sale.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(buildSaleState(connection, sale.get()));
        }
    }

    @Override
    public List<SaleState> listStatesByShop(UUID shopId) throws SQLException {
        Objects.requireNonNull(shopId, "shopId");
        try (Connection connection = database.open()) {
            List<Sale> sales = listByShop(connection, shopId);
            List<SaleState> states = new ArrayList<>(sales.size());
            for (Sale sale : sales) {
                states.add(buildSaleState(connection, sale));
            }
            return List.copyOf(states);
        }
    }

    @Override
    public List<SaleEvent> listEvents(UUID shopId, UUID saleId) throws SQLException {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(saleId, "saleId");
        try (Connection connection = database.open()) {
            if (loadSale(connection, shopId, saleId).isEmpty()) {
                throw new IllegalArgumentException("Sale not found in this shop: " + saleId);
            }
            return loadSaleEvents(connection, shopId, saleId);
        }
    }

    @Override
    public List<SaleAuditRecord> listAuditRecords(UUID shopId, UUID saleId) throws SQLException {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(saleId, "saleId");
        try (Connection connection = database.open()) {
            if (loadSale(connection, shopId, saleId).isEmpty()) {
                throw new IllegalArgumentException("Sale not found in this shop: " + saleId);
            }
            try (PreparedStatement stmt = connection.prepareStatement("""
                    SELECT id, shop_id, device_id, performed_by, action, target_table, target_id, event_id, reason, old_value, new_value, created_at
                    FROM audit_log
                    WHERE shop_id = ? AND target_table = 'sales' AND target_id = ?
                    ORDER BY created_at ASC, id ASC
                    """)) {
                stmt.setString(1, shopId.toString());
                stmt.setString(2, saleId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    List<SaleAuditRecord> records = new ArrayList<>();
                    while (rs.next()) {
                        records.add(readAuditRecord(rs));
                    }
                    return List.copyOf(records);
                }
            }
        }
    }

    private List<Sale> listByShop(Connection connection, UUID shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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

    private SaleState buildSaleState(Connection connection, Sale sale) throws SQLException {
        List<SaleEvent> events = loadSaleEvents(connection, sale.shopId(), sale.id());
        Optional<SaleEvent> voidEvent = events.stream()
                .filter(e -> e.type() == SaleEvent.Type.VOIDED)
                .findFirst();
        SaleStatus status = voidEvent.isPresent() ? SaleStatus.VOIDED : SaleStatus.PAID;
        return new SaleState(sale, status, events, voidEvent);
    }

    private List<SaleEvent> loadSaleEvents(Connection connection, UUID shopId, UUID saleId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                SELECT id, sale_id, shop_id, device_id, type, performed_by, reason, created_at
                FROM sale_events
                WHERE shop_id = ? AND sale_id = ?
                ORDER BY CASE type WHEN 'created' THEN 0 ELSE 1 END ASC, created_at ASC, id ASC
                """)) {
            stmt.setString(1, shopId.toString());
            stmt.setString(2, saleId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<SaleEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(readSaleEvent(rs));
                }
                return List.copyOf(events);
            }
        }
    }

    private List<StockMovement> loadVoidMovements(Connection connection, UUID shopId, UUID saleId, UUID voidEventId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                SELECT sm.id, sm.shop_id, sm.product_id, sm.device_id, sm.type, sm.quantity_milli, sm.reason, sm.created_at
                FROM sale_void_items svi
                JOIN stock_movements sm ON sm.shop_id = svi.shop_id AND sm.product_id = svi.product_id AND sm.id = svi.stock_movement_id
                WHERE svi.shop_id = ? AND svi.sale_id = ? AND svi.void_event_id = ?
                ORDER BY svi.rowid ASC
                """)) {
            stmt.setString(1, shopId.toString());
            stmt.setString(2, saleId.toString());
            stmt.setString(3, voidEventId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<StockMovement> movements = new ArrayList<>();
                while (rs.next()) {
                    movements.add(new StockMovement(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("shop_id")),
                            UUID.fromString(rs.getString("product_id")),
                            UUID.fromString(rs.getString("device_id")),
                            StockMovement.Type.valueOf(rs.getString("type").toUpperCase(Locale.ROOT)),
                            rs.getLong("quantity_milli"),
                            rs.getString("reason"),
                            Instant.parse(rs.getString("created_at"))));
                }
                return List.copyOf(movements);
            }
        }
    }

    private Optional<SaleAuditRecord> loadAuditForEvent(Connection connection, UUID shopId, UUID saleId, UUID eventId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                SELECT id, shop_id, device_id, performed_by, action, target_table, target_id, event_id, reason, old_value, new_value, created_at
                FROM audit_log
                WHERE shop_id = ? AND target_table = 'sales' AND target_id = ? AND event_id = ?
                """)) {
            stmt.setString(1, shopId.toString());
            stmt.setString(2, saleId.toString());
            stmt.setString(3, eventId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readAuditRecord(rs));
            }
        }
    }

    private static SaleEvent readSaleEvent(ResultSet rs) throws SQLException {
        String performedBy = rs.getString("performed_by");
        return new SaleEvent(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("sale_id")),
                UUID.fromString(rs.getString("shop_id")),
                SaleEvent.Type.fromCode(rs.getString("type")),
                UUID.fromString(rs.getString("device_id")),
                performedBy != null ? UUID.fromString(performedBy) : null,
                rs.getString("reason"),
                Instant.parse(rs.getString("created_at")));
    }

    private static SaleAuditRecord readAuditRecord(ResultSet rs) throws SQLException {
        return new SaleAuditRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("shop_id")),
                UUID.fromString(rs.getString("device_id")),
                UUID.fromString(rs.getString("performed_by")),
                rs.getString("action"),
                rs.getString("target_table"),
                UUID.fromString(rs.getString("target_id")),
                UUID.fromString(rs.getString("event_id")),
                rs.getString("reason"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                Instant.parse(rs.getString("created_at")));
    }

    private static String jsonQuote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
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
                SELECT id, sale_id, product_id, product_reference, product_name, unit, quantity_milli, unit_price, subtotal
                FROM sale_items
                WHERE sale_id = ?
                ORDER BY rowid ASC
                """)) {
            stmt.setString(1, saleId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<SaleItem> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new SaleItem(UUID.fromString(rs.getString("id")), saleId,
                            UUID.fromString(rs.getString("product_id")),
                            rs.getString("product_reference"),
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

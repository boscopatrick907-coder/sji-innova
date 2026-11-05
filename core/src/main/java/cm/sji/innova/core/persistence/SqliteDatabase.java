package cm.sji.innova.core.persistence;

import cm.sji.innova.core.sale.SaleEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** File-backed connections. Call initialize before using repositories. */
public final class SqliteDatabase {
    public static final int SCHEMA_VERSION = 8;
    private final Path file;

    public SqliteDatabase(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA synchronous = FULL");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    public void initialize() throws SQLException, IOException {
        Files.createDirectories(file.getParent());
        try (Connection connection = open(); var statement = connection.createStatement()) {
            // SQLite's documented table-rebuild procedure: disable FKs outside the transaction,
            // check all references before commit, then re-enable before returning.
            statement.execute("PRAGMA foreign_keys = OFF");
            // Acquire a write lock before reading the version, so concurrent startup is safe.
            statement.execute("BEGIN IMMEDIATE");
            try {
                int version;
                try (var result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }
                if (version > SCHEMA_VERSION) {
                    throw new SQLException("Database is newer than this application; refusing to change it");
                }
                if (version == 0) {
                    statement.execute("""
                        CREATE TABLE products (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            name TEXT NOT NULL CHECK(length(trim(name)) > 0),
                            unit_price INTEGER NOT NULL CHECK(typeof(unit_price) = 'integer' AND unit_price >= 0),
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            version INTEGER NOT NULL CHECK(version >= 1)
                        )
                        """);
                    statement.execute("CREATE INDEX products_shop_id ON products(shop_id)");
                    statement.execute("PRAGMA user_version = 1");
                    version = 1;
                }
                if (version == 1) {
                    statement.execute("ALTER TABLE products ADD COLUMN model_reference TEXT");
                    statement.execute("ALTER TABLE products ADD COLUMN cost_price INTEGER CHECK(cost_price IS NULL OR (typeof(cost_price) = 'integer' AND cost_price >= 0))");
                    statement.execute("ALTER TABLE products ADD COLUMN category TEXT");
                    statement.execute("ALTER TABLE products ADD COLUMN unit TEXT NOT NULL DEFAULT 'piece' CHECK(unit IN ('piece', 'kg', 'litre'))");
                    statement.execute("""
                        CREATE TABLE product_specifications (
                            product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                            name TEXT NOT NULL CHECK(length(trim(name)) > 0),
                            value TEXT NOT NULL CHECK(length(trim(value)) > 0),
                            PRIMARY KEY (product_id, name)
                        )
                        """);
                    statement.execute("PRAGMA user_version = 2");
                    version = 2;
                }
                if (version == 2) {
                    statement.execute("ALTER TABLE products ADD COLUMN current_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(current_stock_milli) = 'integer')");
                    statement.execute("ALTER TABLE products ADD COLUMN min_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(min_stock_milli) = 'integer' AND min_stock_milli >= 0)");
                    statement.execute("""
                        CREATE TABLE stock_movements (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            product_id TEXT NOT NULL REFERENCES products(id),
                            device_id TEXT NOT NULL,
                            type TEXT NOT NULL CHECK(type IN ('delivery', 'adjustment')),
                            quantity_milli INTEGER NOT NULL CHECK(typeof(quantity_milli) = 'integer' AND quantity_milli != 0),
                            reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
                            created_at TEXT NOT NULL,
                            CHECK(type != 'delivery' OR quantity_milli > 0)
                        )
                        """);
                    statement.execute("CREATE INDEX stock_movements_product ON stock_movements(shop_id, product_id, created_at, id)");
                    statement.execute("CREATE TRIGGER stock_movements_no_update BEFORE UPDATE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END");
                    statement.execute("PRAGMA user_version = 3");
                    version = 3;
                }
                if (version == 3) {
                    statement.execute("""
                        CREATE TABLE stock_movements_v4 (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            product_id TEXT NOT NULL REFERENCES products(id),
                            device_id TEXT NOT NULL,
                            type TEXT NOT NULL CHECK(type IN ('delivery', 'adjustment', 'sale', 'void')),
                            quantity_milli INTEGER NOT NULL CHECK(typeof(quantity_milli) = 'integer' AND quantity_milli != 0),
                            reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
                            created_at TEXT NOT NULL,
                            CHECK((type != 'delivery' AND type != 'void') OR quantity_milli > 0),
                            CHECK(type != 'sale' OR quantity_milli < 0)
                        )
                        """);
                    statement.execute("""
                        INSERT INTO stock_movements_v4 (id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at)
                        SELECT id, shop_id, product_id, device_id, type, quantity_milli, reason, created_at FROM stock_movements
                        """);
                    statement.execute("DROP TABLE stock_movements");
                    statement.execute("ALTER TABLE stock_movements_v4 RENAME TO stock_movements");
                    statement.execute("CREATE INDEX stock_movements_product ON stock_movements(shop_id, product_id, created_at, id)");
                    statement.execute("CREATE TRIGGER stock_movements_no_update BEFORE UPDATE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END");
                    statement.execute("CREATE TRIGGER stock_movements_no_delete BEFORE DELETE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END");

                    statement.execute("""
                        CREATE TABLE sales (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            receipt_number TEXT NOT NULL CHECK(length(trim(receipt_number)) > 0),
                            device_id TEXT NOT NULL,
                            payment_method TEXT NOT NULL CHECK(payment_method IN ('cash', 'momo', 'credit')),
                            total_amount INTEGER NOT NULL CHECK(total_amount >= 0),
                            created_at TEXT NOT NULL
                        )
                        """);
                    statement.execute("CREATE INDEX sales_shop_created ON sales(shop_id, created_at, id)");

                    statement.execute("""
                        CREATE TABLE sale_items (
                            id TEXT PRIMARY KEY NOT NULL,
                            sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
                            product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
                            product_name TEXT NOT NULL,
                            unit TEXT NOT NULL,
                            quantity_milli INTEGER NOT NULL CHECK(quantity_milli > 0),
                            unit_price INTEGER NOT NULL CHECK(unit_price >= 0),
                            subtotal INTEGER NOT NULL CHECK(subtotal >= 0)
                        )
                        """);
                    statement.execute("CREATE INDEX sale_items_sale ON sale_items(sale_id)");
                    statement.execute("PRAGMA user_version = 4");
                    version = 4;
                }
                if (version == 4) {
                    statement.execute("CREATE UNIQUE INDEX products_shop_id_id ON products(shop_id, id)");
                    statement.execute("CREATE UNIQUE INDEX sales_shop_id_id ON sales(shop_id, id)");
                    statement.execute("CREATE UNIQUE INDEX sale_items_sale_product_id ON sale_items(sale_id, id, product_id)");
                    statement.execute("CREATE UNIQUE INDEX stock_movements_shop_product_id ON stock_movements(shop_id, product_id, id)");

                    statement.execute("CREATE TRIGGER sales_no_update BEFORE UPDATE ON sales BEGIN SELECT RAISE(ABORT, 'Sales are append-only'); END");
                    statement.execute("CREATE TRIGGER sales_no_delete BEFORE DELETE ON sales BEGIN SELECT RAISE(ABORT, 'Sales are append-only'); END");
                    statement.execute("CREATE TRIGGER sale_items_no_update BEFORE UPDATE ON sale_items BEGIN SELECT RAISE(ABORT, 'Sale items are append-only'); END");
                    statement.execute("CREATE TRIGGER sale_items_no_delete BEFORE DELETE ON sale_items BEGIN SELECT RAISE(ABORT, 'Sale items are append-only'); END");

                    statement.execute("""
                        CREATE TABLE sale_events (
                            id TEXT PRIMARY KEY NOT NULL,
                            sale_id TEXT NOT NULL,
                            shop_id TEXT NOT NULL,
                            device_id TEXT NOT NULL,
                            type TEXT NOT NULL CHECK(type IN ('created', 'voided')),
                            performed_by TEXT CHECK(performed_by IS NULL OR length(trim(performed_by)) > 0),
                            reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
                            created_at TEXT NOT NULL,
                            CHECK(type != 'voided' OR performed_by IS NOT NULL),
                            UNIQUE(sale_id, type),
                            UNIQUE(shop_id, sale_id, id),
                            FOREIGN KEY (shop_id, sale_id) REFERENCES sales(shop_id, id) ON DELETE RESTRICT
                        )
                        """);
                    statement.execute("CREATE INDEX sale_events_shop_sale_created ON sale_events(shop_id, sale_id, created_at, id)");
                    statement.execute("CREATE TRIGGER sale_events_no_update BEFORE UPDATE ON sale_events BEGIN SELECT RAISE(ABORT, 'Sale events are append-only'); END");
                    statement.execute("CREATE TRIGGER sale_events_no_delete BEFORE DELETE ON sale_events BEGIN SELECT RAISE(ABORT, 'Sale events are append-only'); END");

                    // Deterministically backfill creation events for existing v4 sales without inventing actor IDs.
                    try (var query = connection.createStatement();
                         var existingSales = query.executeQuery(
                                 "SELECT id, shop_id, device_id, receipt_number, created_at FROM sales ORDER BY created_at, id");
                         var insertEvent = connection.prepareStatement("""
                                 INSERT INTO sale_events (id, sale_id, shop_id, device_id, type, performed_by, reason, created_at)
                                 VALUES (?, ?, ?, ?, 'created', NULL, ?, ?)
                                 """)) {
                        while (existingSales.next()) {
                            String saleId = existingSales.getString("id");
                            insertEvent.setString(1, SaleEvent.createdEventId(UUID.fromString(saleId)).toString());
                            insertEvent.setString(2, saleId);
                            insertEvent.setString(3, existingSales.getString("shop_id"));
                            insertEvent.setString(4, existingSales.getString("device_id"));
                            insertEvent.setString(5, "Sale " + existingSales.getString("receipt_number"));
                            insertEvent.setString(6, existingSales.getString("created_at"));
                            insertEvent.addBatch();
                        }
                        insertEvent.executeBatch();
                    }

                    statement.execute("""
                        CREATE TABLE sale_void_items (
                            sale_item_id TEXT PRIMARY KEY NOT NULL,
                            void_event_id TEXT NOT NULL,
                            shop_id TEXT NOT NULL,
                            sale_id TEXT NOT NULL,
                            product_id TEXT NOT NULL,
                            stock_movement_id TEXT NOT NULL UNIQUE,
                            quantity_milli INTEGER NOT NULL CHECK(typeof(quantity_milli) = 'integer' AND quantity_milli > 0),
                            FOREIGN KEY (shop_id, sale_id, void_event_id) REFERENCES sale_events(shop_id, sale_id, id) ON DELETE RESTRICT,
                            FOREIGN KEY (sale_id, sale_item_id, product_id) REFERENCES sale_items(sale_id, id, product_id) ON DELETE RESTRICT,
                            FOREIGN KEY (shop_id, product_id, stock_movement_id) REFERENCES stock_movements(shop_id, product_id, id) ON DELETE RESTRICT
                        )
                        """);
                    statement.execute("CREATE INDEX sale_void_items_sale ON sale_void_items(shop_id, sale_id)");
                    statement.execute("CREATE TRIGGER sale_void_items_no_update BEFORE UPDATE ON sale_void_items BEGIN SELECT RAISE(ABORT, 'Sale void items are append-only'); END");
                    statement.execute("CREATE TRIGGER sale_void_items_no_delete BEFORE DELETE ON sale_void_items BEGIN SELECT RAISE(ABORT, 'Sale void items are append-only'); END");

                    statement.execute("""
                        CREATE TABLE audit_log (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            device_id TEXT NOT NULL,
                            performed_by TEXT NOT NULL CHECK(length(trim(performed_by)) > 0),
                            action TEXT NOT NULL CHECK(length(trim(action)) > 0),
                            target_table TEXT NOT NULL CHECK(length(trim(target_table)) > 0),
                            target_id TEXT NOT NULL CHECK(length(trim(target_id)) > 0),
                            event_id TEXT NOT NULL UNIQUE,
                            reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
                            old_value TEXT NOT NULL CHECK(length(trim(old_value)) > 0),
                            new_value TEXT NOT NULL CHECK(length(trim(new_value)) > 0),
                            created_at TEXT NOT NULL,
                            FOREIGN KEY (shop_id, target_id, event_id) REFERENCES sale_events(shop_id, sale_id, id) ON DELETE RESTRICT
                        )
                        """);
                    statement.execute("CREATE INDEX audit_log_shop_target ON audit_log(shop_id, target_table, target_id, created_at, id)");
                    statement.execute("CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log BEGIN SELECT RAISE(ABORT, 'Audit log is append-only'); END");
                    statement.execute("CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log BEGIN SELECT RAISE(ABORT, 'Audit log is append-only'); END");

                    statement.execute("PRAGMA user_version = 5");
                    version = 5;
                }
                if (version == 5) {
                    ProductFamilyMigration.apply(connection);
                    statement.execute("PRAGMA user_version = 6");
                    version = 6;
                }
                if (version == 6) {
                    ProductPhotoMigration.apply(connection);
                    statement.execute("PRAGMA user_version = 7");
                    version = 7;
                }
                if (version == 7) {
                    ProductImportMigration.apply(connection);
                    statement.execute("PRAGMA user_version = 8");
                    version = 8;
                }
                try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (violations.next()) throw new SQLException("Migration would leave broken foreign keys");
                }
                statement.execute("COMMIT");
            } catch (SQLException failure) {
                try { statement.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
        }
    }
}

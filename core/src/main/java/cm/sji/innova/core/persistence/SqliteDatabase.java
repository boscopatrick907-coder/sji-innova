package cm.sji.innova.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** File-backed connections. Call initialize before using repositories. */
public final class SqliteDatabase {
    public static final int SCHEMA_VERSION = 4;
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
                }
                statement.execute("COMMIT");
            } catch (SQLException failure) {
                try { statement.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
            statement.execute("PRAGMA journal_mode = WAL");
        }
    }
}

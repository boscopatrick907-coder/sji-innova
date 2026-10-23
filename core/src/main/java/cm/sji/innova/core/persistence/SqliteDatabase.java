package cm.sji.innova.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** File-backed connections. Call initialize before using repositories. */
public final class SqliteDatabase {
    private static final int SCHEMA_VERSION = 2;
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

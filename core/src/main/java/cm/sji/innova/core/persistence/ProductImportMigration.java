package cm.sji.innova.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** Schema 8: durable tracking for confirmed CSV product imports. */
final class ProductImportMigration {
    private ProductImportMigration() { }

    static void apply(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE product_import_operations (
                    id TEXT PRIMARY KEY NOT NULL,
                    shop_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL CHECK(length(content_hash) > 0),
                    total_selected INTEGER NOT NULL CHECK(total_selected >= 0),
                    created_at TEXT NOT NULL
                )
                """);
            s.execute("CREATE INDEX product_import_operations_shop ON product_import_operations(shop_id, created_at)");

            s.execute("""
                CREATE TABLE product_import_rows (
                    operation_id TEXT NOT NULL,
                    row_number INTEGER NOT NULL CHECK(row_number >= 1),
                    shop_id TEXT NOT NULL,
                    product_id TEXT NOT NULL,
                    row_hash TEXT NOT NULL CHECK(length(row_hash) > 0),
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(operation_id, row_number),
                    FOREIGN KEY(operation_id) REFERENCES product_import_operations(id) ON DELETE CASCADE,
                    FOREIGN KEY(shop_id, product_id) REFERENCES products(shop_id, id) ON DELETE CASCADE
                )
                """);
            s.execute("CREATE INDEX product_import_rows_product ON product_import_rows(shop_id, product_id)");
            s.execute("CREATE INDEX product_import_rows_hash ON product_import_rows(shop_id, row_hash)");
        }
    }
}

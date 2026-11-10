package cm.sji.innova.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema 9: each sale line keeps the shop reference of the product it sold, such as {@code P-0001}.
 *
 * <p>Lines saved before this version take their product's reference. Shop references are given once
 * and never edited, so this is the reference the product had when it was sold. The table is rebuilt so
 * the new column can be {@code NOT NULL}; every line keeps its rowid, so lines stay in the order they
 * were sold, and the indexes and append-only triggers are created again as they were.
 */
final class SaleLineReferenceMigration {
    private SaleLineReferenceMigration() { }

    static void apply(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            List<String> definitions = new ArrayList<>();
            try (var rows = s.executeQuery("SELECT sql FROM sqlite_schema WHERE tbl_name='sale_items' AND type IN ('index','trigger') AND sql IS NOT NULL")) {
                while (rows.next()) definitions.add(rows.getString(1));
            }
            s.execute("""
                CREATE TABLE sale_items_v9 (
                    id TEXT PRIMARY KEY NOT NULL,
                    sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
                    product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
                    product_reference TEXT NOT NULL CHECK(length(trim(product_reference)) > 0),
                    product_name TEXT NOT NULL,
                    unit TEXT NOT NULL,
                    quantity_milli INTEGER NOT NULL CHECK(quantity_milli > 0),
                    unit_price INTEGER NOT NULL CHECK(unit_price >= 0),
                    subtotal INTEGER NOT NULL CHECK(subtotal >= 0)
                )
                """);
            // A line whose product cannot be found gets NULL and fails NOT NULL, so the whole upgrade rolls back.
            s.execute("""
                INSERT INTO sale_items_v9 (rowid, id, sale_id, product_id, product_reference, product_name,
                                           unit, quantity_milli, unit_price, subtotal)
                SELECT line.rowid, line.id, line.sale_id, line.product_id,
                       (SELECT product.reference FROM products product WHERE product.id = line.product_id),
                       line.product_name, line.unit, line.quantity_milli, line.unit_price, line.subtotal
                FROM sale_items line
                """);
            s.execute("DROP TABLE sale_items");
            s.execute("ALTER TABLE sale_items_v9 RENAME TO sale_items");
            for (String sql : definitions) s.execute(sql);
        }
    }
}

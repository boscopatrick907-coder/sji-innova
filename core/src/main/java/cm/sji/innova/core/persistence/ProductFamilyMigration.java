package cm.sji.innova.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Schema 6: families, shop references and whole-bag selling units. */
final class ProductFamilyMigration {
    private ProductFamilyMigration() { }
    static void apply(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE product_families (
                    id TEXT PRIMARY KEY NOT NULL, shop_id TEXT NOT NULL,
                    name TEXT NOT NULL CHECK(length(trim(name)) > 0), category TEXT,
                    created_at TEXT NOT NULL, UNIQUE(shop_id, id)
                )
                """);
            s.execute("CREATE INDEX families_shop ON product_families(shop_id, name)");
            // Preserve application/custom indexes and triggers when rebuilding the parent table.
            List<String> definitions = new ArrayList<>();
            try (var rows = s.executeQuery("SELECT sql FROM sqlite_schema WHERE tbl_name='products' AND type IN ('index','trigger') AND sql IS NOT NULL")) {
                while (rows.next()) definitions.add(rows.getString(1));
            }
            s.execute("""
                CREATE TABLE products_v6 (
                    id TEXT PRIMARY KEY NOT NULL, shop_id TEXT NOT NULL,
                    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
                    unit_price INTEGER NOT NULL CHECK(typeof(unit_price)='integer' AND unit_price>=0),
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK(version>=1), model_reference TEXT,
                    cost_price INTEGER CHECK(cost_price IS NULL OR (typeof(cost_price)='integer' AND cost_price>=0)),
                    category TEXT, unit TEXT NOT NULL DEFAULT 'piece' CHECK(unit IN ('piece','kg','litre','bag')),
                    current_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(current_stock_milli)='integer'),
                    min_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(min_stock_milli)='integer' AND min_stock_milli>=0),
                    family_id TEXT, reference TEXT NOT NULL CHECK(length(reference)>0),
                    UNIQUE(shop_id, reference),
                    FOREIGN KEY(shop_id, family_id) REFERENCES product_families(shop_id,id) ON DELETE RESTRICT
                )
                """);
            s.execute("""
                INSERT INTO products_v6
                SELECT id,shop_id,name,unit_price,created_at,updated_at,version,model_reference,
                       cost_price,category,unit,current_stock_milli,min_stock_milli,NULL,
                       'P-' || printf('%04d', row_number() OVER (PARTITION BY shop_id ORDER BY created_at,id))
                FROM products
                """);
            s.execute("DROP TABLE products");
            s.execute("ALTER TABLE products_v6 RENAME TO products");
            for (String sql : definitions) s.execute(sql);
            s.execute("CREATE INDEX products_family ON products(shop_id,family_id)");
            s.execute("""
                CREATE TABLE product_reference_sequences (
                    shop_id TEXT PRIMARY KEY NOT NULL,
                    last_value INTEGER NOT NULL CHECK(typeof(last_value)='integer' AND last_value>=0)
                )
                """);
            s.execute("INSERT INTO product_reference_sequences SELECT shop_id,count(*) FROM products GROUP BY shop_id");
        }
    }
}

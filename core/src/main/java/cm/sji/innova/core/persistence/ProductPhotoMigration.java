package cm.sji.innova.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** Schema 7: persistent managed product photos. */
final class ProductPhotoMigration {
    private ProductPhotoMigration() { }

    static void apply(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE product_photos (
                    shop_id TEXT NOT NULL,
                    product_id TEXT NOT NULL PRIMARY KEY,
                    relative_path TEXT NOT NULL CHECK(length(trim(relative_path)) > 0),
                    thumbnail_path TEXT NOT NULL CHECK(length(trim(thumbnail_path)) > 0),
                    content_type TEXT NOT NULL CHECK(content_type IN ('image/jpeg', 'image/png')),
                    file_size INTEGER NOT NULL CHECK(file_size > 0),
                    width INTEGER NOT NULL CHECK(width > 0),
                    height INTEGER NOT NULL CHECK(height > 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(shop_id, product_id) REFERENCES products(shop_id, id) ON DELETE CASCADE
                )
                """);
            s.execute("CREATE INDEX product_photos_shop ON product_photos(shop_id)");
        }
    }
}


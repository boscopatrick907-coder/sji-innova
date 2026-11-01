package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductRepository;
import cm.sji.innova.core.product.ProductService;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Prepares the data folder, the shop ID and the database. This can be slow, so the window
 * calls it from a background thread, never from the Swing event thread.
 *
 * <p>It never deletes or replaces a database or an identity file: on any problem it throws.
 */
public final class AppContextLoader {
    private AppContextLoader() { }

    public static AppContext load(LocalAppData location) throws IOException, SQLException {
        Files.createDirectories(location.directory());
        UUID shopId = ShopIdentity.loadOrCreate(location.shopIdFile());
        SqliteDatabase database = new SqliteDatabase(location.databaseFile());
        database.initialize();
        return new AppContext(location, shopId, new ProductService(new SqliteProductRepository(database)));
    }
}

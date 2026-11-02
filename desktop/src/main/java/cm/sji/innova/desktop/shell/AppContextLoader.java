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
        // An existing database without its ID file: refuse, never invent a new ID.
        if (Files.exists(location.databaseFile()) && !Files.exists(location.shopIdFile())) {
            throw new IOException("The shop database " + location.databaseFile()
                    + " exists, but the shop identity file " + location.shopIdFile() + " is missing."
                    + " Cella did not create a new identity, so your products are not lost."
                    + " Put your copy of shop-id.txt back in this folder, then click Retry.");
        }
        UUID shopId = ShopIdentity.loadOrCreate(location.shopIdFile());
        SqliteDatabase database = new SqliteDatabase(location.databaseFile());
        database.initialize();
        return new AppContext(location, shopId, new ProductService(new SqliteProductRepository(database)));
    }
}
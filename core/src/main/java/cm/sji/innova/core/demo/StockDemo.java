package cm.sji.innova.core.demo;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.stock.*;
import java.nio.file.Path;
import java.util.UUID;
import java.math.BigDecimal;

/** Explicit developer demonstration; never run automatically on normal startup. */
public final class StockDemo {
    private StockDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: StockDemo PATH_TO_DISPOSABLE_DB");
        var database = new SqliteDatabase(Path.of(args[0]));
        database.initialize();
        var shop = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var device = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var operation = UUID.fromString("00000000-0000-0000-0000-000000000004");
        var products = new ProductService(new SqliteProductRepository(database));
        var existing = products.list(shop);
        var product = existing.isEmpty() ? products.create(shop, "Stock demo T-shirt", 3000) : existing.getFirst();
        var stock = new StockService(new SqliteStockRepository(database));
        var result = stock.receive(operation, shop, product.id(), device, 10_000, "Demo delivery");
        stock.setMinimum(shop, product.id(), 2_000);
        System.out.println(result.replayed() ? "Existing delivery loaded; not counted again" : "Recorded delivery of 10 pieces");
        var current = stock.current(shop, product.id());
        System.out.printf("%s | %s %s | minimum: %s | low: %s | movements: %d%n", product.id(),
                BigDecimal.valueOf(current.quantityMilli(), 3).stripTrailingZeros().toPlainString(), current.unit(),
                BigDecimal.valueOf(current.minimumMilli(), 3).stripTrailingZeros().toPlainString(), current.lowStock(),
                stock.history(shop, product.id()).size());
    }
}

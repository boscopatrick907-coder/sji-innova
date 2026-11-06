package cm.sji.innova.desktop.integration;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.*;
import cm.sji.innova.core.product.importer.*;
import cm.sji.innova.core.product.photo.*;
import cm.sji.innova.core.sale.*;
import cm.sji.innova.desktop.sales.*;
import cm.sji.innova.desktop.shell.*;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-module workflow using only disposable data and the actual startup/services. */
class ShopWorkflowTest {
    @TempDir Path directory;

    @Test void catalogImportPhotoStockCheckoutRestartAndBackendVoidShareOneShop() throws Exception {
        var location = new LocalAppData(directory.resolve("shop"));
        var app = AppContextLoader.load(location);
        assertTrue(app.products().list(app.shopId()).isEmpty(), "Startup must not seed products");
        var db = new SqliteDatabase(location.databaseFile());
        var families = new ProductFamilyService(new SqliteProductFamilyRepository(db), app.products());
        var family = families.create(app.shopId(), "Riz", "Épicerie");
        var rice = families.createVariant(app.shopId(), family.id(), "Parfumé 25 kg", 18000,
                "R25", 15000L, null, "bag", Map.of("poids", "25 kg"));
        var large = families.createVariant(app.shopId(), family.id(), "Parfumé 50 kg", 35000,
                "R50", null, null, "bag", Map.of("poids", "50 kg"));
        assertNotEquals(rice.reference(), large.reference());
        assertEquals(List.of(rice), families.search(app.shopId(), "riz parfume 25"));

        var importer = new ProductImportService(new SqliteProductImportRepository(db));
        var preview = importer.preview(app.shopId(), new StringReader(
                "name,unit,selling_price\nHuile,litre,1001\nBroken,bag,abc\n"), ImportOptions.DEFAULT);
        assertEquals(1, preview.invalidRows());
        assertEquals(2, app.products().list(app.shopId()).size(), "Preview is read-only");
        var importRequest = new ConfirmedImportRequest(UUID.randomUUID(), preview.previewId(),
                preview.contentHash(), List.of(1));
        assertEquals(1, importer.confirmImport(app.shopId(), importRequest, preview).importedCount());
        importer.confirmImport(app.shopId(), importRequest, preview);
        assertEquals(3, app.products().list(app.shopId()).size(), "Import retry must not duplicate");
        var oil = app.products().list(app.shopId()).stream().filter(p -> p.name().equals("Huile")).findFirst().orElseThrow();

        var photos = new ProductPhotoService(new SqliteProductPhotoRepository(db), location.directory());
        Path source = directory.resolve("rice.png");
        ImageIO.write(new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        photos.setPhoto(app.shopId(), rice.id(), source);
        Files.delete(source);
        assertInstanceOf(PhotoLoadResult.Loaded.class, photos.loadPhoto(app.shopId(), rice.id()));

        UUID receipt = UUID.randomUUID();
        app.stock().receive(receipt, app.shopId(), rice.id(), app.deviceId(), 10000, "Delivery");
        app.stock().receive(receipt, app.shopId(), rice.id(), app.deviceId(), 10000, "Delivery");
        app.stock().receive(UUID.randomUUID(), app.shopId(), oil.id(), app.deviceId(), 5000, "Delivery");
        app.stock().setMinimum(app.shopId(), rice.id(), 8000);
        var pending = PendingCheckout.start(app.shopId(), app.deviceId(), List.of(
                BasketLine.oneOf(rice, family.name()).withQuantity(2000),
                BasketLine.oneOf(oil, null).withQuantity(500)), LocalDate.of(2026, 11, 6));
        var checkout = new Checkout(app.sales(), new PendingCheckoutStore(location.pendingCheckoutFile()), app.shopId());
        var saved = assertInstanceOf(Checkout.Outcome.Saved.class, checkout.submit(pending));
        assertEquals(36501, saved.sale().totalAmount()); // 36000 + HALF_UP(500.5)
        assertEquals(8000, app.stock().current(app.shopId(), rice.id()).quantityMilli());
        assertEquals(4500, app.stock().current(app.shopId(), oil.id()).quantityMilli());
        assertEquals(0, app.stock().current(app.shopId(), large.id()).quantityMilli());
        assertFalse(Files.exists(location.pendingCheckoutFile()));

        var reopened = AppContextLoader.load(location);
        assertEquals(app.shopId(), reopened.shopId());
        assertEquals(app.deviceId(), reopened.deviceId());
        assertEquals(3, reopened.products().list(reopened.shopId()).size());
        assertEquals(saved.sale(), reopened.sales().getSale(reopened.shopId(), pending.saleId()).orElseThrow());
        assertTrue(reopened.stock().current(reopened.shopId(), rice.id()).lowStock());
        assertTrue(reopened.sales().makeCashSale(pending.saleId(), reopened.shopId(), reopened.deviceId(),
                pending.receiptNumber(), pending.requests()).replayed());
        assertEquals(8000, reopened.stock().current(reopened.shopId(), rice.id()).quantityMilli());
        assertInstanceOf(PhotoLoadResult.Loaded.class, photos.loadPhoto(reopened.shopId(), rice.id()));
        assertTrue(reopened.sales().listSales(UUID.randomUUID()).isEmpty());

        long beforeRejected = reopened.stock().current(reopened.shopId(), oil.id()).quantityMilli();
        assertThrows(IllegalStateException.class, () -> reopened.sales().makeCashSale(UUID.randomUUID(),
                reopened.shopId(), reopened.deviceId(), "OVERSELL", List.of(new SaleItemRequest(oil.id(), 6000))));
        assertEquals(beforeRejected, reopened.stock().current(reopened.shopId(), oil.id()).quantityMilli());
        assertEquals(1, reopened.sales().listSales(reopened.shopId()).size());

        // Voids currently exist in the backend, not as a desktop action.
        UUID voidId = UUID.randomUUID();
        reopened.sales().voidCashSale(voidId, reopened.shopId(), pending.saleId(), reopened.deviceId(),
                UUID.randomUUID(), "Test cancellation");
        assertTrue(reopened.sales().getSaleState(reopened.shopId(), pending.saleId()).orElseThrow().voided());
        assertEquals(10000, reopened.stock().current(reopened.shopId(), rice.id()).quantityMilli());
        assertEquals(5000, reopened.stock().current(reopened.shopId(), oil.id()).quantityMilli());
        assertEquals(saved.sale(), reopened.sales().getSale(reopened.shopId(), pending.saleId()).orElseThrow());
    }
}

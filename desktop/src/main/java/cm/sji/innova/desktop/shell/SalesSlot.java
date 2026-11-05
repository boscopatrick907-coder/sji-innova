package cm.sji.innova.desktop.shell;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductFamilyRepository;
import cm.sji.innova.core.product.ProductFamilyService;
import cm.sji.innova.desktop.sales.PendingCheckoutStore;
import cm.sji.innova.desktop.sales.SalesPanel;

/** Connects the Sales screen to the shell's real services, shop, device and messages. */
public final class SalesSlot {
    private SalesSlot() { }

    public static SalesPanel create(AppContext context, Messages messages) {
        var families = new ProductFamilyService(new SqliteProductFamilyRepository(
                new SqliteDatabase(context.location().databaseFile())), context.products());
        return new SalesPanel(context.sales(), context.products(), families, context.stock(),
                context.shopId(), context.deviceId(),
                new PendingCheckoutStore(context.location().pendingCheckoutFile()), messages.bundle());
    }
}

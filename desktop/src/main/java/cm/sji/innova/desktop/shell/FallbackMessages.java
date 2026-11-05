package cm.sji.innova.desktop.shell;

import java.util.ListResourceBundle;

/**
 * Temporary English texts for the window itself, used when Pascal's translation files do not
 * contain a key yet. Once messages_en/messages_fr contain these keys, this class can go.
 */
public final class FallbackMessages extends ListResourceBundle {
    @Override
    protected Object[][] getContents() {
        return new Object[][] {
            {"app.title", "Cella"},
            {"shell.nav.accessibleName", "Main navigation"},
            {"shell.nav.products", "Products"},
            {"shell.loading", "Opening your shop data..."},
            {"shell.error.title", "Cella could not start"},
            {"shell.error.dataFolder", "Data folder: {0}"},
            {"shell.error.retry", "Retry"},
            {"shell.error.exit", "Exit"},
            {"shell.devShop", "Development shop (temporary identity, not a login): {0}"},
            {"shell.products.placeholder.title", "Products"},
            {"shell.products.placeholder.body",
                "The Products screen is not connected yet. It will appear here once the Products panel is merged."},
            {"catalog.refresh", "Refresh"},
            {"catalog.search", "Name, reference, color, size…"},
            {"catalog.category", "Category"},
            {"catalog.loading", "Loading products…"},
            {"catalog.allCategories", "All categories"},
            {"catalog.stockError", "Stock unavailable. Refresh to try again."},
            {"catalog.loadError", "Products could not be loaded."},
            {"catalog.retryHint", "Use Refresh to try again."},
            {"catalog.ungrouped", "Ungrouped products"},
            {"catalog.unavailable", "Unavailable"},
            {"catalog.products", "Products"},
            {"catalog.addVariant", "Add a variant"},
            {"catalog.addProduct", "Add a product"},
            {"catalog.empty", "No products yet. Add your first product."},
            {"catalog.variant", "Variant"},
            {"catalog.reference", "Reference"},
            {"catalog.available", "Available stock"},
            {"catalog.price", "Selling price"},
            {"catalog.openHint", "Select an item, then Open or press Enter."},
            {"catalog.open", "Open"},
            {"catalog.noMatches", "No matching products."},
            {"catalog.variants", "variants"},
            {"catalog.noPhoto", "No photo"},
            {"catalog.details", "Product details"},
            {"catalog.buying", "Buying price"},
            {"catalog.unknown", "Not provided"},
            {"catalog.movements", "Stock movements"},
            {"catalog.noMovements", "No stock movements."},
            {"catalog.back", "Back"},
            {"catalog.familyOptional", "Family (optional; choose or type a name)"},
            {"catalog.variantName", "Product / variant name *"},
            {"catalog.sellingRequired", "Selling price in whole FCFA *"},
            {"catalog.addInformation", "Add information"},
            {"catalog.infoName", "Name, e.g. Color"},
            {"catalog.infoValue", "Value, e.g. Blue"},
            {"catalog.remove", "Remove"},
            {"catalog.optional", "Additional information (optional)"},
            {"catalog.stockHint", "Record received quantities in Stock."},
            {"catalog.cancel", "Cancel"},
            {"unit.bag", "Bag"},
            {"shell.catalog", "Catalog"},
            {"shell.tagline", "Shop management"},
            {"shell.nav.stock", "Stock"},
        };
    }
}

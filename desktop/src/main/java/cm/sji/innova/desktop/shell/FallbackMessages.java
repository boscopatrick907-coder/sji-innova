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
        };
    }
}

package cm.sji.innova.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.desktop.backup.BackupService;
import cm.sji.innova.desktop.backup.BackupSettings;
import cm.sji.innova.desktop.backup.RestorePreparation;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.settings.BackupReminder;
import cm.sji.innova.desktop.settings.SettingsPanel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03c3: the Settings entry in the sidebar opens the Settings screen, built on the shop that is open, so
 * it reads and changes that shop's real backup and restore state. Each test uses a disposable shop.
 */
class SettingsRouteTest {
    @TempDir Path directory;

    @Test
    void theSettingsEntryOpensTheScreenOnTheShopsOwnBackupAndRestoreServices() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("data"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        try (DataFolderCoordinator.Membership membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(location);
            shop.products().create(shop.shopId(), "Rice", 1_000);
            ShellPanel shell = open(Locale.ENGLISH, location, shop);

            edt(() -> {
                assertEquals("products", shell.selectedScreenId(), "Products stays the first screen");
                assertEquals(List.of("Overview", "Products", "Stock", "Sales", "Settings"), entries(shell));
                assertEquals(0, count(shell, SettingsPanel.class), "Nothing is built before the entry is opened");
                shell.selectScreen("settings");
                return null;
            });
            await(edt(shell::settingsReady));
            edt(() -> {
                assertEquals("settings", shell.selectedScreenId());
                assertTrue(only(shell, SettingsPanel.class).isVisible(), "The Settings screen is the one shown");
                assertEquals("No backup yet.", words(shell, "settingsLastBackup"));
                return null;
            });

            // A backup and a restore made with the shop's own services show when the entry is opened again.
            Path backup = backUp(location, shop, coordinator, directory.resolve("usb"));
            RestorePreparation restores = new RestorePreparation(location, shop.shopId(), Clock.systemDefaultZone());
            restores.schedule(restores.prepare(backup));
            edt(() -> { shell.selectScreen("products"); shell.selectScreen("settings"); return null; });
            await(edt(shell::settingsReady));
            edt(() -> {
                assertEquals(1, count(shell, SettingsPanel.class), "The same screen is used again");
                assertTrue(words(shell, "settingsLastBackup").endsWith("(today)"), words(shell, "settingsLastBackup"));
                assertTrue(words(shell, "settingsRestoreWarning").startsWith("A restore is scheduled."), words(shell, "settingsRestoreWarning"));
                button(shell, "settingsCancelScheduled").doClick();
                return null;
            });

            // Opening the entry while the cancel runs returns that work, so this waits for it.
            edt(() -> { shell.selectScreen("products"); shell.selectScreen("settings"); return null; });
            await(edt(shell::settingsReady));
            assertEquals(Optional.empty(), restores.scheduled(), "The screen's cancel reached the shop's restore service");
            edt(() -> {
                assertTrue(button(shell, "settingsChooseBackup").isVisible());
                assertFalse(button(shell, "settingsCancelScheduled").isVisible());
                return null;
            });
        }
    }

    @Test
    void inFrenchTheEntryAndScreenAreFrenchAndOpeningTheShopAgainBuildsANewScreen() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("data"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        try (DataFolderCoordinator.Membership membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(location);
            ShellPanel shell = open(Locale.FRENCH, location, shop);
            edt(() -> {
                assertEquals(List.of("Accueil", "Produits", "Stock", "Ventes", "Paramètres"), entries(shell));
                shell.selectScreen("settings");
                return null;
            });
            await(edt(shell::settingsReady));
            edt(() -> {
                assertEquals("Aucune sauvegarde pour l’instant.", words(shell, "settingsLastBackup"));
                assertEquals("Créer une sauvegarde", button(shell, "settingsCreateBackup").getText());
                return null;
            });

            // Retry after an error shows the main page again with the context it loaded: the old screen goes.
            edt(() -> { shell.showMain(shop); return null; });
            await(edt(shell::productsReady));
            edt(() -> {
                assertEquals(0, count(shell, SettingsPanel.class), "The old Settings screen is gone");
                shell.selectScreen("settings");
                return null;
            });
            await(edt(shell::settingsReady));
            edt(() -> { assertEquals(1, count(shell, SettingsPanel.class)); return null; });
        }
    }

    @Test
    void theBackupReminderShowsInTheSidebarFromStartUpOpensSettingsAndGoesAfterABackup() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("data"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        try (DataFolderCoordinator.Membership membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(location);
            Path usb = java.nio.file.Files.createDirectories(directory.resolve("usb"));
            new BackupSettings(location).rememberFolder(usb);
            ShellPanel shell = open(Locale.ENGLISH, location, shop);
            await(edt(shell::reminderReady));
            edt(() -> {
                BackupReminder reminder = find(shell, BackupReminder.class, "backupReminder");
                assertTrue(reminder.isVisible(), "No backup yet: reminded from start-up");
                assertEquals("No backup yet. Back up now", reminder.getAccessibleContext().getAccessibleName());
                assertTrue(SwingUtilities.isDescendingFrom(reminder, find(shell, JPanel.class, "cellaSidebar")),
                        "In the sidebar, so the screens keep their room");
                assertEquals("products", shell.selectedScreenId(), "Products stays the first screen");
                reminder.doClick();
                return null;
            });
            await(edt(shell::settingsReady));
            edt(() -> {
                assertEquals("settings", shell.selectedScreenId(), "The reminder opens Settings");
                assertEquals(usb.toAbsolutePath().normalize().toString(), words(shell, "settingsFolder"));
                button(shell, "settingsCreateBackup").doClick();
                return null;
            });

            // Opening the entry while the backup runs returns that work, so this waits for the backup.
            edt(() -> { shell.selectScreen("products"); shell.selectScreen("settings"); return null; });
            await(edt(shell::settingsReady));
            assertTrue(BackupService.lastSuccess(location).isPresent(), "A real backup was made");
            await(edt(shell::reminderReady));
            edt(() -> { assertFalse(find(shell, BackupReminder.class, "backupReminder").isVisible(), "Gone after the backup"); return null; });
        }
    }

    @Test
    void theReminderFitsCompletelyInTheSmallestWindow() throws Exception {
        edt(() -> { com.formdev.flatlaf.FlatLightLaf.setup(); cm.sji.innova.desktop.catalog.CatalogStyle.installDefaults(); return null; });
        LocalAppData location = new LocalAppData(directory.resolve("compact-reminder"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        try (var membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(location);
            ShellPanel shell = open(Locale.FRENCH, location, shop);
            await(edt(shell::reminderReady));
            edt(() -> {
                shell.setSize(1100,760); for(int i=0;i<6;i++) layoutTree(shell);
                var preview = new java.awt.image.BufferedImage(1100,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = preview.createGraphics(); shell.printAll(graphics); graphics.dispose();
                shell.setSize(960,600); for(int i=0;i<6;i++) layoutTree(shell);
                BackupReminder reminder = find(shell,BackupReminder.class,"backupReminder");
                assertTrue(reminder.isVisible());
                assertTrue(reminder.getWidth() >= reminder.getPreferredSize().width,
                        "The reminder must not be squeezed horizontally");
                assertTrue(reminder.getHeight() >= reminder.getPreferredSize().height,
                        "The whole reminder, including its action link, must fit");
                assertTrue(reminder.getY()+reminder.getHeight() <= reminder.getParent().getHeight(),
                        "The sidebar must not clip the reminder through its parent");
                var bounds = SwingUtilities.convertRectangle(reminder.getParent(),reminder.getBounds(),shell);
                assertTrue(bounds.y >= 0 && bounds.y+bounds.height <= shell.getHeight());
                return null;
            });
        }
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container parent) {
            parent.doLayout();
            for (Component child : parent.getComponents()) layoutTree(child);
        }
    }

    // ----- Helpers -----

    private static ShellPanel open(Locale locale, LocalAppData location, AppContext shop) throws Exception {
        ShellPanel shell = edt(() -> new ShellPanel(Messages.load(locale), location, () -> shop, () -> { }));
        edt(() -> { shell.showMain(shop); return null; });
        await(edt(shell::productsReady));
        return shell;
    }

    /** A real backup of the open shop, made the way the Settings screen makes one. */
    private static Path backUp(LocalAppData location, AppContext shop, DataFolderCoordinator coordinator, Path folder) throws Exception {
        Clock clock = Clock.systemDefaultZone();
        EvidenceService evidence = new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), clock);
        return new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidence, clock).create(folder).file();
    }

    private static List<String> entries(Component root) {
        JList<?> navigation = find(root, JList.class, null);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < navigation.getModel().getSize(); i++) labels.add(navigation.getModel().getElementAt(i).toString());
        return labels;
    }

    private static String words(Component root, String name) {
        JLabel label = find(root, JLabel.class, name);
        if (label == null) return fail("No label " + name);
        String words = label.getAccessibleContext().getAccessibleName();
        return words == null ? "" : words;
    }

    private static AbstractButton button(Component root, String name) {
        AbstractButton button = find(root, AbstractButton.class, name);
        return button != null ? button : fail("No button " + name);
    }

    private static <T extends Component> T only(Component root, Class<T> type) {
        assertEquals(1, count(root, type));
        return find(root, type, null);
    }

    private static int count(Component component, Class<?> type) {
        int found = type.isInstance(component) ? 1 : 0;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) found += count(child, type);
        }
        return found;
    }

    private static <T extends Component> T find(Component component, Class<T> type, String name) {
        if (type.isInstance(component) && (name == null || (component instanceof JComponent c && name.equals(c.getName())))) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T edt(ThrowingSupplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.get()); } catch (Throwable thrown) { failure.set(thrown); }
        });
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() instanceof Exception exception) throw exception;
        return result.get();
    }

    private interface ThrowingSupplier<T> { T get() throws Exception; }

    /** Waits off the Swing thread, then lets the screen finish showing the result. */
    private static void await(CompletionStage<?> work) throws Exception {
        work.toCompletableFuture().handle((value, failure) -> null).get(30, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
    }
}

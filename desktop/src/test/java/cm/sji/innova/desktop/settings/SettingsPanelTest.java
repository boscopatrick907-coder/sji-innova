package cm.sji.innova.desktop.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.sale.SaleItemRequest;
import cm.sji.innova.desktop.backup.BackupException;
import cm.sji.innova.desktop.backup.BackupService;
import cm.sji.innova.desktop.backup.RestoreException;
import cm.sji.innova.desktop.backup.RestorePreparation;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.FolderMaintenanceException;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OFF-03c: the Settings screen's backup and restore cards. Most tests give the screen fake actions, which
 * check that every piece of work runs off the Swing event thread; the last one uses the shop's own services.
 * Texts are read through each message's accessible name, the plain words a screen reader gets.
 */
class SettingsPanelTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneId.of("Africa/Douala"));

    @TempDir Path directory;

    // ----- Opening the screen -----

    @Test
    void buildingTheScreenReadsNothingAndReloadingShowsTheLastBackupAndItsFolder() throws Exception {
        FakeActions actions = new FakeActions();
        actions.last = Optional.of(new BackupService.LastBackup(UUID.randomUUID(), Path.of("E:", "Cella backups", "cella-backup-1.zip"),
                "0".repeat(64), CLOCK.instant().minus(Duration.ofDays(3))));
        SettingsPanel panel = panel(actions, new FakeChooser(), Locale.ENGLISH);
        assertEquals(0, actions.calls.size(), "Building a hidden screen reads nothing");

        await(edt(panel::reload));

        edt(() -> {
            assertTrue(words(panel, "settingsLastBackup").startsWith("Last backup: "), words(panel, "settingsLastBackup"));
            assertTrue(words(panel, "settingsLastBackup").endsWith("(3 days ago)"), words(panel, "settingsLastBackup"));
            assertEquals(Path.of("E:", "Cella backups").toString(), words(panel, "settingsFolder"), "The last backup's folder is offered again");
            assertTrue(button(panel, "settingsCreateBackup").isEnabled());
            assertEquals("Replace this shop with a backup.", words(panel, "settingsRestoreSummary"));
            assertTrue(button(panel, "settingsChooseBackup").isVisible());
            assertFalse(button(panel, "settingsCancelScheduled").isVisible());
            return null;
        });
        assertEquals(List.of("lastBackup", "scheduledRestore"), actions.calls);
    }

    @Test
    void withNoBackupYetAFolderMustBeChosenFirst() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);
        await(edt(panel::reload));
        edt(() -> {
            assertEquals("No backup yet.", words(panel, "settingsLastBackup"));
            assertEquals("No folder chosen yet", words(panel, "settingsFolder"));
            assertFalse(button(panel, "settingsCreateBackup").isEnabled(), "No folder, no backup");
            return null;
        });

        chooser.folder = Optional.of(directory.resolve("usb"));
        edt(() -> { button(panel, "settingsChooseFolder").doClick(); return null; });

        edt(() -> {
            assertEquals(directory.resolve("usb").toString(), words(panel, "settingsFolder"));
            assertTrue(button(panel, "settingsCreateBackup").isEnabled());
            return null;
        });
    }

    // ----- Making a backup -----

    @Test
    void aBackupRunsOffTheSwingThreadWithEveryActionDisabledThenShowsItsResult() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        chooser.folder = Optional.of(directory.resolve("usb"));
        actions.hold = new CountDownLatch(1);
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);
        edt(() -> { button(panel, "settingsChooseFolder").doClick(); button(panel, "settingsCreateBackup").doClick(); return null; });

        edt(() -> {
            assertEquals("Making the backup… Sales and stock wait until it is done.", words(panel, "settingsBackupFeedback"));
            assertFalse(button(panel, "settingsCreateBackup").isEnabled());
            assertFalse(button(panel, "settingsChooseFolder").isEnabled());
            assertFalse(button(panel, "settingsChooseBackup").isEnabled());
            return null;
        });
        CompletionStage<Void> reloadMeanwhile = edt(panel::reload);   // reads nothing while the backup runs
        actions.hold.countDown();
        await(reloadMeanwhile);
        await(edt(panel::lastWork));

        edt(() -> {
            assertEquals("Backup saved: cella-backup-2026-11-12-090000-1a2b3c4d.zip", words(panel, "settingsBackupFeedback"));
            assertTrue(words(panel, "settingsLastBackup").endsWith("(today)"), words(panel, "settingsLastBackup"));
            assertTrue(button(panel, "settingsCreateBackup").isEnabled());
            return null;
        });
        assertEquals(List.of("backUp " + directory.resolve("usb")), actions.calls);
    }

    @Test
    void aRefusedBackupSaysWhyInTheShopsWords() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        chooser.folder = Optional.of(directory.resolve("usb"));
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);
        edt(() -> { button(panel, "settingsChooseFolder").doClick(); return null; });

        Map<Exception, String> refusals = Map.of(
                new FolderMaintenanceException(FolderMaintenanceException.Reason.OTHER_INSTANCE, "x"),
                "Another Cella has this shop open. Close it, then try again.",
                new FolderMaintenanceException(FolderMaintenanceException.Reason.OPERATION_RUNNING, "x"),
                "A delivery, sale or import is in progress. Finish it, then try again.",
                new BackupException(BackupException.Reason.REQUEST_PENDING, List.of("pending-checkout.txt"), "x"),
                "A saved request is waiting: pending-checkout.txt. Finish or retry it first.",
                new BackupException(BackupException.Reason.EVIDENCE_UNRESOLVED, List.of("a.properties", "b.properties"), "x"),
                "Set-aside files are waiting for a decision: a.properties, b.properties.",
                new BackupException(BackupException.Reason.DESTINATION_REFUSED, List.of(), "x"),
                "Choose a folder outside the shop’s data folder, ideally on a USB key or another disk.");
        for (var refusal : refusals.entrySet()) {
            actions.failure = refusal.getKey();
            edt(() -> { button(panel, "settingsCreateBackup").doClick(); return null; });
            await(edt(panel::lastWork));
            edt(() -> {
                assertEquals(refusal.getValue(), words(panel, "settingsBackupFeedback"));
                assertTrue(button(panel, "settingsCreateBackup").isEnabled(), "It can be tried again");
                return null;
            });
        }
    }

    // ----- Restoring -----

    @Test
    void aBackupOfAnotherShopShowsWhatItHoldsAndBothWarningsThenIsScheduledThenCancelled() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        chooser.file = Optional.of(directory.resolve("usb").resolve("cella-backup.zip"));
        actions.summary = summary(false, 2);
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);

        edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
        await(edt(panel::lastWork));

        edt(() -> {
            String summary = words(panel, "settingsRestoreSummary");
            assertTrue(summary.contains("Shop: another shop (" + actions.summary.shopId() + ")"), summary);
            assertTrue(summary.contains("2 products, 1 sales, 3 stock movements, 2 photos"), summary);
            assertTrue(summary.contains("2 set-aside files, kept without being sent again"), summary);
            String warning = words(panel, "settingsRestoreWarning");
            assertTrue(warning.startsWith("This backup is of another shop."), warning);
            assertTrue(warning.contains("Records made after this backup will be replaced."), warning);
            assertTrue(button(panel, "settingsConfirmRestore").isVisible());
            assertTrue(button(panel, "settingsCancelPrepared").isVisible());
            assertFalse(button(panel, "settingsChooseBackup").isVisible());
            return null;
        });

        edt(() -> { button(panel, "settingsConfirmRestore").doClick(); return null; });
        await(edt(panel::lastWork));
        edt(() -> {
            assertTrue(words(panel, "settingsRestoreWarning").startsWith("A restore is scheduled. Close Cella and open it again"),
                    words(panel, "settingsRestoreWarning"));
            assertTrue(button(panel, "settingsCancelScheduled").isVisible());
            assertFalse(button(panel, "settingsConfirmRestore").isVisible());
            return null;
        });

        edt(() -> { button(panel, "settingsCancelScheduled").doClick(); return null; });
        await(edt(panel::lastWork));
        edt(() -> {
            assertEquals("The restore was cancelled. Nothing was changed.", words(panel, "settingsRestoreFeedback"));
            assertTrue(button(panel, "settingsChooseBackup").isVisible());
            assertFalse(button(panel, "settingsCancelScheduled").isVisible());
            return null;
        });
        assertEquals(List.of("prepare " + chooser.file.orElseThrow(), "schedule " + actions.summary.restoreId(), "cancelScheduled"), actions.calls);
    }

    @Test
    void aPreparedRestoreCanBeDiscardedAndABackupOfThisShopHasNoShopWarning() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        chooser.file = Optional.of(directory.resolve("cella-backup.zip"));
        actions.summary = summary(true, 0);
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);
        edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
        await(edt(panel::lastWork));
        edt(() -> {
            assertTrue(words(panel, "settingsRestoreSummary").contains("Shop: this shop"));
            assertFalse(words(panel, "settingsRestoreSummary").contains("set-aside"), "No line for no set-aside file");
            assertTrue(words(panel, "settingsRestoreWarning").startsWith("Records made after this backup will be replaced."));
            return null;
        });

        edt(() -> { button(panel, "settingsCancelPrepared").doClick(); return null; });
        await(edt(panel::lastWork));

        edt(() -> {
            assertEquals("Nothing was changed.", words(panel, "settingsRestoreFeedback"));
            assertEquals("Replace this shop with a backup.", words(panel, "settingsRestoreSummary"));
            assertTrue(button(panel, "settingsChooseBackup").isVisible());
            return null;
        });
        assertEquals("discard " + actions.summary.restoreId(), actions.calls.getLast());
    }

    @Test
    void aFileThatCannotBeRestoredIsRefusedWithItsReasonAndNothingIsScheduled() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        chooser.file = Optional.of(directory.resolve("notes.zip"));
        actions.failure = new BackupException(BackupException.Reason.CHECK_FAILED, List.of("notes.zip"), "The backup cannot be used: Unknown backup format");
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);

        edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
        await(edt(panel::lastWork));

        edt(() -> {
            assertEquals("This file is not a Cella backup that can be used. The backup cannot be used: Unknown backup format",
                    words(panel, "settingsRestoreFeedback"));
            assertTrue(button(panel, "settingsChooseBackup").isVisible() && button(panel, "settingsChooseBackup").isEnabled());
            assertFalse(button(panel, "settingsConfirmRestore").isVisible());
            return null;
        });

        actions.failure = new RestoreException(RestoreException.Reason.ALREADY_SCHEDULED, "x");
        edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
        await(edt(panel::lastWork));
        edt(() -> { assertEquals("A restore is already scheduled. Cancel it first.", words(panel, "settingsRestoreFeedback")); return null; });

        actions.failure = null;
        actions.summary = summary(true, 0);
        edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
        await(edt(panel::lastWork));
        edt(() -> {
            assertEquals("", words(panel, "settingsRestoreFeedback"), "An earlier refusal no longer shows");
            assertTrue(button(panel, "settingsConfirmRestore").isVisible());
            return null;
        });
    }

    @Test
    void aScheduledRestoreThatCannotBeReadIsShownWithAWayToCancelIt() throws Exception {
        FakeActions actions = new FakeActions();
        actions.scheduledFailure = new RestoreException(RestoreException.Reason.NOTE_DAMAGED, "x");
        SettingsPanel panel = panel(actions, new FakeChooser(), Locale.ENGLISH);

        await(edt(panel::reload));

        edt(() -> {
            assertEquals("No backup yet.", words(panel, "settingsLastBackup"));
            assertEquals("The scheduled restore cannot be read. Cancel it, then choose the backup again.", words(panel, "settingsRestoreFeedback"));
            assertTrue(button(panel, "settingsCancelScheduled").isVisible());
            assertFalse(button(panel, "settingsChooseBackup").isVisible());
            button(panel, "settingsCancelScheduled").doClick();
            return null;
        });
        await(edt(panel::lastWork));
        edt(() -> {
            assertTrue(button(panel, "settingsChooseBackup").isVisible());
            assertFalse(button(panel, "settingsCancelScheduled").isVisible());
            return null;
        });
        assertEquals("cancelScheduled", actions.calls.getLast());
    }

    @Test
    void reloadingKeepsAChosenBackupAndStillShowsTheLastBackupWhenTheRestoreStateCannotBeRead() throws Exception {
        FakeActions actions = new FakeActions();
        FakeChooser chooser = new FakeChooser();
        chooser.file = Optional.of(directory.resolve("cella-backup.zip"));
        actions.summary = summary(true, 0);
        SettingsPanel panel = panel(actions, chooser, Locale.ENGLISH);
        edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
        await(edt(panel::lastWork));

        actions.scheduledFailure = new java.io.IOException("The disk is not ready");
        await(edt(panel::reload));

        edt(() -> {
            assertEquals("No backup yet.", words(panel, "settingsLastBackup"), "The last backup is shown even so");
            assertEquals("Could not finish. The disk is not ready", words(panel, "settingsRestoreFeedback"));
            assertTrue(button(panel, "settingsConfirmRestore").isVisible(), "The chosen backup is still there to confirm");
            return null;
        });
    }

    // ----- Words -----

    @Test
    void everyWordIsInFrenchAndInEnglishWithTheSamePlaceholders() throws Exception {
        ResourceBundle english = ResourceBundle.getBundle(SettingsPanel.BUNDLE, Locale.ENGLISH);
        ResourceBundle french = ResourceBundle.getBundle(SettingsPanel.BUNDLE, Locale.FRENCH);
        assertEquals(english.keySet(), french.keySet());
        for (String key : english.keySet()) {
            assertFalse(english.getString(key).isBlank(), key);
            assertFalse(french.getString(key).isBlank(), key);
            assertEquals(new MessageFormat(english.getString(key)).getFormats().length, new MessageFormat(french.getString(key)).getFormats().length, key);
        }
        SettingsPanel panel = panel(new FakeActions(), new FakeChooser(), Locale.FRENCH);
        await(edt(panel::reload));
        edt(() -> {
            assertEquals("Aucune sauvegarde pour l’instant.", words(panel, "settingsLastBackup"));
            assertEquals("Créer une sauvegarde", button(panel, "settingsCreateBackup").getText());
            return null;
        });
    }

    // ----- On the shop's own services -----

    @Test
    void onTheShopsOwnServicesABackupIsMadeThenARestoreIsScheduledAndCancelled() throws Exception {
        LocalAppData location = new LocalAppData(directory.resolve("data"));
        DataFolderCoordinator coordinator = DataFolderCoordinator.forFolder(location);
        try (DataFolderCoordinator.Membership membership = coordinator.join()) {
            AppContext shop = AppContextLoader.load(location);
            Product rice = shop.products().create(shop.shopId(), "Rice", 1_000);
            shop.stock().receive(UUID.randomUUID(), shop.shopId(), rice.id(), shop.deviceId(), 10_000, "Delivery");
            shop.sales().makeCashSale(UUID.randomUUID(), shop.shopId(), shop.deviceId(), "REC-1", List.of(new SaleItemRequest(rice.id(), 1_000)));
            FakeChooser chooser = new FakeChooser();
            chooser.folder = Optional.of(directory.resolve("usb"));
            SettingsPanel panel = panel(new SettingsPanel.ServiceActions(location, shop, CLOCK), chooser, Locale.ENGLISH);

            await(edt(panel::reload));
            edt(() -> { assertEquals("No backup yet.", words(panel, "settingsLastBackup")); return null; });
            edt(() -> { button(panel, "settingsChooseFolder").doClick(); button(panel, "settingsCreateBackup").doClick(); return null; });
            await(edt(panel::lastWork));

            List<Path> made;
            try (var files = Files.list(directory.resolve("usb"))) { made = files.toList(); }
            assertEquals(1, made.size(), "One backup on the key");
            edt(() -> {
                assertEquals("Backup saved: " + made.getFirst().getFileName(), words(panel, "settingsBackupFeedback"));
                assertTrue(words(panel, "settingsLastBackup").endsWith("(today)"));
                return null;
            });

            chooser.file = Optional.of(made.getFirst());
            edt(() -> { button(panel, "settingsChooseBackup").doClick(); return null; });
            await(edt(panel::lastWork));
            edt(() -> {
                assertTrue(words(panel, "settingsRestoreSummary").contains("1 products, 1 sales, 2 stock movements"), words(panel, "settingsRestoreSummary"));
                button(panel, "settingsConfirmRestore").doClick();
                return null;
            });
            await(edt(panel::lastWork));
            RestorePreparation restores = new RestorePreparation(location, shop.shopId(), CLOCK);
            assertTrue(restores.scheduled().isPresent(), "The restore is scheduled for the next start");

            edt(() -> { button(panel, "settingsCancelScheduled").doClick(); return null; });
            await(edt(panel::lastWork));
            assertEquals(Optional.empty(), restores.scheduled(), "and cancelled");
        }
    }

    // ----- Helpers -----

    /** Answers from its fields, records each call, and fails any call made on the Swing event thread. */
    private static final class FakeActions implements SettingsPanel.Actions {
        final List<String> calls = new ArrayList<>();
        Optional<BackupService.LastBackup> last = Optional.empty();
        Exception failure;
        Exception scheduledFailure;
        RestorePreparation.Summary summary;
        CountDownLatch hold;

        private void called(String call) throws Exception {
            if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Work on the Swing thread: " + call);
            calls.add(call);
            if (hold != null && !hold.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            if (failure != null && !call.equals("lastBackup") && !call.equals("scheduledRestore")) throw failure;
        }

        @Override public Optional<BackupService.LastBackup> lastBackup() {
            try { called("lastBackup"); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
            return last;
        }

        @Override public Optional<RestorePreparation.Summary> scheduledRestore() throws Exception {
            called("scheduledRestore");
            if (scheduledFailure != null) throw scheduledFailure;
            return Optional.empty();
        }

        @Override public BackupService.Backup backUp(Path folder) throws Exception {
            called("backUp " + folder);
            return new BackupService.Backup(folder.resolve("cella-backup-2026-11-12-090000-1a2b3c4d.zip"), "0".repeat(64), UUID.randomUUID(),
                    CLOCK.instant(), UUID.randomUUID(), 9, 2, 1, 3, 2, 0);
        }

        @Override public RestorePreparation.Summary prepare(Path backup) throws Exception {
            called("prepare " + backup);
            return summary;
        }

        @Override public void schedule(RestorePreparation.Summary scheduled) throws Exception {
            called("schedule " + scheduled.restoreId());
        }

        @Override public void discard(UUID restoreId) throws Exception {
            called("discard " + restoreId);
        }

        @Override public boolean cancelScheduled() throws Exception {
            called("cancelScheduled");
            return true;
        }
    }

    /** Answers the dialogs without showing any. */
    private static final class FakeChooser implements SettingsPanel.Chooser {
        Optional<Path> folder = Optional.empty();
        Optional<Path> file = Optional.empty();

        @Override public Optional<Path> folder(Component parent, Path current, String title) { return folder; }

        @Override public Optional<Path> backup(Component parent, Path folder, String title, String filter) { return file; }
    }

    private static RestorePreparation.Summary summary(boolean sameShop, int evidence) {
        return new RestorePreparation.Summary(UUID.randomUUID(), "0".repeat(64), UUID.randomUUID(), sameShop, UUID.randomUUID(),
                Instant.parse("2026-11-10T14:30:00Z"), 9, 2, 1, 3, 2, evidence, Map.of("cella.db", "0".repeat(64)));
    }

    private SettingsPanel panel(SettingsPanel.Actions actions, SettingsPanel.Chooser chooser, Locale locale) throws Exception {
        return edt(() -> new SettingsPanel(actions, chooser, locale, CLOCK));
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

    private static void await(CompletionStage<Void> work) throws Exception {
        work.toCompletableFuture().get(30, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });   // the screen has shown the result
    }

    /** The plain words of a message: its accessible name, set with its text. */
    private static String words(Component root, String name) {
        JComponent found = find(root, name);
        if (!(found instanceof JLabel label)) return fail("No label " + name);
        String words = label.getAccessibleContext().getAccessibleName();
        return words == null ? "" : words;
    }

    private static AbstractButton button(Component root, String name) {
        return find(root, name) instanceof AbstractButton button ? button : fail("No button " + name);
    }

    private static JComponent find(Component component, String name) {
        if (component instanceof JComponent c && name.equals(c.getName())) return c;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JComponent found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}

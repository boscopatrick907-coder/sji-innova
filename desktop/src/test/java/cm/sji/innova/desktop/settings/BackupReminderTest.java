package cm.sji.innova.desktop.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.desktop.backup.BackupService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** OFF-03c4b: the reminder after 7 days without a backup, as agreed with Patrick on 14 November 2026. */
class BackupReminderTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneId.of("Africa/Douala"));

    @Test
    void dueWithNoBackupOrOneSevenDaysOldButNotBefore() {
        assertEquals(Duration.ofDays(7), BackupReminder.AFTER);
        assertTrue(BackupReminder.due(Optional.empty(), CLOCK), "No backup at all: the safe side");
        assertFalse(BackupReminder.due(madeAgo(Duration.ofDays(7).minusMinutes(1)), CLOCK), "Just under 7 days");
        assertTrue(BackupReminder.due(madeAgo(Duration.ofDays(7)), CLOCK), "7 days");
        assertTrue(BackupReminder.due(madeAgo(Duration.ofDays(30)), CLOCK));
        assertFalse(BackupReminder.due(madeAgo(Duration.ofHours(-2)), CLOCK), "A clock set back does not nag");
    }

    @Test
    void theCardWordsSayHowOldTheLastBackupIsInBothLanguages() {
        ResourceBundle english = ResourceBundle.getBundle(SettingsPanel.BUNDLE, Locale.ENGLISH);
        ResourceBundle french = ResourceBundle.getBundle(SettingsPanel.BUNDLE, Locale.FRENCH);
        assertEquals(Optional.empty(), BackupReminder.cardText(madeAgo(Duration.ofDays(2)), CLOCK, english, Locale.ENGLISH));
        assertEquals(Optional.of("Make a first backup: it protects the shop if this computer breaks or is lost."),
                BackupReminder.cardText(Optional.empty(), CLOCK, english, Locale.ENGLISH));
        assertEquals(Optional.of("The last backup was 12 days ago. Make a new one: it protects the shop if this computer breaks or is lost."),
                BackupReminder.cardText(madeAgo(Duration.ofDays(12).plusHours(5)), CLOCK, english, Locale.ENGLISH));
        assertEquals(Optional.of("La dernière sauvegarde date d’il y a 12 jours. Faites-en une nouvelle : elle protège la boutique si cet ordinateur tombe en panne ou est perdu."),
                BackupReminder.cardText(madeAgo(Duration.ofDays(12)), CLOCK, french, Locale.FRENCH));
    }

    @Test
    void theReminderReadsOffTheSwingThreadShowsWhenDueAndOpensSettings() throws Exception {
        List<String> reads = new ArrayList<>();
        AtomicReference<Optional<BackupService.LastBackup>> last = new AtomicReference<>(Optional.empty());
        Supplier<Optional<BackupService.LastBackup>> reader = () -> {
            reads.add("read on the Swing thread: " + SwingUtilities.isEventDispatchThread());
            return last.get();
        };
        List<String> opened = new ArrayList<>();
        BackupReminder reminder = edt(() -> new BackupReminder(reader, () -> opened.add("settings"), Locale.ENGLISH, CLOCK));
        assertEquals(List.of(), reads, "Building it reads nothing");
        edt(() -> { assertFalse(reminder.isVisible(), "Hidden until it has read"); return null; });

        await(edt(reminder::check));
        edt(() -> {
            assertTrue(reminder.isVisible());
            assertEquals("No backup yet. Back up now", reminder.getAccessibleContext().getAccessibleName());
            assertTrue(reminder.isFocusable(), "The keyboard reaches it");
            reminder.doClick();
            return null;
        });
        assertEquals(List.of("settings"), opened);

        last.set(madeAgo(Duration.ofDays(9)));
        await(edt(reminder::check));
        edt(() -> { assertEquals("Last backup 9 days ago. Back up now", reminder.getAccessibleContext().getAccessibleName()); return null; });

        last.set(madeAgo(Duration.ofHours(1)));
        await(edt(reminder::check));
        edt(() -> { assertFalse(reminder.isVisible(), "A recent backup: no reminder"); return null; });
        assertEquals(List.of("read on the Swing thread: false", "read on the Swing thread: false", "read on the Swing thread: false"), reads);
    }

    @Test
    void inFrenchAndWhenTheRecordCannotBeReadItAsksForABackup() throws Exception {
        BackupReminder reminder = edt(() -> new BackupReminder(() -> { throw new IllegalStateException("unreadable"); }, () -> { },
                Locale.FRENCH, CLOCK));
        await(edt(reminder::check));
        edt(() -> {
            assertTrue(reminder.isVisible());
            assertEquals("Aucune sauvegarde. Sauvegarder", reminder.getAccessibleContext().getAccessibleName());
            return null;
        });
        BackupReminder old = edt(() -> new BackupReminder(() -> madeAgo(Duration.ofDays(8)), () -> { }, Locale.FRENCH, CLOCK));
        await(edt(old::check));
        edt(() -> { assertEquals("Sauvegarde il y a 8 jours. Sauvegarder", old.getAccessibleContext().getAccessibleName()); return null; });
    }

    @Test
    void anOlderReminderReadCannotOverwriteARecentBackup() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        BackupReminder reminder = edt(() -> new BackupReminder(() -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("not released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return Optional.empty();
            }
            return madeAgo(Duration.ofHours(1));
        }, () -> { }, Locale.ENGLISH, CLOCK));
        var old = edt(reminder::check);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try { await(edt(reminder::check)); }
        finally { release.countDown(); }
        await(old);
        edt(() -> { assertFalse(reminder.isVisible(), "An older read must not bring back a cleared reminder"); return null; });
    }

    private static Optional<BackupService.LastBackup> madeAgo(Duration age) {
        return Optional.of(new BackupService.LastBackup(UUID.randomUUID(), Path.of("E:", "cella-backup.zip"), "0".repeat(64),
                CLOCK.instant().minus(age)));
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

    private static void await(java.util.concurrent.CompletionStage<Void> work) throws Exception {
        work.toCompletableFuture().get(30, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
    }
}

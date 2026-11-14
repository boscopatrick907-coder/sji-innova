package cm.sji.innova.desktop.settings;

import cm.sji.innova.desktop.backup.BackupService;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

/**
 * OFF-03c4b: the backup reminder in the sidebar, under the navigation. It shows from start-up when no
 * backup was made in the last {@link #AFTER} days, or none at all, and opens Settings when clicked. It sits
 * in the sidebar so it takes no room from the screens: the smallest window keeps its layout.
 *
 * <p>It is one button, so the keyboard reaches it and a screen reader reads its words. {@link #check()}
 * reads the last backup in the background; the reminder never touches files on the Swing event thread.
 */
public final class BackupReminder extends JButton {
    /** Agreed with Patrick on 14 November 2026: remind after 7 days without a backup. */
    public static final Duration AFTER = Duration.ofDays(7);
    /** Long texts wrap at this many screen pixels; Swing's HTML counts one pt as one pixel. */
    private static final int TEXT_WIDTH = 120;

    private final Supplier<Optional<BackupService.LastBackup>> lastBackup;
    private final ResourceBundle texts;
    private final Locale locale;
    private final Clock clock;
    private CompletionStage<Void> lastCheck = CompletableFuture.completedFuture(null);

    /** The reminder for the shop in {@code location}; {@code open} shows Settings. */
    public BackupReminder(LocalAppData location, Messages messages, Runnable open) {
        this(() -> BackupService.lastSuccess(location), open, messages.bundle().getLocale(), Clock.systemDefaultZone());
    }

    BackupReminder(Supplier<Optional<BackupService.LastBackup>> lastBackup, Runnable open, Locale locale, Clock clock) {
        this.lastBackup = Objects.requireNonNull(lastBackup, "lastBackup");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.texts = ResourceBundle.getBundle(SettingsPanel.BUNDLE, locale);
        Objects.requireNonNull(open, "open");
        setName("backupReminder");
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 10));
        setHorizontalAlignment(SwingConstants.LEFT);
        setForeground(Color.WHITE);
        setFont(getFont().deriveFont(12f));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        putClientProperty("JComponent.minimumHeight", 0);
        setVisible(false);
        addActionListener(event -> open.run());
    }

    /** True when no backup was made in the last {@link #AFTER}, or none at all: the safe side. */
    static boolean due(Optional<BackupService.LastBackup> last, Clock clock) {
        return last.map(backup -> backup.age(clock).compareTo(AFTER) >= 0).orElse(true);
    }

    /** The reminder's words for the Settings card, or empty when no reminder is due. */
    static Optional<String> cardText(Optional<BackupService.LastBackup> last, Clock clock, ResourceBundle texts, Locale locale) {
        if (!due(last, clock)) return Optional.empty();
        return Optional.of(last.map(backup -> new MessageFormat(texts.getString("settings.reminder.card.old"), locale)
                        .format(new Object[] {backup.age(clock).toDays()}))
                .orElse(texts.getString("settings.reminder.card.none")));
    }

    /** Reads the last backup off the Swing event thread, then shows the reminder if one is due, or hides it. */
    public CompletionStage<Void> check() {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        lastCheck = finished;
        new SwingWorker<Optional<BackupService.LastBackup>, Void>() {
            @Override protected Optional<BackupService.LastBackup> doInBackground() {
                return lastBackup.get();
            }

            @Override protected void done() {
                try {
                    show(get());
                } catch (ExecutionException unreadable) {
                    show(Optional.empty());   // the safe side: ask for a backup
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.complete(null);
                }
            }
        }.execute();
        return finished;
    }

    /** The last {@link #check()}, finished once the reminder shows its result. For tests. */
    CompletionStage<Void> lastCheck() {
        return lastCheck;
    }

    private void show(Optional<BackupService.LastBackup> last) {
        if (!due(last, clock)) {
            setVisible(false);
            return;
        }
        String state = last.map(backup -> new MessageFormat(texts.getString("settings.reminder.old"), locale)
                        .format(new Object[] {backup.age(clock).toDays()}))
                .orElse(texts.getString("settings.reminder.none"));
        String action = texts.getString("settings.reminder.action");
        setText("<html><div style='width:" + TEXT_WIDTH + "pt'><b>!</b>&nbsp; " + escape(state)
                + "<br><font color='#D4E0B8'><u>" + escape(action) + "</u></font></div></html>");
        getAccessibleContext().setAccessibleName(state + ". " + action);
        setToolTipText(state);
        setVisible(true);
        revalidate();
        repaint();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, getModel().isRollover() ? 40 : 26));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
        g.setColor(new Color(0xF3D38B));
        g.fillRoundRect(0, 10, 3, Math.max(10, getHeight() - 20), 3, 3);
        if (isFocusOwner()) {
            g.setColor(new Color(255, 255, 255, 150));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 15, 15);
        }
        g.dispose();
        super.paintComponent(graphics);
    }
}

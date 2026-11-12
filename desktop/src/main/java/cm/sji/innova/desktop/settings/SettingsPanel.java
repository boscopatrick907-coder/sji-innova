package cm.sji.innova.desktop.settings;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.desktop.backup.BackupException;
import cm.sji.innova.desktop.backup.BackupService;
import cm.sji.innova.desktop.backup.RestoreException;
import cm.sji.innova.desktop.backup.RestorePreparation;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.FolderMaintenanceException;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * OFF-03c: the Settings screen's backup and restore cards. Every piece of file and database work runs in a
 * background worker through {@link Actions}; this class only builds and updates components, with the shared
 * {@link CatalogStyle} and {@link FeedbackStrip}, so the approved style can change without touching the
 * backup and restore logic. Its words come from its own French and English bundle. Building the screen reads
 * nothing: {@link #reload()} does, when the screen is shown.
 */
public final class SettingsPanel extends JPanel {
    static final String BUNDLE = "cm.sji.innova.desktop.settings.settings";
    /**
     * Long messages wrap at this many screen pixels, so they fit inside a feedback strip in the 720px column.
     * Swing's HTML counts one {@code pt} as one pixel (and one {@code px} as 1.3), so the width is given in pt.
     */
    private static final int TEXT_WIDTH = 600;

    /** The backup and restore work the screen asks for. Every method runs off the Swing event thread. */
    interface Actions {
        Optional<BackupService.LastBackup> lastBackup();
        Optional<RestorePreparation.Summary> scheduledRestore() throws Exception;
        BackupService.Backup backUp(Path folder) throws Exception;
        RestorePreparation.Summary prepare(Path backup) throws Exception;
        void schedule(RestorePreparation.Summary summary) throws Exception;
        void discard(UUID restoreId) throws Exception;
        boolean cancelScheduled() throws Exception;
    }

    /** Asks the person for a folder or a backup file, on the Swing event thread. */
    interface Chooser {
        Optional<Path> folder(Component parent, Path current, String title);
        Optional<Path> backup(Component parent, Path folder, String title, String filter);
    }

    /** What {@link #reload()} reads; {@code unreadable} is why the scheduled restore could not be read, if it could not. */
    private record State(Optional<BackupService.LastBackup> last, Optional<RestorePreparation.Summary> scheduled, Exception unreadable) { }

    private final Actions actions;
    private final Chooser chooser;
    private final ResourceBundle texts;
    private final Locale locale;
    private final Clock clock;

    private final JLabel lastBackup = text("settingsLastBackup");
    private final JLabel folderLabel = text("settingsFolder");
    private final JLabel backupMessage = text("settingsBackupFeedback");
    private final FeedbackStrip backupStrip = CatalogStyle.feedback(backupMessage, FeedbackStrip.Tone.INFO);
    private final JLabel summary = text("settingsRestoreSummary");
    private final JLabel restoreWarning = text("settingsRestoreWarning");
    private final FeedbackStrip warningStrip = CatalogStyle.feedback(restoreWarning, FeedbackStrip.Tone.WARNING);
    private final JLabel restoreMessage = text("settingsRestoreFeedback");
    private final FeedbackStrip restoreStrip = CatalogStyle.feedback(restoreMessage, FeedbackStrip.Tone.INFO);
    private final JButton chooseFolder;
    private final JButton createBackup;
    private final JButton chooseBackup;
    private final JButton confirmRestore;
    private final JButton cancelPrepared;
    private final JButton cancelScheduled;

    private Path folder;
    private RestorePreparation.Summary prepared;
    private RestorePreparation.Summary scheduled;
    private boolean noteDamaged;
    private boolean busy;
    private CompletionStage<Void> lastWork = CompletableFuture.completedFuture(null);

    /** The screen on the shop's own backup and restore services. */
    public SettingsPanel(LocalAppData location, AppContext context, Messages messages) {
        this(new ServiceActions(location, context, Clock.systemDefaultZone()), new Dialogs(), messages.bundle().getLocale(),
                Clock.systemDefaultZone());
    }

    SettingsPanel(Actions actions, Chooser chooser, Locale locale, Clock clock) {
        super(new BorderLayout(0, 18));
        this.actions = Objects.requireNonNull(actions, "actions");
        this.chooser = Objects.requireNonNull(chooser, "chooser");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.texts = ResourceBundle.getBundle(BUNDLE, locale);
        setBackground(CatalogStyle.PAPER);
        setBorder(new EmptyBorder(26, 30, 22, 30));

        JPanel titles = CatalogStyle.panel(new BorderLayout(0, 6));
        titles.add(CatalogStyle.label(t("settings.title"), 28, true), BorderLayout.NORTH);
        JLabel subtitle = CatalogStyle.label(t("settings.subtitle"), 14, false);
        subtitle.setForeground(CatalogStyle.MUTED);
        titles.add(subtitle, BorderLayout.CENTER);
        add(titles, BorderLayout.NORTH);

        chooseFolder = button("settingsChooseFolder", t("settings.backup.choose"), false, this::chooseFolder);
        createBackup = button("settingsCreateBackup", t("settings.backup.create"), true, this::createBackup);
        chooseBackup = button("settingsChooseBackup", t("settings.restore.choose"), false, this::chooseBackup);
        confirmRestore = button("settingsConfirmRestore", t("settings.restore.confirm"), true, this::confirmRestore);
        cancelPrepared = button("settingsCancelPrepared", t("settings.restore.cancel"), false, this::cancelPrepared);
        cancelScheduled = button("settingsCancelScheduled", t("settings.restore.cancelScheduled"), false, this::cancelScheduled);

        JPanel column = CatalogStyle.panel(null);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(left(CatalogStyle.widthLimited(backupCard(), 720)));
        column.add(javax.swing.Box.createVerticalStrut(18));
        column.add(left(CatalogStyle.widthLimited(restoreCard(), 720)));
        JPanel top = CatalogStyle.panel(new BorderLayout());   // the cards keep their own height
        top.add(column, BorderLayout.NORTH);
        add(CatalogStyle.scrollContent(top, 320), BorderLayout.CENTER);

        say(lastBackup, t("settings.loading"));
        showFolder();
        refresh();
    }

    // ----- What the screen can do -----

    /**
     * Reads the last backup and any scheduled restore, off the Swing event thread. While other work runs it
     * reads nothing and returns that work. A backup the person has chosen but not yet confirmed stays chosen.
     */
    public CompletionStage<Void> reload() {
        if (busy) return lastWork;
        return work(restoreMessage, restoreStrip, "", () -> {
            Optional<BackupService.LastBackup> last = actions.lastBackup();
            try {
                return new State(last, actions.scheduledRestore(), null);
            } catch (Exception unreadable) {
                return new State(last, Optional.empty(), unreadable);
            }
        }, state -> {
            showLast(state.last());
            if (folder == null) state.last().map(b -> b.file().getParent()).ifPresent(parent -> { folder = parent; showFolder(); });
            scheduled = state.scheduled().orElse(null);
            noteDamaged = state.unreadable() instanceof RestoreException damaged && damaged.reason() == RestoreException.Reason.NOTE_DAMAGED;
            if (scheduled != null || noteDamaged) prepared = null;
            showRestore();
            if (state.unreadable() != null) tell(restoreMessage, restoreStrip, FeedbackStrip.Tone.ERROR, explain(state.unreadable(), true));
        }, true);
    }

    /** The last piece of background work, finished once the screen shows its result. For tests. */
    CompletionStage<Void> lastWork() {
        return lastWork;
    }

    private void chooseFolder() {
        chooser.folder(this, folder, t("settings.dialog.folder")).ifPresent(chosen -> {
            folder = chosen;
            showFolder();
            say(backupMessage, "");
            refresh();
        });
    }

    private void createBackup() {
        if (folder == null) return;
        Path destination = folder;
        work(backupMessage, backupStrip, t("settings.backup.running"), () -> actions.backUp(destination), backup -> {
            tell(backupMessage, backupStrip, FeedbackStrip.Tone.SUCCESS, f("settings.backup.done", backup.file().getFileName()));
            showLast(Optional.of(new BackupService.LastBackup(backup.backupId(), backup.file(), backup.sha256(), backup.createdAt())));
        }, false);
    }

    private void chooseBackup() {
        chooser.backup(this, folder, t("settings.dialog.file"), t("settings.dialog.filter")).ifPresent(chosen ->
                work(restoreMessage, restoreStrip, t("settings.restore.checking"), () -> actions.prepare(chosen), summaryOfIt -> {
                    prepared = summaryOfIt;
                    showRestore();
                }, true));
    }

    private void confirmRestore() {
        if (prepared == null) return;
        RestorePreparation.Summary confirmed = prepared;
        work(restoreMessage, restoreStrip, "", () -> { actions.schedule(confirmed); return confirmed; }, done -> {
            prepared = null;
            scheduled = done;
            showRestore();
        }, true);
    }

    private void cancelPrepared() {
        if (prepared == null) return;
        UUID restoreId = prepared.restoreId();
        work(restoreMessage, restoreStrip, "", () -> { actions.discard(restoreId); return restoreId; }, ignored -> {
            prepared = null;
            showRestore();
            tell(restoreMessage, restoreStrip, FeedbackStrip.Tone.INFO, t("settings.restore.discarded"));
        }, true);
    }

    private void cancelScheduled() {
        work(restoreMessage, restoreStrip, "", actions::cancelScheduled, ignored -> {
            scheduled = null;
            noteDamaged = false;
            showRestore();
            tell(restoreMessage, restoreStrip, FeedbackStrip.Tone.INFO, t("settings.restore.cancelled"));
        }, true);
    }

    /**
     * Runs {@code task} in a background worker, with every action disabled meanwhile, then shows its result
     * or, if it failed, why, in {@code message}.
     */
    private <T> CompletionStage<Void> work(JLabel message, FeedbackStrip strip, String working, Callable<T> task,
                                           Consumer<T> shown, boolean restoring) {
        busy = true;
        refresh();
        if (!working.isEmpty()) tell(message, strip, FeedbackStrip.Tone.INFO, working);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        lastWork = finished;
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override protected void done() {
                busy = false;
                try {
                    T result = get();
                    say(message, "");   // an earlier refusal no longer applies
                    shown.accept(result);
                } catch (ExecutionException failed) {
                    tell(message, strip, FeedbackStrip.Tone.ERROR, explain(failed.getCause() == null ? failed : failed.getCause(), restoring));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    refresh();
                    finished.complete(null);
                }
            }
        }.execute();
        return finished;
    }

    // ----- What the screen shows -----

    private JPanel backupCard() {
        JPanel card = CatalogStyle.surface(new BorderLayout(0, 12));
        card.add(CatalogStyle.label(t("settings.backup.title"), 20, true), BorderLayout.NORTH);
        JLabel folderTitle = CatalogStyle.label(t("settings.backup.folder"), 14, true);
        folderTitle.setLabelFor(chooseFolder);
        JLabel hint = text("settingsBackupHint");
        hint.setForeground(CatalogStyle.MUTED);
        say(hint, t("settings.backup.hint"));
        card.add(stack(lastBackup, folderTitle, folderLabel, hint, buttons(chooseFolder, createBackup), backupStrip), BorderLayout.CENTER);
        return card;
    }

    private JPanel restoreCard() {
        JPanel card = CatalogStyle.surface(new BorderLayout(0, 12));
        card.add(CatalogStyle.label(t("settings.restore.title"), 20, true), BorderLayout.NORTH);
        card.add(stack(summary, warningStrip, buttons(chooseBackup, confirmRestore, cancelPrepared, cancelScheduled), restoreStrip),
                BorderLayout.CENTER);
        return card;
    }

    private void showLast(Optional<BackupService.LastBackup> last) {
        say(lastBackup, last.map(b -> f("settings.backup.last", CatalogStyle.localDateTime(b.createdAt(), locale), age(b.age(clock))))
                .orElse(t("settings.backup.none")));
    }

    private String age(Duration age) {
        long days = Math.max(0, age.toDays());
        return days == 0 ? t("settings.age.today") : days == 1 ? t("settings.age.yesterday") : f("settings.age.days", days);
    }

    private void showFolder() {
        say(folderLabel, folder == null ? t("settings.backup.folder.none") : folder.toString());
        folderLabel.setForeground(folder == null ? CatalogStyle.MUTED : CatalogStyle.INK);
    }

    /** The restore card for the state it is in: nothing, a prepared backup, or a scheduled restore. */
    private void showRestore() {
        if (scheduled != null) {
            say(summary, String.join("\n", lines(scheduled)));
            say(restoreWarning, f("settings.restore.scheduled", CatalogStyle.localDateTime(scheduled.createdAt(), locale)));
        } else if (prepared != null) {
            say(summary, String.join("\n", lines(prepared)));
            say(restoreWarning, (prepared.sameShop() ? "" : t("settings.restore.otherShopWarning") + "\n") + t("settings.restore.warning"));
        } else {
            say(summary, t("settings.restore.intro"));
            say(restoreWarning, "");
        }
        refresh();
    }

    private List<String> lines(RestorePreparation.Summary s) {
        String shop = s.sameShop() ? t("settings.restore.sameShop") : f("settings.restore.otherShop", s.shopId());
        List<String> lines = new java.util.ArrayList<>(List.of(
                f("settings.restore.shop", shop),
                f("settings.restore.date", CatalogStyle.localDateTime(s.createdAt(), locale)),
                f("settings.restore.counts", s.products(), s.sales(), s.stockMovements(), s.photos())));
        if (s.evidence() > 0) lines.add(f("settings.restore.evidence", s.evidence()));
        return lines;
    }

    /** Which actions make sense now; nothing can start while one runs. */
    private void refresh() {
        chooseFolder.setEnabled(!busy);
        createBackup.setEnabled(!busy && folder != null);
        boolean idle = prepared == null && scheduled == null && !noteDamaged;
        chooseBackup.setVisible(idle);
        chooseBackup.setEnabled(!busy && idle);
        confirmRestore.setVisible(prepared != null);
        cancelPrepared.setVisible(prepared != null);
        confirmRestore.setEnabled(!busy);
        cancelPrepared.setEnabled(!busy);
        cancelScheduled.setVisible(scheduled != null || noteDamaged);
        cancelScheduled.setEnabled(!busy);
        revalidate();
        repaint();
    }

    /** Why the work failed, in the shop's words. */
    String explain(Throwable failure, boolean restoring) {
        if (failure instanceof FolderMaintenanceException refused) {
            return switch (refused.reason()) {
                case OTHER_INSTANCE -> t("settings.error.otherInstance");
                case OPERATION_RUNNING -> t("settings.error.operationRunning");
                default -> t("settings.error.busy");
            };
        }
        if (failure instanceof BackupException refused) {
            String files = String.join(", ", refused.details());
            return switch (refused.reason()) {
                case REQUEST_PENDING -> f("settings.error.requestPending", files);
                case EVIDENCE_UNRESOLVED -> f("settings.error.evidenceUnresolved", files);
                case PHOTO_CHANGED -> f("settings.error.photoChanged", files);
                case DESTINATION_REFUSED -> t("settings.error.destination");
                case CHECK_FAILED -> restoring ? f("settings.error.notABackup", refused.getMessage()) : t("settings.error.backupCheck");
            };
        }
        if (failure instanceof RestoreException refused) {
            return switch (refused.reason()) {
                case ALREADY_SCHEDULED -> t("settings.error.alreadyScheduled");
                case UPGRADE_FAILED -> f("settings.error.upgrade", refused.getMessage());
                case STAGED_CHECK_FAILED -> f("settings.error.stagedCheck", refused.getMessage());
                case STAGING_CHANGED -> t("settings.error.stagingChanged");
                case NOTE_DAMAGED -> t("settings.error.noteDamaged");
                case FOLDER_UNSUPPORTED -> t("settings.error.folderUnsupported");
                default -> f("settings.error.other", refused.getMessage());
            };
        }
        return f("settings.error.other", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    // ----- Small helpers -----

    private void tell(JLabel message, FeedbackStrip strip, FeedbackStrip.Tone tone, String text) {
        strip.setTone(tone);
        say(message, text);
    }

    /** Sets wrapped text; blank stays blank, so a feedback strip hides. */
    private static void say(JLabel label, String text) {
        label.setText(text == null || text.isBlank() ? "" : "<html><div style='width:" + TEXT_WIDTH + "pt'>"
                + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</div></html>");
        label.getAccessibleContext().setAccessibleName(text);
    }

    private static JLabel text(String name) {
        JLabel label = CatalogStyle.label("", 14, false);
        label.setName(name);
        return label;
    }

    private static JButton button(String name, String text, boolean primary, Runnable action) {
        JButton button = CatalogStyle.button(text, primary);
        button.setName(name);
        button.getAccessibleContext().setAccessibleName(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * Buttons side by side, 10px apart. A hidden button leaves no gap. FlowLayout also puts its gap before the
     * first button; the border takes it back, so the row lines up with the text above it.
     */
    private static JPanel buttons(JButton... buttons) {
        JPanel row = CatalogStyle.panel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row.setBorder(new EmptyBorder(0, -10, 0, 0));
        for (JButton button : buttons) row.add(button);
        return row;
    }

    /** Rows one under the other, aligned left, 10px apart. */
    private static JPanel stack(JComponent... rows) {
        JPanel stack = CatalogStyle.panel(null);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) stack.add(javax.swing.Box.createVerticalStrut(10));
            stack.add(left(rows[i]));
        }
        return stack;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private String t(String key) {
        return texts.getString(key);
    }

    private String f(String key, Object... arguments) {
        return new MessageFormat(texts.getString(key), locale).format(arguments);
    }

    // ----- The real services and dialogs -----

    /** The shop's own services, made fresh for each piece of work, in the background worker. */
    static final class ServiceActions implements Actions {
        private final LocalAppData location;
        private final AppContext context;
        private final Clock clock;

        ServiceActions(LocalAppData location, AppContext context, Clock clock) {
            this.location = Objects.requireNonNull(location, "location");
            this.context = Objects.requireNonNull(context, "context");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        private BackupService backups() throws IOException {
            EvidenceService evidence = new EvidenceService(location, context.shopId(), context.stock(), context.sales(),
                    new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), clock);
            return new BackupService(location, context.shopId(), context.deviceId(), DataFolderCoordinator.forFolder(location), evidence, clock);
        }

        private RestorePreparation restores() {
            return new RestorePreparation(location, context.shopId(), clock);
        }

        @Override public Optional<BackupService.LastBackup> lastBackup() {
            try {
                return backups().lastSuccess();
            } catch (IOException unreadable) {
                return Optional.empty();
            }
        }

        @Override public Optional<RestorePreparation.Summary> scheduledRestore() throws IOException {
            return restores().scheduled();
        }

        @Override public BackupService.Backup backUp(Path folder) throws Exception {
            return backups().create(folder);
        }

        @Override public RestorePreparation.Summary prepare(Path backup) throws Exception {
            return restores().prepare(backup);
        }

        @Override public void schedule(RestorePreparation.Summary summary) throws Exception {
            restores().schedule(summary);
        }

        @Override public void discard(UUID restoreId) throws Exception {
            restores().discard(restoreId);
        }

        @Override public boolean cancelScheduled() throws Exception {
            return restores().cancelScheduled();
        }
    }

    /** The system's own file dialogs. */
    static final class Dialogs implements Chooser {
        @Override public Optional<Path> folder(Component parent, Path current, String title) {
            JFileChooser dialog = new JFileChooser(current == null ? null : current.toFile());
            dialog.setDialogTitle(title);
            dialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            dialog.setAcceptAllFileFilterUsed(false);
            return dialog.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? Optional.of(dialog.getSelectedFile().toPath()) : Optional.empty();
        }

        @Override public Optional<Path> backup(Component parent, Path folder, String title, String filter) {
            JFileChooser dialog = new JFileChooser(folder == null ? null : folder.toFile());
            dialog.setDialogTitle(title);
            dialog.setFileSelectionMode(JFileChooser.FILES_ONLY);
            dialog.setFileFilter(new FileNameExtensionFilter(filter, "zip"));
            return dialog.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? Optional.of(dialog.getSelectedFile().toPath()) : Optional.empty();
        }
    }
}

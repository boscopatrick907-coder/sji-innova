package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.sale.SaleState;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

/**
 * The saved sales of this shop, shown inside Ventes, and the details of one of them.
 *
 * <p>This page only reads: nothing here can change a sale, the stock or the unfinished sale file. It
 * reads in the background, and only the newest read may update the page. A failed read says so, so it
 * can never be mistaken for a shop with no sales. Opening a receipt swaps the list for
 * {@link SaleDetailsPanel}; the list keeps its filters and selection while it is hidden. All methods
 * must be called on the Swing event thread.
 */
final class SalesHistoryPanel extends JPanel {
    static final int COLUMN_RECEIPT = 0;
    static final int COLUMN_DATE = 1;
    static final int COLUMN_TOTAL = 2;
    static final int COLUMN_STATUS = 3;

    private static final Logger LOG = Logger.getLogger(SalesHistoryPanel.class.getName());
    private static final String LIST_CARD = "list";
    private static final String MESSAGE_CARD = "message";
    private static final String LIST_PAGE = "listPage";
    private static final String DETAILS_PAGE = "detailsPage";

    private final SaleService sales;
    private final UUID shopId;
    private final ResourceBundle messages;
    private final Locale locale;
    private final Locale dateLocale;
    private final NumberFormat money;

    private final HistoryModel model;
    private final JTable table;
    private final JTextField search = named(new JTextField(), "historySearch");
    private final JComboBox<String> status;
    private final JTextField from = named(new JTextField(), "historyFrom");
    private final JTextField to = named(new JTextField(), "historyTo");
    private final JButton refresh;
    private final JButton retry;
    private final JButton clear;
    private final JButton open;
    private final SaleDetailsPanel details;
    private final CardLayout pages = new CardLayout();
    private final JLabel count = named(CatalogStyle.label(" ", 12, false), "historyCount");
    private final JTextArea message = named(new JTextArea(1, 30), "historyMessage");
    private final FeedbackStrip messageStrip = CatalogStyle.feedback(message, FeedbackStrip.Tone.INFO);
    private final JTextArea pending = named(new JTextArea(1, 30), "historyPending");
    private final CardLayout listCards = new CardLayout();
    private final JPanel listArea = CatalogStyle.panel(listCards);

    private List<SaleState> saved = List.of();      // everything read, newest first
    private List<SaleState> visible = List.of();    // what passes the filters
    private LocalDate fromDay;
    private LocalDate toDay;
    private String dateProblem;
    private UUID selectedSale;
    private boolean loading;
    private boolean failed;
    private boolean fillingTable;
    private boolean showingDetails;
    private long generation;                        // only the newest read may update the page
    private int activeWorkers;

    SalesHistoryPanel(SaleService sales, UUID shopId, ResourceBundle messages, Runnable onBack) {
        this.sales = Objects.requireNonNull(sales, "sales");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.messages = Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(onBack, "onBack");
        boolean french = "fr".equals(messages.getLocale().getLanguage());
        this.locale = french ? Locale.FRANCE : Locale.ENGLISH;
        this.dateLocale = french ? Locale.FRANCE : Locale.UK;   // day first in both languages, like the filters
        this.money = NumberFormat.getIntegerInstance(locale);

        model = new HistoryModel();
        table = named(new JTable(model), "historyTable");
        status = named(new JComboBox<>(new String[] {t("sales.history.status.all"),
            t("sales.history.status.paid"), t("sales.history.status.voided")}), "historyStatus");
        refresh = named(CatalogStyle.button(t("catalog.refresh"), false), "historyRefresh");
        retry = named(CatalogStyle.button(t("sales.history.retry"), true), "historyRetry");
        clear = named(CatalogStyle.button(t("sales.history.clear"), false), "historyClear");
        open = named(CatalogStyle.arrowButton(t("sales.history.openReceipt"), false, false), "historyOpen");
        JButton back = named(CatalogStyle.arrowButton(t("sales.history.back"), false, true), "historyBack");
        back.addActionListener(event -> onBack.run());
        details = new SaleDetailsPanel(messages, this::closeDetails);

        setOpaque(false);
        setLayout(pages);
        JPanel listPage = CatalogStyle.panel(new BorderLayout(0, 10));
        listPage.add(buildHeader(back), BorderLayout.NORTH);
        listPage.add(buildBody(), BorderLayout.CENTER);
        add(listPage, LIST_PAGE);
        add(details, DETAILS_PAGE);
        connectControls();
        updateView();
    }

    /** Reads the saved sales again, keeping the filters and the selected sale. */
    void reload() {
        long request = ++generation;
        loading = true;
        failed = false;
        refill();
        activeWorkers++;
        new SwingWorker<List<SaleState>, Void>() {
            @Override protected List<SaleState> doInBackground() throws Exception {
                return SaleHistoryFilter.newestFirst(sales.listSaleStates(shopId));
            }

            @Override protected void done() {
                activeWorkers--;
                if (request == generation) applyRead(this);
            }
        }.execute();
    }

    /** For tests: true while a read is still running. */
    boolean isBusy() { return activeWorkers > 0; }

    /** Reminds the cashier that browsing here does not settle a sale left unfinished at the till. */
    void setPendingNotice(boolean unfinishedSaleWaiting) {
        pending.setText(unfinishedSaleWaiting ? t("sales.history.pending") : "");
    }

    void focusSearch() { search.requestFocusInWindow(); }

    /** For tests: true while one sale's details replace the list. */
    boolean showingDetails() { return showingDetails; }

    /** Opens the selected receipt. Nothing happens when no sale is selected. */
    private void openSelected() {
        int row = table.getSelectedRow();
        if (showingDetails || row < 0 || row >= visible.size()) return;
        SaleState state = visible.get(row);
        selectedSale = state.sale().id();
        details.show(state);
        showingDetails = true;
        pages.show(this, DETAILS_PAGE);
        details.focusBack();
    }

    /** Back to the list exactly as it was left: same filters, same selected sale. */
    private void closeDetails() {
        showingDetails = false;
        pages.show(this, LIST_PAGE);
        (visible.isEmpty() ? search : table).requestFocusInWindow();
    }

    private void applyRead(SwingWorker<List<SaleState>, Void> worker) {
        loading = false;
        try {
            saved = worker.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            readFailed(interrupted);
        } catch (ExecutionException failure) {
            readFailed(failure);
        }
        refill();
        if (showingDetails && !failed) {
            // A newer read of the receipt on screen, for example one cancelled since it was opened.
            UUID onScreen = details.shown().sale().id();
            saved.stream().filter(state -> state.sale().id().equals(onScreen)).findFirst().ifPresent(details::show);
        }
    }

    private void readFailed(Exception failure) {
        LOG.log(Level.WARNING, "Could not read the sales history", failure);
        failed = true;
        saved = List.of();
    }

    /** Applies the filters, keeps the selected sale if it is still listed, and updates the page. */
    private void refill() {
        visible = dateProblem != null ? List.of()
                : new SaleHistoryFilter(search.getText(), selectedStatus(), fromDay, toDay).apply(saved);
        fillingTable = true;
        model.fireTableDataChanged();
        if (!visible.isEmpty()) {
            int row = Math.max(rowOf(selectedSale), 0);
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
            selectedSale = visible.get(row).sale().id();
        }
        fillingTable = false;
        updateView();
    }

    private int rowOf(UUID saleId) {
        for (int row = 0; row < visible.size(); row++) {
            if (visible.get(row).sale().id().equals(saleId)) return row;
        }
        return -1;
    }

    private SaleHistoryFilter.Status selectedStatus() {
        return SaleHistoryFilter.Status.values()[status.getSelectedIndex()];
    }

    /** The one place that decides what the list area shows. */
    private void updateView() {
        refresh.setEnabled(!loading);
        open.setEnabled(!visible.isEmpty());
        retry.setVisible(false);
        clear.setVisible(false);
        if (failed) {
            retry.setVisible(true);
            showMessage(t("sales.history.loadError"), FeedbackStrip.Tone.ERROR);
        } else if (loading && saved.isEmpty()) {
            showMessage(t("sales.history.loading"), FeedbackStrip.Tone.INFO);
        } else if (dateProblem != null) {
            clear.setVisible(true);
            showMessage(dateProblem, FeedbackStrip.Tone.ERROR);
        } else if (saved.isEmpty()) {
            showMessage(t("sales.history.empty"), FeedbackStrip.Tone.INFO);
        } else if (visible.isEmpty()) {
            clear.setVisible(true);
            showMessage(t("sales.history.noResults"), FeedbackStrip.Tone.INFO);
        } else {
            listCards.show(listArea, LIST_CARD);
            count.setText(loading ? t("sales.history.loading") : t("sales.history.count", visible.size(), saved.size()));
        }
        revalidate();
        repaint();
    }

    private void showMessage(String text, FeedbackStrip.Tone tone) {
        message.setText(text);
        messageStrip.setTone(tone);
        count.setText(" ");
        listCards.show(listArea, MESSAGE_CARD);
        // Wrapped text only knows its height once it has a width, so lay it out a second time.
        SwingUtilities.invokeLater(message::revalidate);
    }

    /**
     * Reads both dates. While a date is still being typed nothing changes; it is judged only when the
     * cashier finishes it, by pressing Enter or leaving the box.
     */
    private void readDates(boolean finished) {
        try {
            LocalDate first = SaleHistoryFilter.parseDay(from.getText());
            LocalDate last = SaleHistoryFilter.parseDay(to.getText());
            fromDay = first;
            toDay = last;
            boolean backwards = first != null && last != null && first.isAfter(last);
            dateProblem = backwards ? t("sales.history.date.backwards") : null;
        } catch (DateTimeParseException notADay) {
            if (!finished) return;
            dateProblem = t("sales.history.date.invalid");
        }
        refill();
    }

    private void clearFilters() {
        search.setText("");
        status.setSelectedIndex(0);
        from.setText("");
        to.setText("");
        readDates(true);
        search.requestFocusInWindow();
    }

    private JComponent buildHeader(JButton back) {
        JPanel backRow = CatalogStyle.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        backRow.add(back);
        JLabel subtitle = CatalogStyle.label(t("sales.history.subtitle"), 14, false);
        subtitle.setForeground(CatalogStyle.MUTED);
        JPanel titles = CatalogStyle.panel(new BorderLayout(0, 6));
        titles.add(CatalogStyle.label(t("sales.history.title"), 28, true), BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.CENTER);
        JPanel actions = CatalogStyle.panel(new GridBagLayout());
        actions.add(refresh);
        JPanel header = CatalogStyle.panel(new BorderLayout(12, 8));
        header.add(backRow, BorderLayout.NORTH);
        header.add(titles, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JComponent buildBody() {
        pending.setEditable(false);
        pending.setFocusable(false);
        pending.setOpaque(false);
        pending.setLineWrap(true);
        pending.setWrapStyleWord(true);
        count.setForeground(CatalogStyle.MUTED);

        listArea.add(buildList(), LIST_CARD);
        listArea.add(buildMessageBox(), MESSAGE_CARD);
        JPanel bottom = CatalogStyle.panel(new BorderLayout(12, 0));
        bottom.add(count, BorderLayout.CENTER);
        bottom.add(open, BorderLayout.EAST);
        JPanel card = CatalogStyle.surface(new BorderLayout(0, 12));
        card.add(buildFilters(), BorderLayout.NORTH);
        card.add(listArea, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);

        JPanel body = CatalogStyle.panel(new BorderLayout(0, 10));
        body.add(CatalogStyle.feedback(pending, FeedbackStrip.Tone.WARNING), BorderLayout.NORTH);
        body.add(card, BorderLayout.CENTER);
        return body;
    }

    private JComponent buildFilters() {
        CatalogStyle.searchField(search, t("sales.history.search"));
        search.getAccessibleContext().setAccessibleName(t("sales.history.search"));
        search.setPreferredSize(new Dimension(200, 40));
        status.setPreferredSize(new Dimension(120, 40));
        dateBox(from, "sales.history.from");
        dateBox(to, "sales.history.to");

        JPanel row = CatalogStyle.panel(new GridBagLayout());
        GridBagConstraints place = new GridBagConstraints();
        place.gridy = 0;
        place.fill = GridBagConstraints.HORIZONTAL;
        place.weightx = 1;
        place.gridwidth = 6;
        place.insets = new Insets(0, 0, 10, 0);
        row.add(search, place);
        place.gridy = 1;
        place.gridwidth = 1;
        place.insets = new Insets(0, 0, 0, 0);
        place.weightx = 0;
        addLabelled(row, place, "sales.history.status", status);
        addLabelled(row, place, "sales.history.from", from);
        addLabelled(row, place, "sales.history.to", to);
        return row;
    }

    private void dateBox(JTextField box, String nameKey) {
        box.setPreferredSize(new Dimension(116, 40));
        box.setToolTipText(t("sales.history.datePattern"));
        box.getAccessibleContext().setAccessibleName(t(nameKey) + " (" + t("sales.history.datePattern") + ")");
        box.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, t("sales.history.datePattern"));
        box.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    }

    private void addLabelled(JPanel row, GridBagConstraints place, String labelKey, JComponent control) {
        JLabel label = CatalogStyle.label(t(labelKey), 14, false);
        label.setLabelFor(control);
        place.insets = new Insets(0, 14, 0, 6);
        row.add(label, place);
        place.insets = new Insets(0, 0, 0, 0);
        row.add(control, place);
    }

    private JComponent buildList() {
        table.setRowHeight(40);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getAccessibleContext().setAccessibleName(t("sales.history.title"));
        // Tab leaves the table instead of walking through its cells.
        table.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        table.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        JComponent list = CatalogStyle.tableScroll(table);
        CatalogStyle.alignColumns(table, COLUMN_TOTAL);
        table.getColumnModel().getColumn(COLUMN_RECEIPT).setPreferredWidth(240);
        table.getColumnModel().getColumn(COLUMN_DATE).setPreferredWidth(160);
        table.getColumnModel().getColumn(COLUMN_TOTAL).setPreferredWidth(120);
        table.getColumnModel().getColumn(COLUMN_STATUS).setPreferredWidth(110);
        // The word comes from the table; the colour only supports it.
        table.getColumnModel().getColumn(COLUMN_STATUS).setCellRenderer((owner, value, selected, focus, row, column) ->
                CatalogStyle.badgeCell(owner, String.valueOf(value),
                        visible.get(owner.convertRowIndexToModel(row)).voided()
                                ? CatalogStyle.BadgeTone.EMPTY : CatalogStyle.BadgeTone.NORMAL,
                        selected, row, column, false));
        return list;
    }

    /** Stands in for the table whenever there is no row to show, and says why. */
    private JComponent buildMessageBox() {
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        JPanel buttons = CatalogStyle.panel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(retry);
        buttons.add(clear);
        JPanel box = CatalogStyle.panel(new BorderLayout(0, 12));
        box.add(messageStrip, BorderLayout.NORTH);
        box.add(buttons, BorderLayout.CENTER);
        JPanel centred = CatalogStyle.panel(new GridBagLayout());
        centred.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 24));
        GridBagConstraints place = new GridBagConstraints();
        place.fill = GridBagConstraints.HORIZONTAL;
        place.weightx = 1;
        centred.add(box, place);
        return centred;
    }

    private void connectControls() {
        refresh.addActionListener(event -> reload());
        retry.addActionListener(event -> reload());
        clear.addActionListener(event -> clearFilters());
        status.addActionListener(event -> refill());
        search.getDocument().addDocumentListener(whenChanged(this::refill));
        for (JTextField box : List.of(from, to)) {
            box.getDocument().addDocumentListener(whenChanged(() -> readDates(false)));
            box.addActionListener(event -> readDates(true));
            box.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent event) { readDates(true); }
            });
        }
        table.getSelectionModel().addListSelectionListener(event -> {
            int row = table.getSelectedRow();
            if (!fillingTable && !event.getValueIsAdjusting() && row >= 0) selectedSale = visible.get(row).sale().id();
        });

        // Four ways to open a receipt: the button, Enter or a double-click in the list, and Enter
        // in a search that is not empty, so typing part of a receipt then Enter opens it.
        open.addActionListener(event -> openSelected());
        table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openReceipt");
        table.getActionMap().put("openReceipt", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { openSelected(); }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event) || row < 0) return;
                table.setRowSelectionInterval(row, row);    // open the row that was clicked, whatever was selected
                openSelected();
            }
        });
        search.addActionListener(event -> {
            if (!search.getText().isBlank()) openSelected();
        });
    }

    private static DocumentListener whenChanged(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        };
    }

    private static <T extends JComponent> T named(T component, String name) {
        component.setName(name);
        return component;
    }

    private String t(String key, Object... arguments) {
        String pattern = messages.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    /** Read-only: saved values as they were recorded, never recalculated. */
    private final class HistoryModel extends AbstractTableModel {
        private final String[] columns = {t("sales.history.column.receipt"), t("sales.history.column.date"),
            t("sales.history.column.total"), t("sales.history.column.status")};

        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Object getValueAt(int row, int column) {
            SaleState state = visible.get(row);
            Sale sale = state.sale();
            return switch (column) {
                case COLUMN_RECEIPT -> sale.receiptNumber();
                case COLUMN_DATE -> CatalogStyle.localDateTime(sale.createdAt(), dateLocale);
                case COLUMN_TOTAL -> money.format(sale.totalAmount());
                default -> t(state.voided() ? "sales.history.voided" : "sales.history.paid");
            };
        }
    }
}

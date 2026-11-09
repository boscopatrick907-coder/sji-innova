package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.PaymentMethod;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleItem;
import cm.sji.innova.core.sale.SaleState;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/**
 * One saved sale, exactly as it was recorded, shown inside the sales history.
 *
 * <p>Read-only. Every value comes from the saved sale and its events: names, quantities, prices and
 * totals are never looked up in today's catalogue or calculated again, and a saved sale holds no
 * buying price. All methods must be called on the Swing event thread.
 */
final class SaleDetailsPanel extends JPanel {
    static final int COLUMN_PRODUCT = 0;
    static final int COLUMN_QUANTITY = 1;
    static final int COLUMN_UNIT_PRICE = 2;
    static final int COLUMN_LINE_TOTAL = 3;

    private final ResourceBundle messages;
    private final Locale locale;
    private final Locale dateLocale;
    private final NumberFormat money;

    private final LinesModel model;
    private final JTable lines;
    private final JButton back;
    private final JLabel title = named(CatalogStyle.label(" ", 28, true), "receiptTitle");
    private final JLabel date = named(CatalogStyle.label(" ", 16, true), "receiptDate");
    private final JLabel payment = named(CatalogStyle.label(" ", 16, true), "receiptPayment");
    private final JLabel status = named(CatalogStyle.label(" ", 16, true), "receiptStatus");
    private final JLabel linesHeading = named(CatalogStyle.label(" ", 18, true), "receiptLinesHeading");
    private final JLabel total = named(CatalogStyle.amountLabel(), "receiptTotal");
    private final JTextArea cancelled = named(new JTextArea(1, 30), "receiptCancelled");

    private List<SaleItem> items = List.of();
    private SaleState shown;

    SaleDetailsPanel(ResourceBundle messages, Runnable onBack) {
        super(new BorderLayout(0, 10));
        this.messages = Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(onBack, "onBack");
        boolean french = "fr".equals(messages.getLocale().getLanguage());
        this.locale = french ? Locale.FRANCE : Locale.ENGLISH;
        this.dateLocale = french ? Locale.FRANCE : Locale.UK;   // day first, like the history list
        this.money = NumberFormat.getIntegerInstance(locale);

        model = new LinesModel();
        lines = named(new JTable(model), "receiptLines");
        back = named(CatalogStyle.arrowButton(t("sales.receipt.back"), false, true), "receiptBack");
        back.addActionListener(event -> onBack.run());
        // Escape also goes back, from anywhere on this page.
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "back");
        getActionMap().put("back", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { onBack.run(); }
        });

        setOpaque(false);
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
    }

    /** Shows one saved sale. Calling it again with a newer read of the same sale updates the page. */
    void show(SaleState state) {
        shown = Objects.requireNonNull(state, "state");
        Sale sale = state.sale();
        items = sale.items();
        title.setText(t("sales.receipt.title", sale.receiptNumber()));
        date.setText(CatalogStyle.localDateTime(sale.createdAt(), dateLocale));
        payment.setText(paymentText(sale.paymentMethod()));
        status.setText(t(state.voided() ? "sales.history.voided" : "sales.history.paid"));
        linesHeading.setText(t("sales.receipt.lines", items.size()));
        total.setText(money.format(sale.totalAmount()) + " FCFA");
        cancelled.setText(state.voidEvent()
                .map(event -> t("sales.receipt.voided",
                        CatalogStyle.localDateTime(event.createdAt(), dateLocale), event.reason()))
                .orElse(""));
        model.fireTableDataChanged();
        lines.clearSelection();     // nothing to choose on a receipt; arrows still move through the lines
        // Wrapped text only knows its height once it has a width, so lay it out a second time.
        SwingUtilities.invokeLater(cancelled::revalidate);
        revalidate();
        repaint();
    }

    /** The sale on the page, or null before the first one is opened. */
    SaleState shown() { return shown; }

    void focusBack() { back.requestFocusInWindow(); }

    private JComponent buildHeader() {
        JPanel backRow = CatalogStyle.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        backRow.add(back);
        JLabel subtitle = CatalogStyle.label(t("sales.receipt.subtitle"), 14, false);
        subtitle.setForeground(CatalogStyle.MUTED);
        JPanel titles = CatalogStyle.panel(new BorderLayout(0, 6));
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.CENTER);
        JPanel header = CatalogStyle.panel(new BorderLayout(12, 8));
        header.add(backRow, BorderLayout.NORTH);
        header.add(titles, BorderLayout.CENTER);
        return header;
    }

    private JComponent buildBody() {
        cancelled.setEditable(false);
        cancelled.setFocusable(false);
        cancelled.setOpaque(false);
        cancelled.setLineWrap(true);
        cancelled.setWrapStyleWord(true);

        JPanel facts = CatalogStyle.panel(new GridLayout(1, 3, 16, 0));
        facts.add(fact("sales.receipt.date", date));
        facts.add(fact("sales.receipt.payment", payment));
        facts.add(fact("sales.receipt.status", status));

        JPanel itemsArea = CatalogStyle.panel(new BorderLayout(0, 8));
        itemsArea.add(linesHeading, BorderLayout.NORTH);
        itemsArea.add(buildLines(), BorderLayout.CENTER);

        JLabel totalCaption = CatalogStyle.label(t("sales.receipt.total"), 16, false);
        totalCaption.setForeground(CatalogStyle.MUTED);
        totalCaption.setLabelFor(total);
        total.getAccessibleContext().setAccessibleName(t("sales.receipt.total"));
        JPanel totalRow = CatalogStyle.panel(new BorderLayout(12, 0));
        totalRow.add(totalCaption, BorderLayout.WEST);
        totalRow.add(total, BorderLayout.CENTER);

        JPanel card = CatalogStyle.surface(new BorderLayout(0, 14));
        card.add(facts, BorderLayout.NORTH);
        card.add(itemsArea, BorderLayout.CENTER);
        card.add(totalRow, BorderLayout.SOUTH);

        JPanel body = CatalogStyle.panel(new BorderLayout(0, 10));
        body.add(CatalogStyle.feedback(cancelled, FeedbackStrip.Tone.WARNING), BorderLayout.NORTH);
        body.add(card, BorderLayout.CENTER);
        return body;
    }

    /** A small caption above its value, such as "Paiement" above "Espèces". */
    private JComponent fact(String captionKey, JLabel value) {
        JLabel caption = CatalogStyle.label(t(captionKey), 12, false);
        caption.setForeground(CatalogStyle.MUTED);
        caption.setLabelFor(value);
        value.getAccessibleContext().setAccessibleName(t(captionKey));
        JPanel fact = CatalogStyle.panel(new BorderLayout(0, 4));
        fact.add(caption, BorderLayout.NORTH);
        fact.add(value, BorderLayout.CENTER);
        return fact;
    }

    private JComponent buildLines() {
        lines.setRowHeight(40);
        lines.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lines.setFillsViewportHeight(true);
        lines.getAccessibleContext().setAccessibleName(t("sales.receipt.linesName"));
        // Tab leaves the table instead of walking through its cells.
        lines.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        lines.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        JComponent list = CatalogStyle.tableScroll(lines);
        CatalogStyle.alignColumns(lines, COLUMN_QUANTITY, COLUMN_UNIT_PRICE, COLUMN_LINE_TOTAL);
        lines.getColumnModel().getColumn(COLUMN_PRODUCT).setPreferredWidth(280);
        lines.getColumnModel().getColumn(COLUMN_QUANTITY).setPreferredWidth(120);
        lines.getColumnModel().getColumn(COLUMN_UNIT_PRICE).setPreferredWidth(140);
        lines.getColumnModel().getColumn(COLUMN_LINE_TOTAL).setPreferredWidth(120);
        return list;
    }

    private String paymentText(PaymentMethod method) {
        String key = "sales.receipt.payment." + method.code();
        return messages.containsKey(key) ? messages.getString(key) : method.code();
    }

    /** "0,5 kg", "2 sacs", "1 pièce": the saved quantity in the saved unit, never rounded. */
    private String quantityText(SaleItem item) {
        String unit = unitName(item.unit());
        boolean plural = !"kg".equals(item.unit()) && item.quantityMilli() != Quantities.ONE;
        return Quantities.format(item.quantityMilli(), locale) + " " + unit + (plural ? "s" : "");
    }

    /** Same unit words as the basket. An old sale with an unknown unit shows the saved word as it is. */
    private String unitName(String unit) {
        if ("kg".equals(unit)) return "kg";
        String key = "unit." + unit;
        return messages.containsKey(key) ? messages.getString(key).toLowerCase(locale) : unit;
    }

    private static <T extends JComponent> T named(T component, String name) {
        component.setName(name);
        return component;
    }

    private String t(String key, Object... arguments) {
        String pattern = messages.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    /** The saved lines, in the order they were sold. */
    private final class LinesModel extends AbstractTableModel {
        private final String[] columns = {t("sales.column.product"), t("sales.column.quantity"),
            t("sales.column.unitPrice"), t("sales.column.lineTotal")};

        @Override public int getRowCount() { return items.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Object getValueAt(int row, int column) {
            SaleItem item = items.get(row);
            return switch (column) {
                case COLUMN_PRODUCT -> item.productName();
                case COLUMN_QUANTITY -> quantityText(item);
                case COLUMN_UNIT_PRICE -> money.format(item.unitPrice()) + " / " + unitName(item.unit());
                default -> money.format(item.subtotal());
            };
        }
    }
}

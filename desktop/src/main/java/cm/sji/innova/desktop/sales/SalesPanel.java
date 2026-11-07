package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductFamily;
import cm.sji.innova.core.product.ProductFamilyService;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleService;
import cm.sji.innova.core.stock.StockLevel;
import cm.sji.innova.core.stock.StockService;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.sales.Checkout.Outcome;
import cm.sji.innova.desktop.sales.Checkout.Recovery;
import cm.sji.innova.desktop.sales.Quantities.InvalidQuantityException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.text.JTextComponent;

/**
 * The Ventes page: search for a variant, build a basket, confirm cash, save the sale.
 *
 * <p>The constructor touches no database. The page loads when it is opened, in a background thread,
 * so stock is fresh each time. All methods must be called on the Swing event thread. The safety rules
 * live in {@link Checkout}; this class only decides what the cashier can click at each moment:
 * <ul>
 *   <li>CHECKING: looking for a sale left unfinished by an earlier run; everything is locked,</li>
 *   <li>EDITING: the basket can be changed,</li>
 *   <li>CONFIRMING: waiting for the cashier to confirm the cash,</li>
 *   <li>SENDING: a background step is running; everything is locked, so a double-click does nothing,</li>
 *   <li>UNCERTAIN: no certain answer; the basket is locked until the sale is retried or cancelled,</li>
 *   <li>DAMAGED: the saved file cannot be read; no new sale until it is set aside.</li>
 * </ul>
 */
public final class SalesPanel extends JPanel {
    private enum PageState { CHECKING, EDITING, CONFIRMING, SENDING, UNCERTAIN, DAMAGED }

    private static final Logger LOG = Logger.getLogger(SalesPanel.class.getName());
    private static final Color PROBLEM = new Color(0xA43B2D);
    static final int COLUMN_QUANTITY = 3;

    /** One consistent picture of the shop, read in the background. */
    private record Snapshot(List<Product> products, Map<UUID, String> familyNames,
                            Map<UUID, StockLevel> levels, boolean stockFailed) { }

    private record Loaded(Recovery recovery, Snapshot snapshot, Exception failure) { }

    private final ProductService products;
    private final ProductFamilyService families;
    private final StockService stock;
    private final UUID shopId;
    private final UUID deviceId;
    private final ResourceBundle messages;
    private final Checkout checkout;
    private JPanel noticeStrip;
    private JPanel resultsStrip;
    private JPanel cartHost;
    private JComponent cartCard;
    private boolean compactCart=true;
    private final Locale locale;
    private final NumberFormat money;

    private final Basket basket = new Basket();
    private final ResultsModel resultsModel;
    private final BasketModel basketModel;
    private final JTextField search = new JTextField();
    private final JButton refresh;
    private final JTable results;
    private final JTextArea resultsHint = new JTextArea(1,16);
    private final JButton addButton;
    private final JTable basketTable;
    private final JButton removeButton;
    private final JLabel basketHint = new JLabel(" ");
    private final JLabel totalLabel = CatalogStyle.label(" ", 20, true);
    private final JTextArea notice = new JTextArea(2, 20);
    private final JButton checkoutButton;
    private final JButton confirmButton;
    private final JButton backButton;
    private final JButton retryButton;
    private final JButton discardButton;
    private final JButton setAsideButton;

    private PageState state = PageState.CHECKING;
    private PendingCheckout draft;              // the sale being sent; kept until the answer is certain
    private List<Product> catalog = List.of();
    private List<Product> visible = List.of();
    private Map<UUID, String> familyNames = new HashMap<>();
    private Map<UUID, StockLevel> levels = new HashMap<>();
    private boolean stockFailed;
    private boolean loadFailed;
    private boolean loading;
    private boolean needsRecovery = true;
    private boolean quantityRefused;
    private boolean resultChosenWithArrows;
    private long generation;                    // only the newest load may update the page
    private int activeWorkers;

    public SalesPanel(SaleService sales, ProductService products, ProductFamilyService families,
                      StockService stock, UUID shopId, UUID deviceId,
                      PendingCheckoutStore pendingStore, ResourceBundle messages) {
        super(new BorderLayout(0, 10));
        this.products = Objects.requireNonNull(products, "products");
        this.families = Objects.requireNonNull(families, "families");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.checkout = new Checkout(sales, pendingStore, shopId);
        boolean french = "fr".equals(messages.getLocale().getLanguage());
        this.locale = french ? Locale.FRANCE : Locale.ENGLISH;
        this.money = NumberFormat.getIntegerInstance(locale);

        resultsModel = new ResultsModel();
        basketModel = new BasketModel();
        results = styledTable(new JTable(resultsModel), "salesResults");
        basketTable = styledTable(new QuantityTable(basketModel), "salesBasket");
        refresh = named(CatalogStyle.button(t("catalog.refresh"), false), "salesRefresh");
        addButton = named(CatalogStyle.button(t("sales.add"), false), "salesAdd");
        removeButton = named(CatalogStyle.button(t("sales.removeLine"), false), "salesRemove");
        checkoutButton = named(CatalogStyle.button(t("sales.checkout"), true), "salesCheckout");
        confirmButton = named(CatalogStyle.button(t("sales.confirm.yes"), true), "salesConfirm");
        backButton = named(CatalogStyle.arrowButton(t("sales.confirm.back"), false, true), "salesBack");
        retryButton = named(CatalogStyle.button(t("sales.retry"), true), "salesRetry");
        discardButton = named(CatalogStyle.button(t("sales.discard"), false), "salesDiscard");
        setAsideButton = named(CatalogStyle.button(t("sales.setAside"), false), "salesSetAside");
        search.setName("salesSearch");
        totalLabel.setName("salesTotal");
        notice.setName("salesNotice");
        resultsHint.setName("salesResultsHint");resultsHint.setEditable(false);resultsHint.setFocusable(false);resultsHint.setOpaque(false);resultsHint.setLineWrap(true);resultsHint.setWrapStyleWord(true);

        setBackground(CatalogStyle.PAPER);
        setBorder(BorderFactory.createEmptyBorder(14, 30, 14, 30));
        JPanel header=CatalogStyle.panel(new BorderLayout(12,0));
        JPanel titles=CatalogStyle.panel(new BorderLayout(0,6));
        titles.add(CatalogStyle.label(t("sales.pageTitle"),28,true),BorderLayout.NORTH);
        JLabel subtitle=CatalogStyle.label(t("sales.pageSubtitle"),14,false);subtitle.setForeground(CatalogStyle.MUTED);
        titles.add(subtitle,BorderLayout.CENTER);header.add(titles,BorderLayout.CENTER);JPanel headerActions=CatalogStyle.panel(new java.awt.GridBagLayout());headerActions.add(refresh);header.add(headerActions,BorderLayout.EAST);
        add(header,BorderLayout.NORTH);
        JPanel areas=CatalogStyle.panel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c=new java.awt.GridBagConstraints();c.gridy=0;c.fill=java.awt.GridBagConstraints.BOTH;c.weighty=1;
        JComponent picker=buildResultsArea(),cart=buildBasketArea();picker.setMinimumSize(new Dimension(0,0));cart.setMinimumSize(new Dimension(0,0));
        c.gridx=0;c.weightx=.6;c.insets=new java.awt.Insets(0,0,0,14);areas.add(picker,c);
        cartCard=cart;cartHost=CatalogStyle.panel(new BorderLayout());cartHost.add(cart,BorderLayout.NORTH);
        c.gridx=1;c.weightx=.4;c.insets=new java.awt.Insets(0,0,0,0);areas.add(cartHost,c);
        add(areas,BorderLayout.CENTER);
        connectControls();
        refreshBasketView();
        applyState();
        resultsHint.setText(t("catalog.loading"));
    }

    /** Reads products and stock again, and on first opening looks for an unfinished sale. */
    public void reload() {
        long request = ++generation;
        // A damaged file is looked at again each time, in case someone has fixed or moved it.
        boolean recover = needsRecovery || state == PageState.DAMAGED;
        needsRecovery = false;
        loading = true;
        updateResultsHint();
        applyState();
        activeWorkers++;
        new SwingWorker<Loaded, Void>() {
            @Override protected Loaded doInBackground() {
                Recovery recovery = recover ? checkout.recover() : null;
                try {
                    return new Loaded(recovery, loadSnapshot(), null);
                } catch (SQLException | RuntimeException failure) {
                    return new Loaded(recovery, null, failure);
                }
            }

            @Override protected void done() {
                activeWorkers--;
                Loaded loaded;
                try {
                    loaded = get();
                } catch (InterruptedException | ExecutionException failure) {
                    needsRecovery = needsRecovery || recover;       // Refresh will look again
                    loaded = new Loaded(null, null, failure);
                }
                if (loaded.recovery() != null) applyRecovery(loaded.recovery());
                if (request == generation) applyLoaded(loaded);
                applyState();
            }
        }.execute();
    }

    /** For tests: true while a background step is still running. */
    boolean isBusy() { return activeWorkers > 0; }

    private Snapshot loadSnapshot() throws SQLException {
        List<Product> allProducts = products.list(shopId);
        Map<UUID, String> names = new HashMap<>();
        for (ProductFamily family : families.list(shopId)) names.put(family.id(), family.name());
        Map<UUID, StockLevel> stockLevels = new HashMap<>();
        boolean failed = false;
        try {
            for (StockLevel level : stock.list(shopId)) stockLevels.put(level.productId(), level);
        } catch (SQLException | RuntimeException failure) {
            // Unknown stock must not look like zero, and the database still checks every sale.
            LOG.log(Level.WARNING, "Could not read stock levels", failure);
            failed = true;
        }
        return new Snapshot(allProducts, names, stockLevels, failed);
    }

    private void applyLoaded(Loaded loaded) {
        loading = false;
        loadFailed = loaded.snapshot() == null;
        if (loadFailed) {
            LOG.log(Level.WARNING, "Could not load the Sales page", loaded.failure());
        } else {
            catalog = loaded.snapshot().products();
            familyNames = loaded.snapshot().familyNames();
            levels = loaded.snapshot().levels();
            stockFailed = loaded.snapshot().stockFailed();
            applyFilter();
            refreshBasketView();
        }
        updateResultsHint();
    }

    private void applyRecovery(Recovery recovery) {
        switch (recovery) {
            case Recovery.Nothing nothing -> state = PageState.EDITING;
            case Recovery.Busy busy -> {
                state = PageState.CHECKING;
                needsRecovery = true;
                showNotice(t("sales.busy"), true);
            }
            case Recovery.AlreadySaved saved -> {
                state = PageState.EDITING;
                showSale("sales.recovered.saved", saved.sale());
            }
            case Recovery.Unfinished unfinished -> {
                draft = unfinished.pending();
                basket.replaceWith(draft.lines());
                state = PageState.UNCERTAIN;
                showNotice(t("sales.recovered.unfinished", draft.receiptNumber(), money.format(draft.total())), true);
            }
            case Recovery.Damaged damaged -> {
                LOG.log(Level.WARNING, damaged.cause(), () -> "Unfinished sale file unusable: " + damaged.file());
                state = PageState.DAMAGED;
                showNotice(t("sales.damaged", damaged.file()), true);
            }
        }
        refreshBasketView();
    }

    private void addSelectedProduct() {
        if (state != PageState.EDITING) return;
        int row = results.getSelectedRow();
        if (row < 0) {
            showNotice(t("sales.pickProduct"), true);
            return;
        }
        Product product = visible.get(row);
        if (!selectedHasStock()) { showNotice(t("sales.outOfStockHint"), true); return; }
        try {
            basket.add(BasketLine.oneOf(product, familyNames.get(product.familyId())));
            showNotice(" ", false);
        } catch (ArithmeticException tooLarge) {
            showNotice(t("sales.error.tooLarge"), true);
            return;
        }
        resultChosenWithArrows = false;
        refreshBasketView();
        int addedRow = basket.lines().stream().map(BasketLine::productId).toList().indexOf(product.id());
        basketTable.changeSelection(addedRow, COLUMN_QUANTITY, false, false);
        search.setText("");
        search.requestFocusInWindow();
        applyState();
    }

    private void removeSelectedLine() {
        if (state != PageState.EDITING) return;
        int row = basketTable.getSelectedRow();
        if (row < 0) {
            showNotice(t("sales.pickLine"), true);
            return;
        }
        stopQuantityEdit();
        basket.remove(basket.lines().get(row).productId());
        refreshBasketView();
        applyState();
    }

    private void beginCheckout() {
        if (state != PageState.EDITING || !stopQuantityEdit() || basket.isEmpty()) return;
        state = PageState.CONFIRMING;
        showNotice(t("sales.confirm.question", money.format(basket.total())), false);
        applyState();
        confirmButton.requestFocusInWindow();
    }

    private void backToBasket() {
        if (state != PageState.CONFIRMING) return;
        state = PageState.EDITING;
        showNotice(" ", false);
        applyState();
        checkoutButton.requestFocusInWindow();
    }

    private void confirmSale() {
        if (state != PageState.CONFIRMING) return;
        // The same basket keeps the same sale ID, so "one logical checkout" stays one ID even after a
        // refusal. A changed basket is a new checkout, which is safe: the old ID was never saved.
        if (draft == null || !draft.lines().equals(basket.lines())) {
            draft = PendingCheckout.start(shopId, deviceId, basket.lines(), LocalDate.now());
        }
        runSaleStep(checkout::submit);
    }

    private void retrySale() {
        if (state == PageState.UNCERTAIN) runSaleStep(checkout::retry);
    }

    private void discardSale() {
        if (state == PageState.UNCERTAIN) runSaleStep(checkout::discard);
    }

    private void runSaleStep(Function<PendingCheckout, Outcome> step) {
        PendingCheckout sending = draft;
        state = PageState.SENDING;
        showNotice(t("sales.saving"), false);
        applyState();
        activeWorkers++;
        new SwingWorker<Outcome, Void>() {
            @Override protected Outcome doInBackground() {
                return step.apply(sending);
            }

            @Override protected void done() {
                activeWorkers--;
                handleOutcome(sending, resultOf(this));
                applyState();
            }
        }.execute();
    }

    private static Outcome resultOf(SwingWorker<Outcome, Void> worker) {
        try {
            return worker.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome.Uncertain(interrupted);
        } catch (ExecutionException failure) {
            return new Outcome.Uncertain(failure);
        }
    }

    private void handleOutcome(PendingCheckout sent, Outcome outcome) {
        switch (outcome) {
            case Outcome.Saved saved -> {
                basket.clear();
                draft = null;
                state = PageState.EDITING;
                showSale("sales.success", saved.sale());
                refreshBasketView();
                reload();
                search.requestFocusInWindow();
            }
            case Outcome.NotSent notSent -> {
                LOG.log(Level.WARNING, "Could not save the unfinished sale file", notSent.cause());
                state = PageState.EDITING;
                if (notSent.cause() instanceof PendingCheckoutStore.ConflictException
                        || notSent.cause() instanceof PendingCheckoutStore.BusyException) needsRecovery = true;
                showNotice(t(notSent.cause() instanceof PendingCheckoutStore.ConflictException
                        || notSent.cause() instanceof PendingCheckoutStore.BusyException
                        ? "sales.busy" : "sales.error.notSent"), true);
            }
            case Outcome.Rejected rejected -> {
                LOG.log(Level.INFO, rejected.cause(), () -> "Sale refused: " + sent.receiptNumber());
                state = PageState.EDITING;
                showNotice(t(rejected.reason() == Checkout.Reason.NOT_ENOUGH_STOCK
                        ? "sales.error.notEnoughStock" : "sales.error.refused"), true);
                reload();       // the stock column now shows why
            }
            case Outcome.Uncertain uncertain -> {
                LOG.log(Level.WARNING, uncertain.cause(), () -> "Sale not confirmed: " + sent.receiptNumber());
                state = PageState.UNCERTAIN;
                showNotice(t("sales.uncertain", sent.receiptNumber()), true);
            }
            case Outcome.Discarded discarded -> {
                draft = null;
                state = PageState.EDITING;
                showNotice(t("sales.discarded"), false);
            }
        }
    }

    private void setAsideDamagedFile() {
        if (state != PageState.DAMAGED) return;
        state = PageState.SENDING;
        applyState();
        activeWorkers++;
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws IOException {
                return checkout.setAsideDamagedFile();
            }

            @Override protected void done() {
                activeWorkers--;
                try {
                    Path kept = get();
                    showNotice(t("sales.setAside.done", kept.getFileName()), false);
                    state = PageState.CHECKING;
                    needsRecovery = true;
                    reload();
                } catch (InterruptedException | ExecutionException failure) {
                    LOG.log(Level.WARNING, "Could not set the damaged file aside", failure);
                    state = PageState.DAMAGED;
                    showNotice(t("sales.setAside.failed"), true);
                }
                applyState();
            }
        }.execute();
    }

    /** The one place that decides what can be clicked. */
    private void applyState() {
        boolean editing = state == PageState.EDITING;
        search.setEnabled(editing);
        results.setEnabled(editing);
        addButton.setEnabled(editing && selectedHasStock());
        basketTable.setEnabled(editing);
        removeButton.setEnabled(editing && !basket.isEmpty());removeButton.setVisible(!basket.isEmpty());
        checkoutButton.setEnabled(editing && !basket.isEmpty());
        checkoutButton.setVisible(state != PageState.CONFIRMING && state != PageState.UNCERTAIN && state != PageState.DAMAGED);
        confirmButton.setVisible(state == PageState.CONFIRMING);
        backButton.setVisible(state == PageState.CONFIRMING);
        retryButton.setVisible(state == PageState.UNCERTAIN);
        discardButton.setVisible(state == PageState.UNCERTAIN);
        setAsideButton.setVisible(state == PageState.DAMAGED);
        refresh.setEnabled(!loading && state != PageState.SENDING);
        if(noticeStrip!=null)noticeStrip.setVisible(!notice.getText().isBlank());
        notice.setRows(state == PageState.DAMAGED ? 3 : 2);     // the damaged message shows a long path
        revalidate();
        repaint();
    }

    private void applyFilter() {
        String query = search.getText();
        visible = catalog.stream()
                .filter(product -> matches(product, familyNames.get(product.familyId()), query))
                .toList();
        resultsModel.fireTableDataChanged();
        if (!visible.isEmpty()) results.setRowSelectionInterval(0, 0);
        resultChosenWithArrows = false;
        updateResultsHint();
        applyState();
    }

    /** With nothing typed, Enter adds only a result chosen with the arrow keys, never the first one. */
    private void addFromSearchBox() {
        if (search.getText().isBlank() && !resultChosenWithArrows) return;
        addSelectedProduct();
    }

    /** Every typed word must appear somewhere, ignoring case and accents. */
    static boolean matches(Product product, String familyName, String query) {
        String text = fold(String.join(" ", product.name(),
                Objects.toString(product.reference(), ""), Objects.toString(product.modelReference(), ""),
                Objects.toString(product.category(), ""), Objects.toString(familyName, ""),
                String.join(" ", product.specifications().keySet()),
                String.join(" ", product.specifications().values())));
        return Arrays.stream(fold(query).strip().split("\\s+")).allMatch(text::contains);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private boolean selectedHasStock() {
        int row = results.getSelectedRow();
        if (loading || loadFailed || stockFailed || row < 0 || row >= visible.size()) return false;
        StockLevel level = levels.get(visible.get(row).id());
        return level != null && level.quantityMilli() > 0;
    }

    private void updateResultsHint() {
        if (loading) resultsHint.setText(t("catalog.loading"));
        else if (loadFailed) resultsHint.setText(t("catalog.loadError") + " " + t("catalog.retryHint"));
        else if (stockFailed) resultsHint.setText(t("catalog.stockError"));
        else if (catalog.isEmpty()) resultsHint.setText(t("catalog.empty"));
        else if (visible.isEmpty()) resultsHint.setText(t("catalog.noMatches"));
        else if (!selectedHasStock()) resultsHint.setText(t("sales.outOfStockHint"));
        else resultsHint.setText(t("sales.addHint"));
        if(resultsStrip!=null){
            boolean warning=stockFailed||loadFailed||(!visible.isEmpty()&&!selectedHasStock());
            resultsStrip.setOpaque(warning);resultsStrip.getComponent(0).setVisible(warning);
            resultsHint.setRows(warning?3:1);resultsHint.setForeground(warning?new Color(0x8A571E):CatalogStyle.MUTED);
        }
    }

    private void refreshBasketView() {
        int selected = basketTable.getSelectedRow();
        basketModel.fireTableDataChanged();
        if (selected >= 0 && selected < basket.lines().size()) {
            basketTable.changeSelection(selected, COLUMN_QUANTITY, false, false);
        }
        totalLabel.setText(t("sales.total", money.format(basket.total())));
        if(cartHost!=null&&compactCart!=basket.isEmpty()){compactCart=basket.isEmpty();cartHost.remove(cartCard);cartHost.add(cartCard,compactCart?BorderLayout.NORTH:BorderLayout.CENTER);cartHost.revalidate();}
        basketHint.setText(basket.isEmpty()?"":t("sales.quantityHint"));
    }

    /** Finishes a quantity being typed. Returns false if what was typed was refused. */
    private boolean stopQuantityEdit() {
        quantityRefused = false;
        if (basketTable.isEditing()) basketTable.getCellEditor().stopCellEditing();
        return !quantityRefused;
    }

    private void showSale(String messageKey, Sale sale) {
        showNotice(t(messageKey, sale.receiptNumber(), money.format(sale.totalAmount())), false);
    }

    private void showNotice(String text, boolean problem) {
        notice.setForeground(problem ? PROBLEM : CatalogStyle.GREEN);
        notice.setText(text);
        if(noticeStrip!=null){noticeStrip.setBackground(problem?new Color(0xFFF2DD):new Color(0xE4F0E8));noticeStrip.setVisible(!text.isBlank());((JLabel)noticeStrip.getComponent(0)).setText(problem?"!":"i");}
    }

    private String t(String key, Object... arguments) {
        String pattern = messages.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    private String stockText(UUID productId, String unit) {
        StockLevel level = levels.get(productId);
        if (stockFailed || level == null) return t("catalog.unavailable");
        long quantity = level.quantityMilli();
        String name = unitName(unit);
        boolean plural = !"kg".equals(unit) && Math.abs(quantity) != Quantities.ONE;
        return Quantities.format(quantity, locale) + " " + name + (plural ? "s" : "");
    }

    private String unitName(String unit) {
        return "kg".equals(unit) ? "kg" : t("unit." + unit).toLowerCase(locale);
    }

    private JComponent buildResultsArea() {
        search.putClientProperty("JTextField.placeholderText",t("catalog.search"));
        search.getAccessibleContext().setAccessibleName(t("catalog.search"));results.getAccessibleContext().setAccessibleName(t("sales.results"));
        results.getColumnModel().getColumn(0).setPreferredWidth(200);
        results.getColumnModel().getColumn(3).setPreferredWidth(95);results.getColumnModel().getColumn(4).setPreferredWidth(135);
        for(int hidden:new int[]{1,2}){var column=results.getColumnModel().getColumn(hidden);column.setMinWidth(0);column.setMaxWidth(0);column.setPreferredWidth(0);}
        JPanel heading=CatalogStyle.panel(new BorderLayout(0,12));heading.add(CatalogStyle.label(t("sales.productsHeading"),20,true),BorderLayout.NORTH);
        search.setPreferredSize(new Dimension(200,40));heading.add(search,BorderLayout.CENTER);
        JPanel below=CatalogStyle.panel(new BorderLayout(0,10));resultsStrip=CatalogStyle.alertStrip(resultsHint);below.add(resultsStrip,BorderLayout.CENTER);below.add(addButton,BorderLayout.SOUTH);
        JPanel area=CatalogStyle.surface(new BorderLayout(0,12));area.add(heading,BorderLayout.NORTH);
        area.add(CatalogStyle.tableScroll(results,t("empty.picker")),BorderLayout.CENTER);
        CatalogStyle.alignColumns(results,3,4);results.setRowHeight(56);
        results.getColumnModel().getColumn(0).setCellRenderer(CatalogStyle.twoLineRenderer(row->Objects.toString(visible.get(row).reference(),"")+" · "+resultsModel.getValueAt(row,2)));
        results.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer(){
            @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int col){
                super.getTableCellRendererComponent(table,value,selected,focus,row,col);Product p=visible.get(row);
                setHorizontalAlignment(SwingConstants.RIGHT);setText("<html><div style='text-align:right'>"+money.format(p.unitPrice())+" FCFA<br>/ "+unitName(p.unit())+"</div></html>");
                setBorder(BorderFactory.createEmptyBorder(3,8,3,8));setToolTipText(String.valueOf(value));return this;
            }
        });
        area.add(below,BorderLayout.SOUTH);return area;
    }

    private JComponent buildBasketArea() {
        basketTable.getAccessibleContext().setAccessibleName(t("sales.basket"));basketTable.setRowHeight(56);
        basketTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        basketTable.getColumnModel().getColumn(COLUMN_QUANTITY).setPreferredWidth(48);
        basketTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        basketTable.getColumnModel().getColumn(COLUMN_QUANTITY).setMinWidth(48);basketTable.getColumnModel().getColumn(5).setMinWidth(70);
        for(int hidden:new int[]{1,2,4,6}){var column=basketTable.getColumnModel().getColumn(hidden);column.setMinWidth(0);column.setMaxWidth(0);column.setPreferredWidth(0);}
        basketTable.getColumnModel().getColumn(COLUMN_QUANTITY).setHeaderValue(t("sales.shortQuantity"));
        basketTable.getColumnModel().getColumn(5).setHeaderValue(t("sales.shortTotal"));
        DefaultCellEditor quantityEditor=new DefaultCellEditor(new JTextField());quantityEditor.setClickCountToStart(1);
        basketTable.getColumnModel().getColumn(COLUMN_QUANTITY).setCellEditor(quantityEditor);
        basketTable.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);basketTable.setSurrendersFocusOnKeystroke(true);
        JPanel area=CatalogStyle.surface(new BorderLayout(0,12));area.add(CatalogStyle.label(t("sales.basketHeading"),20,true),BorderLayout.NORTH);
        area.add(CatalogStyle.tableScroll(basketTable,t("empty.cart")),BorderLayout.CENTER);
        CatalogStyle.alignColumns(basketTable,3,4,5,6);
        basketTable.getColumnModel().getColumn(0).setCellRenderer(CatalogStyle.twoLineRenderer(row->{BasketLine line=basket.lines().get(row);return money.format(line.unitPrice())+" / "+unitName(line.unit());}));
        basketTable.getColumnModel().getColumn(COLUMN_QUANTITY).setCellRenderer(new QuantityRenderer());
        JPanel footer=CatalogStyle.panel(new BorderLayout(0,12));
        JPanel tools=CatalogStyle.panel(new BorderLayout(0,8));tools.add(removeButton,BorderLayout.NORTH);
        basketHint.setFont(basketHint.getFont().deriveFont(12f));tools.add(basketHint,BorderLayout.SOUTH);footer.add(tools,BorderLayout.NORTH);
        footer.add(buildFooter(),BorderLayout.CENTER);area.add(footer,BorderLayout.SOUTH);return area;
    }

    private JComponent buildFooter() {
        notice.setEditable(false);notice.setLineWrap(true);notice.setWrapStyleWord(true);notice.setOpaque(false);notice.setFocusable(false);
        notice.setBorder(BorderFactory.createEmptyBorder());notice.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,14));
        noticeStrip=CatalogStyle.alertStrip(notice);noticeStrip.setVisible(false);
        JPanel actions=CatalogStyle.panel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints action=new java.awt.GridBagConstraints();action.gridx=0;action.gridy=0;action.fill=java.awt.GridBagConstraints.HORIZONTAL;action.weightx=1;action.insets=new java.awt.Insets(4,0,0,0);
        for(JButton button:new JButton[]{setAsideButton,discardButton,retryButton,backButton,confirmButton,checkoutButton}){actions.add(button,action);action.gridy++;}
        checkoutButton.putClientProperty("JComponent.minimumHeight",46);
        JPanel footer=CatalogStyle.panel(new BorderLayout(0,12));footer.add(totalLabel,BorderLayout.NORTH);
        footer.add(noticeStrip,BorderLayout.CENTER);footer.add(actions,BorderLayout.SOUTH);return footer;
    }

    private void connectControls() {
        results.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) { updateResultsHint(); applyState(); }
        });
        refresh.addActionListener(event -> reload());
        addButton.addActionListener(event -> addSelectedProduct());
        removeButton.addActionListener(event -> removeSelectedLine());
        checkoutButton.addActionListener(event -> beginCheckout());
        confirmButton.addActionListener(event -> confirmSale());
        backButton.addActionListener(event -> backToBasket());
        retryButton.addActionListener(event -> retrySale());
        discardButton.addActionListener(event -> discardSale());
        setAsideButton.addActionListener(event -> setAsideDamagedFile());

        search.addActionListener(event -> addFromSearchBox());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        // Up and Down move through the results while the cursor stays in the search box.
        bind(search, JComponent.WHEN_FOCUSED, "DOWN", "nextResult", () -> moveResult(1));
        bind(search, JComponent.WHEN_FOCUSED, "UP", "previousResult", () -> moveResult(-1));
        bind(results, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "ENTER", "addToBasket", this::addSelectedProduct);
        results.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) addSelectedProduct();
            }
        });
        bind(basketTable, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "DELETE", "removeLine", this::removeSelectedLine);
    }

    private void moveResult(int step) {
        if (visible.isEmpty() || state != PageState.EDITING) return;
        int row = Math.floorMod(results.getSelectedRow() + step, visible.size());
        results.setRowSelectionInterval(row, row);
        results.scrollRectToVisible(results.getCellRect(row, 0, true));
        resultChosenWithArrows = true;
    }

    private static void bind(JComponent component, int condition, String key, String name, Runnable action) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private static <T extends JComponent> T named(T component, String name) {
        component.setName(name);
        return component;
    }

    private static <T extends JTable> T styledTable(T table, String name) {
        table.setName(name);
        table.setRowHeight(40);
        CatalogStyle.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false);
        table.setGridColor(CatalogStyle.LINE);
        table.setFillsViewportHeight(true);
        // Tab leaves the table instead of walking through its cells.
        table.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        table.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        return table;
    }

    /** Typing a quantity replaces the old one: without this, typing 2 over 1 would give 12. */
    private static final class QuantityTable extends JTable {
        QuantityTable(BasketModel model) { super(model); }

        @Override public Component prepareEditor(TableCellEditor editor, int row, int column) {
            Component component = super.prepareEditor(editor, row, column);
            if (component instanceof JTextComponent text) text.selectAll();
            return component;
        }
    }

    /** Draws the quantity like a small input box while it can be changed, so it invites a click. */
    private static final class QuantityRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focused, int row, int column) {
            Component cell = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (table.isCellEditable(row, column)) {
                setHorizontalAlignment(SwingConstants.RIGHT);
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(CatalogStyle.MUTED),BorderFactory.createEmptyBorder(0,8,0,8)));
                if (!selected) setBackground(Color.WHITE);
            }
            return cell;
        }
    }

    /** The search results. Families are not listed: only a variant can be sold. */
    private final class ResultsModel extends AbstractTableModel {
        private final String[] columns = {t("catalog.variant"), t("catalog.reference"),
            t("sales.column.characteristics"), t("catalog.available"), t("catalog.price")};

        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Object getValueAt(int row, int column) {
            Product product = visible.get(row);
            return switch (column) {
                case 0 -> BasketLine.oneOf(product, familyNames.get(product.familyId())).label();
                case 1 -> Objects.toString(product.reference(), "");
                case 2 -> product.specifications().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + " : " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
                case 3 -> stockText(product.id(), product.unit());
                default -> money.format(product.unitPrice()) + " FCFA / " + unitName(product.unit());
            };
        }
    }

    /** The basket. Only the quantity can be edited, and only while the basket is not locked. */
    private final class BasketModel extends AbstractTableModel {
        private final String[] columns = {t("sales.column.product"), t("catalog.reference"), t("sales.column.unit"),
            t("sales.column.quantity"), t("sales.column.unitPrice"), t("sales.column.lineTotal"), t("sales.column.stock")};

        @Override public int getRowCount() { return basket.lines().size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return String.class; }

        @Override public boolean isCellEditable(int row, int column) {
            return column == COLUMN_QUANTITY && state == PageState.EDITING;
        }

        @Override public Object getValueAt(int row, int column) {
            BasketLine line = basket.lines().get(row);
            return switch (column) {
                case 0 -> line.label();
                case 1 -> line.reference();
                case 2 -> unitName(line.unit());
                case COLUMN_QUANTITY -> Quantities.format(line.quantityMilli(), locale);
                case 4 -> money.format(line.unitPrice());
                case 5 -> money.format(line.total());
                default -> stockText(line.productId(), line.unit());
            };
        }

        @Override public void setValueAt(Object value, int row, int column) {
            BasketLine line = basket.lines().get(row);
            try {
                basket.setQuantity(line.productId(), Quantities.parseMilli(String.valueOf(value), line.unit()));
                showNotice(" ", false);
            } catch (InvalidQuantityException invalid) {
                quantityRefused = true;
                showNotice(quantityProblem(invalid.problem()), true);
            } catch (ArithmeticException tooLarge) {
                quantityRefused = true;
                showNotice(t("sales.error.tooLarge"), true);
            }
            refreshBasketView();
        }

        private String quantityProblem(Quantities.Problem problem) {
            return t(switch (problem) {
                case BLANK -> "sales.quantity.blank";
                case NOT_A_NUMBER -> "sales.quantity.notANumber";
                case NOT_POSITIVE -> "sales.quantity.notPositive";
                case MUST_BE_WHOLE -> "sales.quantity.mustBeWhole";
                case TOO_MANY_DECIMALS -> "sales.quantity.tooManyDecimals";
                case TOO_LARGE -> "sales.error.tooLarge";
            });
        }
    }
}

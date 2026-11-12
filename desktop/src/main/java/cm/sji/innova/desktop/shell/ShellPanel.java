package cm.sji.innova.desktop.shell;

import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;

import cm.sji.innova.desktop.sales.SalesPanel;
import cm.sji.innova.desktop.dashboard.DashboardPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * The content of the main Cella window. It shows one of three pages:
 * <ol>
 *   <li><b>loading</b> while the database opens (in a background thread),</li>
 *   <li><b>error</b> with Retry and Exit if opening failed,</li>
 *   <li><b>main</b>: navigation on the left, the selected screen on the right.</li>
 * </ol>
 * All methods must be called on the Swing event thread.
 */
public final class ShellPanel extends JPanel {
    public enum Page { LOADING, ERROR, MAIN }

    private static final Logger LOG = Logger.getLogger(ShellPanel.class.getName());

    /** One entry of the navigation list. Add more entries here when new screens exist. */
    private record NavItem(String id, String label) {
        @Override public String toString() { return label; }
    }

    private final Messages messages;
    private final LocalAppData location;
    private final Callable<AppContext> loader;
    private final Runnable onExit;

    private final CardLayout pages = new CardLayout();
    private final JTextArea errorDetail = new JTextArea();
    private final JButton retryButton;
    private final JList<NavItem> navigation = new JList<>(new DefaultListModel<>());
    private final CardLayout screens = new CardLayout();
    private final JPanel screenArea = new JPanel(screens);
    private final JLabel statusLine = new JLabel(" ");
    private final JLabel screenHeading = new JLabel();
    private SalesPanel salesPanel;
    private DashboardPanel dashboard;
    private JPanel sidebar;
    private JLabel wordmark;
    private JLabel tagline;
    private JLabel localCaption;
    private boolean compactSidebar;
    private Page currentPage;
    private CompletionStage<Void> productsReady;
    private AppContext activeContext;
    private cm.sji.innova.desktop.catalog.CatalogPanel catalog;
    private cm.sji.innova.desktop.stock.StockPanel stockPanel;

    public ShellPanel(Messages messages, LocalAppData location, Callable<AppContext> loader, Runnable onExit) {
        super(new BorderLayout());
        this.messages = messages;
        this.location = location;
        this.loader = loader;
        this.onExit = onExit;

        JPanel cards = new JPanel(pages);
        cards.add(buildLoadingPage(), Page.LOADING.name());
        retryButton = CatalogStyle.button(messages.get("shell.error.retry"),true);
        cards.add(buildErrorPage(), Page.ERROR.name());
        cards.add(buildMainPage(), Page.MAIN.name());
        add(cards, BorderLayout.CENTER);
        show(Page.LOADING);
    }

    /** Opens the shop data in the background, then shows the main page or the error page. */
    public void start() {
        show(Page.LOADING);
        new SwingWorker<AppContext, Void>() {
            @Override protected AppContext doInBackground() throws Exception {
                return loader.call();           // runs OFF the event thread
            }
            @Override protected void done() {   // runs ON the event thread
                try {
                    showMain(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    showError(interrupted);
                } catch (ExecutionException failure) {
                    showError(failure.getCause() == null ? failure : failure.getCause());
                }
            }
        }.execute();
    }

    public void showMain(AppContext context) {
        screenArea.removeAll();
        activeContext = context;
        stockPanel = null;
        dashboard = new DashboardPanel(context, messages, this::selectScreen);
        screenArea.add(dashboard, "home");
        var products = ProductsSlot.create(context, messages);
        catalog = products;
        productsReady = products.initialLoadCompletion();
        screenArea.add(products, "products");
        salesPanel = SalesSlot.create(context, messages);       // it reads nothing until it is opened
        screenArea.add(salesPanel, "sales");
        DefaultListModel<NavItem> model = (DefaultListModel<NavItem>) navigation.getModel();
        model.clear();
        model.addElement(new NavItem("home", messages.get("shell.nav.home")));
        model.addElement(new NavItem("products", messages.get("shell.nav.products")));
        model.addElement(new NavItem("stock", messages.get("shell.nav.stock")));
        model.addElement(new NavItem("sales", messages.get("shell.nav.sales")));
        navigation.setSelectedIndex(1);
        statusLine.setText(" ");
        show(Page.MAIN);
        navigation.requestFocusInWindow();
    }

    public void showError(Throwable failure) {
        // Full details for developers go to the log (console and logs/cella-0.log); the page shows a summary.
        LOG.log(Level.SEVERE, failure, () -> "Start-up failed for data folder " + location.directory());
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
        errorDetail.setText(messages.get("shell.error.dataFolder", location.directory())
                + System.lineSeparator() + System.lineSeparator() + message);
        errorDetail.setCaretPosition(0);
        show(Page.ERROR);
        retryButton.requestFocusInWindow();
    }

    public Page currentPage() { return currentPage; }

    /** Obtain on the EDT after showMain, then await off the EDT. */
    public CompletionStage<Void> productsReady() {
        return java.util.Objects.requireNonNull(productsReady, "Products screen is not initialized");
    }

    /** The id of the screen selected in the navigation, or null if nothing is selected. */
    public String selectedScreenId() {
        NavItem item = navigation.getSelectedValue();
        return item == null ? null : item.id();
    }

    /** Select an implemented screen without exposing the navigation model. Call on the EDT. */
    public void selectScreen(String id) {
        for (int i = 0; i < navigation.getModel().getSize(); i++) {
            if (navigation.getModel().getElementAt(i).id().equals(id)) {
                navigation.setSelectedIndex(i); return;
            }
        }
        throw new IllegalArgumentException("Unknown screen: " + id);
    }

    private void show(Page page) {
        currentPage = page;
        pages.show((JComponent) getComponent(0), page.name());
    }

    private JComponent buildLoadingPage() {
        JPanel page = new JPanel(new GridBagLayout());page.setBackground(CatalogStyle.PAPER);
        JPanel box = CatalogStyle.surface(new BorderLayout(0,18));box.setPreferredSize(new java.awt.Dimension(440,190));
        JPanel introduction=CatalogStyle.panel(new BorderLayout(0,12));
        JLabel brand=CatalogStyle.label("cella.",37,false);brand.setFont(new java.awt.Font(java.awt.Font.SERIF,java.awt.Font.PLAIN,37));brand.setForeground(CatalogStyle.GREEN);
        introduction.add(brand,BorderLayout.NORTH);introduction.add(CatalogStyle.label(messages.get("shell.loading"),18,true),BorderLayout.CENTER);box.add(introduction,BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new java.awt.Dimension(240,6));box.add(bar,BorderLayout.SOUTH);
        page.add(box, new GridBagConstraints());
        return page;
    }

    private JComponent buildErrorPage() {
        JPanel page = CatalogStyle.surface(new BorderLayout(0,18));
        page.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JLabel title = CatalogStyle.label(messages.get("shell.error.title"),28,true);
        errorDetail.setEditable(false);
        errorDetail.setLineWrap(true);
        errorDetail.setWrapStyleWord(true);
        JButton exit = CatalogStyle.button(messages.get("shell.error.exit"),false);
        retryButton.addActionListener(event -> start());
        exit.addActionListener(event -> onExit.run());
        JPanel buttons = CatalogStyle.panel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(retryButton);
        buttons.add(exit);
        page.add(title, BorderLayout.NORTH);
        errorDetail.setRows(5);errorDetail.setOpaque(false);
        JScrollPane detailScroll=new JScrollPane(errorDetail);detailScroll.setBorder(null);detailScroll.setOpaque(false);detailScroll.getViewport().setOpaque(false);
        page.add(CatalogStyle.feedback(detailScroll,FeedbackStrip.Tone.ERROR), BorderLayout.CENTER);
        page.add(buttons, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(() -> {
            if (page.getRootPane() != null) page.getRootPane().setDefaultButton(retryButton);
        });
        JPanel outer=new JPanel(new BorderLayout());outer.setBackground(CatalogStyle.PAPER);
        outer.setBorder(BorderFactory.createEmptyBorder(28,30,28,30));outer.add(CatalogStyle.widthLimited(page,720),BorderLayout.NORTH);return outer;
    }

    private JComponent buildMainPage() {
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navigation.setFixedCellHeight(36);
        navigation.getAccessibleContext().setAccessibleName(messages.get("shell.nav.accessibleName"));
        navigation.addListSelectionListener(event -> {
            NavItem item = navigation.getSelectedValue();
            if (!event.getValueIsAdjusting() && item != null) {
                if ("home".equals(item.id())) {
                    dashboard.reload();
                } else if ("stock".equals(item.id())) {
                    if (stockPanel == null) {
                        java.nio.file.Path pendingFile = activeContext.location().directory()
                                .resolve("pending-stock-receipt.properties");
                        cm.sji.innova.desktop.stock.PendingReceiptStore receiptStore =
                                new cm.sji.innova.desktop.stock.FilePendingReceiptStore(pendingFile);
                        java.nio.file.Path adjustmentFile = activeContext.location().directory()
                                .resolve("pending-stock-adjustment.properties");
                        cm.sji.innova.desktop.stock.PendingStockAdjustmentStore adjustmentStore =
                                new cm.sji.innova.desktop.stock.FilePendingStockAdjustmentStore(adjustmentFile);
                        stockPanel = new cm.sji.innova.desktop.stock.StockPanel(activeContext.stock(),
                                activeContext.products(), activeContext.shopId(), activeContext.deviceId(),
                                messages.bundle(), receiptStore, adjustmentStore);
                        screenArea.add(stockPanel, "stock");
                    } else stockPanel.loadProductsAndStock();
                } else if ("products".equals(item.id()) && catalog != null && productsReady.toCompletableFuture().isDone()) {
                    catalog.refreshIfBrowsing();
                } else if ("sales".equals(item.id())) {
                    salesPanel.reload();    // stock may have changed since this screen was last open
                }
                screens.show(screenArea, item.id());
                screenHeading.setText("products".equals(item.id()) ? messages.get("shell.catalog") : item.label());
            }
        });
        sidebar = new JPanel(new BorderLayout(0, 32)) {
            @Override protected void paintComponent(java.awt.Graphics graphics) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setPaint(new java.awt.GradientPaint(0,0,new java.awt.Color(0x357C60),
                        getWidth(),getHeight(),new java.awt.Color(0x12372D)));
                g.fillRect(0,0,getWidth(),getHeight()); g.dispose();
            }
        };
        sidebar.setName("cellaSidebar");sidebar.setBorder(BorderFactory.createEmptyBorder(34,20,24,20));
        sidebar.setPreferredSize(new java.awt.Dimension(248,0));
        JPanel brand = new JPanel(new java.awt.GridLayout(0,1,0,10));brand.setOpaque(false);
        wordmark = new JLabel("cella.");wordmark.setFont(new java.awt.Font(java.awt.Font.SERIF,java.awt.Font.BOLD,40));wordmark.setForeground(java.awt.Color.WHITE);
        tagline = new JLabel(messages.get("shell.tagline"));tagline.setFont(tagline.getFont().deriveFont(13f));tagline.setForeground(new java.awt.Color(0xD8E7DC));
        brand.add(wordmark);brand.add(tagline);sidebar.add(brand,BorderLayout.NORTH);
        navigation.setOpaque(false);navigation.setForeground(java.awt.Color.WHITE);navigation.setFixedCellHeight(54);
        navigation.setCellRenderer(new javax.swing.DefaultListCellRenderer(){
            private boolean active;
            private boolean keyFocus;
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                active=selected;keyFocus=focus;label.setOpaque(false);label.setForeground(java.awt.Color.WHITE);
                label.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,compactSidebar?14:15));
                label.setBorder(BorderFactory.createEmptyBorder(11,12,11,8));
                String id=((NavItem)value).id();
                int section=switch(id){case "home"->-1;case "products"->0;case "stock"->1;default->2;};
                label.setIcon(new CatalogStyle.NavigationIcon(section));label.setIconTextGap(12);
                label.setToolTipText(value.toString());
                return label;
            }
            @Override protected void paintComponent(java.awt.Graphics graphics){
                if(active){
                    java.awt.Graphics2D g=(java.awt.Graphics2D)graphics.create();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new java.awt.Color(255,255,255,28));g.fillRoundRect(0,3,getWidth(),getHeight()-6,18,18);
                    g.setColor(new java.awt.Color(0xD4E0B8));g.fillRoundRect(0,17,3,20,3,3);g.dispose();
                }
                super.paintComponent(graphics);
                if(keyFocus){
                    java.awt.Graphics2D g=(java.awt.Graphics2D)graphics.create();
                    g.setColor(new java.awt.Color(255,255,255,150));
                    g.drawRoundRect(1,4,getWidth()-3,getHeight()-9,16,16);g.dispose();
                }
            }
        });
        JPanel navHolder=new JPanel(new BorderLayout());navHolder.setOpaque(false);navHolder.add(navigation,BorderLayout.NORTH);sidebar.add(navHolder,BorderLayout.CENTER);
        JPanel footer=CatalogStyle.panel(new java.awt.GridLayout(0,1,0,7));
        localCaption=CatalogStyle.label(messages.get("shell.local"),13,false);localCaption.setForeground(new java.awt.Color(0xD6E4CE));
        JLabel offline=CatalogStyle.label(messages.get("shell.offline"),12,false);offline.setForeground(new java.awt.Color(0xBED1C3));
        footer.add(localCaption);footer.add(offline);sidebar.add(footer,BorderLayout.SOUTH);
        statusLine.setText(" "); // Technical identities stay in the data folder.
        JPanel page = new JPanel(new BorderLayout());
        page.add(sidebar, BorderLayout.WEST);
        JPanel content=new JPanel(new BorderLayout());
        screenHeading.setFont(screenHeading.getFont().deriveFont(java.awt.Font.BOLD,13f));screenHeading.setForeground(CatalogStyle.MUTED);
        screenHeading.setText(messages.get("shell.catalog"));screenHeading.setBorder(BorderFactory.createEmptyBorder(18,30,18,20));screenHeading.setOpaque(true);screenHeading.setBackground(java.awt.Color.WHITE);
        content.add(screenArea,BorderLayout.CENTER);
        page.add(content,BorderLayout.CENTER);
        return page;
    }
    /** Keep labelled navigation at the small supported size; give the wider sidebar room on desktop. */
    @Override public void doLayout() {
        if (sidebar != null && getWidth() > 0) {
            boolean compact = getWidth() < 1100;
            int width = compact ? 176 : 248;
            if (sidebar.getPreferredSize().width != width) {
                compactSidebar = compact;
                sidebar.setPreferredSize(new java.awt.Dimension(width, 0));
                sidebar.setBorder(BorderFactory.createEmptyBorder(30,compact?12:20,24,compact?12:20));
                wordmark.setFont(new java.awt.Font(java.awt.Font.SERIF,java.awt.Font.BOLD,compact?33:40));
                tagline.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,compact?12:13));
                navigation.repaint();
            }
        }
        super.doLayout();
    }

    /** Await off the EDT after selecting home, for diagnostics and layout checks. */
    public CompletionStage<cm.sji.innova.desktop.dashboard.DashboardSummary> dashboardReady() {
        return java.util.Objects.requireNonNull(dashboard, "Dashboard not initialized").loadCompletion();
    }

}

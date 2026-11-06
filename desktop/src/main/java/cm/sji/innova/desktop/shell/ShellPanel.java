package cm.sji.innova.desktop.shell;

import cm.sji.innova.desktop.sales.SalesPanel;
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
        retryButton = new JButton(messages.get("shell.error.retry"));
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
        var products = ProductsSlot.create(context, messages);
        catalog = products;
        productsReady = products.initialLoadCompletion();
        screenArea.add(products, "products");
        salesPanel = SalesSlot.create(context, messages);       // it reads nothing until it is opened
        screenArea.add(salesPanel, "sales");
        DefaultListModel<NavItem> model = (DefaultListModel<NavItem>) navigation.getModel();
        model.clear();
        model.addElement(new NavItem("products", messages.get("shell.nav.products")));
        model.addElement(new NavItem("stock", messages.get("shell.nav.stock")));
        model.addElement(new NavItem("sales", messages.get("shell.nav.sales")));
        navigation.setSelectedIndex(0);
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
        JPanel page = new JPanel(new GridBagLayout());
        JPanel box = new JPanel(new BorderLayout(0, 12));
        box.add(new JLabel(messages.get("shell.loading")), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        box.add(bar, BorderLayout.CENTER);
        page.add(box, new GridBagConstraints());
        return page;
    }

    private JComponent buildErrorPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JLabel title = new JLabel(messages.get("shell.error.title"));
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 18f));
        errorDetail.setEditable(false);
        errorDetail.setLineWrap(true);
        errorDetail.setWrapStyleWord(true);
        JButton exit = new JButton(messages.get("shell.error.exit"));
        retryButton.addActionListener(event -> start());
        exit.addActionListener(event -> onExit.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(retryButton);
        buttons.add(exit);
        page.add(title, BorderLayout.NORTH);
        page.add(new JScrollPane(errorDetail), BorderLayout.CENTER);
        page.add(buttons, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(() -> {
            if (page.getRootPane() != null) page.getRootPane().setDefaultButton(retryButton);
        });
        return page;
    }

    private JComponent buildMainPage() {
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navigation.setFixedCellHeight(36);
        navigation.getAccessibleContext().setAccessibleName(messages.get("shell.nav.accessibleName"));
        navigation.addListSelectionListener(event -> {
            NavItem item = navigation.getSelectedValue();
            if (!event.getValueIsAdjusting() && item != null) {
                if ("stock".equals(item.id())) {
                    if (stockPanel == null) {
                        stockPanel = new cm.sji.innova.desktop.stock.StockPanel(activeContext.stock(),
                                activeContext.products(), activeContext.shopId(), activeContext.deviceId(), messages.bundle());
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
        JPanel sidebar = new JPanel(new BorderLayout(0, 32)) {
            @Override protected void paintComponent(java.awt.Graphics graphics) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setPaint(new java.awt.GradientPaint(0,0,new java.awt.Color(0x328767),
                        getWidth(),getHeight(),new java.awt.Color(0x103A2D)));
                g.fillRect(0,0,getWidth(),getHeight()); g.dispose();
            }
        };
        sidebar.setBorder(BorderFactory.createEmptyBorder(30,16,24,16));
        sidebar.setPreferredSize(new java.awt.Dimension(182,0));
        JPanel brand = new JPanel(new java.awt.GridLayout(0,1,0,10));brand.setOpaque(false);
        JLabel wordmark = new JLabel("cella.");wordmark.setFont(new java.awt.Font(java.awt.Font.SERIF,java.awt.Font.PLAIN,37));wordmark.setForeground(java.awt.Color.WHITE);
        JLabel tagline = new JLabel(messages.get("shell.tagline"));tagline.setFont(tagline.getFont().deriveFont(11f));tagline.setForeground(new java.awt.Color(0xD8E7DC));
        brand.add(wordmark);brand.add(tagline);sidebar.add(brand,BorderLayout.NORTH);
        navigation.setOpaque(false);navigation.setForeground(java.awt.Color.WHITE);navigation.setFixedCellHeight(48);
        navigation.setCellRenderer(new javax.swing.DefaultListCellRenderer(){
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                label.setOpaque(selected);label.setBackground(new java.awt.Color(0x3B8067));label.setForeground(java.awt.Color.WHITE);
                label.setBorder(BorderFactory.createEmptyBorder(10,12,10,8));label.setIcon(new cm.sji.innova.desktop.catalog.CatalogStyle.NavigationIcon(index));label.setIconTextGap(12);
                return label;
            }
        });
        JPanel navHolder=new JPanel(new BorderLayout());navHolder.setOpaque(false);navHolder.add(navigation,BorderLayout.NORTH);sidebar.add(navHolder,BorderLayout.CENTER);
        statusLine.setText(" "); // The technical development UUID stays in the data folder, not the ordinary UI.
        JPanel page = new JPanel(new BorderLayout());
        page.add(sidebar, BorderLayout.WEST);
        JPanel content=new JPanel(new BorderLayout());
        screenHeading.setText(messages.get("shell.catalog"));screenHeading.setBorder(BorderFactory.createEmptyBorder(18,30,18,20));screenHeading.setOpaque(true);screenHeading.setBackground(java.awt.Color.WHITE);
        content.add(screenHeading,BorderLayout.NORTH);content.add(screenArea,BorderLayout.CENTER);
        page.add(content,BorderLayout.CENTER);
        return page;
    }
}

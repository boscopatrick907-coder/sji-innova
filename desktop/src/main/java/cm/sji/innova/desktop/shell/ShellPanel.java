package cm.sji.innova.desktop.shell;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
    private Page currentPage;

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
        screenArea.add(ProductsSlot.create(context, messages), "products");
        DefaultListModel<NavItem> model = (DefaultListModel<NavItem>) navigation.getModel();
        model.clear();
        model.addElement(new NavItem("products", messages.get("shell.nav.products")));
        navigation.setSelectedIndex(0);
        statusLine.setText(messages.get("shell.devShop", context.shopId()));
        show(Page.MAIN);
        navigation.requestFocusInWindow();
    }

    public void showError(Throwable failure) {
        failure.printStackTrace();               // full details for developers
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
        errorDetail.setText(messages.get("shell.error.dataFolder", location.directory())
                + System.lineSeparator() + System.lineSeparator() + message);
        errorDetail.setCaretPosition(0);
        show(Page.ERROR);
        retryButton.requestFocusInWindow();
    }

    public Page currentPage() { return currentPage; }

    /** The id of the screen selected in the navigation, or null if nothing is selected. */
    public String selectedScreenId() {
        NavItem item = navigation.getSelectedValue();
        return item == null ? null : item.id();
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
            if (!event.getValueIsAdjusting() && item != null) screens.show(screenArea, item.id());
        });
        JScrollPane navigationScroll = new JScrollPane(navigation);
        navigationScroll.setPreferredSize(new java.awt.Dimension(180, 0));
        statusLine.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel page = new JPanel(new BorderLayout());
        page.add(navigationScroll, BorderLayout.WEST);
        page.add(screenArea, BorderLayout.CENTER);
        page.add(statusLine, BorderLayout.SOUTH);
        return page;
    }
}

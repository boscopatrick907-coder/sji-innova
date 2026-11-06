package cm.sji.innova.desktop;

import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.AppLogging;
import cm.sji.innova.desktop.shell.LocalAppData;
import cm.sji.innova.desktop.shell.Messages;
import cm.sji.innova.desktop.shell.ShellPanel;
import cm.sji.innova.desktop.shell.StartupOptions;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Desktop entry point.
 *
 * <p>Modes: normal launch (opens the Cella window), {@code --smoke-test} (headless check on a
 * throw-away folder) and {@code --product-demo PATH} (prints demo products into the given
 * database). Normal launch never inserts demo products.
 */
public final class DesktopApplication {
    private static final Logger LOG = Logger.getLogger(DesktopApplication.class.getName());

    private DesktopApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--product-demo".equals(args[0])) {
            ProductStorageDemo.run(java.nio.file.Path.of(args[1]));
            return;
        }
        if (args.length == 1 && "--smoke-test".equals(args[0])) {
            smokeTest();
            return;
        }
        StartupOptions options = StartupOptions.parse(args, System.getenv());
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("A graphical desktop is required. Use --smoke-test for a headless startup check.");
        }
        LocalAppData location = LocalAppData.forOptions(options);
        // From here on, errors are also written to logs/cella-0.log in the data folder.
        AppLogging.install(location);
        LOG.info(() -> "Starting Cella with data folder " + location.directory());
        Messages messages = Messages.load(options.locale());
        SwingUtilities.invokeAndWait(() -> {
            setUpLookAndFeel();
            JFrame frame = new JFrame(messages.get("app.title"));
            ShellPanel shell = new ShellPanel(messages, location,
                    () -> AppContextLoader.load(location), () -> System.exit(0));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(shell);
            frame.setSize(1100, 720);
            frame.setMinimumSize(new java.awt.Dimension(960, 600));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            shell.start();   // opens the database in the background
        });
    }

    /**
     * Headless check: runs the real start-up (folder, shop ID, database) on a temporary folder,
     * builds the main page without showing it, and deletes the folder. Real user data is never touched.
     */
    private static void smokeTest() throws Exception {
        Path temporary = Files.createTempDirectory("cella-smoke-");
        try {
            LocalAppData location = new LocalAppData(temporary);
            AppContext context = AppContextLoader.load(location);
            if (!context.products().list(context.shopId()).isEmpty()) {
                throw new IllegalStateException("A fresh database must not contain demo products");
            }
            Messages messages = Messages.load(Locale.ENGLISH);
            AtomicReference<CompletionStage<Void>> productsReady = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                setUpLookAndFeel();
                ShellPanel shell = new ShellPanel(messages, location, () -> context, () -> { });
                shell.showMain(context);
                productsReady.set(shell.productsReady());
                shell.setSize(960, 600);
                shell.doLayout();
                if (shell.currentPage() != ShellPanel.Page.MAIN || !"products".equals(shell.selectedScreenId())) {
                    throw new IllegalStateException("Main page did not show the Products entry");
                }
            });
            // The Products screen is asynchronous: keep temporary data until its query finishes.
            productsReady.get().toCompletableFuture().get(10, TimeUnit.SECONDS);
            System.out.println("Desktop startup smoke check passed");
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static void setUpLookAndFeel() {
        if (!FlatLightLaf.setup()) {
            throw new IllegalStateException("FlatLaf could not initialize");
        }
        cm.sji.innova.desktop.catalog.CatalogStyle.installDefaults();
    }

    private static void deleteQuietly(Path folder) {
        try (var paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // A leftover temporary folder is harmless; it never contains real data.
        }
    }
}

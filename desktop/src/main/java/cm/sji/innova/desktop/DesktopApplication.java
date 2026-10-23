package cm.sji.innova.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Desktop entry point. Business logic will live in the core module. */
public final class DesktopApplication {
    private DesktopApplication() { }

    public static void main(String[] args) throws Exception {
        boolean smokeTest = args.length == 1 && "--smoke-test".equals(args[0]);
        if (!smokeTest && GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("A graphical desktop is required. Use --smoke-test for a headless startup check.");
        }
        SwingUtilities.invokeAndWait(() -> {
            if (!FlatLightLaf.setup()) {
                throw new IllegalStateException("FlatLaf could not initialize");
            }
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            content.add(new JLabel("SJI Innova — application foundation"), BorderLayout.CENTER);
            if (smokeTest) {
                content.setSize(640, 400);
                content.doLayout();
                System.out.println("Desktop startup smoke check passed");
                return;
            }
            JFrame frame = new JFrame("SJI Innova");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(content);
            frame.setSize(640, 400);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}

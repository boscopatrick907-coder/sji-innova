package cm.sji.innova.desktop.shell;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/**
 * The one place where the Products screen is plugged in.
 *
 * <p>Today it returns an honest placeholder. When Willy's {@code ProductPanel} is merged,
 * replace the body of {@link #create} with:
 * <pre>return new cm.sji.innova.desktop.products.ProductPanel(
 *         context.products(), context.shopId(), messages.bundle());</pre>
 * Nothing else in the shell needs to change.
 */
public final class ProductsSlot {
    private ProductsSlot() { }

    public static JComponent create(AppContext context, Messages messages) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JLabel title = new JLabel(messages.get("shell.products.placeholder.title"));
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 20f));
        JTextArea body = new JTextArea(messages.get("shell.products.placeholder.body"));
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        panel.add(title, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }
}

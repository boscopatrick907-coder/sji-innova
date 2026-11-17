package cm.sji.innova.desktop.settings;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import javax.swing.Icon;

/**
 * The Settings entry's icon in the sidebar: a small gear, 20px, drawn in the label's colour with the same
 * thin line as the other navigation icons. It lives here so the shared style file does not change.
 */
public final class SettingsIcon implements Icon {
    @Override public int getIconWidth() { return 20; }

    @Override public int getIconHeight() { return 20; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(component.getForeground());
        g.setStroke(new BasicStroke(1.3f));
        g.draw(new Ellipse2D.Double(4.5, 4.5, 11, 11));
        g.draw(new Ellipse2D.Double(7.75, 7.75, 4.5, 4.5));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int tooth = 0; tooth < 8; tooth++) {
            double angle = Math.PI / 4 * tooth;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            g.draw(new Line2D.Double(10 + 6.4 * cos, 10 + 6.4 * sin, 10 + 8.2 * cos, 10 + 8.2 * sin));
        }
        g.dispose();
    }
}

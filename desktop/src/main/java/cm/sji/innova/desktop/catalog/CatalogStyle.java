package cm.sji.innova.desktop.catalog;

import java.awt.*;
import javax.swing.*;

/** Shared desktop colors and small hand-drawn icons. No web assets or fonts needed. */
public final class CatalogStyle {
    public static final Color PAPER = new Color(0xF7F7F2), INK = new Color(0x22352B),
            GREEN = new Color(0x256549), MUTED = new Color(0x63715E), LINE = new Color(0xE2E5DC);
    private CatalogStyle() { }
    /** Applied after FlatLaf setup; uses the operating system's available sans-serif font. */
    public static void installDefaults() {
        UIManager.put("defaultFont", new Font(Font.SANS_SERIF,Font.PLAIN,14));
        UIManager.put("Button.arc",14);UIManager.put("Component.arc",12);
        UIManager.put("TextComponent.arc",12);UIManager.put("Component.focusWidth",1);
        UIManager.put("Component.borderColor",LINE);UIManager.put("Component.focusColor",GREEN);
        UIManager.put("Table.selectionBackground",new Color(0xE4F0E8));
        UIManager.put("Table.selectionForeground",INK);
        UIManager.put("TableHeader.background",new Color(0xF0F4EE));
        UIManager.put("TableHeader.foreground",MUTED);
    }
    /** Soft white surface for content; the existing page and sidebar colors stay intact. */
    public static JPanel surface(LayoutManager layout) {
        JPanel panel=new JPanel(layout){
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.WHITE);g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,18,18);
                g.setColor(LINE);g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,18,18);g.dispose();
            }
        };
        panel.setOpaque(false);panel.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
        return panel;
    }
    public static JButton arrowButton(String text,boolean primary,boolean back){
        JButton button=button(text,primary);button.setIcon(new ArrowIcon(back? -1:1));
        button.setIconTextGap(9);button.setHorizontalTextPosition(back?SwingConstants.RIGHT:SwingConstants.LEFT);
        return button;
    }
    public static final class ArrowIcon implements Icon {
        private final int direction;
        public ArrowIcon(int direction){this.direction=direction;}
        public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c.isEnabled()?c.getForeground():MUTED);g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(3,8,13,8);int tip=direction<0?3:13,tail=direction<0?7:9;
            g.drawLine(tip,8,tail,4);g.drawLine(tip,8,tail,12);g.dispose();
        }
    }
    public static void styleTable(JTable table){
        table.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));table.setForeground(INK);
        table.setSelectionBackground(new Color(0xE4F0E8));table.setSelectionForeground(INK);
        table.setIntercellSpacing(new Dimension(12,0));table.setShowGrid(false);
        table.getTableHeader().setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));
        table.getTableHeader().setBackground(new Color(0xF0F4EE));table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setPreferredSize(new Dimension(10,36));
    }
    public static JLabel label(String text, int size, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(INK); return label;
    }
    public static JPanel panel(LayoutManager layout) {
        JPanel p = new JPanel(layout); p.setOpaque(false); return p;
    }
    public static JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFont(new Font(Font.SANS_SERIF, primary ? Font.BOLD : Font.PLAIN, 13));
        if (primary) { button.setBackground(GREEN); button.setForeground(Color.WHITE); }
        button.putClientProperty("FlatLaf.style", "arc: 12");
        button.setMargin(new Insets(8,14,8,14));
        button.putClientProperty("JComponent.minimumHeight", 38);
        return button;
    }
    public static final class BoxIcon implements Icon {
        public int getIconWidth() { return 20; }
        public int getIconHeight() { return 20; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c.getForeground());g.setStroke(new BasicStroke(1.3f));
            g.drawPolygon(new int[]{2,10,18,10},new int[]{6,2,6,10},4);
            g.drawPolyline(new int[]{2,2,10,18,18},new int[]{6,15,19,15,6},5);
            g.drawLine(10,10,10,19);g.dispose();
        }
    }
    /** Distinct stock shelves and sales receipt; Products retains its box. */
    public static final class NavigationIcon implements Icon {
        private final int section;
        public NavigationIcon(int section) { this.section=section; }
        public int getIconWidth() { return 20; }
        public int getIconHeight() { return 20; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            if(section==0){new BoxIcon().paintIcon(c,graphics,x,y);return;}
            Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c.getForeground());g.setStroke(new BasicStroke(1.3f));
            if(section==1){
                g.drawLine(2,2,2,18);g.drawLine(18,2,18,18);
                g.drawLine(2,10,18,10);g.drawLine(2,18,18,18);
                g.drawRect(5,3,9,7);g.drawRect(5,12,5,6);g.drawRect(12,13,4,5);
            }else{
                g.drawRect(4,2,12,16);g.drawLine(7,6,13,6);
                g.drawLine(7,10,13,10);g.drawLine(7,14,11,14);
            }
            g.dispose();
        }
    }
    public static final class PhotoPlaceholder extends JPanel {
        private final String caption;
        public PhotoPlaceholder(String caption) { this.caption=caption; setPreferredSize(new Dimension(270,230)); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xEBEEE5));g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);
            int x=getWidth()/2-22,y=getHeight()/2-30;g.setColor(new Color(0x91A08B));
            g.setStroke(new BasicStroke(1.5f));g.drawRoundRect(x,y,44,36,4,4);g.drawOval(x+28,y+7,6,6);
            g.drawPolyline(new int[]{x+4,x+14,x+24,x+31,x+40},new int[]{y+29,y+18,y+28,y+22,y+29},5);
            g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));
            g.drawString(caption,(getWidth()-g.getFontMetrics().stringWidth(caption))/2,y+64);g.dispose();
        }
    }
}

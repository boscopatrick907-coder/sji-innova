package cm.sji.innova.desktop.catalog;

import java.awt.*;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import javax.swing.*;

/** Shared desktop colors and small hand-drawn icons. No web assets or fonts needed. */
public final class CatalogStyle {
    public static final Color PAPER = new Color(0xF5F5EF), INK = new Color(0x22392F),
            GREEN = new Color(0x28664E), MUTED = new Color(0x64715F), LINE = new Color(0xE1E6DA);
    private CatalogStyle() { }
    /** Applied after FlatLaf setup; uses the operating system's available sans-serif font. */
    public static void installDefaults() {
        UIManager.put("defaultFont", new Font(Font.SANS_SERIF,Font.PLAIN,14));
        UIManager.put("Button.disabledText",new Color(0x687268));UIManager.put("Button.disabledBackground",new Color(0xE3E7DF));
        UIManager.put("Button.arc",16);UIManager.put("Component.arc",14);
        UIManager.put("Component.minimumHeight",40);UIManager.put("ScrollBar.width",11);
        UIManager.put("TextComponent.arc",12);UIManager.put("Component.focusWidth",1);
        UIManager.put("Component.borderColor",LINE);UIManager.put("Component.focusColor",GREEN);
        UIManager.put("Table.selectionBackground",new Color(0xE4F0E8));
        UIManager.put("Table.selectionForeground",INK);
        UIManager.put("TableHeader.background",new Color(0xF0F4EE));
        UIManager.put("TableHeader.foreground",MUTED);
        UIManager.put("ProgressBar.foreground",GREEN);
        UIManager.put("Button.default.background",GREEN);
        UIManager.put("Button.default.foreground",Color.WHITE);
        UIManager.put("Component.linkColor",GREEN);
        UIManager.put("CheckBox.icon.selectedBackground",GREEN);
    }
    /** Soft white surface for content; the existing page and sidebar colors stay intact. */
    public static JPanel surface(LayoutManager layout) {
        JPanel panel=new JPanel(layout){
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0,0,0,12));g.fillRoundRect(1,3,getWidth()-2,getHeight()-4,24,24);
                g.setColor(Color.WHITE);g.fillRoundRect(0,0,getWidth()-1,getHeight()-4,24,24);
                g.setColor(LINE);g.drawRoundRect(0,0,getWidth()-1,getHeight()-4,24,24);g.dispose();
            }
        };
        panel.setOpaque(false);panel.setBorder(BorderFactory.createEmptyBorder(18,20,18,20));
        return panel;
    }
    /** Keep large monetary amounts fully visible in a narrow basket without changing their text. */
    public static JLabel amountLabel(){return amountLabel(26);}
    public static JLabel amountLabel(int size){
        Font base=new Font(Font.SANS_SERIF,Font.BOLD,size);
        JLabel label=new JLabel(){
            @Override public Dimension getPreferredSize(){return new Dimension(0,size+10);}
            @Override public Dimension getMinimumSize(){return new Dimension(0,size+10);}
            @Override protected void paintComponent(Graphics graphics){
                Insets insets=getInsets();int available=getWidth()-insets.left-insets.right;if(available<=0)return;
                Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font font=base;String text=getText()==null?"":getText();
                while(g.getFontMetrics(font).stringWidth(text)>available&&font.getSize2D()>8)font=font.deriveFont(font.getSize2D()-.5f);
                g.setFont(font);g.setColor(getForeground());FontMetrics metrics=g.getFontMetrics();
                int x=getHorizontalAlignment()==SwingConstants.LEFT?insets.left:getWidth()-insets.right-metrics.stringWidth(text);
                g.drawString(text,x,(getHeight()-metrics.getHeight())/2+metrics.getAscent());g.dispose();
            }
        };
        label.setFont(base);label.setForeground(INK);label.setHorizontalAlignment(SwingConstants.RIGHT);return label;
    }
    public static void searchField(JTextField field,String placeholder){
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,placeholder);
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,new FlatSearchIcon());
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON,true);
    }
    public enum BadgeTone { NORMAL, LOW, EMPTY, UNKNOWN }
    public static Component badgeCell(JTable table,String text,BadgeTone tone,boolean selected,int row,int column){return badgeCell(table,text,tone,selected,row,column,true);}
    public static Component badgeCell(JTable table,String text,BadgeTone tone,boolean selected,int row,int column,boolean right){
        Color background=switch(tone){case EMPTY->new Color(0xFDECEA);case LOW->new Color(0xFFF0D1);case NORMAL->new Color(0xE4F0E8);case UNKNOWN->new Color(0xEBEEE9);};
        Color foreground=switch(tone){case EMPTY->new Color(0xA52D26);case LOW->new Color(0x8A571E);case NORMAL->GREEN;case UNKNOWN->MUTED;};
        JPanel cell=new JPanel(new GridBagLayout());cell.setBackground(selected?table.getSelectionBackground():table.getBackground());
        JLabel badge=new JLabel(text){
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(background);g.fillRoundRect(0,0,getWidth(),getHeight(),getHeight(),getHeight());g.dispose();super.paintComponent(graphics);
            }
        };
        badge.setForeground(foreground);badge.setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));badge.setBorder(BorderFactory.createEmptyBorder(4,9,4,9));
        GridBagConstraints c=new GridBagConstraints();c.weightx=1;c.anchor=right?GridBagConstraints.EAST:GridBagConstraints.WEST;c.insets=new Insets(0,6,0,6);cell.add(badge,c);
        cell.setToolTipText(text);cell.setSize(table.getColumnModel().getColumn(column).getWidth(),table.getRowHeight(row));cell.doLayout();return cell;
    }
    public static JScrollPane tableScroll(JTable table) {return tableScroll(table,"");}
    public static JScrollPane tableScroll(JTable table,String emptyText) {
        styleTable(table);JScrollPane scroll=new JScrollPane();
        JViewport viewport=new JViewport(){
            @Override protected void paintChildren(Graphics graphics){
                super.paintChildren(graphics);if(table.getRowCount()!=0||emptyText.isBlank())return;
                Graphics2D g=(Graphics2D)graphics.create();g.setColor(MUTED);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int y=Math.max(38,getHeight()/2);new BoxIcon().paintIcon(this,g,getWidth()/2-10,y-30);
                g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));
                g.drawString(emptyText,Math.max(8,(getWidth()-g.getFontMetrics().stringWidth(emptyText))/2),y+10);g.dispose();
            }
        };
        viewport.setForeground(MUTED);viewport.getAccessibleContext().setAccessibleName(emptyText);
        viewport.setView(table);scroll.setViewport(viewport);scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setBorder(null);viewport.setBackground(Color.WHITE);
        Runnable size=()->{table.setPreferredScrollableViewportSize(new Dimension(400,table.getRowCount()==0?76:220));scroll.revalidate();scroll.repaint();};
        table.getModel().addTableModelListener(e->size.run());size.run();return scroll;
    }
    private static javax.swing.table.DefaultTableCellRenderer paddedRenderer(boolean numeric){
        return new javax.swing.table.DefaultTableCellRenderer(){
            @Override public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int row,int col){
                super.getTableCellRendererComponent(t,v,selected,focus,row,col);
                setHorizontalAlignment(numeric?SwingConstants.RIGHT:SwingConstants.LEFT);
                setBorder(BorderFactory.createEmptyBorder(0,10,0,10));setToolTipText(String.valueOf(v));return this;
            }
        };
    }
    public static javax.swing.table.TableCellRenderer twoLineRenderer(java.util.function.IntFunction<String> caption){
        return (table,value,selected,focus,row,col)->{
            String detail=caption.apply(table.convertRowIndexToModel(row));
            JPanel cell=new JPanel(new GridLayout(2,1,0,3));cell.setBackground(selected?table.getSelectionBackground():table.getBackground());
            cell.setBorder(BorderFactory.createEmptyBorder(7,10,7,10));
            JLabel name=label(String.valueOf(value),14,true),sub=label(detail,12,false);sub.setForeground(MUTED);
            cell.add(name);cell.add(sub);cell.setToolTipText(value+" · "+detail);
            cell.setSize(table.getColumnModel().getColumn(col).getWidth(),table.getRowHeight(row));cell.doLayout();return cell;
        };
    }
    public static void alignColumns(JTable table,int... numericColumns){
        java.util.Set<Integer> numbers=new java.util.HashSet<>();for(int n:numericColumns)numbers.add(n);
        for(int c=0;c<table.getColumnCount();c++){
            var column=table.getColumnModel().getColumn(c);column.setCellRenderer(paddedRenderer(numbers.contains(c)));
            final boolean right=numbers.contains(c);
            column.setHeaderRenderer(new javax.swing.table.DefaultTableCellRenderer(){
                @Override public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int row,int col){
                    super.getTableCellRendererComponent(t,v,false,false,row,col);
                    setHorizontalAlignment(right?SwingConstants.RIGHT:SwingConstants.LEFT);
                    setFont(t.getTableHeader().getFont());setBackground(new Color(0xEAF0E5));setForeground(GREEN);
                    setBorder(BorderFactory.createEmptyBorder(0,10,0,10));return this;
                }
            });
        }
    }
    /** Align wide forms to a readable measure without stretching their fields. */
    public static JPanel widthLimited(JComponent child,int maximum){
        JPanel panel=new JPanel(null){
            @Override public Dimension getPreferredSize(){Dimension p=child.getPreferredSize();return new Dimension(Math.min(maximum,p.width),p.height);}
            @Override public void doLayout(){child.setBounds(0,0,Math.min(maximum,getWidth()),getHeight());}
        };panel.setOpaque(false);panel.add(child);return panel;
    }
    public static JPanel alertStrip(JComponent message){return new FeedbackStrip(message,FeedbackStrip.Tone.WARNING,false);}
    public static FeedbackStrip feedback(JComponent message,FeedbackStrip.Tone tone){return new FeedbackStrip(message,tone,true);}
    public static javax.swing.table.TableCellRenderer statusBadge(String lowText){
        return (table,value,selected,focus,row,column)->{
            boolean low=lowText.equals(String.valueOf(value));
            boolean empty=low&&"0".equals(String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(row),2)));
            return badgeCell(table,String.valueOf(value),empty?BadgeTone.EMPTY:low?BadgeTone.LOW:BadgeTone.NORMAL,selected,row,column,false);
        };
    }
    public static JPanel tableCard(String title,JTable table) {return tableCard(title,table,"");}
    public static JPanel tableCard(String title,JTable table,String emptyText) {
        JPanel card=surface(new BorderLayout(0,12));
        card.add(label(title,18,true),BorderLayout.NORTH);card.add(tableScroll(table,emptyText),BorderLayout.CENTER);
        return card;
    }
    /** Keep content readable at small window heights; tables retain their own row scrolling. */
    public static JScrollPane scrollContent(JComponent content,int minimumHeight) {
        class View extends JPanel implements Scrollable {
            View(){super(new BorderLayout());setOpaque(false);add(content);}
            @Override public Dimension getPreferredSize(){return new Dimension(700,Math.max(minimumHeight,content.getPreferredSize().height));}
            public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
            public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 22;}
            public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(22,r.height-30);}
            public boolean getScrollableTracksViewportWidth(){return true;}
            public boolean getScrollableTracksViewportHeight(){return getParent()!=null&&getParent().getHeight()>=getPreferredSize().height;}
        }
        JScrollPane scroll=new JScrollPane(new View());scroll.setBorder(null);
        scroll.getViewport().setBackground(PAPER);scroll.getVerticalScrollBar().setUnitIncrement(22);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);return scroll;
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
            if(direction==2)g.rotate(Math.PI/2,8,8);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c.isEnabled()?c.getForeground():MUTED);g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(3,8,13,8);int tip=direction<0?3:13,tail=direction<0?7:9;
            g.drawLine(tip,8,tail,4);g.drawLine(tip,8,tail,12);g.dispose();
        }
    }
    public static void styleTable(JTable table){
        table.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));table.setForeground(INK);
        table.setSelectionBackground(new Color(0xE4F0E8));table.setSelectionForeground(INK);
        table.setIntercellSpacing(new Dimension(0,0));table.setShowGrid(false);
        table.setDefaultRenderer(Object.class,paddedRenderer(false));
        table.getTableHeader().setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer(){
            @Override public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int row,int col){
                super.getTableCellRendererComponent(t,v,false,false,row,col);setHorizontalAlignment(SwingConstants.LEFT);
                setFont(t.getTableHeader().getFont());setBackground(new Color(0xEAF0E5));setForeground(GREEN);
                setBorder(BorderFactory.createEmptyBorder(0,10,0,10));return this;
            }
        });
        table.getTableHeader().setFont(new Font(Font.SANS_SERIF,Font.BOLD,13));
        table.getTableHeader().setBackground(new Color(0xF0F4EE));table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setPreferredSize(new Dimension(10,36));
    }
    public static String localDateTime(java.time.Instant instant,java.util.Locale locale){
        return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withLocale(locale==null?java.util.Locale.ENGLISH:locale).withZone(java.time.ZoneId.of("Africa/Douala")).format(instant);
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
        button.setFont(new Font(Font.SANS_SERIF, primary ? Font.BOLD : Font.PLAIN, 14));
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
            if(section==-1){
                g.drawPolyline(new int[]{2,10,18},new int[]{9,2,9},3);
                g.drawRect(4,9,12,9);g.drawRect(8,12,4,6);
            }else if(section==1){
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

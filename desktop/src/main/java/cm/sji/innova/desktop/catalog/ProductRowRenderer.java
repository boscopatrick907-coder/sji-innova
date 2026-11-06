package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.product.Product;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;

/** A product-led list with real photos, rather than a spreadsheet of catalog fields. */
final class ProductRowRenderer implements TableCellRenderer {
    private final List<Product> products;
    private final CatalogThumbnails thumbnails;
    private final ResourceBundle messages;
    ProductRowRenderer(List<Product> products,CatalogThumbnails thumbnails,ResourceBundle messages){
        this.products=products;this.thumbnails=thumbnails;this.messages=messages;
    }
    public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){
        Product product=products.get(table.convertRowIndexToModel(row));
        JPanel cell=new JPanel(new BorderLayout(14,0));
        cell.setToolTipText(product.name()+" · "+Objects.toString(product.reference(),"")+" · "+value);
        cell.setBackground(selected?new Color(0xE4F0E8):Color.WHITE);
        cell.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));
        if(column==0){
            Icon photo=thumbnails.request(product.id()).getNow(null);
            JLabel image=new JLabel(photo==null?new InitialIcon(product.name()):photo);
            image.setPreferredSize(new Dimension(64,64));cell.add(image,BorderLayout.WEST);
            JPanel text=CatalogStyle.panel(new GridLayout(0,1,0,5));
            text.add(CatalogStyle.label(product.name(),16,true));
            JLabel reference=CatalogStyle.label(Objects.toString(product.reference(),"")+ (product.category()==null?"":" · "+product.category()),12,false);
            reference.setForeground(CatalogStyle.MUTED);text.add(reference);cell.add(text,BorderLayout.CENTER);
        }else{
            JPanel text=CatalogStyle.panel(new GridLayout(0,1,0,5));
            text.add(CatalogStyle.label(String.valueOf(value),column==3?16:15,column==3));
            JLabel caption=CatalogStyle.label(messages.getString(column==3?"catalog.price":"catalog.available"),12,false);
            caption.setForeground(CatalogStyle.MUTED);text.add(caption);cell.add(text,BorderLayout.CENTER);
        }
        if(focus)cell.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(CatalogStyle.GREEN),cell.getBorder()));
        cell.setSize(table.getColumnModel().getColumn(column).getWidth(),Math.max(1,table.getRowHeight(row)-table.getRowMargin()));
        layout(cell);return cell;
    }
    private static void layout(Container parent){parent.doLayout();for(Component child:parent.getComponents())if(child instanceof Container nested)layout(nested);}
    private record InitialIcon(String name) implements Icon {
        public int getIconWidth(){return 64;}public int getIconHeight(){return 64;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xEBEEE5));g.fillRoundRect(0,0,64,64,14,14);
            g.setColor(CatalogStyle.GREEN);g.setFont(new Font(Font.SERIF,Font.PLAIN,30));
            String initial=name.isEmpty()?"":name.substring(0,name.offsetByCodePoints(0,1)).toUpperCase(java.util.Locale.ROOT);
            g.drawString(initial,(64-g.getFontMetrics().stringWidth(initial))/2,43);g.dispose();
        }
    }
}

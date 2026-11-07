package cm.sji.innova.desktop.shell;

import java.awt.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Layout contracts for the supported smallest window and a wide desktop, with real startup. */
class PageLayoutTest {
    @TempDir Path directory;
    <T>T edt(Supplier<T> action)throws Exception{
        AtomicReference<T> value=new AtomicReference<>();SwingUtilities.invokeAndWait(()->value.set(action.get()));return value.get();
    }
    static <T extends Component>T find(Component root,Class<T> type,String name){
        if(type.isInstance(root)&&name.equals(root.getName()))return type.cast(root);
        if(root instanceof Container parent)for(Component child:parent.getComponents()){T found=find(child,type,name);if(found!=null)return found;}
        return null;
    }
    static void layout(Component c){
        if(c instanceof JScrollPane scroll&&scroll.getViewport().getView() instanceof JTable table)scroll.setColumnHeaderView(table.getTableHeader());
        if(c instanceof Container parent){parent.doLayout();for(Component child:parent.getComponents())layout(child);}
    }
    static Rectangle bounds(Component c,Component root){return SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);}
    ShellPanel open(AppContext context)throws Exception{
        ShellPanel shell=edt(()->{var s=new ShellPanel(Messages.load(Locale.FRENCH),context.location(),()->context,()->{});s.showMain(context);return s;});
        shell.productsReady().toCompletableFuture().get(10,TimeUnit.SECONDS);return shell;
    }
    @Test void salesKeepsPickerBesideCartAndPaymentInsideCartAtBothSizes()throws Exception{
        AppContext app=AppContextLoader.load(new LocalAppData(directory.resolve("shop")));
        var p=app.products().create(app.shopId(),"Rice",1000);
        app.stock().receive(UUID.randomUUID(),app.shopId(),p.id(),app.deviceId(),10000,"Delivery");
        ShellPanel shell=open(app);edt(()->{shell.selectScreen("sales");return null;});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(edt(()->find(shell,JTable.class,"salesResults").getRowCount())==0){assertTrue(System.nanoTime()<deadline);Thread.sleep(10);}
        edt(()->{
            JTable picker=find(shell,JTable.class,"salesResults"),cart=find(shell,JTable.class,"salesBasket");
            JButton pay=find(shell,JButton.class,"salesCheckout");
            for(int width:new int[]{960,1600}){
                shell.setSize(width,720);for(int i=0;i<5;i++)layout(shell);
                Rectangle left=bounds(picker,shell),right=bounds(cart,shell),payment=bounds(pay,shell);
                assertTrue(right.x>=left.x+left.width,"Cart must be beside products");
                assertTrue(payment.x>=right.x-2);assertTrue(payment.x+payment.width<=right.x+right.width+2);
                assertTrue(payment.y+payment.height<=shell.getHeight());
                assertEquals(0,picker.getIntercellSpacing().width);assertEquals(0,picker.getIntercellSpacing().height);
            }
            shell.setSize(960,600);for(int i=0;i<5;i++)layout(shell);
            assertTrue(bounds(pay,shell).y+pay.getHeight()<=600,"Payment must stay visible in the smallest window");
            picker.setRowSelectionInterval(0,0);
            Component text=picker.prepareRenderer(picker.getCellRenderer(0,0),0,0);
            Component number=picker.prepareRenderer(picker.getCellRenderer(0,3),0,3);
            assertEquals(text.getBackground(),number.getBackground(),"Selection must cover the full row");
            assertEquals(picker.getSelectionBackground(),text.getBackground());
            Component badge=((Container)number).getComponent(0);
            assertTrue(number.getWidth()-badge.getX()-badge.getWidth()<=10,"Stock badge must align with the numeric header");
            assertEquals(SwingConstants.RIGHT,((JLabel)picker.getColumnModel().getColumn(3).getHeaderRenderer().getTableCellRendererComponent(picker,"Stock",false,false,-1,3)).getHorizontalAlignment());
            find(shell,JButton.class,"salesAdd").doClick();for(int i=0;i<5;i++)layout(shell);
            assertEquals(1,cart.getRowCount());assertTrue(pay.isEnabled());
            find(shell,JButton.class,"salesCheckout").doClick();for(int i=0;i<5;i++)layout(shell);
            JButton confirm=find(shell,JButton.class,"salesConfirm");assertTrue(confirm.isVisible());
            assertTrue(bounds(confirm,shell).y+confirm.getHeight()<=600,"Cash confirmation must fit too");
            return null;
        });
    }
    @Test void productAndReceiptFieldsStayReadableOnWideScreens()throws Exception{
        AppContext app=AppContextLoader.load(new LocalAppData(directory.resolve("shop")));
        ShellPanel shell=open(app);
        edt(()->{
            shell.setSize(1600,1000);find(shell,JButton.class,"addCatalogProduct").doClick();for(int i=0;i<5;i++)layout(shell);
            JTextField name=find(shell,JTextField.class,"variantName");assertTrue(name.getWidth()>200);assertTrue(name.getWidth()<=720);assertTrue(name.getParent().getParent().getWidth()<=720);
            shell.selectScreen("stock");for(int i=0;i<5;i++)layout(shell);
            JTextField quantity=find(shell,JTextField.class,"stockQuantity");assertTrue(quantity.getWidth()>100);assertTrue(quantity.getWidth()<=720);assertTrue(quantity.getParent().getWidth()<=720);
            return null;
        });
        // Keep temporary files alive until the stock worker finishes.
        var stock=edt(()->findStock(shell));stock.initialLoadCompletion().toCompletableFuture().get(10,TimeUnit.SECONDS);
    }
    static cm.sji.innova.desktop.stock.StockPanel findStock(Component root){
        if(root instanceof cm.sji.innova.desktop.stock.StockPanel p)return p;
        if(root instanceof Container c)for(var child:c.getComponents()){var p=findStock(child);if(p!=null)return p;}return null;
    }
}

package cm.sji.innova.desktop.products;

import cm.sji.innova.core.product.*;
import cm.sji.innova.desktop.catalog.*;
import cm.sji.innova.desktop.shell.*;
import java.awt.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Saves through the actual visible catalog form and reopens the same shop. */
class ProductsShellIntegrationTest {
    @TempDir Path directory;
    static <T extends Component> T find(Component root,Class<T> type,String name) {
        if(type.isInstance(root)&&(name==null||name.equals(root.getName())))return type.cast(root);
        if(root instanceof Container c)for(var child:c.getComponents()){T found=find(child,type,name);if(found!=null)return found;}
        return null;
    }
    static JButton button(Component root,String text) {
        if(root instanceof JButton b&&text.equals(b.getText()))return b;
        if(root instanceof Container c)for(var child:c.getComponents()){var found=button(child,text);if(found!=null)return found;}
        return null;
    }
    record Opened(ShellPanel shell,CatalogPanel catalog){}
    Opened open(AppContext context)throws Exception {
        AtomicReference<Opened> ref=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{
            var shell=new ShellPanel(Messages.load(Locale.ENGLISH),context.location(),()->context,()->{});
            shell.showMain(context);var catalog=find(shell,CatalogPanel.class,null);assertNotNull(catalog);ref.set(new Opened(shell,catalog));
        });return ref.get();
    }
    @Test void savesThroughTheActualShellAndShowsTheSameProductAfterRestart()throws Exception{
        var location=new LocalAppData(directory.resolve("shop"));var first=AppContextLoader.load(location);var window=open(first);
        window.shell.productsReady().toCompletableFuture().get(5,TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()->{
            button(window.catalog,"Add a product").doClick();
            find(window.catalog,JTextField.class,"variantName").setText("Blue T-shirt");
            find(window.catalog,JTextField.class,"sellingPrice").setText("3000");
            find(window.catalog,JComboBox.class,"productFamily").getEditor().setItem("T-shirts");
            find(window.catalog,JButton.class,"saveVariant").doClick();
        });
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){
            var loaded=new AtomicReference<List<Product>>();SwingUtilities.invokeAndWait(()->loaded.set(window.catalog.loadedProducts()));
            if(loaded.get().size()==1)break;Thread.sleep(20);
        }
        Product original=first.products().list(first.shopId()).getFirst();assertEquals(3000,original.unitPrice());assertNotNull(original.familyId());assertEquals("P-0001",original.reference());assertNull(original.costPrice());
        AtomicReference<CompletionStage<Void>> firstPhoto=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{var photo=find(window.catalog,ProductPhotoPanel.class,null);if(photo!=null)firstPhoto.set(photo.initialLoadCompletion());});
        if(firstPhoto.get()!=null)firstPhoto.get().toCompletableFuture().get(5,TimeUnit.SECONDS);
        var reopened=AppContextLoader.load(location);var second=open(reopened);second.shell.productsReady().toCompletableFuture().get(5,TimeUnit.SECONDS);
        AtomicReference<CompletionStage<Void>> photoReady=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            assertEquals(List.of(original),second.catalog.loadedProducts());
            second.catalog.showFamily(original.familyId());assertEquals(1,second.catalog.variantTable().getRowCount());
            second.catalog.showProduct(original.id()); // Existing stock is displayed from the real service.
            assertFalse(find(second.catalog,JToggleButton.class,null).isSelected());
            photoReady.set(find(second.catalog,ProductPhotoPanel.class,null).initialLoadCompletion());
        });
        photoReady.get().toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(0,reopened.stock().current(reopened.shopId(),original.id()).quantityMilli());
    }
    @Test void startupReadinessReportsProductLoadingFailureWithoutAModalDialog()throws Exception{
        var failing=new ProductRepository(){public void insert(Product p){throw new UnsupportedOperationException();}public List<Product> findByShop(UUID shop)throws SQLException{throw new SQLException("Simulated load failure");}};
        var context=TestAppContexts.withProducts(new LocalAppData(directory),new ProductService(failing));var window=open(context);
        ExecutionException failure=assertThrows(ExecutionException.class,()->window.shell.productsReady().toCompletableFuture().get(5,TimeUnit.SECONDS));
        assertInstanceOf(SQLException.class,failure.getCause());
        SwingUtilities.invokeAndWait(()->assertNotNull(button(window.shell,"Refresh")));
    }
}

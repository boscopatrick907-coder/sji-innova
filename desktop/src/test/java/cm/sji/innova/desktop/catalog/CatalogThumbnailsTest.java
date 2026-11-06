package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.persistence.*;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogThumbnailsTest {
    @TempDir Path directory;
    <T>T edt(Supplier<T> action)throws Exception{
        var result=new AtomicReference<T>();SwingUtilities.invokeAndWait(()->result.set(action.get()));return result.get();
    }
    Path image(String name,Color color)throws Exception{
        Path file=directory.resolve(name);BufferedImage image=new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.setColor(color);g.fillRect(0,0,20,20);g.dispose();ImageIO.write(image,"png",file.toFile());return file;
    }
    @Test void attachedPhotoLoadsAndReplacementAppearsAfterCacheInvalidation()throws Exception{
        UUID shop=UUID.randomUUID();var db=new SqliteDatabase(directory.resolve("shop.db"));db.initialize();
        var products=new ProductService(new SqliteProductRepository(db));var product=products.create(shop,"Shirt",1000);
        var photos=new ProductPhotoService(new SqliteProductPhotoRepository(db),directory);
        photos.setPhoto(shop,product.id(),image("red.png",Color.RED));
        AtomicBoolean callbackOnEdt=new AtomicBoolean();
        var thumbnails=edt(()->new CatalogThumbnails(photos,shop,()->callbackOnEdt.set(SwingUtilities.isEventDispatchThread())));
        Icon first=edt(()->{var request=thumbnails.request(product.id());assertFalse(request.isDone());return request;}).get(10,TimeUnit.SECONDS);
        assertNotNull(first);assertEquals(64,first.getIconWidth());
        edt(()->null);assertTrue(callbackOnEdt.get());
        photos.setPhoto(shop,product.id(),image("blue.png",Color.BLUE));
        assertSame(first,edt(()->thumbnails.request(product.id())).get());
        edt(()->{thumbnails.clear();return null;});
        Icon changed=edt(()->thumbnails.request(product.id())).get(10,TimeUnit.SECONDS);
        BufferedImage rendered=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=rendered.createGraphics();changed.paintIcon(new JLabel(),g,0,0);g.dispose();
        Color pixel=new Color(rendered.getRGB(32,32));assertTrue(pixel.getBlue()>200);assertTrue(pixel.getRed()<50);
        java.nio.file.Files.delete(photos.resolvePath(photos.getPhotoMetadata(shop,product.id()).orElseThrow().thumbnailPath()));
        edt(()->{thumbnails.clear();return null;});
        assertNull(edt(()->thumbnails.request(product.id())).get(10,TimeUnit.SECONDS));
        assertEquals(1,products.list(shop).size());
        photos.removePhoto(shop,product.id());edt(()->{thumbnails.clear();return null;});
        assertNull(edt(()->thumbnails.request(product.id())).get(10,TimeUnit.SECONDS));
    }
}

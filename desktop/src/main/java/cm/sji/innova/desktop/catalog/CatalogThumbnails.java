package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.product.photo.PhotoValidator;
import java.nio.file.Files;
import cm.sji.innova.core.product.photo.ProductPhotoService;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Lazy, bounded photo loading. Rendering never touches disk or SQLite. Access on the EDT. */
final class CatalogThumbnails {
    private final ProductPhotoService photos;
    private final UUID shop;
    private final Runnable repaint;
    private final Map<UUID,CompletableFuture<Icon>> cache=new LinkedHashMap<>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<UUID,CompletableFuture<Icon>> entry){return size()>128;}
    };
    private int active;
    CatalogThumbnails(ProductPhotoService photos,UUID shop,Runnable repaint){this.photos=photos;this.shop=shop;this.repaint=repaint;}
    void clear(){cache.clear();}
    CompletableFuture<Icon> request(UUID product){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Request thumbnails on the EDT");
        if(photos==null)return CompletableFuture.completedFuture(null);
        var existing=cache.get(product);if(existing!=null)return existing;
        if(active>=4)return CompletableFuture.completedFuture(null);
        var future=new CompletableFuture<Icon>();cache.put(product,future);active++;
        new SwingWorker<Icon,Void>(){
            protected Icon doInBackground()throws Exception{
                var metadata=photos.getPhotoMetadata(shop,product);if(metadata.isEmpty())return null;
                var path=photos.resolvePath(metadata.get().thumbnailPath());
                if(Files.size(path)>PhotoValidator.MAX_FILE_SIZE_BYTES)return null;
                BufferedImage source;
                try(var input=ImageIO.createImageInputStream(path.toFile())){
                    if(input==null)return null;var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())return null;
                    var reader=readers.next();
                    try{
                        reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                        if(width<=0||height<=0||width>PhotoValidator.THUMBNAIL_MAX_DIMENSION||height>PhotoValidator.THUMBNAIL_MAX_DIMENSION)return null;
                        source=reader.read(0);
                    }finally{reader.dispose();}
                }
                if(source==null)return null;
                BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
                Graphics2D g=image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale=Math.min(64d/source.getWidth(),64d/source.getHeight());
                int w=Math.max(1,(int)(source.getWidth()*scale)),h=Math.max(1,(int)(source.getHeight()*scale));
                g.drawImage(source,(64-w)/2,(64-h)/2,w,h,null);g.dispose();return new ImageIcon(image);
            }
            protected void done(){
                Icon icon=null;try{icon=get();}catch(Exception ignored){/* A missing image must not hide a product. */}
                active--;future.complete(icon);repaint.run();
            }
        }.execute();return future;
    }
}

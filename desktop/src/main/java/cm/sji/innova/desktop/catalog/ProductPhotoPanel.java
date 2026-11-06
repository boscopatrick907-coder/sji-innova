package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.product.photo.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real managed photos: reads, scaling and writes stay off the event thread. */
public final class ProductPhotoPanel extends JPanel {
    private final ProductPhotoService service;
    private final UUID shop,product;
    private final ResourceBundle messages;
    private final Consumer<Boolean> busy;
    private final JPanel image=CatalogStyle.panel(new BorderLayout());
    private final JTextArea status=new JTextArea(2,15);
    private final JButton choose,remove;
    private final CompletableFuture<Void> initial=new CompletableFuture<>();
    private boolean running;
    public ProductPhotoPanel(ProductPhotoService service,UUID shop,UUID product,ResourceBundle messages,Consumer<Boolean> busy){
        super(new BorderLayout(0,10));this.service=service;this.shop=shop;this.product=product;this.messages=messages;this.busy=busy;setOpaque(false);status.setEditable(false);status.setLineWrap(true);status.setWrapStyleWord(true);status.setOpaque(false);
        choose=CatalogStyle.button(t("photo.choose"),true);remove=CatalogStyle.button(t("photo.remove"),false);choose.setName("choosePhoto");remove.setName("removePhoto");
        JPanel heading=CatalogStyle.panel(new BorderLayout(0,6));heading.add(CatalogStyle.label(t("photo.heading"),18,true),BorderLayout.NORTH);JLabel hint=CatalogStyle.label(t("photo.formats"),12,false);hint.setForeground(CatalogStyle.MUTED);heading.add(hint,BorderLayout.CENTER);add(heading,BorderLayout.NORTH);
        image.add(new CatalogStyle.PhotoPlaceholder(t("catalog.noPhoto")));add(image,BorderLayout.CENTER);
        JPanel bottom=CatalogStyle.panel(new BorderLayout(0,8));JPanel buttons=CatalogStyle.panel(new GridLayout(0,1,0,8));buttons.add(choose);buttons.add(remove);bottom.add(buttons,BorderLayout.NORTH);bottom.add(status,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        choose.addActionListener(e->{JFileChooser picker=new JFileChooser();picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JPEG / PNG","jpg","jpeg","png"));if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)setPhoto(picker.getSelectedFile().toPath());});
        remove.addActionListener(e->{if(JOptionPane.showConfirmDialog(this,t("photo.confirmRemove"),t("photo.remove"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)removePhoto();});
        load();
    }
    private String t(String key){return messages.getString(key);}
    public CompletionStage<Void> initialLoadCompletion(){return initial;}
    private record Loaded(PhotoLoadResult result,BufferedImage image){}
    private Loaded read()throws Exception{
        var result=service.loadPhoto(shop,product);
        if(result instanceof PhotoLoadResult.Loaded loaded){BufferedImage photo=ImageIO.read(loaded.imageFile().toFile());if(photo==null)throw new java.io.IOException("Photo cannot be decoded");return new Loaded(result,photo);}
        return new Loaded(result,null);
    }
    private void display(Loaded loaded){
        image.removeAll();if(loaded.image!=null){image.add(new Picture(loaded.image,t("photo.image")));choose.setText(t("photo.replace"));remove.setEnabled(true);status.setText(" ");}
        else{image.add(new CatalogStyle.PhotoPlaceholder(t("catalog.noPhoto")));remove.setEnabled(!(loaded.result instanceof PhotoLoadResult.NotAttached));choose.setText(t("photo.choose"));status.setText(loaded.result instanceof PhotoLoadResult.NotAttached?" ":t("photo.damaged"));}
        image.revalidate();image.repaint();
    }
    private void load(){
        running=true;choose.setEnabled(false);remove.setEnabled(false);
        new SwingWorker<Loaded,Void>(){
            protected Loaded doInBackground()throws Exception{return read();}
            protected void done(){try{display(get());initial.complete(null);}catch(Exception ex){status.setText(t("photo.loadError"));initial.complete(null);}finally{running=false;choose.setEnabled(true);}}
        }.execute();
    }
    public CompletionStage<Void> setPhoto(Path source){return mutate(()->service.setPhoto(shop,product,source));}
    public CompletionStage<Void> removePhoto(){return mutate(()->service.removePhoto(shop,product));}
    private CompletionStage<Void> mutate(Callable<?> action){
        if(running)return CompletableFuture.failedFuture(new IllegalStateException("Photo operation in progress"));
        CompletableFuture<Void> result=new CompletableFuture<>();running=true;busy.accept(true);choose.setEnabled(false);remove.setEnabled(false);status.setText(t("photo.saving"));
        new SwingWorker<Loaded,Void>(){
            protected Loaded doInBackground()throws Exception{action.call();return read();}
            protected void done(){try{display(get());result.complete(null);}catch(Exception ex){status.setText(t("photo.saveError"));remove.setEnabled(true);result.completeExceptionally(ex);}finally{running=false;choose.setEnabled(true);busy.accept(false);}}
        }.execute();return result;
    }
    private static final class Picture extends JComponent {
        private final BufferedImage photo;
        Picture(BufferedImage photo,String description){this.photo=photo;setPreferredSize(new Dimension(300,250));setToolTipText(description);}
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setColor(new Color(0xEBEEE5));g.fillRect(0,0,getWidth(),getHeight());double scale=Math.min((double)getWidth()/photo.getWidth(),(double)getHeight()/photo.getHeight());int w=(int)(photo.getWidth()*scale),h=(int)(photo.getHeight()*scale);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(photo,(getWidth()-w)/2,(getHeight()-h)/2,w,h,null);g.dispose();}
    }
}

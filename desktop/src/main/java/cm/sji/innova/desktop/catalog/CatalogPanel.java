package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.product.*;
import cm.sji.innova.core.stock.*;
import java.awt.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;

/** Family -> variants -> details, backed by one background-loaded shop snapshot. */
public final class CatalogPanel extends JPanel {
    private final ProductService products;
    private final ProductFamilyService families;
    private final StockService stock;
    private final UUID shop;
    private final ResourceBundle messages;
    private final cm.sji.innova.core.product.importer.ProductImportService imports;
    private final cm.sji.innova.core.product.photo.ProductPhotoService photos;
    private final java.nio.file.Path dataDirectory;
    private final CompletableFuture<Void> initial = new CompletableFuture<>();
    private final JPanel body = CatalogStyle.panel(new BorderLayout(0,14));
    private final JPanel breadcrumbs = CatalogStyle.panel(new FlowLayout(FlowLayout.LEFT,6,0));
    private final JTextField search = new JTextField();
    private final JComboBox<String> category = new JComboBox<>();
    private final JLabel error = new JLabel(" ");
    private final FeedbackStrip errorFeedback;
    private final JButton refresh;
    private List<Product> items = List.of();
    private List<ProductFamily> groups = List.of();
    private Map<UUID,StockLevel> levels = Map.of();
    private boolean stockFailed;
    private UUID family, selected;
    private boolean variants, saving;
    private long generation;
    private long historySequence;
    private String filter = "";
    private int selectedRow = -1;
    private JTable table;
    private final CatalogThumbnails thumbnails;
    private final NumberFormat numbers;
    public CatalogPanel(ProductService products, ProductFamilyService families, StockService stock,
                        UUID shop, ResourceBundle messages) {
        this(products,families,stock,shop,messages,null,null,null);
    }
    public CatalogPanel(ProductService products,ProductFamilyService families,StockService stock,UUID shop,
                        ResourceBundle messages,cm.sji.innova.core.product.importer.ProductImportService imports,
                        cm.sji.innova.core.product.photo.ProductPhotoService photos,java.nio.file.Path dataDirectory) {
        super(new BorderLayout(0,18));
        this.imports=imports;this.photos=photos;this.dataDirectory=dataDirectory;
        thumbnails=new CatalogThumbnails(photos,shop,()->{if(table!=null)table.repaint();});
        this.products=products;this.families=families;this.stock=stock;this.shop=shop;this.messages=messages;
        numbers=NumberFormat.getNumberInstance(messages.getLocale()!=null && messages.getLocale().getLanguage().equals("fr")?Locale.FRANCE:Locale.ENGLISH);
        setBackground(CatalogStyle.PAPER);setBorder(BorderFactory.createEmptyBorder(24,30,24,30));
        JPanel top=CatalogStyle.panel(new BorderLayout());top.add(breadcrumbs,BorderLayout.CENTER);
        refresh=CatalogStyle.button(t("catalog.refresh"),false);refresh.addActionListener(e->reload());top.add(refresh,BorderLayout.EAST);
        add(top,BorderLayout.NORTH);add(body,BorderLayout.CENTER);error.setForeground(new Color(0xA43B2D));
        errorFeedback=CatalogStyle.feedback(error,FeedbackStrip.Tone.ERROR);add(errorFeedback,BorderLayout.SOUTH);
        search.getAccessibleContext().setAccessibleName(t("catalog.search"));
        CatalogStyle.searchField(search,t("catalog.search"));
        search.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){filter=search.getText();renderResults();}
            public void removeUpdate(DocumentEvent e){filter=search.getText();renderResults();}
            public void changedUpdate(DocumentEvent e){filter=search.getText();renderResults();}
        });
        category.getAccessibleContext().setAccessibleName(t("catalog.category"));
        category.addActionListener(e->renderResults());reload();
    }
    String t(String key){return messages.getString(key);}
    public CompletionStage<Void> initialLoadCompletion(){return initial;}
    public CompletionStage<Void> reload(){
        thumbnails.clear();
        ++historySequence;long request=++generation;CompletableFuture<Void> ready=new CompletableFuture<>();
        refresh.setEnabled(false);body.removeAll();body.add(new JLabel(t("catalog.loading")));body.revalidate();body.repaint();
        new SwingWorker<Snapshot,Void>(){
            protected Snapshot doInBackground() throws Exception {
                var p=products.list(shop);var f=families.list(shop);
                Map<UUID,StockLevel> l=new HashMap<>();boolean unavailable=false;
                try{for(var level:stock.list(shop))l.put(level.productId(),level);}catch(Exception ex){unavailable=true;}
                return new Snapshot(p,f,l,unavailable);
            }
            protected void done(){
                try{
                    Snapshot s=get();if(request!=generation)return;
                    items=s.items;groups=s.groups;levels=s.levels;stockFailed=s.stockFailed;
                    String previous=(String)category.getSelectedItem();category.removeAllItems();category.addItem(t("catalog.allCategories"));
                    TreeSet<String> values=new TreeSet<>();
                    for(var p:items)if(p.category()!=null)values.add(p.category());
                    for(var f:groups)if(f.category()!=null)values.add(f.category());
                    values.forEach(category::addItem);if(previous!=null&&values.contains(previous))category.setSelectedItem(previous);
                    error.setText(stockFailed?t("catalog.stockError"):" ");render();
                    initial.complete(null);ready.complete(null);
                }catch(Exception ex){
                    Throwable cause=ex instanceof ExecutionException?ex.getCause():ex;
                    if(request==generation){body.removeAll();body.add(new JLabel(t("catalog.loadError")));error.setText(t("catalog.retryHint"));body.revalidate();body.repaint();}
                    initial.completeExceptionally(cause);ready.completeExceptionally(cause);
                }finally{if(request==generation)refresh.setEnabled(true);if(!ready.isDone())ready.complete(null);}
            }
        }.execute();return ready;
    }
    private record Snapshot(List<Product> items,List<ProductFamily> groups,Map<UUID,StockLevel> levels,boolean stockFailed){}
    private static String fold(String text){return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);}
    private String groupName(UUID id){return groups.stream().filter(f->f.id().equals(id)).map(ProductFamily::name).findFirst().orElse(t("catalog.ungrouped"));}
    private String unit(String code){return code.equals("kg")?"kg":t("unit."+code).toLowerCase(Locale.ROOT);}
    private String quantityUnit(String code,long milli){String label=unit(code);return !code.equals("kg")&&Math.abs(milli)!=1000?label+"s":label;}
    private String balance(Product p){var l=levels.get(p.id());return stockFailed||l==null?t("catalog.unavailable"):numbers.format(BigDecimal.valueOf(l.quantityMilli(),3))+" "+quantityUnit(p.unit(),l.quantityMilli());}
    private void crumb(String text,Runnable action){
        if(breadcrumbs.getComponentCount()>0)breadcrumbs.add(CatalogStyle.label("›",16,false));
        JButton b=new JButton(text);b.setBorderPainted(false);b.setContentAreaFilled(false);b.setForeground(CatalogStyle.MUTED);b.addActionListener(e->action.run());breadcrumbs.add(b);
    }
    private void render(){
        JPanel navigationTop=(JPanel)((BorderLayout)getLayout()).getLayoutComponent(BorderLayout.NORTH);
        navigationTop.setVisible(true);navigationTop.add(refresh,BorderLayout.EAST);
        error.setVisible(true);errorFeedback.setVisible(!error.getText().isBlank());
        body.removeAll();breadcrumbs.removeAll();
        if(selected!=null||variants){
            JButton back=CatalogStyle.arrowButton(t("catalog.back"),false,true);back.setName("catalogBack");
            back.addActionListener(e->{if(selected!=null&&family!=null)showFamily(family);else showFamilies();});
            breadcrumbs.add(back);
        }
        crumb(t("catalog.products"),this::showFamilies);
        if(variants||family!=null)crumb(groupName(family),()->showFamily(family));
        Product p=items.stream().filter(i->i.id().equals(selected)).findFirst().orElse(null);
        if(p!=null){crumb(p.name(),()->{});renderDetails(p);}else renderBrowse();
        revalidate();repaint();
    }
    public void showFamilies(){if(saving)return;++historySequence;refresh.setEnabled(true);selected=null;family=null;variants=false;render();}
    public void showFamily(UUID id){if(saving)return;++historySequence;refresh.setEnabled(true);selected=null;family=id;variants=true;render();}
    public void showProduct(UUID id){if(saving)return;++historySequence;refresh.setEnabled(true);Product p=items.stream().filter(i->i.id().equals(id)).findFirst().orElseThrow();selected=id;family=p.familyId();render();}
    private void renderBrowse(){
        JPanel north=CatalogStyle.panel(new BorderLayout(0,14));JPanel title=CatalogStyle.panel(new BorderLayout());
        title.add(CatalogStyle.label(variants?groupName(family):t("catalog.products"),28,true),BorderLayout.WEST);
        JButton add=CatalogStyle.button(t(variants?"catalog.addVariant":"catalog.addProduct"),true);add.setName("addCatalogProduct");add.addActionListener(e->showForm(variants?family:null));JPanel actions=CatalogStyle.panel(new FlowLayout(FlowLayout.RIGHT,8,0));
        if(!variants){((BorderLayout)getLayout()).getLayoutComponent(BorderLayout.NORTH).setVisible(false);actions.add(refresh);}
        if(imports!=null&&!variants){JButton csv=CatalogStyle.button(t("csv.title"),false);csv.setName("openCsvImport");csv.addActionListener(e->showImport());actions.add(csv);}
        actions.add(add);title.add(actions,BorderLayout.EAST);north.add(title,BorderLayout.NORTH);
        if(!variants){JPanel tools=CatalogStyle.panel(new BorderLayout(12,0));search.setPreferredSize(new Dimension(200,38));tools.add(search,BorderLayout.CENTER);tools.add(category,BorderLayout.EAST);north.add(tools,BorderLayout.SOUTH);}
        body.add(north,BorderLayout.NORTH);renderResults();
    }
    private void renderResults(){
        if(!SwingUtilities.isEventDispatchThread()||selected!=null||saving)return;
        Component old=((BorderLayout)body.getLayout()).getLayoutComponent(BorderLayout.CENTER);if(old!=null)body.remove(old);
        if(!variants&&filter.isBlank()&&category.getSelectedIndex()==0){
            JPanel overview=CatalogStyle.panel(new BorderLayout(0,12));
            if(!groups.isEmpty()){
                JPanel cards=CatalogStyle.panel(new GridLayout(0,2,12,12));
                for(var f:groups)cards.add(familyCard(f.id(),f.name(),f.category()));
                JPanel familiesSection=CatalogStyle.panel(new BorderLayout(0,10));
                familiesSection.add(CatalogStyle.label(t("catalog.familiesHeading"),18,true),BorderLayout.NORTH);
                familiesSection.add(cards,BorderLayout.CENTER);
                JScrollPane familyScroll=new JScrollPane(familiesSection);familyScroll.setBorder(null);familyScroll.getViewport().setBackground(CatalogStyle.PAPER);
                familyScroll.setPreferredSize(new Dimension(200,Math.min(300,familiesSection.getPreferredSize().height+6)));
                overview.add(familyScroll,BorderLayout.NORTH);
            }
            List<Product> standalone=items.stream().filter(p->p.familyId()==null).toList();
            JPanel direct=CatalogStyle.panel(new BorderLayout(0,10));
            if(!groups.isEmpty())direct.add(CatalogStyle.label(t("catalog.standaloneHeading"),18,true),BorderLayout.NORTH);
            direct.add(productList(standalone),BorderLayout.CENTER);
            overview.add(direct,BorderLayout.CENTER);body.add(overview,BorderLayout.CENTER);
        }else{
            List<Product> visible=items.stream().filter(p->variants?Objects.equals(p.familyId(),family):matches(p)).toList();
            body.add(productList(visible),BorderLayout.CENTER);
        }
        body.revalidate();body.repaint();
    }
    private JComponent productList(List<Product> visible){
        DefaultTableModel model=new DefaultTableModel(new String[]{t("catalog.variant"),t("catalog.reference"),t("catalog.available"),t("catalog.price")},0){public boolean isCellEditable(int row,int col){return false;}};
        for(var p:visible)model.addRow(new Object[]{p.name(),p.reference(),balance(p),numbers.format(p.unitPrice())+" FCFA / "+unit(p.unit())});
        table=new JTable(model);table.setName("catalogVariants");table.setRowHeight(100);CatalogStyle.styleTable(table);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setShowVerticalLines(false);table.setGridColor(CatalogStyle.LINE);table.setFillsViewportHeight(true);
        table.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,null);
        table.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,null);
        table.setTableHeader(null);table.setBackground(CatalogStyle.PAPER);table.setIntercellSpacing(new Dimension(0,10));
        table.setDefaultRenderer(Object.class,new ProductRowRenderer(visible,thumbnails,messages));
        table.getColumnModel().getColumn(0).setPreferredWidth(370);
        table.getColumnModel().getColumn(1).setMinWidth(0);table.getColumnModel().getColumn(1).setMaxWidth(0);table.getColumnModel().getColumn(1).setPreferredWidth(0);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getAccessibleContext().setAccessibleName(t("catalog.openHint"));
        Runnable open=()->{int row=table.getSelectedRow();if(row>=0){selectedRow=row;showProduct(visible.get(row).id());}};
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"openProduct");table.getActionMap().put("openProduct",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){open.run();}});
        table.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)open.run();}});
        JPanel list=CatalogStyle.panel(new BorderLayout(0,12));JScrollPane tableScroll=new JScrollPane(table);tableScroll.setBorder(null);tableScroll.getViewport().setBackground(CatalogStyle.PAPER);list.add(tableScroll,BorderLayout.CENTER);
        JButton openButton=CatalogStyle.arrowButton(t("catalog.open"),false,false);openButton.addActionListener(e->open.run());
        JPanel foot=CatalogStyle.panel(new BorderLayout());foot.add(new JLabel(visible.isEmpty()?t("catalog.noMatches"):t("catalog.openHint")),BorderLayout.WEST);foot.add(openButton,BorderLayout.EAST);list.add(foot,BorderLayout.SOUTH);
        if(selectedRow>=0&&selectedRow<visible.size())table.setRowSelectionInterval(selectedRow,selectedRow);
        return list;
    }
    private boolean matches(Product p){
        if(category.getSelectedIndex()>0&&!Objects.equals(category.getSelectedItem(),p.category()))return false;
        String text=fold(p.name()+" "+p.reference()+" "+Objects.toString(p.modelReference(),"")+" "+groupName(p.familyId())+" "+Objects.toString(p.category(),"")+" "+p.specifications());
        return Arrays.stream(fold(filter).strip().split("\\s+")).allMatch(text::contains);
    }
    private JComponent familyCard(UUID id,String name,String categoryName){
        long count=items.stream().filter(p->Objects.equals(id,p.familyId())).count();
        String summary=count+" "+t(count==1?"catalog.variantSingular":"catalog.variants");
        JButton open=CatalogStyle.arrowButton(name+"  ·  "+summary,false,false);
        open.setHorizontalAlignment(SwingConstants.LEFT);open.setFont(new Font(Font.SANS_SERIF,Font.BOLD,15));
        Product representative=items.stream().filter(p->Objects.equals(id,p.familyId())).findFirst().orElse(null);
        open.setIcon(new Icon(){
            public int getIconWidth(){return 64;}public int getIconHeight(){return 64;}
            public void paintIcon(Component component,Graphics graphics,int x,int y){
                Icon image=representative==null?null:thumbnails.request(representative.id()).getNow(null);
                if(image==null)new ProductRowRenderer.InitialIcon(name).paintIcon(component,graphics,x,y);
                else image.paintIcon(component,graphics,x,y);
            }
        });
        open.setHorizontalTextPosition(SwingConstants.RIGHT);open.setIconTextGap(16);
        open.setBackground(Color.WHITE);open.setToolTipText(categoryName);open.setPreferredSize(new Dimension(260,96));
        open.addActionListener(e->showFamily(id));return open;
    }
    private void renderDetails(Product p){
        JPanel layout=CatalogStyle.panel(new GridLayout(1,2,24,0));
        JPanel imageColumn=CatalogStyle.surface(new BorderLayout());
        if(photos==null)imageColumn.add(new CatalogStyle.PhotoPlaceholder(t("catalog.noPhoto")),BorderLayout.NORTH);
        else imageColumn.add(new ProductPhotoPanel(photos,shop,p.id(),messages,pending->{saving=pending;if(!pending)thumbnails.clear();refresh.setEnabled(!pending);for(Component crumb:breadcrumbs.getComponents())crumb.setEnabled(!pending);}),BorderLayout.NORTH);
        layout.add(imageColumn);
        JPanel text=CatalogStyle.surface(new GridBagLayout());GridBagConstraints d=new GridBagConstraints();d.gridx=0;d.gridy=0;d.weightx=1;d.fill=GridBagConstraints.HORIZONTAL;d.anchor=GridBagConstraints.NORTHWEST;d.insets=new Insets(0,0,16,0);
        text.add(CatalogStyle.label(t("catalog.reference")+" "+p.reference(),11,false),d);d.gridy++;JTextArea name=new JTextArea(p.name());name.setLineWrap(true);name.setWrapStyleWord(true);name.setEditable(false);name.setOpaque(false);name.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,25));name.setForeground(CatalogStyle.INK);text.add(name,d);
        d.gridy++;text.add(new JLabel(Objects.toString(p.category(),"")),d);
        JPanel prices=CatalogStyle.panel(new GridLayout(1,2,20,0));JPanel selling=value(t("catalog.price"),numbers.format(p.unitPrice()));
        ((JLabel)selling.getComponent(1)).setFont(new Font(Font.SANS_SERIF,Font.PLAIN,28));
        selling.add(CatalogStyle.label("FCFA / "+unit(p.unit()),12,false));prices.add(selling);
        JPanel available=value(t("catalog.available"),balance(p));((JLabel)available.getComponent(1)).setFont(new Font(Font.SANS_SERIF,Font.PLAIN,24));prices.add(available);d.gridy++;text.add(prices,d);
        d.gridy++;text.add(CatalogStyle.label(t("catalog.details"),14,true),d);
        JPanel specs=CatalogStyle.panel(new GridLayout(0,2,18,18));p.specifications().forEach((key,val)->specs.add(value(key,val)));if(p.modelReference()!=null)specs.add(value(t("product.modelReference"),p.modelReference()));d.gridy++;text.add(specs,d);
        JToggleButton buying=new JToggleButton(t("catalog.buying"));buying.setHorizontalAlignment(SwingConstants.LEFT);buying.putClientProperty("FlatLaf.style","arc: 12");
        JLabel buyingValue=new JLabel(p.costPrice()==null?t("catalog.unknown"):numbers.format(p.costPrice())+" FCFA / "+unit(p.unit()));buyingValue.setVisible(false);buying.addActionListener(e->buyingValue.setVisible(buying.isSelected()));d.gridy++;text.add(buying,d);d.gridy++;text.add(buyingValue,d);
        d.gridy++;JButton movements=CatalogStyle.button(t("catalog.movements"),false);movements.setName("openProductMovements");movements.addActionListener(e->showHistory(p));text.add(movements,d);
        text.setMinimumSize(new Dimension(0,0));layout.add(text);
        JPanel wrapper=new WidthPanel();wrapper.add(layout,BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(wrapper);scroll.setBorder(null);scroll.getViewport().setBackground(CatalogStyle.PAPER);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);body.add(scroll,BorderLayout.CENTER);
    }
    private JPanel value(String key,String val){
        JPanel p=CatalogStyle.panel(null);p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        JLabel label=CatalogStyle.label(key,12,false);label.setForeground(CatalogStyle.MUTED);
        label.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));label.setAlignmentX(Component.LEFT_ALIGNMENT);p.add(label);
        JLabel value=CatalogStyle.label(val,16,false);value.setAlignmentX(Component.LEFT_ALIGNMENT);p.add(value);return p;
    }
    private boolean historyBackInHeader(Product product){
        for(Component component:breadcrumbs.getComponents())if(component instanceof JButton back&&"catalogBack".equals(back.getName())){
            back.setName("backFromProductMovements");for(var listener:back.getActionListeners())back.removeActionListener(listener);
            back.addActionListener(e->showProduct(product.id()));return true;
        }
        return false;
    }
    private void showHistory(Product p){
        boolean headerBack=historyBackInHeader(p);
        selected=p.id();long request=++historySequence;refresh.setEnabled(false);body.removeAll();body.add(new JLabel(t("catalog.loading")));body.revalidate();body.repaint();
        new SwingWorker<List<StockMovement>,Void>(){
            protected List<StockMovement> doInBackground() throws Exception{return stock.history(shop,p.id());}
            protected void done(){
                try{
                    var rows=get();if(request!=historySequence)return;body.removeAll();
                    var model=new DefaultTableModel(new String[]{t("stock.history.date"),t("stock.history.type"),t("stock.history.quantity"),t("stock.history.reason")},0){public boolean isCellEditable(int r,int c){return false;}};
                    for(var movement:rows){
                        String kind=switch(movement.type()){case DELIVERY->t("stock.type.delivery");case ADJUSTMENT->t("stock.type.adjustment");case SALE->t("stock.type.sale");case VOID->t("stock.type.void");};
                        model.addRow(new Object[]{CatalogStyle.localDateTime(movement.createdAt(),messages.getLocale()),kind,(movement.quantityMilli()>0?"+":"")+numbers.format(BigDecimal.valueOf(movement.quantityMilli(),3))+" "+quantityUnit(p.unit(),movement.quantityMilli()),movement.reason()});
                    }
                    JTable historyTable=new JTable(model);historyTable.setName("productMovementHistory");historyTable.setRowHeight(40);
                    JPanel history=CatalogStyle.tableCard(t("catalog.movements"),historyTable,t("empty.movements"));CatalogStyle.alignColumns(historyTable,2);
                    JPanel historyHost=CatalogStyle.panel(new BorderLayout());historyHost.add(history,rows.isEmpty()?BorderLayout.NORTH:BorderLayout.CENTER);body.add(historyHost,BorderLayout.CENTER);
                    if(!headerBack){JButton back=CatalogStyle.arrowButton(t("catalog.back"),false,true);back.setName("backFromProductMovements");back.addActionListener(e->showProduct(p.id()));JPanel actions=CatalogStyle.panel(new FlowLayout(FlowLayout.LEFT,0,0));actions.add(back);body.add(actions,BorderLayout.SOUTH);}
                }catch(Exception ex){if(request==historySequence){render();error.setText(t("catalog.stockError"));}}
                finally{if(request==historySequence){refresh.setEnabled(true);body.revalidate();body.repaint();}}
            }
        }.execute();
    }
    private void showImport(){
        ((BorderLayout)getLayout()).getLayoutComponent(BorderLayout.NORTH).setVisible(false);
        error.setVisible(false);errorFeedback.setVisible(false);
        body.removeAll();selected=null;refresh.setEnabled(false);
        for(Component crumb:breadcrumbs.getComponents())crumb.setEnabled(false);
        var flow=new cm.sji.innova.desktop.catalog.importing.CsvImportPanel(imports,shop,dataDirectory,messages,
                ()->{refresh.setEnabled(true);render();},()->{refresh.setEnabled(true);reload();},pending->saving=pending);
        body.add(flow,BorderLayout.CENTER);body.revalidate();body.repaint();
    }
    private void showForm(UUID familyId){
        body.removeAll();selected=null;refresh.setEnabled(false);
        for(Component crumb:breadcrumbs.getComponents())crumb.setEnabled(false);
        VariantForm form=new VariantForm(products,families,shop,messages,groups,items,familyId,
                ()->{refresh.setEnabled(true);render();},p->{family=p.familyId();selected=p.id();reload();},pending->saving=pending);
        body.add(form,BorderLayout.CENTER);body.revalidate();body.repaint();
    }
    private static final class WidthPanel extends JPanel implements Scrollable {
        WidthPanel(){super(new BorderLayout());setOpaque(false);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int axis,int direction){return 18;}
        public int getScrollableBlockIncrement(Rectangle r,int axis,int direction){return Math.max(18,r.height-30);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    /** Reload on re-entry only when no unfinished product form is displayed. */
    public void refreshIfBrowsing() {
        if (saving) return;
        for (Component component : body.getComponents()) if (component instanceof VariantForm || component instanceof cm.sji.innova.desktop.catalog.importing.CsvImportPanel) return;
        reload();
    }
    // Test-facing controls reflect the actual visible catalog, not a parallel mock UI.
    public JTextField searchField(){return search;}
    public JTable variantTable(){return table;}
    public List<Product> loadedProducts(){return items;}
}

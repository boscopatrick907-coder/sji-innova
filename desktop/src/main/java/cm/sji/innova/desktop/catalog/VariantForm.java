package cm.sji.innova.desktop.catalog;

import cm.sji.innova.core.product.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** A scrollable product form; custom pairs and categories stay optional. */
public final class VariantForm extends JPanel {
    private final JTextField name=new JTextField(),price=new JTextField(),buying=new JTextField(),model=new JTextField();
    private final JComboBox<String> family=new JComboBox<>(),category=new JComboBox<>();
    private final JComboBox<String> unit=new JComboBox<>(new String[]{"piece","bag","kg","litre"});
    private final JPanel specs=CatalogStyle.panel(new GridLayout(0,1,0,8));
    private final JLabel error=new JLabel(" ");
    private final JButton save,cancel,add;
    private boolean pending;
    private UUID retainedFamily;
    private String retainedName;
    public VariantForm(ProductService products,ProductFamilyService families,UUID shop,ResourceBundle messages,
                       List<ProductFamily> groups,List<Product> items,UUID selectedFamily,
                       Runnable onCancel,Consumer<Product> onSaved,Consumer<Boolean> onPending){
        super(new BorderLayout(0,15));setOpaque(false);
        java.util.function.Function<String,String> t=messages::getString;
        JLabel title=CatalogStyle.label(t.apply(selectedFamily==null?"catalog.addProduct":"catalog.addVariant"),27,true);add(title,BorderLayout.NORTH);
        family.setEditable(true);((JTextField)family.getEditor().getEditorComponent()).putClientProperty("JTextField.placeholderText",t.apply("catalog.familyExample"));family.addItem("");for(var f:groups)family.addItem(f.name());
        if(selectedFamily!=null)groups.stream().filter(f->f.id().equals(selectedFamily)).findFirst().ifPresent(f->family.setSelectedItem(f.name()));
        category.setEditable(true);category.addItem("");TreeSet<String> categories=new TreeSet<>();for(var p:items)if(p.category()!=null)categories.add(p.category());for(var f:groups)if(f.category()!=null)categories.add(f.category());categories.forEach(category::addItem);
        unit.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int i,boolean selected,boolean focus){return super.getListCellRendererComponent(l,t.apply("unit."+value),i,selected,focus);}});
        JPanel fields=CatalogStyle.surface(new GridBagLayout());GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=0;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(0,0,12,0);
        field(fields,c,t.apply("catalog.familyOptional"),family);field(fields,c,t.apply("catalog.variantName"),name);field(fields,c,t.apply("catalog.sellingRequired"),price);field(fields,c,t.apply("product.unit")+" *",unit);
        JPanel optional=CatalogStyle.panel(new GridBagLayout());GridBagConstraints d=(GridBagConstraints)c.clone();d.gridy=0;
        field(optional,d,t.apply("product.category"),category);field(optional,d,t.apply("product.buyingPrice"),buying);field(optional,d,t.apply("product.modelReference"),model);
        add=CatalogStyle.button(t.apply("catalog.addInformation"),false);
        add.addActionListener(e->{JPanel row=CatalogStyle.panel(new BorderLayout(8,0));JPanel pair=CatalogStyle.panel(new GridLayout(1,2,8,0));JTextField key=new JTextField(),value=new JTextField();key.putClientProperty("JTextField.placeholderText",t.apply("catalog.infoName"));value.putClientProperty("JTextField.placeholderText",t.apply("catalog.infoValue"));key.getAccessibleContext().setAccessibleName(t.apply("catalog.infoName"));value.getAccessibleContext().setAccessibleName(t.apply("catalog.infoValue"));pair.add(key);pair.add(value);row.add(pair);JButton remove=CatalogStyle.button(t.apply("catalog.remove"),false);remove.addActionListener(ev->{specs.remove(row);specs.revalidate();specs.repaint();});row.add(remove,BorderLayout.EAST);specs.add(row);specs.revalidate();specs.repaint();});
        optional.add(specs,d);d.gridy++;optional.add(add,d);optional.setVisible(false);
        JToggleButton more=new JToggleButton(t.apply("catalog.optional"));more.setIcon(new CatalogStyle.ArrowIcon(1));more.setIconTextGap(10);more.putClientProperty("FlatLaf.style","arc: 12");more.putClientProperty("JComponent.minimumHeight",40);more.setHorizontalAlignment(SwingConstants.LEFT);more.addActionListener(e->{optional.setVisible(more.isSelected());revalidate();});fields.add(more,c);c.gridy++;fields.add(optional,c);c.gridy++;
        JLabel stockHint=new JLabel(t.apply("catalog.stockHint"));stockHint.setForeground(CatalogStyle.MUTED);fields.add(stockHint,c);
        JPanel wrapper=CatalogStyle.panel(new BorderLayout());wrapper.add(fields,BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(wrapper);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(18);scroll.getViewport().setBackground(CatalogStyle.PAPER);add(scroll,BorderLayout.CENTER);
        save=CatalogStyle.button(t.apply("action.save"),true);cancel=CatalogStyle.arrowButton(t.apply("catalog.cancel"),false,true);cancel.addActionListener(e->onCancel.run());
        JPanel footer=CatalogStyle.panel(new BorderLayout());error.setForeground(new Color(0xA43B2D));footer.add(error,BorderLayout.NORTH);JPanel buttons=CatalogStyle.panel(new FlowLayout(FlowLayout.RIGHT));buttons.add(cancel);buttons.add(save);footer.add(buttons,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        save.addActionListener(e->{
            if(pending)return;
            final String productName=name.getText().strip(),familyName=String.valueOf(family.getEditor().getItem()).strip(),cat=String.valueOf(category.getEditor().getItem()).strip(),ref=model.getText().strip(),unitCode=(String)unit.getSelectedItem();
            final long selling;final Long cost;Map<String,String> values=new LinkedHashMap<>();
            try{
                if(productName.isBlank())throw new IllegalArgumentException(t.apply("error.requiredName"));
                selling=Long.parseLong(price.getText().strip());cost=buying.getText().isBlank()?null:Long.parseLong(buying.getText().strip());
                if(selling<0||(cost!=null&&cost<0))throw new NumberFormatException();
                for(Component component:specs.getComponents()){
                    JPanel pair=(JPanel)((JPanel)component).getComponent(0);String k=((JTextField)pair.getComponent(0)).getText().strip(),v=((JTextField)pair.getComponent(1)).getText().strip();
                    if(k.isEmpty()&&v.isEmpty())continue;
                    if(k.isEmpty()||v.isEmpty()||values.putIfAbsent(k,v)!=null)throw new IllegalArgumentException(t.apply("error.invalidSpecifications"));
                }
            }catch(NumberFormatException ex){error.setText(t.apply("error.invalidPrice"));return;}catch(IllegalArgumentException ex){error.setText(ex.getMessage());return;}
            pending=true;setEnabledTree(fields,false);save.setEnabled(false);cancel.setEnabled(false);error.setText(t.apply("state.saving"));onPending.accept(true);
            new SwingWorker<Product,Void>(){
                protected Product doInBackground() throws Exception{
                    if(familyName.isEmpty())return products.create(shop,productName,selling,ref,cost,cat,unitCode,values);
                    UUID id=familyName.equals(retainedName)?retainedFamily:null;
                    if(id==null)id=groups.stream().filter(f->f.name().equalsIgnoreCase(familyName)).map(ProductFamily::id).findFirst().orElse(null);
                    if(id==null){var created=families.create(shop,familyName,cat);id=created.id();retainedFamily=id;retainedName=familyName;}
                    return families.createVariant(shop,id,productName,selling,ref,cost,cat,unitCode,values);
                }
                protected void done(){
                    Product saved=null;
                    try{saved=get();}catch(Exception ex){error.setText(t.apply("error.saveProduct"));}
                    finally{pending=false;setEnabledTree(fields,true);save.setEnabled(true);cancel.setEnabled(true);onPending.accept(false);}
                    if(saved!=null)onSaved.accept(saved);
                }
            }.execute();
        });
        name.setName("variantName");price.setName("sellingPrice");family.setName("productFamily");save.setName("saveVariant");
    }
    private static void field(JPanel panel,GridBagConstraints c,String text,JComponent input){JPanel row=CatalogStyle.panel(new BorderLayout(0,6));JLabel label=new JLabel(text);label.setLabelFor(input);row.add(label,BorderLayout.NORTH);row.add(input);input.setPreferredSize(new Dimension(230,36));panel.add(row,c);c.gridy++;}
    private static void setEnabledTree(Component c,boolean enabled){c.setEnabled(enabled);if(c instanceof Container p)for(Component child:p.getComponents())setEnabledTree(child,enabled);}
}

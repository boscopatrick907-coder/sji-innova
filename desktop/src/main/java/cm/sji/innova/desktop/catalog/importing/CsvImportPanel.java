package cm.sji.innova.desktop.catalog.importing;

import cm.sji.innova.core.product.importer.*;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** File -> reviewed rows -> confirmation/result. Pending requests survive restart. */
public final class CsvImportPanel extends JPanel {
    private final ProductImportService service;
    private final UUID shop;
    private final ResourceBundle messages;
    private final Path directory;
    private final Runnable back,finished;
    private final Consumer<Boolean> busy;
    private final JButton choose,confirm,returnButton,template;
    private final JComboBox<String> delimiter;
    private final JCheckBox duplicates;
    private final JTextArea status=new JTextArea(2,20);
    private final DefaultTableModel model;
    private final JTable table;
    private final JScrollPane reviewScroll;
    private final JLabel filename=new JLabel(" ");
    private FeedbackStrip statusFeedback,diagnosticsFeedback;
    private final JTextArea diagnostics=new JTextArea(2,20);
    private final CompletableFuture<Void> ready=new CompletableFuture<>();
    private PendingImportStore store;
    private PendingImportStore.Pending pending;
    private ImportPreview preview;
    private boolean running,completed;
    public CsvImportPanel(ProductImportService service,UUID shop,Path directory,ResourceBundle messages,
                          Runnable back,Runnable finished,Consumer<Boolean> busy){
        super(new BorderLayout(0,18));this.service=service;this.shop=shop;this.directory=directory;this.messages=messages;this.back=back;this.finished=finished;this.busy=busy;setOpaque(false);status.setEditable(false);status.setLineWrap(true);status.setWrapStyleWord(true);status.setOpaque(false);
        JPanel top=CatalogStyle.panel(new BorderLayout(0,15));top.add(CatalogStyle.label(t("csv.title"),28,true),BorderLayout.NORTH);
        choose=CatalogStyle.button(t("csv.choose"),true);template=CatalogStyle.button(t("csv.template"),false);delimiter=new JComboBox<>(new String[]{t("csv.auto"),t("csv.comma"),t("csv.semicolon")});
        JPanel file=CatalogStyle.panel(new GridLayout(1,3,10,0));file.add(choose);file.add(template);file.add(delimiter);
        JPanel source=CatalogStyle.surface(new BorderLayout(0,10));source.add(file,BorderLayout.NORTH);
        JPanel fileInfo=CatalogStyle.panel(new BorderLayout(10,0));fileInfo.add(filename,BorderLayout.CENTER);
        JLabel format=CatalogStyle.label(t("csv.format"),12,false);format.setForeground(CatalogStyle.MUTED);fileInfo.add(format,BorderLayout.EAST);
        source.add(fileInfo,BorderLayout.CENTER);top.add(source,BorderLayout.SOUTH);
        model=new DefaultTableModel(new String[]{t("csv.select"),t("csv.row"),t("product.name"),t("product.unit"),t("catalog.price"),t("csv.state")},0){
            public Class<?> getColumnClass(int col){return col==0?Boolean.class:Object.class;}
            public boolean isCellEditable(int row,int col){return col==0&&!running&&!completed&&store!=null&&pending==null&&preview!=null&&preview.rows().get(row).isValid();}
        };
        table=new JTable(model);table.setRowHeight(40);CatalogStyle.styleTable(table);table.getColumnModel().getColumn(0).setMaxWidth(70);table.getColumnModel().getColumn(1).setMaxWidth(60);table.setName("csvPreview");
        diagnostics.setEditable(false);diagnostics.setLineWrap(true);diagnostics.setWrapStyleWord(true);diagnostics.setBackground(Color.WHITE);diagnostics.setForeground(CatalogStyle.MUTED);
        JPanel center=CatalogStyle.surface(new BorderLayout(0,10));
        center.add(CatalogStyle.label(t("csv.previewHeading"),18,true),BorderLayout.NORTH);
        center.add(CatalogStyle.tableScroll(table,t("empty.import")),BorderLayout.CENTER);
        CatalogStyle.alignColumns(table,1,4);
        table.getColumnModel().getColumn(0).setCellRenderer(table.getDefaultRenderer(Boolean.class));
        diagnostics.setOpaque(false);
        JScrollPane notes=new JScrollPane(diagnostics);notes.setBorder(null);notes.setOpaque(false);notes.getViewport().setOpaque(false);
        diagnosticsFeedback=CatalogStyle.feedback(notes,FeedbackStrip.Tone.WARNING);center.add(diagnosticsFeedback,BorderLayout.SOUTH);
        table.getColumnModel().getColumn(5).setCellRenderer((t,value,selected,focus,row,col)->{
            String state=String.valueOf(value);
            CatalogStyle.BadgeTone tone=state.equals(t("csv.invalid"))||state.equals(t("csv.failed"))?CatalogStyle.BadgeTone.EMPTY:state.equals(t("csv.warning"))||state.equals(t("csv.skipped"))?CatalogStyle.BadgeTone.LOW:CatalogStyle.BadgeTone.NORMAL;
            return CatalogStyle.badgeCell(t,state,tone,selected,row,col,false);
        });
        JPanel previewHost=CatalogStyle.panel(new BorderLayout());previewHost.add(center,BorderLayout.NORTH);
        boolean[] compact={true};model.addTableModelListener(e->{boolean empty=model.getRowCount()==0;if(empty!=compact[0]){compact[0]=empty;previewHost.remove(center);previewHost.add(center,empty?BorderLayout.NORTH:BorderLayout.CENTER);previewHost.revalidate();}});
        JPanel review=CatalogStyle.panel(new BorderLayout(0,16));review.add(top,BorderLayout.NORTH);review.add(previewHost,BorderLayout.CENTER);
        reviewScroll=CatalogStyle.scrollContent(review,460);
        add(reviewScroll,BorderLayout.CENTER);
        duplicates=new JCheckBox(t("csv.duplicates"));duplicates.setOpaque(false);
        confirm=CatalogStyle.button(t("csv.confirm"),true);confirm.setName("confirmCsv");returnButton=CatalogStyle.arrowButton(t("catalog.back"),false,true);
        JPanel bottom=CatalogStyle.surface(new BorderLayout(0,10));JPanel actions=CatalogStyle.panel(new FlowLayout(FlowLayout.RIGHT));actions.add(returnButton);actions.add(confirm);bottom.add(duplicates,BorderLayout.NORTH);statusFeedback=CatalogStyle.feedback(status,FeedbackStrip.Tone.INFO);status.setName("csvFeedback");bottom.add(statusFeedback,BorderLayout.CENTER);bottom.add(actions,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        template.addActionListener(e->{
            JFileChooser picker=new JFileChooser();picker.setSelectedFile(new java.io.File("products_template.csv"));
            if(picker.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
            Path target=picker.getSelectedFile().toPath();
            if(Files.exists(target)&&JOptionPane.showConfirmDialog(this,t("csv.overwrite"),t("csv.template"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            setRunning(true);
            new SwingWorker<Void,Void>(){
                protected Void doInBackground()throws Exception{try(var input=getClass().getResourceAsStream("/cm/sji/innova/desktop/import/products_template.csv")){if(input==null)throw new java.io.IOException("Template unavailable");Files.copy(input,target,StandardCopyOption.REPLACE_EXISTING);}return null;}
                protected void done(){try{get();message("csv.templateSaved",FeedbackStrip.Tone.SUCCESS);}catch(Exception failure){message("csv.templateError",FeedbackStrip.Tone.ERROR);}finally{setRunning(false);}}
            }.execute();
        });
        choose.addActionListener(e->{JFileChooser fileChooser=new JFileChooser();fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV","csv"));if(fileChooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)previewFile(fileChooser.getSelectedFile().toPath());});
        table.getSelectionModel().addListSelectionListener(e->{int row=table.getSelectedRow();if(preview!=null&&row>=0){var p=preview.rows().get(row);diagnosticsFeedback.setTone(p.errors().isEmpty()?FeedbackStrip.Tone.WARNING:FeedbackStrip.Tone.ERROR);diagnostics.setText(String.join("\n",p.errors())+"\n"+String.join("\n",p.warnings()));}});
        confirm.addActionListener(e->submit());returnButton.addActionListener(e->{if(!running){closeStore();if(completed)finished.run();else back.run();}});
        setRunning(true);
        new SwingWorker<Optional<PendingImportStore.Pending>,Void>(){
            protected Optional<PendingImportStore.Pending> doInBackground()throws Exception{store=new PendingImportStore(directory,shop);return store.read();}
            protected void done(){try{var previous=get();if(previous.isPresent()){pending=previous.get();preview=pending.preview();filename.setText(t("csv.savedRequest"));display();message("csv.resume",FeedbackStrip.Tone.WARNING);}ready.complete(null);}catch(Exception ex){message("csv.recoveryError",FeedbackStrip.Tone.ERROR);closeStore();ready.completeExceptionally(ex);}finally{setRunning(false);}}
        }.execute();
    }
    private void message(String key,FeedbackStrip.Tone tone){statusFeedback.setTone(tone);status.setText(t(key));}
    private String t(String key){return messages.getString(key);}
    private void setRunning(boolean value){running=value;busy.accept(value);template.setEnabled(!value&&pending==null&&!completed);choose.setEnabled(!value&&pending==null&&store!=null);delimiter.setEnabled(!value&&pending==null);duplicates.setEnabled(!value&&!completed&&pending==null);returnButton.setEnabled(!value);confirm.setEnabled(!value&&preview!=null&&preview.canImportAny()&&store!=null);}
    public CompletionStage<Void> ready(){return ready;}
    public JTable previewTable(){return table;}
    public CompletionStage<ImportPreview> previewFile(Path source){
        if(running||pending!=null||store==null)return CompletableFuture.failedFuture(new IllegalStateException("Import is busy"));
        CompletableFuture<ImportPreview> result=new CompletableFuture<>();preview=null;filename.setText(source.getFileName().toString());model.setRowCount(0);diagnostics.setText("");setRunning(true);message("csv.reading",FeedbackStrip.Tone.INFO);int option=delimiter.getSelectedIndex();
        new SwingWorker<ImportPreview,Void>(){
            protected ImportPreview doInBackground()throws Exception{
                if(Files.size(source)>10*1024*1024)throw new java.io.IOException("CSV exceeds 10 MiB");
                try(var reader=Files.newBufferedReader(source,StandardCharsets.UTF_8)){return service.preview(shop,reader,new ImportOptions(option==0?null:option==1?',':';',false));}
            }
            protected void done(){try{preview=get();display();message("csv.review",FeedbackStrip.Tone.INFO);result.complete(preview);}catch(Exception ex){message("csv.readError",FeedbackStrip.Tone.ERROR);result.completeExceptionally(ex);}finally{setRunning(false);}}
        }.execute();return result;
    }
    private void display(){
        model.setRowCount(0);for(var row:preview.rows()){
            boolean selected=pending==null?row.isValid()&&!row.hasWarnings():pending.request().selectedRowNumbers().contains(row.rowNumber());
            model.addRow(new Object[]{selected,row.rowNumber(),row.name(),row.unit()!=null&&messages.containsKey("unit."+row.unit())?t("unit."+row.unit()):row.unit(),row.sellingPrice()==null?"—":java.text.NumberFormat.getIntegerInstance(messages.getLocale()).format(row.sellingPrice())+" FCFA",t(!row.isValid()?"csv.invalid":row.hasWarnings()?"csv.warning":"csv.valid")});
        }
        SwingUtilities.invokeLater(()->{if(reviewScroll.getViewport().getExtentSize().height<460)reviewScroll.getVerticalScrollBar().setValue(reviewScroll.getVerticalScrollBar().getMaximum());});
        duplicates.setSelected(pending!=null&&pending.request().allowDuplicates());diagnosticsFeedback.setTone(FeedbackStrip.Tone.ERROR);diagnostics.setText(String.join("\n",preview.fileErrors()));confirm.setText(t(pending==null?"csv.confirm":"csv.retry"));
    }
    public CompletionStage<ImportResult> submit(){
        if(running||preview==null||store==null)return CompletableFuture.failedFuture(new IllegalStateException("No reviewed import"));
        CompletableFuture<ImportResult> completion=new CompletableFuture<>();
        if(pending==null){List<Integer> selected=new ArrayList<>();for(int i=0;i<model.getRowCount();i++)if(Boolean.TRUE.equals(model.getValueAt(i,0)))selected.add(preview.rows().get(i).rowNumber());
            if(selected.isEmpty()){message("csv.noSelection",FeedbackStrip.Tone.WARNING);return CompletableFuture.failedFuture(new IllegalArgumentException("Select rows"));}
            pending=new PendingImportStore.Pending(new ConfirmedImportRequest(UUID.randomUUID(),preview.previewId(),preview.contentHash(),selected,duplicates.isSelected()),preview);
        }
        var request=pending;setRunning(true);message("csv.importing",FeedbackStrip.Tone.INFO);
        new SwingWorker<ImportResult,Void>(){
            protected ImportResult doInBackground()throws Exception{store.save(request);var result=service.confirmImport(shop,request.request(),request.preview());if(result.failedCount()==0)store.clear();return result;}
            protected void done(){try{ImportResult result=get();
                for(var row:result.rowResults())for(int i=0;i<preview.rows().size();i++)if(preview.rows().get(i).rowNumber()==row.rowNumber())model.setValueAt(t(row.status()==ImportedRowResult.Status.SUCCESS?"csv.imported":row.status()==ImportedRowResult.Status.SKIPPED?"csv.skipped":"csv.failed"),i,5);
                diagnosticsFeedback.setTone(result.failedCount()>0?FeedbackStrip.Tone.WARNING:FeedbackStrip.Tone.SUCCESS);diagnostics.setText(t("csv.results")+" "+result.importedCount()+" / "+result.skippedCount()+" / "+result.failedCount());if(result.failedCount()==0){pending=null;completed=true;confirm.setEnabled(false);closeStore();message("csv.finished",FeedbackStrip.Tone.SUCCESS);setRunning(false);completion.complete(result);return;}message("csv.retryHint",FeedbackStrip.Tone.WARNING);completion.complete(result);}catch(Exception ex){message("csv.retryHint",FeedbackStrip.Tone.WARNING);completion.completeExceptionally(ex);}finally{setRunning(false);if(pending!=null)confirm.setText(t("csv.retry"));}}
        }.execute();return completion;
    }
    public void leave(){if(!running){closeStore();if(completed)finished.run();else back.run();}}
    private void closeStore(){if(store!=null){try{store.close();}catch(java.io.IOException ignored){}store=null;}}
}

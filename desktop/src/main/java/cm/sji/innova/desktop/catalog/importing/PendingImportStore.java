package cm.sji.innova.desktop.catalog.importing;

import cm.sji.innova.core.product.importer.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** A portable snapshot saved before submission; one active importer per local data folder. */
public final class PendingImportStore implements AutoCloseable {
    public record Pending(ConfirmedImportRequest request,ImportPreview preview){}
    private final Path file;
    private final UUID shop;
    private final FileChannel channel;
    private final FileLock lock;
    public PendingImportStore(Path directory,UUID shop)throws IOException{
        this.shop=shop;Files.createDirectories(directory);file=directory.resolve("pending-csv.properties");
        channel=FileChannel.open(directory.resolve(".csv-import.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{lock=channel.tryLock();if(lock==null)throw new IOException("Another import is already open");}
        catch(IOException|RuntimeException failure){channel.close();throw new IOException("Another import is already open or the folder is unavailable",failure);}
    }
    public void save(Pending pending)throws IOException{
        if(!shop.equals(pending.preview.shopId()))throw new IOException("Pending import belongs to another shop");
        Properties p=new Properties();p.setProperty("version","1");p.setProperty("shop",shop.toString());
        var request=pending.request;var preview=pending.preview;
        p.setProperty("requestHash",ImportFingerprint.request(shop,request));p.setProperty("operation",request.operationId().toString());p.setProperty("preview",request.previewId().toString());p.setProperty("hash",request.contentHash());p.setProperty("duplicates",Boolean.toString(request.allowDuplicates()));
        p.setProperty("selection",request.selectedRowNumbers().stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")));
        p.setProperty("rows",Integer.toString(preview.rows().size()));
        for(int i=0;i<preview.rows().size();i++){
            var r=preview.rows().get(i);String k="r."+i+".";p.setProperty(k+"number",Integer.toString(r.rowNumber()));
            put(p,k+"raw",r.rawLine());put(p,k+"name",r.name());put(p,k+"model",r.modelReference());put(p,k+"category",r.category());put(p,k+"unit",r.unit());put(p,k+"buying",r.buyingPrice());put(p,k+"selling",r.sellingPrice());
            p.setProperty(k+"errors",Integer.toString(r.errors().size()));for(int j=0;j<r.errors().size();j++)put(p,k+"e."+j,r.errors().get(j));
            p.setProperty(k+"warnings",Integer.toString(r.warnings().size()));for(int j=0;j<r.warnings().size();j++)put(p,k+"w."+j,r.warnings().get(j));
        }
        Path temp=Files.createTempFile(file.getParent(),"pending-csv-",".tmp");
        try{
            try(var out=Files.newOutputStream(temp)){p.store(out,"Cella pending CSV import — preserve until confirmed");}
            if(Files.size(temp)>16*1024*1024)throw new IOException("Import snapshot is too large");
            try(var data=FileChannel.open(temp,StandardOpenOption.WRITE)){data.force(true);}
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
    private static void put(Properties p,String key,Object value){if(value!=null)p.setProperty(key,value.toString());}
    public Optional<Pending> read()throws IOException{
        if(!Files.exists(file))return Optional.empty();
        try{
            if(Files.size(file)>16*1024*1024)throw new IOException("Pending import is too large");
            Properties p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
            if(!"1".equals(p.getProperty("version"))||!shop.toString().equals(p.getProperty("shop")))throw new IOException("Unsupported pending import or wrong shop");
            int count=number(p,"rows",100000);List<ParsedRow> rows=new ArrayList<>();
            for(int i=0;i<count;i++){
                String k="r."+i+".";List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();
                for(int j=0,n=number(p,k+"errors",1000);j<n;j++)errors.add(Objects.requireNonNull(p.getProperty(k+"e."+j)));
                for(int j=0,n=number(p,k+"warnings",1000);j<n;j++)warnings.add(Objects.requireNonNull(p.getProperty(k+"w."+j)));
                rows.add(new ParsedRow(Integer.parseInt(p.getProperty(k+"number")),p.getProperty(k+"raw"),p.getProperty(k+"name"),p.getProperty(k+"model"),p.getProperty(k+"category"),p.getProperty(k+"unit"),price(p,k+"buying"),price(p,k+"selling"),errors,warnings));
            }
            String hash=ImportFingerprint.content(rows);UUID preview=UUID.fromString(p.getProperty("preview"));
            if(!hash.equals(p.getProperty("hash"))||!preview.equals(ImportFingerprint.previewId(shop,hash)))throw new IOException("Pending import is damaged");
            String duplicate=p.getProperty("duplicates");if(!Set.of("true","false").contains(duplicate))throw new IOException("Invalid duplicate decision");
            var request=new ConfirmedImportRequest(UUID.fromString(p.getProperty("operation")),preview,hash,Arrays.stream(p.getProperty("selection").split(",")).map(Integer::parseInt).toList(),Boolean.parseBoolean(duplicate));
            if(!ImportFingerprint.request(shop,request).equals(p.getProperty("requestHash")))throw new IOException("Pending request was changed");
            int valid=(int)rows.stream().filter(ParsedRow::isValid).count();
            var snapshot=new ImportPreview(preview,shop,count,valid,count-valid,(int)rows.stream().filter(ParsedRow::hasWarnings).count(),rows,List.of(),hash);
            return Optional.of(new Pending(request,snapshot));
        }catch(IOException ex){throw ex;}catch(RuntimeException ex){throw new IOException("Pending import is damaged; file was left untouched",ex);}
    }
    private static int number(Properties p,String key,int max)throws IOException{int n=Integer.parseInt(p.getProperty(key));if(n<0||n>max)throw new IOException("Invalid pending import size");return n;}
    private static Long price(Properties p,String key){return p.getProperty(key)==null?null:Long.valueOf(p.getProperty(key));}
    public void clear()throws IOException{Files.deleteIfExists(file);}
    @Override public void close()throws IOException{try{lock.release();}finally{channel.close();}}
}

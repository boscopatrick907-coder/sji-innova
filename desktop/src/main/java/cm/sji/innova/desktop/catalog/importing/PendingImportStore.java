package cm.sji.innova.desktop.catalog.importing;

import cm.sji.innova.core.product.importer.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** A portable snapshot saved before submission; one active importer per local data folder. */
public final class PendingImportStore implements AutoCloseable {
    public record Pending(ConfirmedImportRequest request,ImportPreview preview){}
    /** Another window or Cella process has an import open on this data folder. */
    public static final class BusyException extends IOException{public BusyException(){super("Another import is already open");}}
    /** The saved import cannot be understood, or belongs to another shop: retrying cannot fix it. */
    public static final class DamagedException extends IOException{
        public DamagedException(String message){super(message);}
        public DamagedException(String message,Throwable cause){super(message,cause);}
    }
    /**
     * Folders with a store open in this process. A second store for the same folder is refused before it
     * opens the lock file: on Linux, closing any handle to a file drops every lock this process holds on
     * it, so a refused second handle would silently release the first store's lock.
     */
    private static final Set<Path> OPEN_IN_THIS_PROCESS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path file;
    private final UUID shop;
    private final Path key;
    private final FileChannel channel;
    private final FileLock lock;
    public PendingImportStore(Path directory,UUID shop)throws IOException{
        this.shop=shop;Files.createDirectories(directory);file=directory.resolve("pending-csv.properties");
        key=directory.toRealPath();
        if(!OPEN_IN_THIS_PROCESS.add(key))throw new BusyException();
        FileChannel opened=null;
        try{
            opened=FileChannel.open(key.resolve(".csv-import.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock taken;
            try{taken=opened.tryLock();}
            catch(OverlappingFileLockException inThisProcess){throw new BusyException();}
            catch(IOException|RuntimeException failure){throw new IOException("Another import is already open or the folder is unavailable",failure);}
            if(taken==null)throw new BusyException();
            channel=opened;lock=taken;
        }catch(IOException|RuntimeException failure){
            if(opened!=null)try{opened.close();}catch(IOException closing){failure.addSuppressed(closing);}
            OPEN_IN_THIS_PROCESS.remove(key);
            throw failure;
        }
    }
    /**
     * Holds the folder's import lock without reading or writing any import, for folder maintenance
     * (OFF-03a1): while it is held, no import screen can open in any Cella. Refused with
     * {@link BusyException} while an import screen is open.
     */
    public static AutoCloseable holdFolder(Path directory)throws IOException{return new PendingImportStore(directory,new UUID(0L,0L));}
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
            if(Files.size(file)>16*1024*1024)throw new DamagedException("Pending import is too large");
            Properties p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
            if(!"1".equals(p.getProperty("version"))||!shop.toString().equals(p.getProperty("shop")))throw new DamagedException("Unsupported pending import or wrong shop");
            int count=number(p,"rows",100000);List<ParsedRow> rows=new ArrayList<>();
            for(int i=0;i<count;i++){
                String k="r."+i+".";List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();
                for(int j=0,n=number(p,k+"errors",1000);j<n;j++)errors.add(Objects.requireNonNull(p.getProperty(k+"e."+j)));
                for(int j=0,n=number(p,k+"warnings",1000);j<n;j++)warnings.add(Objects.requireNonNull(p.getProperty(k+"w."+j)));
                rows.add(new ParsedRow(Integer.parseInt(p.getProperty(k+"number")),p.getProperty(k+"raw"),p.getProperty(k+"name"),p.getProperty(k+"model"),p.getProperty(k+"category"),p.getProperty(k+"unit"),price(p,k+"buying"),price(p,k+"selling"),errors,warnings));
            }
            String hash=ImportFingerprint.content(rows);UUID preview=UUID.fromString(p.getProperty("preview"));
            if(!hash.equals(p.getProperty("hash"))||!preview.equals(ImportFingerprint.previewId(shop,hash)))throw new DamagedException("Pending import is damaged");
            String duplicate=p.getProperty("duplicates");if(!Set.of("true","false").contains(duplicate))throw new DamagedException("Invalid duplicate decision");
            var request=new ConfirmedImportRequest(UUID.fromString(p.getProperty("operation")),preview,hash,Arrays.stream(p.getProperty("selection").split(",")).map(Integer::parseInt).toList(),Boolean.parseBoolean(duplicate));
            if(!ImportFingerprint.request(shop,request).equals(p.getProperty("requestHash")))throw new DamagedException("Pending request was changed");
            int valid=(int)rows.stream().filter(ParsedRow::isValid).count();
            var snapshot=new ImportPreview(preview,shop,count,valid,count-valid,(int)rows.stream().filter(ParsedRow::hasWarnings).count(),rows,List.of(),hash);
            return Optional.of(new Pending(request,snapshot));
        }catch(IOException ex){throw ex;}catch(RuntimeException ex){throw new DamagedException("Pending import is damaged; file was left untouched",ex);}
    }
    /**
     * Renames a damaged saved import, keeping it next to the original, so a new import can start.
     * Refuses a readable one, and a file that is only unavailable for a moment. Returns the new path.
     */
    public Path setAside()throws IOException{
        if(!Files.exists(file))throw new IOException("There is no saved import to set aside");
        try{read();}
        catch(DamagedException damaged){return Files.move(file,file.resolveSibling("pending-csv-set-aside-"+UUID.randomUUID()+".properties"),StandardCopyOption.ATOMIC_MOVE);}
        throw new IOException("A readable saved import is resumed, never set aside");
    }
    private static int number(Properties p,String key,int max)throws IOException{int n=Integer.parseInt(p.getProperty(key));if(n<0||n>max)throw new DamagedException("Invalid pending import size");return n;}
    private static Long price(Properties p,String key){return p.getProperty(key)==null?null:Long.valueOf(p.getProperty(key));}
    public void clear()throws IOException{Files.deleteIfExists(file);}
    private boolean closed;
    /** Releases the folder. A second call does nothing, so it never frees a later store's place. */
    @Override public synchronized void close()throws IOException{
        if(closed)return;closed=true;
        try{lock.release();}finally{try{channel.close();}finally{OPEN_IN_THIS_PROCESS.remove(key);}}
    }
}

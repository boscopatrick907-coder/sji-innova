package cm.sji.innova.desktop.sales;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** One checkout-file operation at a time, including its database confirmation. */
final class CheckoutFileLease implements AutoCloseable {
    private static final ConcurrentHashMap<Path,ReentrantLock> JVM = new ConcurrentHashMap<>();
    private final ReentrantLock local;
    private final FileChannel channel;
    private final FileLock file;
    private CheckoutFileLease(ReentrantLock local,FileChannel channel,FileLock file){this.local=local;this.channel=channel;this.file=file;}
    static CheckoutFileLease acquire(Path pending)throws IOException{
        Files.createDirectories(pending.getParent());
        Path key=pending.getParent().toRealPath().resolve(pending.getFileName());
        ReentrantLock local=JVM.computeIfAbsent(key,ignored->new ReentrantLock());
        if(!local.tryLock())throw new PendingCheckoutStore.BusyException();
        // Nested store methods reuse the lease already held by Checkout on this thread.
        if(local.getHoldCount()>1)return new CheckoutFileLease(local,null,null);
        FileChannel channel=null;
        try{
            channel=FileChannel.open(key.resolveSibling(key.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock file=channel.tryLock();if(file==null)throw new PendingCheckoutStore.BusyException();
            return new CheckoutFileLease(local,channel,file);
        }catch(IOException|RuntimeException failure){
            if(channel!=null)try{channel.close();}catch(IOException close){failure.addSuppressed(close);}
            local.unlock();if(failure instanceof IOException io)throw io;throw new PendingCheckoutStore.BusyException(failure);
        }
    }
    public void close()throws IOException{
        try{if(file!=null)file.release();}finally{try{if(channel!=null)channel.close();}finally{local.unlock();}}
    }
}

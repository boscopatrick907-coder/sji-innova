package cm.sji.innova.desktop.dashboard;

import cm.sji.innova.desktop.shell.Messages;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardPanelTest {
    private <T>T edt(Supplier<T> action)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();SwingUtilities.invokeAndWait(()->result.set(action.get()));return result.get();
    }
    private static JLabel find(Component component,String name){
        if(component instanceof JLabel label&&name.equals(label.getName()))return label;
        if(component instanceof Container c)for(Component child:c.getComponents()){JLabel found=find(child,name);if(found!=null)return found;}
        return null;
    }
    @Test void readsRunOffTheEventThreadAndFailureIsNotDisplayedAsZero()throws Exception{
        AtomicBoolean fail=new AtomicBoolean();AtomicInteger reads=new AtomicInteger();
        DashboardPanel panel=edt(()->new DashboardPanel(Messages.load(Locale.FRENCH),screen->{},period->{
            assertFalse(SwingUtilities.isEventDispatchThread());reads.incrementAndGet();
            if(fail.get())throw new java.io.IOException("Storage unavailable");
            return DashboardSummary.calculate(java.util.List.of(),java.util.List.of(),period,Clock.systemUTC());
        }));
        assertEquals(0,reads.get(),"Constructing a hidden screen must not read");
        edt(panel::reload).toCompletableFuture().get(10,TimeUnit.SECONDS);
        edt(()->{assertNotNull(find(panel,"dashboardRevenue"));assertTrue(find(panel,"dashboardRevenue").getText().startsWith("0"));return null;});
        fail.set(true);
        assertThrows(ExecutionException.class,()->edt(panel::reload).toCompletableFuture().get(10,TimeUnit.SECONDS));
        edt(()->{assertNull(find(panel,"dashboardRevenue"));assertTrue(find(panel,"dashboardFeedback").getText().contains("Impossible"));return null;});
        fail.set(false);edt(panel::reload).toCompletableFuture().get(10,TimeUnit.SECONDS);
        edt(()->{assertNotNull(find(panel,"dashboardRevenue"));return null;});
    }
    @Test void anOlderSlowReadCannotOverwriteTheLatestPeriod()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        DashboardPanel panel=edt(()->new DashboardPanel(Messages.load(Locale.ENGLISH),screen->{},period->{
            if(calls.incrementAndGet()==1){started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));throw new java.io.IOException("Old failure");}
            return DashboardSummary.calculate(java.util.List.of(),java.util.List.of(),period,Clock.systemUTC());
        }));
        CompletionStage<DashboardSummary> old=edt(panel::reload);assertTrue(started.await(10,TimeUnit.SECONDS));
        try{edt(panel::reload).toCompletableFuture().get(10,TimeUnit.SECONDS);}finally{release.countDown();}
        assertThrows(ExecutionException.class,()->old.toCompletableFuture().get(10,TimeUnit.SECONDS));
        edt(()->{assertNotNull(find(panel,"dashboardRevenue"));assertFalse(find(panel,"dashboardFeedback").getText().contains("could not"));return null;});
    }
}

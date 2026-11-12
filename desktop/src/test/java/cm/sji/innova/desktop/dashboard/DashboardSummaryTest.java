package cm.sji.innova.desktop.dashboard;

import cm.sji.innova.core.sale.*;
import cm.sji.innova.core.stock.StockLevel;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardSummaryTest {
    private final UUID shop=UUID.randomUUID(),device=UUID.randomUUID(),product=UUID.randomUUID();
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"),ZoneOffset.UTC);
    private SaleState paid(String at,long total,String name) {
        UUID id=UUID.randomUUID();Instant time=Instant.parse(at);
        SaleItem item=new SaleItem(UUID.randomUUID(),id,product,"P-0001",name,"piece",1000,total,total);
        Sale sale=new Sale(id,shop,"REC-"+id,device,PaymentMethod.CASH,total,time,List.of(item));
        SaleEvent event=new SaleEvent(SaleEvent.createdEventId(id),id,shop,SaleEvent.Type.CREATED,device,null,"Sale",time);
        return new SaleState(sale,SaleStatus.PAID,List.of(event),Optional.empty());
    }
    private SaleState voided(SaleState paid,String at) {
        SaleEvent event=new SaleEvent(UUID.randomUUID(),paid.sale().id(),shop,SaleEvent.Type.VOIDED,device,device,"Cancel",Instant.parse(at));
        return new SaleState(paid.sale(),SaleStatus.VOIDED,List.of(paid.events().getFirst(),event),Optional.of(event));
    }
    @Test void paidTotalsUseDoualaDayAndExcludeVoidedSales() {
        var states=List.of(paid("2026-11-11T23:10:00Z",1001,"Old name"),
                paid("2026-11-12T10:00:00Z",2000,"Latest name"),
                paid("2026-11-11T22:59:59Z",999,"Yesterday"),
                voided(paid("2026-11-01T09:00:00Z",5000,"Voided"),"2026-11-12T08:00:00Z"));
        var result=DashboardSummary.calculate(states,List.of(),DashboardSummary.Period.TODAY,clock);
        assertEquals(3001,result.revenue());assertEquals(2,result.paidReceipts());assertEquals(1,result.cancellations());
        assertEquals("1501",result.average().orElseThrow().toPlainString());
        assertEquals(3001,result.days().getFirst().revenue());
        assertEquals("Latest name",result.topProducts().getFirst().name());
        assertEquals("P-0001",result.topProducts().getFirst().reference());
        assertEquals(3001,result.topProducts().getFirst().revenue());
    }
    @Test void sevenDaysIncludesZeroDaysAndExcludesFutureRecords() {
        var result=DashboardSummary.calculate(List.of(paid("2026-11-06T09:00:00Z",1200,"Start"),
                paid("2026-11-05T09:00:00Z",999,"Too old"),paid("2026-11-13T09:00:00Z",999,"Future")),
                List.of(),DashboardSummary.Period.SEVEN_DAYS,clock);
        assertEquals(7,result.days().size());assertEquals(LocalDate.of(2026,11,6),result.from());
        assertEquals(1200,result.revenue());assertEquals(6,result.days().stream().filter(d->d.revenue()==0).count());
    }
    @Test void emptyAndFreeSalesHaveDifferentAverageStates() {
        var empty=DashboardSummary.calculate(List.of(),List.of(),DashboardSummary.Period.MONTH,clock);
        assertTrue(empty.average().isEmpty());assertEquals(12,empty.days().size());assertTrue(empty.topProducts().isEmpty());
        var free=DashboardSummary.calculate(List.of(paid("2026-11-12T09:00:00Z",0,"Free")),List.of(),DashboardSummary.Period.TODAY,clock);
        assertEquals(1,free.paidReceipts());assertEquals("0",free.average().orElseThrow().toPlainString());
    }
    @Test void lowStockUsesEachStoredThresholdIncludingZeroWithoutAddingDifferentUnits() {
        var stocks=List.of(new StockLevel(shop,UUID.randomUUID(),"Rice","bag",2000,2000),
                new StockLevel(shop,UUID.randomUUID(),"Oil","litre",0,0),
                new StockLevel(shop,UUID.randomUUID(),"Soap","piece",3000,2000));
        var result=DashboardSummary.calculate(List.of(),stocks,DashboardSummary.Period.TODAY,clock);
        assertEquals(2,result.lowStock().size());assertEquals(Set.of("bag","litre"),new HashSet<>(result.lowStock().stream().map(StockLevel::unit).toList()));
    }
    @Test void overflowIsReportedRatherThanPublishingAnIncorrectTotal() {
        assertThrows(ArithmeticException.class,()->DashboardSummary.calculate(List.of(
                paid("2026-11-12T09:00:00Z",Long.MAX_VALUE,"Large"),paid("2026-11-12T10:00:00Z",1,"Extra")),
                List.of(),DashboardSummary.Period.TODAY,clock));
    }
}

package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.sale.PaymentMethod;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleEvent;
import cm.sji.innova.core.sale.SaleItem;
import cm.sji.innova.core.sale.SaleState;
import cm.sji.innova.core.sale.SaleStatus;
import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.sales.SaleHistoryFilter.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleHistoryFilterTest {
    private static final UUID SHOP = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();

    private static SaleState sale(UUID id, String receipt, String savedAt, boolean voided) {
        Instant time = Instant.parse(savedAt);
        SaleItem item = new SaleItem(UUID.randomUUID(), id, UUID.randomUUID(), "Rice", "piece", 1_000, 100, 100);
        Sale sale = new Sale(id, SHOP, receipt, DEVICE, PaymentMethod.CASH, 100, time, List.of(item));
        SaleEvent created = new SaleEvent(SaleEvent.createdEventId(id), id, SHOP, SaleEvent.Type.CREATED,
                DEVICE, null, "Cash sale", time);
        if (!voided) return new SaleState(sale, SaleStatus.PAID, List.of(created), Optional.empty());
        SaleEvent cancelled = new SaleEvent(UUID.randomUUID(), id, SHOP, SaleEvent.Type.VOIDED,
                DEVICE, UUID.randomUUID(), "Wrong quantity", time.plusSeconds(60));
        return new SaleState(sale, SaleStatus.VOIDED, List.of(created, cancelled), Optional.of(cancelled));
    }

    private static SaleState sale(String receipt, String savedAt) {
        return sale(UUID.randomUUID(), receipt, savedAt, false);
    }

    private static SaleHistoryFilter days(String from, String to) {
        return new SaleHistoryFilter("", Status.ALL, SaleHistoryFilter.parseDay(from), SaleHistoryFilter.parseDay(to));
    }

    @Test
    void theNewestSaleComesFirst() {
        SaleState old = sale("A", "2026-11-01T10:00:00Z");
        SaleState recent = sale("B", "2026-11-03T10:00:00Z");
        SaleState middle = sale("C", "2026-11-02T10:00:00Z");

        assertEquals(List.of(recent, middle, old), SaleHistoryFilter.newestFirst(List.of(old, recent, middle)));
    }

    @Test
    void salesSavedAtTheSameInstantAlwaysComeInTheSameOrder() {
        SaleState first = sale(new UUID(0, 1), "Z", "2026-11-01T10:00:00Z", false);
        SaleState second = sale(new UUID(0, 2), "A", "2026-11-01T10:00:00Z", false);

        assertEquals(List.of(first, second), SaleHistoryFilter.newestFirst(List.of(second, first)));
        assertEquals(List.of(first, second), SaleHistoryFilter.newestFirst(List.of(first, second)));
    }

    @Test
    void sortingLeavesTheServicesListAsItWas() {
        SaleState old = sale("A", "2026-11-01T10:00:00Z");
        SaleState recent = sale("B", "2026-11-03T10:00:00Z");
        List<SaleState> original = new ArrayList<>(List.of(old, recent));

        SaleHistoryFilter.newestFirst(original);

        assertEquals(List.of(old, recent), original);
    }

    @Test
    void aReceiptIsFoundByAnyPartOfItIgnoringCaseAndSpaces() {
        SaleState wanted = sale("REC-20261105-C527DE5980E6", "2026-11-05T10:00:00Z");

        for (String typed : List.of("", "   ", "c527", "C527", "  rec-20261105 ", "REC-20261105-C527DE5980E6")) {
            assertTrue(new SaleHistoryFilter(typed, Status.ALL, null, null).matches(wanted), typed);
        }
        assertFalse(new SaleHistoryFilter("c527x", Status.ALL, null, null).matches(wanted));
    }

    @Test
    void theStatusFilterUsesTheEffectiveStatus() {
        SaleState paid = sale(UUID.randomUUID(), "PAID", "2026-11-05T10:00:00Z", false);
        SaleState voided = sale(UUID.randomUUID(), "VOID", "2026-11-05T11:00:00Z", true);
        List<SaleState> both = List.of(paid, voided);

        assertEquals(both, new SaleHistoryFilter("", Status.ALL, null, null).apply(both));
        assertEquals(List.of(paid), new SaleHistoryFilter("", Status.PAID, null, null).apply(both));
        assertEquals(List.of(voided), new SaleHistoryFilter("", Status.VOIDED, null, null).apply(both));
    }

    /** Douala is one hour ahead of UTC, so its 5 November starts at 23:00 UTC on 4 November. */
    @Test
    void aDayStartsAndEndsAtMidnightInDouala() {
        SaleState lastSecondOfThe4th = sale("A", "2026-11-04T22:59:59Z");
        SaleState firstSecondOfThe5th = sale("B", "2026-11-04T23:00:00Z");
        SaleState lastSecondOfThe5th = sale("C", "2026-11-05T22:59:59Z");
        SaleState firstSecondOfThe6th = sale("D", "2026-11-05T23:00:00Z");
        List<SaleState> all = List.of(lastSecondOfThe4th, firstSecondOfThe5th, lastSecondOfThe5th, firstSecondOfThe6th);

        assertEquals(List.of(firstSecondOfThe5th, lastSecondOfThe5th), days("05/11/2026", "05/11/2026").apply(all));
        assertEquals(List.of(lastSecondOfThe4th), days("", "04/11/2026").apply(all));
        assertEquals(List.of(firstSecondOfThe6th), days("06/11/2026", "").apply(all));
        assertEquals(all, days("", "").apply(all));
    }

    @Test
    void theDateOnScreenIsTheSameDateTheFilterUses() {
        Instant firstMinuteOfThe5th = Instant.parse("2026-11-04T23:00:00Z");

        assertTrue(CatalogStyle.localDateTime(firstMinuteOfThe5th, Locale.FRANCE).startsWith("05/11/2026"));
        assertEquals(LocalDate.of(2026, 11, 5), firstMinuteOfThe5th.atZone(SaleHistoryFilter.SHOP_ZONE).toLocalDate());
    }

    @Test
    void allTheFiltersMustMatchTogether() {
        SaleState wanted = sale(UUID.randomUUID(), "REC-1", "2026-11-05T10:00:00Z", true);
        SaleState wrongReceipt = sale(UUID.randomUUID(), "REC-2", "2026-11-05T10:00:00Z", true);
        SaleState wrongStatus = sale(UUID.randomUUID(), "REC-1", "2026-11-05T10:00:00Z", false);
        SaleState wrongDay = sale(UUID.randomUUID(), "REC-1", "2026-11-06T10:00:00Z", true);
        LocalDate day = LocalDate.of(2026, 11, 5);

        assertEquals(List.of(wanted), new SaleHistoryFilter("rec-1", Status.VOIDED, day, day)
                .apply(List.of(wanted, wrongReceipt, wrongStatus, wrongDay)));
    }

    @Test
    void aDayMustBeRealAndWrittenDayFirst() {
        assertEquals(LocalDate.of(2026, 11, 5), SaleHistoryFilter.parseDay("05/11/2026"));
        assertEquals(LocalDate.of(2026, 11, 5), SaleHistoryFilter.parseDay(" 5/11/2026 "));
        assertEquals(LocalDate.of(2028, 2, 29), SaleHistoryFilter.parseDay("29/02/2028"));
        assertNull(SaleHistoryFilter.parseDay(""));
        assertNull(SaleHistoryFilter.parseDay("   "));
        for (String wrong : List.of("31/02/2026", "29/02/2026", "13/13/2026", "2026-11-05", "05/11/26", "05/11", "hier")) {
            assertThrows(DateTimeParseException.class, () -> SaleHistoryFilter.parseDay(wrong), wrong);
        }
    }
}

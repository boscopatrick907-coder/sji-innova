package cm.sji.innova.desktop.dashboard;

import cm.sji.innova.core.sale.SaleState;
import cm.sji.innova.core.stock.StockLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Read-only dashboard definitions. All business sums use exact whole FCFA, never catalogue prices. */
public record DashboardSummary(LocalDate from, LocalDate through, long revenue, long paidReceipts,
        long cancellations, List<Day> days, List<TopProduct> topProducts, List<StockLevel> lowStock) {
    public static final ZoneId ZONE = ZoneId.of("Africa/Douala");
    public enum Period { TODAY, SEVEN_DAYS, MONTH }
    public record Day(LocalDate date, long revenue) { }
    public record TopProduct(UUID id, String name, String reference, long revenue) { }

    public DashboardSummary {
        days = List.copyOf(days); topProducts = List.copyOf(topProducts); lowStock = List.copyOf(lowStock);
    }

    /** Display rounding only; an empty period has no average, not an invented zero-value receipt. */
    public Optional<BigDecimal> average() {
        return paidReceipts == 0 ? Optional.empty() : Optional.of(BigDecimal.valueOf(revenue)
                .divide(BigDecimal.valueOf(paidReceipts), 0, RoundingMode.HALF_UP));
    }

    public static DashboardSummary calculate(List<SaleState> sales, List<StockLevel> stocks,
                                               Period period, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate from = switch (period) {
            case TODAY -> today;
            case SEVEN_DAYS -> today.minusDays(6);
            case MONTH -> today.withDayOfMonth(1);
        };
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) days.put(d, 0L);
        Map<UUID, TopProduct> top = new HashMap<>();
        long revenue = 0, receipts = 0, cancellations = 0;
        // Latest saved name/reference wins when a product has different snapshots across receipts.
        List<SaleState> ordered = sales.stream().sorted(Comparator
                .comparing((SaleState s) -> s.sale().createdAt()).reversed()
                .thenComparing(s -> s.sale().id().toString())).toList();
        for (SaleState state : ordered) {
            if (state.voided()) {
                LocalDate cancelled = state.voidEvent().orElseThrow().createdAt().atZone(ZONE).toLocalDate();
                if (!cancelled.isBefore(from) && !cancelled.isAfter(today)) cancellations++;
                continue;
            }
            LocalDate date = state.sale().createdAt().atZone(ZONE).toLocalDate();
            if (!days.containsKey(date)) continue;
            revenue = Math.addExact(revenue, state.sale().totalAmount());
            receipts++;
            days.put(date, Math.addExact(days.get(date), state.sale().totalAmount()));
            for (var item : state.sale().items()) {
                TopProduct previous = top.get(item.productId());
                top.put(item.productId(), previous == null
                        ? new TopProduct(item.productId(), item.productName(), item.productReference(), item.subtotal())
                        : new TopProduct(previous.id(), previous.name(), previous.reference(),
                                Math.addExact(previous.revenue(), item.subtotal())));
            }
        }
        List<TopProduct> best = top.values().stream().sorted(Comparator.comparingLong(TopProduct::revenue)
                .reversed().thenComparing(p -> p.id().toString())).limit(5).toList();
        List<StockLevel> low = stocks.stream().filter(StockLevel::lowStock)
                .sorted(Comparator.comparing(StockLevel::productName).thenComparing(s -> s.productId().toString())).toList();
        return new DashboardSummary(from, today, revenue, receipts, cancellations,
                days.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue())).toList(), best, low);
    }
}

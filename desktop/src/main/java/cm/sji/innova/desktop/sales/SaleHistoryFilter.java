package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.SaleState;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the cashier is looking for in the sales history. Pure logic: no screen and no database.
 *
 * <p>Saved times are never changed. A "day" always means a day in the shop's time zone, the same zone
 * {@code CatalogStyle.localDateTime} displays, so a sale is never shown under one date and filtered
 * under another.
 */
record SaleHistoryFilter(String receipt, Status status, LocalDate from, LocalDate to) {
    enum Status { ALL, PAID, VOIDED }

    static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Douala");

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    /** Two sales saved at the same instant always come in the same order, thanks to their ID. */
    private static final Comparator<SaleState> NEWEST_FIRST = Comparator
            .comparing((SaleState state) -> state.sale().createdAt()).reversed()
            .thenComparing(state -> state.sale().id());

    SaleHistoryFilter {
        receipt = receipt.strip().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(status, "status");
    }

    /** A sorted copy: the list the service returned is left as it was. */
    static List<SaleState> newestFirst(List<SaleState> states) {
        return states.stream().sorted(NEWEST_FIRST).toList();
    }

    /**
     * Reads a day such as 05/11/2026. Blank means "no limit", so it returns null.
     *
     * @throws java.time.format.DateTimeParseException if the text is not a real day
     */
    static LocalDate parseDay(String text) {
        String day = text.strip();
        return day.isEmpty() ? null : LocalDate.parse(day, DAY);
    }

    /** The sales that pass the filter, in the order they were given. */
    List<SaleState> apply(List<SaleState> states) {
        return states.stream().filter(this::matches).toList();
    }

    boolean matches(SaleState state) {
        return receiptMatches(state) && statusMatches(state) && dayMatches(state);
    }

    private boolean receiptMatches(SaleState state) {
        return state.sale().receiptNumber().toLowerCase(Locale.ROOT).contains(receipt);
    }

    private boolean statusMatches(SaleState state) {
        return status == Status.ALL || state.voided() == (status == Status.VOIDED);
    }

    private boolean dayMatches(SaleState state) {
        LocalDate day = state.sale().createdAt().atZone(SHOP_ZONE).toLocalDate();
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }
}

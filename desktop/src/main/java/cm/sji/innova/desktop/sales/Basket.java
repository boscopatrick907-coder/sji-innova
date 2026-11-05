package cm.sji.innova.desktop.sales;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lines the cashier is building, one line per variant, in the order they were added.
 * A change that would make the total too large throws {@link ArithmeticException} and leaves the
 * basket as it was, so the basket stays usable. Use it on the Swing event thread only.
 */
public final class Basket {
    private final Map<UUID, BasketLine> lines = new LinkedHashMap<>();

    /** Adding a variant that is already there adds to its line instead of creating a second one. */
    public void add(BasketLine line) {
        BasketLine existing = lines.get(line.productId());
        store(existing == null ? line
                : existing.withQuantity(Math.addExact(existing.quantityMilli(), line.quantityMilli())));
    }

    public void setQuantity(UUID productId, long quantityMilli) {
        BasketLine existing = lines.get(productId);
        if (existing == null) throw new IllegalArgumentException("Not in the basket: " + productId);
        store(existing.withQuantity(quantityMilli));
    }

    public void remove(UUID productId) { lines.remove(productId); }

    public void clear() { lines.clear(); }

    /** Puts back the lines of a sale that was saved for retry, exactly as they were submitted. */
    public void replaceWith(List<BasketLine> savedLines) {
        lines.clear();
        savedLines.forEach(line -> lines.put(line.productId(), line));
    }

    public List<BasketLine> lines() { return List.copyOf(lines.values()); }

    public boolean isEmpty() { return lines.isEmpty(); }

    public long total() { return totalOf(lines.values()); }

    /** Each line is rounded first, then the rounded lines are added, as SaleService does. */
    public static long totalOf(Collection<BasketLine> lines) {
        long total = 0L;
        for (BasketLine line : lines) total = Math.addExact(total, line.total());
        return total;
    }

    private void store(BasketLine line) {
        Map<UUID, BasketLine> changed = new LinkedHashMap<>(lines);
        changed.put(line.productId(), line);
        totalOf(changed.values());          // throws before anything changes if it is too large
        lines.put(line.productId(), line);
    }
}

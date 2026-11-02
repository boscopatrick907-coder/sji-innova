package cm.sji.innova.core.sale;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Effective state of a sale derived from its immutable event history.
 * The original {@link Sale} and its items remain unchanged when a sale is voided.
 */
public record SaleState(Sale sale, SaleStatus status, List<SaleEvent> events, Optional<SaleEvent> voidEvent) {
    public SaleState {
        Objects.requireNonNull(sale, "sale");
        Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        Objects.requireNonNull(voidEvent, "voidEvent");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Sale state must include at least the creation event");
        }
        if ((status == SaleStatus.VOIDED) != voidEvent.isPresent()) {
            throw new IllegalArgumentException("Voided status must match presence of voidEvent");
        }
    }

    public boolean voided() {
        return status == SaleStatus.VOIDED;
    }
}


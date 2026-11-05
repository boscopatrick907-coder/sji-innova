package cm.sji.innova.core.product;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Organization only: price and stock belong to the sellable Product variants. */
public record ProductFamily(UUID id, UUID shopId, String name, String category, Instant createdAt) {
    public ProductFamily {
        Objects.requireNonNull(id); Objects.requireNonNull(shopId); Objects.requireNonNull(createdAt);
        name = Objects.requireNonNull(name).strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Family name must not be blank");
        category = category == null || category.isBlank() ? null : category.strip();
    }
}

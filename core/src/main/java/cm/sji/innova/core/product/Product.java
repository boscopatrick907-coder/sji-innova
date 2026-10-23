package cm.sji.innova.core.product;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Price is a whole number of FCFA, never floating point. */
public record Product(UUID id, UUID shopId, String name, long unitPrice,
                      Instant createdAt, Instant updatedAt, long version) {
    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = name.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Product name must not be blank");
        if (unitPrice < 0) throw new IllegalArgumentException("Price must not be negative");
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
    }
}

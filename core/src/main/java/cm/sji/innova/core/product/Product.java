package cm.sji.innova.core.product;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Prices are whole FCFA. A null costPrice means the buying price is unknown. */
public record Product(UUID id, UUID shopId, String name, long unitPrice,
                      Instant createdAt, Instant updatedAt, long version,
                      String modelReference, Long costPrice, String category,
                      String unit, Map<String, String> specifications) {
    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = name.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Product name must not be blank");
        if (unitPrice < 0) throw new IllegalArgumentException("Selling price must not be negative");
        if (costPrice != null && costPrice < 0) throw new IllegalArgumentException("Buying price must not be negative");
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        modelReference = optionalText(modelReference);
        category = optionalText(category);
        if (!java.util.List.of("piece", "kg", "litre").contains(unit)) {
            throw new IllegalArgumentException("Unit must be piece, kg or litre");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        Objects.requireNonNull(specifications, "specifications").forEach((key, value) -> {
            String normalizedKey = Objects.requireNonNull(key, "Specification name").strip();
            String normalizedValue = Objects.requireNonNull(value, "Specification value").strip();
            if (normalizedKey.isEmpty() || normalizedValue.isEmpty()) {
                throw new IllegalArgumentException("Specification names and values must not be blank");
            }
            if (normalized.putIfAbsent(normalizedKey, normalizedValue) != null) {
                throw new IllegalArgumentException("Duplicate specification: " + normalizedKey);
            }
        });
        specifications = Collections.unmodifiableMap(normalized);
    }

    /** Existing callers can still create a basic product. */
    public Product(UUID id, UUID shopId, String name, long unitPrice,
                   Instant createdAt, Instant updatedAt, long version) {
        this(id, shopId, name, unitPrice, createdAt, updatedAt, version,
                null, null, null, "piece", Map.of());
    }

    private static String optionalText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}

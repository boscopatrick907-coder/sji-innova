package cm.sji.innova.core.sale;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable append-only lifecycle event for a sale.
 * For {@link Type#VOIDED}, {@code id} is the client-generated void operation ID
 * and {@code performedBy} is the caller-supplied actor UUID.
 * For {@link Type#CREATED}, {@code performedBy} may be {@code null} when recorded
 * before authentication exists or backfilled from a schema-v4 sale.
 */
public record SaleEvent(UUID id, UUID saleId, UUID shopId, Type type,
                        UUID deviceId, UUID performedBy, String reason, Instant createdAt) {
    public enum Type {
        CREATED("created"),
        VOIDED("voided");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Type fromCode(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }
    }

    public SaleEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(createdAt, "createdAt");
        reason = Objects.requireNonNull(reason, "reason").strip();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("Sale event reason must not be blank");
        }
        if (type == Type.VOIDED && performedBy == null) {
            throw new IllegalArgumentException("Void event requires a performedBy actor ID");
        }
    }

    /** Deterministic event ID for a sale's initial creation event. */
    public static UUID createdEventId(UUID saleId) {
        Objects.requireNonNull(saleId, "saleId");
        return UUID.nameUUIDFromBytes(("sale-created:" + saleId).getBytes(StandardCharsets.UTF_8));
    }
}


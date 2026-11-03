package cm.sji.innova.core.sale;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable append-only audit entry for sensitive sale actions such as {@code VOID_SALE}. */
public record SaleAuditRecord(UUID id, UUID shopId, UUID deviceId, UUID performedBy,
                              String action, String targetTable, UUID targetId, UUID eventId,
                              String reason, String oldValue, String newValue, Instant createdAt) {
    public SaleAuditRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(performedBy, "performedBy");
        action = Objects.requireNonNull(action, "action").strip();
        targetTable = Objects.requireNonNull(targetTable, "targetTable").strip();
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(eventId, "eventId");
        reason = Objects.requireNonNull(reason, "reason").strip();
        oldValue = Objects.requireNonNull(oldValue, "oldValue").strip();
        newValue = Objects.requireNonNull(newValue, "newValue").strip();
        Objects.requireNonNull(createdAt, "createdAt");
        if (action.isEmpty() || targetTable.isEmpty() || reason.isEmpty()
                || oldValue.isEmpty() || newValue.isEmpty()) {
            throw new IllegalArgumentException("Audit fields must not be blank");
        }
    }
}


package cm.sji.innova.core.sale;

import cm.sji.innova.core.stock.StockMovement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Result of voiding a cash sale, including the void event, compensating stock movements, and audit record. */
public record VoidSaleResult(Sale sale, SaleEvent voidEvent, List<StockMovement> restoredMovements,
                             SaleAuditRecord auditRecord, boolean replayed) {
    public VoidSaleResult {
        Objects.requireNonNull(sale, "sale");
        Objects.requireNonNull(voidEvent, "voidEvent");
        restoredMovements = List.copyOf(Objects.requireNonNull(restoredMovements, "restoredMovements"));
        Objects.requireNonNull(auditRecord, "auditRecord");
        if (voidEvent.type() != SaleEvent.Type.VOIDED) {
            throw new IllegalArgumentException("VoidSaleResult requires a VOIDED sale event");
        }
        if (restoredMovements.isEmpty()) {
            throw new IllegalArgumentException("VoidSaleResult must contain at least one restored stock movement");
        }
    }

    public UUID operationId() {
        return voidEvent.id();
    }

    public UUID saleId() {
        return sale.id();
    }

    public SaleStatus status() {
        return SaleStatus.VOIDED;
    }
}


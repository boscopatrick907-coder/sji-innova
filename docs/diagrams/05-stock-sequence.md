# UML sequence: receive stock

**Scope:** Current receipt behavior. Snapshot: main `5ff771a`, 29 October 2026.

This shows the order of messages when receiving 10 pieces. The caller can currently be a demo/test; the stock screen is still planned.

```mermaid
sequenceDiagram
    actor Caller as Caller (future stock screen)
    participant Service as StockService
    participant Repo as SqliteStockRepository
    participant DB as SQLite
    Caller->>Service: receive(operationId, shop, product, device, 10000, reason)
    Service->>Service: Validate IDs, reason and positive quantity
    Service->>Repo: record(StockMovement)
    Repo->>DB: BEGIN IMMEDIATE
    Repo->>DB: Find product in requested shop, read current stock
    Repo->>DB: Look up operationId
    alt Same operation ID and matching details
        DB-->>Repo: Original movement
        Repo->>DB: COMMIT
        Repo-->>Caller: Original movement + current stock, replayed=true
    else Reused ID with changed details or invalid product/unit
        Repo->>DB: ROLLBACK
        Repo-->>Caller: Error, no stock change
    else New valid delivery
        Repo->>Repo: Check unit and integer overflow
        Repo->>DB: INSERT delivery movement
        Repo->>DB: UPDATE product current_stock_milli
        alt Writes succeed
            Repo->>DB: COMMIT
            Repo-->>Caller: Movement + updated stock, replayed=false
        else A write fails
            Repo->>DB: ROLLBACK
            Repo-->>Caller: Error, movement and stock change both undone
        end
    end
```

10000 stored units = 10 pieces. The operation ID must be reused only for a retry of the same request. Replay returns **current** stock, which may include later movements. Validation is shared between the record/service and repository; errors can occur before the transaction starts. Shop filtering is not yet user authentication. Return arrows to Caller are simplified: results propagate through the service.

[SVG image](images/05-stock-sequence.svg) · [Editable Mermaid source](sources/05-stock-sequence.mmd) · [Diagram index](README.md)

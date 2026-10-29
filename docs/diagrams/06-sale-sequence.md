# UML sequence: offline cash sale

**Scope:** Current cash checkout, including known limitations. Snapshot: main `5ff771a`, 29 October 2026.

This sequence follows the implemented transaction order. It does not invent a sale-event table or a printed/PDF receipt feature.

```mermaid
sequenceDiagram
    actor Caller as Caller (future checkout screen)
    participant Service as SaleService
    participant Products as ProductRepository
    participant Repo as SqliteSaleRepository
    participant DB as SQLite
    Caller->>Service: makeCashSale(saleId, shop, device, receiptNumber, cart)
    Service->>Products: findByShop(shop)
    Products-->>Service: Product prices and units
    Service->>Service: Build item snapshots and calculate whole-FCFA totals
    Service->>Repo: recordSale(sale)
    Repo->>DB: BEGIN IMMEDIATE
    Repo->>DB: Look up existing saleId
    alt Existing sale in same shop
        Repo->>DB: COMMIT
        Repo-->>Caller: Stored sale, replayed=true
        Note over Repo,Caller: Known gap: changed request details are not compared
    else New sale
        loop Each sale item
            Repo->>DB: Read product unit and current stock
            Repo->>Repo: Check whole pieces and sufficient stock
            Repo->>DB: INSERT negative sale stock movement
            Repo->>DB: UPDATE cached stock
        end
        Repo->>DB: INSERT sales header
        Repo->>DB: INSERT sale_items
        alt All writes succeed
            Repo->>DB: COMMIT
            Repo-->>Caller: Stored sale, replayed=false
        else Validation or write fails
            Repo->>DB: ROLLBACK
            Repo-->>Caller: Error, no partial sale or stock deduction
        end
    end
```

The implementation truncates each fractional line subtotal using integer division; an explicit rounding policy remains to be agreed. A changed retry can return the old sale without rejecting changed details. Product lookup/calculation happens before the write transaction. The diagram’s failure branch summarizes errors anywhere inside the transaction; execution stops at the first failure. A receipt **number** is stored, but receipt document generation is not implemented.

[SVG image](images/06-sale-sequence.svg) · [Editable Mermaid source](sources/06-sale-sequence.mmd) · [Diagram index](README.md)

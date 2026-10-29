# UML domain class diagram

**Scope:** Current Java records (selected fields). Snapshot: main `5ff771a`, 29 October 2026.

A class/record describes information the application passes around. Composition means an object contains other objects; it does not automatically imply a database foreign key.

```mermaid
classDiagram
    direction TB
    class Product {
        <<record>>
        UUID id
        UUID shopId
        String name
        long unitPrice
        Long costPrice
        String modelReference
        String category
        String unit
        Map specifications
        Instant createdAt
        Instant updatedAt
        long version
    }
    class StockMovement {
        <<record>>
        UUID id
        UUID shopId
        UUID productId
        UUID deviceId
        Type type
        long quantityMilli
        String reason
        Instant createdAt
        sameOperation(other) boolean
    }
    class MovementType {
        <<enumeration>>
        DELIVERY
        ADJUSTMENT
        SALE
        VOID
    }
    class StockLevel {
        <<record>>
        UUID shopId
        UUID productId
        String productName
        String unit
        long quantityMilli
        long minimumMilli
        lowStock() boolean
    }
    class StockResult {
        <<record>>
        boolean replayed
    }
    class Sale {
        <<record>>
        UUID id
        UUID shopId
        String receiptNumber
        UUID deviceId
        PaymentMethod paymentMethod
        long totalAmount
        Instant createdAt
    }
    class SaleItem {
        <<record>>
        UUID id
        UUID saleId
        UUID productId
        String productName
        String unit
        long quantityMilli
        long unitPrice
        long subtotal
    }
    class SaleItemRequest {
        <<record>>
        UUID productId
        long quantityMilli
        Long customUnitPrice
    }
    class SaleResult {
        <<record>>
        boolean replayed
    }
    class PaymentMethod {
        <<enumeration>>
        CASH
        MOMO
        CREDIT
    }
    StockMovement --> MovementType : type
    StockResult *-- "1" StockMovement : movement
    StockResult *-- "1" StockLevel : stock
    Sale *-- "1..*" SaleItem : items
    Sale --> PaymentMethod : paymentMethod
    SaleResult *-- "1" Sale : sale
    StockMovement ..> Product : productId
    StockLevel ..> Product : productId
    SaleItem ..> Product : productId
    SaleItemRequest ..> Product : productId
```

`MovementType` is the nested Java enum `StockMovement.Type`; the diagram gives it a unique display name. `unitPrice` means **selling price** and nullable `costPrice` means **buying price**. Cash checkout is implemented; enum values MOMO/CREDIT and movement type VOID do not establish completed payment/void services. Product specifications are a Map in Java and a separate table in SQLite. Cached stock is exposed by StockLevel, not by the Product record. Quantities are thousandths; prices are whole FCFA.

[SVG image](images/03-domain-classes.svg) · [Editable Mermaid source](sources/03-domain-classes.mmd) · [Diagram index](README.md)

# Database ER diagram: proposed full MVP

**Scope:** PLANNED logical model, not an implemented schema or migration. Snapshot: main `5ff771a`, 29 October 2026.

This conceptual view extends the current product/specification model with the future features in the revised plan. Fields are selected examples, not complete SQL definitions. Review the design with Emmanuel before implementation.

```mermaid
erDiagram
    shops ||--o{ users : employs
    shops ||--o{ devices : registers
    shops ||--o{ products : owns
    shops ||--o{ customers : serves
    shops ||--o{ sales : records
    shops ||--o{ audit_log : records
    shops ||--o{ authorized_phones : permits
    users ||--o{ sessions : authenticates
    users ||--o{ sales : performs
    users ||--o{ sale_events : creates
    users ||--o{ audit_log : performs
    devices ||--o{ stock_movements : originates
    devices ||--o{ sales : originates
    devices ||--o{ sale_events : originates
    products ||--o{ product_specifications : describes
    products ||--o{ stock_movements : tracks
    products ||--o{ sale_items : appears_in
    sales ||--|{ sale_items : contains
    sales ||--|{ sale_events : changes_via
    customers |o--o{ sales : optional_customer
    customers ||--o{ debt_payments : repays
    sales |o--o{ debt_payments : optional_allocation
    shops {
        UUID id PK
        string name
        string currency
        boolean vat_enabled
    }
    users {
        UUID id PK
        UUID shop_id FK
        string role
        string password_hash
    }
    devices {
        UUID id PK
        UUID shop_id FK
        string name
    }
    products {
        UUID id PK
        UUID shop_id FK
        string name
        long unit_price
        long cost_price
        long current_stock_milli
        long version
    }
    product_specifications {
        UUID product_id PK,FK
        string name PK
        string value
    }
    stock_movements {
        UUID id PK
        UUID product_id FK
        UUID device_id FK
        string type
        long quantity_milli
        UUID ref_id "planned polymorphic reference - not a FK"
    }
    sales {
        UUID id PK
        UUID shop_id FK
        UUID user_id FK
        UUID device_id FK
        UUID customer_id FK "nullable"
        string payment_type
        long total
        string status "derived cache"
    }
    sale_items {
        UUID id PK
        UUID sale_id FK
        UUID product_id FK
        long quantity_milli
        long unit_price
        string tax_rate_snapshot
    }
    sale_events {
        UUID id PK
        UUID sale_id FK
        UUID user_id FK
        UUID device_id FK
        string type "created momo_confirmed paid_in_full voided"
        string reason
    }
    customers {
        UUID id PK
        UUID shop_id FK
        string name
        string phone
    }
    debt_payments {
        UUID id PK
        UUID customer_id FK
        UUID sale_id FK "nullable"
        long amount
    }
    sessions {
        UUID id PK
        UUID user_id FK
        string token_hash
        datetime expires_at
    }
    authorized_phones {
        UUID id PK
        UUID shop_id FK
        string phone
        string pin_hash
    }
    audit_log {
        UUID id PK
        UUID shop_id FK
        UUID user_id FK
        string action
        UUID target_id "polymorphic - not a FK"
    }
```

This is a **logical target**: UUID/datetime types indicate meaning, not the SQLite TEXT representation. Tenant/shop identifiers, timestamps and catalog versions are omitted from some boxes for readability; they must be specified consistently before SQL is written. Optional tax uses exact agreed arithmetic, not floating-point assumptions.

Transactional records (sales, items, sale events, movements, repayments, audit) are intended to be append-only; state changes become events. Catalog changes require explicit conflict/version rules. Keep future sync delivery status outside immutable business records. Relationships do not by themselves enforce same-shop consistency: composite keys/service checks and an authentication design are still needed.

A polymorphic `ref_id` is not an enforced foreign key. Repayment allocation, cancellation rules, device sync ordering and the physical local/cloud schemas need further design. Parent/child product-variant grouping is not included: each sellable combination remains a product. Optional cash reconciliation and other Should/Could features are deliberately outside this Must-model overview.

[SVG image](images/08-database-target.svg) · [Editable Mermaid source](sources/08-database-target.mmd) · [Diagram index](README.md)

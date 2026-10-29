# Database ER diagram: actual SQLite schema v4

**Scope:** Implemented physical database. Snapshot: main `5ff771a`, 29 October 2026.

Generated against an empty database initialized by the Java migration runner. Every table, column, primary key and drawn foreign key below exists in schema v4.

```mermaid
erDiagram
    products ||--o{ product_specifications : has
    products ||--o{ stock_movements : has
    products ||--o{ sale_items : referenced_by
    sales ||--o{ sale_items : contains
    product_specifications {
        TEXT product_id PK,FK
        TEXT name PK
        TEXT value
    }
    products {
        TEXT id PK
        TEXT shop_id "UUID text - no shop FK yet"
        TEXT name
        INTEGER unit_price "selling price FCFA"
        TEXT created_at "ISO-8601 timestamp"
        TEXT updated_at "ISO-8601 timestamp"
        INTEGER version
        TEXT model_reference "nullable"
        INTEGER cost_price "nullable buying price FCFA"
        TEXT category "nullable"
        TEXT unit "piece kg litre"
        INTEGER current_stock_milli "cached thousandths"
        INTEGER min_stock_milli "low stock threshold"
    }
    sale_items {
        TEXT id PK
        TEXT sale_id FK
        TEXT product_id FK
        TEXT product_name "sale-time snapshot"
        TEXT unit "piece kg litre"
        INTEGER quantity_milli "signed movement or positive sale quantity"
        INTEGER unit_price "selling price FCFA"
        INTEGER subtotal
    }
    sales {
        TEXT id PK
        TEXT shop_id "UUID text - no shop FK yet"
        TEXT receipt_number "not unique in schema"
        TEXT device_id "UUID text - no device FK yet"
        TEXT payment_method "cash momo credit"
        INTEGER total_amount
        TEXT created_at "ISO-8601 timestamp"
    }
    stock_movements {
        TEXT id PK
        TEXT shop_id "UUID text - no shop FK yet"
        TEXT product_id FK
        TEXT device_id "UUID text - no device FK yet"
        TEXT type "delivery adjustment sale void"
        INTEGER quantity_milli "signed movement or positive sale quantity"
        TEXT reason
        TEXT created_at "ISO-8601 timestamp"
    }
```

**How to read it:** one product can have zero or many specification rows, stock movements, or sale items. Every such child row references one product. A sale can have zero or many items at the **database constraint** level; Java’s Sale constructor requires at least one. `product_specifications` has a composite primary key `(product_id, name)`.

**Important boundaries:**

- There are exactly five application tables. No shops, devices, users, customers, debts or sale_events table exists yet. `shop_id` and `device_id` are UUID text fields, not foreign keys to hidden tables.
- There is **no sale_id foreign key in stock_movements**. Sales currently identify a movement using its reason text; the diagram does not invent a durable relational link.
- UUIDs and timestamps are stored as TEXT; prices/quantities are INTEGER. `cost_price`, `model_reference` and `category` are nullable. `unit_price` is selling price, not buying price.
- Delete rules: product specifications CASCADE; sale-item references to products/sales RESTRICT; stock movement product FK uses SQLite’s default NO ACTION. Foreign keys are enabled by each application connection.
- Update/delete triggers make stock_movements append-only. Sales and sale_items do **not** currently have equivalent append-only triggers. Receipt numbers are not UNIQUE.
- Current-stock/minimum default to 0; unit defaults to piece. Quantity thousandths and whole-FCFA price rules apply. Some type/range rules are CHECK constraints and some are service validations.

See the [exact SQL snapshot](database/schema-v4.sql) for constraints, indexes and triggers. It is documentation for a fresh empty database, not a replacement for migration code.

[SVG image](images/07-database-current.svg) · [Editable Mermaid source](sources/07-database-current.mmd) · [Diagram index](README.md)

# UML service and repository class diagram

**Scope:** Current code; signatures shortened for readability. Snapshot: main `5ff771a`, 29 October 2026.

Services are the entry points for business operations. Repository interfaces describe storage operations; the SQLite classes implement them.

```mermaid
classDiagram
    direction LR
    class ProductService {
        create(...) Product
        list(shopId) List
    }
    class StockService {
        receive(...) StockResult
        adjust(...) StockResult
        current(shopId, productId) StockLevel
        list(shopId) List
        history(shopId, productId) List
        setMinimum(...) StockLevel
    }
    class SaleService {
        makeCashSale(...) SaleResult
        getSale(shopId, saleId) Optional
        listSales(shopId) List
    }
    class ProductRepository {
        <<interface>>
        insert(product) void
        findByShop(shopId) List
    }
    class StockRepository {
        <<interface>>
        record(movement) StockResult
        current(...) StockLevel
        list(shopId) List
        history(...) List
        setMinimum(...) StockLevel
    }
    class SaleRepository {
        <<interface>>
        recordSale(sale) SaleResult
        findById(...) Optional
        listByShop(shopId) List
    }
    class SqliteProductRepository
    class SqliteStockRepository
    class SqliteSaleRepository
    class SqliteDatabase {
        Path file
        SCHEMA_VERSION = 4
        open() Connection
        initialize() void
    }
    ProductService --> ProductRepository : injected
    StockService --> StockRepository : injected
    SaleService --> SaleRepository : injected
    SaleService --> ProductRepository : reads catalog
    ProductRepository <|.. SqliteProductRepository
    StockRepository <|.. SqliteStockRepository
    SaleRepository <|.. SqliteSaleRepository
    SqliteProductRepository --> SqliteDatabase
    SqliteStockRepository --> SqliteDatabase
    SqliteSaleRepository --> SqliteDatabase
```

Cash sales reduce stock inside SqliteSaleRepository’s own transaction. They do **not** commit a sale and then call StockService as a separate operation. Desktop code should inject these services and run database work off Swing’s Event Dispatch Thread. Full signatures are in the source and [stock](../backend/STOCK.md)/[sales](../backend/SALES.md) guides.

[SVG image](images/04-services-repositories.svg) · [Editable Mermaid source](sources/04-services-repositories.mmd) · [Diagram index](README.md)

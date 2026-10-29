# Application architecture

**Scope:** Implemented foundation + clearly marked planned components. Snapshot: main `5ff771a`, 29 October 2026.

Boxes marked CURRENT are in the repository. Dashed connections describe future integration, not running services.

```mermaid
flowchart TB
    subgraph Local["Shop computer — local / offline"]
        UI["CURRENT: Swing + FlatLaf entry point<br/>Product/stock/sales screens still planned"]
        Tools["CURRENT: demo tools and integration tests"]
        Services["CURRENT: ProductService<br/>StockService · SaleService"]
        Repos["CURRENT: JDBC repositories"]
        DB[("CURRENT: SQLite schema v4")]
        UI -. "planned screen wiring" .-> Services
        Tools --> Services
        Services --> Repos --> DB
        Sync["PLANNED: background sync client"]
        Services -. "future events" .-> Sync
    end
    subgraph Cloud["Planned cloud components"]
        API["Spring Boot API<br/>authentication + shop isolation"]
        MySQL[(MySQL)]
        API --> MySQL
    end
    Sync -. "authenticated HTTPS + retry" .-> API
    PWA["PLANNED: owner PWA"] -. "owner-scoped access" .-> API
    N8N["PLANNED: n8n workflows"] -. "restricted API access" .-> API
    WA["PLANNED: WhatsApp"] -.-> N8N
    classDef future fill:#fff4d6,stroke:#a77017,stroke-dasharray:5 5
    class Sync,API,MySQL,PWA,N8N,WA future
```

The desktop entry point exists, but it is still a placeholder window. Core services execute locally; they are not separate network servers. SQLite JDBC is bundled with the application. MySQL is a future cloud store, not a replacement for the offline database.

[SVG image](images/01-architecture.svg) · [Editable Mermaid source](sources/01-architecture.mmd) · [Diagram index](README.md)

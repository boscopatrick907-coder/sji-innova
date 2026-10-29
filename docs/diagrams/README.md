# Cella UML and database diagrams

These diagrams are documentation, not new application features. They are based
on **main `5ff771a`**, assessed **29 October 2026**, with actual database metadata
checked against an empty schema-v4 database initialized by the Java code.
No shop data, credentials or live database files are included.

## Pick the diagram you need

| Diagram | What it explains | Scope |
|---|---|---|
| [Application architecture](01-architecture.md) | Where desktop/core/database and future cloud fit | Implemented foundation + clearly marked planned components |
| [UML use-case diagram](02-use-cases.md) | What each kind of user should be able to do | Target workflow; backend status labelled |
| [UML domain class diagram](03-domain-classes.md) | Information carried by the existing Java records | Current Java records (selected fields) |
| [UML service and repository class diagram](04-services-repositories.md) | Which service calls which storage interface | Current code; signatures shortened for readability |
| [UML sequence: receive stock](05-stock-sequence.md) | How a receipt safely changes stock | Current receipt behavior |
| [UML sequence: offline cash sale](06-sale-sequence.md) | How cash checkout saves a sale and reduces stock | Current cash checkout, including known limitations |
| [Database ER diagram: actual SQLite schema v4](07-database-current.md) | The five real tables and their enforced relationships | Implemented physical database |
| [Database ER diagram: proposed full MVP](08-database-target.md) | Future shops/users/customers/debts/events design | PLANNED logical model, not an implemented schema or migration |

## How to view and share

- Open any `.md` page on GitHub: Mermaid diagrams render inline.
- The UML use-case page embeds its SVG. Click any SVG for a standalone image.
- Download an SVG from `images/` for a report, slide or browser. SVG stays sharp
  when resized. Use browser print/save PDF if a PDF copy is needed.
- Edit `.mmd` files for Mermaid or `.puml` for PlantUML. Keep the corresponding
  Markdown diagram and exported image in sync; GitHub does not regenerate images.

## Read the notation

- **UML class diagram:** records/classes and their associations. Dashed arrows
  indicate dependencies; a hollow triangle means interface implementation.
- **UML sequence diagram:** messages ordered from top to bottom; `alt` means
  alternative behavior and `loop` repeats for each item.
- **ER diagram:** database tables and relationships. `PK` is primary key and
  `FK` is foreign key. The crow's foot means “many”; a circle allows zero.
- **Current vs target:** the current ER diagram is physical schema v4. The target
  ER diagram is a proposed logical MVP design, not an existing MySQL database.

## Boundaries and known gaps

Product/stock/cash-sale services exist; desktop forms, customer/debt storage,
login/permissions, backups, sync, cloud API, PWA and WhatsApp workflows are not
implemented in this snapshot. Payment enum values do not prove complete credit
or Mobile Money behavior. Schema v4 has no sale_events table or sale-to-movement
foreign key. The sale retry/detail-comparison and fractional-FCFA rounding issues
identified during review remain open in this snapshot. Diagrams do not hide them.

Sources: [project README](../../README.md), [current master](../../SJI_INNOVA_PROJECT.md),
[revised v0.3 plan](../../SJI_INNOVA_PROJECT_v03.md), [stock guide](../backend/STOCK.md),
[sales guide](../backend/SALES.md), and `core/src/main/java/cm/sji/innova/core/`.
Where project documents differ, code determines the current diagram; future
scope comes from confirmed master decisions and the revised v0.3 event model.

## Regenerate exported images

Authoring tools used: Mermaid engine 11.17.2 (installed with Mermaid CLI 11.12.0)
and PlantUML 1.2025.10. They are
optional documentation tools, not Java application dependencies. Do not modify
application Maven manifests just to render documentation.

With Mermaid CLI installed separately:

```sh
mmdc -i docs/diagrams/sources/07-database-current.mmd -o docs/diagrams/images/07-database-current.svg -b white
```

Repeat for other `.mmd` files. A compatible Chromium browser is required by the
renderer. For the use-case diagram, with PlantUML downloaded separately:

```sh
java -jar /path/to/plantuml.jar -tsvg docs/diagrams/sources/02-use-cases.puml
```

PlantUML writes next to its source by default; move the resulting SVG into
`images/`. Recheck actual schema/relationships and update the snapshot reference
when features change. The SQL snapshot in `database/schema-v4.sql` documents an
empty schema; always use Java migrations to upgrade existing user databases.

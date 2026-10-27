# Shared AI context: Cella desktop pilot

## Purpose and source of truth

Cella is offline-first inventory, sales and customer-credit software for
Cameroonian shops. Desktop: Java 21, Swing and FlatLaf. Local database: SQLite
via JDBC. Future cloud: Spring Boot and MySQL. Future dashboard: HTML/CSS/Chart.js
PWA. Future WhatsApp integration: n8n. Do not replace the selected stack.

Read README.md, SJI_INNOVA_PROJECT.md, SJI_INNOVA_PROJECT_v03.md and the current
code. These documents have diverged: the master confirms Cella, Spring Boot and
Emmanuel as backend/database lead; the versioned v0.3 describes revised events,
optional VAT, and Must/Should/Could scope. Do not resurrect older sync rules or
claim open framework decisions are still undecided. Escalate unresolved conflicts
to Patrick and Emmanuel rather than rewriting the plans yourself.

Snapshot updated with the stock-management implementation. Verify the branch
and current main before relying on these additions. Available on this branch:

- core Product, ProductService, ProductRepository, SqliteDatabase and
  SqliteProductRepository; schema version 3 migrates older databases.
- Product creation and shop-scoped listing; no product edit/delete yet.
- Product fields: id/shopId UUIDs, name, unitPrice (SELLING price), optional
  costPrice (BUYING price), optional modelReference/category, unit, specifications,
  timestamps and catalog version.
- Units are `piece`, `kg`, `litre`. Prices are nonnegative whole FCFA (`long`).
  Buying price may be unknown (`null`) or zero; those mean different things.
- Specifications are Map<String,String>, e.g. color=blue, size=M. Each sellable
  variant is a separate product ID. There is no parent-variant feature yet.
- DesktopApplication is a placeholder Swing entry point. It supports
  --smoke-test and --product-demo PATH; preserve those modes.
- Baseline: 23 SQLite integration tests + 1 desktop startup test. Recount from
  current test reports instead of treating this historical count as mandatory.
- StockService and SqliteStockRepository implement local receipts, adjustments,
  history, current stock and low-stock thresholds. Read docs/backend/STOCK.md for
  the contract, retry IDs and integer-thousandths quantities. No stock UI yet.
- SaleService and SqliteSaleRepository implement atomic cash sales, item snapshotting,
  and stock deduction. Read docs/backend/SALES.md for the contract. No sales UI yet.
- Authentication, printable receipts and sync are not implemented.
  Do not invent those as working features.

## Responsibilities

Patrick: project coordination and integration. Emmanuel: database/backend and
service contracts. Charles: desktop coordination, shell/navigation/shared UI.
Willy: primary Products UI developer, later sales UI. Pascal: translations,
smaller supporting tasks and hands-on QA. AI tool choice does not change ownership.

Desktop work belongs under desktop/. Do not change core/, SQL migrations, Maven
configuration, CI or master plans unless a task explicitly authorizes it and the
responsible owner agrees. Report missing backend operations with the required
input/output; do not work around them with SQL in a button handler. Do not add
React, JavaFX, Electron, a new database, or extra frameworks for this pilot.

## Proposed desktop integration contract

These are NEW interfaces/classes to implement, not claims about existing code.
Inspect current main first; reuse an equivalent contract if Charles has already
merged it, and coordinate changes before editing shared files.

- Charles owns DesktopApplication and shell/context classes in
  `cm.sji.innova.desktop.shell`.
- Willy owns `cm.sji.innova.desktop.products.ProductPanel`, a JPanel whose public
  constructor accepts `(ProductService products, UUID shopId, ResourceBundle messages)`.
- Charles constructs/injects the real ProductService and one stable shop UUID;
  he integrates the panel after Willy's PR. Until then show an honest placeholder.
- Pascal owns UTF-8 resource bundles at
  `desktop/src/main/resources/cm/sji/innova/desktop/i18n/messages_en.properties`
  and `messages_fr.properties`. Bundle base name:
  `cm.sji.innova.desktop.i18n.messages`.
- For the first milestone select French or English at startup using a documented
  option or configuration. Live language switching is a later task.
- No PR should require an unmerged class to compile: use a placeholder until the
  dependency merges. Coordinate any new translation keys with Pascal.

## Database and application context

Use SqliteDatabase.initialize() before repositories are used. Initialization and
DB calls happen off Swing's Event Dispatch Thread (EDT); that thread paints the
UI and processes clicks. Use SwingWorker for slow work, then update controls on
the EDT. Surface failures; do not swallow exceptions or falsely report success.

Charles owns the local development bootstrap: configurable data directory,
a stable database path and a persisted local shop UUID. A documented development
shop context is acceptable until real shop registration/auth exists; label it
as such. Never generate a new shop UUID each launch, silently change the database
path, delete databases to fix errors, or seed fake products in the normal launch.
Use disposable files for tests. SQLite is embedded; no separate installation.

Call ProductService.create with the detailed overload, and ProductService.list
for listing. Read actual signatures. Do not generate a replacement repository.
Do not calculate/update stock, create custom identity or alter catalog versions
in UI code. Future customer API/WhatsApp responses must exclude buying prices.
This pilot has no login/authorization: do not call it secured or production ready.

## Desktop behavior and validation

Use labelled, keyboard-accessible fields; clear save/cancel/retry behavior;
disabled duplicate submission while saving; and useful empty/error/loading states.
Validate whole-FCFA input without floating point. Blank buying price becomes
null, not 0. Store canonical unit codes while translating display labels.
Show specification rows as editable name/value pairs, not comma-separated text
that loses values containing commas. Reject blank or duplicate trimmed names.
Core validation remains authoritative. Do not silently drop failed input.

## Git and AI working rules

Explain what you are changing and why in beginner-friendly language. Begin with
`git status` and read local AGENTS.md instructions if present. Preserve unfinished
user work. Work on the role's small feature branch from current main. Existing
cloud tasks are already isolated: use the existing checkout; create no worktree
unless explicitly requested. On personal PCs each collaborator uses their own
checkout. Do not reset, force-push, or modify someone else's branch.

Commit/push only when the human requests it. Never push directly to main, merge
PRs, or change repository settings automatically. Review generated code with the
human and make sure they can explain it. Do not paste credentials, logs containing
secrets, live databases, target/ outputs or IDE files into Git.

## Verification and completion

Run the full wrapper `clean verify` command from the repository root; preserve
its exit status. Run the packaged --smoke-test command from README.md. Add useful
tests for new behavior; do not replace real persistence tests with passing mocks.
Use temporary DB files and no production data. Run the actual window on Windows
and test keyboard navigation, validation, failure messages and restart persistence.
Report headless checks and visible GUI checks separately. If there is no display,
state exactly what remains for a collaborator to verify.

PR description: user-visible behavior, service dependencies, how tested, screenshot
when possible, and limitations. Do not mark stock, checkout, auth or cloud complete
because there is a navigation placeholder. Stop after the assigned milestone.

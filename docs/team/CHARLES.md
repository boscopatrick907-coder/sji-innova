# Charles — Cash sales screen and shared desktop wiring

Current assignment: 4 November 2026. This replaces the shell/design assignment.
Patrick chooses and implements Products. Willy owns Stock. Keep the existing
mockups as design references; do not resume a competing Products implementation.

## Start

Read README.md, docs/team/CONTEXT.md, docs/team/README.md and
[the sales service contract](../backend/SALES.md). Inspect SaleService,
SaleItemRequest, CashSaleRequest, AppContext and AppContextLoader on current main.
With a clean checkout on updated main, create `desktop/cash-sales-screen`.
Own `desktop/src/main/java/cm/sji/innova/desktop/sales/`, related tests and shared
shell/startup/navigation files. Do not modify Patrick's ProductPanel or Willy's
stock package without coordination.

## Milestone 0 — shared context, small PR first

AppContext currently injects ProductService only. Supply StockService and
SaleService from the SAME initialized database, off the EDT. Coordinate constructor
changes with Willy. Establish and persist a stable development device UUID, reused
across launches; do not confuse it with a shop/user identity. Preserve existing
shop-ID safety, data-dir overrides, failure/retry behavior and smoke readiness.
An existing shop must upgrade without losing its products or changing shop ID.
Document the device file's creation/recovery behavior and test ordinary restart
and malformed-file handling without touching live data. No login is implied.

Agree a panel contract: StockPanel injected with stock/catalog services,
shop/device IDs and messages; SalesPanel injected with sales/catalog/stock
services, the same IDs and messages. Supply real services, never nonexistent APIs.
Merge this independent wiring PR before the screens depend on it. Keep missing
screens honestly unavailable until their panels merge. Patrick handles PR review.

## Milestone 1 — offline cash checkout

- Search/select products and build a basket. Add, change quantity and remove lines.
- Show unit, quantity, selling price, each line total and total in FCFA. Buying
  prices must not appear in checkout. Show stock for guidance; backend validation
  remains authoritative if another operation changes it.
- Use exact integer-thousandths quantities and HALF_UP whole-FCFA rounding PER
  LINE, then sum. Avoid floating point and check overflow. No discounts, custom
  price override or tax feature in this milestone.
- Confirm cash payment and call SaleService.makeCashSale once. Do NOT call
  StockService.adjust separately: the sale service atomically saves/deducts stock.
- Allocate one sale UUID and receipt reference per logical checkout. Preserve
  the full submitted request, IDs and reference while retrying an uncertain result;
  disable double-clicks. Never generate a fresh sale just because a timeout occurs.
  Keep pending checkout retry details across restart, without automatically
  submitting on startup; test recovery. Coordinate the local pending-request format
  with Emmanuel; do not alter core or introduce a second stock ledger.
- Preserve the basket on errors. Clearly resolve an uncertain request before
  discarding/changing it. Clear only after confirmed success; show the persisted
  receipt reference and totals. Ensure receipt references remain unique on restart.
- Refresh stock after success. Do not silently reprice an already submitted retry.
- Provide a simple persisted sales list/detail using existing SaleService queries,
  showing effective PAID/VOIDED state where applicable. No void action yet.

Acceptance: start with 10 pieces, sell 2, see 8 and one recorded sale, restart,
and see the same sale and balance. Repeated clicks/identical retry must not create
a second sale. Insufficient stock or a storage failure leaves a usable basket and
no partial sale. Test fractional rounding, overflow and uncertain-retry recovery.

## Boundaries

No Products redesign, stock receipt/correction form, receipt printing, credit,
Mobile Money, cloud sync, authentication or sale-void UI. Audited voids already
exist in core but are a separate future screen task. Ask Emmanuel about missing
service support; do not patch SQL from desktop code. Pascal coordinates bundles.

## Shared implementation rules

Use Java 21, Swing and FlatLaf. Inspect actual main and the service signatures;
reuse the existing SQLite database and stable shop identity. No SQL in panels,
no replacement repositories, schema changes, demo seeds or database deletion.
Run DB work off the EDT with SwingWorker; update controls on the EDT. Preserve
input on error, distinguish loading/error from zero, disable repeated submissions
and ignore stale background responses. No authentication is implemented.

Use exact quantities: 1000 internal units = 1 piece/kg/litre; pieces must be whole,
kg/litre allow at most three decimal places. Parse without floating point, reject
overflow and invalid values, and format French/English display labels separately
from canonical unit codes. Prices are whole FCFA; use the service's authoritative
results. Coordinate translation keys with Pascal; do not overwrite other people's
bundle edits. Match Patrick's chosen visual tokens without redesigning Products.

## Verification and delivery

Run the full wrapper clean verify and packaged headless smoke test from README.md.
Use temporary SQLite databases in meaningful persistence/integration tests. Keep
existing startup readiness and recovery checks working. Test the actual Windows
window for keyboard flow, resize, loading, failure/retry and reopen persistence;
report untested GUI checks honestly. Never use a live shop database for testing.

Deliver one reviewable PR per milestone with screenshots, branch/commit, tests,
known limitations and exact manual steps. Never push directly to main, force-push
or merge your own PR automatically. Push only when your human requests it.
Explain each step in simple language so the developer can explain the code.

## Prompt for your AI editor

> Read docs/team/CHARLES.md, docs/team/CONTEXT.md and docs/backend/SALES.md.
> My current assignment is shared service/device wiring, then offline cash Sales,
> not Products design. Inspect current code and explain each step simply. Deliver
> milestone 0 as a small PR first. Agree StockPanel injection with Willy. Preserve
> Patrick's Products files and all startup safety. Show tests and limitations.
> Do not push or merge without my request.

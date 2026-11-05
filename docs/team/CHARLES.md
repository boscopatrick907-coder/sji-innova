# Charles — build the visible cash Sales screen

Current assignment: 5 November 2026. Shared setup is merged; start with checkout.
Patrick chooses and implements Products. Willy owns Stock. Keep the existing
mockups as design references; do not resume a competing Products implementation.

## Start

Read README.md, docs/team/CONTEXT.md, docs/team/README.md and
[the sales service contract](../backend/SALES.md). Inspect SaleService,
SaleItemRequest, CashSaleRequest, AppContext and AppContextLoader on current main.
With a clean checkout on updated main, create a fresh branch such as `desktop/cash-checkout-ui`.
The old `desktop/cash-sales-screen` branch has already merged; do not build on
its outdated tip or reset unfinished work.
Own `desktop/src/main/java/cm/sji/innova/desktop/sales/`, related tests and shared
shell/startup/navigation files. Do not modify Patrick's `desktop/catalog/` classes or legacy ProductPanel, or Willy's
stock package without coordination.

## Already completed — do not repeat

PR #21 added startup error logging. PR #22 merged the shared StockService and
SaleService wiring plus a stable device UUID. AppContext now supplies products(),
stock(), sales(), shopId() and deviceId() on the same initialized data file.
Reuse them. Do not recreate device IDs, repositories or startup/bootstrap.

PR #23 added product families, automatic references and canonical `bag` units.
PR #24 connected the chosen family catalog design to the Java shell. Read
[the family contract](../backend/PRODUCT_FAMILIES.md) and
[the catalog implementation guide](../desktop/CATALOG.md).

## Your next visible delivery — a working Ventes page

Deliver SalesPanel and wire it into ShellPanel's navigation. A background service
alone is not completion: Patrick must see and use “Ventes” in the running app.
Use constructor injection from AppContext; the proposed contract is:

```java
new SalesPanel(context.sales(), context.products(), context.stock(),
        context.shopId(), context.deviceId(), messages.bundle())
```

This is a proposed new panel, not a class already present. Include the navigation
entry only when the actual panel exists. Preserve the Products entry and current
catalog, green sidebar and startup behavior. Coordinate ShellPanel edits with
Willy if his Stock PR is ready. No need to wait for his screen to develop checkout:
prepare stock through StockService in disposable test setup, never normal startup.

## Suggested PR sequence

1. Visible checkout: product search, basket, exact totals, confirmation, safe
   submission/retry recovery and navigation integration. A PR which enables real
   sales must include the durable pending-request safeguards below.
2. Persisted sales history/detail, if that keeps the first PR reviewable. Track it
   explicitly as outstanding; the complete assignment includes both deliveries.

## Milestone 1 — offline cash checkout

- Search/select sellable variants and build a basket. Match name, internal reference,
  family/model and characteristics so similar rice bags are distinguishable. Show
  reference, family/variant label and unit; a family itself cannot be sold.
- Add, change quantity and remove lines. Repeated addition of the same variant
  should have predictable behavior; keep submitted line structure fixed on retry.
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
  submitting on startup; test recovery. Document the local pending-request format and coordinate any missing contract
  with Patrick (Emmanuel is currently unavailable). Do not block on Emmanuel or
  alter core/introduce a second stock ledger without review. If pending state
  cannot be persisted safely, block confirmation rather than submit an untracked
  sale. Preserve saved data and show clear recovery options for damaged state.
- Preserve the basket on errors. Clearly resolve an uncertain request before
  discarding/changing it. Clear only after confirmed success; show the persisted
  receipt reference and totals. Ensure receipt references remain unique on restart.
- Refresh stock after success. Do not silently reprice an already submitted retry.
- Provide a simple persisted sales list/detail using existing SaleService queries,
  showing effective PAID/VOIDED state where applicable. No void action yet.

## Acceptance Patrick can demonstrate

- Launch the current app; the green sidebar includes working Products and Ventes.
- Open Ventes, search for a precise variant/reference, add it, change quantity,
  remove a line, and see understandable FCFA totals. No barcode is required.
- Start with 10 pieces, sell 2, see 8 and one recorded sale, restart,
  and see the same sale and balance. Repeated clicks/identical retry must not create
  a second sale. Insufficient stock or a storage failure leaves a usable basket and
  no partial sale. Test fractional rounding, overflow and uncertain-retry recovery.

## Boundaries

No Products redesign, stock receipt/correction form, receipt printing, credit,
Mobile Money, cloud sync, authentication or sale-void UI. Audited voids already
exist in core but are a separate future screen task. Coordinate missing service support with Patrick while Emmanuel is unavailable; do not patch SQL from desktop code. Pascal coordinates bundles.

## Shared implementation rules

Use Java 21, Swing and FlatLaf. Inspect actual main and the service signatures;
reuse the existing SQLite database and stable shop identity. No SQL in panels,
no replacement repositories, schema changes, demo seeds or database deletion.
Run DB work off the EDT with SwingWorker; update controls on the EDT. Preserve
input on error, distinguish loading/error from zero, disable repeated submissions
and ignore stale background responses. No authentication is implemented.

Use exact quantities: 1000 internal units = 1 piece/bag/kg/litre; pieces and bags must be whole,
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
> Shared service/device wiring is already merged in PR #22. My next task is a
> visible Ventes/checkout screen and its real navigation entry, followed by sales
> history. Read the current catalog/family guides; support bags and exact variants.
> Work on a new branch from current main. Explain each step simply. Implement the
> cash checkout and durable retry safeguards using existing services; preserve
> Patrick's catalog and all startup safety. Show screenshots, tests and limitations.
> Do not push or merge without my request.

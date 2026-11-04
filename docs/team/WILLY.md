# Willy — Stock management screen

Current assignment: 4 November 2026. This replaces the old Products assignment.
Patrick now owns Products and its design implementation. Charles owns Sales and
shared startup/navigation wiring. Do not restyle or replace ProductPanel.

## Start

Read README.md, docs/team/CONTEXT.md, docs/team/README.md and
[the stock service contract](../backend/STOCK.md). Inspect current code rather
than assuming every old example describes the latest schema.
With a clean checkout on updated main, create `desktop/stock-screen`.
Own `desktop/src/main/java/cm/sji/innova/desktop/stock/` and related tests.

## Milestone 1 — receive goods and see the result

Build a StockPanel with injected StockService, ProductService, shop UUID, device
UUID and ResourceBundle. Agree its constructor with Charles before implementation.
Use a disposable integration harness until the shared context wiring merges;
your PR must compile without an unmerged class. Do not construct another database
in a button handler or silently invent a device UUID each operation.

- List shop products, units, available quantities and minimum thresholds using
  StockService.list and the existing catalog service as needed.
- Choose a product and receive a positive quantity with a required reason or
  delivery reference through StockService.receive.
- Refresh stock and show the persisted movement after successful receipt.
- Add product movement history using StockService.history. Preserve service order.
- Keep one operation UUID and the same request while a failed/uncertain receipt
  is retried. A genuinely new request gets a new ID. Do not let editing input
  silently turn an uncertain retry into a second receipt.

Acceptance: receive 10 pieces, see 10 available and one movement, restart with the
same data directory, and still see 10. Retry the same receipt: still 10 and one
movement. Verify fractional kg/litres, wrong-shop filtering, failed-save input
preservation and no frozen window during slow loads.

## Milestone 2 — corrections and low-stock thresholds

After milestone 1 review, add signed nonzero corrections through StockService.adjust
with a mandatory reason. Label these as changes, not absolute stock counts:
“Retirer 2 pièces” means -2000. Show before/change/result and confirm destructive
corrections. Reject a negative final balance through the service; never edit or
delete historical movements. Retrying uses the same operation-ID rules.

Add nonnegative minimum thresholds with StockService.setMinimum. The service's
lowStock() means available <= minimum. Display text/icons as well as color.
Refresh when entering Stock and after operations; show loading/retry on failures.
Initial zero means no recorded opening stock, not a verified empty shelf.

## Boundaries

No checkout, sales deductions, product form, photos, login, supplier purchasing,
sync or database migration. Do not use adjustments to simulate a sale. Ask Emmanuel
about missing backend contracts; Charles alone coordinates shared bootstrap files.

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

> Read docs/team/WILLY.md, docs/team/CONTEXT.md and docs/backend/STOCK.md.
> My current assignment is Stock, not Products. Inspect the repository, explain
> each step simply and implement milestone 1 on desktop/stock-screen. Coordinate
> injected services with Charles and labels with Pascal. Preserve Patrick's
> Products files. Show tests and limitations. Do not push or merge without my request.

# Emmanuel — backend task: void a cash sale safely

**Owner:** Emmanuel Oyono. **Coordinator/reviewer:** Patrick.
**Starting snapshot:** main `d179b30`, 2 November 2026.
This file is an implementation brief, not a claim that voiding already works.
Inspect current main first; coordinate any schema changes with other backend work.

## 1. Goal in plain language

A cashier made a mistaken cash sale. The shop needs to cancel that recorded sale,
restore its product quantities and keep a permanent explanation of what happened.

Our acceptance example:

> Receive 10 pieces → sell 2 → stock is 8 → void the sale with a reason →
> stock is 10 → restart → the original sale, void event and history all remain.

Never delete the original sale or edit its original items/prices to pretend it
never happened. A void is a new event and new compensating stock movements.

## 2. Give this prompt to your AI editor

> Read docs/team/EMMANUEL.md, docs/backend/SALES.md, docs/backend/STOCK.md,
> SJI_INNOVA_PROJECT.md and SJI_INNOVA_PROJECT_v03.md. Inspect current code and
> any applicable AGENTS.md first. Implement only the cash-sale void milestone in
> this brief, on backend/sale-voids from current main. Preserve existing work and
> stored data. Explain each change simply. Keep the existing retry/rounding fixes,
> use one database transaction for the complete void, and add meaningful tests
> for failures, concurrency and migration. Do not build UI, sync or authentication
> in this task. Report exact test outcomes and limitations. Do not push or merge
> without my request.

## 3. Get the correct starting code

Preserve unfinished work before switching branches. With a clean checkout:

```sh
git switch main
git pull --ff-only origin main
git switch -c backend/sale-voids
```

If the task branch already exists, inspect and continue it rather than overwriting
it. Do not reset or force-push. Existing cloud tasks are isolated; use their
existing checkout, not a new Git worktree unless explicitly requested.

Requirements: JDK 21 and the committed Maven wrapper. No separate SQLite install.
On Windows PowerShell, from the repository root:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

On Linux/macOS use `./mvnw`. Read README.md for this cloud machine's specific JDK,
cache and proxy activation; do not copy cloud-only paths onto a personal computer.

## 4. What already exists — reuse it

- Schema version **4**: products, specifications, stock movements, sales, sale items.
- ProductService and stock receipt/adjustment/current/history services.
- SaleService.makeCashSale, getSale and listSales; SqliteSaleRepository.
- Atomic cash sale + stock deduction, with rollback tests.
- Identical checkout retries preserve the original sale; changed checkout details
  are rejected. CashSaleRequest handles normalized matching, including cart order.
- New line totals use exact decimal HALF_UP whole-FCFA rounding, then sum lines.
  Existing saved amounts are never recalculated.
- StockMovement.Type.VOID and the v4 SQL constraint accept positive void movements.
  **This is only a building block: there is no complete void service yet.**
- Stock movement history is protected against update/delete by SQLite triggers.
- There are no sale_events, audit_log or users tables yet. There is no real login.
- Stock movements currently lack a durable FK link to a sale/item; their sale
  reason text is not a safe identifier, and receipt_number is not UNIQUE.
- The starting branch has 41 tests. Recount current results if main has advanced.

Core location: `core/src/main/java/cm/sji/innova/core/`.
Tests: `core/src/test/java/cm/sji/innova/core/persistence/`.
Existing stock/sale guides and schema code are authoritative for current behavior.

## 5. Scope and business rules for this milestone

Implement a **full void of one recorded cash sale**. A request must identify:

- A client-generated void operation UUID, reused only for identical retries.
- Shop UUID and original sale UUID.
- Device UUID and performed-by actor UUID.
- A mandatory, trimmed, nonblank reason.

Until authentication exists, performed-by is an explicit development identity
supplied by the caller. Do not invent credentials, create a fake logged-in user,
or add a users FK without a real users table. Document that this is attribution,
not verified authentication/authorization. Real role enforcement comes later.

Rules:

1. The sale must exist in the requested shop and be a cash sale.
2. Restore the quantities from the **saved sale items**, using integer thousandths.
   Do not consult current prices to recalculate the sale or its quantities.
3. Append a voided sale event, positive VOID stock movements and an audit record.
4. Preserve original sale/item IDs, prices, quantities, totals and timestamps.
5. Only one successful void may restore a sale's stock, even with concurrent calls.
6. The same operation ID and same normalized details return the saved void result;
   generated timestamps/IDs must not make a retry look different.
7. The same operation ID with changed sale/shop/device/actor/reason is an error.
8. Another operation ID trying to void an already-voided sale is an explicit error
   with no second stock restoration. Document the exception/result contract.
9. Failed writes, invalid input or integer overflow must leave no partial changes.
10. Replaying the original checkout after a void must not deduct stock again or
    reactivate the sale. The UI must be able to query the effective sale state.

A full void assumes all original quantities should return to recorded stock. This
is not a partial return, a damaged-goods workflow or a physical cash-refund engine.
Do not claim a customer received money automatically. No tax-compliance claim is
made; receipt-document cancellation rules remain separate.

## 6. Service contract to agree and document

Suggested shape below; it is NOT an existing method. Choose final names consistently
with the current services and document the exact signature for the desktop team.
Keep old public checkout/read entry points compatible where practical.

```java
VoidSaleResult voidCashSale(
    UUID operationId,
    UUID shopId,
    UUID saleId,
    UUID deviceId,
    UUID performedBy,
    String reason
);
```

Result should expose the saved void/event identity, original sale identity,
replayed flag and enough state/movement information to refresh the screen.
Supply a shop-scoped read operation for effective sale state/event history.
Existing sale-history reads should not silently delete/filter out voided sales:
retain history and expose state separately or through an explicit new read model.
Future active-sales reports must exclude/reverse voided amounts appropriately.
No new reporting dashboard is required here.

## 7. Database migration and event design

Inspect current schema version before choosing the next number. Version **5** is
only the expected next number if no other migration has merged. Emmanuel owns
coordination of this change; do not collide with another backend developer.

- Add sale event and audit storage following the revised v0.3 append-only design.
- Newly recorded cash sales should append a created event in their existing atomic
  checkout transaction. Checkout retries must not append duplicate created events.
- Migrate existing sales to a consistent created-event baseline, preserving their
  original time/amounts and all stock movements. Use a deterministic, documented
  backfill strategy; don't guess historical actor IDs. Mark unknown legacy actors
  explicitly if needed. Migration must be repeatable and transactional.
- Add explicit relational links from a void event to the original sale and its
  restored item/movement records. Choose appropriate tables/keys. Never identify
  stock deductions by parsing a non-unique receipt number in a reason string.
- Enforce uniqueness sufficient to prevent a second successful void of a sale and
  duplicate restoration of an item. Do not rely only on a screen disabling a button.
- Enable foreign keys and enforce same-shop consistency using appropriate checks/
  keys. A shop UUID in a request is not proof of an authenticated user's authority.
- New events/audit rows are immutable; corrections become later events. Keep sync
  delivery metadata out of immutable business records. No sync implementation now.
- Existing sales/items must remain unchanged. If exposing cached sale status, it is
  a derived value updated atomically with the event, never the source of truth.
- Preserve populated schema-v4 data and rollback the complete migration on failure.
- Do not remove stock immutability protections merely to make runtime voids easier.

Review the chosen DDL and links with Patrick before unrelated schema expansion.
Do not add every future MVP table just because it appears in the planned ER diagram.

## 8. Transaction requirements — one protected save

Use one consistent SQLite transaction approach, following the corrected cash/stock
repositories. Acquire the write lock **before** checking idempotency or void state.

Within that single transaction:

1. Look up an existing void operation and validate identical-retry details.
2. Verify sale ownership/type and that no successful void already exists.
3. Read all original sale items and current product stock.
4. Append the void event, per-item positive movements and explicit links.
5. Update cached stock for all affected products using checked integer arithmetic.
6. Append the audit entry and any derived state update.
7. Commit only after every step succeeds; rollback on SQL or runtime failure.

Repeated lines for the same product must restore their full summed quantity exactly
once. Use immutable saved quantities, not a current editable cart. Check overflow.
Do not call StockService.adjust as a separately committed operation after saving
a void: that would allow a half-voided sale if the second step fails.

Do not mix connection.setAutoCommit(false) with another BEGIN IMMEDIATE; that
caused the earlier nested-transaction failure. Preserve the existing build fix.

## 9. Required tests — evidence before approval

Use temporary databases; never a real shop's data. Add focused integration tests
that can fail when the behavior is wrong. Include:

- [x] Receive 10, sell 2, void: stock returns to 10; event/audit/movements exist.
- [x] Original sale/items/prices/timestamps remain exactly unchanged.
- [x] Close/reopen: void state, original sale and stock history persist.
- [x] Identical retry returns the original void and never restores stock twice.
- [x] Changed operation details are rejected without modifying anything.
- [x] A new operation ID for an already-voided sale cannot restore stock again.
- [x] Two simultaneous void requests (same/different operation IDs) produce exactly
      one effective void/restoration; use synchronization, not timing guesses.
- [x] Wrong-shop, nonexistent sale, blank reason and unsupported payment type fail.
- [x] Multi-item and repeated-product lines restore the correct total quantities.
- [x] kg/litre fractional quantities use exact stored thousandths.
- [x] A failure after an earlier item/event write rolls back every affected row
      and cache value. A legitimate retry afterward can still succeed.
- [x] Restoration overflow rolls everything back.
- [x] Populated schema-v4 upgrade preserves products/specifications/sales/stock,
      backfills events correctly and survives repeated initialization.
- [x] Failed migration restores the previous schema/version/data before retry.
- [x] Checkout creation/retries still work and don't duplicate created events.
- [x] Replaying checkout after void leaves void state and stock unchanged.
- [x] Existing HALF_UP rounding and changed-checkout-retry tests still pass.

Run full wrapper clean verify and the packaged headless startup check from README.
Report passed/failed/skipped/unrun counts honestly. A backend check does not prove
Windows GUI usability; the void screen is outside this task.

## 10. Deliverables and boundaries

Deliver one focused PR titled **Add audited cash-sale voids** with:

- Service/result/event models and a documented public API.
- Coordinated safe migration, repository transaction and durable relational links.
- Required tests and actual command/results.
- Updated docs/backend/SALES.md or a linked VOID_SALES.md explaining errors,
  retry identity, state reads, development actor attribution and limitations.
- A small example for Charles/Willy to call later from a background SwingWorker.

Out of scope: desktop screens, PDF refund documents, partial returns, credit/MoMo
reversal, actual payment-provider refunds, tax calculations, authentication,
cloud sync, and unrelated class/database rewrites. Do not modify friends' UI
branches or put secrets/database files/generated build output into Git.

## Progress / evidence to complete while working

- [x] Confirm no competing void implementation/migration is in progress.
- [x] Inspect current main and agree service/error/state contract.
- [x] Implement migration with populated-data/rollback tests.
- [x] Implement one-transaction void, retry guards and audit/event links.
- [x] Complete behavior/concurrency tests and full verification.
- [x] Document limitations and request another person's review.

Branch / commit / PR:
- Branch: `backend/sale-voids` (from `main` `3d1f133`).

Migration version and tables/constraints added:
- Schema version **5** (`SqliteDatabase.SCHEMA_VERSION = 5`).
- Added `sale_events` (`id`, `sale_id`, `shop_id`, `device_id`, `type IN ('created', 'voided')`, `performed_by`, `reason`, `created_at`, `UNIQUE(sale_id, type)`, `UNIQUE(shop_id, sale_id, id)`, composite FK `(shop_id, sale_id) -> sales(shop_id, id)`).
- Deterministically backfills `created` events (`SaleEvent.createdEventId(saleId)`, `performed_by = NULL`) for existing Schema v4 sales.
- Added `sale_void_items` (`sale_item_id` PK, `void_event_id`, `shop_id`, `sale_id`, `product_id`, `stock_movement_id` UNIQUE, `quantity_milli`, composite FKs to `sale_events`, `sale_items`, and `stock_movements`).
- Added `audit_log` (`id`, `shop_id`, `device_id`, `performed_by`, `action`, `target_table`, `target_id`, `event_id` UNIQUE, `reason`, `old_value`, `new_value`, `created_at`, composite FK to `sale_events`).
- Added append-only `BEFORE UPDATE` and `BEFORE DELETE` triggers on `sales`, `sale_items`, `sale_events`, `sale_void_items`, and `audit_log`.

Public method and error contract:
- `SaleService.voidCashSale(UUID operationId, UUID shopId, UUID saleId, UUID deviceId, UUID performedBy, String reason) -> VoidSaleResult`
- `SaleService.getSaleState(UUID shopId, UUID saleId) -> Optional<SaleState>`
- `SaleService.listSaleStates(UUID shopId) -> List<SaleState>`
- `SaleService.listSaleEvents(UUID shopId, UUID saleId) -> List<SaleEvent>`
- `SaleService.listAuditRecords(UUID shopId, UUID saleId) -> List<SaleAuditRecord>`
- Throws `NullPointerException` on null arguments; `IllegalArgumentException` on blank reason, nonexistent/wrong-shop sale, non-cash sale, or reused `operationId` with changed details; `IllegalStateException` on a new `operationId` when the sale is already voided; `ArithmeticException` on stock restoration overflow; `SQLException` on storage failures.

Commands run and exact results:
- `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`: `BUILD SUCCESS` — 52 tests run (`innova-core`: 51 passed, 0 failed, 0 errors, 0 skipped; `innova-desktop`: 1 passed, 0 failed, 0 errors, 0 skipped).
- `java "-Djava.awt.headless=true" -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar --smoke-test`: exited 0 (`Desktop startup smoke check passed`).

Known limitations / unrun checks:
- No desktop void UI screen, printable credit/void receipt, partial item return, credit/MoMo reversal, authentication/role check (`performedBy` is caller-supplied attribution until login is built), or cloud synchronization is implemented in this milestone.
- Visible Windows GUI interaction for voiding sales is unrun because no void UI screen exists yet.

Never tick a task as done until you have evidence. Open a PR for review; do not
merge automatically. Patrick coordinates review; this file does not authorize
sending messages or credentials to anyone.

## Review follow-up — 2 November 2026

Your first void implementation at `194c481` was reviewed. Read the
[dedicated correction brief](../reviews/emmanuel-voids-2026-11-02/EMMANUEL.md)
and [full evidence](../reviews/emmanuel-voids-2026-11-02/REVIEW.md).
It covers the audit-only mixed-unit aggregate overflow; retain the rest of the
feature and its genuine stock-overflow/rollback protections.

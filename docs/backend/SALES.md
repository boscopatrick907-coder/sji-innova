# Offline cash sales and audited sale voids: backend and desktop handoff

The backend supports Stage 03 cash sales and audited cash-sale voids:
- **Cash sale:** `"Create a product → receive 10 pieces → sell 2 offline → restart → verify 8 remain and the sale still exists."`
- **Cash-sale void:** `"Receive 10 pieces → sell 2 → stock is 8 → void the sale with a reason → stock is 10 → restart → the original sale, void event and history all remain."`

## Core architecture

Sales, stock deductions, and sale voids are **strictly atomic** within single SQLite transactions (`BEGIN IMMEDIATE ... COMMIT`).
- A sale never commits without its corresponding negative stock movement and `created` event.
- A void never deletes or edits the original sale or its line items; it appends a `voided` event, positive `void` stock movements, item-restoration relational links, and a `VOID_SALE` audit record in one transaction.

### Database tables (Schema v5)

1. `sales` (append-only):
   - `id`: Client-generated operation UUID (idempotency key for offline retries).
   - `shop_id`: Shop UUID.
   - `receipt_number`: Human-readable receipt number (e.g. `REC-2026-001`).
   - `device_id`: POS terminal device UUID.
   - `payment_method`: `cash`, `momo`, or `credit`.
   - `total_amount`: Total amount in whole FCFA (`long`).
   - `created_at`: ISO-8601 UTC timestamp.

2. `sale_items` (append-only):
   - `id`: Item UUID.
   - `sale_id`: Foreign key referencing `sales(id)`.
   - `product_id`: Foreign key referencing `products(id)`.
   - `product_reference` (added in Schema v9): Snapshot of the product's shop reference (e.g. `P-0001`) at moment of sale, `NOT NULL` and non-blank. `SaleService` copies it from the product, and the repository refuses a line whose reference is not its product's own. Lines saved before v9 took their product's reference during the upgrade (shop references are never edited); the table rebuild keeps each line's rowid, so line order, indexes and append-only triggers are unchanged.
   - `product_name`: Snapshot of product name at moment of sale.
   - `unit`: Snapshot of unit (`piece`, `kg`, `litre`).
   - `quantity_milli`: Quantity sold in thousandths (`long`, e.g. 2 pieces = 2000).
   - `unit_price`: Unit selling price in whole FCFA.
   - `subtotal`: Exact decimal `(quantityMilli * unitPrice) / 1000`, rounded per line to whole FCFA with HALF_UP.

3. `stock_movements` (append-only):
   - Supports `delivery`, `adjustment`, `sale`, and `void` movement types.
   - `sale` movement records negative quantity (`-quantityMilli`) with reason `"Sale " + receiptNumber`.
   - `void` movement records positive quantity (`+quantityMilli`) with reason `"Void sale " + receiptNumber + ": " + reason`.
   - Updates `products.current_stock_milli` in the same transaction.

4. `sale_events` (append-only, added in Schema v5):
   - `id`: Event UUID (deterministic `SaleEvent.createdEventId(saleId)` for `created`; client-supplied `operationId` for `voided`).
   - `sale_id`, `shop_id`, `device_id`: Same-shop foreign key referencing `sales(shop_id, id)`.
   - `type`: `created` or `voided` (`UNIQUE(sale_id, type)` prevents duplicate creation or second void).
   - `performed_by`: Actor UUID (`NULL` when unknown before login or migrated from Schema v4; mandatory `NOT NULL` for `voided`).
   - `reason`: Non-blank event reason.
   - `created_at`: ISO-8601 UTC timestamp.

5. `sale_void_items` (append-only, added in Schema v5):
   - `sale_item_id` (`PRIMARY KEY`): Prevents restoring any sale item more than once.
   - `void_event_id`, `shop_id`, `sale_id`: Composite foreign key referencing `sale_events(shop_id, sale_id, id)`.
   - `product_id`: Composite foreign key with `(sale_id, sale_item_id)` referencing `sale_items(sale_id, id, product_id)`.
   - `stock_movement_id` (`UNIQUE`): Composite foreign key with `(shop_id, product_id)` referencing `stock_movements(shop_id, product_id, id)`.
   - `quantity_milli`: Positive restored quantity in thousandths.

6. `audit_log` (append-only, added in Schema v5):
   - `id`, `shop_id`, `device_id`, `performed_by`, `action` (`VOID_SALE`), `target_table` (`sales`), `target_id` (`saleId`), `event_id` (`UNIQUE`, referencing `sale_events`), `reason`, `old_value` (JSON), `new_value` (JSON), `created_at`.
   - `old_value` records `{"status":"paid","receiptNumber":"...","totalAmount":...}`.
   - `new_value` records `{"status":"voided","voidEventId":"...","performedBy":"...","restoredItemCount":...,"restoredItems":[{"saleItemId":"...","productId":"...","unit":"...","quantityMilli":...,"stockMovementId":"..."},...]}`. Quantities are recorded per item with their canonical unit (`piece`, `kg`, `litre`) rather than summed across incompatible units.

## How desktop UI calls SaleService (Checkout and Void)

Run all database calls off Swing's Event Dispatch Thread (EDT) using a `SwingWorker`:

```java
var database = new SqliteDatabase(pathToDatabase);
database.initialize();

var productRepo = new SqliteProductRepository(database);
var saleRepo = new SqliteSaleRepository(database);
var saleService = new SaleService(saleRepo, productRepo);

// 1. Checkout in a SwingWorker off the EDT:
UUID saleId = UUID.randomUUID(); // One ID per logical sale; preserve while retrying the same checkout
List<SaleItemRequest> cart = List.of(
    new SaleItemRequest(productId, 2_000L) // 2 pieces
);

SaleResult saleResult = saleService.makeCashSale(saleId, shopId, deviceId, "REC-2026-001", cart);
Sale completedSale = saleResult.sale();

// 2. Void a cash sale in a SwingWorker off the EDT:
UUID voidOperationId = UUID.randomUUID(); // Preserve while retrying the same void request
UUID performedBy = currentActorId; // Development attribution UUID until login/auth is implemented

VoidSaleResult voidResult = saleService.voidCashSale(
    voidOperationId,
    shopId,
    completedSale.id(),
    deviceId,
    performedBy,
    "Cashier rang wrong quantity"
);

// 3. Query effective sale state, event history, and audit trail:
SaleState state = saleService.getSaleState(shopId, completedSale.id()).orElseThrow();
boolean isVoided = state.voided(); // true (state.status() == SaleStatus.VOIDED)
List<SaleState> allShopSales = saleService.listSaleStates(shopId);
List<SaleEvent> events = saleService.listSaleEvents(shopId, completedSale.id());
List<SaleAuditRecord> audits = saleService.listAuditRecords(shopId, completedSale.id());
```

## Public void and state contract

| Method | Meaning |
|---|---|
| `voidCashSale(operationId, shopId, saleId, deviceId, performedBy, reason)` | Atomically voids a cash sale, restores item quantities from saved `sale_items`, records `voided` event, `VOID` stock movements, relational links, and `VOID_SALE` audit entry. Returns `VoidSaleResult`. |
| `getSaleState(shopId, saleId)` | Returns `Optional<SaleState>` with the immutable `Sale`, derived `SaleStatus` (`PAID` or `VOIDED`), ordered `SaleEvent` list, and `Optional<SaleEvent>` void event. |
| `listSaleStates(shopId)` | Returns all sales in the shop with their effective `SaleStatus` and event history (`getSale` and `listSales` also remain available and retain voided sales unchanged). |
| `listSaleEvents(shopId, saleId)` | Returns immutable `SaleEvent` history (`CREATED`, then `VOIDED` if voided) for one sale in the shop. Throws `IllegalArgumentException` if the sale does not exist in `shopId`. |
| `listAuditRecords(shopId, saleId)` | Returns immutable `SaleAuditRecord` entries for the sale in `shopId`. Throws `IllegalArgumentException` if the sale does not exist in `shopId`. |

### Void idempotency, error handling, and attribution rules

- **Identical void retry:** Calling `voidCashSale` with the same `operationId` and the same normalized `(shopId, saleId, deviceId, performedBy, reason.strip())` returns the stored `VoidSaleResult` with `replayed=true` and never restores stock a second time.
- **Changed void retry:** Reusing an `operationId` with a different shop, sale, device, actor, or reason (or reusing a `created` event ID) throws `IllegalArgumentException` without modifying anything.
- **Already-voided sale:** Calling `voidCashSale` with a new `operationId` for a sale that has already been voided throws `IllegalStateException` and does not restore stock again.
- **Invalid inputs / wrong shop / non-cash sale:** Null arguments throw `NullPointerException`. Blank reason, nonexistent `saleId`, wrong `shopId`, or non-cash payment method (`momo`, `credit`) throws `IllegalArgumentException`.
- **Overflow / storage failure:** Per-product stock restoration uses checked arithmetic (`Math.addExact(currentStock, item.quantityMilli())`) and throws `ArithmeticException` on genuine product stock overflow, without combining quantities across different products or units; database errors throw `SQLException`. Any failure rolls back the entire void transaction so no partial event, movement, stock change, or audit row remains.
- **Checkout replay after void:** Retrying `makeCashSale` with the original `saleId` and matching checkout details after the sale was voided returns `replayed=true` without deducting stock again, duplicating the `created` event, or reactivating the voided sale.
- **Development actor attribution:** Until user login and role authentication are implemented, `performedBy` is an explicit caller-supplied UUID recorded for attribution in `sale_events` and `audit_log`. It is not verified authentication or role authorization. Legacy Schema v4 sales migrated to Schema v5 have `performedBy = null` on their backfilled `created` event rather than a guessed user ID.
- **Limitations:** This milestone implements full voids of recorded cash sales only. It does not build desktop UI screens, partial item returns, damaged-goods workflows, credit/MoMo reversals, physical cash-drawer payouts, tax/receipt document cancellation, authentication, or cloud sync.

## Checkout idempotency and whole-FCFA rounding policy

- **Identical checkout retry:** The same sale ID and normalized checkout details return the stored sale with `replayed=true`, without deducting stock again.
- **Changed checkout retry:** Different shop/device/receipt, product, quantity, explicit charged price or line multiplicity throws `IllegalArgumentException`. The original sale and stock remain untouched.
- **Order:** Cart order does not matter, but splitting/combining lines or changing duplicate-line counts does. Receipt whitespace is stripped.
- **Catalog price on retry:** A missing `customUnitPrice` reuses the original saved line price, not today's catalog price. Explicit prices must match charged prices.
- **Whole-FCFA rounding:** New cash sales round **each line** to the nearest whole FCFA using `HALF_UP` (exact halves round upward), then sum the rounded lines. Existing saved sales are never retrospectively recalculated.

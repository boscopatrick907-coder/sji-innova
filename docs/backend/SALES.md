# Offline cash sales: backend and desktop handoff

The backend supports the central cash-sale scenario of Stage 03:
**"Create a product → receive 10 pieces → sell 2 offline → restart → verify 8 remain and the sale still exists."**

## Core architecture

Sales and stock deductions are **strictly atomic** within a single SQLite transaction (`BEGIN IMMEDIATE ... COMMIT`).
A sale never commits without its corresponding negative stock movement, and stock is never deducted without a persisted sale.

### Database tables (Schema v4)

1. `sales`:
   - `id`: Client-generated operation UUID (idempotency key for offline retries).
   - `shop_id`: Shop UUID.
   - `receipt_number`: Human-readable receipt number (e.g. `REC-2026-001`).
   - `device_id`: POS terminal device UUID.
   - `payment_method`: `cash`, `momo`, or `credit`.
   - `total_amount`: Total amount in whole FCFA (`long`).
   - `created_at`: ISO-8601 UTC timestamp.

2. `sale_items`:
   - `id`: Item UUID.
   - `sale_id`: Foreign key referencing `sales(id)`.
   - `product_id`: Foreign key referencing `products(id)`.
   - `product_name`: Snapshot of product name at moment of sale.
   - `unit`: Snapshot of unit (`piece`, `kg`, `litre`).
   - `quantity_milli`: Quantity sold in thousandths (`long`, e.g. 2 pieces = 2000).
   - `unit_price`: Unit selling price in whole FCFA.
   - `subtotal`: Exact decimal `(quantityMilli * unitPrice) / 1000`, rounded per line to whole FCFA with HALF_UP.

3. `stock_movements` (Extended):
   - Supports `sale` and `void` movement types.
   - `sale` movement records negative quantity (`-quantityMilli`) with reason `"Sale " + receiptNumber`.
   - Updates `products.current_stock_milli` in the same transaction.

## How desktop UI calls SaleService

```java
var database = new SqliteDatabase(pathToDatabase);
database.initialize();

var productRepo = new SqliteProductRepository(database);
var saleRepo = new SqliteSaleRepository(database);
var saleService = new SaleService(saleRepo, productRepo);

// In a SwingWorker off the EDT:
UUID saleId = UUID.randomUUID(); // One ID per logical sale; preserve it while retrying the same request
List<SaleItemRequest> cart = List.of(
    new SaleItemRequest(productId, 2_000L) // 2 pieces
);

SaleResult result = saleService.makeCashSale(saleId, shopId, deviceId, "REC-2026-001", cart);
Sale completedSale = result.sale();
```

## Idempotency and error handling

- **Identical retry:** The same sale ID and normalized checkout details return the stored sale with `replayed=true`, without deducting stock again.
- **Changed retry:** Different shop/device/receipt, product, quantity, explicit charged price or line multiplicity throws `IllegalArgumentException`. The original sale and stock remain untouched.
- **Order:** Cart order does not matter, but splitting/combining lines or changing duplicate-line counts does. Receipt whitespace is stripped.
- **Catalog price on retry:** A missing `customUnitPrice` reuses the original saved line price, not today's catalog price. Explicit prices must match charged prices. Old records do not store whether a price originally came from the catalog or an override; an explicit price equal to the stored charged price is treated as equivalent. No new request-fingerprint column is introduced.
- **Concurrency:** The service resolves saved retries before catalog pricing; SQLite also rechecks the original request under its write lock. Different simultaneous requests cannot both claim the same ID.
- **Existing sales:** Saved totals, line prices, item IDs and timestamps are returned unchanged, including sales recorded under the older truncation behavior.
- **Insufficient stock:** Throws `IllegalStateException` with product name and available stock.
- **Piece unit check:** Quantities for unit `piece` must be multiples of 1,000.

## Whole-FCFA rounding policy

New cash sales round **each line** to the nearest whole FCFA using HALF_UP
(exact halves round upward), then sum the rounded lines. This is a project
business rule, not a claim about statutory tax/accounting requirements. VAT and
discounts are not added by this fix and need their own agreed rules later.

| Quantity | Price per unit | Exact line amount | Stored line amount |
|---|---:|---:|---:|
| 0.5 kg | 501 FCFA | 250.5 FCFA | 251 FCFA |
| 0.499 kg | 1 FCFA | 0.499 FCFA | 0 FCFA |
| 0.5 kg | 1 FCFA | 0.5 FCFA | 1 FCFA |
| 2 pieces | 1000 FCFA | 2000 FCFA | 2000 FCFA |

Two separate lines of 0.5 kg at 1 FCFA each total 2 FCFA after per-line rounding.
Combining them into one 1 kg line produces 1 FCFA. The UI must show the line
amounts and sum actually used; it must not independently round only the basket.
There is no minimum-charge rule beyond nonnegative prices, so very small lines
can legitimately round to zero under this policy.

BigDecimal arithmetic avoids binary floating-point error and intermediate long
multiplication overflow. The final line amount and basket sum must fit a Java
long; true overflow throws ArithmeticException before stock changes. Existing
saved sales are never retrospectively recalculated. Schema remains version 4;
no SQL migration, dependency or desktop constructor change is required.

## Regression coverage for these fixes

Tests cover changed header/cart rejection, reordering and duplicate-line matching,
explicit versus catalog prices, retries after reopen/catalog changes/depleted
stock, original rounding preservation, transaction-level concurrent retries and
conflicting submissions, half boundaries, per-line sum, valid large amounts and
real line/basket overflow. Existing transaction rollback tests remain in place.

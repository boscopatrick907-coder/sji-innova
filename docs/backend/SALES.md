# Offline cash sales: backend and desktop handoff

This change implements Stage 03 of the delivery roadmap:
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
   - `subtotal`: Line total in whole FCFA (`(quantityMilli * unitPrice) / 1000`).

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
UUID saleId = UUID.randomUUID(); // Unique per checkout attempt; reuse if retrying network/local timeout
List<SaleItemRequest> cart = List.of(
    new SaleItemRequest(productId, 2_000L) // 2 pieces
);

SaleResult result = saleService.makeCashSale(saleId, shopId, deviceId, "REC-2026-001", cart);
Sale completedSale = result.sale();
```

## Idempotency and error handling

- **Duplicate submission:** Submitting with the same `saleId` returns `replayed=true` with the existing sale and does not deduct stock a second time.
- **Insufficient stock:** Throws `IllegalStateException` with product name and available stock.
- **Piece unit check:** Quantities for unit `piece` must be multiples of 1,000.

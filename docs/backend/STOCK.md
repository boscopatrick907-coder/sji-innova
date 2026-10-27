# Stock management: backend and desktop handoff

This change implements local receipts, signed adjustments, current stock,
movement history and low-stock thresholds. It does not implement a stock screen,
sales, login, cloud synchronization, supplier orders or cost accounting.
Emmanuel should review this branch's schema/service contract before integrating
any parallel backend work. It is not a claim about unpublished changes on his PC.

## Simple model

Receiving 10 pieces creates a +10000 delivery movement and sets current stock to
10000. Receiving 5 more and recording a -2-piece adjustment leaves 13000 (13
pieces). Every movement has its own UUID, shop/product/device UUIDs, type,
quantity, reason and timestamp. Never edit/delete movements to fix a mistake:
record another adjustment with a clear reason. No destructive correction API is
provided. A stock adjustment is not a sale and creates no sale or receipt.

Quantities use integer thousandths: 10 pieces = 10000; 1.5 kg = 1500;
0.25 litre = 250. Piece quantities and thresholds must be multiples of 1000.
Delivery quantity must be positive; an adjustment is a signed, nonzero delta,
not an absolute count. Manual local operations reject a negative result and
integer overflow. The future multi-device sync path must preserve legitimate
conflicting sales and flag negative stock as required by the plan; it must not
reuse this manual-adjustment restriction to discard remote sales.

## Create the service

Call database.initialize() once during application startup, off Swing's EDT.
Inject the resulting service into a future stock panel; do not put SQL in UI code.

```java
var database = new SqliteDatabase(pathToDatabase);
database.initialize();
var stock = new StockService(new SqliteStockRepository(database));

// shopId/productId/deviceId are UUIDs from the application's stable context.
UUID operationId = UUID.randomUUID();
var result = stock.receive(operationId, shopId, productId, deviceId,
        10_000, "Delivery received");
var current = stock.current(shopId, productId);
var history = stock.history(shopId, productId);
stock.setMinimum(shopId, productId, 2_000);
var levels = stock.list(shopId);
```

Imports: `cm.sji.innova.core.persistence.*`, `cm.sji.innova.core.stock.*`,
`java.util.UUID`. Use stable shop/device identities; the service does not create
or authenticate them. Shop filtering is isolation of requested data, not login
or permission enforcement. Authentication and audit-user identity remain future
work. Reasons are mandatory; put delivery references in the reason for now.

## Public contract

| Method | Meaning |
|---|---|
| receive(operationId, shopId, productId, deviceId, quantityMilli, reason) | Positive receipt; returns StockResult |
| adjust(operationId, shopId, productId, deviceId, deltaMilli, reason) | Positive/negative correction; returns StockResult |
| current(shopId, productId) | StockLevel for one product; unknown/wrong-shop product is an error |
| list(shopId) | All stock levels in that shop, including empty stock |
| history(shopId, productId) | Immutable movement records, ordered by timestamp then ID |
| setMinimum(shopId, productId, minimumMilli) | Set nonnegative low-stock threshold; returns StockLevel |

StockLevel contains shopId, productId, productName, canonical unit, quantityMilli,
minimumMilli, and lowStock(). Low means `quantity <= minimum`, so the default
threshold of 0 flags empty stock. A changed threshold increments product catalog
version and updated_at; stock movements update only the stock cache, not catalog
version. Setting an unchanged threshold has no catalog side effect.

StockResult contains the persisted movement, current stock and replayed flag.
Keep an operationId when retrying the SAME request after failure/timeout. Generate
a fresh ID for a genuinely new delivery or adjustment. Same ID and same normalized
business details returns the original movement and replayed=true without adding
stock again. Creation time is ignored for retry matching. Same ID with changed
shop/product/device/type/quantity/reason is rejected. On retry, stock reflects the
current quantity, which may include operations recorded after the original.

Input/product/shop errors throw IllegalArgumentException. Missing required
objects throw NullPointerException. Quantity/catalog-version overflow throws
ArithmeticException. Storage failures throw SQLException (initialization also
throws IOException). Desktop code should translate these into useful messages,
preserve form input, and not assume an unknown error means the save succeeded.
All DB calls run on a SwingWorker, not on the EDT. Preserve the request ID while
the outcome is uncertain, disable duplicate submits, and refresh after success.

## Database upgrade and durability

Schema 3 adds products.current_stock_milli and min_stock_milli, both default 0,
and stock_movements. Existing product IDs, descriptions, prices, specifications
and catalog versions are preserved. Zero means no recorded opening stock; it
does not prove that the physical shelf is empty. Record a real opening count as
an explained positive adjustment before using the app for stock decisions.

History is append-only, enforced by SQLite update/delete triggers. Foreign keys
prevent stock history referencing a nonexistent/deleted product. A receipt or
adjustment takes a write lock BEFORE checking current stock and the operation
ID; it saves the movement and cache in one transaction. Any failure rolls back
both. SQLite busy_timeout allows concurrent callers to wait rather than overwrite
each other. Normal storage errors can still occur and must be reported/retried.

No sync status is stored inside immutable movements. Future sync metadata should
be separate. Future sales must save sale records/events and stock changes in ONE
shared transaction: do not commit a sale and then separately call receive/adjust.
The schema currently permits delivery/adjustment types only; adding sale/void
movements requires a coordinated migration/service extension. Never change a
product's unit after movements exist without a deliberate conversion design.
WAL recovery is not a backup. Review the backup procedure before using real data.

## Try the packaged stock demo

Build with README.md instructions. This is an explicit developer demo; nothing
is seeded by normal desktop startup. Use a disposable path, not a shop database.

```sh
java -cp desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar cm.sji.innova.core.demo.StockDemo data/stock-demo.db
```

Run twice. First run records 10 pieces; second loads the same delivery without
counting it again. Both show 10 pieces and 1 movement. The demo uses fixed
shop/device/operation IDs; it is not production registration or a stock screen.
Run it from the same working directory or use an absolute path to reopen the same
file. Data is ignored by Git. No SQLite installation is required.

## Validation checklist

Automated integration tests cover receipts/adjustments across reopen, exact
retries, conflicting reuse, concurrent receipts/retries, per-shop filtering,
whole-piece/fractional units, invalid input, low-stock boundaries, overflow,
append-only history, rollback after a simulated cache write failure, migration
from schema 2, and failed-migration rollback/retry. Existing product tests remain.
Packaged demo and headless desktop startup must also pass. Windows CI covers
these backend behaviors; it does not prove a visible stock screen exists.

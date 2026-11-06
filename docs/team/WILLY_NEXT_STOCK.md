# Willy — next Stock milestones

Updated 6 November 2026. This replaces the old WILLY.md assignment; do not restart
Products or rebuild the receipt panel. Your original commit `739798f` is preserved
in PR #27, followed by review corrections and current-main integration.

## What is delivered by PR #27 plus corrections

Stock is reachable from the sidebar with real injected services. Receipts,
available quantities and movement history work. Review fixes reject fractional
bags before saving, keep the selected/pending product across refresh, distinguish
known validation rejections from uncertain saves, and retain signed corrections
in history. French/English labels and variant references are included.

PR #27 is merged. Update main with a clean working tree. Preserve uncommitted
work first. Create a NEW branch such as `desktop/stock-recovery`; do not reset or
force-push the old branch. Read README.md, docs/team/CONTEXT.md, docs/backend/STOCK.md,
docs/backend/PRODUCT_FAMILIES.md and the actual code. Java 21 is required.

## Next milestone A — receipt recovery across restart

Current retry IDs/details live in the StockPanel instance only. They survive an
in-session storage error, but not closing/restarting the app. Complete this before
using uncertain retries in a real shop.

1. Your proposed design is approved: implement PendingReceiptStore and
   FilePendingReceiptStore under desktop/stock/. Use pending-stock-receipt.properties
   inside context.location().directory(). Create/inject the lightweight store in
   ShellPanel's StockPanel construction path, not AppContextLoader. Preserve the
   existing public constructor; normal app launch must use the persistent store.
   Construction on the EDT must perform no I/O: initialize/read/write in workers.
   Do not create another shop/database or change the configured data folder.
2. Persist the immutable operation UUID, shop/product/device IDs, canonical unit,
   quantity and normalized reason BEFORE calling StockService.receive. If this
   record cannot be saved, do not submit an untracked receipt.
3. On restart, show the saved original details and an explicit “Retry same receipt”
   action. Do not submit automatically or generate a new operation ID. Reject
   mismatched shop/device data clearly; preserve damaged files for recovery.
4. Keep the durable record until service confirmation. If confirmation succeeded
   but removing pending state failed, retry must reconcile the same ID without
   another stock movement. Make file replacement crash-safe and coordinate access
   across service instances and JVM processes sharing the same local data folder.
   Use a per-directory JVM lock plus an OS file lease. Never overwrite another
   pending operation or clear a file without checking its operation UUID. Keep
   the lock file in place; a competing writer shows a clear blocked state.
5. Do not allow a user to discard an uncertain request and silently duplicate it.
   Known no-commit validation rejections can return to editable input; storage
   uncertainty must retain the original request until resolved.

Acceptance: simulate a receipt committed in SQLite but a lost response; restart,
retry the stored request, and verify ONE movement and the original total stock.
Also test pre-commit failure, damaged pending file, failed pending write and
failed post-confirmation cleanup. Use temporary files; never live shop data.

## Next milestone B — signed stock corrections

After recovery review, implement explained corrections via StockService.adjust.
Use a signed delta, not an absolute replacement balance. For example “Retirer 2
pièces” means -2000. Show current/change/result and ask for confirmation before
submitting a negative correction. Require a reason and keep the movement history
append-only. Use the same durable retry principles as receipts. Never simulate
sales by adjusting stock or delete movements to correct a mistake.

Whole pieces/bags only; kg/litres support up to three decimal places. Parse exact
decimals, support the French decimal comma, check overflow and reject negative
resulting stock through the service. Arithmetic/business failures leave usable
input. Surface storage failures without claiming success.

Acceptance: receive 10, adjust -2 with a reason, reopen and see 8 with signed
history; retry the same correction without subtracting twice. Reject -9 from 8
without any committed movement or frozen form.

## Next milestone C — editable low-stock thresholds

Use StockService.setMinimum. Zero is valid; piece/bag thresholds must be whole.
Explain that lowStock() means quantity <= minimum; use a label/icon as well as
color. Refresh displayed stock after success and on entering the screen, preserving
selection by product UUID. Do not confuse a loading failure with zero stock.

Acceptance: set a minimum of 2; verify 3 is normal, 2 and 0 are low. Reopen and
verify the threshold persists. No schema change or manual SQL is needed.

## Ownership and delivery

Own desktop/stock/ and focused tests. Patrick owns desktop/catalog/; Charles owns
Sales and shared navigation. Coordinate ShellPanel/context changes instead of
replacing his files. Reuse the chosen typography/green style and existing bundles;
coordinate labels with Pascal. Products are sellable variants, not family totals:
show their unique references so similar rice bags remain distinguishable.

No product form, photos, CSV import, authentication, cloud sync, sales, supplier
purchasing or core/schema rewrite in these milestones. If a service gap appears,
describe it to Patrick before changing core. CSV and photo backends are already delivered.

Run the full wrapper clean verify and packaged headless smoke check from README.
Add meaningful persistence/recovery tests with disposable data. Verify keyboard,
resize, validation and save/reopen in an actual Windows window; separate those
checks from headless tests. Include screenshots, branch/commit, exact test results
and limitations in each small PR. Do not push/merge without your human's request.

## Prompt for your AI editor

> Read docs/team/WILLY_NEXT_STOCK.md, docs/team/CONTEXT.md and docs/backend/STOCK.md.
> My receipt screen and its review fixes are delivered in PR #27; do not repeat
> that work. Start from updated main after this documentation PR merges. Explain
> each step simply and implement the approved milestone A contract with crash/retry tests. The store is injected
> in ShellPanel, with all file I/O off the EDT. Corrections and thresholds follow in separate
> small PRs. Preserve existing catalog/Sales work and never touch live shop data.
> Show tests and limitations; do not push or merge without my request.


## Current examples to inspect, not blindly copy

CSV PendingImportStore shows request/snapshot persistence; Sales
PendingCheckoutStore/CheckoutFileLease show ownership-aware cleanup and process
coordination after PR #30. Use the current corrected versions. Preserve the receipt
operation UUID, shop/product/device IDs, canonical unit, quantity and normalized
reason exactly. Recovered receipts never submit automatically. Wrong-shop/device
and damaged files must block submission and remain available for recovery.

Deliver milestone A first; B and C are separate reviewed PRs afterward. Existing
receipt/history navigation and French/English labels must continue working.

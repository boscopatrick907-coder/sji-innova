# Willy and Charles — next work

Updated 6 November 2026. This is the single current assignment file for both of
you. Willy follows the Stock section; Charles follows the Sales section. The
previous separate briefs have been removed. Supporting technical documents are
references, not extra assignments.

## First: update your local copy

Your local copy is the project folder on your own computer. Updating it downloads
the team's merged work from GitHub. Wait until this documentation PR is merged.
Close Cella, open a terminal in your existing sji-innova folder, and run:

```sh
git status
```

If there are modified files or unfinished work, ask your AI editor to help commit
or safely stash that work first. Do not discard it or use reset --hard.
With a clean working tree, run:

```sh
git switch main
git pull --ff-only origin main
```

If either command fails, stop and resolve the message with your AI editor before
continuing. Do not force-push. Create your own branch only after updating main:

Willy:

```sh
git switch -c desktop/stock-recovery
```

Charles:

```sh
git switch -c desktop/sales-history
```

If the branch already exists, inspect it with your AI editor rather than deleting
or resetting it. Java and javac must both report version 21; use the repository's
Maven wrapper. SQLite does not need a separate installation.

## One prompt to give your AI editor

Replace NAME with Willy or Charles:

> I am NAME. Read docs/team/NEXT_WORK.md and follow only my role's section.
> Help me preserve unfinished changes, update my local copy and create my branch
> using the instructions above. Read docs/team/CONTEXT.md and the technical
> references listed for my role. Inspect current code before editing. Explain
> each step simply. Implement my first milestone, test it, and report what works
> and what remains. Preserve collaborators' commits. Do not push or merge unless
> I ask you to.

## Work in parallel

Willy owns desktop/stock/; Charles owns desktop/sales/. Coordinate any shared
ShellPanel edits so neither replaces the other's work. Deliver the first milestone
for review before moving to the next. Patrick reviews and tests the combined app.

## Willy — Stock milestones

Do not restart
Products or rebuild the receipt panel. Your original commit `739798f` is preserved
in PR #27, followed by review corrections and current-main integration.

### What is delivered by PR #27 plus corrections

Stock is reachable from the sidebar with real injected services. Receipts,
available quantities and movement history work. Review fixes reject fractional
bags before saving, keep the selected/pending product across refresh, distinguish
known validation rejections from uncertain saves, and retain signed corrections
in history. French/English labels and variant references are included.

PR #27 is merged. Update main with a clean working tree. Preserve uncommitted
work first. Use the new `desktop/stock-recovery` branch created above; do not reset or
force-push the old branch. Read README.md, docs/team/CONTEXT.md, docs/backend/STOCK.md,
docs/backend/PRODUCT_FAMILIES.md and the actual code. Java 21 is required.

### Next milestone A — receipt recovery across restart

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

### Next milestone B — signed stock corrections

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

### Next milestone C — editable low-stock thresholds

Use StockService.setMinimum. Zero is valid; piece/bag thresholds must be whole.
Explain that lowStock() means quantity <= minimum; use a label/icon as well as
color. Refresh displayed stock after success and on entering the screen, preserving
selection by product UUID. Do not confuse a loading failure with zero stock.

Acceptance: set a minimum of 2; verify 3 is normal, 2 and 0 are low. Reopen and
verify the threshold persists. No schema change or manual SQL is needed.

### Ownership and delivery

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

### Current examples to inspect, not blindly copy

CSV PendingImportStore shows request/snapshot persistence; Sales
PendingCheckoutStore/CheckoutFileLease show ownership-aware cleanup and process
coordination after PR #30. Use the current corrected versions. Preserve the receipt
operation UUID, shop/product/device IDs, canonical unit, quantity and normalized
reason exactly. Recovered receipts never submit automatically. Wrong-shop/device
and damaged files must block submission and remain available for recovery.

Deliver milestone A first; B and C are separate reviewed PRs afterward. Existing
receipt/history navigation and French/English labels must continue working.

## Charles — Sales history and sale details

Current assignment: 6 November 2026. Cash checkout is delivered and merged in
PR #30, including the follow-up pending-file ownership fix. Do not rebuild the
basket, shared services, recovery store or Products screen.

### Goal in plain language

A cashier can find a sale made earlier, open it, and see exactly what was sold,
the quantities, charged prices, total and whether the sale was later voided.
Restarting Cella must not lose the history. This is a read-only history milestone.

### Start

Follow the update instructions above and use your `desktop/sales-history` branch.
Read README.md, docs/team/CONTEXT.md, docs/desktop/SALES_SCREEN.md and
[the backend sales contract](../backend/SALES.md). Inspect SaleService,
SaleState, SaleItem and the existing SalesPanel/Checkout before editing.

Own desktop/sales/ and its tests. Patrick owns desktop/catalog/ and Willy owns
stock/. Coordinate shared ShellPanel changes before editing. Preserve the green
sidebar, cash-checkout workflow, stable context and existing navigation.

### Milestone 1 — browse persisted sales

- Add an obvious “Historique des ventes” entry inside Ventes, with a clear return
  to the cashier basket. Browsing must not clear, submit or alter an unfinished
  basket, confirmation or uncertain checkout. Do not silently abandon pending
  recovery; disable history temporarily during an active write if needed.
- Load shop-scoped history through SaleService.listSaleStates off the Swing EDT.
  Display receipt reference, date/time, total in whole FCFA and effective status.
  Query state, not only raw Sale: immutable original sales also include voided ones.
- Show recent sales first with a deterministic tie-breaker, without modifying
  service history. Search by receipt reference; filter paid/voided and a date range.
- Keep stored timestamps unchanged. Display dates in a documented local time zone
  (shop pilot: Africa/Douala), and apply day/date filters consistently in that zone.
- Give loading, empty, no-results, error/retry and refresh states. A database error
  must not look like an empty sales list. Ignore superseded query results.
- Preserve search/filter/selection when returning from details. Use French/English
  bundles; status should be understandable text, not color alone.

### Milestone 2 — inspect one sale

- Open a selected receipt with Enter, double-click or a labelled Open action.
- Use persisted sale-item snapshots: product name, unit, quantity, charged unit
  price and line subtotal. Never rebuild an old receipt using today's catalog
  price/name or recalculate its saved total. A changed/grouped product must not
  change what the historical sale displays.
- Show receipt, sale date, cash payment, saved total and PAID/VOIDED effective state.
  Buying prices must not appear. Piece/bag quantities are whole; kg/litre can be
  fractional. Render quantities from integer thousandths, never float arithmetic.
- For a voided sale, show its recorded reason/date from the event state; clearly
  identify it as cancelled. Do not erase the original sale or label it paid.
- Add a clear back action to the filtered list, then back to the preserved basket.
  Read-only browsing must not change stock, sale events or pending checkout files.

### Acceptance Patrick can demonstrate

1. Prepare disposable stock, complete two cash sales, restart Cella and find both
   in history with their exact references and totals.
2. Search one reference, open its detail and verify every saved line, fractional
   rounding result and total against the service result.
3. Create a void through the existing backend in test setup: show that sale as
   VOIDED with its recorded reason, while retaining the original lines. Stock
   and audit/history remain unchanged by opening the screen.
4. Change a catalog price/name in controlled test setup: historical details still
   show the original saved values. Do not implement product editing just for this.
5. Preserve a nonempty editable basket across history/detail/back navigation.
   Preserve an uncertain checkout's request/ID and recovery safeguards too.
6. Cover date-boundary filtering in Africa/Douala, both status filters, wrong-shop
   isolation, empty/error/retry and stale background query completion.

### Boundaries and safety

No void button, partial return, refund, receipt printing, tax, discount, credit,
Mobile Money, authentication or cloud sync in this milestone. Voiding UI is a
separate later task with explicit review. Do not add SQL to event handlers, change
core/schema or replace the service. Describe a missing contract to Patrick first.

Keep the corrected checkout lease and owner checks from PR #30: another window
must not overwrite or clear a different pending sale. Inspect the corrected code,
not your old feature branch. Coordinate receipt-store injection with Willy when
he touches shared navigation. Pascal can help translation and hands-on Windows QA.

### Verification and delivery

Run the full Java 21 wrapper clean verify and packaged headless startup from
README.md. Add meaningful temporary-SQLite integration/UI tests for the acceptance
cases; no live shop data. Test keyboard navigation, resize, labels and save/reopen
in the actual Windows window, reporting untested GUI checks separately.

Deliver a small reviewed PR per milestone with screenshots, branch/commit, exact
checks and remaining limitations. Preserve other people's commits. Do not push,
merge, force-push or change settings without your human's request. Explain each
step simply so Charles can explain the result to the team.

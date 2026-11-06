# Charles — Sales history and sale details

Current assignment: 6 November 2026. Cash checkout is delivered and merged in
PR #30, including the follow-up pending-file ownership fix. Do not rebuild the
basket, shared services, recovery store or Products screen.

## Goal in plain language

A cashier can find a sale made earlier, open it, and see exactly what was sold,
the quantities, charged prices, total and whether the sale was later voided.
Restarting Cella must not lose the history. This is a read-only history milestone.

## Start

Preserve unfinished work, update main and create `desktop/sales-history`.
Read README.md, docs/team/CONTEXT.md, docs/desktop/SALES_SCREEN.md and
[the backend sales contract](../backend/SALES.md). Inspect SaleService,
SaleState, SaleItem and the existing SalesPanel/Checkout before editing.

Own desktop/sales/ and its tests. Patrick owns desktop/catalog/ and Willy owns
stock/. Coordinate shared ShellPanel changes before editing. Preserve the green
sidebar, cash-checkout workflow, stable context and existing navigation.

## Milestone 1 — browse persisted sales

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

## Milestone 2 — inspect one sale

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

## Acceptance Patrick can demonstrate

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

## Boundaries and safety

No void button, partial return, refund, receipt printing, tax, discount, credit,
Mobile Money, authentication or cloud sync in this milestone. Voiding UI is a
separate later task with explicit review. Do not add SQL to event handlers, change
core/schema or replace the service. Describe a missing contract to Patrick first.

Keep the corrected checkout lease and owner checks from PR #30: another window
must not overwrite or clear a different pending sale. Inspect the corrected code,
not your old feature branch. Coordinate receipt-store injection with Willy when
he touches shared navigation. Pascal can help translation and hands-on Windows QA.

## Verification and delivery

Run the full Java 21 wrapper clean verify and packaged headless startup from
README.md. Add meaningful temporary-SQLite integration/UI tests for the acceptance
cases; no live shop data. Test keyboard navigation, resize, labels and save/reopen
in the actual Windows window, reporting untested GUI checks separately.

Deliver a small reviewed PR per milestone with screenshots, branch/commit, exact
checks and remaining limitations. Preserve other people's commits. Do not push,
merge, force-push or change settings without your human's request. Explain each
step simply so Charles can explain the result to the team.

## Prompt for your AI editor

> Read docs/team/CHARLES.md, docs/team/CONTEXT.md, docs/desktop/SALES_SCREEN.md and
> docs/backend/SALES.md. Cash checkout and its ownership fix are merged in PR #30.
> My next task is read-only sales history and details on desktop/sales-history.
> Inspect current main; preserve checkout, its pending-file protections and the
> basket when switching views. Use saved sale snapshots and effective void status.
> Explain each step simply, add meaningful tests and screenshots, and report
> limitations. Do not add void actions or push/merge without my request.

# Current feature verification — 6 November 2026

Application baseline: main commit `387ec908e62885a93821c27a0cbeebb0487f1077`.
This is a verification snapshot, not a replacement for individual assignments.

## Scope and method

Java 21 on Linux; Maven wrapper clean verify, packaged headless startup and real
SQLite integration tests with disposable folders. Swing panel tests execute actions
on the event thread and await background work. No live shop data was used.
Added ShopWorkflowTest connects startup, families, CSV, photos, stock, checkout,
reopening and backend voids in one disposable shop.

Final clean verification: **246 tests, 0 failures, 0 errors, 0 skipped**.
Packaged headless startup also passed.

## Results by feature

| Feature | Automated result and coverage |
| --- | --- |
| Startup and local storage | Pass: empty startup, persisted shop/device identity, reopening, migration checks and missing-identity safeguards |
| Products and variants | Pass: details/specifications, optional buying price, families, unique references, category/characteristic search, failed save retry |
| Catalog UI | Pass: navigation/search/back, exact variant selection, stock read errors distinguished from zero |
| CSV import | Pass: read-only preview, invalid rows, duplicate consent, selected-row import, repeat confirmation and restart recovery after lost response |
| Product photos | Pass: managed copy survives source deletion, replacement/removal, rejected damaged input, persistence and recovery safeguards |
| Stock receipts and history | Pass: unit validation, quantities, signed history, in-session uncertain retry without duplicates and selected product retention |
| Cash sales | Pass: basket editing, whole/fractional units, FCFA rounding, confirmation, stock deduction, oversell rollback and saved receipt |
| Checkout recovery | Pass: restart recovery, lost response retry, no automatic submission, damaged state and pending-file ownership across JVM processes |
| Shared shell and languages | Pass for tested Products/Stock/Sales wiring and French/English assertions; not an exhaustive translation audit |
| Core adjustments, thresholds and sale voids | Pass in backend tests; these actions are not all exposed in desktop controls |

The combined scenario receives 10 rice bags and 5 litres of oil, sells 2 bags
and 0.5 litre, and checks 8 bags/4.5 litres remain after reopening. The total is
36,501 FCFA (36,000 plus 500.5 rounded to 501). A repeated sale request leaves
stock unchanged; an oversized sale changes nothing. Backend void returns stock
and retains the original sale. The second rice variant remains independently stocked.

## Current limitations confirmed by code

- Stock pending receipt lives in StockPanel memory. An uncertain receipt is not
  recoverable through that panel after restart yet: Willy's milestone A.
- Stock correction and threshold editing desktop controls remain future work.
- Sales history/detail browsing is not delivered in the desktop yet: Charles's task.
- CSV creates standalone catalog products; it does not import Excel workbooks,
  stock quantities, family mappings or photos.
- Photos attach to saved variants; family photos and adding a photo in the initial
  product form are not implemented.
- Buying-price discretion is not access control. Authentication, cloud sync,
  receipt printing and a user-facing backup/restore flow are not delivered here.
- README's older product-storage paragraph says family grouping is unimplemented;
  that sentence is stale. The later family section and code reflect current behavior.

## Limits of this verification

No interactive display server is available in this execution environment. This
check does not certify Windows GUI behavior, file chooser interaction, layout at
all sizes, visual polish, accessibility or usability for a shop secretary.
A physical power-loss test and live-shop performance trial were not performed.
Headless startup checks packaging/UI initialization, not an actual visible window.
No functional failure was reproduced in the automated scenarios listed above.

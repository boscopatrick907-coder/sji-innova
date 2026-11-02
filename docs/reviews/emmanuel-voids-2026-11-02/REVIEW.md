# Emmanuel cash-sale void review — 2 November 2026

Reviewed backend/sale-voids commit 194c481. This branch has not been merged into
main and no pull request was found in the fetched PR refs at review time.
No project source or collaborator branch was changed during this review.

## Verdict

The core cash-void milestone is substantially implemented and its normal path is
working. One numerical audit edge case should be corrected before approval.
This is a focused review, not a guarantee that every possible behavior was tested.

## P2: An unnecessary mixed-unit quantity sum rejects otherwise valid voids

File: core/src/main/java/cm/sji/innova/core/persistence/SqliteSaleRepository.java
Lines 239, 263 and the restoredQuantityMilli value in the audit JSON.
Source: https://github.com/boscopatrick907-coder/sji-innova/blob/194c481/core/src/main/java/cm/sji/innova/core/persistence/SqliteSaleRepository.java#L263

The void loop correctly checks each product's restored stock with Math.addExact,
but also sums all restored quantities into one long totalRestoredMilli. The audit
sum combines pieces, kilograms and litres into a meaningless single quantity.
It can overflow even when every individual product stock is valid.

Reproduced with disposable data and public services:

1. Create a kg product priced at 1 FCFA and receive Long.MAX_VALUE thousandths.
2. Create a piece product priced at 1000 FCFA and receive 1000 thousandths.
3. Sell both quantities in one sale; the sale total fits long and the sale succeeds.
4. Void the sale. Each product's restoration fits long, but the cross-product sum
   overflows at line 263. The void is rejected with ArithmeticException.
5. Rollback works: sale stays PAID and both stocks stay at their post-sale values.

This requires extremely large quantities; it is an edge case rather than a
normal shop-flow failure. It nevertheless rejects values accepted by the existing
API, and it is distinct from a genuine overflow in an individual stock value.

Correction:

- Remove the cross-product/cross-unit long quantity aggregate as a condition for
  completing a void. Keep checked arithmetic for each actual product stock.
- Audit the item count and per-item/per-product quantities with their units, or
  omit the meaningless aggregate. Do not rewrite earlier immutable audit records.
- Add a regression proving a mixed-product void succeeds when each stock and the
  financial sale total fit long, even if a redundant cross-product sum would not.
- Retain the existing test proving genuine per-product overflow rolls back the
  entire operation.

## What was verified

- One transaction and write lock protect void checks and all restoration writes.
- Existing sale and item records are retained and protected from update/delete.
- CREATED and VOIDED events, deterministic legacy backfill and immutable audit log.
- Explicit sale/item/event/movement links; duplicate receipt text is not the link.
- Same request replay, changed request rejection and second-void rejection.
- Concurrent requests restore stock once; saved quantities and fractional units
  are restored; mid-write failures roll back; checkout retries do not reactivate.
- Schema-v4 upgrade/backfill, repeat initialization and failed-migration rollback.
- Existing checkout retry and HALF_UP rounding behavior remains tested.
- Development actor attribution is explicitly documented as unverified identity;
  actual authentication, refunds and desktop cancellation UI remain out of scope.

## Evidence and remaining checks

Branch clean verify: 52 tests, 0 failures, 0 errors, 0 skipped. The packaged
headless startup check also passed. The GitHub branch build run 41473508266 was
successful. Git merge-tree found no file conflicts with current main; a build
of that final integrated tree was not performed in this review.

Normal acceptance: receive 10 -> sell 2 -> void -> 10, with history retained,
is included in the passing integration tests. No real Windows GUI cancellation
flow exists or was tested. There is no stock-screen or physical cash-refund claim.

After correcting the finding: rerun clean verify and packaged smoke checks, open
or update the backend/sale-voids PR, and request another review before merging.

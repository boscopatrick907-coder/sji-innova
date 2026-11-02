# Emmanuel — correction brief for your cash-sale void implementation

**Owner:** Emmanuel Oyono. **Reviewer/coordinator:** Patrick.
**Review date:** 2 November 2026.
**Reviewed code:** backend/sale-voids, commit `194c481`.
Read the [full review evidence](REVIEW.md) and your [original task](../../team/EMMANUEL.md).
This is one focused correction to your existing work, not a request to rebuild it.

## 1. What you already completed

The reviewed implementation passed 52 tests and its GitHub branch build. It has:

- Atomic cash-sale voids with positive stock-restoration movements.
- Preserved original sales/items, append-only events and audit records.
- Durable sale/item/event/movement links.
- Identical-retry protection, conflicting-retry rejection and single-void guards.
- Concurrency, rollback, fractional-unit and schema-v4-to-v5 migration tests.

Keep that work. No desktop void screen, physical cash refund, authentication or
cloud sync is required by this correction. Passing tests do not cover the edge
case below yet. The report is tied to commit 194c481; inspect your current code.

## 2. Give this prompt to your AI

> Read docs/reviews/emmanuel-voids-2026-11-02/EMMANUEL.md and REVIEW.md, then
> inspect my latest backend/sale-voids code and local AGENTS.md instructions.
> Reproduce the audit-only mixed-unit aggregate overflow in a temporary database.
> Fix only this finding: remove the combined quantity requirement while retaining
> checked per-product stock arithmetic, complete rollback and immutable audit
> records. Add a regression test that fails before the correction and passes
> afterward. Preserve the existing genuine stock-overflow test and all void/retry/
> rounding behavior. Explain the changes simply and report exact test results.
> Do not rewrite my feature, change other developers' code, push or merge without
> my request.

If these files are not yet on main, download both Markdown files from the review
branch and give their text/paths to your AI. Do not merge unrelated documentation
into your feature just to read a task brief.

## 3. Understand the issue

Your void loop correctly calculates each product's restored stock:

```java
long afterStock = Math.addExact(currentStock, item.quantityMilli());
```

It also maintains an audit-only sum across every product:

```java
long totalRestoredMilli = 0L;
// Inside the loop:
totalRestoredMilli = Math.addExact(totalRestoredMilli, item.quantityMilli());
```

That second sum combines unrelated units: shirts, kg of rice and litres of oil.
There is no meaningful single stock quantity for that mixture. Worse, it can
overflow a long even when every individual product stock is valid. Since it is
inside the transaction, the overflow rejects the entire void unnecessarily.

At the reviewed commit, inspect SqliteSaleRepository.java around lines 239, 263
and the restoredQuantityMilli field in the audit new_value JSON. Locate by symbol
if line numbers have moved.

This requires extreme quantities: it is not evidence that normal shop voids fail.
Rollback worked in the reproduced case, so the sale/data were not corrupted.

## 4. Reproduce with disposable data

Use the public services and a temporary database, never real shop data:

1. Create a kg product with a selling price of 1 FCFA per kg.
2. Receive `Long.MAX_VALUE` thousandths for that product (supported by the current
   kg quantity representation).
3. Create a piece product at 1000 FCFA and receive 1000 thousandths (one piece).
4. Sell those two quantities in one cash sale. Its rounded financial total fits
   long, and both stocks become zero.
5. Void that sale. Restoring each individual stock fits long, but the redundant
   cross-product sum overflows at the audit aggregate.

Observed on 194c481: ArithmeticException at totalRestoredMilli; sale remains PAID,
no void/audit survives, and stocks remain at their post-sale values.
Expected after correction: VOIDED, each product restored, exactly one audit/event
and one restoration movement/link per sale item.

Do not replace the extreme values with a fabricated direct SQL cache change:
this scenario must remain valid through the real product/stock/sale service flow.

## 5. Required correction

- [ ] Remove the cross-product/cross-unit long total as a condition of void success.
- [ ] Keep `Math.addExact(currentStock, item.quantityMilli())` or equivalent checked
      arithmetic for every actual product stock update, including repeated lines.
- [ ] For audit output, keep the restored item count and either omit the meaningless
      aggregate or record per-item/per-product quantities with product IDs and units.
      An exact per-item breakdown is preferable if quantities are displayed.
- [ ] Do not simply change the mixed-unit total to double or clamp/truncate it.
      That hides the overflow and still mixes incompatible units.
- [ ] Do not rewrite previous immutable audit entries or remove append-only triggers.
- [ ] Keep one transaction covering event, movements, links, cache and audit insert.
- [ ] Keep request identity/replay rules, wrong-shop rejection and concurrency guards.
- [ ] Update docs/backend/SALES.md to describe the resulting audit representation.

The audit JSON is TEXT today, so removing/changing a field in newly written audit
payloads should not itself require a schema migration. Inspect actual consumers
first; preserve compatibility with old payloads wherever they are read.
Do not create a new migration merely to change a JSON string.

## 6. Tests and acceptance

- [ ] Add the two-product regression above. Verify it fails on the reviewed code
      specifically at the redundant sum, then passes after the correction.
- [ ] Assert original sale/item values remain unchanged.
- [ ] Assert each product's restored quantity is correct and history agrees.
- [ ] Assert one void event/audit and the correct item/movement links are committed.
- [ ] Retry the same void: no second restoration, same saved void result.
- [ ] Keep the existing genuine per-product overflow test. If an actual product
      cannot accept its restoration, the complete operation must still roll back.
- [ ] Keep existing mid-transaction audit-write failure tests and migration tests.
- [ ] Check audit output has no misleading cross-unit quantity. If adding per-item
      JSON, verify it is valid JSON and carries the intended identities/units.
- [ ] Run full clean verify and the packaged headless startup check.

From the repository root on Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
java "-Djava.awt.headless=true" -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar --smoke-test
```

On Linux/macOS use `./mvnw`. Activate JDK 21 first. Cloud-specific cache/proxy
instructions are in README.md; do not copy those paths to a personal computer.
No separate SQLite installation is required.

Baseline for the reviewed branch is 52 tests, not necessarily the count after
rebasing on newer main. State actual passed/failed/errors/skipped/unrun counts;
never disable the old overflow test to get a green build.

## 7. Git workflow and evidence

Inspect git status, preserve unfinished work and continue your existing
backend/sale-voids branch. If its PR is still open, push the correction there when
you authorize it; an existing PR updates automatically. If there is no PR yet,
open one from backend/sale-voids to main after the correction. If the feature has
already merged, use a new focused fix branch from current main instead.
Do not reset, force-push, modify someone else's branch or merge automatically.
Cloud tasks are already isolated; no extra worktree unless explicitly requested.

Suggested commit: `Avoid mixed-unit audit overflow when voiding cash sales`.
Suggested PR title for the overall feature: `Add audited cash-sale voids`.

Fill this section with evidence before requesting review:

- Current branch/base commit:
- Failing regression before correction:
- Code/audit representation changed:
- Passing regression after correction:
- Full build/test counts:
- Packaged startup check:
- Existing true stock-overflow rollback test result:
- Commit / pull request:
- Remaining limitations / unrun checks:

Keep the full review report as historical evidence; append results instead of
rewriting the original finding to say it never happened. Patrick can request a
fresh review after you publish the correction. No messages or credentials should
be sent automatically by the AI.

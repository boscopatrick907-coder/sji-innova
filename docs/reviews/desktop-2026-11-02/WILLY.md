# Willy: Products review and correction tasks

**Priority: prevent unsaved-input loss and strengthen behavior tests.**
Read [the evidence](REVIEW.md) and your [original brief](../../team/WILLY.md).
Your reviewed commit is `55f647f`; inspect your latest branch first.

## What you already delivered

ProductPanel with the agreed constructor, full product inputs, add/remove
specification rows, real ProductService calls, whole-FCFA parsing, unknown buying
price, canonical unit codes, background saving/loading, loading/error states and
translation hooks. You respected the desktop/core boundary.

## Reproduced input-loss issue

Only Save is disabled while a save runs; the form remains editable. On success,
clearForm unconditionally erases whatever is currently in the fields.

Reproduce using a disposable DB and a controllably delayed repository:

1. Enter First product and price 500, then click Save.
2. Hold the repository insert using a test latch, not timing guesses.
3. While the request is pending, enter Second product in the form.
4. Release the first save.
5. Observed: First product saves; Second product text disappears without saving.

Relevant file: products/ProductPanel.java, saveProduct/done/clearForm.

## Corrections to make

- [ ] Choose and implement one consistent behavior: disable ALL editable form,
      unit and specification controls while saving, or track the captured values
      and never clear fields that have been edited since the request started.
- [ ] Include add/remove specification buttons in the design; disabling only text
      fields still leaves ways to change or lose the next request's data.
- [ ] Restore usable controls on success/failure. Failed saves preserve input.
- [ ] Keep JDBC work in SwingWorker and UI changes on the EDT.
- [ ] Add a deterministic slow-save regression test using synchronization/latches.

## Strengthen tests to prove the assigned feature

Current tests seed through the backend, construct ProductPanel, then query the
backend again. That does not prove Save or the displayed product list works.

- [ ] Fill fields and trigger the actual Save action; await worker completion and
      verify the row in a temporary SQLite database.
- [ ] Verify the displayed list contains the saved data after loading completes.
- [ ] Verify unknown buying price remains null and zero remains zero.
- [ ] Cover negative/noninteger/overflow prices and duplicate specification names:
      useful feedback, no invalid inserted row, and retained input.
- [ ] Simulate a persistence failure and check input/control recovery.
- [ ] Check duplicate submission is prevented during a pending save.
- [ ] Await workers and release resources so temporary directories can be cleaned.
      Remove permanent CleanupMode.NEVER once lifecycle handling is correct.
- [ ] Report actual window tests separately; do not call a headless assertion a
      Windows GUI test. Avoid blocking JOptionPane dialogs in headless tests by
      choosing a suitable testable feedback design or actual GUI test setup.

Coordinate shell wiring with Charles. Do not modify his entry point, SQL/core
services or add unrelated checkout, stock-editing or product-deletion features.

## Evidence to fill after working

- Fix commit / PR:
- Reproduction before / after:
- New behavior test names/results:
- Build command, test counts and result:
- Windows version / JDK / checks actually performed:
- Remaining untested behavior:

Keep unchecked items pending and have another person review your update.

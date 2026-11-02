# Cella desktop review — 2 November 2026

Reviewed Charles PR #11 at 20ac3e6 and Willy PR #10 at 55f647f against the
CHARLES.md/WILLY.md/PASCAL.md briefs. Their branches were combined only in a
temporary source snapshot, not merged into main or changed on GitHub.

## Findings to fix

### High priority — missing shop identity silently hides existing data (Charles)

AppContextLoader calls ShopIdentity.loadOrCreate before inspecting the existing
database. When cella.db remains but shop-id.txt is missing, startup creates a new
UUID and succeeds. Product queries now filter by the new shop ID, so previously
saved products disappear from the application's view. They are not deleted.

Reproduction using disposable data: load context, create a product, remove only
that disposable shop-id.txt, load again. Observed: shop UUID changed, the new
context returned 0 products, and the old shop still had 1 product in the database.

Correction: distinguish a genuinely new installation from a database with existing
shop data. Do not invent a new identity for an existing populated database. Show
an actionable recovery error, or provide an explicit validated recovery process.
Add a regression test preserving existing data and identity-file state.

Source: https://github.com/boscopatrick907-coder/sji-innova/blob/20ac3e6/desktop/src/main/java/cm/sji/innova/desktop/shell/AppContextLoader.java#L22
Related: https://github.com/boscopatrick907-coder/sji-innova/blob/20ac3e6/desktop/src/main/java/cm/sji/innova/desktop/shell/ShopIdentity.java#L21

### Medium priority — edits made during save are erased (Willy)

Only the Save button is disabled during persistence. Name/price/specification
controls remain editable. Successful completion unconditionally calls clearForm.

Reproduction: delay repository.insert, click Save for First product, type Second
product while saving, release the write. Observed: First product was correctly
saved, but the newly typed Second product text was erased. No second product was
saved. This is lost unsaved input, not a failed persistence operation.

Correction: disable all editable form/specification controls while the captured
request is saving, or clear only if the form still matches that request. Restore
controls on success and failure and retain failed input. Add a controlled slow-save
regression test; do not rely on timing sleeps.

Source: https://github.com/boscopatrick907-coder/sji-innova/blob/55f647f/desktop/src/main/java/cm/sji/innova/desktop/products/ProductPanel.java#L290
Clear: https://github.com/boscopatrick907-coder/sji-innova/blob/55f647f/desktop/src/main/java/cm/sji/innova/desktop/products/ProductPanel.java#L321

### Testing gap — Products UI behavior is not sufficiently verified (Willy)

The test named panelInitializesAndLoadsExistingProductsHeadlessly seeds products
through the service, constructs the panel, then reads the service again. It does
not wait for or inspect the rendered list, click Save, or test rejection/failure
paths. Therefore its green result does not establish the form's acceptance criteria.

Add meaningful checks for actual Save-to-SQLite, displayed results, unknown versus
zero buying price, invalid/overflow prices, duplicate specifications, save failures
preserving input and duplicate-click prevention. Use temporary databases and
synchronize with worker completion. CleanupMode.NEVER should not be used as a
permanent workaround for workers not being awaited; complete/close workers and
allow test cleanup.

Source: https://github.com/boscopatrick907-coder/sji-innova/blob/55f647f/desktop/src/test/java/cm/sji/innova/desktop/products/ProductPanelTest.java#L32

## What meets the briefs

Charles: one main window, navigation slot, background startup, retry/exit error
page, configurable stable data directory, persisted shop identity on normal
restarts, temporary smoke-test data, preserved demo mode, translation hooks and
startup/context tests. Keeping a Products placeholder is allowed by his initial
brief until Willy's panel is integrated.

Willy: required injected-service ProductPanel constructor, full product fields,
flexible specification rows, whole-FCFA validation, canonical units, blank buying
price as null, real ProductService usage, background save/load, Save disabled while
running, empty/error/loading states, translated-label hooks and HTML escaping of
product text. No core/migration changes or unassigned checkout work were added.

Pascal: no published translation branch, resource bundles or PR found. Work on
his own machine cannot be assessed from this repository; ask him to push it.
French UI is not delivered merely by providing a --lang option: existing fallbacks
are English until real resource bundles are added.

## Integration and verification

Git's merge-tree combined the two branches without file conflicts. The temporary
combined snapshot passed clean verify: 52 tests, 0 failures, 0 errors, 0 skipped.
The packaged headless startup check also passed. The two targeted behavior probes
above reproduce issues despite those passing tests. No real Windows GUI session
was exercised in this review.

Charles's ProductsSlot still deliberately returns a placeholder. After fixes and
review, integrate ProductPanel(context.products(), context.shopId(), messages.bundle()),
then test on Windows: open, create full product, save, close, reopen, verify the
same product/details/shop. Pascal should add both initial product keys and the
new shell.* keys Charles documents. Run the combined tests and manual checks
before marking the shared desktop pilot complete.

## Verdict

Charles: substantial first-task implementation; fix identity recovery and finish
manual GUI acceptance before approval.
Willy: substantial first-task implementation; fix input-loss behavior and improve
behavior tests before approval.
Pascal: not assessable until his deliverables are uploaded.

This is a focused review of the assigned desktop milestone, not an exhaustive
security or performance audit. No project files or collaborator branches were
modified; no PR was approved or merged by this review.

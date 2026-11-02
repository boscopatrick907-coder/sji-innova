# Charles: shell review and correction tasks

**Priority: fix identity recovery before approval.**
Read [the evidence](REVIEW.md) and your [original brief](../../team/CHARLES.md).
Your reviewed commit is `20ac3e6`; inspect your latest branch before changing it.

## What you already delivered

Main window/navigation, background startup, configurable data folder, persistent
shop ID on ordinary restarts, error/retry/exit UI, translation hooks, smoke mode
and startup/context tests. The Products placeholder was allowed by your first
brief; it is not itself a defect. No stock or sales screen is required by this fix.

## Reproduced issue

AppContextLoader calls ShopIdentity.loadOrCreate even when cella.db already has
shop data. If shop-id.txt is missing, a new UUID is created and startup succeeds.
Queries then show an empty shop, although the old products are still in the file.

Reproduce using **disposable data only**:

1. Initialize a local context in a temporary folder.
2. Save a product through the ProductService using that context's shop UUID.
3. Remove only that temporary shop-id.txt, leaving its database intact.
4. Initialize again. The reviewed code silently returns a new UUID and no products.
5. Query the old UUID: the original product still exists. Nothing was deleted.

Relevant files: shell/AppContextLoader.java and shell/ShopIdentity.java.

## Corrections to make

- [ ] Distinguish a fresh installation from an existing database with shop data.
- [ ] If existing data has no valid matching shop identity, stop with a clear
      recovery error, or implement an explicit validated recovery workflow.
      Never guess a new identity and present an empty shop as normal startup.
- [ ] Do not delete/change the existing DB or overwrite a damaged ID file.
- [ ] Preserve normal first launch, normal restart, retry, --data-dir and demo/smoke modes.
- [ ] Add a regression test for existing data + missing identity. Verify records
      remain and no silent new identity is persisted on that failing startup.
- [ ] Keep tests for a genuinely new folder, an existing valid identity and a
      malformed identity file. Review all writes on failed initialization.
- [ ] Keep blocking database/identity checks off Swing's EDT.

If the safest solution needs a core/schema change, coordinate it with Emmanuel;
do not silently expand the desktop PR into a database migration.

## Integration, after Willy's changes are ready

- [ ] Replace the ProductsSlot placeholder with
      `new ProductPanel(context.products(), context.shopId(), messages.bundle())`.
- [ ] Coordinate Pascal's product and shell translation keys.
- [ ] Run combined build/tests and packaged smoke check. If ProductPanel starts
      async work during smoke mode, await it before cleaning temporary files.
- [ ] Test the actual Windows window: launch, navigate, save a product, close,
      reopen with the same data directory and verify the same details/shop ID.

## Evidence to fill after working

- Fix commit / PR:
- Regression test name and result:
- Build command, test counts and result:
- Packaged startup result:
- Windows version / JDK / GUI checks actually performed:
- Remaining untested behavior:

Keep unchecked items pending. Have another collaborator review the correction.

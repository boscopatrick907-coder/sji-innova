# Emmanuel — product photos (delivered backend brief)

Historical assignment: backend delivered in `5a3c942`, with follow-up review fixes.
For current work read [EMMANUEL.md](EMMANUEL.md); CSV import is next.
Owner: Emmanuel.
Patrick owns the photo controls in Products; Charles/Willy retain Sales/Stock.
Branch suggestion: `backend/product-photos` from updated main, not an old branch.
This is a proposed new feature. CSV image URLs/remote downloads are out of scope.

## Goal in plain language

A shopkeeper chooses a local image. Cella copies it into its own managed data
folder and associates it with the right product. Moving/deleting the original
image must not break Cella's copy. Products without a photo remain fully usable.

## Milestone A — storage and service contract

Agree with Patrick a small service for get/set/replace/remove of one optional
photo per product, scoped to shop/product IDs. Decide how a newly created product
gets a photo: creating the product and attaching the image are separate outcomes
unless an atomic combined contract is explicitly implemented. If attachment fails,
tell the user the product exists and allow retry without recreating the product.

Store managed files beneath the configured Cella data directory, using generated
names and relative references. Never store only the original absolute path or
trust a supplied path/filename to choose a destination. Validate shop/product
ownership before access or mutations; filtering is not authentication.
Inspect existing schema first; add a versioned migration only if needed. Preserve
all existing product IDs, stock, sales and events. Do not resurrect stale version
numbers from older docs or silently overwrite a newer schema.

## Milestone B — reliable local storage

- Accept JPEG/PNG only; verify actual decoding/content, not just the extension.
- Proposed input limit: 5 MiB and 20 megapixels; inspect dimensions before full
  decode and bound memory use. Document limits and return friendly errors.
- Create normalized managed images/thumbnails without carrying unnecessary source
  metadata. No network URLs, SVG or executable content in this milestone.
- Decode/copy/resize off the Swing EDT; return a usable thumbnail/full-image
  reference or data contract for Patrick, without coupling core to Swing controls.
- Optional photo: absence returns an explicit empty result for a placeholder.
  Missing/corrupt managed files produce a recoverable warning, not application
  startup failure or a false claim that the photo loaded successfully.
- Replacement failure preserves the existing usable photo. Removal detaches the
  reference and schedules safe cleanup; do not delete another product's file.
- SQLite and filesystem writes are not one atomic transaction. Stage and validate
  files first, publish safely, update references transactionally, and only clean
  old files after commit. Document/test failure windows, crash recovery and
  orphan cleanup. Preserve the last committed photo; never delete files by guessing.
- Make retry and concurrent replacement behavior explicit. Return the committed
  result so the UI does not confuse a failed response with an absent photo.
- Keep managed paths portable with the app data directory. Document a consistent
  backup/restore procedure for BOTH database and image files; copying only SQLite
  is insufficient. Do not imply WAL files alone are a backup.

## Acceptance tests

1. Attach photo, delete the source, reopen Cella: the managed photo still loads.
2. No photo returns an empty result; missing/corrupt file is handled clearly.
3. Invalid/oversized images and traversal-style names cannot write outside the
   managed directory or replace an existing good photo.
4. Successful replace/remove persists after reopen without touching other products.
5. Simulated copy/publish/database failures preserve last committed state; orphan
   recovery is safe and repeatable. Check wrong-shop requests and concurrency.
6. Backup/restore the documented complete data folder into a new location and
   verify associations work. Existing schema migration and core tests still pass.

## Deliverables and boundaries

Deliver service/API, any necessary migration, temporary-file integration tests
and docs/backend/PRODUCT_PHOTOS.md with examples for Patrick. No ProductPanel,
Stock/Sales UI, gallery, cloud storage/sync or CSV-photo support. The design assets
under docs/design/ are references; browser-selected images there are in memory
and do not implement this service.

## Working and delivery rules

Read README.md, CONTEXT.md, the current code and applicable AGENTS.md. Preserve
unfinished work; start a small feature branch from updated main after this brief
merges. Keep Java 21 and the existing stack. No UI, sync or authentication in this
task. Do not alter stock/sale behavior or rebuild another developer's screen.
Coordinate core/schema changes with Patrick and preserve existing databases.
All desktop service calls will run off Swing's EDT. Errors must be actionable,
not silently swallowed. No real shop files or personal photos in Git/test fixtures.

Run the full wrapper clean verify and packaged smoke test from README.md. Use
meaningful temporary-file/database tests, including reopen and failure cases.
Report actual commands/results, limitations, migration behavior and a small Java
calling example for Patrick. Deliver one reviewed PR per milestone. Do not push,
merge, force-push or modify repository settings without your human's request.
Explain each step simply; do not claim a proposed interface already exists.

## Prompt to paste into your AI

> Read docs/team/EMMANUEL_02_PRODUCT_PHOTOS.md and CONTEXT.md. CSV import must be
> reviewed first. Inspect current code and agree a photo service with Patrick.
> Implement one optional managed local photo per product with safe replacement,
> validation, recovery, migration tests and backup documentation. Explain each
> step simply. Do not change desktop screens or push/merge without my request.

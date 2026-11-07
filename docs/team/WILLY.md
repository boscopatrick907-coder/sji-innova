# Willy — current assignment

Updated 7 November 2026. This is the only current assignment file named after
Willy. Follow this file; older role briefs and the combined NEXT_WORK.md have
been removed. Technical references provide context, not additional assignments.

## First: update your local project copy

Wait until the pull request containing this brief and the shared style is merged. Close Cella and open a
terminal in your existing sji-innova project folder. Run:

```sh
git status
```

If there are modified files or unfinished work, ask your AI editor to help commit
or safely stash that work first. Do not discard changes or use reset --hard.
With a clean working tree, run these commands one at a time:

```sh
git switch main
git pull --ff-only origin main
git switch -c desktop/stock-recovery
```

If any command fails, stop and resolve the message before continuing. If your
feature branch already exists, inspect it with your AI editor instead of deleting
or resetting it. Never force-push. The pull command downloads the team's merged
work; the last command creates your own branch for this assignment.

Both java -version and javac -version must report 21. Use the repository's Maven
wrapper; SQLite does not need a separate installation. Follow README.md to build
and launch the app.

## Required UI style for this assignment

Patrick approved the shared app style on 7 November 2026. Read and follow
[the implementation style guide](../desktop/STYLE_GUIDE.md) and
[the current Java previews](../desktop/VISUAL_POLISH.md). This changes presentation
requirements, not your functional milestone. Inspect current main rather than
copying an older screen or the standalone VentesPanel demo.

Reuse CatalogStyle and FeedbackStrip for cards, headings, buttons, arrows, search,
720px forms, aligned tables, rounded badges and typed messages. Do not create your
own palette or per-screen UIManager overrides. Keep the green sidebar/gradient.
Use French/English bundles, readable disabled controls and explicit keyboard actions.
Treat unavailable data as unavailable, never as zero or a successful empty result.

If you already started on your feature branch, preserve/commit or safely stash
unfinished work, skip the new-branch creation above, and merge the latest main:

```sh
git fetch origin
git switch desktop/stock-recovery
git merge origin/main
```

Resolve conflicts with your AI editor while preserving both your functional work
and the shared UI changes. Do not reset, force-push or recreate another person's
commits. This is important because Patrick changed Stock/Sales layout and shared
components while your task was in progress.

For Willy specifically:

- Preserve the current Stock title, capped receipt form, quantities/history cards,
  aligned columns and stock badges. Use stored minimum thresholds, including zero;
  do not copy a fixed five-item threshold or aggregate differently measured variants.
- Milestone A recovery gets a clear warning strip, the saved original details and
  a primary “Réessayer cette réception” action. The saved UUID/details remain locked
  until confirmation. Never retry automatically on startup or make uncertain
  state disappear when navigating. Wrong-shop/device or damaged requests stay
  blocked with an error explanation.
- Later corrections show current quantity, signed change, result and required
  reason before the existing service call. Confirm negative changes. Threshold
  editing uses the same 720px form/feedback rules and preserves the selected UUID.
- Use shared local date/time display and keep movement history append-only. Styling
  must not move file/SQLite work onto the EDT or change ownership/lease safeguards.

Include real Java previews at 960×600, 1100×720 and 1600×1000. Cover the empty,
loaded, failed and pending states relevant to your milestone. Run the Java 21
checks below. State which Windows/native-dialog/keyboard checks were not performed;
headless tests and component renders do not prove actual desktop interaction.
Coordinate shared helpers or ShellPanel changes with Patrick and the other developer.

## Prompt for your AI editor

> I am Willy. Read docs/team/WILLY.md, docs/team/CONTEXT.md
> and docs/desktop/STYLE_GUIDE.md. Preserve the approved app-wide style.
> Help me preserve unfinished changes, update my local copy and create my branch
> using this file's instructions. Inspect current code and the technical references
> below. Explain each step simply. Implement my first milestone, test it and report
> what works and what remains. Preserve collaborators' commits. Do not push or
> merge unless I ask you to.

Do not restart
Products or rebuild the receipt panel. Your original commit `739798f` is preserved
in PR #27, followed by review corrections and current-main integration.

## What is delivered by PR #27 plus corrections

Stock is reachable from the sidebar with real injected services. Receipts,
available quantities and movement history work. Review fixes reject fractional
bags before saving, keep the selected/pending product across refresh, distinguish
known validation rejections from uncertain saves, and retain signed corrections
in history. French/English labels and variant references are included.

PR #27 is merged. Update main with a clean working tree. Preserve uncommitted
work first. Use the new `desktop/stock-recovery` branch created above; do not reset or
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

## Current examples to inspect, not blindly copy

CSV PendingImportStore shows request/snapshot persistence; Sales
PendingCheckoutStore/CheckoutFileLease show ownership-aware cleanup and process
coordination after PR #30. Use the current corrected versions. Preserve the receipt
operation UUID, shop/product/device IDs, canonical unit, quantity and normalized
reason exactly. Recovered receipts never submit automatically. Wrong-shop/device
and damaged files must block submission and remain available for recovery.

Deliver milestone A first; B and C are separate reviewed PRs afterward. Existing
receipt/history navigation and French/English labels must continue working.

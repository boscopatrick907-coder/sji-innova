# Takou / Charles — restore and backup/settings

Updated 13 November 2026. This is the only current assignment file named after Charles. Patrick has
changed the work split: **Takou handles restore and backup/settings; Patrick and Codex handle the
shop workflow testing, usability fixes and the shared acceptance checklist.** This replaces earlier sales-history-only and whole-roadmap briefs.

## What is already merged

Receipt history/details/references, recovery acceptance and forced-close tests, recovery guidance,
and PRs #47–#49's folder coordinator, evidence decisions and checked backup-capture services.
Their original commits and the additive review corrections remain intact. Do not rebuild those services.
PR #50 also merged the approved shared styling and live local dashboard. Reuse them; do not rebuild them.

## Restore delivery review — PRs #53–#57

Your restore preparation, journal/recovery, startup, cancellation/status services and Settings screen
have been submitted as five stacked PRs. The review adds corrections without recreating your commits:
pre-photo-schema migration, strict ordinary staging paths, and gated metadata readers. See
[RESTORE_REVIEW.md](../desktop/RESTORE_REVIEW.md) for scope and verification evidence.

These submissions are **not merged yet**. Patrick reviews and merges #53, #54, #55, #56, then #57 after
their checks pass. Keep each original commit with the normal merge option; do not force-push or squash
the reviewed branches. Update an existing working branch safely rather than restarting your work.

## Your immediate work

1. **OFF-03c3 — connect Settings to the real app:** after the reviewed stack merges, add a translated
   Settings entry to ShellPanel, create the existing SettingsPanel with the current location/context,
   and call reload when the route opens. Keep Products as the initial screen. Reuse the approved
   styling and lifetime folder membership; no file/database work on the event thread. Add a small
   integration test proving the route opens and production services are used. Coordinate ShellPanel,
   shared messages and navigation assertions with Patrick before editing them.
2. **OFF-03c4 — remaining backup usability:** reminders, retention, destination persistence and any
   approved defaults still need implementation/acceptance. Review the existing backup/restore
   contract and resolve the product choices with Patrick; do not silently choose deletion defaults.
   Retention must never remove the last valid backup, pre-restore backups, quarantined generations,
   pending staging or anything a live journal references.
3. **OFF-03d — technical acceptance:** include corrupt/unsupported backups, pending destination work,
   interrupted restore and two-process exclusion on Windows/Linux. Deliver a real disposable-shop
   backup/restore comparison covering products, stock, sales, photos, evidence and device identity.
   Patrick and Codex supply the ordinary shop trial and shared checklist; those are not a substitute
   for your restore failure/restart tests.

Deliver one small reviewable PR at a time. Follow [BACKUP_RESTORE.md](../design/BACKUP_RESTORE.md)
and keep [OFFLINE_COMPLETION.md](../roadmap/OFFLINE_COMPLETION.md) honest about what remains.

## How Patrick and Codex will help

Your next deliverable after the review stack is **OFF-03c3 Settings integration**. Maintain the restore
contract and its failure/restart tests while connecting the existing screen.
Patrick and Codex will take the following supporting work:

- Exercise the ordinary shop workflow in a separate demonstration data folder: product families and
  variants, photos, stock receipts, sales, cancellations and dashboard totals.
- Close and reopen Cella and check that products, photos, balances and receipt history persist.
- Fix general usability issues and maintain the approved shared visual components. You own the
  functional restore/backup Settings controls, using those components.
- Prepare the shared manual acceptance checklist and expected before/after records for restore.
  A useful fixture is Riz with Basmati 5 kg and Basmati 25 kg variants, separate stock thresholds,
  photos, paid receipts and a cancelled receipt. Track each unit separately.

This supporting trial has not yet been completed. It does not replace your automated archive,
maintenance-lock, journal, interrupted-restore and two-process tests, or prove restore is ready.
You still own technical OFF-03d acceptance evidence for your implementation; Patrick and Codex own
preparing the shared checklist and running the ordinary shop/manual comparison when restore is available.

In each PR, provide a short summary of what works, commands and results, known gaps, and exact steps
for us to verify with disposable data. Report blockers and shared-file needs early. Do not wait for
our ordinary shop trial to start independent Settings integration work. If a supporting fix becomes
necessary for restore, coordinate it with Patrick instead of silently duplicating that work.

## Design ownership and file coordination

Patrick selected Atelier’s warm green/ivory palette with Comptoir’s clear sans-serif typography.
That direction is merged in PR #50; see [the shared style guide](../desktop/STYLE_GUIDE.md)
and [live dashboard contract](../desktop/DASHBOARD.md). The [two visual proposals](../design/visual-directions/index.html) remain reference mockups.
Patrick and Codex maintain the expanded sidebar, typography, shared components and dashboard. You do
not need to build competing mockups, redesign Products/Stock/Sales or implement the dashboard now.

Use current CatalogStyle and FeedbackStrip for functional Settings UI. Keep component construction
separate from restore/backup logic so the approved style can be applied later. Before editing
ShellPanel, CatalogStyle, FeedbackStrip, AppContext or common bundles, tell Patrick which files and
contracts you need; agree the small shared change first. Prefer a self-contained settings package.
Follow the approved shared tokens and components; keep restore/backup behavior independent of styling.

Read [STYLE_GUIDE.md](../desktop/STYLE_GUIDE.md), [DATA_FOLDER.md](../desktop/DATA_FOLDER.md),
[RECOVERY_EVIDENCE.md](../desktop/RECOVERY_EVIDENCE.md), [BACKUP.md](../desktop/BACKUP.md), and the actual
merged code. Prototype screenshots are not implementation instructions to replace the Java app with HTML.

## Update safely and preserve work

Close Cella. Run `git status` in the existing project. Preserve unfinished work by committing or safely
stashing it; never discard it, reset hard, recreate another person's commits or force-push.

With a clean worktree, update from main. If you have not started a restore branch, run one command
at a time (the assignment changes are available after their documentation PR merges):

```sh
git switch main
git pull --ff-only origin main
git switch -c desktop/backup-restore
```

If you already started restore, inspect that branch and merge current main safely instead of creating
another branch. Existing branches must not be deleted/restarted. Java and javac must be version 21;
use the repository Maven wrapper. SQLite needs no separate installation.

## Verification

Preserve immutable operation IDs, stored money/quantities, historical receipts, append-only movements,
folder locks and evidence dispositions. Never perform database/file/image work on the Swing event
thread. Keep all readers/writers and startup paths in the coordinator protocol; real path aliases
must not bypass it. No automatic replay of restored operations.

Run Java 21 `clean verify`, the packaged checks, and focused restore/process tests. Include Windows
and Linux CI evidence, screenshots for UI, actual native/manual checks and any limits separately.
Wait for background workers before deleting test folders or releasing membership; use bounded child
cleanup. Do not use live shop data for failure drills.

Patrick reviews before merge. Your editor should push/merge only when you ask it to.

## Prompt for your AI editor

> I am Takou (Charles). Read docs/team/CHARLES.md, docs/design/BACKUP_RESTORE.md and the current
> backup/coordinator/evidence implementation. PR #50 styling and dashboard are already merged.
> Patrick and Codex handle ordinary shop/restart testing, usability fixes and the shared checklist;
> My submitted restore/services/Settings work is in reviewed PRs #53–#57. My next task after merge
> is OFF-03c3 Settings integration, then the remaining OFF-03c4 work and technical OFF-03d evidence.
> Do not duplicate their supporting work or treat it as completed. Preserve all existing
> commits and behavior. Explain steps simply, start with a small Settings-integration PR and preserve actual
> failure/restart boundaries. Coordinate shared shell/style/context changes with Patrick before
> editing them. Do not rewrite the app or start a competing design. Do not push or merge unless I ask.

# Takou / Charles — restore and backup/settings

Updated 12 November 2026. This is the only current assignment file named after Charles. Patrick has
changed the work split: **Takou handles restore and backup/settings; Patrick and Codex handle the
shop workflow testing, usability fixes and the shared acceptance checklist.** This replaces earlier sales-history-only and whole-roadmap briefs.

## What is already merged

Receipt history/details/references, recovery acceptance and forced-close tests, recovery guidance,
and PRs #47–#49's folder coordinator, evidence decisions and checked backup-capture services.
Their original commits and the additive review corrections remain intact. Do not rebuild those services.
PR #50 also merged the approved shared styling and live local dashboard. Reuse them; do not rebuild them.

## Your immediate work

1. **OFF-03b — restore service:** follow [the corrected backup/restore contract](../design/BACKUP_RESTORE.md).
   Validate archives and staged migrations before active data changes; obtain exclusive ownership;
   verify a pre-restore backup; use the durable journal to recover coherent database/identity/photos/
   evidence after a kill at every boundary. Handle WAL/SHM and device identity as specified.
2. **OFF-03c — backup/settings functionality:** create/restore actions, destination, progress,
   confirmed results, last successful backup, reminders/retention and FR/EN wording. Resolve remaining
   product defaults with Patrick; do not describe an unimplemented restore or successful backup as ready.
3. **OFF-03d — acceptance:** restore a disposable shop into another folder and compare products,
   stock, sales, photos, evidence and identity. Include corrupted/unsupported backups, pending work,
   interrupted restore and two-process exclusion on Windows/Linux.

Deliver small reviewable PRs, not a combined rewrite. Keep the full checklist in
[OFFLINE_COMPLETION.md](../roadmap/OFFLINE_COMPLETION.md); later backlog work is planned with Patrick
after these milestones. No additional assignment to another collaborator is implied.

## How Patrick and Codex will help

Your next deliverable is a small **OFF-03b restore-service PR**. Keep your focus on the restore
contract and its failure/restart tests; Settings and backup controls follow after that service review.
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
our ordinary shop trial to start independent restore-service work. If a supporting fix becomes
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
> I own OFF-03b restore, then OFF-03c backup/settings and technical OFF-03d acceptance evidence.
> Do not duplicate their supporting work or treat it as completed. Preserve all existing
> commits and behavior. Explain steps simply, start with a small restore-service PR and test actual
> failure/restart boundaries. Coordinate shared shell/style/context changes with Patrick before
> editing them. Do not rewrite the app or start a competing design. Do not push or merge unless I ask.

# Desktop review: corrections and verification

Review date: **2 November 2026**. Reviewed snapshots:
Charles PR #11 (`20ac3e6`), Willy PR #10 (`55f647f`).
This is a review of those commits; later changes must be checked again.

## Start with your file

| Person | Action file | Responsibility |
|---|---|---|
| Charles | [CHARLES.md](CHARLES.md) | Safe identity recovery, shell and integration |
| Willy | [WILLY.md](WILLY.md) | Prevent input loss and test the real Products behavior |
| Pascal | [PASCAL.md](PASCAL.md) | Publish translations and record actual GUI checks |
| Everyone | [Full evidence and findings](REVIEW.md) | Understand why the changes are needed |

The full report is the original review record. Keep its reproduction/evidence
intact; add later verification results rather than rewriting history as passed.
Your action file is a checklist: complete items only after doing the work and
recording evidence. Download with GitHub's Raw/Download option, or read it directly
in your editor after this documentation is merged and you update from main.

## Give the file to your AI

Paste this, replacing YOUR_NAME with CHARLES, WILLY or PASCAL:

> Read docs/team/CONTEXT.md, my original role brief, and
> docs/reviews/desktop-2026-11-02/YOUR_NAME.md. Read REVIEW.md for evidence.
> Inspect my current feature branch and preserve existing work. Fix only my
> assigned findings; do not rewrite core services or someone else's screen.
> Reproduce each bug before fixing it, add a regression test, run relevant checks,
> and explain the change simply. Report manual Windows checks separately from
> headless checks. Do not mark tasks complete without evidence. Do not push or
> merge without my request.

## Branch and review workflow

Charles/Willy should normally update their existing open feature PR, so the fix
appears in the same review. If that PR has already merged, create a new fix branch
from current main. Inspect git status and preserve unfinished work first. Do not
reset, force-push or automatically merge. Pascal uses desktop/translations or a
new equivalent branch from current main.

If you want these review files locally before their documentation PR merges,
download your file and REVIEW.md from GitHub. Do not merge the documentation
branch into your feature just to obtain instructions; keep the code review focused.
Each person can update their checklist in their branch once the documentation is
available, or provide the same evidence in their PR description/comment.

## Shared acceptance after corrections

- Charles's missing-identity case no longer silently opens an empty new shop.
- Willy's slow-save case does not discard edits or permit confusing extra input.
- Product form tests exercise actual saves, list results and failure handling.
- Translations exist in French and English with all required keys.
- Wire the Products panel into the shell, then open/save/restart on Windows.
- Run clean verify and packaged smoke check on the combined version.
- Another collaborator reviews each change before merging.

The earlier combined snapshot passed 52 tests and headless startup; that did not
cover the two reproduced bugs. No percentage-complete or final approval is implied.

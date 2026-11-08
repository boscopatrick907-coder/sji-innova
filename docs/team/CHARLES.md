# Charles — complete the offline application and redesign

Updated 8 November 2026 by Patrick's explicit assignment. This is the only current assignment file named after Charles. It replaces the earlier sales-history-only brief.

## Your assignment

You own **all remaining offline work and the complete visual/interaction redesign** described in [OFFLINE_COMPLETION.md](../roadmap/OFFLINE_COMPLETION.md): OFF-01 through OFF-11, OFF-07A, and the later P2 extensions. Patrick reviews product choices, design proposals and real-shop results. Other developers are not automatically assigned parts of this work; ask Patrick to coordinate help if needed.

The roadmap is the detailed checklist and acceptance contract. Keep completion status there instead of creating another competing task file. P2 extensions are a later backlog, not a reason to delay a dependable P0/P1 release.

## First: update safely

Close Cella. In your existing repository run `git status`. Preserve unfinished changes by committing or safely stashing them with your editor's help; do not discard them.

After the documentation containing this assignment is merged, use a clean working tree and run one command at a time:

```sh
git switch main
git pull --ff-only origin main
git switch -c desktop/offline-receipt-details
```

If the branch already exists, inspect it and safely merge current main rather than deleting or resetting it. Never force-push. If a command fails, resolve it before continuing. Use Java 21 for both java and javac and the repository's Maven wrapper. SQLite needs no separate installation.

## Work in this order

1. **OFF-01:** complete saved receipt details, retaining filters, basket and pending checkout. Sales-history listing already exists; do not redo it.
2. **OFF-02:** close recovery/restart/concurrency acceptance gaps for stock, checkout and imports. Preserve immutable request UUIDs and existing directory leases.
3. **OFF-03:** deliver consistent backup/restore including photos, identity and pending-operation policy, with safe restore validation and actual restore evidence.
4. **OFF-07/OFF-07A:** prepare two strong complete-screen design directions early. Patrick approves one; then implement shared sidebar, typography and components before migrating screens.
5. Finish **OFF-04/OFF-05/OFF-06:** product/stock usability, cashier checks, native pilot testing and installable packages.
6. Complete **OFF-08 through OFF-11:** local dashboard/query definitions, local permissions, void/print/report workflows, performance and support. Respect dependencies: confidential metrics need permissions and an honest cost model.
7. After the core offline release is accepted, implement P2 extensions in Patrick's chosen order.

Deliver small reviewable PRs with the roadmap task ID. Do not submit one huge unverified rewrite. Record what works, what remains and what was actually tested; do not mark a task done merely because it compiles.

## Design requirements

Keep the green palette/gradient Patrick likes and follow the expanded redesign section of the roadmap. The assignment is a substantial whole-app improvement: refined larger responsive sidebar, locally available/bundled elegant typography, consistent controls, stronger layout hierarchy, improved product/photo screens, Stock, checkout, receipts, dashboard and Settings. Simplicity for a secretary takes priority over decorative complexity.

Read [STYLE_GUIDE.md](../desktop/STYLE_GUIDE.md) and [VISUAL_POLISH.md](../desktop/VISUAL_POLISH.md). Reuse and improve CatalogStyle, FeedbackStrip and shared navigation instead of scattering fonts/colors or UIManager overrides across screens. Update the guide after Patrick approves the direction. No paid design tool or runtime online dependency is required.

Provide real Java previews at 960×600, 1100×720 and 1600×1000. Include empty/loading/error/pending states and verify keyboard, scaling and native dialogs separately. Headless images are useful evidence, not proof of native usability.

## Preserve the working application

- Keep Willy's, Emmanuel's and all other collaborators' original commits and existing merged behavior. Add corrections; no force-push or history rewrite.
- Use actual shop-scoped services and local records. Never replace data with demo values to make the dashboard look finished.
- Keep exact long/decimal business arithmetic, canonical units, immutable sale snapshots and append-only movements. Do not sum incompatible stock units or invent profit from unknown buying costs.
- Retain recovery requests until confirmed reconciliation; no automatic startup submission or new UUID for an uncertain retry.
- Keep file/database/image work off the Swing event thread. Tests must wait for all relevant background readers before deleting temporary files on Windows.
- Treat service/schema changes as part of the same end-to-end task: include compatible migration, integrity and regression checks. Preserve historical data and backup/restore behavior.
- Implement permissions at application/service boundaries as well as in the interface. The current development shop/device identity is not authentication.

## Verification and handoff

Run the Java 21 wrapper `clean verify` and packaged startup/persistence checks from README. Require green Windows/Linux CI and focused tests for money, stock, recovery, backup/restore and reporting changes. Record native interaction checks separately, with screenshots and any limits. Use disposable data for failure drills; never commit private shop records.

Patrick approves the design and release after evidence against the roadmap's acceptance checks. Your editor may implement and test locally, but push or merge only when you explicitly instruct it to do so.

## Prompt for your AI editor

> I am Charles. Patrick has assigned me all remaining Cella offline work and the whole-app redesign. Read docs/team/CHARLES.md, docs/roadmap/OFFLINE_COMPLETION.md, docs/team/CONTEXT.md, docs/desktop/STYLE_GUIDE.md and README.md. Inspect current main; preserve all existing work and original collaborator commits. Explain each step simply. Start with OFF-01 receipt details, create a small feature branch safely, implement and test it, and report acceptance evidence and remaining work. Prepare redesign proposals before changing the shared visual direction. Do not attempt the whole backlog in one rewrite. Do not push or merge unless I ask you to.

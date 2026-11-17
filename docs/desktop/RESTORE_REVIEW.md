# Review of Charles's restore and Settings delivery

13 November 2026. PRs #53–#57 are reviewed as a stack. Original author commits remain ancestors of
each corrected branch; corrections are additional commits and normal merges. This review does not
merge the PRs, claim the Settings route is connected, or declare all offline work complete.

## Corrections

- **Before-photo backups:** the archive checker queried `product_photos` even for schema 6, so a
  valid older snapshot failed before staged migration could create the table. The checker now
  handles the version boundary, rejects photo payload for a pre-photo schema, and still checks
  declared counts. A regression migrates a schema-6 fixture and proves the live shop is unchanged.
- **Staging file types:** the old fingerprint walk followed file links, accepting a linked photo
  whose target had identical bytes. Staged trees now reject symbolic links and special files.
  Both scheduling and startup require an ordinary archive copy and staging root. Inventory
  fingerprinting also refuses linked/special active items. Regression tests cover substitution of
  a photo before scheduling and an archive after scheduling, before any active files move.
- **Metadata during maintenance:** scheduled-restore and last-backup status reads were not counted
  as folder work. Both now enter the shared gate. Tests hold maintenance, show readers waiting,
  then release maintenance and verify the readers return the correct results. Startup uses an
  internal note reader while it already holds exclusive ownership; it never enters its own paused gate.

The before-fix regression runs reproduced the old-schema rejection, the accepted linked photo/archive,
and the ungated status read. These corrections preserve the existing journal phases, immutable
business operation IDs, pre-restore backup, old-generation quarantine and device identity policy.

## Validation and limits

The original combined suite passed 449 tests before the new cases. Review validation uses Java 21:

```sh
./mvnw clean verify
java -Djava.awt.headless=true -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar --smoke-test
```

The combined suite includes real child-process termination/restart at restore boundaries, rollback,
startup before identity reads, cancellation, maintenance exclusion and Settings service tests. Focused
preparation/archive/backup and installer suites are also run at their corresponding stack levels.
Symbolic-link tests explicitly skip when the host cannot create links; ordinary-path checks remain
active on every platform. CI results and actual desktop/manual acceptance must be checked separately.
Process termination tests do not establish power-loss durability, and checksums are not authentication.

## What remains

SettingsPanel exists and has FR/EN text, but no sidebar route yet (OFF-03c3). Backup reminders,
retention/destination behavior and the complete manual acceptance remain separate work. Read
[CHARLES.md](../team/CHARLES.md) for the updated assignment. Patrick and Codex handle the ordinary
shop/restart trial and shared checklist; Charles keeps technical restore acceptance ownership.

After green checks, merge #53 → #54 → #55 → #56 → #57 using normal merge commits to retain history.

## Settings integration review — PRs #58–#60

The Settings route, remembered destination and seven-day reminder are the next reviewed stack.
The original combined suite passed 470 tests before review; three new cases reproduced these issues:

- If the chosen folder disappeared after loading, the generic backup service recreated its path.
  Settings now uses the existing-folder API; failure leaves the destination absent, preserves the
  prior success record and displays the reconnect/choose-folder guidance. No removable-device
  identity guarantee is inferred from a path still existing.
- After preference storage failed, reopening Settings silently replaced the current folder choice
  with the old persisted choice. The current choice now remains active in that window; restarting
  still uses the stored preference, as the existing error message explains.
- An older reminder read could overwrite the result of a newer check. A generation guard now
  applies only the latest result, including the check after a saved backup.

Regression tests cover all three cases. The existing-folder fixture uses a real directory, matching
what the production folder chooser returns. The original six commits remain ancestors of the
reviewed heads. PR #58 needs no code correction; fixes extend #59 and #60. The current assignment
in CHARLES.md moves to retention and final technical/manual acceptance after this stack merges.

A rendered 960×600 preview also exposed clipping of the reminder's action by its parent container.
The sidebar now gives that area enough vertical space. A layout test checks containment after a
wide-to-small resize; checking only the button's own preferred height had missed the clipping.

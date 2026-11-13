# Making a backup (OFF-03a3)

`cm.sji.innova.desktop.backup.BackupService` makes one checked backup ZIP of a shop, following "Making a
backup" in `docs/design/BACKUP_RESTORE.md`. It builds on the folder coordinator (OFF-03a1,
`DATA_FOLDER.md`) and the decisions about set-aside files (OFF-03a2, `RECOVERY_EVIDENCE.md`). There is no
screen yet (OFF-03c) and no restore yet (OFF-03b). Call it off the Swing event thread.

```java
BackupService.Backup backup = new BackupService(location, shopId, deviceId, coordinator, evidence, clock)
        .create(Path.of("E:\\Cella backups"));
```

## What it does, in order

1. **Refuses a destination inside the shop's data folder** (`DESTINATION_REFUSED`).
2. **Enters exclusive folder maintenance.** It pauses all work, takes the folder alone, and holds the
   stock, checkout and import leases. If another Cella is open, or an operation is running, the
   coordinator refuses (`OTHER_INSTANCE`, `OPERATION_RUNNING`), and nothing is written.
3. **Under that hold, refuses unresolved work:**
   - a saved request (`REQUEST_PENDING`, naming the file);
   - a set-aside file without a decision covering its bytes (`EVIDENCE_UNRESOLVED`, naming it).
4. **Snapshots the database with SQLite `VACUUM INTO`** through `Maintenance.openDatabase()`, then checks
   the copy: `integrity_check`, `foreign_key_check`, the schema version, and only this shop's records
   in every table that has a shop ID.
   What a crashed Cella committed but left in SQLite's log is in the snapshot.
5. **Copies** `shop-id.txt`, the photos the snapshot refers to (a missing one fails with
   `PHOTO_CHANGED`), and every set-aside file with all decision files, under `recovery-evidence/`.
6. **Writes the manifest:**
   - the format, a backup ID, the schema version, the shop ID and the time;
   - **the source computer's ID**, for the record only: restore never installs it;
   - the counts of products, sales, stock movements and photos;
   - the evidence inventory: each file, its SHA-256 and its decision's outcome;
   - each payload file's size and SHA-256. The manifest does not checksum itself.
7. **Writes the ZIP** under a temporary name ending in `.<backup ID>.partial` in the destination, and
   forces it to the disk.
8. **Checks it with `BackupArchive`**, the same checker restore will use, and compares it with the
   snapshot.
9. **Publishes it** with an atomic rename to `cella-backup-<date>-<time>-<start of the backup ID>.zip`,
   for example `cella-backup-2026-11-11-153000-1a2b3c4d.zip`:
   - the ID keeps names apart, even for two shops backing up to the same USB key in the same second;
   - a file that has the name anyway is refused (`DESTINATION_REFUSED`), never replaced;
   - if the destination cannot rename atomically, the backup fails instead of falling back to a copy.
10. **Records the success** in `last-backup.properties` in the data folder: the backup ID, the file and
    its SHA-256. This happens only after publication.
11. **Gives the folder back**, and normal work goes on. The temporary work folder is removed whatever
    happened.

A file named `cella-backup-*.zip` is therefore always complete and checked. A temporary file carries
the backup's ID, so an interrupted one can be recognised and removed later; it is never reported as a
success.

## The last backup (OFF-03c)

`lastSuccess()` reads `last-backup.properties` as a `LastBackup`: the backup ID, the file, its SHA-256
and when it was made, with `age(clock)` for "made 3 days ago" and, later, the reminder. A record that
cannot be read counts as none: a reminder then asks for a backup, which is the safe side. The older
`lastBackup()`, which returns the raw properties, is unchanged.

## `BackupArchive`: the shared checker

`BackupArchive.check(archive, scratch)` trusts nothing in the archive:

- **Entry names:** only the known structure (`manifest.properties`, `cella.db`, `shop-id.txt`,
  `photos/<shop>/<file>`, `recovery-evidence/<file>`). It refuses:
  - absolute paths, drive letters, `..` and `.`, backslashes and `:` (alternate streams);
  - trailing dots or spaces;
  - folders, and names that differ only by case.
- **Limits:** the number of entries, the manifest's size, each entry's size and the total size. Every
  read is bounded, and an entry larger or smaller than its manifest says is refused.
- **Contents:**
  - every payload file must be listed in the manifest, and every listed file present;
  - each SHA-256 must match;
  - the shop ID file must agree with the manifest.
- **The database**, extracted to `scratch`:
  - `integrity_check` and `foreign_key_check`;
  - its version must match the manifest and not be newer than this Cella;
  - only one shop, in every table that has a shop ID (the same check as the snapshot's);
  - counts and photos as listed.

## Tests

**`BackupServiceTest` (13)** uses a running shop with two products, deliveries, a sale and a photo,
joined to its folder.

| Test | Checks |
| --- | --- |
| `aBackupHoldsTheSnapshotTheShopIdThePhotosAndTheDecidedEvidenceEachWithItsChecksum` | The file name and entries, the decided set-aside file and its decision, nothing else (no device ID, locks, logs), the manifest's identity, source computer and evidence inventory, every size and SHA-256, the database's stock, and the success record naming this archive |
| `theLastBackupIsReadWithItsAgeAndAnUnreadableRecordCountsAsNone` | None before a backup; then the backup ID, file, SHA-256, time and age; an unreadable record counts as none |
| `aBackupChangesNothingInTheShopAndNormalWorkGoesOnAfterwards` | Only the success record is added; afterwards, writes, operations and the import screen work again |
| `noBackupWhileARequestIsSavedAndTheRequestIsNamed` | Each of the four saved requests |
| `noBackupWhileASetAsideFileHasNoDecisionThenItWorksOnceDecided` | `EVIDENCE_UNRESOLVED`, then backed up with its decision |
| `noBackupWhileAnotherCellaHasTheShopOpen` | An idle second process: `OTHER_INSTANCE`, nothing written |
| `noBackupWhileAnOperationIsInProgressOrTheImportScreenIsOpen` | `OPERATION_RUNNING` |
| `aMissingPhotoStopsTheBackupAndLeavesNoFile` | `PHOTO_CHANGED` |
| `aDestinationInsideTheShopFolderIsRefused` | The folder itself, a sub-folder, the photos |
| `aBackupThatCannotTakeItsFinalNameLeavesNoNewBackupAndKeepsTheLastSuccessRecord` | The rename fails three ways (a disk that cannot rename safely, a removed USB key, a file that took the name). Each time, no new backup or temporary file is left, the other file is not replaced, the success record still names the earlier backup, and the next backup works |
| `aSaleCommittedJustBeforeACrashIsInTheNextBackup` | A second Cella killed just after committing a sale; the next backup holds it |
| `aBackupKilledWhileCopyingTheDatabaseLeavesNoBackupAndNoSuccessRecord` | Killed during `VACUUM INTO` |
| `aBackupKilledJustBeforeItsFinalNameLeavesNoBackupAndNoSuccessRecord` | Killed while checking its archive: only `*.partial` is left, no success record; the next backup works |

In every refusal, nothing is in the destination, no success is recorded, and the folder is given back.

**`BackupArchiveTest` (7)** starts from a real backup and refuses:
- a changed byte in a photo, or in the shop ID file;
- an unlisted entry, or a missing listed one;
- unsafe names (such as `photos/../b.png`), and names that differ only by case even when the manifest
  lists both;
- a backup from a newer Cella;
- a database that does not match its manifest (counts, shop ID), or that holds another shop's sale;
- a damaged manifest, or a file that is not a backup.

## Not yet

- The Settings screen makes backups (`SETTINGS.md`). Removing old temporary files, reminders and
  retention come next (OFF-03c4);
  `lastSuccess()` gives the reminder what it needs.
- Restore (OFF-03b) reuses `BackupArchive`.
- Power loss is not tested. The archive is forced to the disk before its rename, but a rename alone
  does not prove power-loss durability.

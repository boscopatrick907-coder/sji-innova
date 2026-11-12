# Preparing a restore (OFF-03b1)

`cm.sji.innova.desktop.backup.RestorePreparation` is the first half of restoring a backup, following
"Validate and stage before touching active shop data" in `docs/design/BACKUP_RESTORE.md`. **It never
changes the shop.** Everything it writes is under `restore/` in the data folder, and nothing there is used
until a scheduled restore runs at the next start, which comes in OFF-03b2 (swap and recover) and OFF-03b3
(at start-up). There is no screen yet (OFF-03c). Call it off the Swing event thread: it refuses that thread.

```java
RestorePreparation restore = new RestorePreparation(location, shopId, clock);
RestorePreparation.Summary summary = restore.prepare(Path.of("E:\\cella-backup-2026-11-11-153000-1a2b3c4d.zip"));
// show the summary and "Records made after this snapshot will be replaced", then, if the person confirms:
restore.schedule(summary);
```

## What it writes

```text
restore/
    <restore ID>/
        archive.zip          a private copy of the chosen file
        new/                 the backup unpacked and brought up to date: what a restore will install
            cella.db
            shop-id.txt
            photos/<shop>/...
            recovery-evidence/...
    scheduled.properties     written once, when the person confirms
```

## `prepare(chosen)`

1. **Refuses while a restore is scheduled** (`ALREADY_SCHEDULED`).
2. **Copies the chosen file** to `restore/<restore ID>/archive.zip`, bounded in size, with its SHA-256. This
   is the only time the chosen file is read, so replacing it on the USB key afterwards changes nothing.
3. **Checks and unpacks the copy** with `BackupArchive.extract`: the same checks as a backup's read-back,
   and every listed file is written into `new/` only under its checked name, compared with its size and
   SHA-256 as it is written. A photo must belong to the backup's own shop.
4. **Brings the unpacked database up to date** when the backup comes from an older Cella, exactly as Cella
   does at start-up, on that copy only (`UPGRADE_FAILED` if it cannot).
5. **Checks the result again** (`STAGED_CHECK_FAILED`): up to date, `integrity_check` and
   `foreign_key_check`, only this backup's shop in every table that has a shop ID, the same numbers of
   products, sales and stock movements as the backup, every photo present, and one self-contained database
   file without a log beside it.
6. **Says what it holds:** the shop (and whether it is the shop open now), the source computer, the
   backup's date and version, the counts, the set-aside files, and the SHA-256 of the copy and of every
   prepared file.

On any failure its `restore/<restore ID>/` folder is removed again. A file that is not a usable backup is
refused by `BackupArchive` with `BackupException` `CHECK_FAILED`.

## `schedule(summary)`

After the person confirms the summary:

1. **Checks that nothing changed:** the copy's SHA-256 and every prepared file must be exactly as
   summarised, with no file added or removed (`STAGING_CHANGED`).
2. **Forces the prepared files to the disk.**
3. **Writes `restore/scheduled.properties` once:** the folder, the restore ID, the shop, the counts, the
   SHA-256 of the copy and of every prepared file, and a SHA-256 of the note itself. The note is written
   in full first, then given its name by a hard link, which is refused if a note already exists, so a
   scheduled restore is never replaced (`ALREADY_SCHEDULED`). A data folder that cannot do this is refused
   (`FOLDER_UNSUPPORTED`).

`scheduled()` reads the note back. A note whose checksum does not match, or that names another data
folder, is reported as `NOTE_DAMAGED`, never ignored. `discard(restoreId)` removes a prepared restore
that was not scheduled; a scheduled one is never removed there.

## `cancelScheduled()` (OFF-03c)

The person changed their mind before restarting Cella: the scheduled restore will not run.
1. **Refused once the restore has started** (a journal exists): from then on only recovery at start-up
   decides (`ALREADY_SCHEDULED`).
2. **The note goes first**, so from that moment nothing is scheduled.
3. **Then the prepared copy is removed**, unless its folder also holds a pre-restore backup or an old shop
   from an attempt that stopped: then the folder is kept.

A note that cannot be read (`NOTE_DAMAGED`) can be cancelled too: that is the way out of it; its prepared
copy stays, like any prepared restore. It returns whether a restore was scheduled.

## Folder ownership

`prepare`, `schedule`, `discard` and `cancelScheduled` pass through the folder's gate (OFF-03a1), so they
wait while a backup or restore holds the folder, and a backup waits for them. They refuse the Swing event
thread.

## Tests

**`RestorePreparationTest` (13)** uses a running shop with products, deliveries, a sale and a photo, a
backup of it on a "USB key", then one more product made after the backup. Every test checks that the shop
is not changed: each file outside `restore/` keeps its SHA-256.

| Test | Checks |
| --- | --- |
| `aBackupIsCopiedCheckedUnpackedAndSummarisedWithoutChangingTheShop` | The summary, the copy, the unpacked files and their fingerprints, one database file without a log, the backup's products only |
| `theChosenFileIsReadOnceSoReplacingItAfterwardsChangesNothing` | Another shop's backup is said to be one; swapping the file on the key after preparing changes neither the files nor the note |
| `aFileThatIsNotAGoodBackupIsRefusedAndLeavesNothingBehind` | Not a ZIP, a changed byte, an unsafe name, a newer Cella: `CHECK_FAILED`, nothing left in `restore/` |
| `aBackupFromAnOlderCellaIsBroughtUpToDateInTheCopyOnly` | A schema 8 backup: the copy reaches the current schema and its sale line takes its product's reference |
| `anUpgradeThatFailsLeavesNothingBehindAndChangesNothing` | `UPGRADE_FAILED`, nothing left |
| `theFinalCheckRefusesWhatAFaultyUpgradeCouldLeave` | A missing photo, an old schema, another record count, another shop's product family |
| `schedulingFirstChecksThatThePreparedFilesAreExactlyTheOnesSummarised` | A changed photo, a changed copy, an added file: `STAGING_CHANGED`, no note |
| `onlyOneRestoreIsScheduledAndAScheduledOneIsNeverDiscarded` | A second schedule and a new prepare are refused, the note is unchanged, the scheduled restore is kept and the other removed |
| `aDamagedNoteOrOneFromAnotherFolderIsReportedNeverIgnored` | A changed value, a note cut short, a note copied to another folder: `NOTE_DAMAGED` |
| `aScheduledRestoreCanBeCancelledBeforeTheNextStart` | The note and the prepared copy go, the shop is unchanged, nothing is left to cancel, and another restore can be scheduled |
| `aDamagedNoteCanStillBeCancelled` | A note that cannot be read is removed; its prepared copy stays |
| `aRestoreThatStartedCannotBeCancelledAndAPreRestoreBackupIsNeverRemoved` | With a journal, cancelling is refused and the restore stays scheduled; a pre-restore backup in the folder is kept |
| `preparingSchedulingAndCancellingWaitWhileTheFolderIsHeldAndAreRefusedOnTheSwingThread` | Each waits during maintenance and writes nothing meanwhile; prepare, schedule, discard and cancel refuse the Swing thread |

**`BackupArchiveTest`** gains `extractUnpacksEveryListedFileUnderItsNameAndNothingElse`, and refuses a
listed photo filed under another shop.

## Not yet

- Installing a scheduled restore, the journal and recovery after a crash: OFF-03b2, in `RESTORE_RUN.md`.
- Running it at start-up, before the shop opens: OFF-03b3a, in `DesktopApplication.openShop` (see `RESTORE_RUN.md`).
- The restore screen: OFF-03c2, in `SETTINGS.md`. Removing old `restore/` folders: OFF-03c4.

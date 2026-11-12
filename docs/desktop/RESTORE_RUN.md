# Running a restore, and recovering from a crash (OFF-03b2)

`cm.sji.innova.desktop.backup.RestoreInstaller` is the second half of restoring a backup, following
"Exclusive restore with a durable recovery journal" in `docs/design/BACKUP_RESTORE.md`. It installs a restore
that `RestorePreparation` scheduled (OFF-03b1, `RESTORE.md`), and finishes or undoes one that a crash
interrupted. Cella calls it at start-up from `DesktopApplication.openShop` (OFF-03b3a, below). There is no screen
(OFF-03c).

```java
RestoreInstaller.Result result = new RestoreInstaller(location, coordinator, clock).atStart();
// NOTHING_SCHEDULED, RESTORED, or ROLLED_BACK with the reason in result.detail()
```

**Call it before anything reads the shop's identity or opens its services**, as a member of the folder,
off the Swing event thread. After an interrupted restore, `shop-id.txt` may be set aside: a start-up that
created a new identity first would leave a second one in the way, and the restore then stops for repair
(tested).

## What `atStart()` does

- **No journal and no scheduled note:** nothing, and it takes nothing. Another open Cella is no problem.
- **Otherwise it takes the folder alone** (folder maintenance, OFF-03a1). Another open Cella stops it
  before anything moves (`OTHER_INSTANCE`).
- **A journal:** a restore was interrupted. See "Recovery" below.
- **A scheduled note:** it runs the restore.

## Running a scheduled restore

Before anything moves, it checks, and stops with nothing changed (the restore stays scheduled):
1. the copy of the backup and every prepared file are exactly as confirmed (`STAGING_CHANGED`);
2. the prepared database is at this Cella's version (`STAGED_CHECK_FAILED`, "prepared by another version");
3. **a pre-restore backup** of the shop as it is now, in `restore/<restore ID>/pre-restore/`, made and read
   back like any backup. This also refuses a saved request (`REQUEST_PENDING`), a set-aside file without a
   decision (`EVIDENCE_UNRESOLVED`) and a missing photo (`PHOTO_CHANGED`). It is not recorded as the shop's
   last backup: a copy on the same disk does not protect against losing that disk.

Then it writes **the journal**, `restore-journal.properties` in the data folder. It lists every item the
restore moves, with the SHA-256 of its old version here and its new prepared version:

| Item | Old version | New version |
| --- | --- | --- |
| `cella.db` | moved aside **with** any `cella.db-wal`, `cella.db-shm`, `cella.db-journal` | the prepared database, one file, no log |
| `shop-id.txt` | moved aside | the backup's shop |
| `photos/` | moved aside | the backup's photos |
| `recovery-evidence/` | moved aside | the backup's set-aside files and decisions, **inactive**: they are not in the data folder's root, so nothing treats them as waiting work |
| the old shop's set-aside files | moved aside | none |
| `last-backup.properties` | moved aside with the old shop | none |

`device-id.txt`, `logs/`, lock files and `restore/` never move. A folder's fingerprint is the SHA-256 of
every file in it with its path. Only these names are ever accepted from a journal.

The phases, each published by replacing the whole journal in one atomic step after forcing it to the disk:

1. `PREPARED`: the list is written.
2. Each old item is **renamed** into `restore/<restore ID>/old/`, one by one. → `OLD_QUARANTINED`
3. Each new item is renamed from `restore/<restore ID>/new/` into the data folder. → `NEW_INSTALLED`
4. **The installed shop is checked:** every item exactly as prepared, nothing old left beside it, the shop
   ID file naming the restored shop, and the database sound (`integrity_check`, `foreign_key_check`), at
   this version, one shop, with the confirmed numbers of products, sales and stock movements. → `VERIFIED`
5. → `COMMITTED`
6. The note and the journal move into `restore/<restore ID>/`: the restore is over.

A rename never replaces anything: if the target exists, the restore stops for repair. Nothing old is
deleted: the old shop and the pre-restore backup stay in `restore/<restore ID>/`.

If any step from 2 to 4 fails, **the whole old shop is put back** (see below) and `atStart()` returns
`ROLLED_BACK` with the reason. The prepared files go back to `new/`.

## Recovery

At the next start, a journal is found first:

- **Before `COMMITTED`:** the whole old shop is put back, from where each item actually is, since a crash
  can come between two renames: a new version in the data folder goes back to `new/`, then an old version
  set aside comes back. Every old item must then match its fingerprint exactly, `ROLLED_BACK` is
  published, and the note and journal move into the restore's folder. Doing it again after another crash
  gives the same result.
- **After `COMMITTED`:** the installed shop is checked again, as in step 4, and the restore finished.
- **Neither can be proved** (an old item does not match, a committed shop was changed, something
  unexpected is in the way): it stops with `NEEDS_REPAIR`, moves nothing more and keeps every file and the
  journal, so Cella keeps the shop closed until someone repairs it.

A journal that cannot be read, or that names an item a restore never moves, is refused
(`JOURNAL_DAMAGED`) and kept.

While a journal exists, no new restore can be prepared, and `RestorePreparation.discard` never removes a
restore that ran or is running.

## At start-up (OFF-03b3a)

`DesktopApplication.openShop`, which the start-up page runs in the background, now does, in this order:
1. joins the data folder (OFF-03a1);
2. `RestoreInstaller.atStart()`, **before anything reads `shop-id.txt` or opens the database**;
3. loads the shop.

- **Nothing scheduled:** step 2 takes nothing; start-up is as before.
- **Restored, or put back:** the shop opens. The outcome is written to the log (`logs/cella-0.log`): an
  information line for a restore, a warning with the reason for one that was undone.
- **Refused** (another Cella is open, the prepared files changed, saved work, a damaged journal, a restore
  that needs repair): the shop does not open. The start-up page's existing error view shows the message and
  offers Retry; the restore stays as it was.

The start page itself is unchanged: telling the person "Restoring…" or "the restore was undone" needs a
change to `ShellPanel`, which waits for Patrick's agreement (OFF-03b3b). Until the restore screen exists
(OFF-03c), nobody can schedule a restore, so nothing changes for anyone yet.

## Tests

**`RestoreInstallerTest` (16)** copies a shop that was backed up and then changed (Soap added), and
schedules the backup in each copy. "The whole old shop" and "the whole new shop" are checked by the
fingerprint of every item and of `device-id.txt`, then by the products in the database.

| Test | Checks |
| --- | --- |
| `aScheduledRestoreReplacesTheShopAndKeepsTheOldOneAside` | The whole new shop; the old items, whole, in `old/`; a pre-restore backup with Soap; journal and note kept with the restore; the restored shop can be backed up |
| `aBackupOfAnotherShopBringsThatShopButThisComputerKeepsItsDeviceId` | The backup's shop ID, this computer's device ID |
| `withNothingScheduledNothingHappensButAnotherOpenCellaStopsARestoreBeforeAnythingMoves` | An idle second Cella process: no effect when nothing is scheduled; `OTHER_INSTANCE` otherwise, nothing moved |
| `aSavedRequestAnUndecidedSetAsideFileOrAFailedPreRestoreBackupStopsItBeforeAnythingMoves` | `REQUEST_PENDING`, `EVIDENCE_UNRESOLVED`, `PHOTO_CHANGED`, each with nothing moved; then it runs |
| `changedPreparedFilesOrADamagedNoteOrJournalStopItBeforeAnythingMoves` | A changed prepared file or copy, a database prepared by another version, a damaged note, a damaged journal, a journal naming `../outside` |
| `aFailedCheckOfTheInstalledShopPutsTheWholeOldShopBack` | A spoiled installed photo, and a database without the confirmed records: `ROLLED_BACK`, the whole old shop |
| `whatCannotBeProvedWholeStopsForRepairAndKeepsEveryFile` | An old item spoiled while aside, a committed shop spoiled before finishing, an identity invented while the real one was aside: `NEEDS_REPAIR`, journal kept |
| `aCellaKilledAfterAnyStepComesBackAsTheWholeOldShopOrTheWholeNewOne` | A real Cella process killed after **each** durable step in turn (pre-restore backup, every rename, every journal phase, the note, the journal); at restart: before `PREPARED` the restore runs, before `COMMITTED` the whole old shop, after it the whole new shop. No new restore can be prepared meanwhile |
| `aCellaKilledWhilePuttingTheOldShopBackIsRecoveredAgain` | Killed in the middle of the renames, then killed again while putting the old shop back: the whole old shop |
| `aSaleCommittedByACrashedCellaIsKeptWithTheOldShop` | A sale committed by a Cella killed just afterwards is in the old shop kept aside and in the pre-restore backup, not in the restored shop |
| `aLogLeftBesideTheOldDatabaseGoesAsideWithItAndComesBackWithIt` | A `cella.db-wal` beside the old database moves aside with it, comes back with it, and is never beside the restored one |
| `setAsideFilesOfTheBackupStayInactiveAndTheOldShopsGoAside` | Nothing waits for a decision after the restore; the old shop's set-aside file is kept with it |
| `aRestoreThatRanIsNeverDiscarded` | `discard` refuses; the old shop and the pre-restore backup are kept |
| `aCellaStartingWithAScheduledRestoreOpensTheRestoredShop` | A real Cella started through `openShop` opens the restored shop, with this computer's device ID and the old shop kept aside |
| `aCellaStartingAfterACrashInTheMiddleOfARestorePutsTheOldShopBackBeforeReadingItsIdentity` | With `shop-id.txt` and `cella.db` set aside by an interrupted restore, a starting Cella opens the whole old shop with its own identity: none is invented |
| `anotherOpenCellaStopsARestoreAtStartUpUntilItCloses` | A starting Cella is refused while another is open, with nothing moved; once it closes, starting again restores |

`RestorePreparationTest` and `BackupServiceTest` run unchanged. The crash-test helper `ForcedClose` gains
`reached(step)`, so a restore step can be a kill point, and no longer creates `cella.db` when checking a
folder where a restore had moved it aside.

## Not yet

- **The start page does not say what the restore did** (OFF-03b3b, after Patrick's agreement on
  `ShellPanel`): "Restoring…", "the restore was undone because…", and a clearer page for `NEEDS_REPAIR`.
  Today a refusal shows on the existing error page with Retry, and outcomes are only in the log.
- Removing old restores: OFF-03c4. The screen is in `SETTINGS.md`; cancelling is in `RESTORE.md`.
- Power loss is not tested. Files and the journal are forced to the disk, but a rename's folder entry is
  not (Java cannot do that on Windows), and a power cut is not simulated.

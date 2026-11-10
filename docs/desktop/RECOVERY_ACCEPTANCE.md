# OFF-02: recovery and integrity evidence

This page shows how each OFF-02 check in the offline roadmap is proved for the four operations that
keep a request on disk until they are sure:

- stock receipts;
- stock corrections;
- cash sales;
- CSV imports.

Every test runs on real SQLite and real pending files in a temporary folder. Failures are injected
around them. No shop's real data is used. CI runs all of them on Windows and Linux.

Run only these acceptance tests and the forced-close drills with:

```sh
./mvnw --batch-mode --no-transfer-progress -pl core,desktop test -Dtest="*RecoveryAcceptanceTest,*ForcedCloseDrillTest" -Dsurefire.failIfNoSpecifiedTests=false
```

## The rule all four follow

1. **Save the request first.** It holds its UUID, shop, device, product, unit, quantity and reason
   (or the sale's lines, or the import's reviewed rows).
2. **Send it second.** The database recognises the same UUID: a stock movement's ID is the
   operation's UUID, a sale's ID is the sale's UUID, and an import's operation is recorded once.
3. **Remove the request only once the answer is certain.**
   - A known refusal (not enough stock, a whole-bag rule) removes it and gives the input back.
   - Any other failure keeps it and locks the input, so the same UUID is sent again.
4. **Opening a screen only reads.** It shows a saved request, and nothing is sent until the user
   presses retry.
5. **One process at a time per data folder.** Each operation takes a lease on a lock file next to its
   request:
   - stock receipts and corrections share `.pending-stock-receipt.lock`;
   - cash sales use `pending-checkout.txt.lock`;
   - imports use `.csv-import.lock`.

## Stock receipts and corrections

New tests are in `StockRecoveryAcceptanceTest`. Balances are in thousandths, so 7 000 is 7 pieces.

| Scenario | Test | Balance | Movements | Request file |
| --- | --- | --- | --- | --- |
| Receipt saved by the database, answer lost, restart, retry | `aReceiptSavedByTheDatabaseWhoseAnswerWasLost…` | 0 → 7 000, still 7 000 after restart and retry | 1, whose ID is the original UUID | Kept until the retry, then removed |
| Correction saved, answer lost, restart, retry | `aCorrectionSavedByTheDatabaseWhoseAnswerWasLost…` | 10 000 → 13 000, unchanged by restart and retry | 2 (delivery + the correction with the original UUID) | Kept until the retry, then removed |
| Correction fails before the database saves | `aCorrectionThatFailsBeforeTheDatabaseSaves…` | 10 000 → 10 000, then 13 000 on retry | 1 → 2, original UUID | Kept, then removed |
| Receipt fails before the database saves | `StockPanelTest.failedReceiptCanBeRetriedWithoutDuplicatingStock` | 0 → 0, then 10 000 on retry | 0 → 1 | Kept, then removed |
| Request file cannot be written (receipt, correction) | `a…WhosePendingFileCannotBeWrittenIsNeverSent` | Unchanged; the database is never called | Unchanged | None; the retry saves it, then sends once |
| Confirmed, but the file cannot be removed (receipt, correction) | `aConfirmed…WhosePendingFileCannotBeRemoved…` | 7 000 (receipt), 13 000 (correction), unchanged by the retry | 1 / 2 | Kept with the same UUID; the retry replays and removes it |
| Opening with a saved request | the restart steps above; `StockRecoveryRegressionTest.recoveredReceiptShouldExplainThatItIsPending` | Unchanged | Unchanged | Kept; nothing is sent |
| Request from another shop or device | `savedRequestsFromAnotherShopOrDevice…` | Unchanged | Unchanged | Kept byte for byte; receiving, correcting and minimums are blocked |
| Request for a changed unit or an unknown product | `savedRequestsForAChangedProductOrUnit…` | Unchanged | Unchanged | Kept byte for byte; blocked |
| Damaged request file | `damagedPendingFilesBlockTheScreen…` | Unchanged | Unchanged | Kept byte for byte; blocked, also after Refresh |
| Temporary read failure, then Refresh | `StockRecoveryRegressionTest.refreshAfterTemporaryReadFailure…` | — | — | The screen unblocks |
| Two windows: a receipt and a correction at once | `StockRecoveryRegressionTest.receiptAndAdjustmentMustNotBothBecomePending…` | — | — | Only the first is kept |
| A second Java process holds the stock folder | `whileAnotherProcessHoldsTheStockFolder…` | Unchanged while it holds; 7 000 after it lets go | 0 → 1 | Nothing saved while held; the same request then works |
| A second Java process tries the folder while a receipt is on its way | `aReceiptKeepsTheStockFolderFromSavingItsRequestUntilTheAnswerComes` | 0 → 7 000 | 1 | Saved before sending; the other process is refused until the answer comes |
| A known refusal | `StockPanelTest.deterministicServiceOverflowLeavesInputEditable…` | Unchanged | Unchanged | Removed; the input is editable again |

## Cash sales

The new test is in `CheckoutRecoveryAcceptanceTest`; the others were already there. The shop starts
with 10 pieces of rice and the sale is 2.

| Scenario | Test | Stock | Sales | Request file |
| --- | --- | --- | --- | --- |
| Saved by the database, answer lost, retry | `CheckoutTest.aFailureAfterSavingCanBeRetriedWithoutASecondSale` | 10 000 → 8 000, unchanged by the retry | 1 | Kept, then removed |
| Same, found after a restart | `CheckoutTest.afterARestartASaleThatWasReallySavedIsRecognised` | 8 000 | 1 | Removed when the screen checks the database |
| Fails before the database saves | `CheckoutTest.aFailureBeforeSavingCanBeRetriedWithoutLosingAnything` | 10 000, then 8 000 on retry | 0 → 1 | Kept, then removed |
| Request file cannot be written | `CheckoutTest.ifTheDetailsCannotBeSavedNothingIsSent` | Unchanged | 0 | None; nothing is sent |
| Confirmed, but the file cannot be removed | `aConfirmedSaleWhosePendingFileCannotBeRemoved…` | 8 000, unchanged afterwards | 1; the database is called once | Kept unchanged; the next opening finds the sale saved and removes the file |
| Opening with a saved sale | `CheckoutTest.aRestartNeverSendsAnythingByItself` | Unchanged | Unchanged | Kept; nothing is sent |
| Damaged file, or one from another shop | `CheckoutTest.aDamagedFileIsReportedAndKept`, `aFileFromAnotherShopCountsAsDamaged` | Unchanged | Unchanged | Kept; **Set the file aside** renames it, never deletes it |
| A damaged file fixed or moved by hand | `SalesPanelTest.aDamagedFileThatWasMovedByHandIsNoticedByRefresh` | — | — | Refresh notices it |
| Storage fails, then retry | `SalesPanelTest.aStorageFailureSavesNothingAndRetryFinishesTheSale` | 10 000, then 8 000 | 0 → 1 | Kept, then removed |
| Two windows, two processes | `CheckoutOwnershipTest` (6 tests, including `aSecondJvmCannotSubmitOrRecover…`) | — | Never two | Never overwritten or cleared by another window |
| A known refusal, or one the database contradicts | `CheckoutTest.notEnoughStockSavesNothingAtAll`, `aRefusalIsNotTrustedWhenTheSaleIsReallySaved` | Unchanged / 8 000 | 0 / 1 | Removed only once certain |

## CSV imports

New tests are in `CsvImportRecoveryAcceptanceTest`; the others were already there. The file has one
valid row, « Huile ».

| Scenario | Test | Products | Request file |
| --- | --- | --- | --- |
| Saved by the database, answer lost, restart, retry | `ImportAndPhotoControlsTest.lostCommitResponseCanResume…` | 0 → 1, still 1 after the retry | Kept, then removed |
| A failure in the middle of an import | core `ProductImportStorageTest.midImportFailureLeavesCommittedRowsIntactAndRetryReplaysSafely` | Saved rows stay; the retry adds only the rest | — |
| Request file cannot be written | `anImportWhosePendingFileCannotBeWrittenIsNeverSent` | 0; the import is never attempted | None; the retry saves it, then imports once |
| Confirmed, but the file cannot be removed; restart | `aConfirmedImportWhosePendingFileCannotBeRemoved…` | 1; opening sends nothing; the retry is answered from the saved import (no new insert) | Kept, then removed |
| Damaged file, or one from another shop | `ImportAndPhotoControlsTest.pendingStoreRejectsConflictingAccessAndDamagedOrWrongShopState` | Unchanged | Kept |
| Another process has an import open | `whileAnotherProcessHasAnImportOpen…` | 0 while it is open; 1 after | Nothing written; the import works once the other closes |
| Changed rows, shop, selection or duplicate consent | core `ProductImportConfirmationTest` | Unchanged | Refused before anything is recorded |

## How the failures are made

- **Lost answer:** the repository saves for real, then throws.
- **Failure before saving:** it throws first.
- **A request file that cannot be written:** the store throws on save, or the file's name is taken by
  a folder.
- **A file that cannot be removed:** stock stores throw on removal. For sales and imports, whose stores
  are final classes, `UndeletableFile` protects the real file right after the database saves:
  - on Windows it holds the file open without delete sharing;
  - on Linux and macOS it makes its folder read-only, so these tests keep the request in a folder
    apart from the database;
  - it proves the protection on a sibling file first, and skips the test where it cannot work (for
    example as root).
- **Another process:** the test starts a second Java process that takes the same lease and waits.

## Forced close and restart (OFF-02c)

New tests are in `StockForcedCloseDrillTest`, `CheckoutForcedCloseDrillTest` and
`CsvImportForcedCloseDrillTest`. Each drill:

1. Prepares a disposable data folder (shop ID, device ID and database), as Cella's start-up does.
2. Starts a second Java process. It opens the folder the same way and does one operation on the real
   screen, wired as the shell wires it.
3. Kills that process at a chosen moment of the database write. On Windows the process is terminated;
   on Linux it gets `SIGKILL`. Cella runs no more code, so nothing is rolled back, removed or closed
   by it.
4. Waits until the system has released the dead process's files, as a person restarting Cella would.
5. Opens the same folder again in the test, as restarting Cella does. It checks what the screen
   offers, then retries.

The two moments:

- **Just before the commit:** the database has written everything but has not committed it. That is
  the movement and the new balance; or the sale, its lines, its stock change and its event; or, for
  an import, row 1 committed and row 2 written.
- **Just after the commit:** the database has committed, but Cella has not yet heard the answer or
  removed its request file.

Every drill also checks that the request file was on disk before the database was called.

| Operation | Killed | After the kill | After the restart | After the retry | Request file |
| --- | --- | --- | --- | --- | --- |
| Receipt of 7 | Before the commit | 0, no movement | 0; nothing sent; the receipt is offered and cannot be changed | 7 000; 1 movement, with the original UUID | Kept byte for byte until the retry, then removed |
| Receipt of 7 | After the commit | 7 000; 1 movement, with the UUID | 7 000; nothing sent | 7 000; still 1 movement; « Receipt confirmed (retried operation). » | Kept until the retry, then removed |
| Correction of +3 from 10 000 | Before the commit | 10 000; 1 movement | 10 000; nothing sent; the correction is offered and cannot be changed | 13 000; 2 movements, the new one with the original UUID | Kept byte for byte until the retry, then removed |
| Correction of +3 from 10 000 | After the commit | 13 000; 2 movements | 13 000; nothing sent | 13 000; still 2 movements | Kept until the retry, then removed |
| Cash sale of 2 from 10 000 | Before the commit | 10 000; no sale | 10 000; no sale; the sale is shown with its receipt number, and Retry is offered | 8 000; 1 sale, with the original ID and receipt number | Kept byte for byte until the retry, then removed |
| Cash sale of 2 from 10 000 | After the commit | 8 000; 1 sale, with the ID | 8 000; still 1 sale; the screen checked the database and reports the sale saved | Nothing to retry | Removed by that check |
| Import of 3 rows | Between rows 1 and 2 | 1 product (« Huile ») | 1 product; nothing sent; the 3 saved rows are offered, selection locked | 3 products, each once | Kept byte for byte until the retry, then removed |
| Import of 3 rows | After the last row's commit | 3 products | 3 products; nothing sent | 3 products; answered from the saved import | Kept until the retry, then removed |

**How the moment is chosen.** The second process puts a thin wrapper around the SQLite driver (test
code only; Cella is unchanged). Each statement is reported after it runs. At the chosen statement the
process prints `PAUSED` and waits to be killed.

**What a kill leaves behind.**
- The lock files (`.pending-stock-receipt.lock`, `pending-checkout.txt.lock`, `.csv-import.lock`) stay
  on disk. They never block the restart, because a lease is a lock held by the system, not the
  file's presence.
- SQLite's `cella.db-wal` and `cella.db-shm` stay too; every drill checks this, because a clean exit
  would have removed them. The first connection after the kill folds them into `cella.db`: it keeps
  what was committed and drops what was not.

**What Windows does after a kill** (found while building the drills):
- It releases the dead process's file locks a moment after the process ends; Microsoft documents this.
  Measured here: 8 to 75 ms. Restarting at once would have shown « Another Cella window is using the
  pending stock receipt file ».
- For a few milliseconds more, SQLite can report « disk I/O error » while it resets `cella.db-shm`.
  Measured here: up to 35 ms.
- Nobody restarts Cella that fast, and both errors are temporary: trying again a moment later works,
  and the start-up error page offers Retry. The drills wait the same way, then restart.

## Found while testing (for OFF-02b and Patrick)

Nothing was lost or counted twice in any scenario. These are about what the user is told:

1. **Stock:** a damaged request file, or one from another shop or device, or for a changed product,
   blocks receiving, correcting and minimums. The file is kept, but the screen gives no next step:
   - a damaged file shows « Unable to load products or stock » with a technical reason;
   - the receipt store can already set a damaged file aside, but no screen offers it;
   - the correction store cannot.
2. **Imports:** a damaged request file, or one from another shop, shows « Import unavailable or pending
   file damaged. Preserve the file and contact support. » There is no other way forward in the app.
3. **Corrections:** when the database confirmed a correction but its file could not be removed, the
   screen says « Adjustment failed or its outcome is uncertain ». Receipts say « confirmed, but
   recovery cleanup is pending ». Both are safe, because the retry counts once.
4. **Cash sales:** a saved sale is checked against the shop, but not against the device; stock requests
   check both. A data folder normally keeps one device ID, so this only matters if `device-id.txt` is
   replaced. Should the sale check it too?

## Review correction: bounded child-process cleanup

All new subprocess helpers use `TestChildProcess`. On normal completion, output timeout or an
assertion failure, it terminates and waits for the child before closing its pipes and waiting for the
output reader. Reader threads are daemon threads and cleanup waits are bounded. This avoids waiting
in `ExecutorService.close()` for a silent child's `readLine()` before reaching the code that kills it.

`TestChildProcessTest` starts a real child holding a file lease. It verifies that a missing output
marker raises the original timeout, an assertion remains the original failure, the child is gone,
the output reader finishes, and the lease becomes available again. Windows lock release is allowed a
bounded settling interval, just as in the forced-close drills.

## Not covered here

- **A power cut.** It can also lose what the system had not yet written to the disk. The database uses
  `synchronous = FULL` for this, but a test cannot cut the power.
- **A kill while a request file is being written.** Every store writes a temporary file, then moves it
  into place in one step, so a kill leaves either no request or the whole one, plus perhaps a leftover
  `.tmp` file. There is no way to stop the process inside that write without changing Cella.

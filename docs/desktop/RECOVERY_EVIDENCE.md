# Set-aside files and their decisions (OFF-03a2)

Since OFF-02b, a saved request a screen cannot use can be **set aside**: renamed in the data folder,
never deleted. Setting a file aside unblocks the screen, but it does not settle what happened to the
operation. `docs/design/BACKUP_RESTORE.md` ("Saved requests and preserved evidence") therefore asks for
an explicit decision about each set-aside file. Backup and restore stay blocked until every one has a
decision. This is the service for that; the screen where staff record decisions comes later.

## The set-aside files

| Kind | File names |
| --- | --- |
| Stock receipt | `pending-stock-receipt-damaged-<id>.properties`, `pending-stock-receipt-set-aside-<id>.properties` |
| Stock correction | `pending-stock-adjustment-damaged-<id>.properties`, `pending-stock-adjustment-set-aside-<id>.properties` |
| Cash sale | `pending-checkout-damaged-<id>.txt` |
| CSV import | `pending-csv-set-aside-<id>.properties` |

## `EvidenceService`

- **`inventory()`** lists each set-aside file with:
  - its kind, size and **SHA-256**;
  - the date of the request in it;
  - what can be read from it: operation, shop, computer, product, quantity, note. A damaged file gives
    nothing; nothing is guessed;
  - a **hint**: `FOUND` (its operation is in this shop's records), `NOT_FOUND`, `OTHER_SHOP` (this
    shop's records cannot tell), or `UNKNOWN` (unreadable, or the records cannot be checked, for
    example because the product no longer exists). A hint is help for the person deciding; it is never
    applied by itself;
  - its **status**:
    - `UNDECIDED`;
    - `DECIDED`: a decision covers these exact bytes;
    - `CHANGED_SINCE_DECISION`: the file changed after its decision, so it needs a new one;
    - `DECISION_UNREADABLE`: a decision file exists but cannot be read, so someone must look at it.
- **`unresolved()`**: every file whose status is not `DECIDED`. Backup (OFF-03a3) and restore (OFF-03b)
  refuse while it is not empty.
- **`decide(file, sha256 seen, who, outcome, operation, reason)`** records one decision:
  - **outcomes:** `ALREADY_RECORDED` (the matching operation's ID is required), `ENTERED_AGAIN`,
    `DID_NOT_HAPPEN`, `OTHER`;
  - **who** and **reason** are required: a typed name, since Cella has no user accounts yet (OFF-09);
  - **refused** if the name is not a set-aside file (`NOT_EVIDENCE`), if the file changed since the
    person looked at it (`EVIDENCE_CHANGED`), or if a decision already covers these bytes
    (`ALREADY_DECIDED`). Nothing is written then.

## How decisions are kept

- One file per set-aside file and fingerprint:
  `recovery-evidence/<set-aside file>.<first 16 hex of its SHA-256>.decision.properties`. It holds the
  format, the file name, the full SHA-256, who, when (UTC), the outcome, the operation ID if any, and
  the reason. Decisions are never replaced; a changed file gets its own new decision, and the older
  one stays.
- Written safely: a temporary file is forced to the disk, then renamed into place.
- One decision is written at a time per folder, across threads and Cella processes (a lock file in
  `recovery-evidence/`). Inside one process only one thread opens that lock file at a time, because on
  Linux closing a second handle would drop the first one's lock.
- Every decision passes the data folder's gate (OFF-03a1), so it waits during a backup or restore.
- Set-aside files are only ever read: never moved, changed or deleted.

## Tests: `EvidenceServiceTest`

A real shop with one set-aside file of each kind, made the way the screens make them. The six files
are: a damaged receipt; a receipt from another computer that the database already has; a correction
for a product that no longer exists; a correction from another shop; a damaged sale; and a damaged
import whose operation can still be read.

| Test | Checks |
| --- | --- |
| `everyKindIsFoundWithItsFingerprintWhatItSaysAndAHint` | All six, with size, SHA-256, details and hints (`FOUND`, `NOT_FOUND`, `OTHER_SHOP`, `UNKNOWN`); all block |
| `aDecisionCoversThoseExactBytesIsWrittenOnceAndUnblocksOnlyThatFile` | Read back exactly; only that file unblocks; a second decision is refused and the first stays |
| `aFileThatChangesAfterItsDecisionNeedsANewOne` | `CHANGED_SINCE_DECISION`, blocking; a new decision covers the new bytes |
| `aDecisionAboutBytesThePersonDidNotSeeIsRefusedAndNothingIsWritten` | `EVIDENCE_CHANGED` |
| `aDecisionNeedsAnEvidenceFileANameAReasonAndForAlreadyRecordedTheOperation` | Missing name, reason or operation; `cella.db`, `shop-id.txt`, an active request or `../` are not evidence |
| `setAsideFilesAreOnlyEverRead` | Bytes and dates unchanged after listing and deciding all six |
| `anUnreadableDecisionBlocksAndIsNeverReplaced` | `DECISION_UNREADABLE`, and the file is kept |
| `recordingADecisionWaitsDuringMaintenanceThenGoesOn` | Waits for a backup or restore, writes nothing meanwhile, then records |
| `twoPeopleDecidingTheSameFileAtOnceGetOneDecision` | One decision, one `ALREADY_DECIDED` |
| `anotherCellaRecordingADecisionMakesThisOneWaitItsTurn` | A second Cella process holding the decision lock makes this one wait |

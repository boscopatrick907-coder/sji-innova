# Who is using the data folder (OFF-03a1)

Backup and restore need a shop's data folder to themselves. This is the folder coordination from
"Folder ownership and maintenance" in `docs/design/BACKUP_RESTORE.md`. **It coordinates only: it does
not make backups.** Nothing changes on screen yet. Backup (OFF-03a3) and restore (OFF-03b) will use it.

## The rule

- **Every running Cella holds a shared lock** on `.cella-maintenance.lock` in its data folder. It takes
  it at start-up, before reading the shop ID or opening the database, and keeps it until it exits,
  even when it is idle. Several Cellas can hold it at once, so normal use is unchanged.
- **All work on the folder takes part:**
  - every database connection passes the folder's gate for its whole life, from opening to closing;
  - every photo change (save, removal, clean-up) passes it for its whole duration, files included;
  - saved receipts, corrections, cash sales and imports are covered by their operation leases, which
    maintenance takes.
- **Maintenance needs the folder alone:**
  1. It pauses new work, then waits (for a set time) for the work already running: open connections
     and photo changes. If they don't finish in time, it gives up and changes nothing.
  2. It gives up this Cella's shared lock and tries to take an exclusive one. If another Cella holds
     the folder, even an idle one, it takes its shared lock back and refuses: « Another Cella has this
     shop open. Close it, then try again. »
  3. It then takes the stock, checkout and import leases, in that order. If a receipt, sale or import
     is in progress, or the import screen is open, it gives everything back and refuses: « Finish the
     operation in progress first. »
  4. When maintenance ends, it releases the leases and takes the shared lock back, and the paused work
     goes on.
- **Maintenance reaches the database only through `Maintenance.openDatabase()`**, which uses the gate's
  pass. There is no other way past a paused gate: an ordinary connection opened on the maintenance
  thread is refused instead of being let through.
- **Nothing waits on the Swing event thread.** Maintenance cannot begin there, and a connection opened
  there during maintenance is refused at once instead of freezing the window.
- **A Cella cannot start while another is in maintenance.** The start-up page says « Another Cella is
  making a backup or restore of this shop. Wait until it finishes, then click Retry. »
- **If this computer cannot share the lock,** Cella refuses to open the folder rather than guess.

## How it is built

| Piece | What it does |
| --- | --- |
| `core/persistence/DatabaseGate` | Installed per folder. `SqliteDatabase.open()` enters it before opening and leaves it once on close (or on a failed open). `enterFolder(folder)` covers file work. `open(pass)` is the owner's maintenance path. Without a gate, nothing changes. |
| `core/product/photo/PhotoStoreLock` | Now enters the folder gate before the photo lock, so every photo change takes part. Connections a photo change opens inside its work never wait for it. |
| `shell/DataFolderCoordinator` | One per folder per process, with one lock-file handle shared by every window: on Linux, closing any second handle to a file drops the process's lock. Provides `join()` (membership; the last one to close lets go), `beginMaintenance(drain)`, `Maintenance.openDatabase()` and `Maintenance.close()`. |
| `DesktopApplication.openShop` | Cella's start-up: joins the folder, then finishes, undoes or runs a restore (OFF-03b3a, `RESTORE_RUN.md`), then loads the shop. The membership lasts until Cella exits; the system then releases the lock. |

**Lock order:** maintenance first, then the stock, checkout and import leases. Never begin maintenance
while holding one of those. Close a `Maintenance` on the thread that began it.

**Swapping the locks is not atomic.** Another Cella may take the folder in the gap. The coordinator
then refuses safely. If it also cannot get its shared lock back, all work in this Cella keeps waiting
until a background thread gets it.

### Public contracts that changed

- `SqliteDatabase.open()` behaves as before unless a gate is installed. New overload: `open(Pass)`.
- `PendingReceiptFileLease` and `CheckoutFileLease` are now public, so the coordinator can take them.
  Nothing else in them changed.
- `PendingImportStore` refuses a second store for the same folder in the same process before opening
  the lock file, and `close()` can be called twice. On Linux, a refused second handle used to release
  the first store's lock silently. New: `holdFolder(folder)`, which takes the import lock without a
  shop, for maintenance.
- `PhotoStoreLock.acquire` (package-private) can now also throw `SQLException`; its three callers
  already declared it.

## Tests

`DataFolderCoordinatorTest` (13) uses real second Java processes for the other Cellas.
`DatabaseGateTest` (6) is in core.

| Test | Checks |
| --- | --- |
| `twoCellasCanHaveTheSameShopOpenAtOnce` | A second Cella joins while the first holds the folder |
| `cellaJoinsTheFolderWhenItStartsBeforeOpeningTheShop` | A Cella started through `openShop` holds the folder |
| `anIdleCellaElsewhereStopsMaintenanceAndThisOneCarriesOnNormally` | Failed exclusive acquisition: `OTHER_INSTANCE`, this Cella keeps working, the folder stays shared; works once the others close |
| `aCellaCannotOpenAShopWhileAnotherIsMaintainingItAndCanOnceItEnds` | `IN_MAINTENANCE`, nothing opened; opens once maintenance ends |
| `maintenanceWaitsForOpenConnectionsThenPausesNewOnesExceptItsOwn` | An already-open connection is waited for; new ones wait and go on after |
| `maintenanceGivesUpWhenWorkDoesNotFinishInTimeAndChangesNothing` | `STILL_WORKING`; work is not held back; the folder stays shared |
| `productPhotoAndThresholdWritesWaitDuringMaintenanceAndGoOnAfterwards` | A product, a minimum threshold and a photo all wait, no photo file is written meanwhile, and all three finish afterwards |
| `photoWorkAlreadyRunningHoldsMaintenanceBackAndItsOwnConnectionsDoNotWait` | Maintenance waits for a running photo change, whose own connection does not wait |
| `anOperationInProgressOrTheImportScreenStopsMaintenanceAndNothingChanges` | A receipt in progress or the open import screen: `OPERATION_RUNNING`, then normal work and sharing go on |
| `maintenanceHoldsEveryOperationLeaseUntilItEnds` | Stock, cash sale and import cannot start during maintenance, and can afterwards |
| `duringMaintenanceOnlyItsOwnPathReachesTheDatabase` | An ordinary connection on the maintenance thread is refused; `openDatabase()` works |
| `theSwingThreadNeverWaitsAndNeverStartsMaintenance` | Refused at once on the Swing thread, both for maintenance and for a connection |
| `theFolderIsOnlyLetGoWhenTheLastMemberLeaves` | Two members: the folder stays held until both leave |
| `DatabaseGateTest` (6) | One enter and one leave per connection (closed twice, failed open); a refusing gate opens nothing; only the owner's pass gets through; folder work enters once; no gate, no change |

## Limits

- Older Cella versions, other programs and database tools do not take part. Close them before a
  backup or restore.
- The start-up check for an unfinished or scheduled restore is in `openShop` since OFF-03b3a; see `RESTORE_RUN.md`.
- Live SQLite on a network share is not supported.

## Review correction: one gate for all paths to a shop

DatabaseGate keys both database connections and file work by real filesystem path. An alternate
symbolic-link path must enter the same gate; databases/subfolders not created yet resolve against
their real existing ancestor. Resolution failures fail closed rather than returning an ungated
connection. DatabaseGateTest covers database and file work through an alias; only those alias tests
are skipped on systems that do not allow creating symbolic links. The ordinary Windows/Linux gate
and two-process tests remain required.

The coordinator tests also wait for maintenance worker completion before releasing membership.
Executor shutdown is bounded and awaited, so teardown cannot race the worker's maintenance close.

# Who is using the data folder (OFF-03a1)

Backup and restore need a shop's data folder to themselves. This is the first part of the
"Folder ownership and maintenance" section of `docs/design/BACKUP_RESTORE.md`. Nothing changes on
screen yet: backup (OFF-03a3) and restore (OFF-03b) will use it.

## The rule

- **Every running Cella holds a shared lock** on `.cella-maintenance.lock` in its data folder. It takes
  it at start-up, before reading the shop ID or opening the database, and keeps it until it exits,
  even when it is idle. Several Cellas can hold it at once, so normal use is unchanged.
- **Maintenance needs the folder alone:**
  1. It first pauses this Cella's database work. New connections wait, and it waits (for a set time)
     for the open ones to close. If they don't close in time, it gives up and changes nothing.
  2. It then gives up this Cella's shared lock and tries to take an exclusive one.
  3. If another Cella holds the folder, even an idle one, it takes its shared lock back and refuses:
     « Another Cella has this shop open. Close it, then try again. » Nothing is touched.
  4. When maintenance ends, it takes the shared lock back and the paused work goes on.
- **A Cella cannot start while another is in maintenance.** The start-up page says « Another Cella is
  making a backup or restore of this shop. Wait until it finishes, then click Retry. »
- **If this computer cannot share the lock,** Cella refuses to open the folder rather than guess.

## How it is built

| Piece | What it does |
| --- | --- |
| `shell/DataFolderCoordinator` | One per folder per process. It keeps one file handle, shared by every member and window: on Linux, closing any second handle to the file would drop this process's lock. `join()` gives a membership, and the last one to close lets go. `beginMaintenance(drain)` and `Maintenance.close()` swap the locks. |
| `core/persistence/DatabaseGate` | Every connection `SqliteDatabase.open()` makes to a gated file enters the gate before opening and leaves it once when closed. The coordinator installs the gate when the first member joins. Without a gate, for example in tests and demos, nothing changes. |
| `DesktopApplication.openShop` | Cella's start-up: joins the folder, then loads the shop. The membership lasts until Cella exits; the system then releases the lock. |

**Lock order:** maintenance first, then the stock, checkout and import leases. Never begin maintenance
while holding one of those.

**Swapping the locks is not atomic.** Another Cella may take the folder in the gap. The coordinator
then refuses safely. If it also cannot get its shared lock back, every connection in this Cella keeps
waiting until a background thread gets it.

## Tests

`DataFolderCoordinatorTest` uses real second Java processes for the other Cellas. `DatabaseGateTest`
is in core.

| Test | Checks |
| --- | --- |
| `twoCellasCanHaveTheSameShopOpenAtOnce` | A second Cella joins while the first holds the folder |
| `cellaJoinsTheFolderWhenItStartsBeforeOpeningTheShop` | A Cella started through `openShop` holds the folder, so maintenance elsewhere is refused |
| `anIdleCellaElsewhereStopsMaintenanceAndThisOneCarriesOnNormally` | Refused with `OTHER_INSTANCE`; this Cella keeps working and the folder stays shared; maintenance works once the others close |
| `aCellaCannotOpenAShopWhileAnotherIsMaintainingItAndCanOnceItEnds` | Refused with `IN_MAINTENANCE`, nothing opened; opens once maintenance ends |
| `maintenanceWaitsForOpenConnectionsThenPausesNewOnesExceptItsOwn` | Waits for an open connection; new ones wait during maintenance and go on after; maintenance itself can use the database |
| `maintenanceGivesUpWhenWorkDoesNotFinishInTimeAndChangesNothing` | `STILL_WORKING`; new work is not held back; the folder stays shared |
| `theFolderIsOnlyLetGoWhenTheLastMemberLeaves` | Two members: the folder stays held until both leave |
| `DatabaseGateTest` (4 tests) | One enter and one leave per connection, even when closed twice or when opening fails; a refusing gate opens nothing; no gate, no change |

## Limits

- Older Cella versions, other programs and database tools do not take part. Close them before a
  backup or restore.
- A connection opened on the Swing event thread during maintenance would wait, freezing the window
  until maintenance ends. Cella's screens normally open their connections in background workers; the
  backup and restore screens (OFF-03c) must check this before maintenance is offered.
- The start-up check for an unfinished restore belongs to OFF-03b, which defines the restore journal.
- Live SQLite on a network share is not supported.

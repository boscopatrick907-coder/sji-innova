# OFF-03: backup and restore — design proposal

Status: **proposal for Patrick's approval.** No code yet. It answers each OFF-03 check in the offline
completion roadmap and ends with the questions that need a decision.

## Goal

A shop can make a backup of everything Cella needs (records, photos, shop identity) to a USB key, and
can restore it on the same computer or a new one. A damaged or wrong backup never touches the shop's
current data. A restore never sends an operation twice.

## What is in the data folder today

| File | In a backup? | Why |
| --- | --- | --- |
| `cella.db` (and `-wal`, `-shm`) | Yes, as one consistent snapshot | All records: products, stock, sales, imports, photos' metadata |
| `shop-id.txt` | Yes | The shop's identity; every record is tied to it |
| `photos/<shop>/…` | Yes, the files the snapshot refers to | Product photos live outside the database |
| `device-id.txt` | **No** | It names this computer, not the shop (see "Identity") |
| `pending-*.properties`, `pending-checkout.txt` | **No: a backup waits until there are none** | See "Saved requests" |
| `*-damaged-*`, `*-set-aside-*` | No | Evidence already kept in the folder; not needed to run the shop |
| `*.lock`, `logs/`, `photos/.staging` | No | Belong to a running Cella only |

## The backup file

One ZIP file, for example `cella-backup-2026-11-10-1530.zip`, readable by any computer:

```
manifest.properties
cella.db
shop-id.txt
photos/<shop>/…
```

`manifest.properties` holds:
- the backup format version (`1`);
- Cella's version and the database schema version;
- the shop ID;
- the date and time of the snapshot;
- the number of products, sales and stock movements;
- every file's size and SHA-256 checksum.

## Making a backup

1. **Refuse while a request is saved.** If a receipt, correction, cash sale or import is waiting to
   be retried, the backup does not start. It names the request and the screen where it can be
   finished. (See "Saved requests".)
2. **Hold the folder.** It takes the stock, checkout and import leases, so no other Cella window or
   process can start an operation during the backup.
3. **Snapshot the database with SQLite `VACUUM INTO`.** SQLite writes a complete, consistent copy in
   one read transaction. It never copies the live `cella.db` file by hand, which could catch it
   half-written. Checked with Cella's SQLite (3.53.4) on a WAL database: run from its own connection
   while another connection was inside a transaction, it produced one self-contained file that passed
   `integrity_check` and kept the schema version. It must run on a connection with no other
   statement open.
4. **Copy the photos the snapshot refers to.** A photo is replaced by publishing the new file first,
   then deleting the old one. If a referenced photo has disappeared meanwhile, the backup stops and
   asks to try again; it never writes a backup with a missing photo.
5. **Write the ZIP under a temporary name, then rename it** when the manifest and every checksum are
   written. A half-written backup never has the final name.
6. **Check it by reading it back:** open the ZIP, compare every checksum, and run SQLite
   `integrity_check` on the copied database.
7. **Record the time of the last successful backup**, shown on the Settings screen.

The backup runs in the background with a progress bar; Cella stays usable for reading, and new
operations wait until it finishes (usually a few seconds).

## Saved requests

**Proposal: no backup while a request is saved.** Every saved request is an operation whose result
is not yet known. Finishing it first (Retry, or set aside a damaged file) means a backup never holds
an uncertain operation. A restore can then never send one again, on this computer or another.

The roadmap allows this choice ("block backup with actionable instructions while writes are
unresolved"). The other choice, putting the requests in the backup, would need a reconciliation step
after every restore. That is more code and more risk for a case that a shop can avoid with one click.

## Restoring

Restore replaces this computer's shop with the backup's shop, as it was at the snapshot date.

1. **Choose a backup file.** Cella checks it **before touching anything**:
   - every ZIP entry is one of the expected names, with no absolute path and no `..`;
   - the manifest's format is known, and every checksum matches;
   - the database passes `integrity_check` and `foreign_key_check`;
   - its schema version is not newer than this Cella (an older one is migrated on start, as usual).

   A file that fails any check is refused, with the reason, and nothing changes.
2. **Show what will happen:** the shop ID, the snapshot date and time, and the number of products,
   sales and photos. Warn plainly: "Everything recorded on this computer after <date> will be
   replaced. Sales made after that date are not in this backup."
3. **Ask for confirmation**, then **close Cella**. The restore itself happens at the next start,
   before any screen opens the database, so nothing in this process holds it.
4. **At the next start**, before anything else, Cella:
   - takes all the folder leases, and stops with a clear message if another Cella is still open;
   - makes an automatic **pre-restore backup** of the current data, next to the other backups;
   - moves the current `cella.db`, `shop-id.txt` and `photos/` into `replaced-<date>/` inside the
     data folder;
   - moves the checked files from the staging folder into place;
   - opens the database normally (migrations run as on any start) and runs `integrity_check`.
5. **If anything fails, it puts the old files back** from `replaced-<date>/` and says so. The current
   data is only removed when a later step confirms the restore worked.

## Identity

- **The shop ID comes from the backup.** All restored records belong to it.
- **The device ID stays this computer's.** Restoring on a new computer keeps the new computer's ID;
  restoring on the same computer keeps its own.
- **Because a backup never holds a saved request, no operation can be sent twice**, even if the old
  computer still has its data.
- **Two computers must not both keep working on copies of the same shop.** Cella has no
  synchronisation, so their records would drift apart. The restore screen says so.

## Where it appears in Cella

A new **Settings** entry in the sidebar, with two cards:

- **Backup:** destination folder (default `Documents\Cella backups`, with a reminder that a USB key
  or another disk is safer), **Create backup**, progress, result, and the last successful backup.
  It warns when the destination is on the same disk as the data folder.
- **Restore:** **Choose a backup…**, the summary from step 2, and **Restore** with its confirmation.

**Reminders and retention:** after a set number of days without a backup, Cella shows a reminder at
start-up. The Settings screen can keep only the last N backups in the destination folder. It only
ever deletes older `cella-backup-*.zip` files it made, never pre-restore backups.

## Delivery in small PRs

| PR | Contents | Proven by |
| --- | --- | --- |
| **OFF-03a** | Backup service: refuse while a request is saved, leases, `VACUUM INTO`, photos, manifest, checksums, ZIP, read-back check. No screen. | Tests on real SQLite and files, including a photo replaced during a backup and a killed backup leaving no final file |
| **OFF-03b** | Restore service: every check, staging, restore at start-up, pre-restore backup, putting the old files back on failure | Tests with damaged, wrong-format, newer-schema and path-escaping backups, each leaving the current data unchanged byte for byte |
| **OFF-03c** | Settings screen, reminders, retention, English and French texts | Screen tests and real-window pictures |
| **OFF-03d** | The roadmap's acceptance check | Back up a shop with photos and sales, restore into a separate data folder, and compare products, sale totals, balances, photos and recovery behaviour |

## Questions for Patrick

1. **Settings in the sidebar now**, or wait for the OFF-07 redesign? It changes `ShellPanel`.
2. **No backup while a request is saved:** do you agree?
3. **Reminder:** after how many days without a backup? (Proposal: 7.)
4. **Retention:** keep how many backups? (Proposal: 10, and off unless the shop turns it on.)
5. **Default destination:** `Documents\Cella backups`, or ask the first time?
6. Still open from OFF-02: should a saved cash sale also be checked against the device?

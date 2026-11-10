# OFF-03: backup and restore — design proposal

Status: **corrected design proposal; implementation is still pending.** Charles's original proposal is
preserved in commit `ef4914a`; this revision addresses the review of PR #46. These are requirements for
future OFF-03 PRs, not claims about behavior already in Cella. Product choices at the end remain
proposals until Patrick selects them.

## Goal

A shop can make a backup of everything Cella needs (records, photos, shop identity) to a USB key, and
can restore it on the same computer or a new one. A damaged or wrong backup never touches the shop's
current data. Restore never submits a business operation automatically; preserved operation IDs and
explicit reconciliation protect retries. This does not prevent people from entering the same real-world
sale twice or operating two disconnected copies of a shop.

## What is in the data folder today

| File | In a backup? | Why |
| --- | --- | --- |
| `cella.db` | Yes, as a self-contained SQLite snapshot | Records and photo metadata; live WAL/SHM are not separate archive entries |
| Live `cella.db-wal`, `cella.db-shm` | No separate archive entries | SQLite incorporates committed data into the snapshot; restore must isolate old sidecars |
| `shop-id.txt` | Yes | The shop's identity; every record is tied to it |
| `photos/<shop>/…` | Yes, the files the snapshot refers to | Product photos live outside the database |
| `device-id.txt` | **No** | It names this computer, not the shop (see "Identity") |
| Active pending requests for stock, checkout and CSV | **No: backup is blocked until reconciled** | Check under maintenance ownership; see "Saved requests" |
| Damaged/set-aside recovery evidence and its resolution records | Yes, in an evidence namespace; unresolved evidence blocks backup | Renaming a file does not settle its operation; never reactivate evidence on restore |
| `*.lock`, `logs/`, `photos/.staging` | No | Belong to a running Cella only |

## The backup file

One ZIP file, for example `cella-backup-2026-11-10-1530.zip`, readable by any computer:

```
manifest.properties
cella.db
shop-id.txt
photos/<shop>/…
recovery-evidence/…
```

`manifest.properties` holds:
- the backup format version (`1`);
- Cella's version and the database schema version;
- the shop ID;
- the date and time of the snapshot;
- the number of products, sales and stock movements;
- every payload file's size and SHA-256 checksum (the manifest does not checksum itself);
- the source device ID as descriptive metadata, never installed as the destination's active identity;
- recovery-evidence inventory and its resolution references.

Checksums detect accidental changes, not authenticity against an attacker who can rewrite the archive
and manifest. Do not label an unsigned backup authenticated or encrypted.

## Folder ownership and maintenance — required before backup or restore

The current stock, checkout and import leases protect individual operations. **They neither detect an
idle Cella instance nor cover product creation, photos, thresholds, identities and every other writer.**
They cannot alone implement this design.

Introduce one canonical data-folder coordinator before exposing backup/restore:

1. Resolve the folder's real/canonical path. One JVM manager owns one channel to a persistent
   `.cella-maintenance.lock` file per folder; windows and contexts in that JVM share it by reference
   count. Do not independently acquire overlapping Java file locks for each window.
2. Every normal participating Cella process holds an OS **shared lifetime lease**, acquired before
   reading identity or opening AppContext/database services and retained until all contexts/workers
   close. An idle process still holds it. Keep the lock file in place; presence is not ownership.
3. For maintenance, disable new actions in every window in this process, drain all queued/in-flight
   database/file workers, close connections and stop timers that could reopen them. Every production
   route participates: startup/migration, products/families, photos and their cleanup, thresholds,
   stock, sales/voids, CSV, identities/settings and future background jobs.
4. Release this JVM's shared lease only after it is quiescent, then attempt an **exclusive** OS lease
   on the same file. This is not an atomic upgrade: if another app enters the gap, acquisition must
   fail safely with “Close the other Cella instance”; do not touch shop files or silently proceed.
   Resume only after reacquiring the ordinary shared lease, or remain blocked/exit cleanly.
5. Hold exclusive ownership through snapshot/evidence/photo capture or the entire restore/recovery
   sequence. Take the existing operation leases in a fixed order: maintenance → stock → checkout →
   import. Never request maintenance ownership while retaining an operation lease. Use bounded,
   cancellable acquisition and unwind every acquired resource on failure.
6. Ordinary startup first takes its shared lease and checks the restore journal before reading shop
   identity or opening services. With no incomplete/scheduled restore, it may continue normally.
   Otherwise release the shared lease and obtain exclusive ownership to recover/restore; if another
   instance prevents that, stay blocked and ask for it to close. Recheck journal state after acquisition.
   Only after recovery is coherent may ordinary services be installed. Drop cached services/identity
   and reload them after restore; never resume an old context.

For the first implementation, pause database/file access during maintenance; cached screen content
and progress/cancel UI can remain visible. Do not promise continued live reads or arbitrary writes.
The coordinator itself must not block the Swing event thread.

Prove shared/exclusive behavior on Windows/Linux and on the supported local filesystem; fail closed
if the required lock behavior is unsupported. Older Cella versions and external database tools do not
honor this new protocol: close them before maintenance. Do not claim the protocol can exclude
nonparticipating programs, or support live SQLite on a network share.

## Making a backup

1. **Enter exclusive maintenance** using the protocol above. Acquire operation leases in the agreed
   order and **then** recheck every active pending request and archived-evidence resolution. An early
   UI check is only advisory: another window could save a request before ownership is acquired.
2. **Refuse unresolved work.** Name the receipt/correction/sale/import and its recovery screen.
   Check all registered recovery stores, not only one filename glob. Apply the policy below to
   damaged/set-aside evidence. No snapshot begins until these checks succeed under the same leases.
3. **Snapshot with SQLite `VACUUM INTO`** to a new temporary file, using a dedicated connection outside
   a transaction and with no other statement/result open. Never copy a live `.db` by hand. Close the
   snapshot connection before validation, and verify that the snapshot is self-contained, has the
   expected schema/shop identity, and passes `integrity_check` and `foreign_key_check`.
4. **Capture assets from that snapshot.** Copy exactly its referenced photos plus required identity,
   resolution records and evidence. Keep maintenance ownership until capture finishes. Missing,
   changed, unreadable or inconsistent assets fail the backup; never fill them with empty placeholders.
5. **Write a uniquely named temporary ZIP** in the destination directory, outside live shop payload
   and restore staging. Include sizes/checksums, then close/flush it. A destination inside an asset,
   staging or quarantine tree is refused. Do not recurse into backups when collecting payload.
6. **Validate the temporary ZIP before publication.** Read it back through the same bounded archive
   validator used for restore, check checksums and SQLite integrity/references, and compare manifest
   counts/identity to the snapshot. On failure keep no success-named archive or success timestamp.
7. **Publish only the verified archive**, using atomic move on the destination filesystem. Where that
   guarantee is unavailable, fail with a useful message in the first release; do not silently fall
   back to an unverified partial final file. Do not overwrite an existing backup. Record last-success
   metadata only after publication, tied to the archive ID/checksum. A crash after publication but
   before metadata update is recovered by recognizing the verified archive, not by reporting a newer
   success without a file.
8. Release maintenance safely and reload the normal context. Interrupted temporary capture/ZIP files
   can be cleaned later by their recorded operation ID; they are never presented as successful backups.

Implementation must document flush/directory-metadata durability and filesystem assumptions; atomic
rename does not by itself prove power-loss durability. Forced-close tests are required; actual power
failure is a separate limitation. Measure duration instead of promising that every backup takes seconds.

## Saved requests and preserved evidence

**Policy: no backup or destructive restore while destination operations remain unresolved.**
A readable pending operation must be confirmed/reconciled through its existing UUID. A damaged or
foreign request may be set aside to unblock ordinary work, but **set aside is not resolved**.

- Preserve archived bytes, origin/shop information where known, and a content hash. Add an explicit
  review/resolution record identifying the evidence, reviewer, time, reason and any matched operation
  UUID/result. Do not infer success/failure from an unreadable file or from a missing active filename.
- Until review establishes the disposition, backup and restore remain blocked with actionable guidance.
  Unknown disposition is not automatically marked “never committed”. The resolution workflow and its
  tests are prerequisites to OFF-03, not functionality already delivered by PR #45.
- Include resolved evidence and its resolution records in `recovery-evidence/` and the manifest, so it
  remains available on a replacement computer. Restore it into a non-active namespace; never copy it
  back to active pending filenames or submit it automatically.
- A pre-restore backup follows the same rules. If it cannot be verified or unresolved work is found,
  restore stops before any active files move. A corrupt destination requiring specialist recovery is
  a separate supported-repair task, not a reason to bypass the pre-restore backup silently.

This reconciles the roadmap's permitted blocking policy with the fact that a damaged request's
outcome may be unknown. Excluding requests is safe only after the exclusion has been established under
ownership and the evidence has a recorded disposition.

## Restoring

Restore replaces the destination shop with a verified snapshot. It does not recover transactions made
after the snapshot date or synchronize two computers.

### Validate and stage before touching active shop data

1. Choose the archive and validate it into a separate, bounded staging directory. Reject unknown
   formats, newer schemas, duplicate entry names, symlinks, path traversal, absolute/drive/UNC paths,
   Windows alternate-stream names and case-colliding paths. Apply limits to entry count, individual
   and total expanded bytes; never extract unlimited ZIP data. Accept only manifest-listed payload
   and the known archive structure. Resolve each destination inside staging before writing.
2. Check every checksum/size, shop identity, photo reference, evidence inventory, schema and database
   integrity/foreign keys. Migrate **only the staged database**, then validate it again. Failure leaves
   the active shop untouched. Derive counts and displayed identity from the validated staged payload.
3. Show the shop, snapshot time and counts with “Records made after this snapshot will be replaced”.
   Ask for confirmation and schedule restore at restart. Bind intent to the staged archive checksum,
   target folder and validated metadata; revalidate after restart. A replaced source ZIP must not be
   substituted after confirmation.

### Exclusive restore with a durable recovery journal

At startup, before ordinary services read the shop identity/database:

1. Acquire exclusive maintenance and operation leases. If another participating instance is open,
   stop without changing the shop. Recheck unresolved destination operations and evidence, then make
   and verify a pre-restore backup. If any step fails, stop here. Retain both staging and current data.
2. Write a versioned, checksummed restore-intent journal outside the files being replaced. It names
   the restore UUID, old/staged identities and checksums, staging/quarantine paths and each component.
   Publish each phase using durable atomic journal replacement; validate paths against fixed roots.
   Keep staged generations and quarantine on the active folder's filesystem for per-file atomic moves;
   unsupported move/durability guarantees must stop the operation rather than trigger unsafe fallback.
3. Use explicit recoverable phases: `PREPARED` → `OLD_QUARANTINED` → `NEW_INSTALLED` → `VERIFIED` →
   `COMMITTED`. Files move one by one, so the journal must also describe the component inventory and
   intended destinations; recovery reconciles actual files/checksums even if a kill precedes a phase
   update. Never assume several renames form a single atomic transaction.
4. Close all old database handles. Preserve the old `cella.db` **together with any existing `-wal` and
   `-shm`** in the old generation/quarantine, along with old shop identity, photos and evidence. Do not
   discard a WAL that may contain committed data or pair old sidecars with the restored database.
5. Install the validated self-contained staged database without old sidecars, its matching shop ID,
   photos and evidence. Preserve destination device identity separately. Revalidate the installed
   generation before any business service can open; write the `COMMITTED` marker before normal startup.
6. Until `COMMITTED`, a restart deterministically restores the complete old generation from journal
   inventory, including sidecars and identity, then validates it before offering the app. An ordinary
   caught failure takes the same recovery path. If a coherent old generation cannot be proved, remain
   in a recovery screen and preserve all generations; never start with a mixture or delete evidence.
7. After `COMMITTED`, restart completes forward: validate/use the new generation and retain the old
   one plus the pre-restore backup. Mark journal completion durably before later optional cleanup.
   If post-commit validation fails, remain blocked for explicit repair; do not silently switch history.

Do not remove the old generation immediately. Retention must distinguish user backups, pre-restore
backups and quarantined generations. A stale UI action must not delete files referenced by a journal.

**Crash acceptance:** kill a real process before/after each component move, phase publication and final
commit marker. Restart without manual file edits and prove a coherent old/new shop, the correct device
policy, no restored active requests, preserved evidence and no duplicate business writes. Test repeat
recovery, full disk, a failed pre-backup, checksum failure, migration failure and an unavailable target.

## Identity

- **The shop ID comes from the backup.** All restored records belong to it.
- **The device ID stays this computer's.** Restoring on a new computer keeps the new computer's ID;
  restoring on the same computer keeps its own.
- Restored evidence is inactive and restore never submits operations. Original transaction IDs stay
  unchanged. Source device identity is retained as provenance in the manifest, not as an active ID.
- Device-ID ownership checks for an active cash request are a separate recovery-policy decision:
  do not change them merely as part of backup or silently relabel a foreign request.
- **Two computers must not both keep working on copies of the same shop.** Cella has no
  synchronisation, so their records would drift apart. The restore screen says so.

## Where it appears in Cella

A new **Settings** entry in the sidebar, with two cards:

- **Backup:** destination folder (default `Documents\Cella backups`, with a reminder that a USB key
  or another disk is safer), **Create backup**, progress, result, and the last successful backup.
  It explains that another disk/USB protects against loss of the computer's disk. Where mount/device
  information cannot establish separation, say it is unknown instead of claiming the destination is safe.
- **Restore:** **Choose a backup…**, the summary from step 2, and **Restore** with its confirmation.

**Reminders and retention:** after a set number of days without a backup, Cella shows a reminder at
start-up. The Settings screen can keep only the last N backups in the destination folder. It only
ever deletes verified backups with matching ownership/manifest metadata in the configured destination,
not arbitrary files matching a name. Never delete pre-restore backups, the last valid backup, active
staging or files referenced by a restore journal. Reminder/retention values below remain proposals.

## Delivery in small PRs

| PR | Contents | Proven by |
| --- | --- | --- |
| **OFF-03a** | Folder coordinator, complete writer integration, evidence resolution policy, snapshot/assets/ZIP validation before publication. No backup screen. | Two JVMs including an idle app; product/photo/threshold writers; pending-file race; unresolved archives; failed/forced-close capture and publication, with no false success |
| **OFF-03b** | Bounded archive validation, staged migration, exclusive startup restore, pre-backup and durable journal/generation recovery. | Bad ZIP/paths/versions, another idle process, source replacement, pending destination work, old WAL/SHM, and kills at every move/phase; rejected input does not change active shop data |
| **OFF-03c** | Settings screen, reminders, retention, English and French texts | Screen tests and real-window pictures |
| **OFF-03d** | The roadmap's acceptance check | Back up a shop with photos and sales, restore into a separate data folder, and compare products, sale totals, balances, photos and recovery behaviour |

## Review acceptance checklist

- [ ] All production file/database access participates in the canonical folder ownership protocol.
- [ ] An idle second process prevents destructive maintenance; failure to acquire ownership changes nothing.
- [ ] Pending/evidence checks are repeated under ownership; a rename never implies reconciliation.
- [ ] Only a read-back-validated archive receives a final name and a success record.
- [ ] Restore staging/migration never modifies the active shop before exclusive ownership and pre-backup.
- [ ] A durable journal and generation inventory handle process termination between individual moves.
- [ ] Old WAL/SHM never accompany the restored snapshot; old committed data remains recoverable.
- [ ] Windows/Linux tests exercise normal, failed and killed processes and complete without orphan readers.

## Questions for Patrick

1. **Settings in the sidebar now**, or wait for the OFF-07 redesign? It changes `ShellPanel`.
2. **No backup while a request is saved:** do you agree?
3. **Reminder:** after how many days without a backup? (Proposal: 7.)
4. **Retention:** keep how many backups? (Proposal: 10, and off unless the shop turns it on.)
5. **Default destination:** `Documents\Cella backups`, or ask the first time?
6. Still open from OFF-02: should a saved cash sale also be checked against the device?

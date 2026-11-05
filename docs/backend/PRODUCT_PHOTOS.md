# Persistent Product Photo Storage (Backend Specification)

**Lead & Author:** Emmanuel Oyono (Backend & Database Lead)  
**Desktop Integration:** Patrick Bosco (Product Catalog & UI)  
**Status:** Implemented on `backend/product-photos`, Schema Version 7.  

---

## 1. Goal in Plain Language

A shopkeeper selects a local photo (JPEG or PNG) for a product. Cella:
1. Validates the image format, file size (maximum 5 MiB), and image dimensions (maximum 20 megapixels).
2. Generates a normalized managed image and a smooth thumbnail (maximum 256x256 bounding box), stripping camera metadata (EXIF/GPS).
3. Copies both files into Cella's managed data directory under `photos/<shopId>/...`.
4. Records the portable relative paths in the SQLite database (`product_photos` table).

**Key guarantees:**
* Moving, renaming, or deleting the original image file never breaks Cella's copy.
* Products without photos remain 100% usable (placeholder state).
* Missing or corrupted image files produce recoverable warnings in the UI, never an application crash or startup failure.
* Replacing a photo is safe: if the database or filesystem encounters an error, the previous photo is preserved intact.

---

## 2. Database Schema (Version 7)

Schema migration version 7 adds the `product_photos` table in SQLite:

```sql
CREATE TABLE product_photos (
    shop_id TEXT NOT NULL,
    product_id TEXT NOT NULL PRIMARY KEY,
    relative_path TEXT NOT NULL CHECK(length(trim(relative_path)) > 0),
    thumbnail_path TEXT NOT NULL CHECK(length(trim(thumbnail_path)) > 0),
    content_type TEXT NOT NULL CHECK(content_type IN ('image/jpeg', 'image/png')),
    file_size INTEGER NOT NULL CHECK(file_size > 0),
    width INTEGER NOT NULL CHECK(width > 0),
    height INTEGER NOT NULL CHECK(height > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(shop_id, product_id) REFERENCES products(shop_id, id) ON DELETE CASCADE
);

CREATE INDEX product_photos_shop ON product_photos(shop_id);
```

### Schema Properties
* **One optional photo per product:** `product_id TEXT NOT NULL PRIMARY KEY` enforces that a product has at most one photo record at any time.
* **Shop scoping:** Enforced by foreign key `(shop_id, product_id) REFERENCES products(shop_id, id)`.
* **Portability:** `relative_path` and `thumbnail_path` store relative references (e.g. `photos/<shopId>/<productId>_<token>.jpg`) with forward slashes (`/`), so the data folder can be moved to any path or OS.

---

## 3. Core Contract & Classes

All classes live in `cm.sji.innova.core.product.photo` (and persistence in `cm.sji.innova.core.persistence`).

### `ProductPhoto`
Immutable metadata record:
* `UUID shopId()`
* `UUID productId()`
* `String relativePath()` (relative to data directory)
* `String thumbnailPath()` (relative to data directory)
* `String contentType()` (`image/jpeg` or `image/png`)
* `long fileSize()` (bytes of main normalized image)
* `int width()` (pixels)
* `int height()` (pixels)
* `Instant createdAt()`
* `Instant updatedAt()`

### `PhotoLoadResult`
Sealed interface representing the outcome of loading a photo:
* `PhotoLoadResult.NotAttached`: Product exists, but has no photo attached. Patrick's UI shows a standard placeholder icon.
* `PhotoLoadResult.Loaded`: Photo is attached and verified on disk. Exposes `ProductPhoto photo()`, `Path imageFile()`, and `Path thumbnailFile()`.
* `PhotoLoadResult.MissingFile`: Metadata exists in DB, but the file was removed or lost on disk. Non-fatal; UI displays a placeholder with a warning badge and allows the user to re-attach.
* `PhotoLoadResult.CorruptFile`: File exists on disk, but cannot be decoded. Non-fatal warning.

### `ProductPhotoService`
Key operations:
* `PhotoLoadResult loadPhoto(UUID shopId, UUID productId)`: Loads and verifies the photo from storage.
* `ProductPhoto setPhoto(UUID shopId, UUID productId, Path sourceFile)`: Validates, stages, normalizes, and attaches/replaces a photo.
* `ProductPhoto setPhoto(UUID shopId, UUID productId, InputStream in, String hint)`: Overload for input streams.
* `boolean removePhoto(UUID shopId, UUID productId)`: Detaches the photo from DB and deletes the managed files. Returns `true` if removed, `false` if none was attached.
* `OrphanCleanupReport cleanupOrphans(UUID shopId)`: Housekeeping to delete stale staging files or unreferenced files.
* `Path resolvePath(String relativePath)`: Resolves a relative path to absolute `Path` with strict directory traversal validation.

---

## 4. Staging and Two-Phase Failure Recovery

Filesystem operations and SQLite transactions cannot execute as a single atomic CPU instruction. To guarantee reliability without corrupting data or leaving dangling files:

1. **Validation:** Checks magic bytes and reads image headers before decoding. Files $> 5$ MiB or $> 20$ MP are rejected immediately with a friendly `PhotoValidationException`.
2. **Staging:** The normalized image and thumbnail are written to temporary staging files (`photos/.staging/<uuid>_main.ext`).
3. **Publishing:** Staged files are moved into the shop directory with unique revision tokens (`photos/<shopId>/<productId>_<token>.ext`).
4. **Database Transaction:** `INSERT ... ON CONFLICT(product_id) DO UPDATE ...` executes in SQLite.
5. **Failure reconciliation:** A write error may occur after auto-commit. Read the metadata
   again before deleting published files. If it points at the new revision, return that
   committed photo. If the read cannot establish the outcome, retain the files and
   surface the error; later safe cleanup can reclaim genuine orphans. Delete the new
   revision only when it is known not to be referenced.
6. **Post-Commit Cleanup:** Only *after* the SQLite commit succeeds, the old photo's files on disk are safely deleted.

---

## 5. UI Calling Guide for Patrick (Swing EDT Safety)

All file I/O, image scaling, and SQLite queries must run **off the Swing Event Dispatch Thread (EDT)** using `SwingWorker`.

### Recommended Form Workflow: Create Product Then Attach Photo

Creating the product in the catalog and attaching a photo are separate operations:
1. Call `productService.create(...)` first.
2. If the user selected an image file, call `photoService.setPhoto(shopId, product.id(), selectedFile)`.
3. If photo attachment fails (e.g. invalid file format or disk error), inform the user: *"The product was created successfully, but the photo could not be attached: [error]. You can retry attaching a photo from the product edit screen."* This prevents losing the product input if an image file is invalid.

### Example SwingWorker: Loading a Product Thumbnail

```java
public void loadProductThumbnail(UUID shopId, UUID productId, JLabel thumbnailLabel) {
    thumbnailLabel.setIcon(null);
    thumbnailLabel.setText("Loading...");

    new SwingWorker<PhotoLoadResult, Void>() {
        @Override
        protected PhotoLoadResult doInBackground() throws Exception {
            // Runs on background worker thread
            return photoService.loadPhoto(shopId, productId);
        }

        @Override
        protected void done() {
            // Runs on Swing EDT
            try {
                PhotoLoadResult result = get();
                switch (result) {
                    case PhotoLoadResult.Loaded loaded -> {
                        // Load thumbnail image without freezing the UI
                        ImageIcon icon = new ImageIcon(loaded.thumbnailFile().toString());
                        thumbnailLabel.setIcon(icon);
                        thumbnailLabel.setText("");
                        thumbnailLabel.setToolTipText(loaded.photo().width() + "x" + loaded.photo().height() + " px");
                    }
                    case PhotoLoadResult.NotAttached na -> {
                        thumbnailLabel.setIcon(defaultPlaceholderIcon);
                        thumbnailLabel.setText("");
                        thumbnailLabel.setToolTipText("No photo attached");
                    }
                    case PhotoLoadResult.MissingFile mf -> {
                        thumbnailLabel.setIcon(warningPlaceholderIcon);
                        thumbnailLabel.setText("");
                        thumbnailLabel.setToolTipText("Photo file is missing: " + mf.message());
                    }
                    case PhotoLoadResult.CorruptFile cf -> {
                        thumbnailLabel.setIcon(warningPlaceholderIcon);
                        thumbnailLabel.setText("");
                        thumbnailLabel.setToolTipText("Photo file is corrupted: " + cf.message());
                    }
                }
            } catch (Exception ex) {
                thumbnailLabel.setIcon(warningPlaceholderIcon);
                thumbnailLabel.setText("Error");
            }
        }
    }.execute();
}
```

### Example SwingWorker: Attaching a Chosen Photo

```java
public void attachPhoto(UUID shopId, UUID productId, Path chosenFile, Runnable onSuccess, Consumer<String> onError) {
    new SwingWorker<ProductPhoto, Void>() {
        @Override
        protected ProductPhoto doInBackground() throws Exception {
            return photoService.setPhoto(shopId, productId, chosenFile);
        }

        @Override
        protected void done() {
            try {
                ProductPhoto photo = get();
                onSuccess.run();
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof PhotoValidationException pve) {
                    onError.accept(pve.getMessage()); // Friendly validation error
                } else {
                    onError.accept("Could not save photo: " + cause.getMessage());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }.execute();
}
```

---

## 6. Portable Backup and Restore Procedure

### Critical Backup Rule
> **IMPORTANT:** Copying only `cella.db` is **NOT** a complete backup. Cella stores images on the filesystem and relative pointers in SQLite. Copying WAL files alone or SQLite alone will produce dangling references and missing images on restore.

To make a complete, portable backup of Cella:
1. **Locate the Cella data directory:**
   * Windows: `%APPDATA%\Cella` (e.g. `C:\Users\<user>\AppData\Roaming\Cella`)
   * Linux / macOS: `~/.cella`
2. **Back up the entire folder:**
   Copy the entire directory including:
   * `cella.db` (database file)
   * `photos/` (all managed shop product photos and thumbnails)
   * `shop-id.txt` (local shop identity)
   * `device-id.txt` (local device identity)
3. **To Restore:**
   * Place the backed up folder into the target computer's `%APPDATA%\Cella` (or pass the path via `--data-dir <path>`).
   * When Cella starts up, all relative references (`photos/<shopId>/...`) resolve identically against the new location, restoring all products, stock, sales, and photos seamlessly.



## Review corrections and maintenance protocol

Emmanuel's original commit `5a3c942` is retained. Follow-up corrections serialize
set/replace/remove and orphan cleanup with a data-directory lock. A JVM-local lock
coordinates service instances and an OS file lock on `.photo-store.lock` coordinates
processes on the same local filesystem. Leave that lock file in place; deleting it
while the app is running can break coordination. Maintenance waits for active
writes, including writes in other shops sharing the staging folder. These calls
may block and must stay off the Swing EDT. A crash releases the OS lock, allowing
later cleanup; only unreferenced files are removed. Network-shared folders and
external writers that ignore the protocol are unsupported.

Loading now checks both the main image and thumbnail, including actual raster
decoding with dimension bounds; intact headers alone no longer count as success.
The InputStream upload overload checks shop/product ownership before copying,
limits bytes as they arrive, and treats filenameHint only as display text.
It leaves the caller-owned input stream open. Path uploads retain the 5 MiB/20 MP
limits; normalized stored files are dimension-bounded on loading.

Regressions cover active-save cleanup across instances and another JVM, a lost
successful-commit response, an unavailable reconciliation query, truncated main
pixels, corrupt thumbnails and oversized stream input. Photos are still a backend
service; the desktop photo picker is separate integration work.

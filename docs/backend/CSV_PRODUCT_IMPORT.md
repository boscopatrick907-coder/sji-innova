# CSV Product Import: Backend Specification & Developer Guide

**Lead & Author:** Emmanuel Oyono (Backend & Database Lead)  
**Desktop Integration:** Patrick Bosco (Product Catalog & UI)  
**Status:** Implemented on `backend/csv-product-import`, Schema Version 8.  
**Brief Reference:** [`docs/team/EMMANUEL_01_CSV_IMPORT.md`](../team/EMMANUEL_01_CSV_IMPORT.md).  

---

## 1. Goal in Plain Language

A shopkeeper exports a product catalog from Excel as a CSV file. Cella:
1. **Previews without Writing:** Parses the file, checks product validation rules, and flags candidate duplicates without creating or modifying any database records.
2. **Selective Confirmed Import:** The user reviews the row-by-row preview in the desktop dialog, unchecks unwanted rows, and confirms the valid items they want to import.
3. **Durable & Idempotent:** Each confirmed import uses a unique client-generated `operationId`. The product and its import mapping are committed together in the same database transaction. Identical retries (e.g. after a network timeout, crash, or restart) safely return the existing imported products without creating duplicates.
4. **Clean Scope:** Products only. No opening stock quantities or stock movements are created; imported products start with the standard zero-stock state. No photo attachments; photos are managed separately via the photo service.

---

## 2. File Format & Template

A UTF-8 template file is located at [`docs/import/products_template.csv`](../import/products_template.csv):

```csv
name,model_reference,category,unit,buying_price,selling_price
T-shirt classique,TS-001,Vêtements,piece,2000,3000
Riz parfumé,RIZ-001,Alimentation,kg,600,800
Câble USB-C,USB-02,Accessoires,piece,,2500
Sac de farine 50kg,FAR-50,Alimentation,bag,18000,22500
Huile de palme raffinée,HUI-01,Alimentation,litre,900,1200
```

### Columns & Rules
| Column | Required? | Rules & Validation |
|---|---|---|
| `name` | **Yes** | Trimmed, non-empty text. Must not be blank. |
| `model_reference` | No | Optional supplier/model code (e.g., `TS-001`). Blank is normalized to `null`. Not required to be unique. |
| `category` | No | Optional category text (e.g., `Alimentation`). Blank is normalized to `null`. |
| `unit` | **Yes** | Canonical unit code: `piece`, `bag`, `kg`, or `litre` (case-insensitive, normalized to lowercase). Any other unit is rejected with an actionable error. |
| `buying_price` | No | Non-negative whole FCFA (`long`). Blank means unknown (`null`), NOT zero. Explicit `0` is preserved. Buying price may exceed selling price (clearance sales). |
| `selling_price` | **Yes** | Non-negative whole FCFA (`long`). |

### Parsing Rules & Parser Choice
* **Parser:** Built-in strict RFC-4180 compliant state-machine scanner (`Rfc4180CsvReader.java`).
  * *Why built-in zero-dependency parser:* Avoids external Maven shade/module-info conflicts, completely self-contained, offline-compatible, and guarantees exact compliance with RFC-4180 multiline fields, quote escaping (`""`), and UTF-8 BOM (`\uFEFF`) handling.
* **Delimiters:** Auto-detects comma (`,`) or semicolon (`;`), with explicit override in `ImportOptions`. Semicolon support is critical for Cameroonian Windows PCs using French Excel locales.
* **Unsupported Columns:** Columns such as `quantity`, `stock`, or `photo` are explicitly rejected with friendly errors instead of silently discarded.
* **Line Numbering:** Row indices in `ParsedRow` and `ImportedRowResult` are 1-based data row numbers (row 1 is the first data row following the header).

---

## 3. Database Schema (Version 8)

Migration version 8 adds durable tracking for import operations and individual row mappings:

```sql
CREATE TABLE product_import_operations (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    content_hash TEXT NOT NULL CHECK(length(content_hash) > 0),
    total_selected INTEGER NOT NULL CHECK(total_selected >= 0),
    created_at TEXT NOT NULL
);
CREATE INDEX product_import_operations_shop ON product_import_operations(shop_id, created_at);

CREATE TABLE product_import_rows (
    operation_id TEXT NOT NULL,
    row_number INTEGER NOT NULL CHECK(row_number >= 1),
    shop_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    row_hash TEXT NOT NULL CHECK(length(row_hash) > 0),
    created_at TEXT NOT NULL,
    PRIMARY KEY(operation_id, row_number),
    FOREIGN KEY(operation_id) REFERENCES product_import_operations(id) ON DELETE CASCADE,
    FOREIGN KEY(shop_id, product_id) REFERENCES products(shop_id, id) ON DELETE CASCADE
);
CREATE INDEX product_import_rows_product ON product_import_rows(shop_id, product_id);
CREATE INDEX product_import_rows_hash ON product_import_rows(shop_id, row_hash);
```

---

## 4. Duplicate Detection & Transaction Policy

### Duplicate Candidate Warnings
* Previews check each row against:
  1. Earlier rows in the same CSV file.
  2. Existing products in the current shop catalog (matching `name` or `model_reference`).
  3. Previously imported row content hashes (`product_import_rows.row_hash`).
* Cella **never silently merges or overwrites** an existing product.
* In the desktop UI, candidate duplicates have warning messages attached and default to unselected. If the shopkeeper explicitly checks them, Cella creates distinct products with unique references (`P-0001`, `P-0002`).

### Atomic Per-Row Transaction Policy
* Confirmed imports execute per-row commits.
* For each confirmed row, SQLite acquires `BEGIN IMMEDIATE`:
  1. Increments `product_reference_sequences` to allocate the next reference (`P-xxxx`).
  2. Inserts the row into `products`.
  3. Inserts the mapping into `product_import_rows`.
  4. Commits.
* If a crash or failure occurs on row $k$, rows $1 \dots k-1$ remain durably saved, row $k$ rolls back cleanly, and subsequent retry under the same `operationId` continues from row $k$ without re-inserting rows $1 \dots k-1$.
* Submitting the same `operationId` with *different* file content or selection throws `IllegalArgumentException`.

---

## 5. UI Integration Guide for Patrick (Swing EDT Safety)

All parsing and database execution must run **off the Swing EDT** using `SwingWorker`.

### Recommended Form Workflow

```
[ User selects CSV file ]
           ↓
[ Background SwingWorker: importService.preview(...) ]
           ↓
[ On EDT: Display Preview Dialog ]
  - Table of rows with Status / Name / Unit / Prices / Warnings
  - Rows with errors: red, uncheckable
  - Rows with warnings: yellow, unchecked by default
  - Valid rows: green, checked by default
           ↓
[ User clicks "Import Selected (N items)" ]
           ↓
[ Background SwingWorker: importService.confirmImport(...) ]
           ↓
[ On EDT: Show success summary and refresh product list ]
```

### Example SwingWorker: Previewing CSV

```java
public void previewCsv(UUID shopId, Path csvFile, Consumer<ImportPreview> onPreviewReady, Consumer<String> onError) {
    new SwingWorker<ImportPreview, Void>() {
        @Override
        protected ImportPreview doInBackground() throws Exception {
            try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
                return importService.preview(shopId, reader, ImportOptions.DEFAULT);
            }
        }

        @Override
        protected void done() {
            try {
                ImportPreview preview = get();
                if (!preview.fileErrors().isEmpty()) {
                    onError.accept(String.join("\n", preview.fileErrors()));
                } else {
                    onPreviewReady.accept(preview);
                }
            } catch (Exception ex) {
                onError.accept("Failed to read CSV: " + ex.getMessage());
            }
        }
    }.execute();
}
```

### Example SwingWorker: Confirming Import

```java
public void confirmImport(
        UUID shopId,
        ImportPreview preview,
        List<Integer> selectedRowNumbers,
        Consumer<ImportResult> onImportComplete,
        Consumer<String> onError) {

    UUID operationId = UUID.randomUUID();
    ConfirmedImportRequest request = new ConfirmedImportRequest(
            operationId, preview.previewId(), preview.contentHash(), selectedRowNumbers);

    new SwingWorker<ImportResult, Void>() {
        @Override
        protected ImportResult doInBackground() throws Exception {
            return importService.confirmImport(shopId, request, preview.rows());
        }

        @Override
        protected void done() {
            try {
                ImportResult result = get();
                onImportComplete.accept(result);
            } catch (Exception ex) {
                onError.accept("Import failed: " + ex.getMessage());
            }
        }
    }.execute();
}
```

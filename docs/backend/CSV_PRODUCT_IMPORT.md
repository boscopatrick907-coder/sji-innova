# CSV Product Import: Backend Format & Service Specification (Milestone A)

**Owner:** Emmanuel Oyono. **Desktop Integration:** Patrick.  
**Brief Reference:** [`docs/team/EMMANUEL_01_CSV_IMPORT.md`](../team/EMMANUEL_01_CSV_IMPORT.md).  
**Branch:** `backend/csv-product-import`.

---

## 1. Goal & Principles

Enable shopkeepers to export product catalogs from Excel as CSV and import them safely into Cella:
1. **Preview First (Read-only):** The user selects a CSV file. The preview parses the file, normalizes fields, checks business rules, and detects duplicate candidates without writing anything to the database.
2. **Selective Confirmed Import:** The user reviews the row-by-row preview in the desktop UI and explicitly confirms selected valid rows.
3. **Durability & Idempotency:** The import operation is tied to an operation UUID and row mappings to ensure retries across timeouts, crashes, or network interruptions cannot import duplicate products.
4. **Clean Scope:** Products only. No stock deductions, movements, sales, or photos. Every imported product starts with initial zero stock (no stock movements).

---

## 2. File Format & Encoding Rules

### Header Format
```csv
name,model_reference,category,unit,buying_price,selling_price
```

A template file is available at [`docs/import/products_template.csv`](products_template.csv).

### Field Rules & Data Types
| Column | Required? | Rules & Validation |
|---|---|---|
| `name` | **Yes** | Trimmed, non-empty text. Must not be blank. |
| `model_reference` | No | Optional reference code/SKU (e.g., `TS-001`). Blank is normalized to `null`. |
| `category` | No | Optional classification (e.g., `Vêtements`). Blank is normalized to `null`. |
| `unit` | **Yes** | Canonical unit code: `piece`, `kg`, `litre`, or `bag` (case-insensitive, normalized to lowercase). Any other value is rejected. |
| `buying_price` | No | Whole FCFA (`long`). Non-negative. Blank means unknown (`null`), NOT zero. Buying price may exceed selling price (e.g., clearance). |
| `selling_price` | **Yes** | Whole FCFA (`long`). Non-negative. |

### Parser & Syntax Handling
- **Encoding:** UTF-8 (with or without UTF-8 BOM `\uFEFF`).
- **Delimiters:** Auto-detect or explicit choice between comma (`,`) and semicolon (`;`), essential for Excel exports on French Windows locales.
- **Quoting & Escaping:** Standard RFC-4180 quoting (`"value, with comma"`, escaped quotes `""`, and multiline strings).
- **Line Endings:** Supports CRLF (`\r\n`) and LF (`\n`).
- **Disallowed Columns:** Unrecognized columns (e.g. `quantity`, `stock`, `photo`) are explicitly flagged with an actionable error rather than silently dropped.

---

## 3. Proposed Java Service Contract

### Package: `cm.sji.innova.core.product.importer`

```java
public interface ProductImportService {

    /**
     * Read-only preview: parses CSV and inspects against current shop data without writing anything.
     */
    ImportPreview preview(UUID shopId, Reader csvReader, ImportOptions options) throws IOException, SQLException;

    /**
     * Confirmed import: creates products for the explicitly selected rows in the request.
     * Safe against identical retries after failure or restart.
     */
    ImportResult confirmImport(UUID shopId, ConfirmedImportRequest request) throws SQLException;
}
```

### Data Models & Records

```java
public record ImportOptions(
    Character delimiter,     // Optional: ',', ';', or null for auto-detection
    boolean allowDuplicates  // Default false: flag duplicate warnings
) {}

public record ParsedRow(
    int rowNumber,           // 1-based data row index (row 1 is first data row after header)
    String rawLine,          // Source CSV line text for debugging/UI
    String name,
    String modelReference,
    String category,
    String unit,
    Long buyingPrice,
    Long sellingPrice,
    List<String> errors,     // Validation errors preventing import
    List<String> warnings    // Duplicate candidates or non-fatal alerts
) {
    public boolean isValid() { return errors.isEmpty(); }
    public boolean hasWarnings() { return !warnings.isEmpty(); }
}

public record ImportPreview(
    UUID previewId,
    UUID shopId,
    int totalRows,
    int validRows,
    int invalidRows,
    int duplicateWarningRows,
    List<ParsedRow> rows,
    List<String> fileErrors   // File-level issues: malformed header, empty file, encoding
) {
    public boolean canImportAny() { return validRows > 0 && fileErrors.isEmpty(); }
}

public record ConfirmedImportRequest(
    UUID operationId,         // Client-generated UUID for retry idempotency
    UUID previewId,           // Binds to the reviewed preview
    List<Integer> selectedRowNumbers // Explicit 1-based row numbers confirmed by user
) {}

public record ImportedRowResult(
    int rowNumber,
    UUID productId,          // Persisted product UUID if imported
    Status status,           // SUCCESS, SKIPPED, or FAILED
    String message
) {
    public enum Status { SUCCESS, SKIPPED, FAILED }
}

public record ImportResult(
    UUID operationId,
    UUID shopId,
    int importedCount,
    int skippedCount,
    int failedCount,
    List<ImportedRowResult> rowResults,
    boolean replayed         // true if identical retry returned existing import
) {}
```

---

## 4. Duplicate Detection & Transaction Policy

1. **Duplicate Detection:**
   - Candidate matches are checked within the file and against existing products in `products WHERE shop_id = ?` matching on normalized `name` or `model_reference`.
   - Cella never silently merges or overwrites existing products on name alone.
   - Rows with duplicate warnings default to unchecked in Patrick's UI, requiring explicit cashier confirmation if they are genuinely new items.

2. **Transaction Policy:**
   - Previews are strictly read-only (`Connection.setReadOnly(true)` or query-only).
   - Confirmed imports write products using per-row atomic inserts, tracking progress in an append-only `product_import_rows` operation log so timeouts can safely report what was imported and retries do not re-create rows.

---

## 5. Next Steps (Handoff to Patrick)
- Patrick can design the Swing preview table/dialog based on `ImportPreview` and `ParsedRow`.
- Emmanuel will implement the parser (using a robust RFC-4180 parser) and `SqliteProductImportRepository` in Milestone B.


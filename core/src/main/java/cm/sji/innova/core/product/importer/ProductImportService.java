package cm.sji.innova.core.product.importer;

import cm.sji.innova.core.product.Product;
import java.io.IOException;
import java.io.Reader;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service orchestrating CSV catalog preview, validation, and safe confirmed import.
 */
public final class ProductImportService {

    private static final Set<String> ALLOWED_UNITS = Set.of("piece", "kg", "litre", "bag");
    private static final Set<String> KNOWN_HEADERS = Set.of(
            "name", "model_reference", "category", "unit", "buying_price", "selling_price"
    );

    private final ProductImportRepository repository;

    public ProductImportService(ProductImportRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * Strictly read-only preview: parses CSV and inspects against current shop data without mutating database.
     */
    public ImportPreview preview(UUID shopId, Reader csvReader, ImportOptions options) throws IOException, SQLException {
        Objects.requireNonNull(shopId, "shopId must not be null");
        Objects.requireNonNull(csvReader, "csvReader must not be null");
        ImportOptions opt = options != null ? options : ImportOptions.DEFAULT;

        List<Rfc4180CsvReader.CsvRecord> records;
        try {
            records = Rfc4180CsvReader.readAll(csvReader, opt.delimiter());
        } catch (CsvParseException cpe) {
            return new ImportPreview(
                    UUID.randomUUID(), shopId, 0, 0, 0, 0,
                    List.of(), List.of("CSV format syntax error: " + cpe.getMessage()), "empty");
        }

        if (records.isEmpty()) {
            return new ImportPreview(
                    UUID.randomUUID(), shopId, 0, 0, 0, 0,
                    List.of(), List.of("The CSV file is empty or contains no records."), "empty");
        }

        // 1. Validate Header Row (Record 0)
        Rfc4180CsvReader.CsvRecord headerRecord = records.get(0);
        List<String> headerCols = headerRecord.fields();
        List<String> fileErrors = new ArrayList<>();

        Map<String, Integer> colIndexMap = new HashMap<>();
        for (int i = 0; i < headerCols.size(); i++) {
            String col = headerCols.get(i).trim().toLowerCase(Locale.ROOT);
            if (col.isEmpty()) {
                fileErrors.add("Blank header at column " + (i + 1));
                continue;
            }
            if (colIndexMap.containsKey(col)) {
                fileErrors.add("Duplicate column header '" + col + "' at position " + (i + 1));
            } else {
                colIndexMap.put(col, i);
            }
            if (!KNOWN_HEADERS.contains(col)) {
                fileErrors.add("Unsupported column '" + headerCols.get(i)
                        + "'. CSV product import only supports catalog fields (name, model_reference, category, unit, buying_price, selling_price). Stock and photos must be managed separately.");
            }
        }

        if (!colIndexMap.containsKey("name")) {
            fileErrors.add("Missing required header column 'name'.");
        }
        if (!colIndexMap.containsKey("unit")) {
            fileErrors.add("Missing required header column 'unit'.");
        }
        if (!colIndexMap.containsKey("selling_price")) {
            fileErrors.add("Missing required header column 'selling_price'.");
        }

        if (!fileErrors.isEmpty()) {
            return new ImportPreview(
                    UUID.randomUUID(), shopId, records.size() - 1, 0, records.size() - 1, 0,
                    List.of(), fileErrors, "header-error");
        }

        // 2. Fetch existing catalog products for duplicate candidate detection
        List<ProductImportRepository.ExistingProductSummary> existingProducts = repository.listExistingProducts(shopId);
        Map<String, ProductImportRepository.ExistingProductSummary> existingByName = new HashMap<>();
        Map<String, ProductImportRepository.ExistingProductSummary> existingByRef = new HashMap<>();
        for (var p : existingProducts) {
            existingByName.put(p.name().trim().toLowerCase(Locale.ROOT), p);
            if (p.modelReference() != null && !p.modelReference().isBlank()) {
                existingByRef.put(p.modelReference().trim().toLowerCase(Locale.ROOT), p);
            }
        }

        // 3. Process Data Rows
        List<ParsedRow> parsedRows = new ArrayList<>();
        Map<String, Integer> seenNamesInFile = new HashMap<>();
        Map<String, Integer> seenRefsInFile = new HashMap<>();

        int validCount = 0;
        int invalidCount = 0;
        int warningCount = 0;

        for (int i = 1; i < records.size(); i++) {
            Rfc4180CsvReader.CsvRecord rec = records.get(i);
            int rowNumber = i; // 1-based data row index
            List<String> fields = rec.fields();
            List<String> rowErrors = new ArrayList<>();
            List<String> rowWarnings = new ArrayList<>();
            if (fields.size() != headerCols.size()) {
                rowErrors.add("Expected " + headerCols.size() + " columns but found " + fields.size());
            }

            String rawName = getColValue(fields, colIndexMap.get("name"));
            String rawModelRef = getColValue(fields, colIndexMap.get("model_reference"));
            String rawCategory = getColValue(fields, colIndexMap.get("category"));
            String rawUnit = getColValue(fields, colIndexMap.get("unit"));
            String rawBuying = getColValue(fields, colIndexMap.get("buying_price"));
            String rawSelling = getColValue(fields, colIndexMap.get("selling_price"));

            // Name
            String name = null;
            if (rawName == null || rawName.isBlank()) {
                rowErrors.add("Product name must not be blank.");
            } else {
                name = rawName.trim();
            }

            // Unit
            String unit = null;
            if (rawUnit == null || rawUnit.isBlank()) {
                rowErrors.add("Unit is required.");
            } else {
                String normalizedUnit = rawUnit.trim().toLowerCase(Locale.ROOT);
                if (!ALLOWED_UNITS.contains(normalizedUnit)) {
                    rowErrors.add("Invalid unit '" + rawUnit + "'. Allowed units are: piece, bag, kg, litre.");
                } else {
                    unit = normalizedUnit;
                }
            }

            // Selling price
            Long sellingPrice = null;
            if (rawSelling == null || rawSelling.isBlank()) {
                rowErrors.add("Selling price is required.");
            } else {
                try {
                    sellingPrice = parsePrice(rawSelling.trim());
                    if (sellingPrice < 0) {
                        rowErrors.add("Selling price must not be negative: " + rawSelling);
                    }
                } catch (NumberFormatException e) {
                    rowErrors.add("Selling price must be a whole FCFA number: '" + rawSelling + "'.");
                }
            }

            // Buying price
            Long buyingPrice = null;
            if (rawBuying != null && !rawBuying.isBlank()) {
                try {
                    buyingPrice = parsePrice(rawBuying.trim());
                    if (buyingPrice < 0) {
                        rowErrors.add("Buying price must not be negative: " + rawBuying);
                    }
                } catch (NumberFormatException e) {
                    rowErrors.add("Buying price must be a whole FCFA number: '" + rawBuying + "'.");
                }
            }

            String modelRef = (rawModelRef != null && !rawModelRef.isBlank()) ? rawModelRef.trim() : null;
            String category = (rawCategory != null && !rawCategory.isBlank()) ? rawCategory.trim() : null;

            // Duplicate warnings (only if row is otherwise valid so far)
            if (rowErrors.isEmpty()) {
                String nameKey = name.toLowerCase(Locale.ROOT);
                if (seenNamesInFile.containsKey(nameKey)) {
                    rowWarnings.add("Possible duplicate: name matches row " + seenNamesInFile.get(nameKey) + " in this file.");
                } else {
                    seenNamesInFile.put(nameKey, rowNumber);
                }

                if (modelRef != null) {
                    String refKey = modelRef.toLowerCase(Locale.ROOT);
                    if (seenRefsInFile.containsKey(refKey)) {
                        rowWarnings.add("Possible duplicate: model_reference matches row " + seenRefsInFile.get(refKey) + " in this file.");
                    } else {
                        seenRefsInFile.put(refKey, rowNumber);
                    }
                }

                if (existingByName.containsKey(nameKey)) {
                    var existing = existingByName.get(nameKey);
                    rowWarnings.add("Matches existing product in catalog: '" + existing.name() + "' (" + existing.reference() + ").");
                }
                if (modelRef != null && existingByRef.containsKey(modelRef.toLowerCase(Locale.ROOT))) {
                    var existing = existingByRef.get(modelRef.toLowerCase(Locale.ROOT));
                    rowWarnings.add("Model reference matches existing product in catalog (" + existing.reference() + ").");
                }
            }

            ParsedRow parsed = new ParsedRow(
                    rowNumber, rec.rawText(), name, modelRef, category, unit,
                    buyingPrice, sellingPrice, rowErrors, rowWarnings);

            // Check if exact row hash was previously imported
            if (parsed.isValid() && repository.wasPreviouslyImported(shopId, parsed.rowHash())) {
                List<String> withPrior = new ArrayList<>(parsed.warnings());
                withPrior.add("This exact product was previously imported.");
                parsed = new ParsedRow(
                        rowNumber, rec.rawText(), name, modelRef, category, unit,
                        buyingPrice, sellingPrice, parsed.errors(), withPrior);
            }

            parsedRows.add(parsed);

            if (parsed.isValid()) {
                validCount++;
            } else {
                invalidCount++;
            }
            if (parsed.hasWarnings()) {
                warningCount++;
            }
        }

        String contentHash = ImportFingerprint.content(parsedRows);

        return new ImportPreview(
                ImportFingerprint.previewId(shopId, contentHash), shopId, parsedRows.size(), validCount,
                invalidCount, warningCount, parsedRows, List.of(), contentHash);
    }

    /** Preferred API: keeps the reviewed shop/preview and rows together. */
    public ImportResult confirmImport(UUID shopId,ConfirmedImportRequest request,ImportPreview preview) throws SQLException {
        Objects.requireNonNull(preview,"preview");
        if(!shopId.equals(preview.shopId()) || !request.previewId().equals(preview.previewId())
                || !request.contentHash().equals(preview.contentHash()) || !preview.fileErrors().isEmpty()) {
            throw new IllegalArgumentException("Confirmation does not match the reviewed preview");
        }
        return confirmImport(shopId,request,preview.rows());
    }

    /**
     * Executes confirmed import for explicitly reviewed and selected rows.
     */
    public ImportResult confirmImport(
            UUID shopId,
            ConfirmedImportRequest request,
            List<ParsedRow> reviewedRows) throws SQLException {

        Objects.requireNonNull(shopId, "shopId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(reviewedRows, "reviewedRows must not be null");

        String actualContent = ImportFingerprint.content(reviewedRows);
        if (!actualContent.equals(request.contentHash())
                || !ImportFingerprint.previewId(shopId, actualContent).equals(request.previewId())) {
            throw new IllegalArgumentException("Confirmation does not match the reviewed preview/shop");
        }
        Map<Integer,ParsedRow> verified = new HashMap<>();
        for (ParsedRow row : reviewedRows) {
            if (row.rowNumber()<1 || verified.putIfAbsent(row.rowNumber(),row)!=null)
                throw new IllegalArgumentException("Preview contains invalid or duplicate row numbers");
        }
        for(int number:request.selectedRowNumbers()) {
            if(!verified.containsKey(number))throw new IllegalArgumentException("Selected row not in preview: "+number);
        }
        String requestIdentity = ImportFingerprint.request(shopId,request);
        // Atomic registration checks the entire request, including selection/duplicate consent.
        repository.recordOperation(request.operationId(), shopId, requestIdentity, request.selectedRowNumbers().size());

        // 1. Idempotency check: verify if operation ID already exists
        Optional<ProductImportRepository.ImportOperationRecord> existingOp = repository.findOperation(request.operationId());
        if (existingOp.isPresent()) {
            var op = existingOp.get();
            if (!op.shopId().equals(shopId)) {
                throw new IllegalArgumentException("Operation " + request.operationId() + " belongs to a different shop.");
            }
            if (!op.contentHash().equals(requestIdentity)) {
                throw new IllegalArgumentException("Operation " + request.operationId()
                        + " was already executed with different file content or row selection.");
            }

            // Same operationId and same contentHash: this is an idempotent retry or replay
            List<ProductImportRepository.ImportedRowRecord> committed = repository.listImportedRows(request.operationId());
            Map<Integer, ProductImportRepository.ImportedRowRecord> committedByRow = new HashMap<>();
            for (var c : committed) {
                committedByRow.put(c.rowNumber(), c);
            }

            // If all selected rows were committed, return replayed result
            boolean allCommitted = true;
            for (int r : request.selectedRowNumbers()) {
                if (!committedByRow.containsKey(r)) {
                    allCommitted = false;
                    break;
                }
            }

            if (allCommitted) {
                List<ImportedRowResult> results = new ArrayList<>();
                for (int r : request.selectedRowNumbers()) {
                    var c = committedByRow.get(r);
                    results.add(new ImportedRowResult(r, c.productId(), ImportedRowResult.Status.SUCCESS, "Previously imported"));
                }
                return new ImportResult(
                        request.operationId(), shopId, results.size(), 0, 0, results, true);
            }
        } else {
            // First time this operation is executed: record operation header
            repository.recordOperation(request.operationId(), shopId, requestIdentity, request.selectedRowNumbers().size());
        }

        // 2. Perform per-row atomic imports for remaining uncommitted selected rows
        Map<Integer, ParsedRow> rowMap = new HashMap<>();
        for (ParsedRow row : reviewedRows) {
            rowMap.put(row.rowNumber(), row);
        }

        List<ProductImportRepository.ImportedRowRecord> alreadyCommitted = repository.listImportedRows(request.operationId());
        Set<Integer> alreadyCommittedRows = new HashSet<>();
        Map<Integer, UUID> alreadyCommittedIds = new HashMap<>();
        for (var ac : alreadyCommitted) {
            alreadyCommittedRows.add(ac.rowNumber());
            alreadyCommittedIds.put(ac.rowNumber(), ac.productId());
        }

        List<ImportedRowResult> rowResults = new ArrayList<>();
        int importedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (int rowNum : request.selectedRowNumbers()) {
            if (alreadyCommittedRows.contains(rowNum)) {
                rowResults.add(new ImportedRowResult(
                        rowNum, alreadyCommittedIds.get(rowNum), ImportedRowResult.Status.SUCCESS, "Already imported in earlier attempt"));
                importedCount++;
                continue;
            }

            ParsedRow row = rowMap.get(rowNum);
            if (row == null) {
                rowResults.add(new ImportedRowResult(
                        rowNum, null, ImportedRowResult.Status.FAILED, "Row " + rowNum + " not found in reviewed preview."));
                failedCount++;
                continue;
            }

            if (!row.isValid()) {
                rowResults.add(new ImportedRowResult(
                        rowNum, null, ImportedRowResult.Status.FAILED, "Row " + rowNum + " has validation errors: " + row.errors()));
                failedCount++;
                continue;
            }

            if (!request.allowDuplicates() && row.hasWarnings()) {
                rowResults.add(new ImportedRowResult(rowNum,null,ImportedRowResult.Status.SKIPPED,"Duplicate candidate requires explicit confirmation"));
                skippedCount++;
                continue;
            }
            Instant now = Instant.now();
            Product toCreate = new Product(
                    UUID.randomUUID(), shopId, row.name(), row.sellingPrice(), now, now, 1,
                    row.modelReference(), row.buyingPrice(), row.category(), row.unit(),
                    Map.of(), null, null);

            try {
                Product saved = repository.insertImportedProduct(
                        request.operationId(), row.rowNumber(), row.rowHash(), toCreate, request.allowDuplicates());
                rowResults.add(new ImportedRowResult(
                        rowNum, saved.id(), ImportedRowResult.Status.SUCCESS, "Imported with reference " + saved.reference()));
                importedCount++;
            } catch (DuplicateImportException duplicate) {
                rowResults.add(new ImportedRowResult(rowNum,null,ImportedRowResult.Status.SKIPPED,"Duplicate candidate requires explicit confirmation"));
                skippedCount++;
            } catch (SQLException ex) {
                rowResults.add(new ImportedRowResult(
                        rowNum, null, ImportedRowResult.Status.FAILED, "Import could not be confirmed. Retry the same operation."));
                failedCount++;
            }
        }

        return new ImportResult(
                request.operationId(), shopId, importedCount, skippedCount, failedCount, rowResults, false);
    }

    private static String getColValue(List<String> fields, Integer colIndex) {
        if (colIndex == null || colIndex < 0 || colIndex >= fields.size()) {
            return null;
        }
        return fields.get(colIndex);
    }

    private static long parsePrice(String text) {
        if (text.contains(".") || text.contains(",")) {
            throw new NumberFormatException("Fractional prices are not supported: " + text);
        }
        return Long.parseLong(text);
    }

}

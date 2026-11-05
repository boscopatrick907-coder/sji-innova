package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.importer.ProductImportRepository;
import cm.sji.innova.core.product.importer.DuplicateImportException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed implementation of ProductImportRepository.
 */
public final class SqliteProductImportRepository implements ProductImportRepository {
    private final SqliteDatabase database;

    public SqliteProductImportRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public boolean wasPreviouslyImported(UUID shopId, String rowHash) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement(
                     "SELECT 1 FROM product_import_rows WHERE shop_id = ? AND row_hash = ? LIMIT 1")) {
            s.setString(1, shopId.toString());
            s.setString(2, rowHash);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public List<ExistingProductSummary> listExistingProducts(UUID shopId) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, name, model_reference, reference FROM products WHERE shop_id = ?")) {
            s.setString(1, shopId.toString());
            try (ResultSet rs = s.executeQuery()) {
                List<ExistingProductSummary> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new ExistingProductSummary(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("name"),
                            rs.getString("model_reference"),
                            rs.getString("reference")));
                }
                return list;
            }
        }
    }

    @Override
    public Optional<ImportOperationRecord> findOperation(UUID operationId) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, shop_id, content_hash, total_selected, created_at FROM product_import_operations WHERE id = ?")) {
            s.setString(1, operationId.toString());
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ImportOperationRecord(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("shop_id")),
                            rs.getString("content_hash"),
                            rs.getInt("total_selected"),
                            Instant.parse(rs.getString("created_at"))));
                }
                return Optional.empty();
            }
        }
    }

    @Override
    public void recordOperation(UUID operationId, UUID shopId, String contentHash, int totalSelected) throws SQLException {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                try(var insert=c.prepareStatement("""
                        INSERT INTO product_import_operations(id,shop_id,content_hash,total_selected,created_at)
                        VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING
                        """)) {
                    insert.setString(1,operationId.toString());insert.setString(2,shopId.toString());
                    insert.setString(3,contentHash);insert.setInt(4,totalSelected);insert.setString(5,Instant.now().toString());insert.executeUpdate();
                }
                try(var check=c.prepareStatement("SELECT shop_id,content_hash,total_selected FROM product_import_operations WHERE id=?")) {
                    check.setString(1,operationId.toString());
                    try(var row=check.executeQuery()) {
                        if(!row.next() || !shopId.toString().equals(row.getString(1))
                                || !contentHash.equals(row.getString(2)) || totalSelected!=row.getInt(3))
                            throw new IllegalArgumentException("Operation ID was already used with different shop, content, selection or duplicate consent");
                    }
                }
                c.commit();
            }catch(SQLException|RuntimeException failure){
                try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;
            }
        }
    }

    @Override
    public List<ImportedRowRecord> listImportedRows(UUID operationId) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement("""
                     SELECT operation_id, row_number, product_id, row_hash, created_at
                     FROM product_import_rows
                     WHERE operation_id = ?
                     ORDER BY row_number
                     """)) {
            s.setString(1, operationId.toString());
            try (ResultSet rs = s.executeQuery()) {
                List<ImportedRowRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new ImportedRowRecord(
                            UUID.fromString(rs.getString("operation_id")),
                            rs.getInt("row_number"),
                            UUID.fromString(rs.getString("product_id")),
                            rs.getString("row_hash"),
                            Instant.parse(rs.getString("created_at"))));
                }
                return list;
            }
        }
    }

    @Override
    public Product insertImportedProduct(UUID operationId, int rowNumber, String rowHash, Product product) throws SQLException {
        return insertImportedProduct(operationId,rowNumber,rowHash,product,false);
    }

    @Override
    public Product insertImportedProduct(UUID operationId, int rowNumber, String rowHash, Product product,
                                         boolean allowDuplicates) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                // Acquire the SQLite write lock before checking replay and duplicate candidates.
                try(var lock=connection.prepareStatement("UPDATE product_import_operations SET total_selected=total_selected WHERE id=? AND shop_id=?")) {
                    lock.setString(1,operationId.toString());lock.setString(2,product.shopId().toString());
                    if(lock.executeUpdate()!=1)throw new IllegalArgumentException("Import operation not found in this shop");
                }
                try(var replay=connection.prepareStatement("""
                        SELECT p.*,r.row_hash FROM product_import_rows r JOIN products p ON p.id=r.product_id
                        WHERE r.operation_id=? AND r.row_number=? AND r.shop_id=?
                        """)) {
                    replay.setString(1,operationId.toString());replay.setInt(2,rowNumber);replay.setString(3,product.shopId().toString());
                    try(var row=replay.executeQuery()) {
                        if(row.next()) {
                            if(!rowHash.equals(row.getString("row_hash")))throw new IllegalArgumentException("Imported row content changed");
                            long cost=row.getLong("cost_price");Long buying=row.wasNull()?null:cost;
                            Product saved=new Product(UUID.fromString(row.getString("id")),product.shopId(),row.getString("name"),
                                    row.getLong("unit_price"),Instant.parse(row.getString("created_at")),Instant.parse(row.getString("updated_at")),
                                    row.getLong("version"),row.getString("model_reference"),buying,row.getString("category"),row.getString("unit"),
                                    java.util.Map.of(),row.getString("family_id")==null?null:UUID.fromString(row.getString("family_id")),row.getString("reference"));
                            connection.commit();return saved;
                        }
                    }
                }
                if(!allowDuplicates) {
                    try(var candidates=connection.prepareStatement("SELECT name,model_reference FROM products WHERE shop_id=?")) {
                        candidates.setString(1,product.shopId().toString());
                        try(var rows=candidates.executeQuery()) {
                            while(rows.next()) {
                                String reference=rows.getString(2);
                                if(rows.getString(1).strip().equalsIgnoreCase(product.name()) ||
                                        (product.modelReference()!=null && reference!=null && reference.strip().equalsIgnoreCase(product.modelReference())))
                                    throw new DuplicateImportException();
                            }
                        }
                    }
                    try(var prior=connection.prepareStatement("SELECT 1 FROM product_import_rows WHERE shop_id=? AND row_hash=? LIMIT 1")) {
                        prior.setString(1,product.shopId().toString());prior.setString(2,rowHash);
                        try(var rows=prior.executeQuery()){if(rows.next())throw new DuplicateImportException();}
                    }
                }
                long next;
                try (var sequence = connection.prepareStatement("""
                        INSERT INTO product_reference_sequences(shop_id,last_value) VALUES (?,1)
                        ON CONFLICT(shop_id) DO UPDATE SET last_value=last_value+1
                        RETURNING last_value
                        """)) {
                    sequence.setString(1, product.shopId().toString());
                    try (var rows = sequence.executeQuery()) {
                        rows.next();
                        next = rows.getLong(1);
                    }
                }
                String reference = "P-" + String.format(Locale.ROOT, "%04d", next);

                try (var statement = connection.prepareStatement("""
                        INSERT INTO products (id, shop_id, name, unit_price, created_at, updated_at,
                                              version, model_reference, cost_price, category, unit, family_id, reference)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, product.id().toString());
                    statement.setString(2, product.shopId().toString());
                    statement.setString(3, product.name());
                    statement.setLong(4, product.unitPrice());
                    statement.setString(5, product.createdAt().toString());
                    statement.setString(6, product.updatedAt().toString());
                    statement.setLong(7, product.version());
                    statement.setString(8, product.modelReference());
                    if (product.costPrice() == null) {
                        statement.setNull(9, Types.BIGINT);
                    } else {
                        statement.setLong(9, product.costPrice());
                    }
                    statement.setString(10, product.category());
                    statement.setString(11, product.unit());
                    statement.setString(12, product.familyId() == null ? null : product.familyId().toString());
                    statement.setString(13, reference);
                    statement.executeUpdate();
                }

                try (var statement = connection.prepareStatement("""
                        INSERT INTO product_import_rows (operation_id, row_number, shop_id, product_id, row_hash, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, operationId.toString());
                    statement.setInt(2, rowNumber);
                    statement.setString(3, product.shopId().toString());
                    statement.setString(4, product.id().toString());
                    statement.setString(5, rowHash);
                    statement.setString(6, product.createdAt().toString());
                    statement.executeUpdate();
                }

                Product saved = new Product(
                        product.id(), product.shopId(), product.name(), product.unitPrice(),
                        product.createdAt(), product.updatedAt(), product.version(), product.modelReference(),
                        product.costPrice(), product.category(), product.unit(), product.specifications(),
                        product.familyId(), reference);

                connection.commit();
                return saved;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }
}

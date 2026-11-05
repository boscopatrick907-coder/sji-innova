package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.ProductFamily;
import cm.sji.innova.core.product.ProductFamilyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SqliteProductFamilyRepository implements ProductFamilyRepository {
    private final SqliteDatabase database;
    public SqliteProductFamilyRepository(SqliteDatabase database) { this.database = Objects.requireNonNull(database); }
    @Override public void insert(ProductFamily family) throws SQLException {
        try (var c = database.open(); var s = c.prepareStatement(
                "INSERT INTO product_families(id,shop_id,name,category,created_at) VALUES(?,?,?,?,?)")) {
            s.setString(1, family.id().toString()); s.setString(2, family.shopId().toString());
            s.setString(3, family.name()); s.setString(4, family.category());
            s.setString(5, family.createdAt().toString()); s.executeUpdate();
        }
    }
    @Override public List<ProductFamily> findByShop(UUID shopId) throws SQLException {
        Objects.requireNonNull(shopId);
        try (var c = database.open(); var s = c.prepareStatement(
                "SELECT * FROM product_families WHERE shop_id=? ORDER BY name,id")) {
            s.setString(1, shopId.toString());
            try (var rows = s.executeQuery()) {
                List<ProductFamily> result = new ArrayList<>();
                while (rows.next()) result.add(new ProductFamily(UUID.fromString(rows.getString("id")), shopId,
                        rows.getString("name"), rows.getString("category"), Instant.parse(rows.getString("created_at"))));
                return List.copyOf(result);
            }
        }
    }
    @Override public void assign(UUID shopId, UUID productId, UUID familyId) throws SQLException {
        Objects.requireNonNull(shopId); Objects.requireNonNull(productId); Objects.requireNonNull(familyId);
        try (var c = database.open(); var s = c.prepareStatement("""
                UPDATE products SET family_id=?,version=version+1,updated_at=?
                WHERE shop_id=? AND id=? AND (family_id IS NULL OR family_id<>?)
                  AND EXISTS(SELECT 1 FROM product_families WHERE shop_id=? AND id=?)
                """)) {
            s.setString(1,familyId.toString()); s.setString(2,Instant.now().toString());
            s.setString(3,shopId.toString()); s.setString(4,productId.toString());
            s.setString(5,familyId.toString()); s.setString(6,shopId.toString()); s.setString(7,familyId.toString());
            if (s.executeUpdate() == 0) {
                try (var check = c.prepareStatement("SELECT 1 FROM products WHERE shop_id=? AND id=? AND family_id=?")) {
                    check.setString(1,shopId.toString()); check.setString(2,productId.toString()); check.setString(3,familyId.toString());
                    try (var row = check.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Product or family not found in this shop");
                    }
                }
            }
        }
    }
}

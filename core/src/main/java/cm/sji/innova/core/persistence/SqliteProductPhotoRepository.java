package cm.sji.innova.core.persistence;

import cm.sji.innova.core.product.photo.ProductPhoto;
import cm.sji.innova.core.product.photo.ProductPhotoRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed repository for product photos.
 */
public final class SqliteProductPhotoRepository implements ProductPhotoRepository {
    private final SqliteDatabase database;

    public SqliteProductPhotoRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public boolean productExists(UUID shopId, UUID productId) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement(
                     "SELECT 1 FROM products WHERE shop_id = ? AND id = ?")) {
            s.setString(1, shopId.toString());
            s.setString(2, productId.toString());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Optional<ProductPhoto> findPhoto(UUID shopId, UUID productId) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement("""
                     SELECT shop_id, product_id, relative_path, thumbnail_path, content_type,
                            file_size, width, height, created_at, updated_at
                     FROM product_photos
                     WHERE shop_id = ? AND product_id = ?
                     """)) {
            s.setString(1, shopId.toString());
            s.setString(2, productId.toString());
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    @Override
    public void savePhoto(ProductPhoto photo) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO product_photos (
                         shop_id, product_id, relative_path, thumbnail_path, content_type,
                         file_size, width, height, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(product_id) DO UPDATE SET
                         shop_id = excluded.shop_id,
                         relative_path = excluded.relative_path,
                         thumbnail_path = excluded.thumbnail_path,
                         content_type = excluded.content_type,
                         file_size = excluded.file_size,
                         width = excluded.width,
                         height = excluded.height,
                         updated_at = excluded.updated_at
                     """)) {
            s.setString(1, photo.shopId().toString());
            s.setString(2, photo.productId().toString());
            s.setString(3, photo.relativePath());
            s.setString(4, photo.thumbnailPath());
            s.setString(5, photo.contentType());
            s.setLong(6, photo.fileSize());
            s.setInt(7, photo.width());
            s.setInt(8, photo.height());
            s.setString(9, photo.createdAt().toString());
            s.setString(10, photo.updatedAt().toString());
            s.executeUpdate();
        }
    }

    @Override
    public Optional<ProductPhoto> deletePhoto(UUID shopId, UUID productId) throws SQLException {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                Optional<ProductPhoto> existing;
                try (PreparedStatement select = c.prepareStatement("""
                        SELECT shop_id, product_id, relative_path, thumbnail_path, content_type,
                               file_size, width, height, created_at, updated_at
                        FROM product_photos
                        WHERE shop_id = ? AND product_id = ?
                        """)) {
                    select.setString(1, shopId.toString());
                    select.setString(2, productId.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        existing = rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                    }
                }

                if (existing.isPresent()) {
                    try (PreparedStatement delete = c.prepareStatement(
                            "DELETE FROM product_photos WHERE shop_id = ? AND product_id = ?")) {
                        delete.setString(1, shopId.toString());
                        delete.setString(2, productId.toString());
                        delete.executeUpdate();
                    }
                }

                c.commit();
                return existing;
            } catch (SQLException failure) {
                try { c.rollback(); }
                catch (SQLException rb) { failure.addSuppressed(rb); }
                throw failure;
            }
        }
    }

    @Override
    public List<ProductPhoto> listPhotos(UUID shopId) throws SQLException {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement("""
                     SELECT shop_id, product_id, relative_path, thumbnail_path, content_type,
                            file_size, width, height, created_at, updated_at
                     FROM product_photos
                     WHERE shop_id = ?
                     ORDER BY product_id
                     """)) {
            s.setString(1, shopId.toString());
            try (ResultSet rs = s.executeQuery()) {
                List<ProductPhoto> photos = new ArrayList<>();
                while (rs.next()) {
                    photos.add(mapRow(rs));
                }
                return photos;
            }
        }
    }

    private static ProductPhoto mapRow(ResultSet rs) throws SQLException {
        return new ProductPhoto(
                UUID.fromString(rs.getString("shop_id")),
                UUID.fromString(rs.getString("product_id")),
                rs.getString("relative_path"),
                rs.getString("thumbnail_path"),
                rs.getString("content_type"),
                rs.getLong("file_size"),
                rs.getInt("width"),
                rs.getInt("height"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}


package cm.sji.innova.core.product.photo;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for product photos.
 */
public interface ProductPhotoRepository {

    /** Checks whether the product exists and is owned by the specified shop. */
    boolean productExists(UUID shopId, UUID productId) throws SQLException;

    /** Finds the photo metadata record for a given product. */
    Optional<ProductPhoto> findPhoto(UUID shopId, UUID productId) throws SQLException;

    /** Stores or updates a product's photo metadata record. */
    void savePhoto(ProductPhoto photo) throws SQLException;

    /** Deletes the photo record for a product and returns the deleted record if one existed. */
    Optional<ProductPhoto> deletePhoto(UUID shopId, UUID productId) throws SQLException;

    /** Lists all photos for a shop. */
    List<ProductPhoto> listPhotos(UUID shopId) throws SQLException;
}

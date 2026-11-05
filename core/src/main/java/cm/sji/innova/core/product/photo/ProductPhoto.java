package cm.sji.innova.core.product.photo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata record for a product photo managed by Cella.
 *
 * <p>Relative paths are always relative to the configured Cella data directory,
 * using forward slashes (/) for portability across operating systems.
 */
public record ProductPhoto(
        UUID shopId,
        UUID productId,
        String relativePath,
        String thumbnailPath,
        String contentType,
        long fileSize,
        int width,
        int height,
        Instant createdAt,
        Instant updatedAt) {

    public ProductPhoto {
        Objects.requireNonNull(shopId, "shopId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Objects.requireNonNull(thumbnailPath, "thumbnailPath must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (relativePath.isBlank() || relativePath.contains("..")) {
            throw new IllegalArgumentException("Invalid relativePath: " + relativePath);
        }
        if (thumbnailPath.isBlank() || thumbnailPath.contains("..")) {
            throw new IllegalArgumentException("Invalid thumbnailPath: " + thumbnailPath);
        }
        if (!contentType.equals("image/jpeg") && !contentType.equals("image/png")) {
            throw new IllegalArgumentException(
                    "Unsupported contentType: " + contentType + ". Only image/jpeg and image/png are allowed.");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be positive: " + fileSize);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive: " + width + "x" + height);
        }
    }
}


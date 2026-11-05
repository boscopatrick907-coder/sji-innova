package cm.sji.innova.core.product.photo;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome when loading a product photo from Cella storage.
 *
 * <p>Patrick's UI can switch over these types to show placeholders,
 * render loaded images, or display non-fatal warnings if files were moved.
 */
public sealed interface PhotoLoadResult {

    /** The product exists, but no photo has been attached to it. */
    record NotAttached(UUID shopId, UUID productId) implements PhotoLoadResult {
        public NotAttached {
            Objects.requireNonNull(shopId, "shopId must not be null");
            Objects.requireNonNull(productId, "productId must not be null");
        }
    }

    /** The photo is attached and verified to exist and decode on disk. */
    record Loaded(ProductPhoto photo, Path imageFile, Path thumbnailFile) implements PhotoLoadResult {
        public Loaded {
            Objects.requireNonNull(photo, "photo must not be null");
            Objects.requireNonNull(imageFile, "imageFile must not be null");
            Objects.requireNonNull(thumbnailFile, "thumbnailFile must not be null");
        }
    }

    /** A photo is recorded in the database, but its managed file is missing on disk. */
    record MissingFile(ProductPhoto photo, Path expectedPath, String message) implements PhotoLoadResult {
        public MissingFile {
            Objects.requireNonNull(photo, "photo must not be null");
            Objects.requireNonNull(expectedPath, "expectedPath must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    /** A photo file exists on disk, but its content cannot be decoded or is damaged. */
    record CorruptFile(ProductPhoto photo, Path file, String message) implements PhotoLoadResult {
        public CorruptFile {
            Objects.requireNonNull(photo, "photo must not be null");
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }
}


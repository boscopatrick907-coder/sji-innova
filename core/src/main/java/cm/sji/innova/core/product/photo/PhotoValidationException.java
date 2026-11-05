package cm.sji.innova.core.product.photo;

/**
 * Thrown when an image file fails validation (e.g. invalid format,
 * resolution over 20 megapixels, file size over 5 MiB, or corrupted headers).
 */
public class PhotoValidationException extends RuntimeException {
    public PhotoValidationException(String message) {
        super(message);
    }

    public PhotoValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}


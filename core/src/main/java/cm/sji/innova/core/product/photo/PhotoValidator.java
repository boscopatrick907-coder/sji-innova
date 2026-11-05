package cm.sji.innova.core.product.photo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Validates, decodes, and normalizes product photos according to Cella storage limits.
 *
 * <p>Limits:
 * <ul>
 *   <li>Maximum file size: 5 MiB (5,242,880 bytes)</li>
 *   <li>Maximum resolution: 20 megapixels (20,000,000 pixels)</li>
 *   <li>Supported formats: JPEG (image/jpeg) and PNG (image/png)</li>
 * </ul>
 *
 * <p>Resolution is inspected using image headers before full raster memory is allocated.
 */
public final class PhotoValidator {

    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L; // 5 MiB
    public static final long MAX_PIXELS = 20_000_000L; // 20 Megapixels
    public static final int THUMBNAIL_MAX_DIMENSION = 256;

    public record ValidatedImage(
            String formatName,
            String contentType,
            String fileExtension,
            int width,
            int height,
            BufferedImage normalizedImage,
            BufferedImage thumbnailImage) {}

    private PhotoValidator() { }

    public static ValidatedImage validateAndProcess(Path sourceFile) throws IOException {
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw new PhotoValidationException("Image source file does not exist or is not a regular file: " + sourceFile);
        }

        long fileSize = Files.size(sourceFile);
        if (fileSize <= 0) {
            throw new PhotoValidationException("Image file is empty (0 bytes).");
        }
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new PhotoValidationException(
                    "Image file size (" + fileSize + " bytes) exceeds the maximum allowed limit of 5 MiB.");
        }

        // Validate magic bytes to reject disguised non-image files or unsupported formats (e.g. SVG, scripts, executables)
        String format = detectMagicFormat(sourceFile);
        if (format == null) {
            throw new PhotoValidationException("Unsupported image content. Only JPEG and PNG files are allowed.");
        }

        // Probe dimensions from image header without loading entire pixel raster
        int width;
        int height;
        BufferedImage fullRaster;
        try (ImageInputStream in = ImageIO.createImageInputStream(sourceFile.toFile())) {
            if (in == null) {
                throw new PhotoValidationException("Failed to read image stream from: " + sourceFile);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new PhotoValidationException("No decoder found for image format: " + format);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);

                if (width <= 0 || height <= 0) {
                    throw new PhotoValidationException("Invalid image dimensions: " + width + "x" + height);
                }

                long totalPixels = (long) width * (long) height;
                if (totalPixels > MAX_PIXELS) {
                    throw new PhotoValidationException("Image resolution (" + width + "x" + height + " = "
                            + totalPixels + " pixels) exceeds the maximum allowed limit of 20 megapixels.");
                }

                fullRaster = reader.read(0);
            } catch (PhotoValidationException pve) {
                throw pve;
            } catch (Exception ex) {
                throw new PhotoValidationException("Failed to decode image header or content: " + ex.getMessage(), ex);
            } finally {
                reader.dispose();
            }
        }

        if (fullRaster == null) {
            throw new PhotoValidationException("Could not decode image raster from: " + sourceFile);
        }

        String contentType;
        String extension;
        BufferedImage normalized;
        BufferedImage thumbnail;

        if ("jpeg".equals(format)) {
            contentType = "image/jpeg";
            extension = "jpg";
            normalized = normalizeRgb(fullRaster);
            thumbnail = createScaledImage(normalized, THUMBNAIL_MAX_DIMENSION, false);
        } else {
            contentType = "image/png";
            extension = "png";
            boolean hasAlpha = fullRaster.getColorModel().hasAlpha();
            normalized = normalizePng(fullRaster, hasAlpha);
            thumbnail = createScaledImage(normalized, THUMBNAIL_MAX_DIMENSION, hasAlpha);
        }

        return new ValidatedImage(format, contentType, extension, width, height, normalized, thumbnail);
    }

    private static String detectMagicFormat(Path file) throws IOException {
        byte[] magic = new byte[8];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(magic, 0, magic.length);
            if (read < 3) {
                return null;
            }
            // JPEG: FF D8 FF
            if ((magic[0] & 0xFF) == 0xFF && (magic[1] & 0xFF) == 0xD8 && (magic[2] & 0xFF) == 0xFF) {
                return "jpeg";
            }
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (read >= 8 && (magic[0] & 0xFF) == 0x89
                    && magic[1] == 0x50 && magic[2] == 0x4E && magic[3] == 0x47
                    && magic[4] == 0x0D && magic[5] == 0x0A && magic[6] == 0x1A && magic[7] == 0x0A) {
                return "png";
            }
        }
        return null;
    }

    private static BufferedImage normalizeRgb(BufferedImage src) {
        BufferedImage target = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static BufferedImage normalizePng(BufferedImage src, boolean hasAlpha) {
        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(src.getWidth(), src.getHeight(), type);
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static BufferedImage createScaledImage(BufferedImage src, int maxDimension, boolean hasAlpha) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= maxDimension && srcH <= maxDimension) {
            return src;
        }

        double scale = Math.min((double) maxDimension / srcW, (double) maxDimension / srcH);
        int targetW = Math.max(1, (int) Math.round(srcW * scale));
        int targetH = Math.max(1, (int) Math.round(srcH * scale));

        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetW, targetH, type);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}

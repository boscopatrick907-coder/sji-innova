package cm.sji.innova.core.product.photo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Service managing persistent, local product photos.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>Original source files are never mutated; Cella copies images into its managed folder.</li>
 *   <li>Deleting or moving the original source file never affects Cella's copy.</li>
 *   <li>Stored references are always relative to the data directory for portability.</li>
 *   <li>Staged publication: files are staged and published first; on database failure, newly staged
 *       files are cleaned up and the previous photo remains intact.</li>
 *   <li>Old files are only cleaned up AFTER successful database commit.</li>
 * </ul>
 */
public final class ProductPhotoService {

    private final ProductPhotoRepository repository;
    private final Path dataDirectory;
    private final Path photosDirectory;
    private final Path stagingDirectory;

    public ProductPhotoService(ProductPhotoRepository repository, Path dataDirectory) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory must not be null")
                .toAbsolutePath().normalize();
        this.photosDirectory = this.dataDirectory.resolve("photos");
        this.stagingDirectory = this.photosDirectory.resolve(".staging");
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path photosDirectory() {
        return photosDirectory;
    }

    public Path resolvePath(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        if (relativePath.contains("..")) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        Path resolved = dataDirectory.resolve(relativePath.replace('/', File.separatorChar)).normalize();
        if (!resolved.startsWith(dataDirectory)) {
            throw new SecurityException("Path resolved outside data directory: " + relativePath);
        }
        return resolved;
    }

    /**
     * Retrieves the metadata record of a product's photo if present.
     */
    public Optional<ProductPhoto> getPhotoMetadata(UUID shopId, UUID productId) throws SQLException {
        verifyProductOwnership(shopId, productId);
        return repository.findPhoto(shopId, productId);
    }

    /**
     * Loads and verifies a product photo from storage.
     *
     * <p>Returns {@link PhotoLoadResult} detailing whether the photo is absent, loaded,
     * missing on disk, or damaged.
     */
    public PhotoLoadResult loadPhoto(UUID shopId, UUID productId) throws SQLException {
        verifyProductOwnership(shopId, productId);
        Optional<ProductPhoto> photoOpt = repository.findPhoto(shopId, productId);
        if (photoOpt.isEmpty()) {
            return new PhotoLoadResult.NotAttached(shopId, productId);
        }

        ProductPhoto photo = photoOpt.get();
        Path mainFile = resolvePath(photo.relativePath());
        Path thumbFile = resolvePath(photo.thumbnailPath());

        if (!Files.exists(mainFile) || !Files.isRegularFile(mainFile)) {
            return new PhotoLoadResult.MissingFile(
                    photo, mainFile, "The photo file could not be found in Cella's data folder: " + mainFile);
        }
        if (!Files.exists(thumbFile) || !Files.isRegularFile(thumbFile)) {
            return new PhotoLoadResult.MissingFile(
                    photo, thumbFile, "The thumbnail file could not be found in Cella's data folder: " + thumbFile);
        }

        for (Path image : List.of(mainFile, thumbFile)) {
            try {
                PhotoValidator.verifyStoredImage(image);
            } catch (IOException | RuntimeException failure) {
                return new PhotoLoadResult.CorruptFile(photo, image,
                        "The stored image cannot be decoded: " + failure.getMessage());
            }
        }

        return new PhotoLoadResult.Loaded(photo, mainFile, thumbFile);
    }

    /**
     * Attaches or replaces a photo for a product from a local source file.
     *
     * <p>Two-phase staging ensures that if an error occurs at any point, the previous
     * photo is preserved intact and no corrupt files are referenced.
     */
    public ProductPhoto setPhoto(UUID shopId, UUID productId, Path sourceFile) throws IOException, SQLException {
        try (var lock = PhotoStoreLock.acquire(dataDirectory)) {
            return setPhotoLocked(shopId, productId, sourceFile);
        }
    }

    private ProductPhoto setPhotoLocked(UUID shopId, UUID productId, Path sourceFile) throws IOException, SQLException {
        verifyProductOwnership(shopId, productId);
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");

        // 1. Validate image format, size, and dimensions
        PhotoValidator.ValidatedImage validated = PhotoValidator.validateAndProcess(sourceFile);

        // 2. Stage normalized images into staging directory
        Files.createDirectories(stagingDirectory);
        UUID stageId = UUID.randomUUID();
        Path stagedMain = stagingDirectory.resolve(stageId + "_main." + validated.fileExtension());
        Path stagedThumb = stagingDirectory.resolve(stageId + "_thumb." + validated.fileExtension());

        try {
            ImageIO.write(validated.normalizedImage(), validated.formatName(), stagedMain.toFile());
            ImageIO.write(validated.thumbnailImage(), validated.formatName(), stagedThumb.toFile());

            // 3. Move staged files into managed shop directory
            Path shopDir = photosDirectory.resolve(shopId.toString());
            Files.createDirectories(shopDir);

            String token = UUID.randomUUID().toString();
            String mainFilename = productId + "_" + token + "." + validated.fileExtension();
            String thumbFilename = productId + "_" + token + "_thumb." + validated.fileExtension();

            Path publishedMain = shopDir.resolve(mainFilename);
            Path publishedThumb = shopDir.resolve(thumbFilename);

            Files.move(stagedMain, publishedMain, StandardCopyOption.ATOMIC_MOVE);
            Files.move(stagedThumb, publishedThumb, StandardCopyOption.ATOMIC_MOVE);

            long fileSize = Files.size(publishedMain);
            String relMain = "photos/" + shopId + "/" + mainFilename;
            String relThumb = "photos/" + shopId + "/" + thumbFilename;

            // 4. Retrieve existing photo metadata (to clean up old files after commit)
            Optional<ProductPhoto> existing = repository.findPhoto(shopId, productId);
            Instant now = Instant.now();
            Instant createdAt = existing.map(ProductPhoto::createdAt).orElse(now);

            ProductPhoto newPhoto = new ProductPhoto(
                    shopId, productId, relMain, relThumb, validated.contentType(),
                    fileSize, validated.width(), validated.height(), createdAt, now);

            // 5. Database commit
            try {
                repository.savePhoto(newPhoto);
            } catch (SQLException dbFailure) {
                // Auto-commit may have succeeded before a close/response error. Reconcile
                // under the store lock before deciding whether these files are unreferenced.
                Optional<ProductPhoto> committed;
                try {
                    committed = repository.findPhoto(shopId, productId);
                } catch (SQLException uncertain) {
                    dbFailure.addSuppressed(uncertain);
                    // Retain files when the outcome cannot be established. Safe cleanup can
                    // reclaim them later; deleting a committed image would lose user data.
                    throw dbFailure;
                }
                boolean saved = committed.filter(p -> p.relativePath().equals(relMain)
                        && p.thumbnailPath().equals(relThumb)).isPresent();
                if (!saved) {
                    try { Files.deleteIfExists(publishedMain); } catch (IOException cleanup) { dbFailure.addSuppressed(cleanup); }
                    try { Files.deleteIfExists(publishedThumb); } catch (IOException cleanup) { dbFailure.addSuppressed(cleanup); }
                    throw dbFailure;
                }
                newPhoto = committed.orElseThrow();
            }

            // 6. Post-commit cleanup: remove old photo files
            if (existing.isPresent()) {
                ProductPhoto old = existing.get();
                if (!old.relativePath().equals(relMain)) {
                    try { Files.deleteIfExists(resolvePath(old.relativePath())); } catch (IOException ignored) { }
                }
                if (!old.thumbnailPath().equals(relThumb)) {
                    try { Files.deleteIfExists(resolvePath(old.thumbnailPath())); } catch (IOException ignored) { }
                }
            }

            return newPhoto;
        } finally {
            // Clean up temporary staged files if any remain
            try { Files.deleteIfExists(stagedMain); } catch (IOException ignored) { }
            try { Files.deleteIfExists(stagedThumb); } catch (IOException ignored) { }
        }
    }

    /**
     * Attaches or replaces a photo from an input stream.
     */
    public ProductPhoto setPhoto(UUID shopId, UUID productId, InputStream inputStream, String filenameHint)
            throws IOException, SQLException {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        verifyProductOwnership(shopId, productId);
        // Hints are display text, never filesystem paths. Bound the stream while copying.
        Path tempFile = Files.createTempFile("cella_upload_", ".image");
        try {
            try (var output = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = inputStream.read(buffer)) != -1) {
                    total += count;
                    if (total > PhotoValidator.MAX_FILE_SIZE_BYTES) {
                        throw new PhotoValidationException("Image exceeds the maximum allowed limit of 5 MiB");
                    }
                    output.write(buffer, 0, count);
                }
            }
            return setPhoto(shopId, productId, tempFile);
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) { }
        }
    }

    /**
     * Removes the photo associated with a product.
     *
     * @return true if a photo was removed, false if no photo was attached.
     */
    public boolean removePhoto(UUID shopId, UUID productId) throws SQLException, IOException {
        try (var lock = PhotoStoreLock.acquire(dataDirectory)) {
            return removePhotoLocked(shopId, productId);
        }
    }

    private boolean removePhotoLocked(UUID shopId, UUID productId) throws SQLException, IOException {
        verifyProductOwnership(shopId, productId);
        Optional<ProductPhoto> deleted = repository.deletePhoto(shopId, productId);
        if (deleted.isEmpty()) {
            return false;
        }

        ProductPhoto old = deleted.get();
        try {
            Files.deleteIfExists(resolvePath(old.relativePath()));
        } catch (IOException ignored) { }
        try {
            Files.deleteIfExists(resolvePath(old.thumbnailPath()));
        } catch (IOException ignored) { }

        return true;
    }

    /**
     * Performs maintenance by removing orphaned files in staging and unreferenced files in the shop folder.
     */
    public OrphanCleanupReport cleanupOrphans(UUID shopId) throws SQLException, IOException {
        Objects.requireNonNull(shopId, "shopId");
        try (var lock = PhotoStoreLock.acquire(dataDirectory)) {
            return cleanupOrphansLocked(shopId);
        }
    }

    private OrphanCleanupReport cleanupOrphansLocked(UUID shopId) throws SQLException, IOException {
        int stagingCleaned = 0;
        int unreferencedCleaned = 0;
        List<String> cleanedPaths = new ArrayList<>();

        // 1. Clean staging directory
        if (Files.exists(stagingDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) {
                        Files.deleteIfExists(p);
                        stagingCleaned++;
                        cleanedPaths.add(p.toString());
                    }
                }
            }
        }

        // 2. Clean unreferenced files in shop directory
        Path shopDir = photosDirectory.resolve(shopId.toString());
        if (Files.exists(shopDir)) {
            List<ProductPhoto> currentPhotos = repository.listPhotos(shopId);
            Set<Path> activePaths = new HashSet<>();
            for (ProductPhoto photo : currentPhotos) {
                activePaths.add(resolvePath(photo.relativePath()));
                activePaths.add(resolvePath(photo.thumbnailPath()));
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(shopDir)) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p) && !activePaths.contains(p.normalize())) {
                        Files.deleteIfExists(p);
                        unreferencedCleaned++;
                        cleanedPaths.add(p.toString());
                    }
                }
            }
        }

        return new OrphanCleanupReport(stagingCleaned, unreferencedCleaned, cleanedPaths);
    }

    private void verifyProductOwnership(UUID shopId, UUID productId) throws SQLException {
        Objects.requireNonNull(shopId, "shopId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (!repository.productExists(shopId, productId)) {
            throw new IllegalArgumentException("Product " + productId + " does not belong to shop " + shopId);
        }
    }
}

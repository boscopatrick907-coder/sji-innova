package cm.sji.innova.core.product.photo;

import java.util.List;

/**
 * Report detailing the outcome of an orphan/staging cleanup pass.
 */
public record OrphanCleanupReport(
        int stagingFilesDeleted,
        int unreferencedFilesDeleted,
        List<String> deletedPaths) {

    public OrphanCleanupReport {
        deletedPaths = List.copyOf(deletedPaths);
    }
}


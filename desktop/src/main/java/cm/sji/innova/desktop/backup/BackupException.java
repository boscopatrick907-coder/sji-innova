package cm.sji.innova.desktop.backup;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Why a backup did not start or did not finish, or why an archive cannot be used. No file with a backup's
 * final name, and no success record, is left behind in any of these cases. A folder that is busy or held
 * by another Cella is reported by the coordinator's own {@code FolderMaintenanceException}.
 */
public final class BackupException extends IOException {
    public enum Reason {
        /** A receipt, correction, cash sale or import is saved and waiting to be retried. */
        REQUEST_PENDING,
        /** A set-aside file has no decision covering its exact bytes yet. */
        EVIDENCE_UNRESOLVED,
        /** A photo the database refers to is missing or unreadable. */
        PHOTO_CHANGED,
        /** The destination is inside the shop's data folder, or cannot be used. */
        DESTINATION_REFUSED,
        /** The snapshot, or the archive read back, failed a check. */
        CHECK_FAILED
    }

    private final Reason reason;
    private final List<String> details;

    public BackupException(Reason reason, List<String> details, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.details = List.copyOf(details);
    }

    public Reason reason() { return reason; }

    /** The files concerned: which saved requests, set-aside files or photos, or which archive. */
    public List<String> details() { return details; }
}

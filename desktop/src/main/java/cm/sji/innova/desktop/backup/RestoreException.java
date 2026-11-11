package cm.sji.innova.desktop.backup;

import java.io.IOException;
import java.util.Objects;

/**
 * Why a restore could not be prepared or scheduled. The shop itself is never changed in any of these
 * cases. A backup file that cannot be used is reported by {@link BackupArchive} as a
 * {@link BackupException} with {@code CHECK_FAILED}.
 */
public final class RestoreException extends IOException {
    public enum Reason {
        /** A restore is already scheduled for the next start. */
        ALREADY_SCHEDULED,
        /** The backup comes from an older Cella and could not be brought up to date. */
        UPGRADE_FAILED,
        /** The unpacked, updated copy failed its final check. */
        STAGED_CHECK_FAILED,
        /** The prepared files changed or disappeared between preparing and scheduling. */
        STAGING_CHANGED,
        /** The note that schedules a restore cannot be read or does not belong to this folder. */
        NOTE_DAMAGED,
        /** This data folder cannot record a restore safely. */
        FOLDER_UNSUPPORTED
    }

    private final Reason reason;

    public RestoreException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}

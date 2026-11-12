package cm.sji.innova.desktop.backup;

import java.io.IOException;
import java.util.Objects;

/**
 * Why a restore could not be prepared, scheduled or run. Before a restore's journal exists, the shop is
 * never changed in any of these cases. A backup file that cannot be used is reported by {@link BackupArchive}
 * as a {@link BackupException} with {@code CHECK_FAILED}.
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
        FOLDER_UNSUPPORTED,
        /** The journal of a restore in progress cannot be read, or does not belong to this folder (OFF-03b2). */
        JOURNAL_DAMAGED,
        /**
         * Neither the old shop nor the new one can be proved whole after an interrupted restore. Nothing more
         * is moved and every file is kept; Cella must not open the shop until someone repairs it (OFF-03b2).
         */
        NEEDS_REPAIR
    }

    private final Reason reason;

    public RestoreException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}

package cm.sji.innova.desktop.shell;

import java.io.IOException;
import java.util.Objects;

/** Why Cella could not open a shop folder, or could not start a backup or restore on it. Nothing was changed. */
public final class FolderMaintenanceException extends IOException {
    public enum Reason {
        /** Another Cella is making a backup or restore of this shop, so this one cannot open it now. */
        IN_MAINTENANCE,
        /** Another Cella has this shop open, so a backup or restore cannot have the folder to itself. */
        OTHER_INSTANCE,
        /** This Cella is still saving something and did not finish in time. */
        STILL_WORKING,
        /** A backup or restore is already running in this Cella. */
        ALREADY_RUNNING,
        /** This computer's file system cannot share the folder lock, so Cella will not risk it. */
        UNSUPPORTED
    }

    private final Reason reason;

    public FolderMaintenanceException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}

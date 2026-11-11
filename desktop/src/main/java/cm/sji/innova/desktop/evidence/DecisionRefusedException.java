package cm.sji.innova.desktop.evidence;

import java.io.IOException;
import java.util.Objects;

/** Why a decision was not recorded. Nothing was written. */
public final class DecisionRefusedException extends IOException {
    public enum Reason {
        /** The name is not a set-aside file in this data folder. */
        NOT_EVIDENCE,
        /** The file is not the one the person looked at: its fingerprint changed. */
        EVIDENCE_CHANGED,
        /** A decision already covers these exact bytes; decisions are never replaced. */
        ALREADY_DECIDED
    }

    private final Reason reason;

    public DecisionRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}

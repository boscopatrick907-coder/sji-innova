package cm.sji.innova.desktop.evidence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a person decided about one set-aside file. It covers only the exact bytes it names by their
 * SHA-256: if the file changes, it needs a new decision. Written once, never changed.
 *
 * @param operationId the matching operation, when the outcome is {@link Outcome#ALREADY_RECORDED}; otherwise
 *                    the new operation if one is known, or {@code null}
 */
public record EvidenceDecision(String evidenceFile, String evidenceSha256, String decidedBy, Instant decidedAt,
                               Outcome outcome, UUID operationId, String reason) {
    public static final int MAX_NAME = 120;
    public static final int MAX_REASON = 1000;

    /** What happened to the request in the set-aside file, as the person established it. */
    public enum Outcome {
        /** The operation is already in Cella's records (give its ID). */
        ALREADY_RECORDED,
        /** Someone entered the operation again by hand. */
        ENTERED_AGAIN,
        /** The operation did not really happen, so nothing is to be entered. */
        DID_NOT_HAPPEN,
        /** Something else: the reason says what. */
        OTHER
    }

    public EvidenceDecision {
        Objects.requireNonNull(evidenceFile, "evidenceFile");
        Objects.requireNonNull(evidenceSha256, "evidenceSha256");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(outcome, "outcome");
        decidedBy = required(decidedBy, MAX_NAME, "Who decided");
        reason = required(reason, MAX_REASON, "The reason");
        if (outcome == Outcome.ALREADY_RECORDED && operationId == null) {
            throw new IllegalArgumentException("Already recorded: give the ID of the matching operation");
        }
    }

    private static String required(String text, int max, String what) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(what + " is required");
        String trimmed = text.strip();
        if (trimmed.length() > max) throw new IllegalArgumentException(what + " is too long (" + max + " characters at most)");
        return trimmed;
    }
}

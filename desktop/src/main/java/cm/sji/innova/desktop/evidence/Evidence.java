package cm.sji.innova.desktop.evidence;

import java.time.Instant;
import java.util.UUID;

/**
 * One set-aside file, as found in the data folder: what it is, its fingerprint, what can be read from it,
 * a hint about its operation, and whether a decision covers it.
 *
 * @param savedAt when the request in it was last written (renaming it aside keeps this date)
 * @param details what could be read from it, or {@code null} if nothing could
 * @param decision the decision that covers it, or the older one for a file that changed since; else {@code null}
 */
public record Evidence(String fileName, EvidenceKind kind, boolean damagedWhenSetAside, long size, String sha256,
                       Instant savedAt, Details details, Hint hint, Status status, EvidenceDecision decision) {

    /** What the file says, where it could be read. Any field may be {@code null}. */
    public record Details(UUID operationId, UUID shopId, UUID deviceId, UUID productId, String unit,
                          Long quantityMilli, String note) { }

    /** Help for the person deciding. Never applied by itself. */
    public enum Hint {
        /** Its operation is in this shop's records. */
        FOUND,
        /** Its operation is not in this shop's records. */
        NOT_FOUND,
        /** It belongs to another shop, so this shop's records cannot tell. */
        OTHER_SHOP,
        /** It could not be read, or the records could not be checked. */
        UNKNOWN
    }

    public enum Status {
        /** No decision yet: backup and restore are blocked. */
        UNDECIDED,
        /** A decision covers these exact bytes. */
        DECIDED,
        /** A decision exists, but the file changed afterwards: it needs a new one. */
        CHANGED_SINCE_DECISION,
        /** A decision file for it exists but cannot be read: someone must look at it. */
        DECISION_UNREADABLE
    }

    /** True while this file blocks backup and restore. */
    public boolean blocking() { return status != Status.DECIDED; }
}

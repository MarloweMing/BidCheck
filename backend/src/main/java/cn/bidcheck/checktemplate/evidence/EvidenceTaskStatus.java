package cn.bidcheck.checktemplate.evidence;

/** Durable states of an evidence task. */
public enum EvidenceTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    NOT_FOUND_IN_CLOSED_SCOPE,
    PARTIAL_SCOPE,
    RETRY_WAIT,
    FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == NOT_FOUND_IN_CLOSED_SCOPE
                || this == PARTIAL_SCOPE || this == FAILED;
    }
}

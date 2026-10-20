package cn.bidcheck.checktemplate.evidence;

/** Sanitized failure persisted with a terminal task. */
public record EvidenceTaskFailure(String code, String message, boolean retryable, int failedAttempt) {
    public EvidenceTaskFailure {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        code = code.trim();
        message = message == null ? "" : message.trim();
        if (failedAttempt < 1) throw new IllegalArgumentException("failedAttempt must be positive");
    }
}

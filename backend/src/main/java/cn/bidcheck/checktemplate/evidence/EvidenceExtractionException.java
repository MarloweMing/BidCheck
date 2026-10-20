package cn.bidcheck.checktemplate.evidence;

/** Extractors must explicitly classify retryable failures; unknown runtime failures are permanent. */
public final class EvidenceExtractionException extends Exception {
    private final String code;
    private final boolean retryable;

    private EvidenceExtractionException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        this.code = code.trim();
        this.retryable = retryable;
    }

    public static EvidenceExtractionException retryable(String code, String message) {
        return new EvidenceExtractionException(code, message, true, null);
    }

    public static EvidenceExtractionException permanent(String code, String message) {
        return new EvidenceExtractionException(code, message, false, null);
    }

    public static EvidenceExtractionException permanent(String code, String message, Throwable cause) {
        return new EvidenceExtractionException(code, message, false, cause);
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}

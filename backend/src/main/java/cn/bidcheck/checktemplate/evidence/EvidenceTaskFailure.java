package cn.bidcheck.checktemplate.evidence;

/** Sanitized failure persisted with a terminal task. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceTaskFailure {
        private final String code;
        private final String message;
        private final boolean retryable;
        private final int failedAttempt;
        public String code() { return code; }
        public String message() { return message; }
        public boolean retryable() { return retryable; }
        public int failedAttempt() { return failedAttempt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceTaskFailure that = (EvidenceTaskFailure) other; return java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.message, that.message) && java.util.Objects.equals(this.retryable, that.retryable) && java.util.Objects.equals(this.failedAttempt, that.failedAttempt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.message); hash = 31 * hash + java.util.Objects.hashCode(this.retryable); hash = 31 * hash + java.util.Objects.hashCode(this.failedAttempt); return hash; }
        @Override public String toString() { return "EvidenceTaskFailure[" + "code=" + code + ", message=" + message + ", retryable=" + retryable + ", failedAttempt=" + failedAttempt + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceTaskFailure(@com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("message") String message, @com.fasterxml.jackson.annotation.JsonProperty("retryable") boolean retryable, @com.fasterxml.jackson.annotation.JsonProperty("failedAttempt") int failedAttempt) {
        if (code == null || cn.bidcheck.compat.Java8Api.isBlank(code)) throw new IllegalArgumentException("code is required");
        code = code.trim();
        message = message == null ? "" : message.trim();
        if (failedAttempt < 1) throw new IllegalArgumentException("failedAttempt must be positive");

            this.code = code; this.message = message; this.retryable = retryable; this.failedAttempt = failedAttempt;
        }
}

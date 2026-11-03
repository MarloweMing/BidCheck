package cn.bidcheck.checktemplate.evidence;

import java.time.Instant;

/** Lease-bound execution context. A late worker cannot publish after its lease has been replaced. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceExtractionContext {
        private final EvidenceNeedKey key;
        private final int attempt;
        private final String leaseToken;
        private final Instant startedAt;
        public EvidenceNeedKey key() { return key; }
        public int attempt() { return attempt; }
        public String leaseToken() { return leaseToken; }
        public Instant startedAt() { return startedAt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceExtractionContext that = (EvidenceExtractionContext) other; return java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.attempt, that.attempt) && java.util.Objects.equals(this.leaseToken, that.leaseToken) && java.util.Objects.equals(this.startedAt, that.startedAt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.attempt); hash = 31 * hash + java.util.Objects.hashCode(this.leaseToken); hash = 31 * hash + java.util.Objects.hashCode(this.startedAt); return hash; }
        @Override public String toString() { return "EvidenceExtractionContext[" + "key=" + key + ", attempt=" + attempt + ", leaseToken=" + leaseToken + ", startedAt=" + startedAt + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceExtractionContext(@com.fasterxml.jackson.annotation.JsonProperty("key") EvidenceNeedKey key, @com.fasterxml.jackson.annotation.JsonProperty("attempt") int attempt, @com.fasterxml.jackson.annotation.JsonProperty("leaseToken") String leaseToken, @com.fasterxml.jackson.annotation.JsonProperty("startedAt") Instant startedAt) {
        if (key == null) throw new IllegalArgumentException("key is required");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if (leaseToken == null || cn.bidcheck.compat.Java8Api.isBlank(leaseToken)) throw new IllegalArgumentException("leaseToken is required");
        if (startedAt == null) throw new IllegalArgumentException("startedAt is required");

            this.key = key; this.attempt = attempt; this.leaseToken = leaseToken; this.startedAt = startedAt;
        }
}

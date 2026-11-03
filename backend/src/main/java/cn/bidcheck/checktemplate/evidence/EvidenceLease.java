package cn.bidcheck.checktemplate.evidence;

import java.time.Instant;

/** Persistable ownership lease for one extraction attempt. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceLease {
        private final String ownerId;
        private final String token;
        private final Instant acquiredAt;
        private final Instant expiresAt;
        public String ownerId() { return ownerId; }
        public String token() { return token; }
        public Instant acquiredAt() { return acquiredAt; }
        public Instant expiresAt() { return expiresAt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceLease that = (EvidenceLease) other; return java.util.Objects.equals(this.ownerId, that.ownerId) && java.util.Objects.equals(this.token, that.token) && java.util.Objects.equals(this.acquiredAt, that.acquiredAt) && java.util.Objects.equals(this.expiresAt, that.expiresAt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.ownerId); hash = 31 * hash + java.util.Objects.hashCode(this.token); hash = 31 * hash + java.util.Objects.hashCode(this.acquiredAt); hash = 31 * hash + java.util.Objects.hashCode(this.expiresAt); return hash; }
        @Override public String toString() { return "EvidenceLease[" + "ownerId=" + ownerId + ", token=" + token + ", acquiredAt=" + acquiredAt + ", expiresAt=" + expiresAt + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceLease(@com.fasterxml.jackson.annotation.JsonProperty("ownerId") String ownerId, @com.fasterxml.jackson.annotation.JsonProperty("token") String token, @com.fasterxml.jackson.annotation.JsonProperty("acquiredAt") Instant acquiredAt, @com.fasterxml.jackson.annotation.JsonProperty("expiresAt") Instant expiresAt) {
        ownerId = required(ownerId, "ownerId");
        token = required(token, "token");
        if (acquiredAt == null || expiresAt == null || !expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("lease timestamps are invalid");
        }

            this.ownerId = ownerId; this.token = token; this.acquiredAt = acquiredAt; this.expiresAt = expiresAt;
        }

    public boolean activeAt(Instant now) {
        return now != null && now.isBefore(expiresAt);
    }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

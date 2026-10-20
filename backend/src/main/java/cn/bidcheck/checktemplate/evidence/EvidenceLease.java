package cn.bidcheck.checktemplate.evidence;

import java.time.Instant;

/** Persistable ownership lease for one extraction attempt. */
public record EvidenceLease(String ownerId, String token, Instant acquiredAt, Instant expiresAt) {
    public EvidenceLease {
        ownerId = required(ownerId, "ownerId");
        token = required(token, "token");
        if (acquiredAt == null || expiresAt == null || !expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("lease timestamps are invalid");
        }
    }

    public boolean activeAt(Instant now) {
        return now != null && now.isBefore(expiresAt);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

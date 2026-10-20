package cn.bidcheck.checktemplate.evidence;

import java.time.Instant;

/** Lease-bound execution context. A late worker cannot publish after its lease has been replaced. */
public record EvidenceExtractionContext(
        EvidenceNeedKey key,
        int attempt,
        String leaseToken,
        Instant startedAt) {
    public EvidenceExtractionContext {
        if (key == null) throw new IllegalArgumentException("key is required");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if (leaseToken == null || leaseToken.isBlank()) throw new IllegalArgumentException("leaseToken is required");
        if (startedAt == null) throw new IllegalArgumentException("startedAt is required");
    }
}

package cn.bidcheck.checktemplate.evidence;

import java.time.Instant;
import java.util.Optional;

/**
 * Minimal persistence port. A Store/database adapter only needs atomic create and revision CAS;
 * coordinator behavior does not depend on in-process locks.
 */
public interface EvidenceTaskRepository {
    EvidenceTask createOrGet(EvidenceNeedKey key, int maxAttempts, Instant now);
    Optional<EvidenceTask> find(String taskId);

    /** Replace only when the durable revision equals {@code expectedRevision}. */
    boolean replace(EvidenceTask replacement, long expectedRevision);
}

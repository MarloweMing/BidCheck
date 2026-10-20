package cn.bidcheck.checktemplate.evidence;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe reference repository; production Store adapters can implement the same CAS port. */
public final class InMemoryEvidenceTaskRepository implements EvidenceTaskRepository {
    private final ConcurrentHashMap<String, EvidenceTask> tasks = new ConcurrentHashMap<>();

    @Override
    public EvidenceTask createOrGet(EvidenceNeedKey key, int maxAttempts, Instant now) {
        EvidenceTask seed = EvidenceTask.pending(key, maxAttempts, now);
        EvidenceTask task = tasks.computeIfAbsent(seed.taskId(), ignored -> seed);
        if (!task.key().equals(key)) throw new IllegalStateException("evidence key hash collision");
        return task;
    }

    @Override
    public Optional<EvidenceTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public boolean replace(EvidenceTask replacement, long expectedRevision) {
        final boolean[] replaced = {false};
        tasks.computeIfPresent(replacement.taskId(), (ignored, current) -> {
            if (current.revision() == expectedRevision) {
                replaced[0] = true;
                return replacement;
            }
            return current;
        });
        return replaced[0];
    }
}

package cn.bidcheck.checktemplate.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable, optimistic-lockable representation of an evidence task. */
public record EvidenceTask(
        String taskId,
        EvidenceNeedKey key,
        EvidenceTaskStatus status,
        long revision,
        int attempts,
        int maxAttempts,
        EvidenceLease lease,
        Instant nextAttemptAt,
        EvidenceExtractionResult result,
        EvidenceTaskFailure failure,
        Set<String> subscriberIds,
        Instant createdAt,
        Instant updatedAt) {

    public EvidenceTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        taskId = taskId.trim();
        if (key == null || !taskId.equals(key.id())) throw new IllegalArgumentException("taskId must match key id");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (revision < 0 || attempts < 0 || maxAttempts < 1 || attempts > maxAttempts) {
            throw new IllegalArgumentException("invalid task counters");
        }
        subscriberIds = subscriberIds == null ? Set.of() : Set.copyOf(subscriberIds);
        if (createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("invalid task timestamps");
        }
        if ((status == EvidenceTaskStatus.RUNNING) != (lease != null)) {
            throw new IllegalArgumentException("only RUNNING tasks have a lease");
        }
        if ((status == EvidenceTaskStatus.RETRY_WAIT) != (nextAttemptAt != null)) {
            throw new IllegalArgumentException("only RETRY_WAIT tasks have nextAttemptAt");
        }
        boolean resultState = status == EvidenceTaskStatus.SUCCEEDED
                || status == EvidenceTaskStatus.NOT_FOUND_IN_CLOSED_SCOPE
                || status == EvidenceTaskStatus.PARTIAL_SCOPE;
        if (resultState != (result != null)) throw new IllegalArgumentException("result does not match status");
        boolean failureState = status == EvidenceTaskStatus.FAILED || status == EvidenceTaskStatus.RETRY_WAIT;
        if (failureState != (failure != null)) {
            throw new IllegalArgumentException("failure does not match status");
        }
        if (result != null && status != statusFor(result)) {
            throw new IllegalArgumentException("result disposition does not match status");
        }
    }

    public static EvidenceTask pending(EvidenceNeedKey key, int maxAttempts, Instant now) {
        if (key == null || now == null) throw new IllegalArgumentException("key and now are required");
        return new EvidenceTask(key.id(), key, EvidenceTaskStatus.PENDING, 0, 0, maxAttempts,
                null, null, null, null, Set.of(), now, now);
    }

    public EvidenceTask subscribe(String subscriberId, Instant now) {
        String normalized = required(subscriberId, "subscriberId");
        if (subscriberIds.contains(normalized)) return this;
        LinkedHashSet<String> subscribers = new LinkedHashSet<>(subscriberIds);
        subscribers.add(normalized);
        return copy(status, revision + 1, attempts, lease, nextAttemptAt, result, failure,
                Set.copyOf(subscribers), now);
    }

    public boolean canAcquireAt(Instant now) {
        if (status == EvidenceTaskStatus.PENDING) return true;
        if (status == EvidenceTaskStatus.RETRY_WAIT) return !now.isBefore(nextAttemptAt);
        return status == EvidenceTaskStatus.RUNNING && !lease.activeAt(now);
    }

    public EvidenceTask acquire(String ownerId, String token, Instant now, Duration duration) {
        if (!canAcquireAt(now)) throw new IllegalStateException("task is not ready for a lease");
        if (attempts >= maxAttempts) throw new IllegalStateException("attempt limit reached");
        EvidenceLease nextLease = new EvidenceLease(ownerId, token, now, now.plus(duration));
        return copy(EvidenceTaskStatus.RUNNING, revision + 1, attempts + 1, nextLease,
                null, null, null, subscriberIds, now);
    }

    public boolean ownsLease(String token) {
        return status == EvidenceTaskStatus.RUNNING && lease != null && lease.token().equals(token);
    }

    public EvidenceTask complete(String token, EvidenceExtractionResult nextResult, Instant now) {
        if (!ownsLease(token)) throw new IllegalStateException("lease is no longer owned");
        EvidenceTaskStatus nextStatus = statusFor(nextResult);
        return copy(nextStatus, revision + 1, attempts, null, null, nextResult, null, subscriberIds, now);
    }

    public EvidenceTask waitForRetry(String token, EvidenceTaskFailure nextFailure,
                                     Instant retryAt, Instant now) {
        if (!ownsLease(token)) throw new IllegalStateException("lease is no longer owned");
        if (attempts >= maxAttempts) throw new IllegalStateException("attempt limit reached");
        if (retryAt == null || !retryAt.isAfter(now)) throw new IllegalArgumentException("retryAt must be in the future");
        return copy(EvidenceTaskStatus.RETRY_WAIT, revision + 1, attempts, null, retryAt,
                null, nextFailure, subscriberIds, now);
    }

    public EvidenceTask fail(String token, EvidenceTaskFailure nextFailure, Instant now) {
        if (status == EvidenceTaskStatus.RUNNING && token != null && !ownsLease(token)) {
            throw new IllegalStateException("lease is no longer owned");
        }
        if (status != EvidenceTaskStatus.RUNNING && status != EvidenceTaskStatus.PENDING
                && status != EvidenceTaskStatus.RETRY_WAIT) {
            throw new IllegalStateException("task cannot fail from " + status);
        }
        return copy(EvidenceTaskStatus.FAILED, revision + 1, attempts, null, null,
                null, nextFailure, subscriberIds, now);
    }

    private EvidenceTask copy(EvidenceTaskStatus nextStatus, long nextRevision, int nextAttempts,
                              EvidenceLease nextLease, Instant nextAttemptAt,
                              EvidenceExtractionResult nextResult, EvidenceTaskFailure nextFailure,
                              Set<String> subscribers, Instant now) {
        return new EvidenceTask(taskId, key, nextStatus, nextRevision, nextAttempts, maxAttempts,
                nextLease, nextAttemptAt, nextResult, nextFailure, subscribers, createdAt, now);
    }

    private static EvidenceTaskStatus statusFor(EvidenceExtractionResult result) {
        if (result == null) throw new IllegalArgumentException("result is required");
        return switch (result.disposition()) {
            case POSITIVE -> EvidenceTaskStatus.SUCCEEDED;
            case NOT_FOUND -> EvidenceTaskStatus.NOT_FOUND_IN_CLOSED_SCOPE;
            case PARTIAL -> EvidenceTaskStatus.PARTIAL_SCOPE;
        };
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

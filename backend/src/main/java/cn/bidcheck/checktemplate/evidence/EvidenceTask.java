package cn.bidcheck.checktemplate.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable, optimistic-lockable representation of an evidence task. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceTask {
        private final String taskId;
        private final EvidenceNeedKey key;
        private final EvidenceTaskStatus status;
        private final long revision;
        private final int attempts;
        private final int maxAttempts;
        private final EvidenceLease lease;
        private final Instant nextAttemptAt;
        private final EvidenceExtractionResult result;
        private final EvidenceTaskFailure failure;
        private final Set<String> subscriberIds;
        private final Instant createdAt;
        private final Instant updatedAt;
        public String taskId() { return taskId; }
        public EvidenceNeedKey key() { return key; }
        public EvidenceTaskStatus status() { return status; }
        public long revision() { return revision; }
        public int attempts() { return attempts; }
        public int maxAttempts() { return maxAttempts; }
        public EvidenceLease lease() { return lease; }
        public Instant nextAttemptAt() { return nextAttemptAt; }
        public EvidenceExtractionResult result() { return result; }
        public EvidenceTaskFailure failure() { return failure; }
        public Set<String> subscriberIds() { return subscriberIds; }
        public Instant createdAt() { return createdAt; }
        public Instant updatedAt() { return updatedAt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceTask that = (EvidenceTask) other; return java.util.Objects.equals(this.taskId, that.taskId) && java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.attempts, that.attempts) && java.util.Objects.equals(this.maxAttempts, that.maxAttempts) && java.util.Objects.equals(this.lease, that.lease) && java.util.Objects.equals(this.nextAttemptAt, that.nextAttemptAt) && java.util.Objects.equals(this.result, that.result) && java.util.Objects.equals(this.failure, that.failure) && java.util.Objects.equals(this.subscriberIds, that.subscriberIds) && java.util.Objects.equals(this.createdAt, that.createdAt) && java.util.Objects.equals(this.updatedAt, that.updatedAt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.taskId); hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.attempts); hash = 31 * hash + java.util.Objects.hashCode(this.maxAttempts); hash = 31 * hash + java.util.Objects.hashCode(this.lease); hash = 31 * hash + java.util.Objects.hashCode(this.nextAttemptAt); hash = 31 * hash + java.util.Objects.hashCode(this.result); hash = 31 * hash + java.util.Objects.hashCode(this.failure); hash = 31 * hash + java.util.Objects.hashCode(this.subscriberIds); hash = 31 * hash + java.util.Objects.hashCode(this.createdAt); hash = 31 * hash + java.util.Objects.hashCode(this.updatedAt); return hash; }
        @Override public String toString() { return "EvidenceTask[" + "taskId=" + taskId + ", key=" + key + ", status=" + status + ", revision=" + revision + ", attempts=" + attempts + ", maxAttempts=" + maxAttempts + ", lease=" + lease + ", nextAttemptAt=" + nextAttemptAt + ", result=" + result + ", failure=" + failure + ", subscriberIds=" + subscriberIds + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceTask(@com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId, @com.fasterxml.jackson.annotation.JsonProperty("key") EvidenceNeedKey key, @com.fasterxml.jackson.annotation.JsonProperty("status") EvidenceTaskStatus status, @com.fasterxml.jackson.annotation.JsonProperty("revision") long revision, @com.fasterxml.jackson.annotation.JsonProperty("attempts") int attempts, @com.fasterxml.jackson.annotation.JsonProperty("maxAttempts") int maxAttempts, @com.fasterxml.jackson.annotation.JsonProperty("lease") EvidenceLease lease, @com.fasterxml.jackson.annotation.JsonProperty("nextAttemptAt") Instant nextAttemptAt, @com.fasterxml.jackson.annotation.JsonProperty("result") EvidenceExtractionResult result, @com.fasterxml.jackson.annotation.JsonProperty("failure") EvidenceTaskFailure failure, @com.fasterxml.jackson.annotation.JsonProperty("subscriberIds") Set<String> subscriberIds, @com.fasterxml.jackson.annotation.JsonProperty("createdAt") Instant createdAt, @com.fasterxml.jackson.annotation.JsonProperty("updatedAt") Instant updatedAt) {
        if (taskId == null || cn.bidcheck.compat.Java8Api.isBlank(taskId)) throw new IllegalArgumentException("taskId is required");
        taskId = taskId.trim();
        if (key == null || !taskId.equals(key.id())) throw new IllegalArgumentException("taskId must match key id");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (revision < 0 || attempts < 0 || maxAttempts < 1 || attempts > maxAttempts) {
            throw new IllegalArgumentException("invalid task counters");
        }
        subscriberIds = subscriberIds == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(subscriberIds);
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

            this.taskId = taskId; this.key = key; this.status = status; this.revision = revision; this.attempts = attempts; this.maxAttempts = maxAttempts; this.lease = lease; this.nextAttemptAt = nextAttemptAt; this.result = result; this.failure = failure; this.subscriberIds = subscriberIds; this.createdAt = createdAt; this.updatedAt = updatedAt;
        }

    public static EvidenceTask pending(EvidenceNeedKey key, int maxAttempts, Instant now) {
        if (key == null || now == null) throw new IllegalArgumentException("key and now are required");
        return new EvidenceTask(key.id(), key, EvidenceTaskStatus.PENDING, 0, 0, maxAttempts,
                null, null, null, null, cn.bidcheck.compat.Java8Api.setOf(), now, now);
    }

    public EvidenceTask subscribe(String subscriberId, Instant now) {
        String normalized = required(subscriberId, "subscriberId");
        if (subscriberIds.contains(normalized)) return this;
        LinkedHashSet<String> subscribers = new LinkedHashSet<>(subscriberIds);
        subscribers.add(normalized);
        return copy(status, revision + 1, attempts, lease, nextAttemptAt, result, failure,
                cn.bidcheck.compat.Java8Api.setCopyOf(subscribers), now);
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
        switch (result.disposition()) {case POSITIVE:return EvidenceTaskStatus.SUCCEEDED;case NOT_FOUND:return EvidenceTaskStatus.NOT_FOUND_IN_CLOSED_SCOPE;case PARTIAL:return EvidenceTaskStatus.PARTIAL_SCOPE;default:throw new IncompatibleClassChangeError();}
    }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

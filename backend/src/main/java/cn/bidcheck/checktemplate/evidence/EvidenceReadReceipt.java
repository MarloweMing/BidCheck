package cn.bidcheck.checktemplate.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import cn.bidcheck.compat.HexFormat;

/** Immutable audit receipt proving which shared task revision a subscriber read. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceReadReceipt {
        private final String receiptId;
        private final String subscriberId;
        private final String taskId;
        private final EvidenceNeedKey key;
        private final long taskRevision;
        private final EvidenceTaskStatus status;
        private final int attempts;
        private final boolean sharedTask;
        private final EvidenceExtractionResult result;
        private final EvidenceTaskFailure failure;
        private final Instant readAt;
        public String receiptId() { return receiptId; }
        public String subscriberId() { return subscriberId; }
        public String taskId() { return taskId; }
        public EvidenceNeedKey key() { return key; }
        public long taskRevision() { return taskRevision; }
        public EvidenceTaskStatus status() { return status; }
        public int attempts() { return attempts; }
        public boolean sharedTask() { return sharedTask; }
        public EvidenceExtractionResult result() { return result; }
        public EvidenceTaskFailure failure() { return failure; }
        public Instant readAt() { return readAt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceReadReceipt that = (EvidenceReadReceipt) other; return java.util.Objects.equals(this.receiptId, that.receiptId) && java.util.Objects.equals(this.subscriberId, that.subscriberId) && java.util.Objects.equals(this.taskId, that.taskId) && java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.taskRevision, that.taskRevision) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.attempts, that.attempts) && java.util.Objects.equals(this.sharedTask, that.sharedTask) && java.util.Objects.equals(this.result, that.result) && java.util.Objects.equals(this.failure, that.failure) && java.util.Objects.equals(this.readAt, that.readAt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.receiptId); hash = 31 * hash + java.util.Objects.hashCode(this.subscriberId); hash = 31 * hash + java.util.Objects.hashCode(this.taskId); hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.taskRevision); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.attempts); hash = 31 * hash + java.util.Objects.hashCode(this.sharedTask); hash = 31 * hash + java.util.Objects.hashCode(this.result); hash = 31 * hash + java.util.Objects.hashCode(this.failure); hash = 31 * hash + java.util.Objects.hashCode(this.readAt); return hash; }
        @Override public String toString() { return "EvidenceReadReceipt[" + "receiptId=" + receiptId + ", subscriberId=" + subscriberId + ", taskId=" + taskId + ", key=" + key + ", taskRevision=" + taskRevision + ", status=" + status + ", attempts=" + attempts + ", sharedTask=" + sharedTask + ", result=" + result + ", failure=" + failure + ", readAt=" + readAt + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceReadReceipt(@com.fasterxml.jackson.annotation.JsonProperty("receiptId") String receiptId, @com.fasterxml.jackson.annotation.JsonProperty("subscriberId") String subscriberId, @com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId, @com.fasterxml.jackson.annotation.JsonProperty("key") EvidenceNeedKey key, @com.fasterxml.jackson.annotation.JsonProperty("taskRevision") long taskRevision, @com.fasterxml.jackson.annotation.JsonProperty("status") EvidenceTaskStatus status, @com.fasterxml.jackson.annotation.JsonProperty("attempts") int attempts, @com.fasterxml.jackson.annotation.JsonProperty("sharedTask") boolean sharedTask, @com.fasterxml.jackson.annotation.JsonProperty("result") EvidenceExtractionResult result, @com.fasterxml.jackson.annotation.JsonProperty("failure") EvidenceTaskFailure failure, @com.fasterxml.jackson.annotation.JsonProperty("readAt") Instant readAt) {
        if (receiptId == null || cn.bidcheck.compat.Java8Api.isBlank(receiptId) || subscriberId == null || cn.bidcheck.compat.Java8Api.isBlank(subscriberId)
                || taskId == null || cn.bidcheck.compat.Java8Api.isBlank(taskId)) throw new IllegalArgumentException("receipt identity is required");
        if (key == null || status == null || !status.terminal() || readAt == null) {
            throw new IllegalArgumentException("receipt requires a terminal task");
        }

            this.receiptId = receiptId; this.subscriberId = subscriberId; this.taskId = taskId; this.key = key; this.taskRevision = taskRevision; this.status = status; this.attempts = attempts; this.sharedTask = sharedTask; this.result = result; this.failure = failure; this.readAt = readAt;
        }

    static EvidenceReadReceipt from(EvidenceTask task, String subscriberId, Instant readAt) {
        String id = digest(task.taskId() + ":" + task.revision() + ":" + subscriberId);
        return new EvidenceReadReceipt(id, subscriberId, task.taskId(), task.key(), task.revision(),
                task.status(), task.attempts(), task.subscriberIds().size() > 1,
                task.result(), task.failure(), readAt);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

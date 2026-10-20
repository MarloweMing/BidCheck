package cn.bidcheck.checktemplate.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Immutable audit receipt proving which shared task revision a subscriber read. */
public record EvidenceReadReceipt(
        String receiptId,
        String subscriberId,
        String taskId,
        EvidenceNeedKey key,
        long taskRevision,
        EvidenceTaskStatus status,
        int attempts,
        boolean sharedTask,
        EvidenceExtractionResult result,
        EvidenceTaskFailure failure,
        Instant readAt) {

    public EvidenceReadReceipt {
        if (receiptId == null || receiptId.isBlank() || subscriberId == null || subscriberId.isBlank()
                || taskId == null || taskId.isBlank()) throw new IllegalArgumentException("receipt identity is required");
        if (key == null || status == null || !status.terminal() || readAt == null) {
            throw new IllegalArgumentException("receipt requires a terminal task");
        }
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

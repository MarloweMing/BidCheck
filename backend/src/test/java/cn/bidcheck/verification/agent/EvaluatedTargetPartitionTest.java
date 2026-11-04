package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely synthetic transport and host; no provider call or original document. */
class EvaluatedTargetPartitionTest {
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static Map<String,Object> partition(boolean closed, String revision) {
        return map("status", "COMPLETED", "targetReceiptAuthority", "CURRENT_SERVER_PROOF",
            "serverPlanRevision", "plan-1", "serverPlanFingerprint", "fingerprint-1",
            "targetEvidenceRevision", revision, "targetEvidenceRevisions", map("A", revision, "B", "B-1"),
            "completedTargetObligationIds", closed ? Arrays.asList("A") : Collections.emptyList(),
            "remainingTargetObligationIds", closed ? Arrays.asList("B") : Arrays.asList("A", "B"),
            "businessCheckCompleted", false, "targetCheckCompleted", false);
    }

    private static Map<String,Object> evaluated(boolean closed) {
        Map<String,Object> result = partition(closed, "revision-2");
        Map<String,Object> receipt = new LinkedHashMap<>(result);
        receipt.putAll(map("kind", "HOST_TARGET_RECOMPUTE", "requestId", "partition-test",
            "tool", "read_table_record", "targetObligationId", "B", "targetObligationIds", Arrays.asList("A", "B")));
        result.put("evaluatedTargetReceipt", receipt);
        return result;
    }

    private Map<String,Object> run(Map<String,Object> hostResponse) throws Exception {
        Map<String,Object> context = partition(true, "revision-1");
        context.putAll(map("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", Arrays.asList("A", "B")));
        Map<String,Object> start = map("type", "start", "protocol", "bidcheck-agent-1",
            "requestId", "partition-test", "context", context,
            "tools", Arrays.asList(map("name", "read_table_record", "description", "合成表格工具",
                "parameters", map("type", "object", "properties", map("targetObligationId", map("type", "string")),
                    "required", Arrays.asList("targetObligationId"), "additionalProperties", false))));
        AtomicInteger turns = new AtomicInteger();
        JavaAgentRunner.ModelTransport model = (request, timeout) -> {
            List<Map<String,Object>> calls = turns.getAndIncrement() == 0
                ? Arrays.asList(map("id", "read-1", "type", "function", "function",
                    map("name", "read_table_record", "arguments", "{\"targetObligationId\":\"B\"}")))
                : Collections.emptyList();
            return map("choices", Arrays.asList(map("message", map("role", "assistant", "content", "", "tool_calls", calls))));
        };
        SdkAgentInvestigator sdk = new SdkAgentInvestigator(true,
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "synthetic-key", "synthetic", 5, 1, null, model);
        return sdk.investigate(start, (name, args) -> hostResponse, event -> {});
    }

    @Test void changedRevisionRetainsOnlyCurrentlyProvenTargets() throws Exception {
        Map<String,Object> result = run(evaluated(true));
        assertEquals(Arrays.asList("A"), result.get("completedTargetObligationIds"));
        assertEquals(Arrays.asList("B"), result.get("businessUnclosedTargetObligationIds"));
        assertEquals(false, result.get("businessCheckCompleted"));
        assertEquals(1, result.get("automaticRecomputations"));
    }

    @Test void currentCounterevidenceReopensPreviouslyClosedTarget() throws Exception {
        Map<String,Object> result = run(evaluated(false));
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
        assertEquals(Arrays.asList("A", "B"), result.get("businessUnclosedTargetObligationIds"));
    }

    @Test void unsignedNavigationNeverCertifiesTheNewRevision() throws Exception {
        Map<String,Object> result = run(partition(true, "revision-2"));
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
    }

    @SuppressWarnings("unchecked")
    @Test void conflictingNestedRecomputeCannotOverrideEvaluatedPartition() throws Exception {
        Map<String,Object> response = evaluated(false);
        Map<String,Object> conflicting = new LinkedHashMap<>((Map<String,Object>) response.get("evaluatedTargetReceipt"));
        conflicting.put("kind", "HOST_RULE_RECOMPUTE");
        conflicting.put("completedTargetObligationIds", Arrays.asList("A"));
        conflicting.put("remainingTargetObligationIds", Arrays.asList("B"));
        response.put("computationReceipt", conflicting);
        assertEquals("HOST_TARGET_RECEIPT_INVALID", run(response).get("errorCode"));
    }

    @SuppressWarnings("unchecked")
    @Test void staleIncompleteOrConflictingEvaluationReceiptIsRejected() throws Exception {
        List<Map<String,Object>> mutations = Arrays.asList(
            map("requestId", "old-request"), map("tool", "search_evidence"),
            map("targetObligationId", "A"), map("kind", "WORKER_ASSERTION"),
            map("serverPlanFingerprint", "other-plan"), map("targetEvidenceRevision", "revision-1"),
            map("targetObligationIds", Arrays.asList("A")),
            map("completedTargetObligationIds", Arrays.asList("A", "B")),
            map("targetEvidenceRevisions", map("A", "revision-1", "B", "B-1")));
        for (Map<String,Object> mutation : mutations) {
            Map<String,Object> response = evaluated(true);
            ((Map<String,Object>) response.get("evaluatedTargetReceipt")).putAll(mutation);
            Map<String,Object> result = run(response);
            assertEquals("HOST_TARGET_RECEIPT_INVALID", result.get("errorCode"), mutation.toString());
            assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
        }
    }
}

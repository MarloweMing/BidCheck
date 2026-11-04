package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline provider and host; the real SDK, Runner and diagnostic filter execute unchanged. */
public final class RunnerNoNewFactFixture {
    private RunnerNoNewFactFixture() {}
    public static Map<String,Object> run(boolean changeRevision) {
        AtomicInteger turns = new AtomicInteger();
        AtomicInteger hostCalls = new AtomicInteger();
        ObjectMapper json = new ObjectMapper();
        Map<String,Object> context = map("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", Collections.singletonList("A"),
            "serverPlanRevision", "plan", "serverPlanFingerprint", "fingerprint", "targetEvidenceRevision", "global1",
            "targetEvidenceRevisions", map("A", "v1"));
        context.putAll(receipt("v1"));
        SdkAgentInvestigator sdk = new SdkAgentInvestigator(true, "https://dashscope.aliyuncs.com/compatible-mode/v1", "synthetic-key",
            "synthetic-model", 10, 1, null, (request, timeout) -> {
                int turn = turns.incrementAndGet();
                Map<String,Object> arguments = map("query", "query" + Math.min(turn, 2), "targetObligationId", "A");
                Map<String,Object> call = map("id", "call" + turn, "type", "function", "function",
                    map("name", "search_evidence", "arguments", json.writeValueAsString(arguments)));
                return map("choices", Collections.singletonList(map("message", map("role", "assistant", "tool_calls", Collections.singletonList(call)))));
            });
        Map<String,Object> schema = map("type", "object", "properties",
            map("query", map("type", "string"), "targetObligationId", map("type", "string")),
            "required", Arrays.asList("query", "targetObligationId"), "additionalProperties", false);
        Map<String,Object> start = map("type", "start", "protocol", "bidcheck-agent-1", "requestId", "round-test",
            "context", context, "tools", Collections.singletonList(map("name", "search_evidence", "description", "synthetic read", "parameters", schema)));
        return sdk.investigate(start, (name, arguments) -> {
            int call = hostCalls.incrementAndGet();
            Map<String,Object> response = map("status", "COMPLETED", "sources", Collections.singletonList(
                map("id", "S" + call, "documentId", "doc", "page", call, "locationType", "PAGE", "text", "synthetic original " + call)));
            if (changeRevision && call == 2) {
                Map<String,Object> recomputed = receipt("v2");
                recomputed.put("kind", "HOST_RULE_RECOMPUTE");
                recomputed.put("status", "COMPLETED");
                response.putAll(receipt("v2"));
                response.put("computationReceipt", recomputed);
            }
            return response;
        }, event -> {});
    }
    /** Pass an adversarial raw diagnostic through the actual SDK protocol and safeLedger. */
    public static Map<String,Object> filterLedger(Map<String,Object> row) throws Exception {
        ObjectMapper json = new ObjectMapper();
        StringBuilder input = new StringBuilder();
        for (int index = 1; index <= 3; index++) input.append(json.writeValueAsString(map(
            "type", "event", "protocol", "bidcheck-agent-1", "requestId", "round-test", "event", "model_started",
            "id", "permit" + index, "modelIndex", index))).append('\n');
        input.append(json.writeValueAsString(map("type", "result", "protocol", "bidcheck-agent-1", "requestId", "round-test",
            "status", "NO_PROGRESS", "code", "REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", "tracingExportEnabled", false,
            "modelCalls", 3, "targetProgressLedger", Collections.singletonList(row),
            "remainingTargetObligationIds", Collections.singletonList("A")))).append('\n');
        final byte[] frames = input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SdkAgentInvestigator.ProtocolFactory protocol = new SdkAgentInvestigator.ProtocolFactory() {
            public String identity() { return "synthetic-offline-ledger"; }
            public SdkAgentInvestigator.ProtocolConnection open(int timeout, String endpoint, String key, String model, String audit) {
                return new SdkAgentInvestigator.ProtocolConnection() {
                    final java.io.InputStream incoming = new java.io.ByteArrayInputStream(frames);
                    final java.io.OutputStream outgoing = new java.io.ByteArrayOutputStream();
                    public java.io.InputStream input() { return incoming; }
                    public java.io.OutputStream output() { return outgoing; }
                    public void close() {}
                };
            }
        };
        SdkAgentInvestigator sdk = new SdkAgentInvestigator(true, "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "synthetic-key", "synthetic-model", 10, 1, protocol);
        Map<String,Object> context = map("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", Collections.singletonList("A"),
            "serverPlanRevision", "plan", "serverPlanFingerprint", "fingerprint", "targetEvidenceRevision", "globalv1",
            "targetEvidenceRevisions", map("A", "v1"));
        context.putAll(receipt("v1"));
        Map<String,Object> start = map("type", "start", "protocol", "bidcheck-agent-1", "requestId", "round-test", "context", context,
            "tools", Collections.singletonList(map("name", "search_evidence", "description", "synthetic read",
                "parameters", map("type", "object", "properties", map(), "additionalProperties", false))));
        return sdk.investigate(start, (name, arguments) -> { throw new AssertionError("No tool is requested"); }, event -> {});
    }
    private static Map<String,Object> receipt(String revision) {
        return map("targetReceiptAuthority", "CURRENT_SERVER_PROOF", "serverPlanRevision", "plan",
            "serverPlanFingerprint", "fingerprint", "targetEvidenceRevision", "global" + revision,
            "targetEvidenceRevisions", map("A", revision), "completedTargetObligationIds", Collections.emptyList(),
            "remainingTargetObligationIds", Collections.singletonList("A"), "businessCheckCompleted", false, "targetCheckCompleted", false);
    }
    private static Map<String,Object> map(Object... items) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) result.put((String) items[i], items[i + 1]);
        return result;
    }
}

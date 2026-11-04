package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport and original host callback boundary; no provider or real documents. */
class EvaluatedReceiptContextTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path audit;

    @Test void validatedReadReceiptReplacesAnOlderRecomputeView() throws Exception {
        List<Map<String,Object>> requests = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        Map<String,Object> result = run((request, timeout) -> {
            requests.add(JSON.convertValue(request, LinkedHashMap.class));
            int index = turn.incrementAndGet();
            if (index == 1) return response(call("old", "recompute_check", map()));
            if (index == 2) return response(call("new", "read_section", map("sourceId", "S1", "targetObligationId", "B")));
            return response();
        }, (name, args) -> name.equals("recompute_check") ? partition("old-revision") : evaluated("new-revision", "S1"));
        assertEquals(3, requests.size(), result.toString());
        Map<String,Object> state = hostState(requests.get(2));
        Map<String,Object> latest = object(state.get("latestComputationReceipt"));
        assertEquals("new-revision", latest.get("targetEvidenceRevision"));
        assertEquals("HOST_TARGET_RECOMPUTE", latest.get("kind"));
        assertEquals(Collections.singletonList("A"), latest.get("completedTargetObligationIds"));
        assertEquals(Collections.singletonList("B"), state.get("remainingTargetObligationIds"));
        assertFalse(JSON.writeValueAsString(latest).contains("SIBLING_LARGE_PROOF"));
        assertFalse(latest.containsKey("expectedFactContracts"));
        assertEquals(false, result.get("businessCheckCompleted"));
    }

    @Test void sixteenReadReceiptsAreReferencesWhileBodiesAndRawAuditRemainComplete() throws Exception {
        List<Map<String,Object>> requests = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        Map<String,Object> result = run((request, timeout) -> {
            requests.add(JSON.convertValue(request, LinkedHashMap.class));
            if (turn.getAndIncrement() != 0) return response();
            List<Map<String,Object>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) calls.add(call("read-" + i, "read_section", map("sourceId", "S" + i, "targetObligationId", "B")));
            return responseList(calls);
        }, (name, args) -> evaluated("new-revision", (String) args.get("sourceId")));
        assertEquals(2, requests.size(), result.toString());
        Map<String,Object> latest = object(hostState(requests.get(1)).get("latestComputationReceipt"));
        assertEquals("new-revision", latest.get("targetEvidenceRevision"));
        int references = 0;
        for (Object raw : list(requests.get(1).get("messages"))) {
            Map<String,Object> message = object(raw);
            if (!"tool".equals(message.get("role"))) continue;
            Map<String,Object> payload = JSON.readValue((String) message.get("content"), Map.class);
            Map<String,Object> toolResult = object(payload.get("result"));
            Map<String,Object> receipt = object(toolResult.get("evaluatedTargetReceipt"));
            assertTrue(receipt.containsKey("fullReceiptRef"));
            assertFalse(receipt.containsKey("obligations"));
            assertFalse(receipt.containsKey("expectedFactContracts"));
            assertEquals("currentHostState.latestComputationReceipt", receipt.get("activeViewRef"));
            assertTrue(JSON.writeValueAsString(toolResult.get("sources")).contains("EXACT_SOURCE_BODY_"));
            references++;
        }
        assertEquals(16, references);
        assertFalse(JSON.writeValueAsString(requests.get(1)).contains("SIBLING_LARGE_PROOF"));
        assertTrue(JSON.writeValueAsString(requests.get(1)).length() < 80000, "Repeated receipts should not dominate provider context");
        int rawOutputs = 0;
        try (java.util.stream.Stream<Path> paths = Files.list(audit)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!path.getFileName().toString().matches(".*-tool-[0-9]+-output\\.json")) continue;
                String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertTrue(raw.contains("SIBLING_LARGE_PROOF"));
                assertTrue(raw.contains("EXACT_SOURCE_BODY_"));
                rawOutputs++;
            }
        }
        assertEquals(16, rawOutputs);
        assertEquals(16L, ((Number) result.get("toolCalls")).longValue());
    }

    @Test void invalidOldAndWrongRequestReceiptsNeverReachAnotherProviderView() throws Exception {
        for (Map<String,Object> mutation : Arrays.asList(map("requestId", "wrong-request"), map("targetEvidenceRevision", "old-revision"),
                map("serverPlanFingerprint", "wrong-plan"), map("targetObligationIds", Collections.singletonList("B")),
                map("targetObligationId", "A"))) {
            List<Map<String,Object>> requests = new ArrayList<>();
            Map<String,Object> hostOutput = evaluated("new-revision", "S1");
            object(hostOutput.get("evaluatedTargetReceipt")).putAll(mutation);
            Map<String,Object> result = run((request, timeout) -> {
                requests.add(request);
                return response(call("invalid", "read_section", map("sourceId", "S1", "targetObligationId", "B")));
            }, (name, args) -> hostOutput);
            assertEquals("HOST_TARGET_RECEIPT_INVALID", result.get("errorCode"), mutation.toString());
            assertEquals(1, requests.size());
            assertFalse(hostState(requests.get(0)).containsKey("latestComputationReceipt"));
        }
    }

    private interface Callback { Map<String,Object> call(String name, Map<String,Object> args); }

    private Map<String,Object> run(JavaAgentRunner.ModelTransport model, Callback callback) throws Exception {
        Map<String,Object> context = partition("initial-revision");
        context.putAll(map("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", Arrays.asList("A", "B")));
        SdkAgentInvestigator sdk = new SdkAgentInvestigator(true, "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "synthetic-key", "synthetic", 15, 1, null, model);
        java.lang.reflect.Field auditDirectory = SdkAgentInvestigator.class.getDeclaredField("auditDirectory");
        auditDirectory.setAccessible(true);
        auditDirectory.set(sdk, audit.toString());
        return sdk.investigate(map("type", "start", "protocol", "bidcheck-agent-1", "requestId", "receipt-context-test", "context", context,
            "tools", Arrays.asList(tool("read_section", map("sourceId", map("type", "string"), "targetObligationId", map("type", "string"))),
                tool("recompute_check", map()))), callback::call, event -> {});
    }

    private static Map<String,Object> partition(String revision) {
        return map("status", "COMPLETED", "targetReceiptAuthority", "CURRENT_SERVER_PROOF", "serverPlanRevision", "plan-1",
            "serverPlanFingerprint", "fingerprint-1", "targetEvidenceRevision", revision, "targetEvidenceRevisions", map("A", revision, "B", "B-1"),
            "completedTargetObligationIds", Collections.singletonList("A"), "remainingTargetObligationIds", Collections.singletonList("B"),
            "businessCheckCompleted", false, "targetCheckCompleted", false);
    }

    private static Map<String,Object> evaluated(String revision, String sourceId) {
        Map<String,Object> result = partition(revision);
        Map<String,Object> receipt = new LinkedHashMap<>(result);
        String largeProof = "SIBLING_LARGE_PROOF " + cn.bidcheck.compat.Java8Api.repeat("synthetic evidence ", 4000);
        receipt.putAll(map("kind", "HOST_TARGET_RECOMPUTE", "requestId", "receipt-context-test", "tool", "read_section", "targetObligationId", "B",
            "targetObligationIds", Arrays.asList("A", "B"), "obligations", Arrays.asList(
                map("id", "A", "status", "PASS", "gaps", Collections.emptyList(), "proof", largeProof),
                map("id", "B", "status", "UNDETERMINED", "gaps", Collections.singletonList(map("code", "CONTRACT_REQUIRED")), "proof", "ACTIVE_PROOF")),
            "expectedFactContracts", Arrays.asList(map("obligationId", "A", "contractBody", largeProof), map("obligationId", "B", "kind", "CONTRACT"))));
        result.put("evaluatedTargetReceipt", receipt);
        result.put("sources", Collections.singletonList(map("id", sourceId, "quote", "EXACT_SOURCE_BODY_" + sourceId)));
        return result;
    }

    private static Map<String,Object> tool(String name, Map<String,Object> properties) {
        return map("name", name, "description", "synthetic tool", "parameters", map("type", "object", "properties", properties, "additionalProperties", false));
    }
    private static Map<String,Object> call(String id, String name, Map<String,Object> args) throws Exception {
        return map("id", id, "type", "function", "function", map("name", name, "arguments", JSON.writeValueAsString(args)));
    }
    @SafeVarargs private static Map<String,Object> response(Map<String,Object>... calls) { return responseList(Arrays.asList(calls)); }
    private static Map<String,Object> responseList(List<Map<String,Object>> calls) {
        return map("choices", Collections.singletonList(map("message", map("role", "assistant", "tool_calls", calls))));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return value instanceof Map ? (Map<String,Object>) value : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return value instanceof List ? (List<Object>) value : Collections.emptyList(); }
    private static Map<String,Object> hostState(Map<String,Object> request) throws Exception {
        Map<String,Object> input = JSON.readValue((String) object(list(request.get("messages")).get(1)).get("content"), Map.class);
        return object(input.get("currentHostState"));
    }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}

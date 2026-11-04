package cn.bidcheck.verification.agent;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic host navigation references, with assertions outside model callbacks. */
class ContractPacketNavigationRunnerTest {
    @Test void unrankedHostPacketPagesAreReadBeforeModelWithinOriginalLimit() {
        List<String> reads = new ArrayList<>();
        Map<String,Object> result = run(17, "plan", reads);
        assertEquals(16, reads.size());
        assertEquals("S2", reads.get(0));
        assertEquals("S17", reads.get(15));
        assertFalse(reads.contains("S18"), "The unread tail must remain unproven at the limit");
        assertFalse(reads.contains("foreign"));
        assertFalse(reads.contains("old-version"));
        assertEquals(false, result.get("businessCheckCompleted"));
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
    }

    @Test void wrongPlanNavigationCannotIntroduceARead() {
        List<String> reads = new ArrayList<>();
        run(2, "wrong-plan", reads);
        assertEquals(Collections.emptyList(), reads);
    }

    private Map<String,Object> run(int count, String navigationPlan, List<String> reads) {
        Map<String,Object> row = map("separatorTitle", "Synthetic Alpha", "documentId", "doc", "documentHash", "hash",
            "documentVersion", 1, "inventoryPage", 1, "proofPageStart", 2, "proofPageEnd", 20);
        Map<String,Object> context = map("workflowPolicy", "TARGET_FACTS_V1", "canonicalTargetPartition", true,
            "targetObligationIds", Collections.singletonList("row"), "targetEvidenceRevisions", map("row", "revision"),
            "serverPlanRevision", "plan", "targetObligations", Collections.singletonList(map("id", "row", "predicate", "CONTRACT",
                "parameters", map("readScopePolicy", "EXACT_DECLARED_ROW_AND_CONTRACT_PACKET", "declaredPerformanceRow", row))));
        SdkAgentInvestigator bridge = new SdkAgentInvestigator(true, "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "synthetic-offline-key", "synthetic", 15, 1, null,
            (request, timeout) -> map("choices", Collections.singletonList(map("message", map("role", "assistant", "content", "", "tool_calls", Collections.emptyList())))));
        return bridge.investigate(map("type", "start", "protocol", "bidcheck-agent-1", "requestId", "synthetic-packet",
            "context", context, "tools", Arrays.asList(tool("search_evidence", "query"), tool("read_section", "sourceId"))),
            (name, args) -> {
                if (name.equals("search_evidence")) {
                    List<Map<String,Object>> refs = new ArrayList<>();
                    Map<String,Object> foreign = source("foreign", 2); foreign.put("documentId", "foreign"); refs.add(foreign);
                    Map<String,Object> old = source("old-version", 2); old.put("documentVersion", 0); refs.add(old);
                    for (int i = 2; i < count + 2; i++) refs.add(source("S" + i, i));
                    return map("status", "COMPLETED", "sources", Collections.emptyList(),
                        "contractPacketNavigation", map("authority", "CURRENT_SERVER_PROOF", "targetObligationId", "row",
                            "serverPlanRevision", navigationPlan, "sources", refs));
                }
                String id = String.valueOf(args.get("sourceId"));
                reads.add(id);
                Map<String,Object> source = source(id, Integer.parseInt(id.substring(1)));
                source.put("quote", "Original synthetic body");
                return map("status", "COMPLETED", "sources", Collections.singletonList(source));
            }, event -> {});
    }

    private Map<String,Object> source(String id, int page) {
        return map("sourceId", id, "id", id, "documentId", "doc", "documentHash", "hash", "documentVersion", 1,
            "locationType", "PAGE", "location", page, "navigationOnly", true);
    }

    private Map<String,Object> tool(String name, String field) {
        return map("name", name, "description", "Synthetic tool", "parameters", map("type", "object", "properties",
            map(field, map("type", "string"), "targetObligationId", map("type", "string")),
            "required", Arrays.asList(field, "targetObligationId"), "additionalProperties", false));
    }

    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}

package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaAgentTargetTraversalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void totalModelBudgetRemainsFifteenWhileUnfinishedTargetsStayUnproven() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        String previous = System.getProperty("bidcheck.agent.max-model-calls");
        System.setProperty("bidcheck.agent.max-model-calls", "15");
        try {
            Map<String,Object> result = run(context("A", "B", "C", "D", "E", "F"), (request, timeout) -> {
                int turn = turns.incrementAndGet();
                return response(call("search" + turn, "search_evidence", map("query", "missing", "targetObligationId", active(request))));
            }, (name, args) -> map("sources", Collections.emptyList()));
            assertEquals(15, turns.get());
            assertEquals("INVESTIGATION_CALL_LIMIT", result.get("code"));
            assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
            assertEquals(false, result.get("targetCheckCompleted"));
        } finally {
            if (previous == null) System.clearProperty("bidcheck.agent.max-model-calls");
            else System.setProperty("bidcheck.agent.max-model-calls", previous);
        }
    }

    @Test void contractPageContinuationIsActuallyReadWithoutUsingInventoryAsProof() throws Exception {
        List<String> reads = new ArrayList<>();
        Map<String,Object> ctx = context("A");
        ctx.put("targetObligations", Collections.singletonList(row("A", "Alpha", 11, 12)));
        Map<String,Object> result = run(ctx, (request, timeout) -> {
            assertEquals(Arrays.asList("S11", "S11-continuation", "S12"), reads);
            assertTrue(JSON.writeValueAsString(request).contains("EXACT_END_OF_PAGE"));
            return response();
        }, (name, args) -> {
            if (name.equals("search_evidence")) return map("sources", Collections.singletonList(source("S11", 11, "snippet")),
                "omitted", Collections.singletonList(map("sourceId", "S12", "page", 12, "reason", "CHAR_LIMIT")));
            String id = (String) args.get("sourceId");
            reads.add(id);
            if (id.equals("S11")) return map("sources", Collections.singletonList(source(id, 11, "first part")),
                "partial", true, "nextSourceId", "S11-continuation");
            return map("sources", Collections.singletonList(source(id, id.equals("S12") ? 12 : 11, "EXACT_END_OF_PAGE")));
        });
        assertEquals(Arrays.asList("S11", "S11-continuation", "S12"), reads);
        assertNotEquals("FAILED", result.get("status"), result.toString());
        assertEquals(Collections.emptyList(), result.get("acceptedFactIds"));
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
    }

    @Test void proseCannotSkipAnotherUnvisitedTarget() throws Exception {
        List<String> offered = new ArrayList<>();
        Map<String,Object> result = run(context("A", "B"), (request, timeout) -> {
            offered.add(active(request));
            return response();
        }, (name, args) -> map());
        assertEquals(Arrays.asList("A", "B"), offered);
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
    }

    @Test void duplicateRoundsStayLocalAndEveryRemainingTargetIsVisited() throws Exception {
        List<String> visited = new ArrayList<>();
        AtomicInteger turns = new AtomicInteger();
        Map<String,Object> result = run(context("A", "B", "C"), (request, timeout) -> {
            int turn = turns.incrementAndGet();
            String target = active(request);
            return response(call("repeat" + turn, "search_evidence", map("query", "same", "targetObligationId", target)));
        }, (name, args) -> {
            visited.add((String) args.get("targetObligationId"));
            return map("sources", Collections.emptyList());
        });
        assertEquals(Arrays.asList("A", "B", "C"), visited, "A cached operation cannot terminate unvisited B or C");
        assertEquals(9, turns.get());
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
        assertEquals(false, result.get("targetCheckCompleted"));
    }

    @Test void staleBlockedCallsCannotConsumeAnUnvisitedTargetsThreeRounds() throws Exception {
        List<String> visited = new ArrayList<>();
        AtomicInteger turns = new AtomicInteger();
        Map<String,Object> result = run(context("A", "B"), (request, timeout) -> {
            int turn = turns.incrementAndGet();
            if (turn > 7) return response();
            return response(call("repeat" + turn, "search_evidence", map("query", "same", "targetObligationId", turn <= 6 ? "A" : "B")));
        }, (name, args) -> {
            visited.add((String) args.get("targetObligationId"));
            return map("sources", Collections.emptyList());
        });
        assertEquals(Arrays.asList("A", "B"), visited);
        assertNotEquals("FAILED", result.get("status"));
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
    }

    @Test void proseForOneRowDoesNotSkipOtherDeclaredContractRows() throws Exception {
        List<String> visited = new ArrayList<>();
        Map<String,Object> ctx = context("A", "B");
        ctx.put("targetObligations", Arrays.asList(row("A", "Alpha", 11, 12), row("B", "Beta", 21, 22)));
        Map<String,Object> result = run(ctx, (request, timeout) -> response(), (name, args) -> {
            if (name.equals("search_evidence")) visited.add((String) args.get("targetObligationId"));
            return map("sources", Collections.emptyList());
        });
        assertEquals(Arrays.asList("A", "B"), visited);
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
        assertEquals(false, result.get("targetCheckCompleted"));
    }

    @Test void eachRowReadsItsActualContractPagesBeforeFirstModelProposal() throws Exception {
        List<String> reads = new ArrayList<>();
        Map<String,Object> ctx = context("A", "B");
        ctx.put("targetObligations", Arrays.asList(row("A", "Alpha", 11, 12), row("B", "Beta", 21, 22)));
        AtomicInteger turns = new AtomicInteger();
        Map<String,Object> result = run(ctx, (request, timeout) -> {
            String target = active(request);
            int first = target.equals("A") ? 11 : 21;
            assertTrue(reads.contains(target + ":S" + first), "Contract page must actually be read before model: " + target);
            assertTrue(reads.contains(target + ":S" + (first + 1)), "Second contract page must be read");
            String encoded = JSON.writeValueAsString(request);
            assertTrue(encoded.contains("CONTRACT_BODY_" + first));
            assertTrue(encoded.contains("CONTRACT_BODY_" + (first + 1)));
            assertFalse(reads.contains(target + ":INVENTORY"));
            assertFalse(reads.contains(target + ":OUTSIDE"));
            assertFalse(reads.contains(target + ":OTHER_DOCUMENT"));
            turns.incrementAndGet();
            return response();
        }, (name, args) -> {
            String target = (String) args.get("targetObligationId");
            int first = target.equals("A") ? 11 : 21;
            if (name.equals("search_evidence")) {
                assertTrue(((String) args.get("query")).contains(target.equals("A") ? "Alpha" : "Beta"));
                Map<String,Object> otherDocument = source("OTHER_DOCUMENT", first, "wrong document");
                otherDocument.put("documentId", "other");
                return map("sources", Arrays.asList(source("INVENTORY", 1, "declaration only"),
                    source("S" + first, first, "snippet"), source("S" + (first + 1), first + 1, "snippet"),
                    source("OUTSIDE", 90, "outside packet"), otherDocument));
            }
            assertEquals("read_section", name);
            String sourceId = (String) args.get("sourceId");
            reads.add(target + ":" + sourceId);
            int page = Integer.parseInt(sourceId.substring(1));
            return map("sources", Collections.singletonList(source(sourceId, page, "CONTRACT_BODY_" + page)));
        });
        assertEquals(2, turns.get());
        assertEquals(Arrays.asList("A:S11", "A:S12", "B:S21", "B:S22"), reads);
        assertEquals(Collections.emptyList(), result.get("acceptedFactIds"));
        assertEquals(Collections.emptyList(), result.get("completedTargetObligationIds"));
    }

    private static Map<String,Object> row(String id, String title, int first, int last) {
        return map("id", id, "predicate", "CONTRACT", "parameters", map("readScopePolicy", "EXACT_DECLARED_ROW_AND_CONTRACT_PACKET",
            "declaredPerformanceRow", map("separatorTitle", title, "documentId", "synthetic-document", "documentHash", "synthetic-hash",
                "documentVersion", 1, "inventoryPage", 1, "row", 1, "proofPageStart", first, "proofPageEnd", last)));
    }

    private static Map<String,Object> source(String id, int page, String text) {
        return map("id", id, "documentId", "synthetic-document", "locationType", "PAGE", "location", page, "quote", text);
    }

    private interface Callback { Map<String,Object> call(String name, Map<String,Object> args); }

    private static Map<String,Object> run(Map<String,Object> ctx, JavaAgentRunner.ModelTransport model, Callback callback) throws Exception {
        SdkAgentInvestigator bridge = new SdkAgentInvestigator(true,
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "synthetic-offline-key", "synthetic", 15, 1, null, model);
        return bridge.investigate(map("type", "start", "protocol", "bidcheck-agent-1", "requestId", "synthetic-target-traversal",
            "context", ctx, "tools", Arrays.asList(tool("search_evidence", "query"), tool("read_section", "sourceId"))),
            callback::call, event -> {});
    }

    private static Map<String,Object> context(String... targets) {
        Map<String,Object> revisions = new LinkedHashMap<>();
        for (String target : targets) revisions.put(target, "revision-" + target);
        return map("workflowPolicy", "TARGET_FACTS_V1", "canonicalTargetPartition", true,
            "targetObligationIds", Arrays.asList(targets), "targetEvidenceRevisions", revisions, "serverPlanRevision", "synthetic-plan");
    }

    @SuppressWarnings("unchecked") private static String active(Map<String,Object> request) throws Exception {
        List<Map<String,Object>> messages = (List<Map<String,Object>>) request.get("messages");
        Map<String,Object> input = JSON.readValue((String) messages.get(1).get("content"), Map.class);
        return (String) ((Map<String,Object>) input.get("currentHostState")).get("activeTargetObligationId");
    }

    private static Map<String,Object> tool(String name, String field) {
        return map("name", name, "description", "Synthetic test tool", "parameters", map("type", "object",
            "properties", map(field, map("type", "string"), "targetObligationId", map("type", "string")),
            "required", Arrays.asList(field, "targetObligationId"), "additionalProperties", false));
    }

    private static Map<String,Object> call(String id, String name, Map<String,Object> args) throws Exception {
        return map("id", id, "type", "function", "function", map("name", name, "arguments", JSON.writeValueAsString(args)));
    }

    @SafeVarargs private static Map<String,Object> response(Map<String,Object>... calls) {
        return map("choices", Collections.singletonList(map("message", map("role", "assistant", "tool_calls", Arrays.asList(calls)))));
    }

    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}

package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.agent.AgentInvestigator;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic frozen packets; navigation never certifies facts or document completeness. */
class ContractPacketCoverageTest {
    @Test void packetNavigationIncludesUnrankedPageButDoesNotMarkItRead() {
        EvidenceInvestigation.Host host = host("Signing text on a separate page");
        Map<String,Object> output = host.call("search_evidence", linked("targetObligationId", "row", "query", "Synthetic Alpha"));
        Map<String,Object> packet = object(output.get("contractPacketNavigation"));
        assertEquals("CURRENT_SERVER_PROOF", packet.get("authority"));
        List<Map<String,Object>> refs = RuleEvaluator.maps(packet.get("sources"));
        assertEquals(Arrays.asList(2, 3, 4), pages(refs));
        assertTrue(refs.stream().allMatch(ref -> Boolean.TRUE.equals(ref.get("navigationOnly"))));
        assertTrue(refs.stream().noneMatch(ref -> ref.containsKey("quote")));
        assertEquals(false, object(packet.get("coverage")).get("complete"));
        assertEquals(Arrays.asList(2, 3, 4), object(packet.get("coverage")).get("pendingPages"));
        assertTrue(host.facts.isEmpty());
    }

    @Test void originalReadCompletesCoverageWithoutCompletingBusinessProof() {
        EvidenceInvestigation.Host host = host("Signing text on a separate page");
        Map<String,Object> search = host.call("search_evidence", linked("targetObligationId", "row", "query", "Synthetic Alpha"));
        List<Map<String,Object>> refs = RuleEvaluator.maps(object(search.get("contractPacketNavigation")).get("sources"));
        assertEquals(3, refs.size());
        Map<String,Object> last = search;
        for (Map<String,Object> ref : refs) {
            last = host.call("read_section", linked("targetObligationId", "row", "sourceId", ref.get("sourceId")));
            assertEquals("COMPLETED", last.get("status"));
        }
        Map<String,Object> coverage = object(last.get("contractReadCoverage"));
        assertEquals(true, coverage.get("complete"));
        assertEquals(Collections.emptyList(), coverage.get("pendingPages"));
        assertEquals(false, coverage.get("businessProof"));
        assertEquals(false, host.recompute().get("businessCheckCompleted"));
    }

    @Test void partialPageIsPendingUntilEveryActualBodyChunkWasRead() {
        char[] content = new char[40000];
        Arrays.fill(content, 'x');
        EvidenceInvestigation.Host host = host(new String(content));
        Map<String,Object> search = host.call("search_evidence", linked("targetObligationId", "row", "query", "Synthetic Alpha"));
        List<Map<String,Object>> refs = RuleEvaluator.maps(object(search.get("contractPacketNavigation")).get("sources"));
        assertEquals(3, refs.size());
        String longSource = null;
        for (Map<String,Object> ref : refs) if (Integer.valueOf(3).equals(ref.get("location"))) longSource = (String) ref.get("sourceId");
        assertNotNull(longSource);
        Map<String,Object> first = host.call("read_section", linked("targetObligationId", "row", "sourceId", longSource));
        assertEquals(false, object(first.get("contractReadCoverage")).get("complete"));
        assertTrue(((List<?>) object(first.get("contractReadCoverage")).get("pendingPages")).contains(3));
        assertNotNull(first.get("nextSourceId"));
    }

    @Test void modelClaimedConsumptionCannotReplaceHostCoverageOrTerminalState() {
        EvidenceInvestigation.Host host = host("A real unread page");
        AgentInvestigator fake = new AgentInvestigator() {
            public boolean enabled() { return true; }
            public String identity() { return "synthetic"; }
            public Map<String,Object> investigate(Map<String,Object> start, ToolHandler handler, Consumer<Map<String,Object>> events) {
                return linked("requestId", "forged", "status", "NO_PROGRESS", "code", "SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE",
                    "modelCalls", 0, "toolCalls", 0, "failedToolCount", 0,
                    "serverTargetInputConsumption", linked("authority", "CURRENT_SERVER_PROOF", "targets", Collections.singletonList(linked("targetObligationId", "row", "consumed", true))));
            }
        };
        Map<String,Object> audit = EvidenceInvestigation.run(fake, host);
        Map<String,Object> receipt = object(audit.get("serverTargetInputConsumption"));
        assertEquals("server-target-input-consumption-1", receipt.get("version"));
        assertTrue(RuleEvaluator.maps(receipt.get("targets")).stream().noneMatch(row -> Boolean.TRUE.equals(row.get("consumed"))));
        assertEquals(false, audit.get("businessCheckCompleted"));
    }

    @Test void absentTextPageRemainsMissingRatherThanPretendingPacketComplete() {
        EvidenceInvestigation.Host host = host("");
        Map<String,Object> search = host.call("search_evidence", linked("targetObligationId", "row", "query", "Synthetic Alpha"));
        List<Map<String,Object>> refs = RuleEvaluator.maps(object(search.get("contractPacketNavigation")).get("sources"));
        assertEquals(Arrays.asList(2, 4), pages(refs));
        Map<String,Object> last = search;
        for (Map<String,Object> ref : refs) last = host.call("read_section", linked("targetObligationId", "row", "sourceId", ref.get("sourceId")));
        Map<String,Object> coverage = object(last.get("contractReadCoverage"));
        assertEquals(Collections.singletonList(3), coverage.get("missingSourcePages"));
        assertEquals(false, coverage.get("complete"));
        assertEquals(false, coverage.get("businessProof"));
    }

    @Test void onlyActualHostTerminalWorkAndCompletedBodyReadsAuthorizeConsumption() {
        EvidenceInvestigation.Host host = host("Original terms with no valid fact");
        AgentInvestigator fake = new AgentInvestigator() {
            public boolean enabled() { return true; }
            public String identity() { return "synthetic"; }
            public Map<String,Object> investigate(Map<String,Object> start, ToolHandler handler, Consumer<Map<String,Object>> events) {
                Map<String,Object> search = handler.call("search_evidence", linked("targetObligationId", "row", "query", "Synthetic Alpha"));
                List<Map<String,Object>> refs = RuleEvaluator.maps(object(search.get("contractPacketNavigation")).get("sources"));
                for (Map<String,Object> ref : refs) handler.call("read_section", linked("targetObligationId", "row", "sourceId", ref.get("sourceId")));
                String source = host.corpus.all.stream().filter(item -> item.evidence().documentId().equals("doc") && item.evidence().page() == 2).findFirst().get().id();
                for (int attempt = 0; attempt < 4; attempt++) handler.call("submit_candidate_fact", linked("targetObligationId", "row",
                    "fact", linked("kind", "CONTRACT", "field", "contractName", "rawValue", "Synthetic Alpha", "value", "Synthetic Alpha",
                        "scope", "HISTORICAL_PROJECT", "sourceIds", Collections.singletonList(source), "attributes", linked())));
                return linked("requestId", start.get("requestId"), "status", "NO_PROGRESS", "code", "SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE",
                    "modelCalls", 0, "toolCalls", 8, "failedToolCount", 0);
            }
        };
        Map<String,Object> audit = EvidenceInvestigation.run(fake, host);
        List<Map<String,Object>> rows = RuleEvaluator.maps(object(audit.get("serverTargetInputConsumption")).get("targets"));
        assertEquals(1, rows.size());
        assertEquals(true, rows.get(0).get("readCoverageComplete"));
        assertEquals(true, rows.get(0).get("terminalForRevision"));
        assertEquals(true, rows.get(0).get("consumed"));
        assertEquals(false, audit.get("businessCheckCompleted"));
    }

    private EvidenceInvestigation.Host host(String middle) {
        List<Page> pages = Arrays.asList(new Page(1, "Inventory only", "PAGE", "TEXT", ""),
            new Page(2, "Synthetic Alpha original cover", "PAGE", "TEXT", ""),
            new Page(3, middle, "PAGE", "TEXT", ""), new Page(4, "Other original terms", "PAGE", "TEXT", ""),
            new Page(5, "Foreign row", "PAGE", "TEXT", ""));
        Document doc = new Document("doc", "project", "BID", "synthetic.pdf", 1, "frozen-hash", "", pages, Collections.emptyList());
        Document foreign = new Document("foreign", "project", "BID", "foreign.pdf", 1, "foreign-hash", "", pages, Collections.emptyList());
        EvidenceCorpus corpus = new EvidenceCorpus(Arrays.asList(doc, foreign));
        Rule rule = new Rule("row", "synthetic packet", "CONTRACT", "", "", "", "", "",
            linked("declaredPerformanceRow", linked("documentId", "doc", "documentHash", "frozen-hash", "documentVersion", 1,
                "separatorTitle", "Synthetic Alpha", "inventoryPage", 1, "proofPageStart", 2, "proofPageEnd", 4)), "", "", Collections.emptyList());
        Plan plan = new Plan("ALL", Collections.singletonList(rule), "SYNTHETIC", Collections.emptyList());
        Map<String,Object> context = new LinkedHashMap<>();
        Checkpoint cp = new Checkpoint("cp", "synthetic", "test", "HIGH", "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null);
        return new EvidenceInvestigation.Host(cp, plan, plan.rules(), corpus, new LinkedHashMap<>(), context,
            new SharedFactReuse(corpus, context, "synthetic", "synthetic"), new RunSession(ModelGateway.disabled(), context, "synthetic"), null,
            new ArrayList<>(), Collections.emptySet());
    }

    private List<Integer> pages(List<Map<String,Object>> refs) {
        List<Integer> pages = new ArrayList<>();
        for (Map<String,Object> ref : refs) pages.add(((Number) ref.get("location")).intValue());
        return pages;
    }

    @SuppressWarnings("unchecked") private Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>) value : Collections.emptyMap();
    }
}

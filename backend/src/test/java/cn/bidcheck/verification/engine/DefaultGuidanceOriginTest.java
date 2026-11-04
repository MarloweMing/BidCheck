package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.verification.agent.AgentInvestigator;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class DefaultGuidanceOriginTest {
    @Test void stateKeepsActualValidationFamilyUntilEvidenceChanges() {
        AgentTargetWorkflow workflow = new AgentTargetWorkflow();
        Rule rule = rule();
        assertEquals("DEFAULT_GUIDANCE", workflow.state(rule, "entity", "v1").get("repairActionOrigin"));
        workflow.observe(rule, "entity", "v1", Collections.singletonList(linked("code", "SUBJECT_BINDING_REQUIRED")));
        Map<String,Object> state = workflow.state(rule, "entity", "v1");
        assertEquals("VALIDATION_REPAIR", state.get("repairActionOrigin"));
        assertEquals("SUBJECT_OR_RECORD_BINDING", state.get("failureFamily"));
        assertEquals(Collections.singletonList("RESOLVE_SUBJECT_FIRST"), state.get("remainingRepairActions"));
        assertEquals(false, state.get("terminalForRevision"));
        assertEquals("DEFAULT_GUIDANCE", workflow.state(rule, "entity", "v2").get("repairActionOrigin"));
    }

    @Test void hostRebuildsOriginInsteadOfTrustingModelClaim() {
        EvidenceInvestigation.Host host = host("Original neutral text", Collections.emptySet());
        AgentInvestigator fake = investigator((start, handler) -> {
            handler.call("submit_candidate_fact", linked("targetObligationId", "target"));
            Map<String,Object> result = stopped(start);
            result.put("serverTargetInputConsumption", linked("repairActionOriginVersion", "host-repair-action-origin-1", "targets",
                Collections.singletonList(linked("targetObligationId", "target", "repairActionOrigin", "DEFAULT_GUIDANCE", "remainingRepairActions", Collections.emptyList()))));
            return result;
        });
        Map<String,Object> work = target(EvidenceInvestigation.run(fake, host));
        assertEquals("VALIDATION_REPAIR", work.get("repairActionOrigin"));
        assertFalse(((Collection<?>) work.get("remainingRepairActions")).isEmpty());
        assertEquals(false, work.get("consumed"));
    }

    @Test void longPageTailRemainsPendingUntilActualContinuationRead() {
        char[] chars = new char[40000]; Arrays.fill(chars, 'x');
        EvidenceInvestigation.Host host = host(new String(chars), Collections.emptySet());
        Map<String,Object> audit = EvidenceInvestigation.run(investigator((start, handler) -> {
            Source source = host.corpus.all.get(0); host.navigation.put(source.id(), source);
            Map<String,Object> read = handler.call("read_section", linked("targetObligationId", "target", "sourceId", source.id()));
            assertNotNull(read.get("nextSourceId"));
            return stopped(start);
        }), host);
        assertEquals(false, target(audit).get("readCoverageComplete"));
        assertFalse(((Collection<?>) target(audit).get("pendingReadActions")).isEmpty());
        EvidenceInvestigation.Host complete = host(new String(chars), Collections.emptySet());
        Map<String,Object> done = EvidenceInvestigation.run(investigator((start, handler) -> {
            Source source = complete.corpus.all.get(0); complete.navigation.put(source.id(), source);
            String next = source.id();
            for (int n = 0; !next.isEmpty() && n < 8; n++) {
                Map<String,Object> read = handler.call("read_section", linked("targetObligationId", "target", "sourceId", next));
                next = str(read, "nextSourceId");
            }
            return stopped(start);
        }), complete);
        assertEquals(true, target(done).get("readCoverageComplete"));
        assertEquals(Collections.emptyList(), target(done).get("pendingReadActions"));
    }

    @Test void tableContinuationNeedsActualRemainingRowsNotAnotherBodyRead() {
        EvidenceInvestigation.Host host = tableHost();
        Map<String,Object> audit = EvidenceInvestigation.run(investigator((start, handler) -> {
            Source source = host.corpus.all.get(0); host.navigation.put(source.id(), source);
            Map<String,Object> read = handler.call("read_table_record", linked("targetObligationId", "target", "sourceId", source.id()));
            assertEquals(80, read.get("nextRowOffset"));
            handler.call("read_section", linked("targetObligationId", "target", "sourceId", source.id()));
            return stopped(start);
        }), host);
        assertEquals(false, target(audit).get("readCoverageComplete"));
        assertTrue(target(audit).get("pendingReadActions").toString().contains("80"));
        EvidenceInvestigation.Host complete = tableHost();
        Map<String,Object> done = EvidenceInvestigation.run(investigator((start, handler) -> {
            Source source = complete.corpus.all.get(0); complete.navigation.put(source.id(), source);
            handler.call("read_table_record", linked("targetObligationId", "target", "sourceId", source.id(), "rowOffset", 80));
            handler.call("read_table_record", linked("targetObligationId", "target", "sourceId", source.id(), "rowOffset", 0));
            return stopped(start);
        }), complete);
        assertEquals(true, target(done).get("readCoverageComplete"));
    }

    @Test void searchPartialPageIsConcreteUnreadWorkEvenBeforeExplicitRead() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5000; i++) text.append("needle neutral text ");
        EvidenceInvestigation.Host host = host(text.toString(), Collections.emptySet());
        Map<String,Object> audit = EvidenceInvestigation.run(investigator((start, handler) -> {
            Map<String,Object> result = handler.call("search_evidence", linked("targetObligationId", "target", "query", "needle"));
            assertEquals(true, result.get("partial"));
            assertFalse(((Collection<?>) result.get("sourceIds")).isEmpty());
            return stopped(start);
        }), host);
        assertEquals(false, target(audit).get("readCoverageComplete"));
        assertFalse(((Collection<?>) target(audit).get("pendingReadActions")).isEmpty());
    }

    @Test void initialIncompleteReadIsNotLostByDefaultOrigin() {
        EvidenceInvestigation.Host host = host("Original neutral text", Collections.singleton("initial-source"));
        Map<String,Object> audit = EvidenceInvestigation.run(investigator((start, handler) -> stopped(start)), host);
        assertEquals(false, target(audit).get("readCoverageComplete"));
        assertTrue(target(audit).get("pendingReadActions").toString().contains("INITIAL_INCOMPLETE_READ"));
    }

    private EvidenceInvestigation.Host tableHost() {
        EvidenceInvestigation.Host host = host("Synthetic table original text", Collections.emptySet());
        host.ctx.put("layoutLookup", (Function<Map<String,Object>,Object>) args -> {
            List<Map<String,Object>> cells = new ArrayList<>();
            for (int i = 0; i < 81; i++) cells.add(linked("documentId", "doc", "documentHash", "hash", "documentVersion", 1,
                "locationType", "PAGE", "location", 1, "kind", "TABLE_CELL", "spanId", "cell-" + i,
                "text", "neutral", "row", i, "column", 1));
            return cells;
        });
        return host;
    }
    private Rule rule() { return new Rule("target", "Synthetic", "DECLARATION", "", "commitment", "EXISTS", "commitment", "", linked(), "", "", Collections.emptyList()); }
    private EvidenceInvestigation.Host host(String text, Set<String> incomplete) {
        Document doc = new Document("doc", "project", "BID", "synthetic.pdf", 1, "hash", "", Collections.singletonList(new Page(1, text, "PAGE", "TEXT", "")), Collections.emptyList());
        EvidenceCorpus corpus = new EvidenceCorpus(Collections.singletonList(doc));
        Plan plan = new Plan("ALL", Collections.singletonList(rule()), "SYNTHETIC", Collections.emptyList());
        Map<String,Object> context = new LinkedHashMap<>();
        Checkpoint cp = new Checkpoint("cp", "Synthetic", "test", "HIGH", "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null);
        return new EvidenceInvestigation.Host(cp, plan, plan.rules(), corpus, new LinkedHashMap<>(), context,
            new SharedFactReuse(corpus, context, "synthetic", "synthetic"), new RunSession(ModelGateway.disabled(), context, "synthetic"), null, new ArrayList<>(), incomplete);
    }
    private interface Run { Map<String,Object> apply(Map<String,Object> start, AgentInvestigator.ToolHandler handler); }
    private AgentInvestigator investigator(Run run) { return new AgentInvestigator() {
        public boolean enabled() { return true; }
        public String identity() { return "synthetic"; }
        public Map<String,Object> investigate(Map<String,Object> start, ToolHandler handler, Consumer<Map<String,Object>> events) { return run.apply(start, handler); }
    }; }
    private Map<String,Object> stopped(Map<String,Object> start) {
        return linked("requestId", start.get("requestId"), "status", "NO_PROGRESS", "code", "REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", "failedToolCount", 0,
            "targetProgressLedger", Collections.singletonList(linked("targetObligationId", "target", "visited", true, "navigationCount", 3, "noEvidenceProgressRounds", 3)));
    }
    private Map<String,Object> target(Map<String,Object> audit) {
        return RuleEvaluator.maps(((Map<?,?>) audit.get("serverTargetInputConsumption")).get("targets")).get(0);
    }
}

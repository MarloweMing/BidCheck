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

    @Test void ninthRankedCommitmentIsActuallyReadAndReturnedToTheFirstSearchWithoutProvingAnAppointment() {
        List<Page> pages=new ArrayList<>();
        for(int n=1;n<=7;n++)pages.add(new Page(n,(n==1?"投标文件\n项目名称：办公楼改造工程\n":"")+"对照文本 证明材料 参考记录。原文编号 "+n,"PAGE","TEXT",""));
        pages.add(new Page(457,"联合体协议书\n项目名称：办公楼改造工程\n两方自愿组成联合体共同投标，甲公司为牵头人。","PAGE","TEXT",""));
        pages.add(new Page(475,"对照文本。我方承诺拟委派关键岗位人员未超出同时承接项目的最大数量。","PAGE","TEXT",""));
        Rule appointment=new Rule("target","委派责任","RELATION","李明","委派","EXISTS","牵头单位","",linked(),"","",Collections.emptyList());
        EvidenceInvestigation.Host host=host(pages,Collections.emptySet(),appointment);
        Map<String,Object> audit=EvidenceInvestigation.run(investigator((s,h)->{
            Map<String,Object> found=h.call("search_evidence",linked("targetObligationId","target","query","对照文本 证明材料 参考记录"));
            assertTrue(RuleEvaluator.maps(found.get("sources")).stream().anyMatch(row->Integer.valueOf(475).equals(row.get("location"))
                && str(row,"quote").contains("最大数量")));
            return stopped(s);
        }),host);
        List<Map<String,Object>> records=RuleEvaluator.maps(audit.get("sectionContinuationExecutions"));
        assertTrue(records.stream().anyMatch(r->Integer.valueOf(475).equals(r.get("page")) && Boolean.TRUE.equals(r.get("cursorRead"))
            && "SEARCH_PAGE_LIMIT".equals(r.get("phase")) && Boolean.FALSE.equals(r.get("businessProof"))));
        assertTrue(ProofVerifier.strings(audit.get("remainingTargetObligationIds")).contains("target"));
    }

    @Test void exactSectionCursorResumesAcrossSessionsAndAChangedVersionOrUnreadableSourceNeverCountsAsRead() {
        List<Page> pages=Collections.singletonList(new Page(475,"我方承诺拟委派关键岗位人员未超出同时承接项目的最大数量。","PAGE","TEXT",""));
        for(String variant:Arrays.asList("legacy","current","sourceChanged","readFails")){
            EvidenceInvestigation.Host host=host(pages,Collections.emptySet());
            Source original=host.corpus.all.get(0);
            Map<String,Object> authority=EvidenceInvestigation.run(investigator((s,h)->stopped(s)),host);
            Map<String,Object> row=target(authority);
            Map<String,Object> cursor=linked("action","READ_SECTION_CONTINUATION","sourceId",original.id(),"documentId","doc","page",475);
            if(!"legacy".equals(variant))cursor.putAll(linked("documentHash","sourceChanged".equals(variant)?"other-version":"hash",
                "documentVersion",1,"locationType","PAGE","unreadSourceIds",Collections.singletonList(original.id())));
            row.put("pendingReadActions",Collections.singletonList(cursor));row.put("readCoverageComplete",false);
            ((Map<String,Object>)authority.get("serverTargetInputConsumption")).put("targets",Collections.singletonList(row));
            EvidenceInvestigation.Host next=host(pages,Collections.emptySet());
            next.ctx.put("previousAtomicCheck",linked("agentInvestigation",authority));
            if("readFails".equals(variant))next.corpus.all.clear(); // The id remains navigable, its original cannot be opened.
            Map<String,Object> result=EvidenceInvestigation.run(investigator((s,h)->stopped(s)),next);
            List<Map<String,Object>> attempts=RuleEvaluator.maps(result.get("sectionContinuationExecutions"));
            assertFalse(attempts.isEmpty(),variant);
            if("sourceChanged".equals(variant))assertEquals("SOURCE_CHANGED_SELECT_AGAIN",attempts.get(0).get("status"));
            else if("readFails".equals(variant)){
                assertFalse((Boolean)attempts.get(0).get("cursorRead"));
                assertFalse(RuleEvaluator.maps(target(result).get("pendingReadActions")).isEmpty());
            }else{
                assertEquals("RESUME",attempts.get(0).get("phase"));
                assertTrue((Boolean)attempts.get(0).get("cursorRead"));
                assertTrue(RuleEvaluator.maps(target(result).get("pendingReadActions")).isEmpty());
            }
            assertTrue(ProofVerifier.strings(result.get("remainingTargetObligationIds")).contains("target"));
        }
    }

    @Test void hostReadsRemainingTableCellsOnTheFirstStoppedInvestigationWithoutCreditingBusinessProgress() {
        EvidenceInvestigation.Host host=tableHost();
        Map<String,Object> audit=readFirst80(host,true);
        List<Map<String,Object>> executions=RuleEvaluator.maps(audit.get("tableContinuationExecutions"));
        assertEquals(1,executions.size());
        assertEquals(80,executions.get(0).get("rowOffset"));
        assertEquals(1,executions.get(0).get("returnedRows"));
        assertEquals("COMPLETED",executions.get(0).get("status"));
        assertEquals(false,executions.get(0).get("businessProof"));
        assertEquals(Collections.emptyList(),target(audit).get("pendingReadActions"));
        assertTrue(ProofVerifier.strings(audit.get("remainingTargetObligationIds")).contains("target"));
        assertEquals(0,audit.get("acceptedFactCount"));
    }

    @Test void failedHostTableReadPersistsItsActualOffsetAndResumesBeforeTheNextSdkSession() {
        EvidenceInvestigation.Host first=tableHost();
        Function<Map<String,Object>,Object> healthy=(Function<Map<String,Object>,Object>)first.ctx.get("layoutLookup");
        int[] reads={0};first.ctx.put("layoutLookup",(Function<Map<String,Object>,Object>) request->{
            if(++reads[0]>1)throw new IllegalStateException("test layout store unavailable");
            return healthy.apply(request);
        });
        Map<String,Object> audit=readFirst80(first,true);
        assertEquals("FAILED",RuleEvaluator.maps(audit.get("tableContinuationExecutions")).get(0).get("status"));
        assertEquals(80,RuleEvaluator.maps(target(audit).get("pendingReadActions")).get(0).get("rowOffset"));
        EvidenceInvestigation.Host next=tableHost();next.ctx.put("previousAtomicCheck",linked("agentInvestigation",audit));
        Map<String,Object> resumed=EvidenceInvestigation.run(investigator((s,h)->stopped(s)),next);
        Map<String,Object> tail=RuleEvaluator.maps(resumed.get("tableContinuationExecutions")).get(0);
        assertEquals("RESUME",tail.get("phase"));assertEquals(80,tail.get("rowOffset"));
        assertEquals(1,tail.get("returnedRows"));assertEquals(Collections.emptyList(),target(resumed).get("pendingReadActions"));
        assertTrue(ProofVerifier.strings(resumed.get("remainingTargetObligationIds")).contains("target"));
    }

    @Test void shorterReplacementLayoutRestartsAtZeroAndAnUnavailableTableRetainsARead() {
        Map<String,Object> original=readFirst80(tableHost(),false);
        for(boolean empty:Arrays.asList(false,true)){
            EvidenceInvestigation.Host next=tableHost();
            next.ctx.put("previousAtomicCheck",linked("agentInvestigation",original));
            Function<Map<String,Object>,Object> healthy=(Function<Map<String,Object>,Object>)next.ctx.get("layoutLookup");
            next.ctx.put("layoutLookup",(Function<Map<String,Object>,Object>) req->
                empty?Collections.emptyList():RuleEvaluator.maps(healthy.apply(req)).subList(0,2));
            Map<String,Object> audit=EvidenceInvestigation.run(investigator((s,h)->stopped(s)),next);
            List<Map<String,Object>> executions=RuleEvaluator.maps(audit.get("tableContinuationExecutions"));
            assertEquals("TABLE_LAYOUT_CHANGED",executions.get(0).get("error"));
            assertEquals(0,executions.get(1).get("rowOffset"));
            if(empty){
                assertFalse(RuleEvaluator.maps(target(audit).get("pendingReadActions")).isEmpty());
                assertEquals("NO_RECORD",executions.get(1).get("status"));
            }else{
                assertEquals(2,executions.get(1).get("returnedRows"));
                assertEquals(Collections.emptyList(),target(audit).get("pendingReadActions"));
            }
            assertTrue(ProofVerifier.strings(audit.get("remainingTargetObligationIds")).contains("target"));
        }
    }

    @Test void changedSourceVersionNeverReusesOldTableOffset() {
        Map<String,Object> original=readFirst80(tableHost(),false);
        Map<String,Object> cursor=(Map<String,Object>)((List<?>)target(original).get("pendingReadActions")).get(0);
        cursor.put("documentHash","different-frozen-source");
        EvidenceInvestigation.Host next=tableHost();next.ctx.put("previousAtomicCheck",linked("agentInvestigation",original));
        Map<String,Object> result=EvidenceInvestigation.run(investigator((s,h)->stopped(s)),next);
        assertEquals("SOURCE_CHANGED_SELECT_AGAIN",RuleEvaluator.maps(result.get("tableContinuationExecutions")).get(0).get("status"));
        assertTrue(ProofVerifier.strings(result.get("remainingTargetObligationIds")).contains("target"));
    }

    private Map<String,Object> readFirst80(EvidenceInvestigation.Host host,boolean stalled) {
        return EvidenceInvestigation.run(investigator((start,handler)->{
            Source source=host.corpus.all.get(0);host.navigation.put(source.id(),source);
            Map<String,Object> first=handler.call("read_table_record",linked("targetObligationId","target","sourceId",source.id()));
            assertEquals(80,first.get("nextRowOffset"));
            Map<String,Object> terminal=stopped(start);
            if(stalled)terminal.put("targetProgressLedger",Collections.singletonList(linked("targetObligationId","target",
                "visited",true,"noNewFactCounterVersion","java-agent-target-rounds-1","noNewFactRequestId",start.get("requestId"),
                "noNewFactStopReason","THREE_ROUNDS_NO_NEW_FACT","noNewFactModelRounds",3)));
            return terminal;
        }),host);
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
        return host(Collections.singletonList(new Page(1,text,"PAGE","TEXT","")),incomplete);
    }
    private EvidenceInvestigation.Host host(List<Page> pages, Set<String> incomplete) {
        return host(pages,incomplete,rule());
    }
    private EvidenceInvestigation.Host host(List<Page> pages, Set<String> incomplete,Rule target) {
        Document doc = new Document("doc", "project", "BID", "synthetic.pdf", 1, "hash", "", pages, Collections.emptyList());
        EvidenceCorpus corpus = new EvidenceCorpus(Collections.singletonList(doc));
        Plan plan = new Plan("ALL", Collections.singletonList(target), "SYNTHETIC", Collections.emptyList());
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

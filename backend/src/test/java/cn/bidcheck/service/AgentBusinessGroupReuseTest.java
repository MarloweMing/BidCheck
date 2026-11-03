package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentBusinessGroupReuseTest {
    private Document tender(String... bodies){List<Page> pages=new ArrayList<>();for(int i=0;i<bodies.length;i++)pages.add(new Page(i+1,bodies[i],"PARAGRAPH","DOCX",""));return new Document("t","p","TENDER","tender.docx",1,"frozen-hash","",pages,cn.bidcheck.compat.Java8Api.listOf());}
    private Checkpoint checkpoint(String id,Document tender,int page,String expected){Page p=tender.pages().get(page-1);Evidence source=new Evidence(tender.id(),tender.name(),p.number(),p.locationType(),p.text(),"p"+p.number(),1);return new Checkpoint(id,"营业执照","资格检查","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",expected,"",true,true,true,source);}

    @Test void onlyEvidenceInvestigationIsSharedForFrozenEquivalentObligations(){
        Document tender=tender("本项目不划分标段。","投标人资格要求","投标人须提交有效营业执照。","投标单位应提供有效的营业执照。");
        Checkpoint first=checkpoint("first",tender,3,"投标人须提交有效营业执照。"),second=checkpoint("second",tender,4,"投标单位应提供有效的营业执照。");
        AgentBusinessGroupReuse.Plan plan=AgentBusinessGroupReuse.plan(cn.bidcheck.compat.Java8Api.listOf(first, second),tender,cp->true);
        assertEquals(1,plan.reusableGroupCount());assertEquals(1,plan.reusedCheckpointCount());
        cn.bidcheck.service.AgentBusinessGroupReuse.Membership group=plan.membership("second");assertNotNull(group);assertEquals("first",group.leaderCheckpointId());assertFalse(group.leader("second"));
        assertEquals(false,group.audit("second").get("businessDecisionCopied"));assertEquals("EVIDENCE_INVESTIGATION_ONLY",group.audit("second").get("reuseScope"));
    }

    @Test void differentSubjectsLotsOrIneligibleChecksNeverShareExecution(){
        Document tender=tender("投标人资格要求","第一标段设计单位资格要求","须提交有效营业执照。","第二标段施工单位资格要求","须提交有效营业执照。");
        Checkpoint design=checkpoint("design",tender,3,"须提交有效营业执照。"),construction=checkpoint("construction",tender,5,"须提交有效营业执照。");
        assertEquals(0,AgentBusinessGroupReuse.plan(cn.bidcheck.compat.Java8Api.listOf(design, construction),tender,cp->true).reusableGroupCount());
        Document same=tender("本项目不划分标段。","投标人资格要求","须提供承诺书。","须提供承诺书。");
        Checkpoint a=checkpoint("a",same,3,"须提供承诺书。"),b=checkpoint("b",same,4,"须提供承诺书。");
        assertEquals(0,AgentBusinessGroupReuse.plan(cn.bidcheck.compat.Java8Api.listOf(a, b),same,cp->!cp.id().equals("b")).reusableGroupCount());
    }

    @Test void followerReceiptContainsOnlyFrozenSourcesAndFactsAndNeverCopiesDecision(){
        AgentBusinessGroupReuse.Membership group=new AgentBusinessGroupReuse.Membership("group","leader",cn.bidcheck.compat.Java8Api.listOf("leader", "follower"),cn.bidcheck.compat.Java8Api.mapOf());
        Map<String,Object> source=cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "projectId", "p", "documentId", "bid", "documentHash", "hash", "documentVersion", "1", "parserFingerprint", "parser", "sourceFingerprint", "source-fingerprint");
        Map<String,Object> accepted=cn.bidcheck.compat.Java8Api.mapOf("id", "F1", "kind", "FIELD", "field", "项目负责人", "rawValue", "测试甲", "sourceIds", cn.bidcheck.compat.Java8Api.listOf("S1"));
        Map<String,Object> rejected=cn.bidcheck.compat.Java8Api.mapOf("id", "F2", "kind", "FIELD", "field", "项目负责人", "rawValue", "伪造", "sourceIds", cn.bidcheck.compat.Java8Api.listOf("UNKNOWN"));
        Map<String,Object> snapshot=cn.bidcheck.compat.Java8Api.mapOf("id", "leader-snapshot", "type", AgentBusinessGroupReuse.FACT_SNAPSHOT, "status", "COMPLETED", "schemaVersion", "engine-schema", "engineVersion", "engine", "modelIdentity", "model", "output", cn.bidcheck.compat.Java8Api.mapOf("sources", cn.bidcheck.compat.Java8Api.listOf(source), "sourceIds", cn.bidcheck.compat.Java8Api.listOf("S1", "UNKNOWN"), "facts", cn.bidcheck.compat.Java8Api.listOf(accepted, rejected), "businessProof", true));
        Checkpoint cp=new Checkpoint("leader","人员","资格检查","HIGH","SEMANTIC_RESPONSE","","","人员要求","",true,true,true,null);
        Finding leader=new Finding("finding","run",cp,new Evaluation("PASS","首项通过","",cn.bidcheck.compat.Java8Api.listOf(),0,"AGENT_LED_REVIEW"),
            cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("businessDecision", "PASS", "agentInvestigation", cn.bidcheck.compat.Java8Api.mapOf("serverPlanFingerprint", "plan-1", "targetEvidenceRevision", "evidence-1"))));
        AgentBusinessGroupReuse.EvidenceReceipt receipt=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",leader,cn.bidcheck.compat.Java8Api.listOf(snapshot));
        assertTrue(receipt.reusable());assertEquals(cn.bidcheck.compat.Java8Api.listOf("S1"),receipt.sourceIds());assertEquals(1,receipt.factCount());
        Map<String,Object> replay=(Map<String,Object>)receipt.step().get("output");
        assertEquals(false,replay.get("businessProof"));assertFalse(replay.containsKey("businessDecision"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(accepted),replay.get("facts"));assertEquals("plan-1",receipt.planFingerprint());assertEquals("evidence-1",receipt.evidenceRevision());
        AtomicReference<Map<String,Object>> published=new AtomicReference<>();Map<String,Object> context=new LinkedHashMap<>();
        context.put("priorSteps",cn.bidcheck.compat.Java8Api.listOf());context.put("stepObserver",(Consumer<Map<String,Object>>)published::set);
        assertTrue(AgentBusinessGroupReuse.attach(receipt,context));assertEquals(receipt.step(),published.get());
        assertEquals(receipt.sourceIds(),context.get("recoveryChangedSourceIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(receipt.step()),context.get("priorSteps"));
        assertEquals("evidence-1",context.get("agentGroupSourceEvidenceRevision"));assertEquals("plan-1",context.get("agentGroupSourcePlanFingerprint"));
        context.put("recoveryChangedSourceIds",cn.bidcheck.compat.Java8Api.listOf());published.set(null);
        assertFalse(AgentBusinessGroupReuse.attach(receipt,context));assertEquals(1,((List<?>)context.get("priorSteps")).size(),"same receipt remains single-flight");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),context.get("recoveryChangedSourceIds"),"duplicate receipt does not wake a completed recovery again");assertNull(published.get());
    }

    @Test void resavedAndReorderedSnapshotDoesNotPretendToContainNewEvidence(){
        cn.bidcheck.service.AgentBusinessGroupReuse.Membership group=group();Map<String,Object> source=source("S1","hash",1),other=source("S2","hash",1);
        Map<String,Object> first=fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1", "S2"),"张某",cn.bidcheck.compat.Java8Api.mapOf("cellIds", cn.bidcheck.compat.Java8Api.listOf("c1", "c2"), "months", cn.bidcheck.compat.Java8Api.listOf("2025-02", "2025-01")));
        Map<String,Object> second=fact("F2",cn.bidcheck.compat.Java8Api.listOf("S2"),"李某",cn.bidcheck.compat.Java8Api.mapOf());
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt before=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("snapshot-1","leader",cn.bidcheck.compat.Java8Api.listOf(source, other),cn.bidcheck.compat.Java8Api.listOf(first, second))));
        Map<String,Object> rearranged=fact("new-fact-id",cn.bidcheck.compat.Java8Api.listOf("S2", "S1"),"张某",cn.bidcheck.compat.Java8Api.mapOf("months", cn.bidcheck.compat.Java8Api.listOf("2025-01", "2025-02"), "cellIds", cn.bidcheck.compat.Java8Api.listOf("c2", "c1"), "reusedFromBatchId", "batch-new"));
        Map<String,Object> another=new LinkedHashMap<>(second);another.put("id","new-fact-2");another.put("receiptId","new-receipt");
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt after=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("snapshot-2","leader",cn.bidcheck.compat.Java8Api.listOf(other, source),cn.bidcheck.compat.Java8Api.listOf(another, rearranged, rearranged))));
        assertEquals(before.fingerprint(),after.fingerprint());assertEquals(before.step().get("id"),after.step().get("id"));
        Map<String,Object> context=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf(before.step()), "recoveryChangedSourceIds", cn.bidcheck.compat.Java8Api.listOf()));
        assertFalse(AgentBusinessGroupReuse.attach(after,context));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),context.get("recoveryChangedSourceIds"));
    }

    @Test void sourceAliasesAndReceiptWrappingDoNotGenerateAdditionalEvidence(){
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt before=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("old","leader",cn.bidcheck.compat.Java8Api.listOf(source("S1","hash",1)),cn.bidcheck.compat.Java8Api.listOf(fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1"),"张某",cn.bidcheck.compat.Java8Api.mapOf())))));
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt alias=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("new","leader",cn.bidcheck.compat.Java8Api.listOf(source("renamed-source","hash",1)),cn.bidcheck.compat.Java8Api.listOf(fact("F2",cn.bidcheck.compat.Java8Api.listOf("renamed-source"),"张某",cn.bidcheck.compat.Java8Api.mapOf())))));
        assertEquals(before.fingerprint(),alias.fingerprint(),"a source alias changes neither frozen source nor fact");
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt reverse=AgentBusinessGroupReuse.evidenceReceipt(group(),"leader",finding("follower"),cn.bidcheck.compat.Java8Api.listOf(before.step()));
        assertTrue(reverse.reusable());assertEquals(before.fingerprint(),reverse.fingerprint());
        assertEquals("follower",reverse.step().get("reusedFromCheckpointId"),"the actual source member is retained for audit");
    }

    @Test void sourceContentParserOrBusinessFactChangeWakesRecovery(){
        Map<String,Object> s=source("S1","hash",1),f=fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1"),"张某",cn.bidcheck.compat.Java8Api.mapOf("employer", "甲单位"));
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt before=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("first","leader",cn.bidcheck.compat.Java8Api.listOf(s),cn.bidcheck.compat.Java8Api.listOf(f))));
        List<Map<String,Object>> changedSources=new ArrayList<>();changedSources.add(source("S1","replaced-hash",2));
        Map<String,Object> parser=new LinkedHashMap<>(s);parser.put("parserFingerprint","parser-v2");changedSources.add(parser);
        Map<String,Object> fingerprint=new LinkedHashMap<>(s);fingerprint.put("sourceFingerprint","corrected-span");changedSources.add(fingerprint);
        for(Map<String,Object> changed:changedSources){cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt after=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("first","leader",cn.bidcheck.compat.Java8Api.listOf(changed),cn.bidcheck.compat.Java8Api.listOf(f))));
            assertNotEquals(before.fingerprint(),after.fingerprint());Map<String,Object> context=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf(before.step()), "recoveryChangedSourceIds", cn.bidcheck.compat.Java8Api.listOf()));
            assertTrue(AgentBusinessGroupReuse.attach(after,context));assertEquals(cn.bidcheck.compat.Java8Api.listOf("S1"),context.get("recoveryChangedSourceIds"));}
        for(Map<String,Object> changed:cn.bidcheck.compat.Java8Api.listOf(fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1"),"李某",cn.bidcheck.compat.Java8Api.mapOf("employer", "甲单位")), fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1"),"张某",cn.bidcheck.compat.Java8Api.mapOf("employer", "乙单位")))){
            cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt after=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("first","leader",cn.bidcheck.compat.Java8Api.listOf(s),cn.bidcheck.compat.Java8Api.listOf(changed))));assertNotEquals(before.fingerprint(),after.fingerprint());}
    }

    @Test void receiptCannotCrossGroupOrUseAnotherCheckpointSteps(){
        List<Map<String,Object>> steps=cn.bidcheck.compat.Java8Api.listOf(snapshot("snapshot","leader",cn.bidcheck.compat.Java8Api.listOf(source("S1","hash",1)),cn.bidcheck.compat.Java8Api.listOf(fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1"),"张某",cn.bidcheck.compat.Java8Api.mapOf()))));
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"outsider",finding("leader"),steps).reusable());
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("outsider"),steps).reusable());
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"leader",finding("leader"),steps).reusable());
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"leader",finding("follower"),steps).reusable(),"leader steps cannot masquerade as a follower source");
    }

    @Test void ambiguousFrozenSourceDoesNotProduceAReceipt(){
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt receipt=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("snapshot","leader",
            cn.bidcheck.compat.Java8Api.listOf(source("S1","hash",1), source("S1","other-hash",2)),cn.bidcheck.compat.Java8Api.listOf(fact("F1",cn.bidcheck.compat.Java8Api.listOf("S1"),"张某",cn.bidcheck.compat.Java8Api.mapOf())))));
        assertFalse(receipt.reusable());
    }

    private AgentBusinessGroupReuse.Membership group(){return new AgentBusinessGroupReuse.Membership("group","leader",cn.bidcheck.compat.Java8Api.listOf("leader", "follower"),cn.bidcheck.compat.Java8Api.mapOf());}
    private Finding finding(String id){return new Finding("finding-"+id,"run",new Checkpoint(id,"人员","资格检查","HIGH","SEMANTIC_RESPONSE","","","人员要求","",true,true,true,null),new Evaluation("UNDETERMINED","","",cn.bidcheck.compat.Java8Api.listOf(),0,"AGENT_LED_REVIEW"),cn.bidcheck.compat.Java8Api.mapOf());}
    private Map<String,Object> source(String id,String hash,int version){return cn.bidcheck.compat.Java8Api.mapOf("id", id, "projectId", "p", "documentId", "bid", "documentHash", hash, "documentVersion", version, "parserFingerprint", "parser", "sourceFingerprint", "source-span");}
    private Map<String,Object> fact(String id,List<String> sources,String name,Map<String,Object> attrs){return cn.bidcheck.compat.Java8Api.mapOf("id", id, "kind", "FIELD", "subject", name, "field", "姓名", "rawValue", name, "value", name, "scope", "CURRENT_PROJECT", "sourceIds", sources, "attributes", attrs);}
    private Map<String,Object> snapshot(String id,String checkpoint,List<Map<String,Object>> sources,List<Map<String,Object>> facts){return cn.bidcheck.compat.Java8Api.mapOf("id", id, "checkpointId", checkpoint, "type", AgentBusinessGroupReuse.FACT_SNAPSHOT, "status", "COMPLETED", "schemaVersion", "schema", "engineVersion", "engine", "modelIdentity", "model", "output", cn.bidcheck.compat.Java8Api.mapOf("facts", facts, "sources", sources, "sourceIds", cn.bidcheck.compat.Java8Api.toList(sources.stream().map(s->s.get("id")))));}
}

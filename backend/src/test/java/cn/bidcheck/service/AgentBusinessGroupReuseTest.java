package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentBusinessGroupReuseTest {
    private Document tender(String... bodies){List<Page> pages=new ArrayList<>();for(int i=0;i<bodies.length;i++)pages.add(new Page(i+1,bodies[i],"PARAGRAPH","DOCX",""));return new Document("t","p","TENDER","tender.docx",1,"frozen-hash","",pages,List.of());}
    private Checkpoint checkpoint(String id,Document tender,int page,String expected){Page p=tender.pages().get(page-1);Evidence source=new Evidence(tender.id(),tender.name(),p.number(),p.locationType(),p.text(),"p"+p.number(),1);return new Checkpoint(id,"营业执照","资格检查","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",expected,"",true,true,true,source);}

    @Test void onlyEvidenceInvestigationIsSharedForFrozenEquivalentObligations(){
        Document tender=tender("本项目不划分标段。","投标人资格要求","投标人须提交有效营业执照。","投标单位应提供有效的营业执照。");
        Checkpoint first=checkpoint("first",tender,3,"投标人须提交有效营业执照。"),second=checkpoint("second",tender,4,"投标单位应提供有效的营业执照。");
        AgentBusinessGroupReuse.Plan plan=AgentBusinessGroupReuse.plan(List.of(first,second),tender,cp->true);
        assertEquals(1,plan.reusableGroupCount());assertEquals(1,plan.reusedCheckpointCount());
        var group=plan.membership("second");assertNotNull(group);assertEquals("first",group.leaderCheckpointId());assertFalse(group.leader("second"));
        assertEquals(false,group.audit("second").get("businessDecisionCopied"));assertEquals("EVIDENCE_INVESTIGATION_ONLY",group.audit("second").get("reuseScope"));
    }

    @Test void differentSubjectsLotsOrIneligibleChecksNeverShareExecution(){
        Document tender=tender("投标人资格要求","第一标段设计单位资格要求","须提交有效营业执照。","第二标段施工单位资格要求","须提交有效营业执照。");
        Checkpoint design=checkpoint("design",tender,3,"须提交有效营业执照。"),construction=checkpoint("construction",tender,5,"须提交有效营业执照。");
        assertEquals(0,AgentBusinessGroupReuse.plan(List.of(design,construction),tender,cp->true).reusableGroupCount());
        Document same=tender("本项目不划分标段。","投标人资格要求","须提供承诺书。","须提供承诺书。");
        Checkpoint a=checkpoint("a",same,3,"须提供承诺书。"),b=checkpoint("b",same,4,"须提供承诺书。");
        assertEquals(0,AgentBusinessGroupReuse.plan(List.of(a,b),same,cp->!cp.id().equals("b")).reusableGroupCount());
    }

    @Test void followerReceiptContainsOnlyFrozenSourcesAndFactsAndNeverCopiesDecision(){
        AgentBusinessGroupReuse.Membership group=new AgentBusinessGroupReuse.Membership("group","leader",List.of("leader","follower"),Map.of());
        Map<String,Object> source=Map.of("id","S1","projectId","p","documentId","bid","documentHash","hash",
            "documentVersion","1","parserFingerprint","parser","sourceFingerprint","source-fingerprint");
        Map<String,Object> accepted=Map.of("id","F1","kind","FIELD","field","项目负责人","rawValue","测试甲","sourceIds",List.of("S1"));
        Map<String,Object> rejected=Map.of("id","F2","kind","FIELD","field","项目负责人","rawValue","伪造","sourceIds",List.of("UNKNOWN"));
        Map<String,Object> snapshot=Map.of("id","leader-snapshot","type",AgentBusinessGroupReuse.FACT_SNAPSHOT,"status","COMPLETED",
            "schemaVersion","engine-schema","engineVersion","engine","modelIdentity","model",
            "output",Map.of("sources",List.of(source),"sourceIds",List.of("S1","UNKNOWN"),"facts",List.of(accepted,rejected),"businessProof",true));
        Checkpoint cp=new Checkpoint("leader","人员","资格检查","HIGH","SEMANTIC_RESPONSE","","","人员要求","",true,true,true,null);
        Finding leader=new Finding("finding","run",cp,new Evaluation("PASS","首项通过","",List.of(),0,"AGENT_LED_REVIEW"),
            Map.of("atomicCheck",Map.of("businessDecision","PASS","agentInvestigation",Map.of("serverPlanFingerprint","plan-1","targetEvidenceRevision","evidence-1"))));
        AgentBusinessGroupReuse.EvidenceReceipt receipt=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",leader,List.of(snapshot));
        assertTrue(receipt.reusable());assertEquals(List.of("S1"),receipt.sourceIds());assertEquals(1,receipt.factCount());
        Map<String,Object> replay=(Map<String,Object>)receipt.step().get("output");
        assertEquals(false,replay.get("businessProof"));assertFalse(replay.containsKey("businessDecision"));
        assertEquals(List.of(accepted),replay.get("facts"));assertEquals("plan-1",receipt.planFingerprint());assertEquals("evidence-1",receipt.evidenceRevision());
        AtomicReference<Map<String,Object>> published=new AtomicReference<>();Map<String,Object> context=new LinkedHashMap<>();
        context.put("priorSteps",List.of());context.put("stepObserver",(Consumer<Map<String,Object>>)published::set);
        assertTrue(AgentBusinessGroupReuse.attach(receipt,context));assertEquals(receipt.step(),published.get());
        assertEquals(receipt.sourceIds(),context.get("recoveryChangedSourceIds"));assertEquals(List.of(receipt.step()),context.get("priorSteps"));
        assertEquals("evidence-1",context.get("agentGroupSourceEvidenceRevision"));assertEquals("plan-1",context.get("agentGroupSourcePlanFingerprint"));
        context.put("recoveryChangedSourceIds",List.of());published.set(null);
        assertFalse(AgentBusinessGroupReuse.attach(receipt,context));assertEquals(1,((List<?>)context.get("priorSteps")).size(),"same receipt remains single-flight");
        assertEquals(List.of(),context.get("recoveryChangedSourceIds"),"duplicate receipt does not wake a completed recovery again");assertNull(published.get());
    }

    @Test void resavedAndReorderedSnapshotDoesNotPretendToContainNewEvidence(){
        var group=group();Map<String,Object> source=source("S1","hash",1),other=source("S2","hash",1);
        Map<String,Object> first=fact("F1",List.of("S1","S2"),"张某",Map.of("cellIds",List.of("c1","c2"),"months",List.of("2025-02","2025-01")));
        Map<String,Object> second=fact("F2",List.of("S2"),"李某",Map.of());
        var before=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",finding("leader"),List.of(snapshot("snapshot-1","leader",List.of(source,other),List.of(first,second))));
        Map<String,Object> rearranged=fact("new-fact-id",List.of("S2","S1"),"张某",Map.of("months",List.of("2025-01","2025-02"),"cellIds",List.of("c2","c1"),"reusedFromBatchId","batch-new"));
        Map<String,Object> another=new LinkedHashMap<>(second);another.put("id","new-fact-2");another.put("receiptId","new-receipt");
        var after=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",finding("leader"),List.of(snapshot("snapshot-2","leader",List.of(other,source),List.of(another,rearranged,rearranged))));
        assertEquals(before.fingerprint(),after.fingerprint());assertEquals(before.step().get("id"),after.step().get("id"));
        Map<String,Object> context=new LinkedHashMap<>(Map.of("priorSteps",List.of(before.step()),"recoveryChangedSourceIds",List.of()));
        assertFalse(AgentBusinessGroupReuse.attach(after,context));assertEquals(List.of(),context.get("recoveryChangedSourceIds"));
    }

    @Test void sourceAliasesAndReceiptWrappingDoNotGenerateAdditionalEvidence(){
        var before=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),List.of(snapshot("old","leader",List.of(source("S1","hash",1)),List.of(fact("F1",List.of("S1"),"张某",Map.of())))));
        var alias=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),List.of(snapshot("new","leader",List.of(source("renamed-source","hash",1)),List.of(fact("F2",List.of("renamed-source"),"张某",Map.of())))));
        assertEquals(before.fingerprint(),alias.fingerprint(),"a source alias changes neither frozen source nor fact");
        var reverse=AgentBusinessGroupReuse.evidenceReceipt(group(),"leader",finding("follower"),List.of(before.step()));
        assertTrue(reverse.reusable());assertEquals(before.fingerprint(),reverse.fingerprint());
        assertEquals("follower",reverse.step().get("reusedFromCheckpointId"),"the actual source member is retained for audit");
    }

    @Test void sourceContentParserOrBusinessFactChangeWakesRecovery(){
        Map<String,Object> s=source("S1","hash",1),f=fact("F1",List.of("S1"),"张某",Map.of("employer","甲单位"));
        var before=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),List.of(snapshot("first","leader",List.of(s),List.of(f))));
        List<Map<String,Object>> changedSources=new ArrayList<>();changedSources.add(source("S1","replaced-hash",2));
        Map<String,Object> parser=new LinkedHashMap<>(s);parser.put("parserFingerprint","parser-v2");changedSources.add(parser);
        Map<String,Object> fingerprint=new LinkedHashMap<>(s);fingerprint.put("sourceFingerprint","corrected-span");changedSources.add(fingerprint);
        for(Map<String,Object> changed:changedSources){var after=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),List.of(snapshot("first","leader",List.of(changed),List.of(f))));
            assertNotEquals(before.fingerprint(),after.fingerprint());Map<String,Object> context=new LinkedHashMap<>(Map.of("priorSteps",List.of(before.step()),"recoveryChangedSourceIds",List.of()));
            assertTrue(AgentBusinessGroupReuse.attach(after,context));assertEquals(List.of("S1"),context.get("recoveryChangedSourceIds"));}
        for(Map<String,Object> changed:List.of(fact("F1",List.of("S1"),"李某",Map.of("employer","甲单位")),fact("F1",List.of("S1"),"张某",Map.of("employer","乙单位")))){
            var after=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),List.of(snapshot("first","leader",List.of(s),List.of(changed))));assertNotEquals(before.fingerprint(),after.fingerprint());}
    }

    @Test void receiptCannotCrossGroupOrUseAnotherCheckpointSteps(){
        List<Map<String,Object>> steps=List.of(snapshot("snapshot","leader",List.of(source("S1","hash",1)),List.of(fact("F1",List.of("S1"),"张某",Map.of()))));
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"outsider",finding("leader"),steps).reusable());
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("outsider"),steps).reusable());
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"leader",finding("leader"),steps).reusable());
        assertFalse(AgentBusinessGroupReuse.evidenceReceipt(group(),"leader",finding("follower"),steps).reusable(),"leader steps cannot masquerade as a follower source");
    }

    @Test void ambiguousFrozenSourceDoesNotProduceAReceipt(){
        var receipt=AgentBusinessGroupReuse.evidenceReceipt(group(),"follower",finding("leader"),List.of(snapshot("snapshot","leader",
            List.of(source("S1","hash",1),source("S1","other-hash",2)),List.of(fact("F1",List.of("S1"),"张某",Map.of())))));
        assertFalse(receipt.reusable());
    }

    private AgentBusinessGroupReuse.Membership group(){return new AgentBusinessGroupReuse.Membership("group","leader",List.of("leader","follower"),Map.of());}
    private Finding finding(String id){return new Finding("finding-"+id,"run",new Checkpoint(id,"人员","资格检查","HIGH","SEMANTIC_RESPONSE","","","人员要求","",true,true,true,null),new Evaluation("UNDETERMINED","","",List.of(),0,"AGENT_LED_REVIEW"),Map.of());}
    private Map<String,Object> source(String id,String hash,int version){return Map.of("id",id,"projectId","p","documentId","bid","documentHash",hash,"documentVersion",version,"parserFingerprint","parser","sourceFingerprint","source-span");}
    private Map<String,Object> fact(String id,List<String> sources,String name,Map<String,Object> attrs){return Map.of("id",id,"kind","FIELD","subject",name,"field","姓名","rawValue",name,"value",name,"scope","CURRENT_PROJECT","sourceIds",sources,"attributes",attrs);}
    private Map<String,Object> snapshot(String id,String checkpoint,List<Map<String,Object>> sources,List<Map<String,Object>> facts){return Map.of("id",id,"checkpointId",checkpoint,"type",AgentBusinessGroupReuse.FACT_SNAPSHOT,"status","COMPLETED","schemaVersion","schema","engineVersion","engine","modelIdentity","model","output",Map.of("facts",facts,"sources",sources,"sourceIds",sources.stream().map(s->s.get("id")).toList()));}
}

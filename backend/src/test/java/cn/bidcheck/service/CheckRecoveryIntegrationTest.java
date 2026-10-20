package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:check-recovery;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false","bidcheck.visual.enabled=false","bidcheck.queue.enabled=false"})
class CheckRecoveryIntegrationTest {
    static final Path DATA=temp();static Path temp(){try{return Files.createTempDirectory("bidcheck-recovery-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;
    String parsed(String p,String role,String text)throws Exception{
        TaskRow task=service.upload(p,role,new MockMultipartFile("file","synthetic.txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));
        service.execute(task);return BidService.text(store.map(task.inputJson),"documentId");
    }
    Map<String,Object> frozenParent()throws Exception{
        String p=BidService.text(service.createProject(Map.of("name","补查隔离测试")),"id");
        String tender=parsed(p,"TENDER","投标人应提交授权委托书及报价清单。");service.execute(service.extract(p,tender));
        for(Checkpoint cp:(List<Checkpoint>)service.detail(p).get("checkpoints"))service.updateCheckpoint(cp.id(),new Checkpoint(cp.id(),cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),cp.expected(),cp.unit(),cp.required(),true,false,cp.source()));
        for(String name:List.of("授权委托书","报价清单"))service.createCheckpoint(p,BidService.fields("title",name,"category","形式检查","type","MATERIAL_EXISTENCE","field","material","operator","EXISTS","expected",name,"confirmed",true));
        String bid=parsed(p,"BID","本项目投标文件\n授权委托书\n本单位授权王甲作为本项目投标代理人，办理投标有关事项。\n报价清单\n本项目报价金额及明细见下表。");
        Map<String,Object> run=service.startRun(p,bid);String id=BidService.text(run,"id");
        List<Checkpoint> cps=store.list("snapshot",p,id,Checkpoint.class);
        for(int i=0;i<cps.size();i++){
            Checkpoint cp=cps.get(i);Evaluation e=new Evaluation(i==0?"PASS":"UNDETERMINED",i==0?"原始已核实":"旧规则未完成","",List.of(),0,"FROZEN_FIXTURE");
            Finding f=new Finding(BidService.stableId(id+":"+cp.id()),id,cp,e,Map.of());store.save("finding",f.id(),p,id,f);
            if(i==0)service.review(f.id(),Map.of("status","PASS","note","已核对旧版证据","reviewer","测试员"));
        }
        run.put("status","COMPLETED");run.put("resolutionPolicyIdentity","old-policy");run.put("ocrPreparationComplete",true);store.save("run",id,p,bid,run);
        TaskRow task=service.task(BidService.text(run,"taskId"));task.status="SUCCEEDED";store.mapper().updateTask(task);return run;
    }
    @Test void childRecoveryPreservesFrozenHistoryResolvedFindingAndManualReview()throws Exception{
        Map<String,Object> parent=frozenParent();String p=BidService.text(parent,"projectId"),id=BidService.text(parent,"id");
        String original=store.require("run",id).body;List<Finding> originalFindings=store.list("finding",p,id,Finding.class);
        Map<String,Object> parentTemplateSnapshot=store.getMap("check_spec_snapshot",BidService.text(parent,"templateSnapshotId"));
        ReflectionTestUtils.setField(service,"templateRouteEnabled",false);
        Map<String,Object> child;
        try {child=service.recoverRun(p,id);} finally {ReflectionTestUtils.setField(service,"templateRouteEnabled",true);}
        assertNotEquals(id,child.get("id"));assertEquals(id,child.get("parentRunId"));assertEquals(true,child.get("ocrPreparationComplete"));
        assertEquals(parent.get("templateRouteEnabled"),child.get("templateRouteEnabled"));assertEquals(parent.get("templateSpecIdentity"),child.get("templateSpecIdentity"));
        Map<String,Object> childTemplateSnapshot=store.getMap("check_spec_snapshot",BidService.text(child,"templateSnapshotId"));
        assertEquals(parentTemplateSnapshot.get("specs"),childTemplateSnapshot.get("specs"));assertEquals(parentTemplateSnapshot.get("routing"),childTemplateSnapshot.get("routing"));
        assertEquals(child.get("id"),service.recoverRun(p,id).get("id"));
        service.execute(service.task(BidService.text(child,"taskId")));
        assertEquals(original,store.require("run",id).body);assertEquals(originalFindings,store.list("finding",p,id,Finding.class));
        Map<String,Object> payload=service.run(BidService.text(child,"id"),false);List<Finding> after=(List<Finding>)payload.get("results");
        Finding kept=after.stream().filter(f->"FROZEN_FIXTURE".equals(f.evaluation().method())&&"PASS".equals(f.evaluation().status())).findFirst().orElseThrow();
        assertEquals(id,CheckResolution.map(CheckResolution.map(kept.assessment()).get("inheritedFrom")).get("runId"));
        assertEquals(1,((List<?>)payload.get("inheritedReviews")).size());assertTrue(((List<?>)payload.get("reviews")).isEmpty());
        assertEquals(0L,CheckResolution.map(payload.get("resolutionSummary")).get("activeJobCount"));
    }
    @Test void cannotRecoverOtherProjectOrAnActiveRun()throws Exception{
        Map<String,Object> parent=frozenParent();String p=BidService.text(parent,"projectId"),id=BidService.text(parent,"id");
        String other=BidService.text(service.createProject(Map.of("name","另一个项目")),"id");assertThrows(Exception.class,()->service.recoverRun(other,id));
        parent.put("status","RUNNING");store.save("run",id,p,BidService.text(parent,"documentId"),parent);assertThrows(Exception.class,()->service.recoverRun(p,id));
    }
    @Test void childRecoveryReadsPersistedGroupDeltasInsteadOfTheParentsCompleteSnapshot()throws Exception{
        Map<String,Object> parent=frozenParent();String p=BidService.text(parent,"projectId"),parentId=BidService.text(parent,"id");
        Finding pending=store.list("finding",p,parentId,Finding.class).stream().filter(CheckResolution::unresolved).findFirst().orElseThrow();
        String doc=BidService.text(parent,"documentId"),hash=store.get("document",doc,Document.class).sha256();
        String reportId=cn.bidcheck.consistency.ConsistencyRunSupport.reportId(parentId);
        Map<String,Object> original=BidService.fields("documentId",doc,"documentHash",hash,"reviewSnapshotComplete",true,
            "doubtReview",Map.of("completedGroups",9),"groups",List.of(Map.of("id","group-1","findingId",pending.id(),"targetedReview",Map.of("status","OLD"))));
        store.save("consistency_report",reportId,p,parentId,original);
        Map<String,Object> child=service.recoverRun(p,parentId);String id=BidService.text(child,"id");
        Map<String,Object> initial=CheckResolution.map(service.run(id,false).get("consistency"));
        assertFalse(initial.containsKey("reviewSnapshotComplete"));assertFalse(initial.containsKey("doubtReview"));
        assertFalse(CheckResolution.maps(initial.get("groups")).get(0).containsKey("targetedReview"));
        store.save("consistency_review_group",BidService.stableId(id+":group-1"),p,id,
            BidService.fields("documentId",doc,"documentHash",hash,"groupId","group-1","updatedAt","2026-10-17T01:00:00Z",
                "targetedReview",Map.of("status","COMPLETED","sourceIds",List.of("source-a")),"doubtReview",Map.of("completedGroups",1)));
        store.save("consistency_review_group",BidService.stableId(id+":wrong-version"),p,id,
            BidService.fields("documentId",doc,"documentHash","wrong-hash","groupId","group-1","updatedAt","2026-10-17T02:00:00Z","targetedReview",Map.of("status","WRONG")));
        for(int attempt=0;attempt<2;attempt++){
            Map<String,Object> restored=CheckResolution.map(service.run(id,false).get("consistency"));
            assertEquals("COMPLETED",CheckResolution.map(CheckResolution.maps(restored.get("groups")).get(0).get("targetedReview")).get("status"));
            assertEquals(1,CheckResolution.map(restored.get("doubtReview")).get("completedGroups"));
        }
        assertEquals(original,store.getMap("consistency_report",reportId));
    }
    @Test void invalidatedOldPassIsNotPublishedAsANewConclusionWhileRecoveryIsQueuedOrFailed()throws Exception{
        Map<String,Object> parent=frozenParent();String p=BidService.text(parent,"projectId"),parentId=BidService.text(parent,"id");
        Finding old=store.list("finding",p,parentId,Finding.class).stream().filter(f->"PASS".equals(f.evaluation().status())).findFirst().orElseThrow();
        store.save("finding",old.id(),p,parentId,new Finding(old.id(),parentId,old.checkpoint(),old.evaluation(),Map.of("atomicCheck",Map.of("complete",true,"workflowState","COMPLETED"))));
        Map<String,Object> child=service.recoverRun(p,parentId);String id=BidService.text(child,"id");
        Finding queued=store.get("finding",BidService.stableId(id+":"+old.checkpoint().id()),Finding.class);
        assertEquals("UNDETERMINED",queued.evaluation().status());assertEquals("SYSTEM_INCOMPLETE",CheckResolution.map(queued.assessment().get("businessPresentation")).get("state"));
        assertFalse(queued.assessment().containsKey("atomicCheck"));assertTrue(CheckResolution.unresolved(queued));
        assertEquals("PASS",CheckResolution.map(CheckResolution.map(queued.assessment().get("inheritedFrom")).get("previousEvaluation")).get("status"));
        assertEquals("PASS",store.get("finding",old.id(),Finding.class).evaluation().status());
        child.put("status","FAILED");store.save("run",id,p,BidService.text(child,"documentId"),child);
        assertTrue(((Number)CheckResolution.map(service.run(id,false).get("resolutionSummary")).get("unresolvedOriginalCount")).intValue()>=2);
        service.execute(service.task(BidService.text(child,"taskId")));
        Finding checked=store.get("finding",queued.id(),Finding.class);
        Map<String,Object> inherited=CheckResolution.map(checked.assessment().get("inheritedFrom"));
        assertEquals(false,inherited.get("pending"));assertEquals(true,inherited.get("rechecked"));assertNotEquals("RECOVERY_PENDING",checked.evaluation().method());
    }
    @Test void aChildThatFailsBeforeItsQueuedItemsRunCanBeRecoveredAndKeepsAncestorReviews()throws Exception{
        Map<String,Object> parent=frozenParent();String p=BidService.text(parent,"projectId");
        Map<String,Object> child=service.recoverRun(p,BidService.text(parent,"id"));String id=BidService.text(child,"id");
        child.put("status","FAILED");store.save("run",id,p,BidService.text(child,"documentId"),child);
        assertEquals(true,CheckResolution.map(service.run(id,false).get("resolutionSummary")).get("canRecover"));
        Map<String,Object> next=service.recoverRun(p,id);assertEquals(id,next.get("parentRunId"));
        assertEquals(1,CheckResolution.maps(next.get("priorReviewRecords")).size());
        assertEquals(parent.get("id"),CheckResolution.maps(next.get("priorReviewRecords")).get(0).get("runId"));
    }
    Map<String,Object> addGeneratedConsistencyGroup(Map<String,Object> parent,boolean legacyMissingMetadata){
        String runId=BidService.text(parent,"id"),project=BidService.text(parent,"projectId");
        Checkpoint cp=new Checkpoint("generated-group-checkpoint","人员名称差异","全文信息检查","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","同一对象的信息应一致","",true,true,true,null);
        String findingId=BidService.stableId(runId+":"+cp.id());
        Map<String,Object> group=BidService.fields("id","group-generated","findingId",findingId,"title",cp.title(),"status","EVIDENCE_INSUFFICIENT","categoryId","personnel","relationType","SAME_FIELD","ruleId","person-name",
            "comparisonContext",Map.of("subject","人员甲"),"evidences",List.of(),"reason","对象身份尚未完整关联。");
        Map<String,Object> metadata=legacyMissingMetadata?Map.of():Map.of("consistencyGroupId","group-generated","categoryId","personnel","relationType","SAME_FIELD","ruleId","person-name","comparisonContext",Map.of("subject","人员甲"));
        store.save("finding",findingId,project,runId,new Finding(findingId,runId,cp,new Evaluation("UNDETERMINED","比较证据不足","",List.of(),0,"FULL_TEXT_CONSISTENCY"),metadata));
        Document document=store.get("document",BidService.text(parent,"documentId"),Document.class);
        store.save("consistency_report",cn.bidcheck.consistency.ConsistencyRunSupport.reportId(runId),project,runId,
            BidService.fields("documentId",document.id(),"documentHash",document.sha256(),"reviewSnapshotComplete",true,"groups",List.of(group),"occurrences",List.of()));
        return group;
    }
    @Test void generatedGroupRecoveryRestoresLocationAndFinishesExecutionWithoutInventingPass()throws Exception{
        Map<String,Object> parent=frozenParent(),group=addGeneratedConsistencyGroup(parent,true);String project=BidService.text(parent,"projectId"),parentId=BidService.text(parent,"id");
        Finding old=store.get("finding",BidService.text(group,"findingId"),Finding.class);String frozen=store.require("finding",old.id()).body;
        assertFalse(store.list("snapshot",project,parentId,Checkpoint.class).stream().anyMatch(c->c.id().equals(old.checkpoint().id())));
        Map<String,Object> child=service.recoverRun(project,parentId);String runId=BidService.text(child,"id"),findingId=BidService.stableId(runId+":"+old.checkpoint().id());
        Finding queued=store.get("finding",findingId,Finding.class);
        assertEquals("group-generated",queued.assessment().get("consistencyGroupId"));assertEquals("personnel",queued.assessment().get("categoryId"));
        assertEquals(Map.of("subject","人员甲"),queued.assessment().get("comparisonContext"));assertEquals("RECOVERY_PENDING",queued.evaluation().method());
        service.execute(service.task(BidService.text(child,"taskId")));
        Finding result=store.get("finding",findingId,Finding.class);
        assertEquals("UNDETERMINED",result.evaluation().status());assertEquals("CONSISTENCY_DERIVED_OBSERVED_OCCURRENCES",result.evaluation().method());
        Map<String,Object> proof=CheckResolution.map(result.assessment().get("derivedConclusion"));
        assertEquals(cn.bidcheck.consistency.ConsistencyDerivedConclusion.VERSION,proof.get("policyVersion"));
        assertEquals("UNDETERMINED",proof.get("status"));assertEquals("OBSERVED_OCCURRENCES",proof.get("proofScope"));
        assertEquals(List.of(),proof.get("originalOccurrenceIds"));assertEquals(List.of(),proof.get("resolvedOccurrenceIds"));
        assertEquals(List.of("ORIGINAL_OCCURRENCE_SET_EMPTY"),proof.get("sourceFailures"));
        assertEquals(false,proof.get("comparisonCoverageComplete"));assertEquals(false,proof.get("documentCoverageComplete"));
        assertEquals("EVIDENCE_INSUFFICIENT",proof.get("originalGroupStatus"));assertEquals(true,proof.get("originalRecordsPreserved"));
        assertEquals(queued.evaluation().method(),CheckResolution.map(result.assessment().get("originalConsistencyEvaluation")).get("method"));
        assertEquals(false,CheckResolution.map(result.assessment().get("inheritedFrom")).get("pending"));assertEquals(true,CheckResolution.map(result.assessment().get("inheritedFrom")).get("rechecked"));
        assertEquals("FINISHED",CheckResolution.map(result.assessment().get("consistencyRecoveryExecution")).get("status"));
        assertEquals(true,CheckResolution.map(result.assessment().get("consistencyRecoveryExecution")).get("recomputed"));
        assertEquals("EVIDENCE_INSUFFICIENT",CheckResolution.map(result.assessment().get("consistencyReview")).get("status"));
        assertFalse(CheckResolution.ACTIVE.contains(BidService.text(store.getMap("check_resolution",CheckResolution.jobId(runId,old.checkpoint().id())),"status")));
        assertEquals(frozen,store.require("finding",old.id()).body);
    }
    @Test void savedGroupReceiptRepairsOldRecoveryMetadataWithoutRepeatingReviewOrAcceptingModelPass()throws Exception{
        Map<String,Object> parent=frozenParent(),group=addGeneratedConsistencyGroup(parent,false);String project=BidService.text(parent,"projectId");
        String frozenOriginal=store.require("finding",BidService.text(group,"findingId")).body;
        Map<String,Object> child=service.recoverRun(project,BidService.text(parent,"id"));String runId=BidService.text(child,"id"),findingId=BidService.stableId(runId+":generated-group-checkpoint");
        Finding queued=store.get("finding",findingId,Finding.class);
        store.save("finding",findingId,project,runId,new Finding(findingId,runId,queued.checkpoint(),queued.evaluation(),Map.of("inheritedFrom",queued.assessment().get("inheritedFrom"),"adjustments",queued.assessment().get("adjustments"))));
        Map<String,Object> receipt=BidService.fields("policyVersion",cn.bidcheck.consistency.ConsistencyDoubtReviewer.VERSION,"status","MODEL_REVIEWED","reason","已保存复核建议。","recommendation",Map.of("status","PASS","reason","模型建议，仅作参考"));
        Document document=store.get("document",BidService.text(child,"documentId"),Document.class);
        store.save("consistency_review_group",BidService.stableId(runId+":consistency-review:group-generated"),project,runId,
            BidService.fields("documentId",document.id(),"documentHash",document.sha256(),"groupId",group.get("id"),"updatedAt","2026-10-17T01:00:00Z","targetedReview",receipt));
        service.execute(service.task(BidService.text(child,"taskId")));
        Finding result=store.get("finding",findingId,Finding.class);
        assertEquals("group-generated",result.assessment().get("consistencyGroupId"));assertEquals("person-name",result.assessment().get("ruleId"));
        assertEquals("UNDETERMINED",result.evaluation().status());assertEquals("CONSISTENCY_DERIVED_OBSERVED_OCCURRENCES",result.evaluation().method());
        Map<String,Object> proof=CheckResolution.map(result.assessment().get("derivedConclusion"));
        assertEquals(cn.bidcheck.consistency.ConsistencyDerivedConclusion.VERSION,proof.get("policyVersion"));
        assertEquals(document.id(),proof.get("sourceDocumentId"));assertEquals(document.sha256(),proof.get("sourceDocumentHash"));
        assertEquals("UNDETERMINED",proof.get("status"));assertEquals("OBSERVED_OCCURRENCES",proof.get("proofScope"));
        assertEquals(List.of(),proof.get("originalOccurrenceIds"));assertEquals(List.of(),proof.get("resolvedOccurrenceIds"));
        assertEquals(List.of("ORIGINAL_OCCURRENCE_SET_EMPTY"),proof.get("sourceFailures"));
        assertEquals(false,proof.get("comparisonCoverageComplete"));assertEquals(false,proof.get("documentCoverageComplete"));
        assertEquals("EVIDENCE_INSUFFICIENT",proof.get("originalGroupStatus"));assertEquals(true,proof.get("humanReviewsPreserved"));
        assertEquals(queued.evaluation().method(),CheckResolution.map(result.assessment().get("originalConsistencyEvaluation")).get("method"));
        assertEquals(true,CheckResolution.map(result.assessment().get("consistencyRecoveryExecution")).get("recomputed"));
        assertEquals(false,CheckResolution.map(result.assessment().get("inheritedFrom")).get("pending"));
        assertEquals(receipt,CheckResolution.map(result.assessment().get("consistencyReview")));
        assertEquals("PASS",CheckResolution.map(CheckResolution.map(result.assessment().get("consistencyReview")).get("recommendation")).get("status"));
        assertEquals(frozenOriginal,store.require("finding",BidService.text(group,"findingId")).body);
        assertEquals(0,((Number)store.getMap("run",runId).get("consistencyReviewAttemptsReserved")).intValue());
    }
    @Test void inProgressOrOtherGroupReceiptCannotCompleteRecovery(){
        Checkpoint cp=new Checkpoint("generated","待恢复组","全文信息检查","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","比较同一对象","",true,true,true,null);
        Finding pending=new Finding("finding","run",cp,new Evaluation("UNDETERMINED","等待核验","",List.of(),0,"RECOVERY_PENDING"),Map.of("inheritedFrom",Map.of("pending",true)));
        Map<String,Object> group=BidService.fields("id","group","findingId","finding","targetedReview",Map.of("policyVersion",cn.bidcheck.consistency.ConsistencyDoubtReviewer.VERSION,"status","IN_PROGRESS"));
        Finding inProgress=BidService.reviewedConsistencyFinding(pending,group);
        assertEquals("RECOVERY_PENDING",inProgress.evaluation().method());assertEquals(true,CheckResolution.map(inProgress.assessment().get("inheritedFrom")).get("pending"));
        group.put("findingId","another-finding");assertEquals(pending,BidService.reviewedConsistencyFinding(pending,group));
    }
}

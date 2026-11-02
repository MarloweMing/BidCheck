package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.mock.web.MockMultipartFile;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InferenceConfirmationServiceTest {
    final Map<String,Stored> rows=new LinkedHashMap<>();final RecordMapper mapper=mock(RecordMapper.class);
    final Store store=new Store(mapper,new ObjectMapper());final BidService bids=mock(BidService.class);
    final Document bid=new Document("bid","project","BID","a.pdf",1,"hash","",List.of(),List.of());
    final Document tender=new Document("tender","project","TENDER","a.docx",1,"thash","",List.of(),List.of());
    final Checkpoint cp=new Checkpoint("cp","人员","资格","HIGH","SEMANTIC_RESPONSE","role","EXISTS","负责人","",true,true,true,null);
    final InferenceConfirmationService service;
    InferenceConfirmationServiceTest(){
        when(mapper.get(anyString())).thenAnswer(a->rows.get(a.getArgument(0)));when(mapper.getForUpdate(anyString())).thenAnswer(a->rows.get(a.getArgument(0)));
        doAnswer(a->{Stored row=a.getArgument(0);rows.put(row.id,row);return null;}).when(mapper).save(any());
        doAnswer(a->{Stored row=a.getArgument(0);rows.put(row.id,row);return 1;}).when(mapper).insertRecord(any());
        when(mapper.list(anyString(),any(),any())).thenAnswer(a->rows.values().stream().filter(r->Objects.equals(r.kind,a.getArgument(0))&&(a.getArgument(1)==null||Objects.equals(r.projectId,a.getArgument(1)))&&(a.getArgument(2)==null||Objects.equals(r.parentId,a.getArgument(2)))).toList());
        ObjectProvider<BidService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(bids);
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new InferenceConfirmationService(store,provider,tx);when(bids.document("bid")).thenReturn(bid);when(bids.document("tender")).thenReturn(tender);
        when(bids.recoverRun("project","run")).thenReturn(fields("id","next","status","PENDING"));
        store.save("project","project","project","",Map.of("id","project"));run("run",cp);run("next",cp);
    }
    void run(String id,Checkpoint point){store.save("run",id,"project","",fields("id",id,"projectId","project","documentId","bid","tenderDocumentId","tender","status","COMPLETED"));store.save("snapshot",id+"-snapshot","project",id,point);}
    String capture(){service.capture("run","cp",bid,tender,fields("id","engine","claim","确认姓名","proposedFact",Map.of("value","张国超")));return text(service.list("run").get(0),"id");}
    Map<String,Object> decision(String status){return fields("decision",status,"reviewer","审核人","note","对照原文确认","expectedRevision",0);}
    @Test void confirmationPersistsActorRevisionAndQueuesIndependentRecalculation(){String id=capture();var response=service.decide("run",id,decision("CONFIRMED"));assertEquals("QUEUED",response.get("recomputeStatus"));assertEquals(1,service.confirmed("project",bid,tender).size());
        var claim=service.list("run").get(0);assertEquals(1,claim.get("revision"));assertEquals("审核人",claim.get("actor"));assertNotNull(claim.get("decidedAt"));assertEquals(1,((List<?>)service.exportRecords("run").get("history")).size());assertThrows(RuntimeException.class,()->service.decide("run",id,decision("CONFIRMED")));}
    @Test void pendingClaimRemainsAvailableOnSameSnapshotAndRecomputesCurrentRun(){String id=capture();assertEquals("run",service.list("next").get(0).get("originRunId"));when(bids.recoverRun("project","next")).thenReturn(fields("id","next2"));service.decide("next",id,decision("CONFIRMED"));verify(bids).recoverRun("project","next");}
    @Test void changedClauseOrFileVersionCannotReuseAssertion(){capture();run("other",new Checkpoint("cp","人员","资格","HIGH","SEMANTIC_RESPONSE","role","EXISTS","两位负责人","",true,true,true,null));assertTrue(service.list("other").isEmpty());
        assertTrue(service.confirmed("project",new Document("bid","project","BID","a.pdf",2,"newhash","",List.of(),List.of()),tender).isEmpty());}
    @Test void rejectedClaimDoesNotPublishFactAndRecomputesToRemoveStaleConfirmation(){String id=capture();service.decide("run",id,decision("REJECTED"));assertTrue(service.confirmed("project",bid,tender).isEmpty());assertEquals(1,service.decisions("project",bid,tender).size());verify(bids).recoverRun("project","run");}
    @Test void runningCheckAndUnfrozenConfirmationCannotAlterAnOldRun(){String id=capture();service.decide("run",id,decision("CONFIRMED"));assertTrue(service.confirmedForRun("run","project",bid,tender).isEmpty());
        Map<String,Object> run=store.getMap("run","next");run.put("inferenceConfirmationRecords",List.of(Map.of("id",id,"revision",1)));store.save("run","next","project","",run);assertEquals(1,service.confirmedForRun("next","project",bid,tender).size());
        run.put("inferenceConfirmationRecords",List.of(Map.of("id",id,"revision",2)));store.save("run","next","project","",run);assertTrue(service.confirmedForRun("next","project",bid,tender).isEmpty());}
    @Test void frozenConfirmationDoesNotSurviveAChangedRequirementAndRevisionMustBeInteger(){String id=capture();Map<String,Object> invalid=decision("CONFIRMED");invalid.put("expectedRevision",0.9);assertThrows(RuntimeException.class,()->service.decide("run",id,invalid));service.decide("run",id,decision("CONFIRMED"));
        run("changed",new Checkpoint("cp","人员","资格","HIGH","SEMANTIC_RESPONSE","role","EXISTS","两位负责人","",true,true,true,null));Map<String,Object> changed=store.getMap("run","changed");changed.put("inferenceConfirmationRecords",List.of(Map.of("id",id,"revision",1)));store.save("run","changed","project","",changed);assertTrue(service.confirmedForRun("changed","project",bid,tender).isEmpty());}
    @Test void duplicatePendingCardCannotOverwriteAnExistingDecision(){String first=capture();service.capture("next","cp",bid,tender,fields("id","engine","claim","同一推断"));String duplicate=text(service.list("next").get(0),"id");assertNotEquals(first,duplicate);service.decide("run",first,decision("CONFIRMED"));assertThrows(RuntimeException.class,()->service.decide("next",duplicate,decision("REJECTED")));assertEquals("CONFIRMED",service.list("next").get(0).get("status"));}
    @Test void queueFailureDoesNotLoseSavedConfirmation(){String id=capture();when(bids.recoverRun("project","run")).thenThrow(new IllegalStateException("database unavailable"));assertEquals("SAVED_REQUIRES_RECHECK",service.decide("run",id,decision("CONFIRMED")).get("recomputeStatus"));assertEquals(1,service.confirmed("project",bid,tender).size());}
    @Test void attachmentUsesBoundItemAndNeverConfirmsClaimOrReplacesBid() throws Exception {
        String id=capture();var file=new MockMultipartFile("file","proof.pdf","application/pdf",new byte[]{1,2,3});
        when(bids.uploadSupplement("run","cp",id,file,"人员证明")).thenReturn(fields("attachmentId","attachment","taskId","parse","status","PENDING","recomputeStatus","WAITING_PARSE"));
        Map<String,Object> result=service.appendAttachment("run",id,file," 人员证明 ",0);
        assertEquals("WAITING_PARSE",result.get("recomputeStatus"));assertEquals("parse",result.get("taskId"));
        verify(bids).uploadSupplement("run","cp",id,file,"人员证明");verify(bids,never()).recoverRun(anyString(),anyString());
        assertEquals("NEEDS_CONFIRMATION",service.list("run").get(0).get("status"));assertTrue(service.confirmed("project",bid,tender).isEmpty());
        assertEquals("bid",store.getMap("run","run").get("documentId"));
    }
    @Test void attachmentRejectsStaleRevisionRunningCheckAndEmptyContent() throws Exception {
        String id=capture();var file=new MockMultipartFile("file","proof.pdf","application/pdf",new byte[]{1});
        assertThrows(RuntimeException.class,()->service.appendAttachment("run",id,file,"证明",1));
        assertThrows(RuntimeException.class,()->service.appendAttachment("run",id,file," ",0));
        assertThrows(RuntimeException.class,()->service.appendAttachment("run",id,new MockMultipartFile("file",new byte[0]),"证明",0));
        Map<String,Object> running=store.getMap("run","run");running.put("status","RUNNING");store.save("run","run","project","",running);
        assertThrows(RuntimeException.class,()->service.appendAttachment("run",id,file,"证明",0));verify(bids,never()).uploadSupplement(anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void sharedLedgerRequiresFrozenRevisionUnchangedClaimAndVerifiedSourceBytes(){
        Document source=new Document("bid","project","BID","a.pdf",1,"hash","",List.of(new Page(22,"拟在本合同任职 项目负责人 张国超","PAGE","NATIVE","")),List.of());when(bids.document("bid")).thenReturn(source);
        service.capture("run","cp",source,tender,fields("id","source-backed","claim","确认对应关系","proposedFact",fields("kind","RELATION","subject","张国超","field","同一人","value","张*超"),"evidences",List.of(fields("sourceId","source","documentId","bid","documentHash","hash","documentVersion",1,"page",22,"locationType","PAGE","quote","项目负责人 张国超"))));
        String id=text(service.list("run").get(0),"id");service.decide("run",id,decision("CONFIRMED"));
        assertTrue(service.sharedConfirmedForRun("run","project",source,tender).isEmpty());Map<String,Object> next=store.getMap("run","next");next.put("inferenceConfirmationRecords",List.of(Map.of("id",id,"revision",1)));store.save("run","next","project","",next);
        assertEquals(1,service.sharedConfirmedForRun("next","project",source,tender).size());assertEquals(1,store.maps("inference_fact_ledger","project","bid").size());
        Map<String,Object> changed=store.getMap("inference_claim",id);changed.put("proposedFact",Map.of("value","其他人员"));store.save("inference_claim",id,"project","run",changed);assertTrue(service.sharedConfirmedForRun("next","project",source,tender).isEmpty());
    }
    @Test void waitingConfirmationNeverBecomesAutomaticReinvestigation(){var finding=new Finding("f","run",cp,new Evaluation("NEEDS_CONFIRMATION","待确认","",List.of(),0,"RULE"),Map.of("atomicCheck",Map.of("complete",false,"gaps",List.of(Map.of("code","INFERENCE_CONFIRMATION_REQUIRED")))));
        assertTrue(CheckResolution.unresolved(finding));assertEquals("WAITING_USER",CheckResolution.plan(finding).get("status"));assertEquals("USER_ACTION",BusinessResultPresentation.describe(finding.evaluation(),finding.assessment()).get("state"));}
}

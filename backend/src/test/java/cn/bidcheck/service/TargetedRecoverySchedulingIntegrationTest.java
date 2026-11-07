package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.VerificationService;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.bidcheck.service.BidService.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:targeted-recovery-scheduling;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false","bidcheck.visual.enabled=false","bidcheck.queue.enabled=false"})
class TargetedRecoverySchedulingIntegrationTest {
    static final Path DATA=temp();static Path temp(){try{return Files.createTempDirectory("bidcheck-targeted-recovery-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;@Autowired PlatformTransactionManager manager;
    @MockBean FocusedCheckEvaluator focused;@MockBean VerificationService verification;
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Fixture {
        private final String project;
        private final String runId;
        private final TaskRow task;
        private final Document bid;
        private final Finding before;
        @com.fasterxml.jackson.annotation.JsonCreator
        Fixture(@com.fasterxml.jackson.annotation.JsonProperty("project") String project, @com.fasterxml.jackson.annotation.JsonProperty("runId") String runId, @com.fasterxml.jackson.annotation.JsonProperty("task") TaskRow task, @com.fasterxml.jackson.annotation.JsonProperty("bid") Document bid, @com.fasterxml.jackson.annotation.JsonProperty("before") Finding before) { this.project = project; this.runId = runId; this.task = task; this.bid = bid; this.before = before; }
        public String project() { return project; }
        public String runId() { return runId; }
        public TaskRow task() { return task; }
        public Document bid() { return bid; }
        public Finding before() { return before; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fixture that = (Fixture) other; return java.util.Objects.equals(this.project, that.project) && java.util.Objects.equals(this.runId, that.runId) && java.util.Objects.equals(this.task, that.task) && java.util.Objects.equals(this.bid, that.bid) && java.util.Objects.equals(this.before, that.before); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.project); hash = 31 * hash + java.util.Objects.hashCode(this.runId); hash = 31 * hash + java.util.Objects.hashCode(this.task); hash = 31 * hash + java.util.Objects.hashCode(this.bid); hash = 31 * hash + java.util.Objects.hashCode(this.before); return hash; }
        @Override public String toString() { return "Fixture[" + "project=" + project + ", runId=" + runId + ", task=" + task + ", bid=" + bid + ", before=" + before + "]"; }
}
    Fixture fixture(Map<String,Object> atomic){
        String project=text(service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "定向恢复隔离测试")),"id"),run=Store.id(),doc=Store.id();
        Document bid=new Document(doc,project,"BID","fixture.pdf",1,"doc-hash",now(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,"本项目资格证明文件\n注册编号A123","PAGE","PDF_TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        store.save("document",doc,project,"",bid);
        TaskRow task=new TaskRow();task.id=Store.id();task.projectId=project;task.taskType="CHECK";task.status="RUNNING";task.progress=96;task.stage="补查";task.createdAt=now();task.updatedAt=task.createdAt;task.inputJson=store.encode(cn.bidcheck.compat.Java8Api.mapOf("runId", run, "documentId", doc));store.mapper().insertTask(task);
        store.save("run",run,project,doc,fields("id",run,"projectId",project,"taskId",task.id,"documentId",doc,"status","RUNNING","onDemandAttemptsReserved",0,"consistencyReviewAttemptsReserved",0));
        Checkpoint cp=new Checkpoint("cp","设计负责人资格","资格检查","HIGH","SEMANTIC_RESPONSE","person","EXISTS","查询截图与证书信息一致","",true,true,true,null);
        Finding before=new Finding(stableId(run+":"+cp.id()),run,cp,new Evaluation("UNDETERMINED","字段未对齐","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
        store.save("finding",before.id(),project,run,before);when(focused.supports(any())).thenReturn(true);
        // Standard scheduling now reaches the mode-aware entry point; delegate its
        // real FIXED_WORKFLOW branch back to the existing fixture's supports stub.
        when(focused.supports(any(),eq(CheckReviewMode.FIXED))).thenCallRealMethod();
        when(focused.identity()).thenReturn("fixture-only");return new Fixture(project,run,task,bid,before);
    }
    void execute(Fixture f)throws Exception{
        Function<Checkpoint,Map<String,Object>> contexts=cp->fields("priorSteps",cn.bidcheck.compat.Java8Api.listOf(),"visualObservations",store.maps("check_visual_observation",f.project(),f.runId()));
        Method method=BidService.class.getDeclaredMethod("continueResolution",TaskRow.class,String.class,Document.class,Document.class,List.class,Map.class,OnDemandModelBudget.class,Function.class);
        method.setAccessible(true);method.invoke(service,f.task(),f.runId(),f.bid(),null,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf(),new OnDemandModelBudget(store,new TransactionTemplate(manager),f.task(),f.runId()),contexts);
    }
    Map<String,Object> observation(Fixture f,String id,String value){return fields("id",id,"documentId",f.bid().id(),"checkpointId","cp","obligationId","R5","page",1,"locationType","PAGE","value",value,"type","FIELD",
        "source",fields("documentId",f.bid().id(),"documentHash",f.bid().sha256(),"documentVersion",1,"page",1,"renderedSha256",cn.bidcheck.compat.Java8Api.repeat("a", 64)));}
    @Test void existingObservationDoesNotStartAnotherTextAssessment()throws Exception{
        Fixture f=fixture(fields("visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "obligationId", "R5"))));
        Map<String,Object> old=observation(f,"old","A123"),same=observation(f,"new-receipt","A123");store.save("check_visual_observation",Store.id(),f.project(),f.runId(),old);
        when(verification.collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any())).thenReturn(cn.bidcheck.compat.Java8Api.listOf(same));
        execute(f);verify(focused,never()).assess(any(),any(),any(),anyMap());
        Map<String,Object> job=store.getMap("check_resolution",CheckResolution.jobId(f.runId(),"cp"));assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));
        assertTrue(CheckResolution.maps(job.get("diagnostics")).stream().anyMatch(d->"NO_NEW_VALID_OBSERVATION".equals(d.get("code"))));
        assertEquals(f.before(),store.get("finding",f.before().id(),Finding.class));
    }
    @Test void addedImageInputRecomputesOnlyBoundNodesAndPreservesFullProofContext()throws Exception{
        Fixture f=fixture(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","status","PASS","conditionRole","REQUIREMENT"), fields("id","R5","status","UNDETERMINED","conditionRole","REQUIREMENT")),
            "visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "obligationId", "R5"))));
        Map<String,Object> observation=observation(f,"new","A123");when(verification.collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any())).thenReturn(cn.bidcheck.compat.Java8Api.listOf(observation));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{Map<String,Object> context=call.getArgument(3);captured.add(context);
            return new FocusedCheckEvaluator.Result(f.before().evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields("complete",false,"gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "SUBJECT_RELATION_UNPROVEN")))));});
        execute(f);assertEquals(1,captured.size());Map<String,Object> context=captured.get(0);
        assertEquals("EVIDENCE_RECOMPUTE",context.get("recoveryMode"));assertEquals(false,context.get("recoveryModelAllowed"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("R5"),context.get("recoveryTargetObligationIds"));
        assertEquals(1,context.get("recoveryNewObservationCount"));assertEquals(CheckResolution.atomic(f.before()),context.get("previousAtomicCheck"));
        assertEquals(1,CheckResolution.maps(context.get("recoveryChangedLocations")).get(0).get("location"));
        assertEquals(0,((Number)store.getMap("run",f.runId()).get("onDemandAttemptsReserved")).intValue());
    }
    @Test void rejectedFieldAndEmptyVisualResultPersistNoProgressInsteadOfFailedRequest()throws Exception{
        Fixture f=fixture(fields("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(fields("status","FAILED","reason","FIELD_VALIDATION_FAILED")),
            "modelDiagnostics",fields("unresolvedFailures",0,"failures",cn.bidcheck.compat.Java8Api.listOf()),
            "visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "obligationId", "R5")),"gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_FIELD_UNREADABLE"))));
        when(verification.collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any())).thenReturn(cn.bidcheck.compat.Java8Api.listOf());
        execute(f);verify(focused,never()).assess(any(),any(),any(),anyMap());
        Map<String,Object> job=store.getMap("check_resolution",CheckResolution.jobId(f.runId(),"cp"));
        assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));
        java.util.Map<java.lang.String, java.lang.Object> outcome=CheckResolution.map(job.get("lastAttemptOutcome"));
        assertEquals(false,outcome.get("processingFailed"));assertEquals("NO_NEW_INPUT",outcome.get("executionState"));
        assertEquals(false,outcome.get("businessComplete"));
        assertEquals("UNDETERMINED",store.get("finding",f.before().id(),Finding.class).evaluation().status());
    }
    @Test void requirementRepairRunsLocallyOnceAndTheSameFailedInputCannotRequeue()throws Exception{
        Fixture f=fixture(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")),"compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "VALIDATED", "repairAttempts", 0),
            "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","obligationId","R5","reasonCode","REQUIREMENT_COMPARISON_TARGET_UNRESOLVED"))));
        when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{Map<String,Object> context=call.getArgument(3);assertEquals(false,context.get("recoveryModelAllowed"));assertEquals("RECOMPILE_REQUIREMENT",context.get("recoveryMode"));return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);String jobId=CheckResolution.jobId(f.runId(),"cp");Map<String,Object> job=store.getMap("check_resolution",jobId);
        assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));assertEquals(1,((Number)job.get("attempts")).intValue());
        job.put("status","QUEUED");store.save("check_resolution",jobId,f.project(),f.runId(),job);execute(f);
        verify(focused,times(1)).assess(any(),any(),isNull(),anyMap());verify(verification,never()).collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any());
        assertEquals(1,store.maps("check_resolution_attempt",f.project(),jobId).size());
        assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
    }
    @Test void sourceRestorationReachesTheExecutorInsteadOfBeingDiscardedByTheStrategyAllowlist()throws Exception{
        Fixture f=fixture(fields("plan",fields("method","SCOPE_UNRESOLVED","rules",cn.bidcheck.compat.Java8Api.listOf(),"issues",cn.bidcheck.compat.Java8Api.listOf("来源未绑定")),
            "compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","RESTORE_REQUIREMENT_SOURCE","reasonCode","REQUIREMENT_SCOPE_UNRESOLVED"))));
        when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{Map<String,Object> context=call.getArgument(3);
            assertEquals("RESTORE_REQUIREMENT_SOURCE",context.get("recoveryMode"));assertEquals(false,context.get("recoveryModelAllowed"));
            assertFalse(context.containsKey("recoveryTargetObligationIds"));return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);verify(focused,times(1)).assess(any(),any(),isNull(),anyMap());
        verify(verification,never()).collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any());
        assertEquals(1,store.maps("check_resolution_attempt",f.project(),CheckResolution.jobId(f.runId(),"cp")).size());
    }
    @Test void compilerVersionChangeReopensBlockedRequirementsOnceWithoutNewEvidence(){
        Fixture f=fixture(fields("compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"),"plan",cn.bidcheck.compat.Java8Api.mapOf("requirementScope", cn.bidcheck.compat.Java8Api.mapOf("requirementRevisions", cn.bidcheck.compat.Java8Api.mapOf("requirementCompilerRevision", "old"))),
            "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","REPAIR_REQUIREMENT","reasonCode","CAPABILITY_UNRESOLVED"))));
        String id=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",id,f.project(),f.runId(),fields("id",id,"checkpointId","cp","status","BLOCKED_NO_PROGRESS"));
        assertEquals(1,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        Map<String,Object> job=store.getMap("check_resolution",id);assertEquals("REPAIR_REQUIREMENT",job.get("strategy"));
        assertEquals(cn.bidcheck.verification.engine.RequirementRepairPolicy.versions(),job.get("requiredRequirementRevisions"));
        job.put("status","BLOCKED_NO_PROGRESS");store.save("check_resolution",id,f.project(),f.runId(),job);
        assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
    }
    @Test void repairedSchemaThenVisualGapRunsNewStrategyWithoutInventingBusinessProgress()throws Exception{
        Fixture f=fixture(fields("modelDiagnostics",fields("unresolvedFailures",1,"failures",cn.bidcheck.compat.Java8Api.listOf(fields("errorCode","OUTPUT_SCHEMA_INVALID","retryable",true))),"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","MODEL_OUTPUT_SCHEMA_INVALID"))));
        when(focused.assess(any(),any(),isNull(),anyMap())).thenReturn(new FocusedCheckEvaluator.Result(f.before().evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields("modelDiagnostics",cn.bidcheck.compat.Java8Api.mapOf("unresolvedFailures", 0),"visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "obligationId", "R5")),"gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_FIELD_UNREADABLE"))))));
        when(verification.collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any())).thenReturn(cn.bidcheck.compat.Java8Api.listOf());
        execute(f);verify(focused,times(1)).assess(any(),any(),isNull(),anyMap());verify(verification,times(1)).collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any());
        String jobId=CheckResolution.jobId(f.runId(),"cp");Map<String,Object> job=store.getMap("check_resolution",jobId);
        assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));assertEquals("VERIFY_SOURCE_PAGES",job.get("strategy"));assertEquals(2,((Number)job.get("attempts")).intValue());
        List<Map<String,Object>> attempts=store.maps("check_resolution_attempt",f.project(),jobId);
        assertTrue(attempts.stream().anyMatch(a->Boolean.TRUE.equals(CheckResolution.map(a.get("outcome")).get("technicalRecovered"))&&"FINISHED".equals(a.get("status"))));
        java.util.Map<java.lang.String, java.lang.Object> presentation=BusinessResultPresentation.describe(f.before().evaluation(),store.get("finding",f.before().id(),Finding.class).assessment(),job);
        assertEquals("EVIDENCE_PENDING",presentation.get("state"));
    }
    @Test void previouslyFinishedExactInputCannotBeScheduledAgain()throws Exception{
        Fixture f=fixture(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","EVIDENCE_NOT_FOUND")),
            "recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","RETRIEVE_EVIDENCE","obligationId","R5"))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");
        String input=CheckResolution.actionFingerprint(f.before(),service.resolutionPolicyIdentity(),f.bid().sha256(),"TARGETED_EVIDENCE");
        store.save("check_resolution_attempt",stableId(jobId+":"+input),f.project(),jobId,
            fields("strategy","TARGETED_EVIDENCE","inputFingerprint",input,"status","FINISHED","finishedAt",now()));
        execute(f);
        verify(focused,never()).assess(any(),any(),isNull(),anyMap());
        Map<String,Object> job=store.getMap("check_resolution",jobId);
        assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));assertTrue(text(job,"stopReason").contains("相同策略和证据"));
        assertEquals(1,store.maps("check_resolution_attempt",f.project(),jobId).size());
    }
    private Map<String,Object> dependency(String obligation,String key,long revision){return fields("obligationId",obligation,"observationKey",key,"modelIdentity","model",
        "target",fields("predicate","FIELD","subject","设计单位","field","证号","unit",""),"sourceIds",cn.bidcheck.compat.Java8Api.listOf("S1"),"observedRevision",revision);}
    private Map<String,Object> changedField(String id,String key){return fields("itemId",id,"recordType","FIELD_OBSERVATION","observationKey",key,"modelIdentity","model","sources",cn.bidcheck.compat.Java8Api.listOf(fields("id","S2","documentHash","doc-hash")));}
    @Test void scheduledEventsKeepTheirSourceAndSemanticKeyOnTheMatchingObligation(){
        List<Map<String,Object>> bindings=Arrays.asList(dependency("A","field-A",0),dependency("B","field-B",0));
        Fixture f=fixture(fields("gaps",Collections.singletonList(fields("code","EVIDENCE_NOT_FOUND")),
            "recoveryEvidenceDependencies",bindings));
        String jobId=CheckResolution.jobId(f.runId(),"cp");
        store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        Map<String,Object> a=changedField("fA","field-A"),b=changedField("fB","field-B");
        a.put("sources",Collections.singletonList(fields("id","source-A","documentHash",f.bid().sha256())));
        b.put("sources",Collections.singletonList(fields("id","source-B","documentHash",f.bid().sha256())));
        for(Map<String,Object> event:Arrays.asList(a,b))
            OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"producer",fields("changedEvidence",Collections.singletonList(event)));
        assertEquals(1,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        Map<String,Object> trigger=CheckResolution.map(store.getMap("check_resolution",jobId).get("dependencyTrigger"));
        Map<String,Object> byTarget=CheckResolution.map(trigger.get("byTarget"));
        Map<String,Object> first=CheckResolution.map(byTarget.get("A")),second=CheckResolution.map(byTarget.get("B"));
        assertEquals(Collections.singletonList(RecoveryEvidenceIdentity.key(a)),first.get("semanticEvidenceKeys"));
        assertEquals(Collections.singletonList(RecoveryEvidenceIdentity.key(b)),second.get("semanticEvidenceKeys"));
        assertEquals(Collections.singletonList("source-A"),first.get("sourceIds"));
        assertEquals(Collections.singletonList("source-B"),second.get("sourceIds"));
        assertEquals(1,((List<?>)first.get("eventIds")).size());
        assertNotEquals(first.get("eventIds"),second.get("eventIds"));
        assertEquals(2,((List<?>)trigger.get("eventIds")).size());
    }
    @Test void aNewSharedFieldRequeuesOnlyBoundBlockedNodesOnceAndNeverPublishesAConclusion(){
        Fixture f=fixture(fields("recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("R1","cert-key",0), dependency("R2","unrelated-key",0))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");
        store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS","strategy","BLOCKED"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("new-field-1","cert-key"))));
        assertEquals(1,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        Map<String,Object> job=store.getMap("check_resolution",jobId);
        assertEquals("QUEUED",job.get("status"));assertEquals("RECOMPUTE_SHARED_EVIDENCE",job.get("strategy"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("R1"),job.get("dependencyTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("S2"),CheckResolution.map(job.get("dependencyTrigger")).get("sourceIds"));
        assertEquals(false,CheckResolution.map(job.get("dependencyTrigger")).get("businessProof"));
        assertEquals(store.encode(f.before()),store.encode(store.get("finding",f.before().id(),Finding.class)));
        job.put("status","BLOCKED_NO_PROGRESS");store.save("check_resolution",jobId,f.project(),f.runId(),job);
        assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()),"an attempted change is not scheduled repeatedly");
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("new-field-1","cert-key"))));
        assertEquals(1,OrdinaryRecoveryDependencies.currentRevision(store,f.project(),f.runId()));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("new-field-2","cert-key"))));
        assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()),"a new receipt for the same observation/source cannot reopen the missing field");
        Map<String,Object> changedSource=changedField("new-field-3","cert-key");changedSource.put("sources",cn.bidcheck.compat.Java8Api.listOf(fields("id","S3","documentHash","doc-hash","documentVersion",1,"page",33)));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedSource)));
        assertEquals(1,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()),"a newly read matching source can reopen the missing field");
    }
    @Test void unrelatedSelfPublishedAndAlreadyObservedFactsDoNotWakeBlockedChecks(){
        Fixture f=fixture(fields("recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("R1","cert-key",2))));String jobId=CheckResolution.jobId(f.runId(),"cp");
        store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS","strategy","BLOCKED"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("old-field","cert-key"))));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("unrelated-field","other-key"))));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("own-field","cert-key"))));
        assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
        Map<String,Object> nativeFact=fields("itemId","native-fact","recordType","FACT","fact",fields("kind","FIELD","subject","施工单位","field","证号","unit",""),"sources",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S2")));
        assertFalse(OrdinaryRecoveryDependencies.matches(dependency("R1","cert-key",0),nativeFact));
        nativeFact.put("fact",fields("kind","FIELD","subject","设计单位","field","证号","unit",""));
        assertTrue(OrdinaryRecoveryDependencies.matches(dependency("R1","cert-key",0),nativeFact));
    }
    @Test void newRoleAndEmploymentEvidenceOnlyWakeTheirDependentCertificate(){
        Map<String,Object> design=dependency("design","design-certificate",0);design.put("target",fields("predicate","CERTIFICATE","subject","设计负责人","field","注册证书","parameters",cn.bidcheck.compat.Java8Api.mapOf("employer", "设计单位")));design.put("resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf("张三"));
        Map<String,Object> construction=dependency("construction","construction-certificate",0);construction.put("target",fields("predicate","CERTIFICATE","subject","施工负责人","field","注册证书","parameters",cn.bidcheck.compat.Java8Api.mapOf("employer", "施工单位")));construction.put("resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf("李四"));
        Map<String,Object> event=fields("itemId","role-edge","recordType","FACT","sources",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S22")),
            "fact",fields("kind","PERSON_ROLE","subject","设计负责人","field","role","value","张三","scope","CURRENT_PROJECT"));
        assertTrue(OrdinaryRecoveryDependencies.matches(design,event));assertFalse(OrdinaryRecoveryDependencies.matches(construction,event));
        event.put("fact",fields("kind","RELATION","subject","张三","field","受雇于","value","甲设计有限公司","scope","CURRENT_PROJECT","attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "甲设计有限公司")));
        assertTrue(OrdinaryRecoveryDependencies.matches(design,event));assertFalse(OrdinaryRecoveryDependencies.matches(construction,event));
        event.put("fact",fields("kind","RELATION","subject","张三","field","受雇于","value","甲设计有限公司","scope","HISTORICAL_PROJECT"));
        assertFalse(OrdinaryRecoveryDependencies.matches(design,event));
    }
    @Test void newCertificateForResolvedCompanyOrPersonWakesItsRoleRequirementOnly(){
        Map<String,Object> design=dependency("design","design-key",0);design.put("target",fields("predicate","CERTIFICATE","subject","设计单位","field","资质证书","parameters",cn.bidcheck.compat.Java8Api.mapOf()));design.put("resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf("甲公司"));
        Map<String,Object> construction=dependency("construction","construction-key",0);construction.put("target",fields("predicate","CERTIFICATE","subject","施工单位","field","资质证书","parameters",cn.bidcheck.compat.Java8Api.mapOf()));construction.put("resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf("乙公司"));
        Map<String,Object> event=fields("itemId","cert-company","recordType","FACT","sources",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S22")),
            "fact",fields("kind","CERTIFICATE","subject","甲公司","field","资质证书","scope","CURRENT_PROJECT"));
        assertTrue(OrdinaryRecoveryDependencies.matches(design,event));assertFalse(OrdinaryRecoveryDependencies.matches(construction,event));
        design.put("target",fields("predicate","VALIDITY","subject","设计负责人","field","注册证书","parameters",cn.bidcheck.compat.Java8Api.mapOf()));design.put("resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf("张三"));
        event.put("fact",fields("kind","CERTIFICATE","subject","张三","field","注册证书","scope","CURRENT_PROJECT"));
        assertTrue(OrdinaryRecoveryDependencies.matches(design,event));assertFalse(OrdinaryRecoveryDependencies.matches(construction,event));
        event.put("fact",fields("kind","CERTIFICATE","subject","李四","field","注册证书","scope","CURRENT_PROJECT"));assertFalse(OrdinaryRecoveryDependencies.matches(design,event));
        event.put("fact",fields("kind","CERTIFICATE","subject","张三","field","另一证书","scope","CURRENT_PROJECT"));assertFalse(OrdinaryRecoveryDependencies.matches(design,event));
    }
    @Test void obsoleteRuleBindingsAreRetainedForAuditButNeverScheduled(){
        Fixture f=fixture(fields("recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("old-rule","old-key",0))));String jobId=CheckResolution.jobId(f.runId(),"cp");
        store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS","strategy","BLOCKED"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        Finding changed=new Finding(f.before().id(),f.runId(),f.before().checkpoint(),f.before().evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields("recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("new-rule","new-key",0)))));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),changed);
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("obsolete-field","old-key"))));
        assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        assertEquals(1,store.maps("ordinary_recovery_binding",f.project(),f.runId()).stream().filter(b->Boolean.FALSE.equals(b.get("active"))).count());
    }
    @Test void changedCanonicalEvidenceFlowsThroughSchedulerIntoOnlyTheSelectedRecoveryNode()throws Exception{
        Fixture f=fixture(fields("recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("R1","first-key",0), dependency("R2","second-key",0)),
            "conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","status","UNDETERMINED"), fields("id","R2","status","UNDETERMINED"))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS","strategy","BLOCKED"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        Map<String,Object> change=changedField("second-field","second-key");change.put("sources",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S2"), cn.bidcheck.compat.Java8Api.mapOf("id", "S3")));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another-cp",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(change)));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{captured.add(new LinkedHashMap<>(call.getArgument(3)));
            return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);assertEquals(1,captured.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("R2"),captured.get(0).get("recoveryTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("S2", "S3"),captured.get(0).get("recoveryChangedSourceIds"));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(captured.get(0).get("recoveryDependencyRevision"),"")));
        assertEquals("EVIDENCE_RECOMPUTE",captured.get(0).get("recoveryMode"));assertEquals(false,captured.get(0).get("recoveryModelAllowed"));
        assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
        assertEquals("UNDETERMINED",store.get("finding",f.before().id(),Finding.class).evaluation().status());
    }
    @Test void semanticallyEquivalentFactReceiptsDoNotAdvanceClockOrWakeAnotherPass(){
        Fixture f=fixture(fields("recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("R1","cert-key",0))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        Map<String,Object> source=fields("id","alias-1","documentId",f.bid().id(),"documentHash",f.bid().sha256(),"documentVersion",1,"page",22);
        Map<String,Object> fact=fields("id","fact-1","kind","FIELD","subject","设计单位","field","证号","value","A123","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf("alias-1"));
        Map<String,Object> event=fields("itemId","receipt-1","recordType","FACT","fact",fact,"sources",cn.bidcheck.compat.Java8Api.listOf(source));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(event)));
        assertEquals(1,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        Map<String,Object> job=store.getMap("check_resolution",jobId);job.put("status","BLOCKED_NO_PROGRESS");store.save("check_resolution",jobId,f.project(),f.runId(),job);
        event.put("itemId","receipt-2");fact.put("id","fact-2");fact.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("alias-2"));source.put("id","alias-2");fact.put("confidence",0.99);
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(event)));
        assertEquals(1,OrdinaryRecoveryDependencies.currentRevision(store,f.project(),f.runId()));assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
        fact.put("value","A124");OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"another",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(event)));
        assertEquals(2,OrdinaryRecoveryDependencies.currentRevision(store,f.project(),f.runId()));assertEquals(1,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));
    }
    @Test void completedPersonnelDependencyIsRevalidatedLocallyBeforeSuppressingModelEscalation()throws Exception{
        for(String id:cn.bidcheck.compat.Java8Api.listOf("D49da4c734dd7c16f1fe7-ROLE-5", "D68837233c15fdac3e078-ROLE-2", "D68837233c15fdac3e078-ROLE-5")){
            Fixture f=fixture(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id",id,"status","PASS","gaps",cn.bidcheck.compat.Java8Api.listOf()), fields("id","SOCIAL","status","UNDETERMINED")),
                "recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency(id,"cert-key",0)),"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","SOCIAL_EMPLOYER_REQUIRED"))));
            String jobId=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS"));
            OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
            OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("new-field","cert-key"))));
            List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{captured.add(new LinkedHashMap<>(call.getArgument(3)));
                return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
            execute(f);assertEquals(1,captured.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf(id),captured.get(0).get("recoveryTargetObligationIds"));
            assertEquals("RECOMPUTE_SHARED_EVIDENCE",captured.get(0).get("recoveryStrategy"));assertEquals(false,captured.get(0).get("recoveryModelAllowed"));
            assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
            assertEquals("UNDETERMINED",store.get("finding",f.before().id(),Finding.class).evaluation().status());
        }
    }
    @Test void newContradictoryValueRevalidatesAPreviouslyPassedConditionWithoutCallingModels()throws Exception{
        Fixture f=fixture(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","CERT","status","PASS","gaps",cn.bidcheck.compat.Java8Api.listOf()), fields("id","OTHER","status","UNDETERMINED")),
            "recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("CERT","cert-key",0)),"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","SOCIAL_EMPLOYER_REQUIRED"))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        Map<String,Object> contrary=fields("itemId","contrary-value","recordType","FACT",
            "fact",fields("kind","FIELD","subject","设计单位","field","证号","value","A124","scope","CURRENT_PROJECT"),
            "sources",cn.bidcheck.compat.Java8Api.listOf(fields("id","S33","documentHash",f.bid().sha256(),"documentVersion",1,"page",33)));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(contrary)));
        Map<String,Object> updated=fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","CERT","status","FAIL","gaps",cn.bidcheck.compat.Java8Api.listOf()), fields("id","OTHER","status","UNDETERMINED")),
            "recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("CERT","cert-key",1)),"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","SOCIAL_EMPLOYER_REQUIRED")));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{captured.add(new LinkedHashMap<>(call.getArgument(3)));
            return new FocusedCheckEvaluator.Result(f.before().evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", updated));});
        execute(f);assertEquals(1,captured.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("CERT"),captured.get(0).get("recoveryTargetObligationIds"));
        assertEquals(false,captured.get(0).get("recoveryModelAllowed"));assertEquals("EVIDENCE_RECOMPUTE",captured.get(0).get("recoveryMode"));
        Finding actual=store.get("finding",f.before().id(),Finding.class);assertEquals("FAIL",CheckResolution.maps(CheckResolution.atomic(actual).get("conditions")).get(0).get("status"));
        assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
    }
    @Test void localRecomputeThenTargetedInvestigationCannotBounceOnReceiptOnlyChanges()throws Exception{
        Fixture f=fixture(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","status","UNDETERMINED"), fields("id","OLD","status","UNDETERMINED")),
            "gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","EVIDENCE_NOT_FOUND")),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","RETRIEVE_EVIDENCE","obligationId","R1")),
            "recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("R1","cert-key",0))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","BLOCKED_NO_PROGRESS"));
        OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("first","cert-key"))));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{Map<String,Object> ctx=new LinkedHashMap<>(call.getArgument(3));captured.add(ctx);
            OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("copy-"+captured.size(),"cert-key"))));
            return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);assertEquals(2,captured.size());assertEquals("RECOMPUTE_SHARED_EVIDENCE",captured.get(0).get("recoveryStrategy"));assertEquals(false,captured.get(0).get("recoveryModelAllowed"));
        assertEquals("TARGETED_EVIDENCE",captured.get(1).get("recoveryStrategy"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("R1"),captured.get(1).get("recoveryTargetObligationIds"));
        assertEquals(1,OrdinaryRecoveryDependencies.currentRevision(store,f.project(),f.runId()));assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
        execute(f);assertEquals(2,captured.size(),"a second scheduler pass has neither a new source nor new semantic input");
    }
    @Test void changedVisualReceiptCannotRestartAnAlreadyConsumedTargetInvestigation()throws Exception{
        Fixture f=fixture(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","status","UNDETERMINED","proof",fields("factIds",cn.bidcheck.compat.Java8Api.listOf("stable-fact")))),
            "gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","SUBJECT_RELATION_UNPROVEN")),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","RETRIEVE_EVIDENCE","obligationId","R1"))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");
        store.save("check_resolution_attempt","consumed-"+f.runId(),f.project(),jobId,fields("strategy","TARGETED_EVIDENCE","status","NO_PROGRESS",
            "semanticConsumedInputs",RecoverySemanticGate.inputs(f.before(),fields("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("R1")),service.resolutionPolicyIdentity())));
        store.save("check_visual_observation",Store.id(),f.project(),f.runId(),observation(f,"new-label-new-receipt","同一文字"));
        execute(f);verify(focused,never()).assess(any(),any(),any(),anyMap());
        java.util.Map<java.lang.String, java.lang.Object> job=store.getMap("check_resolution",jobId);assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));
        assertEquals("TARGET_SEMANTIC_INPUT_CONSUMED",CheckResolution.map(job.get("semanticGate")).get("reasonCode"));
        assertEquals(f.before(),store.get("finding",f.before().id(),Finding.class),"scheduling must not rewrite the business result");
    }

    @Test void persistedTargetGateLeavesIndependentPendingTargetsRunnable()throws Exception{
        Fixture f=fixture(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","status","UNDETERMINED"), fields("id","R2","status","UNDETERMINED")),
            "gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","EVIDENCE_NOT_FOUND")),"recoveryActions",cn.bidcheck.compat.Java8Api.listOf(fields("strategy","RETRIEVE_EVIDENCE","obligationId","R1"), fields("strategy","RETRIEVE_EVIDENCE","obligationId","R2"))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");
        store.save("check_resolution_attempt","consumed-"+f.runId(),f.project(),jobId,fields("strategy","TARGETED_EVIDENCE","status","NO_PROGRESS",
            "semanticConsumedInputs",RecoverySemanticGate.inputs(f.before(),fields("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("R1")),service.resolutionPolicyIdentity())));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{captured.add(new LinkedHashMap<>(call.getArgument(3)));
            return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);assertEquals(1,captured.size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("R2"),captured.get(0).get("recoveryTargetObligationIds"));
        assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
    }

    @Test void stalledAgentWaitsForNovelMatchingEvidenceAndOnlyRecomputesItsAffectedTarget()throws Exception{
        Map<String,Object> receipt=fields("status","FAILED","errorCode","AGENT_STALLED_WITH_REMAINING_TARGETS",
            "remainingTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("B"),"blockedTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("A"),"completedTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("C"),
            "investigationIncomplete",true,"stopScope","CURRENT_INVESTIGATION_ONLY");
        Fixture f=fixture(fields("complete",false,"agentInvestigation",receipt,"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","AGENT_INVESTIGATION_STALLED")),
            "conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","B","status","UNDETERMINED")),"recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("B","cert-key",0))));
        String jobId=CheckResolution.jobId(f.runId(),"cp");
        execute(f);verify(focused,never()).assess(any(),any(),any(),anyMap());
        assertEquals("FAILED",store.getMap("check_resolution",jobId).get("status"));assertEquals("WAIT_AGENT_INPUT_CHANGE",store.getMap("check_resolution",jobId).get("strategy"));
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("unrelated","other-key"))));
        execute(f);verify(focused,never()).assess(any(),any(),any(),anyMap());
        OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("new-field","cert-key"))));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{
            captured.add(new LinkedHashMap<>(call.getArgument(3)));return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);assertEquals(1,captured.size());assertEquals("RECOMPUTE_SHARED_EVIDENCE",captured.get(0).get("recoveryStrategy"));
        assertEquals(false,captured.get(0).get("recoveryModelAllowed"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("B"),captured.get(0).get("recoveryTargetObligationIds"));
        Map<String,Object> job=store.getMap("check_resolution",jobId);assertEquals("FAILED",job.get("status"));
        assertEquals("NEW_RELEVANT_EVIDENCE_AFTER_AGENT_STALL",job.get("resumeReasonCode"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("B"),job.get("remainingTargetObligationIds"));
        Finding actual=store.get("finding",f.before().id(),Finding.class);
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(actual.evaluation(),actual.assessment(),job).get("state"));
        assertEquals(receipt,CheckResolution.map(CheckResolution.atomic(actual).get("agentInvestigation")));
        execute(f);assertEquals(1,captured.size(),"the consumed event must not restart a stalled investigation");
    }

    @Test void evidenceEventsDoNotWakeGenericFailedServicesEvenWithStalledStrategyLabel(){
        for(String strategy:cn.bidcheck.compat.Java8Api.listOf("RETRY_FAILED_STEP", "WAIT_AGENT_INPUT_CHANGE")){
            Fixture f=fixture(fields("agentInvestigation",fields("status","FAILED","errorCode","WORKER_DISCONNECTED"),
                "gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","AGENT_INVESTIGATION_FAILED")),"recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("B","cert-key",0))));
            String jobId=CheckResolution.jobId(f.runId(),"cp");store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","FAILED","strategy",strategy));
            OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),f.before());
            OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(changedField("new-field","cert-key"))));
            assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()));assertEquals("FAILED",store.getMap("check_resolution",jobId).get("status"));
        }
    }

    @Test void fullySatisfiedCheckIsRevalidatedByNovelCounterevidenceWithOrWithoutExistingJob()throws Exception{
        for(boolean existingJob:cn.bidcheck.compat.Java8Api.listOf(false, true)){
            Fixture f=fixture(fields("complete",true,"workflowState","COMPLETED","conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","CERT","status","PASS","gaps",cn.bidcheck.compat.Java8Api.listOf())),
                "recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("CERT","cert-key",0)),"gaps",cn.bidcheck.compat.Java8Api.listOf()));
            Evidence evidence=new Evidence(f.bid().id(),f.bid().name(),1,"PAGE",f.bid().pages().get(0).text(),"chars:0",1);
            Finding satisfied=new Finding(f.before().id(),f.runId(),f.before().checkpoint(),new Evaluation("PASS","前次证书核验满足要求","",cn.bidcheck.compat.Java8Api.listOf(evidence),1,"FOCUSED_ATOMIC_RULES"),f.before().assessment());
            store.save("finding",satisfied.id(),f.project(),f.runId(),satisfied);OrdinaryRecoveryDependencies.bind(store,f.project(),f.runId(),satisfied);
            String jobId=CheckResolution.jobId(f.runId(),"cp");
            if(existingJob)store.save("check_resolution",jobId,f.project(),f.runId(),fields("id",jobId,"checkpointId","cp","status","SUCCEEDED","strategy","COMPLETE","attempts",1));
            assertEquals("SATISFIED",BusinessResultPresentation.describe(satisfied.evaluation(),satisfied.assessment()).get("state"));
            assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()),"no event cannot create broad rechecks");
            Map<String,Object> contrary=fields("itemId","contrary-"+f.runId(),"recordType","FACT","fact",fields("kind","FIELD","subject","设计单位","field","证号","value","A123","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf("S33")),
                "sources",cn.bidcheck.compat.Java8Api.listOf(fields("id","S33","documentHash",f.bid().sha256(),"documentVersion",1,"page",1)));
            OrdinaryRecoveryDependencies.publishChanges(store,f.project(),f.runId(),"other-checkpoint",fields("changedEvidence",cn.bidcheck.compat.Java8Api.listOf(contrary)));
            List<Map<String,Object>> captured=new ArrayList<>();
            Map<String,Object> riskAtomic=fields("complete",true,"workflowState","COMPLETED","conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","CERT","status","FAIL","gaps",cn.bidcheck.compat.Java8Api.listOf())),"recoveryEvidenceDependencies",cn.bidcheck.compat.Java8Api.listOf(dependency("CERT","cert-key",1)),"gaps",cn.bidcheck.compat.Java8Api.listOf());
            when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{captured.add(new LinkedHashMap<>(call.getArgument(3)));
                return new FocusedCheckEvaluator.Result(new Evaluation("FAIL","新证据与原证号不一致","核对补充证据",cn.bidcheck.compat.Java8Api.listOf(evidence),1,"FOCUSED_ATOMIC_RULES"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", riskAtomic));});
            execute(f);assertEquals(1,captured.size());assertEquals("RECOMPUTE_SHARED_EVIDENCE",captured.get(0).get("recoveryStrategy"));
            assertEquals(false,captured.get(0).get("recoveryModelAllowed"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("CERT"),captured.get(0).get("recoveryTargetObligationIds"));
            Finding actual=store.get("finding",satisfied.id(),Finding.class);assertEquals("FAIL",actual.evaluation().status());
            assertEquals("RISK",BusinessResultPresentation.describe(actual.evaluation(),actual.assessment()).get("state"));
            assertEquals(0,OrdinaryRecoveryDependencies.schedule(store,f.project(),f.runId()),"the same counterevidence must be consumed once");
        }
    }
}

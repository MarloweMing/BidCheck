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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @MockitoBean FocusedCheckEvaluator focused;@MockitoBean VerificationService verification;
    record Fixture(String project,String runId,TaskRow task,Document bid,Finding before) {}
    Fixture fixture(Map<String,Object> atomic){
        String project=text(service.createProject(Map.of("name","定向恢复隔离测试")),"id"),run=Store.id(),doc=Store.id();
        Document bid=new Document(doc,project,"BID","fixture.pdf",1,"doc-hash",now(),List.of(new Page(1,"本项目资格证明文件\n注册编号A123","PAGE","PDF_TEXT","")),List.of());
        store.save("document",doc,project,"",bid);
        TaskRow task=new TaskRow();task.id=Store.id();task.projectId=project;task.taskType="CHECK";task.status="RUNNING";task.progress=96;task.stage="补查";task.createdAt=now();task.updatedAt=task.createdAt;task.inputJson=store.encode(Map.of("runId",run,"documentId",doc));store.mapper().insertTask(task);
        store.save("run",run,project,doc,fields("id",run,"projectId",project,"taskId",task.id,"documentId",doc,"status","RUNNING","onDemandAttemptsReserved",0,"consistencyReviewAttemptsReserved",0));
        Checkpoint cp=new Checkpoint("cp","设计负责人资格","资格检查","HIGH","SEMANTIC_RESPONSE","person","EXISTS","查询截图与证书信息一致","",true,true,true,null);
        Finding before=new Finding(stableId(run+":"+cp.id()),run,cp,new Evaluation("UNDETERMINED","字段未对齐","",List.of(),0,"FOCUSED_ATOMIC_RULES"),Map.of("atomicCheck",atomic));
        store.save("finding",before.id(),project,run,before);when(focused.supports(any())).thenReturn(true);when(focused.identity()).thenReturn("fixture-only");return new Fixture(project,run,task,bid,before);
    }
    void execute(Fixture f)throws Exception{
        Function<Checkpoint,Map<String,Object>> contexts=cp->fields("priorSteps",List.of(),"visualObservations",store.maps("check_visual_observation",f.project(),f.runId()));
        Method method=BidService.class.getDeclaredMethod("continueResolution",TaskRow.class,String.class,Document.class,Document.class,List.class,Map.class,OnDemandModelBudget.class,Function.class);
        method.setAccessible(true);method.invoke(service,f.task(),f.runId(),f.bid(),null,List.of(),Map.of(),new OnDemandModelBudget(store,new TransactionTemplate(manager),f.task(),f.runId()),contexts);
    }
    Map<String,Object> observation(Fixture f,String id,String value){return fields("id",id,"documentId",f.bid().id(),"checkpointId","cp","obligationId","R5","page",1,"locationType","PAGE","value",value,"type","FIELD",
        "source",fields("documentId",f.bid().id(),"documentHash",f.bid().sha256(),"documentVersion",1,"page",1,"renderedSha256","a".repeat(64)));}
    @Test void existingObservationDoesNotStartAnotherTextAssessment()throws Exception{
        Fixture f=fixture(fields("visualRequests",List.of(Map.of("page",1,"obligationId","R5"))));
        Map<String,Object> old=observation(f,"old","A123"),same=observation(f,"new-receipt","A123");store.save("check_visual_observation",Store.id(),f.project(),f.runId(),old);
        when(verification.collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any())).thenReturn(List.of(same));
        execute(f);verify(focused,never()).assess(any(),any(),any(),anyMap());
        Map<String,Object> job=store.getMap("check_resolution",CheckResolution.jobId(f.runId(),"cp"));assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));
        assertTrue(CheckResolution.maps(job.get("diagnostics")).stream().anyMatch(d->"NO_NEW_VALID_OBSERVATION".equals(d.get("code"))));
        assertEquals(f.before(),store.get("finding",f.before().id(),Finding.class));
    }
    @Test void addedImageInputRecomputesOnlyBoundNodesAndPreservesFullProofContext()throws Exception{
        Fixture f=fixture(fields("conditions",List.of(fields("id","R1","status","PASS","conditionRole","REQUIREMENT"),fields("id","R5","status","UNDETERMINED","conditionRole","REQUIREMENT")),
            "visualRequests",List.of(Map.of("page",1,"obligationId","R5"))));
        Map<String,Object> observation=observation(f,"new","A123");when(verification.collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any())).thenReturn(List.of(observation));
        List<Map<String,Object>> captured=new ArrayList<>();when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{Map<String,Object> context=call.getArgument(3);captured.add(context);
            return new FocusedCheckEvaluator.Result(f.before().evaluation(),Map.of("atomicCheck",fields("complete",false,"gaps",List.of(Map.of("code","SUBJECT_RELATION_UNPROVEN")))));});
        execute(f);assertEquals(1,captured.size());Map<String,Object> context=captured.get(0);
        assertEquals("RECOMPUTE_ONLY",context.get("recoveryMode"));assertEquals(false,context.get("recoveryModelAllowed"));assertEquals(List.of("R5"),context.get("recoveryTargetObligationIds"));
        assertEquals(1,context.get("recoveryNewObservationCount"));assertEquals(CheckResolution.atomic(f.before()),context.get("previousAtomicCheck"));
        assertEquals(1,CheckResolution.maps(context.get("recoveryChangedLocations")).get(0).get("location"));
        assertEquals(0,((Number)store.getMap("run",f.runId()).get("onDemandAttemptsReserved")).intValue());
    }
    @Test void requirementRepairRunsLocallyOnceAndTheSameFailedInputCannotRequeue()throws Exception{
        Fixture f=fixture(fields("gaps",List.of(Map.of("code","REQUIREMENT_COMPARISON_TARGET_UNRESOLVED")),"compilationAudit",Map.of("state","VALIDATED","repairAttempts",0),
            "recoveryActions",List.of(fields("strategy","REPAIR_REQUIREMENT","obligationId","R5","reasonCode","REQUIREMENT_COMPARISON_TARGET_UNRESOLVED"))));
        when(focused.assess(any(),any(),isNull(),anyMap())).thenAnswer(call->{Map<String,Object> context=call.getArgument(3);assertEquals(false,context.get("recoveryModelAllowed"));assertEquals("RECOMPUTE_ONLY",context.get("recoveryMode"));return new FocusedCheckEvaluator.Result(f.before().evaluation(),f.before().assessment());});
        execute(f);String jobId=CheckResolution.jobId(f.runId(),"cp");Map<String,Object> job=store.getMap("check_resolution",jobId);
        assertEquals("BLOCKED_NO_PROGRESS",job.get("status"));assertEquals(1,((Number)job.get("attempts")).intValue());
        job.put("status","QUEUED");store.save("check_resolution",jobId,f.project(),f.runId(),job);execute(f);
        verify(focused,times(1)).assess(any(),any(),isNull(),anyMap());verify(verification,never()).collectCheckVisual(any(),anyString(),any(),anyMap(),anyList(),anyList(),any());
        assertEquals(1,store.maps("check_resolution_attempt",f.project(),jobId).size());
        assertEquals("BLOCKED_NO_PROGRESS",store.getMap("check_resolution",jobId).get("status"));
    }
}

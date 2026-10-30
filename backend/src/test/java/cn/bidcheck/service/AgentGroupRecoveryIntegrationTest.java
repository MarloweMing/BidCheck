package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:agent-group-recovery;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000",
    "bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false","bidcheck.visual.enabled=false","bidcheck.queue.enabled=false"})
class AgentGroupRecoveryIntegrationTest {
    static final Path DATA=temp();private static Path temp(){try{return Files.createTempDirectory("bidcheck-group-recovery-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;@Autowired PlatformTransactionManager manager;
    @MockitoBean FocusedCheckEvaluator focused;
    record Fixture(String project,String run,TaskRow task,Document bid,Document tender,Finding before) {}
    private Fixture fixture(){
        String project=text(service.createProject(Map.of("name","业务组恢复隔离测试")),"id"),run=Store.id();
        Document bid=new Document(Store.id(),project,"BID","投标.pdf",1,"bid-hash",now(),List.of(new Page(1,"营业执照：甲公司","PAGE","PDF_TEXT","")),List.of());
        String obligation="投标人须提交有效营业执照。";Document tender=new Document(Store.id(),project,"TENDER","招标.docx",1,"tender-hash",now(),List.of(
            new Page(1,"本项目不划分标段。","BLOCK","TEXT",""),new Page(2,"投标人资格要求","BLOCK","TEXT",""),
            new Page(3,obligation,"BLOCK","TEXT",""),new Page(4,obligation,"BLOCK","TEXT","")),List.of());
        store.save("document",bid.id(),project,"",bid);store.save("document",tender.id(),project,"",tender);
        TaskRow task=new TaskRow();task.id=Store.id();task.projectId=project;task.taskType="CHECK";task.status="RUNNING";task.progress=96;task.stage="补查";task.createdAt=now();task.updatedAt=task.createdAt;
        task.inputJson=store.encode(Map.of("runId",run,"documentId",bid.id()));store.mapper().insertTask(task);
        store.save("run",run,project,bid.id(),fields("id",run,"projectId",project,"taskId",task.id,"documentId",bid.id(),"tenderDocumentId",tender.id(),"reviewMode",CheckReviewMode.AGENT,"status","RUNNING","onDemandAttemptsReserved",0));
        Finding follower=null;
        for(int i=0;i<2;i++){String id=i==0?"leader":"follower";Checkpoint cp=new Checkpoint(id,"营业执照","资格检查","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",obligation,"",true,true,true,
            new Evidence(tender.id(),tender.name(),i+3,"BLOCK",obligation,"",1));store.save("snapshot",stableId(run+":snapshot:"+id),project,run,cp);
            Finding finding=new Finding(stableId(run+":"+id),run,cp,new Evaluation("UNDETERMINED","Agent 调查失败","",List.of(),0,"AGENT_LED_REVIEW"),Map.of("atomicCheck",fields("complete",false,
                "conditions",List.of(fields("id","R1","conditionRole","REQUIREMENT","status","UNDETERMINED","predicate","MATERIAL","field","营业执照")),
                "agentInvestigation",fields("status","FAILED","errorCode","WORKER_DISCONNECTED"),"gaps",List.of(Map.of("code","AGENT_INVESTIGATION_STALLED")))));
            store.save("finding",finding.id(),project,run,finding);if(i==1)follower=finding;
            store.save("check_resolution",CheckResolution.jobId(run,id),project,run,fields("id",CheckResolution.jobId(run,id),"runId",run,"checkpointId",id,"status",i==1?"QUEUED":"BLOCKED_NO_PROGRESS","strategy","WAIT_AGENT_INPUT_CHANGE","requiresInputChange",true,"attempts",0));
        }
        when(focused.supports(any(),eq(CheckReviewMode.AGENT))).thenReturn(true);when(focused.identity()).thenReturn("fixture");
        Fixture fixture=new Fixture(project,run,task,bid,tender,follower);saveSnapshot(fixture,"first","bid-hash");return fixture;
    }
    private void saveSnapshot(Fixture f,String id,String sourceHash){
        String parent=stableId(f.run()+":atomic-steps:leader");Map<String,Object> snapshot=fields("id",id,"checkpointId","leader","runId",f.run(),"type",AgentBusinessGroupReuse.FACT_SNAPSHOT,"status","COMPLETED","schemaVersion","schema","engineVersion","engine","modelIdentity","model",
            "output",fields("sourceIds",List.of("S1"),"sources",List.of(fields("id","S1","projectId",f.project(),"documentId",f.bid().id(),"documentHash",sourceHash,"documentVersion",1,"parserFingerprint","parser","sourceFingerprint","page1")),
                "facts",List.of(fields("id","F"+id,"kind","MATERIAL","subject","甲公司","field","营业执照","rawValue","营业执照：甲公司","value","营业执照","sourceIds",List.of("S1")))));
        store.save("check_atomic_step",stableId(parent+":"+id),f.project(),parent,snapshot);
    }
    private void execute(Fixture f)throws Exception{
        Function<Checkpoint,Map<String,Object>> contexts=cp->{String parent=stableId(f.run()+":atomic-steps:"+cp.id());return fields("reviewMode",CheckReviewMode.AGENT,"priorSteps",store.maps("check_atomic_step",f.project(),parent),
            "stepObserver",(Consumer<Map<String,Object>>)step->{Map<String,Object> saved=new LinkedHashMap<>(step);saved.put("runId",f.run());store.save("check_atomic_step",stableId(parent+":"+text(step,"id")+":"+text(step,"status")),f.project(),parent,saved);});};
        Method method=BidService.class.getDeclaredMethod("continueResolution",TaskRow.class,String.class,Document.class,Document.class,List.class,Map.class,OnDemandModelBudget.class,Function.class);
        method.setAccessible(true);method.invoke(service,f.task(),f.run(),f.bid(),f.tender(),List.of(),Map.of(),new OnDemandModelBudget(store,new TransactionTemplate(manager),f.task(),f.run()),contexts);
    }
    @Test void queuedAgentItemUsesStoredGroupFactsLocallyAndPublishesTheVerifiedConclusion()throws Exception{
        Fixture f=fixture();Evidence source=new Evidence(f.bid().id(),f.bid().name(),1,"PAGE","营业执照：甲公司","p1",1);
        when(focused.assess(any(),any(),any(),anyMap())).thenAnswer(call->{Map<String,Object> context=call.getArgument(3);assertEquals(false,context.get("recoveryModelAllowed"));assertEquals("EVIDENCE_RECOMPUTE",context.get("recoveryMode"));
            assertFalse(((BooleanSupplier)context.get("modelAttemptPermit")).getAsBoolean());
            return new FocusedCheckEvaluator.Result(new Evaluation("PASS","已按规则完成核验","",List.of(source),1,"RULE"),Map.of("atomicCheck",fields("complete",true,"gaps",List.of(),"conditions",List.of(fields("id","R1","conditionRole","REQUIREMENT","status","PASS","predicate","MATERIAL","field","营业执照","evidences",List.of(source))))));});
        execute(f);assertEquals("PASS",store.get("finding",f.before().id(),Finding.class).evaluation().status());
        assertEquals("SUCCEEDED",store.getMap("check_resolution",CheckResolution.jobId(f.run(),"follower")).get("status"));
        assertEquals(1,store.maps("check_atomic_step",f.project(),stableId(f.run()+":atomic-steps:follower")).stream().filter(s->AgentGroupRecovery.CONSUMED.equals(s.get("type"))).count());
        verify(focused,times(1)).assess(any(),any(),any(),anyMap());assertEquals(0,((Number)store.getMap("run",f.run()).get("onDemandAttemptsReserved")).intValue());
    }
    @Test void requeuedFailedItemKeepsOriginalDiagnosticAndDoesNotRepeatTheReceipt()throws Exception{
        Fixture f=fixture();when(focused.assess(any(),any(),any(),anyMap())).thenReturn(new FocusedCheckEvaluator.Result(f.before().evaluation(),Map.of("atomicCheck",fields("complete",false,"gaps",List.of(Map.of("code","EVIDENCE_MISSING"))))));
        execute(f);assertEquals(f.before(),store.get("finding",f.before().id(),Finding.class));
        Map<String,Object> job=store.getMap("check_resolution",CheckResolution.jobId(f.run(),"follower"));job.put("status","QUEUED");store.save("check_resolution",text(job,"id"),f.project(),f.run(),job);
        saveSnapshot(f,"resaved","bid-hash");execute(f);verify(focused,times(1)).assess(any(),any(),any(),anyMap());
        assertEquals(f.before(),store.get("finding",f.before().id(),Finding.class));
    }
}

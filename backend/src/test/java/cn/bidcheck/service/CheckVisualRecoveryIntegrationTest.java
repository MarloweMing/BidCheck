package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.VerificationService;
import cn.bidcheck.verification.source.VisualVerificationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:check-visual-recovery;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false","bidcheck.visual.enabled=true","bidcheck.verification.max-visual-calls=0","bidcheck.queue.enabled=false"})
class CheckVisualRecoveryIntegrationTest {
    static final Path DATA=temp();static Path temp(){try{return Files.createTempDirectory("bidcheck-visual-recovery-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;@Autowired VerificationService verification;@Autowired FocusedCheckContext contexts;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean VisualVerificationService visuals;

    @Test void ordinaryCheckUsesSharedVisualExecutorWithoutLegacyCallCapAndBindsOnlyCurrentObservations()throws Exception{
        when(visuals.identity()).thenReturn("fixture-visual-v1");when(visuals.parallelism()).thenReturn(1);
        when(visuals.requestIdentity(any(),anyList(),anyMap())).thenReturn("fixture-request");
        String project=BidService.text(service.createProject(Map.of("name","原图恢复隔离测试")),"id");
        TaskRow upload=service.upload(project,"BID",new MockMultipartFile("file","synthetic.txt","text/plain","本项目授权委托书\n要求位置（盖章）".getBytes(StandardCharsets.UTF_8)));
        service.execute(upload);Document parsed=service.document(BidService.text(store.map(upload.inputJson),"documentId"));
        // The image adapter is mocked; the fixture verifies orchestration and provenance, not image recognition.
        Document bid=new Document(parsed.id(),project,"BID","synthetic.pdf",1,parsed.sha256(),parsed.createdAt(),List.of(new Page(1,"本项目授权委托书\n要求位置（盖章）","PAGE","PDF_TEXT","")),List.of());
        store.save("document",bid.id(),project,"",bid);
        String runId=Store.id();TaskRow task=new TaskRow();task.id=Store.id();task.projectId=project;task.taskType="CHECK";task.status="RUNNING";task.progress=96;
        task.stage="原图补查";task.createdAt=BidService.now();task.updatedAt=task.createdAt;task.inputJson=store.encode(Map.of("runId",runId,"documentId",bid.id()));store.mapper().insertTask(task);
        store.save("run",runId,project,bid.id(),BidService.fields("id",runId,"projectId",project,"taskId",task.id,"documentId",bid.id(),"status","RUNNING","checkMode","RULES_RETRIEVAL","onDemandAttemptsReserved",0,"consistencyReviewAttemptsReserved",0));
        Map<String,Object> source=BidService.fields("documentId",bid.id(),"projectId",project,"documentHash",bid.sha256(),"documentVersion",1,"page",1,"renderedSha256","a".repeat(64),"renderedWidth",100,"renderedHeight",100);
        Map<String,Object> observation=BidService.fields("type","SEAL","label","要求位置","value","可见章","readability","CLEAR","page",1,"source",source,"sourceBbox",List.of(1,1,30,30));
        Map<String,Object> response=BidService.fields("status","OBSERVED","pageResults",List.of(BidService.fields("status","COMPLETED","page",1,"source",source,"observations",List.of(observation))),"limitations",List.of());
        when(visuals.verifyWindow(any(),any(),anyList(),anyMap(),any(),anyString())).thenAnswer(call->{assertTrue(((BooleanSupplier)call.getArgument(4)).getAsBoolean());return response;});
        Checkpoint cp=new Checkpoint("seal-cp","授权委托书盖章","形式检查","HIGH","SIGNATURE_SEAL","seal","EXISTS","要求位置盖章","",true,true,true,null);
        Map<String,Object> result=Map.of("visualRequests",List.of(Map.of("documentId",bid.id(),"page",1,"obligationId","seal-1","task","SEAL","sourceIds",List.of("source-1"))));
        OnDemandModelBudget calls=new OnDemandModelBudget(store,new TransactionTemplate(manager),task,runId);
        List<Map<String,Object>> observations=verification.collectCheckVisual(task,runId,cp,result,List.of(bid),new ArrayList<>(),calls::reserve);
        assertEquals(1,observations.size());assertEquals(1,((Number)store.getMap("run",runId).get("visualCallCount")).intValue());
        assertEquals(1,((Number)store.getMap("run",runId).get("onDemandAttemptsReserved")).intValue());
        store.save("check_visual_observation",Store.id(),project,runId,observations.get(0));
        var factory=contexts.prepare(task,runId,bid,null,"fixture-engine",List.of(),calls::reserve);
        assertEquals(1,CheckResolution.maps(factory.apply(cp).get("visualObservations")).size());
        when(visuals.identity()).thenReturn("fixture-visual-v2");
        assertTrue(CheckResolution.maps(factory.apply(cp).get("visualObservations")).isEmpty());
        verify(visuals,times(1)).verifyWindow(any(),any(),anyList(),anyMap(),any(),anyString());
    }
}

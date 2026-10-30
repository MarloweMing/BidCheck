package cn.bidcheck.service;

import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.VerificationService;
import cn.bidcheck.verification.external.ExternalVerificationAdapter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real collector, lease, durable operation key and stored record, with a synthetic provider. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:external-scope-pipeline;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false","bidcheck.visual.enabled=false","bidcheck.queue.enabled=false"})
class ExternalScopePipelineTest {
    static final Path DATA=temp();static Path temp(){try{return Files.createTempDirectory("external-scope-pipeline-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired Store store;@Autowired VerificationService verification;@Autowired PlatformTransactionManager transactions;
    @MockitoBean ExternalVerificationAdapter external;
    @Test void collectorKeepsScopeThroughPersistenceAndNeverReusesDifferentBusinessScope(){
        String project=Store.id(),run=Store.id();TaskRow task=task(project,run);
        when(external.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));when(external.identity()).thenReturn("scope-fixture-adapter-v3");
        when(external.query(anyMap())).thenAnswer(invocation->{Map<String,Object> query=invocation.getArgument(0);assertEquals("www.creditchina.gov.cn",query.get("requiredProvider"));assertEquals(List.of("judgmentDefaulter"),query.get("requiredConditionFields"));
            return Map.of("status","COMPLETED","record",BidService.fields("id",Store.id(),"type",query.get("type"),"subject",query.get("subject"),
                "source","https://www.creditchina.gov.cn","queriedAt",BidService.now(),"effectiveAt",query.get("referenceDate"),"evaluationStage",query.get("evaluationStage"),
                "coverageComplete",true,"rawSnapshotHash","fixture-"+query.get("requirementScopeFingerprint"),"provenanceStatus","TRUSTED_CONNECTOR",
                "requirementScopeFingerprint",query.get("requirementScopeFingerprint"),"coveredConditionFields",query.get("requiredConditionFields"),
                "result",Map.of("status","VERIFIED","facts",List.of(Map.of("field","judgmentDefaulter","value",false,"rawValue","false","sourceQuote","judgmentDefaulter=false")))));});
        Map<String,Object> request=BidService.fields("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_EVALUATION","referenceDate","2025-08-05",
            "requiredProvider","www.creditchina.gov.cn","requirementScopeFingerprint","a".repeat(64),"requiredConditionFields",List.of("judgmentDefaulter"));
        var first=verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),new ArrayList<>(),()->true);
        assertEquals(1,first.size());assertEquals("a".repeat(64),first.get(0).get("requirementScopeFingerprint"));
        var repeated=verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),new ArrayList<>(),()->true);
        assertEquals(first,repeated);verify(external,times(1)).query(anyMap());
        request.put("requirementScopeFingerprint","b".repeat(64));
        var other=verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),new ArrayList<>(),()->true);
        assertEquals("b".repeat(64),other.get(0).get("requirementScopeFingerprint"));verify(external,times(2)).query(anyMap());
    }
    @Test void storedConnectorFailureCanRetryAndDoesNotBecomeSuccessfulCachedEvidence(){
        String project=Store.id(),run=Store.id();TaskRow task=task(project,run);
        when(external.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));when(external.identity()).thenReturn("scope-fixture-retry-v3");
        Map<String,Object> request=BidService.fields("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_EVALUATION","referenceDate","2025-08-05",
            "requiredProvider","www.creditchina.gov.cn","requirementScopeFingerprint","c".repeat(64),"requiredConditionFields",List.of("judgmentDefaulter"));
        Map<String,Object> record=BidService.fields("type","CREDIT_CHINA","subject","合成企业","provenanceStatus","TRUSTED_CONNECTOR","requirementScopeFingerprint","c".repeat(64));
        when(external.query(anyMap())).thenReturn(BidService.fields("status","ERROR","errorCode","EXTERNAL_TIMEOUT","record",null),Map.of("status","COMPLETED","record",record));
        List<Map<String,Object>> diagnostics=new ArrayList<>();
        assertTrue(verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),diagnostics,()->true).isEmpty());
        assertTrue(diagnostics.stream().anyMatch(d->"EXTERNAL_ENGINE_FAILURE".equals(d.get("code"))));
        assertEquals(List.of(record),verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),new ArrayList<>(),()->true));
        assertEquals(List.of(record),verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),new ArrayList<>(),()->true));
        verify(external,times(2)).query(anyMap());
    }
    @Test void disabledTypeProducesConcreteDiagnosticWithoutCallingProviderOrClaimingNoHit(){
        TaskRow task=task(Store.id(),Store.id());String run=store.map(task.inputJson).get("runId").toString();
        when(external.enabledTypes()).thenReturn(List.of());when(external.identity()).thenReturn("DISABLED");
        List<Map<String,Object>> diagnostics=new ArrayList<>();var request=Map.<String,Object>of("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_SUBMISSION","referenceDate","2025-08-05");
        assertTrue(verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),diagnostics,()->true).isEmpty());
        assertEquals(1,diagnostics.size());assertEquals("EXTERNAL_CONNECTOR_NOT_CONFIGURED",diagnostics.get(0).get("code"));assertEquals(false,diagnostics.get(0).get("queryExecuted"));
        verify(external,never()).query(anyMap());assertFalse(diagnostics.toString().contains("EXTERNAL_RECORD_NOT_FOUND"));
    }
    @Test void futureStageAndMissingQueryScopeAreNotSilentlySkipped(){
        TaskRow task=task(Store.id(),Store.id());String run=store.map(task.inputJson).get("runId").toString();when(external.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));
        var request=Map.<String,Object>of("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_EVALUATION","referenceDate","2025-08-05");
        List<Map<String,Object>> diagnostics=new ArrayList<>();
        verification.collectCheckExternal(task,run,Map.of("workflowState","WAIT_STAGE","externalRequests",List.of(request)),Map.of("evaluationStage","BID_SUBMISSION"),diagnostics,()->true);
        assertEquals("EXTERNAL_WAIT_STAGE",diagnostics.get(0).get("code"));assertEquals("BID_EVALUATION",diagnostics.get(0).get("evaluationStage"));
        diagnostics.clear();verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(Map.of("type","CREDIT_CHINA"))),Map.of(),diagnostics,()->true);
        assertEquals("EXTERNAL_QUERY_SCOPE_INCOMPLETE",diagnostics.get(0).get("code"));verify(external,never()).query(anyMap());
    }
    @Test void noHitReceiptIsRecordedAndDistinctFromTransportFailure(){
        TaskRow task=task(Store.id(),Store.id());String run=store.map(task.inputJson).get("runId").toString();when(external.enabledTypes()).thenReturn(List.of("CREDIT_CHINA"));when(external.identity()).thenReturn("negative-receipt");
        var request=Map.<String,Object>of("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_SUBMISSION","referenceDate","2025-08-05");
        var record=Map.<String,Object>of("type","CREDIT_CHINA","provenanceStatus","TRUSTED_CONNECTOR","coverageComplete",true,"result",Map.of("status","NOT_FOUND","facts",List.of()));
        when(external.query(anyMap())).thenReturn(Map.of("status","COMPLETED","record",record,"queryExecuted",true));
        List<Map<String,Object>> diagnostics=new ArrayList<>();assertEquals(List.of(record),verification.collectCheckExternal(task,run,Map.of("externalRequests",List.of(request)),Map.of(),diagnostics,()->true));
        assertEquals("EXTERNAL_RECORD_NOT_FOUND",diagnostics.get(0).get("code"));assertEquals(true,diagnostics.get(0).get("queryExecuted"));
    }
    private TaskRow task(String project,String run){
        store.save("project",project,project,"",Map.of("id",project,"name","外部范围接线回归"));
        TaskRow pending=new TaskRow();pending.id=Store.id();pending.projectId=project;pending.taskType="CHECK";pending.status="PENDING";pending.progress=96;pending.stage="隔离契约测试";pending.createdAt=BidService.now();pending.updatedAt=pending.createdAt;pending.inputJson=store.encode(Map.of("runId",run,"documentId","bid"));
        new TransactionTemplate(transactions).executeWithoutResult(s->{store.mapper().insertTask(pending);assertEquals(1,store.mapper().claimTask(pending.id,BidService.now(),"scope-fixture",System.currentTimeMillis()+600000));});
        TaskRow task=store.mapper().task(pending.id);store.save("run",run,project,"bid",BidService.fields("id",run,"projectId",project,"taskId",task.id,"documentId","bid","status","RUNNING"));
        return task;
    }
}

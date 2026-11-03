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
import org.springframework.boot.test.mock.mockito.MockBean;
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
    @MockBean ExternalVerificationAdapter external;
    @Test void collectorKeepsScopeThroughPersistenceAndNeverReusesDifferentBusinessScope(){
        String project=Store.id(),run=Store.id();TaskRow task=task(project,run);
        when(external.enabledTypes()).thenReturn(cn.bidcheck.compat.Java8Api.listOf("CREDIT_CHINA"));when(external.identity()).thenReturn("scope-fixture-adapter-v3");
        when(external.query(anyMap())).thenAnswer(invocation->{Map<String,Object> query=invocation.getArgument(0);assertEquals("www.creditchina.gov.cn",query.get("requiredProvider"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("judgmentDefaulter"),query.get("requiredConditionFields"));
            return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "record", BidService.fields("id",Store.id(),"type",query.get("type"),"subject",query.get("subject"),
                "source","https://www.creditchina.gov.cn","queriedAt",BidService.now(),"effectiveAt",query.get("referenceDate"),"evaluationStage",query.get("evaluationStage"),
                "coverageComplete",true,"rawSnapshotHash","fixture-"+query.get("requirementScopeFingerprint"),"provenanceStatus","TRUSTED_CONNECTOR",
                "requirementScopeFingerprint",query.get("requirementScopeFingerprint"),"coveredConditionFields",query.get("requiredConditionFields"),
                "result",cn.bidcheck.compat.Java8Api.mapOf("status", "VERIFIED", "facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "judgmentDefaulter", "value", false, "rawValue", "false", "sourceQuote", "judgmentDefaulter=false")))));});
        Map<String,Object> request=BidService.fields("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_EVALUATION","referenceDate","2025-08-05",
            "requiredProvider","www.creditchina.gov.cn","requirementScopeFingerprint",cn.bidcheck.compat.Java8Api.repeat("a", 64),"requiredConditionFields",cn.bidcheck.compat.Java8Api.listOf("judgmentDefaulter"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> first=verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),new ArrayList<>(),()->true);
        assertEquals(1,first.size());assertEquals(cn.bidcheck.compat.Java8Api.repeat("a", 64),first.get(0).get("requirementScopeFingerprint"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> repeated=verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),new ArrayList<>(),()->true);
        assertEquals(first,repeated);verify(external,times(1)).query(anyMap());
        request.put("requirementScopeFingerprint",cn.bidcheck.compat.Java8Api.repeat("b", 64));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> other=verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),new ArrayList<>(),()->true);
        assertEquals(cn.bidcheck.compat.Java8Api.repeat("b", 64),other.get(0).get("requirementScopeFingerprint"));verify(external,times(2)).query(anyMap());
    }
    @Test void storedConnectorFailureCanRetryAndDoesNotBecomeSuccessfulCachedEvidence(){
        String project=Store.id(),run=Store.id();TaskRow task=task(project,run);
        when(external.enabledTypes()).thenReturn(cn.bidcheck.compat.Java8Api.listOf("CREDIT_CHINA"));when(external.identity()).thenReturn("scope-fixture-retry-v3");
        Map<String,Object> request=BidService.fields("type","CREDIT_CHINA","subject","合成企业","evaluationStage","BID_EVALUATION","referenceDate","2025-08-05",
            "requiredProvider","www.creditchina.gov.cn","requirementScopeFingerprint",cn.bidcheck.compat.Java8Api.repeat("c", 64),"requiredConditionFields",cn.bidcheck.compat.Java8Api.listOf("judgmentDefaulter"));
        Map<String,Object> record=BidService.fields("type","CREDIT_CHINA","subject","合成企业","provenanceStatus","TRUSTED_CONNECTOR","requirementScopeFingerprint",cn.bidcheck.compat.Java8Api.repeat("c", 64));
        when(external.query(anyMap())).thenReturn(BidService.fields("status","ERROR","errorCode","EXTERNAL_TIMEOUT","record",null),cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "record", record));
        List<Map<String,Object>> diagnostics=new ArrayList<>();
        assertTrue(verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),diagnostics,()->true).isEmpty());
        assertTrue(diagnostics.stream().anyMatch(d->"EXTERNAL_ENGINE_FAILURE".equals(d.get("code"))));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(record),verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),new ArrayList<>(),()->true));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(record),verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),new ArrayList<>(),()->true));
        verify(external,times(2)).query(anyMap());
    }
    @Test void disabledTypeProducesConcreteDiagnosticWithoutCallingProviderOrClaimingNoHit(){
        TaskRow task=task(Store.id(),Store.id());String run=store.map(task.inputJson).get("runId").toString();
        when(external.enabledTypes()).thenReturn(cn.bidcheck.compat.Java8Api.listOf());when(external.identity()).thenReturn("DISABLED");
        List<Map<String,Object>> diagnostics=new ArrayList<>();java.util.Map<java.lang.String, java.lang.Object> request=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("type", "CREDIT_CHINA", "subject", "合成企业", "evaluationStage", "BID_SUBMISSION", "referenceDate", "2025-08-05");
        assertTrue(verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),diagnostics,()->true).isEmpty());
        assertEquals(1,diagnostics.size());assertEquals("EXTERNAL_CONNECTOR_NOT_CONFIGURED",diagnostics.get(0).get("code"));assertEquals(false,diagnostics.get(0).get("queryExecuted"));
        verify(external,never()).query(anyMap());assertFalse(diagnostics.toString().contains("EXTERNAL_RECORD_NOT_FOUND"));
    }
    @Test void futureStageAndMissingQueryScopeAreNotSilentlySkipped(){
        TaskRow task=task(Store.id(),Store.id());String run=store.map(task.inputJson).get("runId").toString();when(external.enabledTypes()).thenReturn(cn.bidcheck.compat.Java8Api.listOf("CREDIT_CHINA"));
        java.util.Map<java.lang.String, java.lang.Object> request=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("type", "CREDIT_CHINA", "subject", "合成企业", "evaluationStage", "BID_EVALUATION", "referenceDate", "2025-08-05");
        List<Map<String,Object>> diagnostics=new ArrayList<>();
        verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("workflowState", "WAIT_STAGE", "externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf("evaluationStage", "BID_SUBMISSION"),diagnostics,()->true);
        assertEquals("EXTERNAL_WAIT_STAGE",diagnostics.get(0).get("code"));assertEquals("BID_EVALUATION",diagnostics.get(0).get("evaluationStage"));
        diagnostics.clear();verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_CHINA"))),cn.bidcheck.compat.Java8Api.mapOf(),diagnostics,()->true);
        assertEquals("EXTERNAL_QUERY_SCOPE_INCOMPLETE",diagnostics.get(0).get("code"));verify(external,never()).query(anyMap());
    }
    @Test void noHitReceiptIsRecordedAndDistinctFromTransportFailure(){
        TaskRow task=task(Store.id(),Store.id());String run=store.map(task.inputJson).get("runId").toString();when(external.enabledTypes()).thenReturn(cn.bidcheck.compat.Java8Api.listOf("CREDIT_CHINA"));when(external.identity()).thenReturn("negative-receipt");
        java.util.Map<java.lang.String, java.lang.Object> request=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("type", "CREDIT_CHINA", "subject", "合成企业", "evaluationStage", "BID_SUBMISSION", "referenceDate", "2025-08-05");
        java.util.Map<java.lang.String, java.lang.Object> record=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("type", "CREDIT_CHINA", "provenanceStatus", "TRUSTED_CONNECTOR", "coverageComplete", true, "result", cn.bidcheck.compat.Java8Api.mapOf("status", "NOT_FOUND", "facts", cn.bidcheck.compat.Java8Api.listOf()));
        when(external.query(anyMap())).thenReturn(cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "record", record, "queryExecuted", true));
        List<Map<String,Object>> diagnostics=new ArrayList<>();assertEquals(cn.bidcheck.compat.Java8Api.listOf(record),verification.collectCheckExternal(task,run,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request)),cn.bidcheck.compat.Java8Api.mapOf(),diagnostics,()->true));
        assertEquals("EXTERNAL_RECORD_NOT_FOUND",diagnostics.get(0).get("code"));assertEquals(true,diagnostics.get(0).get("queryExecuted"));
    }
    private TaskRow task(String project,String run){
        store.save("project",project,project,"",cn.bidcheck.compat.Java8Api.mapOf("id", project, "name", "外部范围接线回归"));
        TaskRow pending=new TaskRow();pending.id=Store.id();pending.projectId=project;pending.taskType="CHECK";pending.status="PENDING";pending.progress=96;pending.stage="隔离契约测试";pending.createdAt=BidService.now();pending.updatedAt=pending.createdAt;pending.inputJson=store.encode(cn.bidcheck.compat.Java8Api.mapOf("runId", run, "documentId", "bid"));
        new TransactionTemplate(transactions).executeWithoutResult(s->{store.mapper().insertTask(pending);assertEquals(1,store.mapper().claimTask(pending.id,BidService.now(),"scope-fixture",System.currentTimeMillis()+600000));});
        TaskRow task=store.mapper().task(pending.id);store.save("run",run,project,"bid",BidService.fields("id",run,"projectId",project,"taskId",task.id,"documentId","bid","status","RUNNING"));
        return task;
    }
}

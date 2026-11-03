package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCheckStateTest {
    private Finding finding(Map<String,Object> assessment){return new Finding("f","run",new Checkpoint("cp","指定来源核验","资格检查","HIGH","SEMANTIC_RESPONSE","credit","=","false","",true,true,true,null),new Evaluation("UNDETERMINED","原文要求评标时现场查询","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES"),assessment);}
    private Map<String,Object> request(){return cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_CHINA", "subject", "测试公司", "evaluationStage", "BID_EVALUATION", "referenceDate", "2025-08-05");}
    private Map<String,Object> assessment(){return cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request())));}
    @Test void failedOrUnconfiguredQueryUpdatesDiagnosticsWithoutFabricatingEvidenceOrRecomputation(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("EXTERNAL_CONNECTOR_NOT_CONFIGURED", "EXTERNAL_CONFIGURATION_INVALID", "EXTERNAL_ENGINE_FAILURE", "EXTERNAL_QUERY_SCOPE_INCOMPLETE")){
            Finding before=finding(assessment());Finding after=ExternalCheckRecovery.attachDiagnostics(before,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code, "queryExecuted", false)));
            assertSame(before.evaluation(),after.evaluation());assertEquals(CheckResolution.atomic(before).get("externalRequests"),CheckResolution.atomic(after).get("externalRequests"));
            assertEquals(CheckResolution.atomic(before).get("conditions"),CheckResolution.atomic(after).get("conditions"));
            assertEquals(false,CheckResolution.map(after.assessment().get("externalQueryUpdate")).get("conclusionRecomputed"));
            assertEquals(false,CheckResolution.map(after.assessment().get("externalQueryUpdate")).get("evidenceChanged"));
            assertFalse((boolean)CheckResolution.progress(before,after).get("progressMade"));
            Map<String,Object> display=BusinessResultPresentation.describe(after.evaluation(),after.assessment());
            assertEquals("SYSTEM_INCOMPLETE",display.get("state"));assertEquals("SYSTEM",display.get("owner"));assertEquals(false,display.get("userActionRequired"));
            assertTrue(CheckResolution.unresolved(after));
        }
    }
    @Test void futureStageHasKnownTriggerAndDoesNotBecomeManualTask(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request()), "workflowState", "WAIT_STAGE", "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "WAIT_STAGE")))));
        Map<String,Object> display=BusinessResultPresentation.describe(f.evaluation(),f.assessment()),plan=CheckResolution.plan(f);
        assertEquals("WAIT_EXTERNAL",display.get("state"));assertEquals("待评标阶段核验",display.get("label"));assertEquals(false,display.get("userActionRequired"));
        assertEquals("EXTERNAL",display.get("owner"));assertEquals("WAIT_STAGE",plan.get("strategy"));assertEquals("WAITING_EXTERNAL",plan.get("status"));
        assertFalse(plan.get("reason").toString().contains("尚缺"));assertTrue(plan.get("nextAction").toString().contains("招标人"));
        assertTrue(CheckResolution.unresolved(f));assertEquals("UNDETERMINED",f.evaluation().status());
    }
    @Test void actualProcessingFailureOutranksKnownFutureStage(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("workflowState", "WAIT_STAGE", "externalRequests", cn.bidcheck.compat.Java8Api.listOf(request()), "fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "errorCode", "MODEL_TIMEOUT")))));
        java.util.Map<java.lang.String, java.lang.Object> display=BusinessResultPresentation.describe(f.evaluation(),f.assessment());assertEquals("SYSTEM_INCOMPLETE",display.get("state"));assertEquals(false,display.get("userActionRequired"));
    }
    @Test void generatedExternalRequestDoesNotClaimExecutedOrAssignUserAction(){
        Finding f=finding(assessment());java.util.Map<java.lang.String, java.lang.Object> display=BusinessResultPresentation.describe(f.evaluation(),f.assessment());
        assertEquals("SYSTEM_INCOMPLETE",display.get("state"));assertEquals(false,display.get("userActionRequired"));
        assertEquals("NOT_EXECUTED",CheckResolution.map(display.get("externalState")).get("state"));assertEquals("QUERY_EXTERNAL",CheckResolution.plan(f).get("strategy"));
    }
    @Test void persistedJobDiagnosticAlsoCorrectsLegacyFindingResponsibility(){
        Finding f=finding(assessment());java.util.Map<java.lang.String, java.lang.Object> display=BusinessResultPresentation.describe(f.evaluation(),f.assessment(),cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", "BLOCKED_NO_PROGRESS", "diagnostics", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "EXTERNAL_CONNECTOR_NOT_CONFIGURED"))));
        assertEquals("外部来源未接入",display.get("label"));assertEquals("SYSTEM_INCOMPLETE",display.get("state"));assertEquals(false,display.get("userActionRequired"));
    }
    @Test void successfulNoHitAndOwnerDataAreDistinctFromFailureAndNeverImplicitPass(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("EXTERNAL_RECORD_NOT_FOUND", "EXTERNAL_RECORD_INCONCLUSIVE", "EXTERNAL_OWNER_DATA_REQUIRED")){
            Finding f=ExternalCheckRecovery.attachDiagnostics(finding(assessment()),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code, "queryExecuted", !code.equals("EXTERNAL_OWNER_DATA_REQUIRED"))));
            java.util.Map<java.lang.String, java.lang.Object> display=BusinessResultPresentation.describe(f.evaluation(),f.assessment());assertEquals("WAIT_EXTERNAL",display.get("state"));assertEquals(false,display.get("userActionRequired"));
            assertEquals("WAITING_EXTERNAL",CheckResolution.plan(f).get("status"));assertEquals("UNDETERMINED",f.evaluation().status());
        }
    }
    @Test void unrelatedDiagnosticsDoNotOverwriteExistingReceipt(){
        Finding f=finding(assessment());assertSame(f,ExternalCheckRecovery.attachDiagnostics(f,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "NO_NEW_VALID_OBSERVATION"))));
    }
    @Test void unchangedMissingConnectorDoesNotOfferRepeatedRecoveryButChangedConfigurationDoes(){
        Finding f=ExternalCheckRecovery.attachDiagnostics(finding(assessment()),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "EXTERNAL_CONNECTOR_NOT_CONFIGURED")));
        Map<String,Object> job=new LinkedHashMap<>(CheckResolution.plan(f));job.put("id","job");
        assertEquals(true,job.get("requiresConfigurationChange"));assertEquals("FAILED",job.get("status"));
        Map<String,Object> run=cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "resolutionPolicyIdentity", "old");
        assertEquals(false,CheckResolution.summary(run,cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(job),"old").get("canRecover"));
        assertEquals(true,CheckResolution.summary(run,cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(job),"new-connector").get("canRecover"));
    }
    @Test void frozenOcrScopeClarificationSurvivesCopiedBusinessReason(){
        String body="尚缺完整响应依据；模型提出 OCR 文字须结合上下文核对。";
        String raw="部分图像OCR未完成，现有文字不能证明全部内容已经核验。"+body;
        Evaluation frozen=new Evaluation("UNDETERMINED",raw,"历史建议",cn.bidcheck.compat.Java8Api.listOf(),0,"OCR_REVIEW");
        Map<String,Object> clarification=cn.bidcheck.compat.Java8Api.mapOf("reason", body, "legacyClarified", true, "originalReason", raw);
        Map<String,Object> business=BusinessResultPresentation.describe(frozen,cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals(raw,business.get("reason"));assertEquals(body,ReportService.analysisReason(frozen,clarification,business));
        assertEquals(raw,frozen.reason());assertEquals(raw,clarification.get("originalReason"));
    }
    @Test void latestExternalDiagnosticStillOverridesOldOcrOrEvidenceProse(){
        Finding f=ExternalCheckRecovery.attachDiagnostics(finding(assessment()),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "EXTERNAL_CONNECTOR_NOT_CONFIGURED")));
        java.util.Map<java.lang.String, java.lang.Object> business=BusinessResultPresentation.describe(f.evaluation(),f.assessment());
        String reason=ReportService.analysisReason(f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("reason", "旧提示：尚缺材料", "legacyClarified", true),business);
        assertEquals(business.get("reason"),reason);assertTrue(reason.contains("外部来源未接入"));assertFalse(reason.contains("旧提示"));
        assertEquals("UNDETERMINED",f.evaluation().status());
    }
    @Test void currentStageReasonIsNotReplacedByHistoricalUnknownTime(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request()), "workflowState", "WAIT_STAGE")));
        java.util.Map<java.lang.String, java.lang.Object> business=BusinessResultPresentation.describe(f.evaluation(),f.assessment());
        assertTrue(ReportService.analysisReason(f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("reason", "无法确定核验时点"),business).contains("待评标阶段核验"));
    }
}

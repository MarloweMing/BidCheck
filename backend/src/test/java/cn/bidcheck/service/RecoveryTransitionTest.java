package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;

class RecoveryTransitionTest {
    Finding finding(Map<String,Object> atomic){
        Checkpoint cp=new Checkpoint("cp","证照名称一致","形式检查","HIGH","SEMANTIC_RESPONSE","name","EQ","一致","",true,true,true,null);
        return new Finding("f","r",cp,new Evaluation("UNDETERMINED","证据仍待对应","",cn.bidcheck.compat.Java8Api.listOf(),0,"RULE"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
    }
    Finding failure(){return finding(fields("modelDiagnostics",fields("unresolvedFailures",1,"failures",cn.bidcheck.compat.Java8Api.listOf(fields("errorCode","OUTPUT_SCHEMA_INVALID","retryable",true))),"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","MODEL_OUTPUT_SCHEMA_INVALID"))));}
    Map<String,Object> transition(Finding before,Finding after,String next,String status){
        return RecoveryTransition.afterAttempt(before,after,"RETRY_FAILED_STEP",cn.bidcheck.compat.Java8Api.mapOf("strategy", next, "status", status),false,false,true,cn.bidcheck.compat.Java8Api.listOf());
    }
    @Test void schemaRecoveryWithoutBusinessProofCanScheduleDifferentEvidenceStep(){
        Finding after=finding(fields("modelDiagnostics",fields("unresolvedFailures",0,"failures",cn.bidcheck.compat.Java8Api.listOf(fields("errorCode","OUTPUT_SCHEMA_INVALID","recoveredByRetry",true))),"gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","VALIDITY_FIELD_REQUIRED"))));
        java.util.Map<java.lang.String, java.lang.Object> actual=transition(failure(),after,"VERIFY_SOURCE_PAGES","QUEUED");
        assertEquals("QUEUED",actual.get("status"));assertEquals(true,actual.get("technicalRecovered"));assertEquals(false,actual.get("businessComplete"));assertEquals("SUCCEEDED",actual.get("executionState"));
    }
    @Test void recoveredCallWithNoRemainingStrategyStopsWithoutReportingFailure(){
        java.util.Map<java.lang.String, java.lang.Object> actual=transition(failure(),finding(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","CERTIFICATE_CATEGORY_REQUIRED")))),"BLOCKED","BLOCKED_NO_PROGRESS");
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(false,actual.get("processingFailed"));assertEquals(true,actual.get("technicalRecovered"));
    }
    @Test void persistentFailureAndProtectedOldProofRemainFailed(){
        assertEquals("FAILED",transition(failure(),failure(),"RETRY_FAILED_STEP","QUEUED").get("status"));
        Finding original=finding(cn.bidcheck.compat.Java8Api.mapOf());Finding kept=new Finding(original.id(),original.runId(),original.checkpoint(),original.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("recoveryFailure", cn.bidcheck.compat.Java8Api.mapOf("retainedCompletedProof", true, "modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("errorCode", "MODEL_TIMEOUT"))));
        assertEquals("FAILED",transition(failure(),kept,"BLOCKED","BLOCKED_NO_PROGRESS").get("status"));
    }
    @Test void fieldBindingRejectionAndNoNewVisualObservationAreNotRequestFailures(){
        Finding before=finding(fields("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(fields("status","FAILED","errorCode","FIELD_VALIDATION_FAILED")),
            "gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","VISUAL_FIELD_RELATION_REQUIRES_ORIGINAL_SOURCE"))));
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,before,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf("strategy", "VERIFY_SOURCE_PAGES", "status", "QUEUED"),false,false,false,
            cn.bidcheck.compat.Java8Api.listOf(fields("code","NO_NEW_VALID_OBSERVATION","newObservationCount",0)));
        assertEquals(false,actual.get("processingFailed"));assertEquals("NO_NEW_INPUT",actual.get("executionState"));
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(false,actual.get("businessComplete"));
        assertTrue(RecoveryTransition.hasProcessingFailure(finding(fields("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(fields("status","FAILED","errorCode","MODEL_TIMEOUT"))))));
    }
    @Test void retainedProofAfterUnresolvedCompilationIsNotATransportFailure(){
        Finding base=finding(cn.bidcheck.compat.Java8Api.mapOf());
        Finding retained=new Finding(base.id(),base.runId(),base.checkpoint(),base.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("recoveryFailure", fields("retainedCompletedProof",true,
            "compilationAudit",fields("state","UNRESOLVED"),"latestPlan",fields("status","BLOCKED_NO_PROGRESS"))));
        assertFalse(RecoveryTransition.hasProcessingFailure(retained));
        assertEquals("BLOCKED_NO_PROGRESS",transition(base,retained,"BLOCKED","BLOCKED_NO_PROGRESS").get("status"));
    }
    @Test void aDifferentExecutableStrategyDoesNotRequireInventedBusinessProgress(){
        java.util.Map<java.lang.String, java.lang.Object> actual=transition(finding(cn.bidcheck.compat.Java8Api.mapOf()),finding(cn.bidcheck.compat.Java8Api.mapOf()),"VERIFY_SOURCE_PAGES","QUEUED");
        assertEquals("QUEUED",actual.get("status"));assertEquals(false,actual.get("technicalRecovered"));assertEquals(true,actual.get("newStrategyWork"));
    }
    @Test void visualReadWithoutBusinessClosureCanProceedToTargetedEvidence(){
        Finding before=finding(fields("gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","SUBJECT_RELATION_UNPROVEN"))));
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,before,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf("strategy", "TARGETED_EVIDENCE", "status", "QUEUED"),false,false,true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("QUEUED",actual.get("status"));assertEquals(false,actual.get("businessComplete"));assertEquals(false,actual.get("technicalRecovered"));
    }
    @Test void actualVisualServiceFailureIsSeparateFromNoObservation(){
        Finding after=finding(cn.bidcheck.compat.Java8Api.mapOf());java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(after,after,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf("strategy", "VERIFY_SOURCE_PAGES", "status", "QUEUED"),false,false,false,cn.bidcheck.compat.Java8Api.listOf(fields("code","VISUAL_RETRY_PENDING","description","暂时失败")));
        assertEquals("FAILED",actual.get("status"));assertEquals(true,actual.get("temporaryVisualFailure"));
    }
    @Test void externalFailureReceiptIsPreservedWithoutEvidenceOrBusinessRecomputation(){
        Finding before=finding(cn.bidcheck.compat.Java8Api.mapOf());List<Map<String,Object>> diagnostics=cn.bidcheck.compat.Java8Api.listOf(fields("code","EXTERNAL_ENGINE_FAILURE","description","指定来源请求超时","providerErrorCode","EXTERNAL_TIMEOUT"));
        Finding after=ExternalCheckRecovery.attachDiagnostics(before,diagnostics);
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,after,"QUERY_EXTERNAL",cn.bidcheck.compat.Java8Api.mapOf("strategy", "QUERY_EXTERNAL", "status", "QUEUED"),false,false,false,diagnostics);
        assertEquals("FAILED",actual.get("status"));assertEquals(true,actual.get("processingFailed"));assertEquals(true,actual.get("externalProcessingFailure"));
        assertEquals(false,actual.get("businessComplete"));assertEquals(false,actual.get("technicalRecovered"));assertEquals("FAILED",actual.get("executionState"));
        assertEquals("指定来源请求超时",actual.get("stopReason"));assertTrue(RecoveryTransition.hasProcessingFailure(after));assertSame(before.evaluation(),after.evaluation());
    }
    @Test void noHitReceiptIsNotARequestFailure(){
        Finding before=finding(cn.bidcheck.compat.Java8Api.mapOf());List<Map<String,Object>> diagnostics=cn.bidcheck.compat.Java8Api.listOf(fields("code","EXTERNAL_RECORD_NOT_FOUND","description","指定范围未命中"));
        Finding after=ExternalCheckRecovery.attachDiagnostics(before,diagnostics);
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,after,"QUERY_EXTERNAL",cn.bidcheck.compat.Java8Api.mapOf("strategy", "WAIT_EXTERNAL_RECORD", "status", "WAITING_EXTERNAL"),false,false,false,diagnostics);
        assertEquals("WAITING_EXTERNAL",actual.get("status"));assertEquals(false,actual.get("processingFailed"));assertEquals(false,actual.get("businessComplete"));
        assertFalse(RecoveryTransition.hasProcessingFailure(after));
    }
    @Test void waitingForExternalInputIsNotConvertedToFailedRetry(){
        java.util.Map<java.lang.String, java.lang.Object> actual=transition(failure(),finding(cn.bidcheck.compat.Java8Api.mapOf()),"BLOCKED","WAITING_EXTERNAL");
        assertEquals("WAITING_EXTERNAL",actual.get("status"));assertEquals(true,actual.get("technicalRecovered"));
    }
    @Test void sameStrategyCanContinueAfterBusinessProgressExposesAnotherPage(){
        Finding before=finding(fields("visualRequests",cn.bidcheck.compat.Java8Api.listOf(fields("documentId","bid","page",1,"task","SEAL"))));
        Finding after=finding(fields("visualRequests",cn.bidcheck.compat.Java8Api.listOf(fields("documentId","bid","page",2,"task","SEAL"))));
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,after,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf("strategy", "VERIFY_SOURCE_PAGES", "status", "QUEUED"),true,false,true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("QUEUED",actual.get("status"));assertEquals(true,actual.get("inputsChanged"));
        assertEquals(false,actual.get("businessComplete"));assertEquals(false,actual.get("technicalRecovered"));
    }
    @Test void sameStrategyStillStopsWhenChangedPageHasNoBusinessProgress(){
        Finding before=finding(fields("visualRequests",cn.bidcheck.compat.Java8Api.listOf(fields("page",1))));Finding after=finding(fields("visualRequests",cn.bidcheck.compat.Java8Api.listOf(fields("page",2))));
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,after,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf("strategy", "VERIFY_SOURCE_PAGES", "status", "QUEUED"),false,false,true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(true,actual.get("inputsChanged"));
    }
    @Test void sameStrategyDoesNotRepeatUnchangedInputEvenWithProgressFlag(){
        Finding before=finding(fields("visualRequests",cn.bidcheck.compat.Java8Api.listOf(fields("page",1))));
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,before,"VERIFY_SOURCE_PAGES",cn.bidcheck.compat.Java8Api.mapOf("strategy", "VERIFY_SOURCE_PAGES", "status", "QUEUED"),true,false,true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(false,actual.get("inputsChanged"));
    }
    @Test void newlyCompletedObligationCanExposeDifferentPendingTarget(){
        Finding before=finding(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","A","status","UNDETERMINED"), fields("id","B","status","UNDETERMINED"))));
        Finding after=finding(fields("conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","A","status","PASS"), fields("id","B","status","UNDETERMINED"))));
        java.util.Map<java.lang.String, java.lang.Object> actual=RecoveryTransition.afterAttempt(before,after,"TARGETED_EVIDENCE",cn.bidcheck.compat.Java8Api.mapOf("strategy", "TARGETED_EVIDENCE", "status", "QUEUED"),true,false,true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("QUEUED",actual.get("status"));assertEquals(true,actual.get("inputsChanged"));assertEquals(false,actual.get("businessComplete"));
    }
}

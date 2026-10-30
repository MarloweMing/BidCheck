package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;

class RecoveryTransitionTest {
    Finding finding(Map<String,Object> atomic){
        Checkpoint cp=new Checkpoint("cp","证照名称一致","形式检查","HIGH","SEMANTIC_RESPONSE","name","EQ","一致","",true,true,true,null);
        return new Finding("f","r",cp,new Evaluation("UNDETERMINED","证据仍待对应","",List.of(),0,"RULE"),Map.of("atomicCheck",atomic));
    }
    Finding failure(){return finding(fields("modelDiagnostics",fields("unresolvedFailures",1,"failures",List.of(fields("errorCode","OUTPUT_SCHEMA_INVALID","retryable",true))),"gaps",List.of(fields("code","MODEL_OUTPUT_SCHEMA_INVALID"))));}
    Map<String,Object> transition(Finding before,Finding after,String next,String status){
        return RecoveryTransition.afterAttempt(before,after,"RETRY_FAILED_STEP",Map.of("strategy",next,"status",status),false,false,true,List.of());
    }
    @Test void schemaRecoveryWithoutBusinessProofCanScheduleDifferentEvidenceStep(){
        Finding after=finding(fields("modelDiagnostics",fields("unresolvedFailures",0,"failures",List.of(fields("errorCode","OUTPUT_SCHEMA_INVALID","recoveredByRetry",true))),"gaps",List.of(fields("code","VALIDITY_FIELD_REQUIRED"))));
        var actual=transition(failure(),after,"VERIFY_SOURCE_PAGES","QUEUED");
        assertEquals("QUEUED",actual.get("status"));assertEquals(true,actual.get("technicalRecovered"));assertEquals(false,actual.get("businessComplete"));assertEquals("SUCCEEDED",actual.get("executionState"));
    }
    @Test void recoveredCallWithNoRemainingStrategyStopsWithoutReportingFailure(){
        var actual=transition(failure(),finding(fields("gaps",List.of(fields("code","CERTIFICATE_CATEGORY_REQUIRED")))),"BLOCKED","BLOCKED_NO_PROGRESS");
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(false,actual.get("processingFailed"));assertEquals(true,actual.get("technicalRecovered"));
    }
    @Test void persistentFailureAndProtectedOldProofRemainFailed(){
        assertEquals("FAILED",transition(failure(),failure(),"RETRY_FAILED_STEP","QUEUED").get("status"));
        Finding original=finding(Map.of());Finding kept=new Finding(original.id(),original.runId(),original.checkpoint(),original.evaluation(),Map.of("recoveryFailure",Map.of("retainedCompletedProof",true,"modelDiagnostics",Map.of("errorCode","MODEL_TIMEOUT"))));
        assertEquals("FAILED",transition(failure(),kept,"BLOCKED","BLOCKED_NO_PROGRESS").get("status"));
    }
    @Test void fieldBindingRejectionAndNoNewVisualObservationAreNotRequestFailures(){
        Finding before=finding(fields("fieldCoverage",List.of(fields("status","FAILED","errorCode","FIELD_VALIDATION_FAILED")),
            "gaps",List.of(fields("code","VISUAL_FIELD_RELATION_REQUIRES_ORIGINAL_SOURCE"))));
        var actual=RecoveryTransition.afterAttempt(before,before,"VERIFY_SOURCE_PAGES",Map.of("strategy","VERIFY_SOURCE_PAGES","status","QUEUED"),false,false,false,
            List.of(fields("code","NO_NEW_VALID_OBSERVATION","newObservationCount",0)));
        assertEquals(false,actual.get("processingFailed"));assertEquals("NO_NEW_INPUT",actual.get("executionState"));
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(false,actual.get("businessComplete"));
        assertTrue(RecoveryTransition.hasProcessingFailure(finding(fields("fieldCoverage",List.of(fields("status","FAILED","errorCode","MODEL_TIMEOUT"))))));
    }
    @Test void retainedProofAfterUnresolvedCompilationIsNotATransportFailure(){
        Finding base=finding(Map.of());
        Finding retained=new Finding(base.id(),base.runId(),base.checkpoint(),base.evaluation(),Map.of("recoveryFailure",fields("retainedCompletedProof",true,
            "compilationAudit",fields("state","UNRESOLVED"),"latestPlan",fields("status","BLOCKED_NO_PROGRESS"))));
        assertFalse(RecoveryTransition.hasProcessingFailure(retained));
        assertEquals("BLOCKED_NO_PROGRESS",transition(base,retained,"BLOCKED","BLOCKED_NO_PROGRESS").get("status"));
    }
    @Test void aDifferentExecutableStrategyDoesNotRequireInventedBusinessProgress(){
        var actual=transition(finding(Map.of()),finding(Map.of()),"VERIFY_SOURCE_PAGES","QUEUED");
        assertEquals("QUEUED",actual.get("status"));assertEquals(false,actual.get("technicalRecovered"));assertEquals(true,actual.get("newStrategyWork"));
    }
    @Test void visualReadWithoutBusinessClosureCanProceedToTargetedEvidence(){
        Finding before=finding(fields("gaps",List.of(fields("code","SUBJECT_RELATION_UNPROVEN"))));
        var actual=RecoveryTransition.afterAttempt(before,before,"VERIFY_SOURCE_PAGES",Map.of("strategy","TARGETED_EVIDENCE","status","QUEUED"),false,false,true,List.of());
        assertEquals("QUEUED",actual.get("status"));assertEquals(false,actual.get("businessComplete"));assertEquals(false,actual.get("technicalRecovered"));
    }
    @Test void actualVisualServiceFailureIsSeparateFromNoObservation(){
        Finding after=finding(Map.of());var actual=RecoveryTransition.afterAttempt(after,after,"VERIFY_SOURCE_PAGES",Map.of("strategy","VERIFY_SOURCE_PAGES","status","QUEUED"),false,false,false,List.of(fields("code","VISUAL_RETRY_PENDING","description","暂时失败")));
        assertEquals("FAILED",actual.get("status"));assertEquals(true,actual.get("temporaryVisualFailure"));
    }
    @Test void externalFailureReceiptIsPreservedWithoutEvidenceOrBusinessRecomputation(){
        Finding before=finding(Map.of());List<Map<String,Object>> diagnostics=List.of(fields("code","EXTERNAL_ENGINE_FAILURE","description","指定来源请求超时","providerErrorCode","EXTERNAL_TIMEOUT"));
        Finding after=ExternalCheckRecovery.attachDiagnostics(before,diagnostics);
        var actual=RecoveryTransition.afterAttempt(before,after,"QUERY_EXTERNAL",Map.of("strategy","QUERY_EXTERNAL","status","QUEUED"),false,false,false,diagnostics);
        assertEquals("FAILED",actual.get("status"));assertEquals(true,actual.get("processingFailed"));assertEquals(true,actual.get("externalProcessingFailure"));
        assertEquals(false,actual.get("businessComplete"));assertEquals(false,actual.get("technicalRecovered"));assertEquals("FAILED",actual.get("executionState"));
        assertEquals("指定来源请求超时",actual.get("stopReason"));assertTrue(RecoveryTransition.hasProcessingFailure(after));assertSame(before.evaluation(),after.evaluation());
    }
    @Test void noHitReceiptIsNotARequestFailure(){
        Finding before=finding(Map.of());List<Map<String,Object>> diagnostics=List.of(fields("code","EXTERNAL_RECORD_NOT_FOUND","description","指定范围未命中"));
        Finding after=ExternalCheckRecovery.attachDiagnostics(before,diagnostics);
        var actual=RecoveryTransition.afterAttempt(before,after,"QUERY_EXTERNAL",Map.of("strategy","WAIT_EXTERNAL_RECORD","status","WAITING_EXTERNAL"),false,false,false,diagnostics);
        assertEquals("WAITING_EXTERNAL",actual.get("status"));assertEquals(false,actual.get("processingFailed"));assertEquals(false,actual.get("businessComplete"));
        assertFalse(RecoveryTransition.hasProcessingFailure(after));
    }
    @Test void waitingForExternalInputIsNotConvertedToFailedRetry(){
        var actual=transition(failure(),finding(Map.of()),"BLOCKED","WAITING_EXTERNAL");
        assertEquals("WAITING_EXTERNAL",actual.get("status"));assertEquals(true,actual.get("technicalRecovered"));
    }
    @Test void sameStrategyCanContinueAfterBusinessProgressExposesAnotherPage(){
        Finding before=finding(fields("visualRequests",List.of(fields("documentId","bid","page",1,"task","SEAL"))));
        Finding after=finding(fields("visualRequests",List.of(fields("documentId","bid","page",2,"task","SEAL"))));
        var actual=RecoveryTransition.afterAttempt(before,after,"VERIFY_SOURCE_PAGES",Map.of("strategy","VERIFY_SOURCE_PAGES","status","QUEUED"),true,false,true,List.of());
        assertEquals("QUEUED",actual.get("status"));assertEquals(true,actual.get("inputsChanged"));
        assertEquals(false,actual.get("businessComplete"));assertEquals(false,actual.get("technicalRecovered"));
    }
    @Test void sameStrategyStillStopsWhenChangedPageHasNoBusinessProgress(){
        Finding before=finding(fields("visualRequests",List.of(fields("page",1))));Finding after=finding(fields("visualRequests",List.of(fields("page",2))));
        var actual=RecoveryTransition.afterAttempt(before,after,"VERIFY_SOURCE_PAGES",Map.of("strategy","VERIFY_SOURCE_PAGES","status","QUEUED"),false,false,true,List.of());
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(true,actual.get("inputsChanged"));
    }
    @Test void sameStrategyDoesNotRepeatUnchangedInputEvenWithProgressFlag(){
        Finding before=finding(fields("visualRequests",List.of(fields("page",1))));
        var actual=RecoveryTransition.afterAttempt(before,before,"VERIFY_SOURCE_PAGES",Map.of("strategy","VERIFY_SOURCE_PAGES","status","QUEUED"),true,false,true,List.of());
        assertEquals("BLOCKED_NO_PROGRESS",actual.get("status"));assertEquals(false,actual.get("inputsChanged"));
    }
    @Test void newlyCompletedObligationCanExposeDifferentPendingTarget(){
        Finding before=finding(fields("conditions",List.of(fields("id","A","status","UNDETERMINED"),fields("id","B","status","UNDETERMINED"))));
        Finding after=finding(fields("conditions",List.of(fields("id","A","status","PASS"),fields("id","B","status","UNDETERMINED"))));
        var actual=RecoveryTransition.afterAttempt(before,after,"TARGETED_EVIDENCE",Map.of("strategy","TARGETED_EVIDENCE","status","QUEUED"),true,false,true,List.of());
        assertEquals("QUEUED",actual.get("status"));assertEquals(true,actual.get("inputsChanged"));assertEquals(false,actual.get("businessComplete"));
    }
}

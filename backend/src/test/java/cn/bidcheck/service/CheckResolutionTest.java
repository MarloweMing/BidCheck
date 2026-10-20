package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckResolutionTest {
    Finding finding(String status,Map<String,Object> assessment){
        Checkpoint cp=new Checkpoint("cp","测试要求","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","授权委托书","",true,true,true,null);
        return new Finding("f","run",cp,new Evaluation(status,"说明","",List.of(),0,"RULE"),assessment);
    }
    @Test void systemOwnershipDoesNotInventAnActiveRecoveryJob(){
        Finding f=finding("UNDETERMINED",Map.of());Map<String,Object> plan=CheckResolution.plan(f);
        assertEquals("BLOCKED_NO_PROGRESS",plan.get("status"));
        Map<String,Object> summary=CheckResolution.summary(Map.of("status","COMPLETED"),List.of(f),List.of(),"new-policy");
        assertEquals(0L,summary.get("activeJobCount"));assertEquals(true,summary.get("canRecover"));
    }
    @Test void onlyConcreteVisualRequestsQueuePageWork(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("visualRequests",List.of(Map.of("page",7)))));
        assertEquals("VERIFY_SOURCE_PAGES",CheckResolution.plan(f).get("strategy"));
        assertEquals("QUEUED",CheckResolution.plan(f).get("status"));
    }
    @Test void samePolicyAndNoProgressCannotOfferAnEndlessReplay(){
        Map<String,Object> summary=CheckResolution.summary(Map.of("status","COMPLETED","resolutionPolicyIdentity","p"),List.of(finding("UNDETERMINED",Map.of())),
            List.of(Map.of("id","job","status","BLOCKED_NO_PROGRESS")),"p");
        assertEquals(false,summary.get("canRecover"));assertEquals(1L,summary.get("blockedCount"));
    }
    @Test void failedTransportRemainsRecoverableWithoutACumulativeBudget(){
        Finding f=finding("UNDETERMINED",Map.of("model",Map.of("attempts",List.of(Map.of("errorCode","HTTP_503")))));
        assertEquals("RETRY_FAILED_STEP",CheckResolution.plan(f).get("strategy"));
        assertEquals(true,CheckResolution.summary(Map.of("status","FAILED","resolutionPolicyIdentity","p"),List.of(f),List.of(Map.of("status","FAILED")),"p").get("canRecover"));
    }
    @Test void externalStageDoesNotTriggerRepeatedModelRequests(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("externalRequests",List.of(Map.of("type","UPLOAD_PLATFORM")))));
        assertEquals("WAITING_EXTERNAL",CheckResolution.plan(f).get("status"));
    }
    @Test void completeExternalRequestQueuesConnectorButWrongBusinessStageStillWaits(){
        Map<String,Object> request=Map.of("type","CREDIT_CHINA","subject","海南测试有限公司",
            "evaluationStage","BID_SUBMISSION","referenceDate","2026-10-19");
        Finding ready=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("externalRequests",List.of(request))));
        assertEquals("QUERY_EXTERNAL",CheckResolution.plan(ready).get("strategy"));
        assertEquals("QUEUED",CheckResolution.plan(ready).get("status"));
        Finding wrongStage=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("workflowState","WAIT_STAGE","externalRequests",List.of(request))));
        assertEquals("WAITING_EXTERNAL",CheckResolution.plan(wrongStage).get("status"));
        assertEquals("BLOCKED",CheckResolution.plan(wrongStage).get("strategy"));
    }
    @Test void completedBusinessResultDoesNotInheritAnUnrelatedFailure(){
        assertEquals("SUCCEEDED",CheckResolution.plan(finding("PASS",Map.of("model",Map.of("retryable",true)))).get("status"));
    }
    @Test void outputFailureIsSplitInsteadOfBlindlyRepeatingTheSameRequest(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("recoveryActions",List.of(Map.of("strategy","SPLIT_FAILED_REQUEST")))));
        assertEquals("SPLIT_FAILED_REQUEST",CheckResolution.plan(f).get("strategy"));
    }
    @Test void unstartedSnapshotItemsAreCountedAndRecoverable(){
        Map<String,Object> summary=CheckResolution.summary(Map.of("status","FAILED","resolutionPolicyIdentity","p"),List.of(finding("PASS",Map.of())),List.of(),"p",3);
        assertEquals(3L,summary.get("unresolvedOriginalCount"));assertEquals(4,summary.get("originalCount"));assertEquals(true,summary.get("canRecover"));
    }
    @Test void temporaryVisualFailureIsRecoverableAfterItsCooldown(){
        Finding f=finding("UNDETERMINED",Map.of());Map<String,Object> run=Map.of("status","COMPLETED","resolutionPolicyIdentity","p");
        assertEquals(false,CheckResolution.summary(run,List.of(f),List.of(Map.of("status","FAILED","retryNotBeforeEpochMillis",Long.MAX_VALUE)),"p").get("canRecover"));
        assertEquals(true,CheckResolution.summary(run,List.of(f),List.of(Map.of("status","FAILED","retryNotBeforeEpochMillis",0)),"p").get("canRecover"));
    }
    @Test void invalidCompilationNeverDispatchesAnUnrelatedPictureRequest(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("compilationAudit",Map.of("state","UNRESOLVED"),
            "visualRequests",List.of(Map.of("page",7)),"recoveryActions",List.of(Map.of("strategy","SPLIT_FAILED_REQUEST")))));
        assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));assertEquals("BLOCKED_NO_PROGRESS",CheckResolution.plan(f).get("status"));
    }
    @Test void compilationTransportCooldownIsPreservedWithoutVisualFallback(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("compilationAudit",Map.of("state","REPAIR_RETRY_PENDING","retryNotBeforeEpochMillis",90000L),"visualRequests",List.of(Map.of("page",7)))));
        assertEquals("FAILED",CheckResolution.plan(f).get("status"));assertEquals(90000L,CheckResolution.plan(f).get("retryNotBeforeEpochMillis"));
        assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));
    }
    @Test void failureWordingIdsOrderingAndSummaryCountsDoNotUnlockMoreCalls(){
        Finding before=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("proof",Map.of("id","random-a","reason","文字A"),
            "gaps",List.of(Map.of("code","BAD_PLAN","description","旧说明","ruleId","R1")),"summary",Map.of("pending",6))));
        Finding after=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("proof",Map.of("id","random-b","reason","文字B"),
            "gaps",List.of(Map.of("code","BAD_JSON","description","新说明","ruleId","R999")),"summary",Map.of("pending",8))));
        assertEquals(CheckResolution.outcomeFingerprint(before),CheckResolution.outcomeFingerprint(after));
        assertEquals(false,CheckResolution.progress(before,after).get("progressMade"));
        assertEquals(CheckResolution.actionFingerprint(before,"policy","doc","VERIFY_SOURCE_PAGES"),CheckResolution.actionFingerprint(after,"policy","doc","VERIFY_SOURCE_PAGES"));
    }
    Finding condition(String id,String status,String role,Evidence source){
        return finding("UNDETERMINED",Map.of("atomicCheck",Map.of("conditions",List.of(Map.of("id",id,"title","描述"+id,"predicate","CERTIFICATE","subject","施工单位","sourceQuote","施工单位应持有资质","conditionRole",role,"status",status,"evidences",List.of(source))))));
    }
    @Test void aVerifiedBusinessConditionCountsButItsRandomIdDoesNot(){
        Evidence e=new Evidence("bid","bid.pdf",15,"PAGE","证书编号：A12345","a",0);
        Finding before=condition("R1","UNDETERMINED","REQUIREMENT",e),after=condition("R987","PASS","REQUIREMENT",e);
        assertEquals(true,CheckResolution.progress(before,after).get("progressMade"));assertEquals(1L,CheckResolution.progress(before,after).get("newCompletedConditionCount"));
        assertEquals(CheckResolution.outcomeFingerprint(after),CheckResolution.outcomeFingerprint(condition("different","PASS","REQUIREMENT",e)));
    }
    @Test void referenceDateAloneAndProofRegressionAreNotBusinessProgress(){
        Evidence e=new Evidence("tender","tender.docx",1,"BLOCK","投标截止时间2025年8月5日","a",0);
        Finding unresolved=finding("UNDETERMINED",Map.of());
        assertEquals(false,CheckResolution.progress(unresolved,condition("R1","PASS","AUXILIARY",e)).get("progressMade"));
        assertEquals(false,CheckResolution.progress(condition("R1","PASS","REQUIREMENT",e),unresolved).get("progressMade"));
    }
    @Test void recoveredFailureDoesNotTriggerAnotherRetry(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("modelDiagnostics",Map.of("failures",List.of(Map.of("retryable",true,"errorCode","HTTP_503","recoveredByRetry",true))))));
        assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));
    }
    @Test void longerCitationWithoutAnAdditionalProvedConditionDoesNotUnlockAnotherStrategy(){
        Finding shortQuote=condition("a","PASS","REQUIREMENT",new Evidence("bid","bid.pdf",15,"PAGE","证书编号A1","",0));
        Finding longQuote=condition("b","PASS","REQUIREMENT",new Evidence("bid","bid.pdf",15,"PAGE","资质证书，证书编号A1，有效期至2028年","",0));
        assertEquals(false,CheckResolution.progress(shortQuote,longQuote).get("progressMade"));
        assertEquals(CheckResolution.actionFingerprint(shortQuote,"p","d","X"),CheckResolution.actionFingerprint(longQuote,"p","d","X"));
    }
    @Test void temporaryFailureCannotEraseCompletedPartialProofAndNewContradictionIsNotHidden(){
        Evidence e=new Evidence("bid","bid.pdf",15,"PAGE","证书编号A1","",0);
        Finding before=condition("a","PASS","REQUIREMENT",e);
        Finding failed=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("modelDiagnostics",Map.of("errorCode","MODEL_TIMEOUT"))));
        Finding kept=CheckResolution.protectCompletedProof(before,failed);
        assertEquals(CheckResolution.atomic(before),CheckResolution.atomic(kept));assertEquals("UNDETERMINED",kept.evaluation().status());
        assertEquals(true,CheckResolution.map(kept.assessment().get("recoveryFailure")).get("retainedCompletedProof"));
        Finding contradicted=condition("a","FAIL","REQUIREMENT",e);
        assertSame(contradicted,CheckResolution.protectCompletedProof(before,contradicted));
    }
    @Test void extractionTimeoutIsNotMisreportedAsACompilationServiceFailure(){
        Finding f=finding("UNDETERMINED",Map.of("atomicCheck",Map.of("compilationAudit",Map.of("state","UNRESOLVED"),
            "modelDiagnostics",Map.of("failures",List.of(Map.of("stage","EXTRACT","errorCode","REQUEST_TIMEOUT","retryable",true))))));
        Map<String,Object> plan=CheckResolution.plan(f);assertEquals("FAILED",plan.get("status"));assertEquals("BLOCKED",plan.get("strategy"));
        assertTrue(plan.get("reason").toString().contains("证据处理"));assertFalse(plan.get("reason").toString().contains("要求解析的服务请求暂时失败"));
    }
}

package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckResolutionTest {
    Finding finding(String status,Map<String,Object> assessment){
        Checkpoint cp=new Checkpoint("cp","测试要求","形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","授权委托书","",true,true,true,null);
        return new Finding("f","run",cp,new Evaluation(status,"说明","",cn.bidcheck.compat.Java8Api.listOf(),0,"RULE"),assessment);
    }
    @Test void systemOwnershipDoesNotInventAnActiveRecoveryJob(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf());Map<String,Object> plan=CheckResolution.plan(f);
        assertEquals("BLOCKED_NO_PROGRESS",plan.get("status"));
        Map<String,Object> summary=CheckResolution.summary(cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED"),cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(),"new-policy");
        assertEquals(0L,summary.get("activeJobCount"));assertEquals(true,summary.get("canRecover"));
    }
    @Test void onlyConcreteVisualRequestsQueuePageWork(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 7)))));
        assertEquals("VERIFY_SOURCE_PAGES",CheckResolution.plan(f).get("strategy"));
        assertEquals("QUEUED",CheckResolution.plan(f).get("status"));
    }
    @Test void pendingVisualWorkRunsBeforeAnAgentStallWaitsForNewInput(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("agentInvestigation", cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "errorCode", "AGENT_STALLED_WITH_REMAINING_TARGETS"), "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "AGENT_INVESTIGATION_STALLED")), "visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "page", 7, "task", "FIELD")))));
        Map<String,Object> plan=CheckResolution.plan(f);
        assertEquals("VERIFY_SOURCE_PAGES",plan.get("strategy"));
        assertEquals("QUEUED",plan.get("status"));
    }
    @Test void samePolicyAndNoProgressCannotOfferAnEndlessReplay(){
        Map<String,Object> summary=CheckResolution.summary(cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "resolutionPolicyIdentity", "p"),cn.bidcheck.compat.Java8Api.listOf(finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf())),
            cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", "BLOCKED_NO_PROGRESS")),"p");
        assertEquals(false,summary.get("canRecover"));assertEquals(1L,summary.get("blockedCount"));
    }
    @Test void failedTransportRemainsRecoverableWithoutACumulativeBudget(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("model", cn.bidcheck.compat.Java8Api.mapOf("attempts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("errorCode", "HTTP_503")))));
        assertEquals("RETRY_FAILED_STEP",CheckResolution.plan(f).get("strategy"));
        assertEquals(true,CheckResolution.summary(cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "resolutionPolicyIdentity", "p"),cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED")),"p").get("canRecover"));
    }
    @Test void missingExternalScopeIsSystemRepairInsteadOfUnknownStageOrUserWork(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("type", "UPLOAD_PLATFORM")))));
        assertEquals("FAILED",CheckResolution.plan(f).get("status"));assertEquals("REPAIR_EXTERNAL_SCOPE",CheckResolution.plan(f).get("strategy"));
    }
    @Test void completeExternalRequestQueuesConnectorButWrongBusinessStageStillWaits(){
        Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_CHINA", "subject", "海南测试有限公司", "evaluationStage", "BID_SUBMISSION", "referenceDate", "2026-10-19");
        Finding ready=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(request))));
        assertEquals("QUERY_EXTERNAL",CheckResolution.plan(ready).get("strategy"));
        assertEquals("QUEUED",CheckResolution.plan(ready).get("status"));
        Finding wrongStage=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("workflowState", "WAIT_STAGE", "externalRequests", cn.bidcheck.compat.Java8Api.listOf(request))));
        assertEquals("WAITING_EXTERNAL",CheckResolution.plan(wrongStage).get("status"));
        assertEquals("WAIT_STAGE",CheckResolution.plan(wrongStage).get("strategy"));
    }
    @Test void completedBusinessResultDoesNotInheritAnUnrelatedFailure(){
        assertEquals("SUCCEEDED",CheckResolution.plan(finding("PASS",cn.bidcheck.compat.Java8Api.mapOf("model", cn.bidcheck.compat.Java8Api.mapOf("retryable", true)))).get("status"));
    }
    @Test void outputFailureIsSplitInsteadOfBlindlyRepeatingTheSameRequest(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("recoveryActions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("strategy", "SPLIT_FAILED_REQUEST")))));
        assertEquals("SPLIT_FAILED_REQUEST",CheckResolution.plan(f).get("strategy"));
    }
    @Test void unstartedSnapshotItemsAreCountedAndRecoverable(){
        Map<String,Object> summary=CheckResolution.summary(cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "resolutionPolicyIdentity", "p"),cn.bidcheck.compat.Java8Api.listOf(finding("PASS",cn.bidcheck.compat.Java8Api.mapOf())),cn.bidcheck.compat.Java8Api.listOf(),"p",3);
        assertEquals(3L,summary.get("unresolvedOriginalCount"));assertEquals(4,summary.get("originalCount"));assertEquals(true,summary.get("canRecover"));
    }
    @Test void temporaryVisualFailureIsRecoverableAfterItsCooldown(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf());Map<String,Object> run=cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "resolutionPolicyIdentity", "p");
        assertEquals(false,CheckResolution.summary(run,cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "retryNotBeforeEpochMillis", Long.MAX_VALUE)),"p").get("canRecover"));
        assertEquals(true,CheckResolution.summary(run,cn.bidcheck.compat.Java8Api.listOf(f),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "retryNotBeforeEpochMillis", 0)),"p").get("canRecover"));
    }
    @Test void invalidCompilationNeverDispatchesAnUnrelatedPictureRequest(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("compilationAudit", cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"), "visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 7)), "recoveryActions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("strategy", "SPLIT_FAILED_REQUEST")))));
        assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));assertEquals("BLOCKED_NO_PROGRESS",CheckResolution.plan(f).get("status"));
    }
    @Test void compilationTransportCooldownIsPreservedWithoutVisualFallback(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("compilationAudit", cn.bidcheck.compat.Java8Api.mapOf("state", "REPAIR_RETRY_PENDING", "retryNotBeforeEpochMillis", 90000L), "visualRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 7)))));
        assertEquals("FAILED",CheckResolution.plan(f).get("status"));assertEquals(90000L,CheckResolution.plan(f).get("retryNotBeforeEpochMillis"));
        assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));
    }
    @Test void failureWordingIdsOrderingAndSummaryCountsDoNotUnlockMoreCalls(){
        Finding before=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("proof", cn.bidcheck.compat.Java8Api.mapOf("id", "random-a", "reason", "文字A"), "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "BAD_PLAN", "description", "旧说明", "ruleId", "R1")), "summary", cn.bidcheck.compat.Java8Api.mapOf("pending", 6))));
        Finding after=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("proof", cn.bidcheck.compat.Java8Api.mapOf("id", "random-b", "reason", "文字B"), "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "BAD_JSON", "description", "新说明", "ruleId", "R999")), "summary", cn.bidcheck.compat.Java8Api.mapOf("pending", 8))));
        assertEquals(CheckResolution.outcomeFingerprint(before),CheckResolution.outcomeFingerprint(after));
        assertEquals(false,CheckResolution.progress(before,after).get("progressMade"));
        assertEquals(CheckResolution.actionFingerprint(before,"policy","doc","VERIFY_SOURCE_PAGES"),CheckResolution.actionFingerprint(after,"policy","doc","VERIFY_SOURCE_PAGES"));
    }
    Finding condition(String id,String status,String role,Evidence source){
        return finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", id, "title", "描述"+id, "predicate", "CERTIFICATE", "subject", "施工单位", "sourceQuote", "施工单位应持有资质", "conditionRole", role, "status", status, "evidences", cn.bidcheck.compat.Java8Api.listOf(source))))));
    }
    Finding frozenInputs(Finding finding){
        Map<String,Object> atomic=new LinkedHashMap<>(CheckResolution.atomic(finding));
        atomic.put("recoveryDependencies",cn.bidcheck.compat.Java8Api.mapOf("version", "receipt-v3", "sourceRevision", "frozen-docs", "validationIdentity", "validator-v2", "planRevision", "same-rules", "inputRevision", "same-inputs"));
        Map<String,Object> assessment=new LinkedHashMap<>(finding.assessment());assessment.put("atomicCheck",atomic);
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),finding.evaluation(),assessment);
    }
    @Test void aVerifiedBusinessConditionCountsButItsRandomIdDoesNot(){
        Evidence e=new Evidence("bid","bid.pdf",15,"PAGE","证书编号：A12345","a",0);
        Finding before=condition("R1","UNDETERMINED","REQUIREMENT",e),after=condition("R987","PASS","REQUIREMENT",e);
        assertEquals(true,CheckResolution.progress(before,after).get("progressMade"));assertEquals(1L,CheckResolution.progress(before,after).get("newCompletedConditionCount"));
        assertEquals(CheckResolution.outcomeFingerprint(after),CheckResolution.outcomeFingerprint(condition("different","PASS","REQUIREMENT",e)));
    }
    @Test void referenceDateAloneAndProofRegressionAreNotBusinessProgress(){
        Evidence e=new Evidence("tender","tender.docx",1,"BLOCK","投标截止时间2025年8月5日","a",0);
        Finding unresolved=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals(false,CheckResolution.progress(unresolved,condition("R1","PASS","AUXILIARY",e)).get("progressMade"));
        assertEquals(false,CheckResolution.progress(condition("R1","PASS","REQUIREMENT",e),unresolved).get("progressMade"));
    }
    @Test void recoveredFailureDoesNotTriggerAnotherRetry(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("failures", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("retryable", true, "errorCode", "HTTP_503", "recoveredByRetry", true))))));
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
        Finding before=frozenInputs(condition("a","PASS","REQUIREMENT",e));
        Finding failed=frozenInputs(finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("errorCode", "MODEL_TIMEOUT")))));
        Finding kept=CheckResolution.protectCompletedProof(before,failed);
        assertEquals(CheckResolution.atomic(before),CheckResolution.atomic(kept));assertEquals("UNDETERMINED",kept.evaluation().status());
        assertEquals(true,CheckResolution.map(kept.assessment().get("recoveryFailure")).get("retainedCompletedProof"));
        assertEquals(true,CheckResolution.map(kept.assessment().get("recoveryFailure")).get("processingFailed"));
        Finding contradicted=condition("a","FAIL","REQUIREMENT",e);
        assertSame(contradicted,CheckResolution.protectCompletedProof(before,contradicted));
    }
    @Test void unresolvedNewRuleDoesNotReviveProofThatTheCurrentValidatorDidNotProve(){
        Finding before=condition("a","PASS","REQUIREMENT",new Evidence("bid","bid.pdf",15,"PAGE","证书编号A1","",0));
        Finding unresolved=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("compilationAudit", cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"))));
        Finding kept=CheckResolution.protectCompletedProof(before,unresolved);
        assertEquals(CheckResolution.atomic(unresolved),CheckResolution.atomic(kept));
        java.util.Map<java.lang.String, java.lang.Object> receipt=CheckResolution.map(kept.assessment().get("supersededProof"));
        assertEquals(false,receipt.get("activeBusinessProof"));assertEquals("NO_EXPLICIT_TRANSPORT_FAILURE",receipt.get("reasonCode"));
        assertFalse(CheckResolution.maps(receipt.get("previousConditions")).isEmpty());assertFalse(RecoveryTransition.hasProcessingFailure(kept));
    }
    @Test void retainedPartialProofDoesNotRestoreAnAlreadyRecoveredRequestFailure(){
        Finding proved=condition("a","PASS","REQUIREMENT",new Evidence("bid","bid.pdf",15,"PAGE","证书编号A1","",0));
        Map<String,Object> old=new LinkedHashMap<>(CheckResolution.atomic(proved));old.put("modelDiagnostics",cn.bidcheck.compat.Java8Api.mapOf("unresolvedFailures", 1, "errorCode", "MODEL_TIMEOUT"));
        Finding before=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", old));
        Finding after=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("compilationAudit", cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"), "modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("unresolvedFailures", 0))));
        assertTrue(RecoveryTransition.hasProcessingFailure(before));
        Finding kept=CheckResolution.protectCompletedProof(before,after);
        assertEquals(CheckResolution.atomic(after),CheckResolution.atomic(kept));assertFalse(RecoveryTransition.hasProcessingFailure(kept));
        assertEquals(CheckResolution.plan(after),CheckResolution.plan(kept));
        java.util.Map<java.lang.String, java.lang.Object> transition=RecoveryTransition.afterAttempt(before,kept,"RETRY_FAILED_STEP",CheckResolution.plan(kept),false,false,true,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(false,transition.get("processingFailed"));assertEquals(true,transition.get("technicalRecovered"));
    }
    @Test void sourceRejectionOrExplicitRevalidationRevokesPriorPassEvenWhenAnotherRequestTimesOut(){
        Evidence evidence=new Evidence("bid","bid.pdf",15,"PAGE","证书编号A1","",0);Finding before=frozenInputs(condition("a","PASS","REQUIREMENT",evidence));
        for(Map<String,Object> revocation:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("rejectedFacts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "RECOVERY_SOURCE_REVISION_MISMATCH", "sourceIds", cn.bidcheck.compat.Java8Api.listOf("s1")))), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "a", "status", "UNDETERMINED", "conditionRole", "REQUIREMENT", "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "SUBJECT_IDENTITY_CONFLICT"))))))){
            Map<String,Object> atomic=new LinkedHashMap<>(revocation);atomic.put("modelDiagnostics",cn.bidcheck.compat.Java8Api.mapOf("errorCode", "MODEL_TIMEOUT"));atomic.put("compilationAudit",cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"));
            Finding after=frozenInputs(finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic))),result=CheckResolution.protectCompletedProof(before,after);
            assertEquals(CheckResolution.atomic(after),CheckResolution.atomic(result));assertFalse(result.assessment().containsKey("recoveryFailure"));
            assertEquals("CURRENT_VALIDATOR_REVOKED_PROOF",CheckResolution.map(result.assessment().get("supersededProof")).get("reasonCode"));
        }
    }
    @Test void timeoutCannotCarryOldProofAcrossUnknownChangedSourcesOrValidatorVersions(){
        Finding before=frozenInputs(condition("a","PASS","REQUIREMENT",new Evidence("bid","bid.pdf",15,"PAGE","证书编号A1","",0)));
        Finding timeout=frozenInputs(finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("errorCode", "MODEL_TIMEOUT")))));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("version", "sourceRevision", "validationIdentity", "planRevision", "inputRevision")){
            Map<String,Object> atomic=new LinkedHashMap<>(CheckResolution.atomic(timeout)),receipt=new LinkedHashMap<>(CheckResolution.map(atomic.get("recoveryDependencies")));receipt.put(key,"changed");atomic.put("recoveryDependencies",receipt);
            Finding after=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic)),result=CheckResolution.protectCompletedProof(before,after);
            assertEquals(CheckResolution.atomic(after),CheckResolution.atomic(result),key);assertEquals(false,CheckResolution.map(result.assessment().get("supersededProof")).get("activeBusinessProof"));
        }
        Finding legacy=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("errorCode", "MODEL_TIMEOUT"))));assertEquals(CheckResolution.atomic(legacy),CheckResolution.atomic(CheckResolution.protectCompletedProof(before,legacy)));
    }
    @Test void extractionTimeoutIsNotMisreportedAsACompilationServiceFailure(){
        Finding f=finding("UNDETERMINED",cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("compilationAudit", cn.bidcheck.compat.Java8Api.mapOf("state", "UNRESOLVED"), "modelDiagnostics", cn.bidcheck.compat.Java8Api.mapOf("failures", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("stage", "EXTRACT", "errorCode", "REQUEST_TIMEOUT", "retryable", true))))));
        Map<String,Object> plan=CheckResolution.plan(f);assertEquals("FAILED",plan.get("status"));assertEquals("BLOCKED",plan.get("strategy"));
        assertTrue(plan.get("reason").toString().contains("证据处理"));assertFalse(plan.get("reason").toString().contains("要求解析的服务请求暂时失败"));
    }
}

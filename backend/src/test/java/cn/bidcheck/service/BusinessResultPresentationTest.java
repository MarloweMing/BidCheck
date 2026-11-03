package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Evaluation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessResultPresentationTest {
    @Test void unimplementedSupplementAdapterIsSystemWorkNotASecondUploadRequest(){
        cn.bidcheck.domain.Domain.Evaluation evaluation=new Evaluation("UNDETERMINED","补证解析完成，当前判定器尚未消费附件","",cn.bidcheck.compat.Java8Api.listOf(),0,"SUPPLEMENT_ANALYSIS_REQUIRED");
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation,cn.bidcheck.compat.Java8Api.mapOf("publicationGuard", cn.bidcheck.compat.Java8Api.mapOf("code", "SUPPLEMENT_EVALUATOR_UNAVAILABLE")));
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));
    }
    @Test void rejectedFieldEvidenceIsNotAnUnreadOrFailedRequestButExplicitMappingStillNeedsSystemWork(){
        java.util.Map<java.lang.String, java.lang.String> field=cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "errorCode", "FIELD_VALIDATION_FAILED");
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> atomic=new LinkedHashMap<String,Object>(cn.bidcheck.compat.Java8Api.mapOf("fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(field)));
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
        assertEquals("EVIDENCE_PENDING",p.get("state"));assertEquals("FINISHED",dimension(p,"execution").get("state"));
        atomic.put("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REGISTRATION_EMPLOYER_BINDING_REQUIRED")));
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic)).get("state"));
    }
    @Test void verifiedSemanticWrittenProofHasSameNarrowScopeAsAtomicDeclaration(){
        cn.bidcheck.domain.Domain.Evidence evidence=new cn.bidcheck.domain.Domain.Evidence("bid","投标文件",1,"PAGE","我方承诺遵守相关管理规定。","p1",1);
        cn.bidcheck.domain.Domain.Evaluation evaluation=new Evaluation("PASS","承诺文字已提供","",cn.bidcheck.compat.Java8Api.listOf(evidence),0,"SEMANTIC");
        java.util.Map<java.lang.String, java.lang.Object> proof=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("validated", true, "scope", "WRITTEN_RESPONSE", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C1"));
        java.util.Map<java.lang.String, java.lang.Object> assessment=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("semanticProof", proof));
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation,assessment);assertEquals("书面要求已满足",p.get("label"));assertEquals("WRITTEN_RESPONSE",dimension(p,"proofScope").get("state"));assertTrue(p.get("nextStep").toString().contains("不等于实际资格状态"));
        assertEquals("UNSPECIFIED",dimension(BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment),"proofScope").get("state"));
        assertEquals("UNSPECIFIED",dimension(BusinessResultPresentation.describe(evaluation("PASS"),assessment),"proofScope").get("state"));
    }
    @Test void stoppedRecoveryDoesNotPromiseContinuingBackgroundWorkOrAskForMaterials(){
        for(String status:cn.bidcheck.compat.Java8Api.listOf("BLOCKED_NO_PROGRESS", "FAILED")){
            java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", status));
            assertTrue(p.get("nextStep").toString().contains("已停止"));assertFalse((boolean)p.get("userActionRequired"));assertEquals(false,dimension(p,"execution").get("active"));
        }
    }
    @Test void validatedGapSeparatesSystemRetrievalAndExternalRecordButFailureStillWins(){
        for(String kind:cn.bidcheck.compat.Java8Api.listOf("SYSTEM_REQUIREMENT", "SYSTEM_RETRIEVAL", "SYSTEM_RELATION", "EXTERNAL_INPUT")){
            java.util.Map<java.lang.String, java.lang.Object> a=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("semanticGap", cn.bidcheck.compat.Java8Api.mapOf("validated", true, "kind", kind)));
            java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),a);assertEquals(kind.equals("EXTERNAL_INPUT")?"USER_ACTION":"SYSTEM_INCOMPLETE",p.get("state"));
            java.util.Map<java.lang.String, java.lang.Object> failed=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),a,cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", "FAILED"));assertEquals("SYSTEM_INCOMPLETE",failed.get("state"));
        }
    }
    @Test void observedOccurrenceScopeDoesNotBecomeCertificateOrFullDocumentPass(){
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> d=new LinkedHashMap<String,Object>(cn.bidcheck.compat.Java8Api.mapOf("status", "PASS", "proofScope", "OBSERVED_OCCURRENCES", "comparisonCoverageComplete", true, "documentCoverageComplete", false, "originalOccurrenceIds", cn.bidcheck.compat.Java8Api.listOf("o1", "o2"), "unresolvedOccurrenceIds", cn.bidcheck.compat.Java8Api.listOf()));
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("PASS"),cn.bidcheck.compat.Java8Api.mapOf("derivedConclusion", d));assertEquals("已识别记录一致",p.get("label"));assertEquals("OBSERVED_OCCURRENCES",dimension(p,"proofScope").get("state"));
        d.put("unresolvedOccurrenceIds",cn.bidcheck.compat.Java8Api.listOf("o2"));assertEquals("UNSPECIFIED",dimension(BusinessResultPresentation.describe(evaluation("PASS"),cn.bidcheck.compat.Java8Api.mapOf("derivedConclusion", d)),"proofScope").get("state"));
    }
    @SuppressWarnings("unchecked") Map<String,Object> dimension(Map<String,Object> p,String name){return (Map<String,Object>)((Map<?,?>)p.get("dimensions")).get(name);}
    Evaluation evaluation(String status){return new Evaluation(status,"最终依据说明","原始建议",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES");}
    Map<String,Object> provenCondition(String predicate){return cn.bidcheck.compat.Java8Api.mapOf("predicate", predicate, "conditionRole", "REQUIREMENT", "status", "PASS", "evaluated", true, "proof", cn.bidcheck.compat.Java8Api.mapOf("sourceBound", true), "evidences", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "d", "page", 1, "quote", "对应原文")), "gaps", cn.bidcheck.compat.Java8Api.listOf());}
    @Test void writtenResponseIsNotPresentedAsExternalFactVerification(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("PASS"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(provenCondition("DECLARATION"), provenCondition("MATERIAL")))));
        assertEquals("WRITTEN_RESPONSE",dimension(p,"proofScope").get("state"));assertEquals("书面要求已满足",dimension(p,"conclusion").get("label"));assertEquals("SATISFIED",p.get("state"));
    }
    @Test void factualConditionsHaveDistinctScopeAndMixedOrIncompleteStructureDoesNotGuess(){
        java.util.Map<java.lang.String, java.lang.Object> factual=BusinessResultPresentation.describe(evaluation("PASS"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(provenCondition("NUMBER"), provenCondition("VALIDITY")))));
        assertEquals("BUSINESS_CONDITIONS",dimension(factual,"proofScope").get("state"));assertEquals("业务条件已验证",dimension(factual,"conclusion").get("label"));
        for(List<Map<String,Object>> rows:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(provenCondition("NUMBER"), provenCondition("DECLARATION")), cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("conditionRole", "REQUIREMENT", "predicate", "NUMBER", "status", "PASS")), cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("conditionRole", "AUXILIARY", "predicate", "REFERENCE_TIME", "status", "PASS")))){
            java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("PASS"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("conditions", rows)));assertEquals("UNSPECIFIED",dimension(p,"proofScope").get("state"));assertEquals("满足要求",dimension(p,"conclusion").get("label"));
        }
    }
    @Test void actualFailedVisualJobCannotBeHiddenBehindStageOrExternalUserAction(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("workflowState", "WAIT_STAGE", "externalRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "PLATFORM")))),cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", "FAILED", "diagnostics", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_ENGINE_FAILURE"))));
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals(false,p.get("userActionRequired"));assertEquals("FAILED",dimension(p,"execution").get("state"));assertEquals(false,dimension(p,"execution").get("active"));
    }
    @Test void failedFieldIsSystemWorkButReadNotFoundRemainsEvidencePending(){for(java.lang.String state:cn.bidcheck.compat.Java8Api.listOf("UNREAD", "FAILED", "READ_NOT_FOUND", "EXTRACTED")){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("fieldCoverage", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", state)))));assertEquals(cn.bidcheck.compat.Java8Api.setOf("UNREAD", "FAILED").contains(state)?"SYSTEM_INCOMPLETE":"EVIDENCE_PENDING",p.get("state"));assertEquals(false,p.get("userActionRequired"));
    }}
    @Test void finalGuardOutranksInternalCompletedAndPreservesOriginalProof(){
        Map<String,Object> raw=cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("businessDecision", "PASS", "workflowState", "COMPLETED", "complete", true, "conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("predicate", "REFERENCE_TIME", "status", "PASS"))));
        java.util.Map<java.lang.String, java.lang.Object> result=FocusedCheckEvaluator.reconcileAssessment(evaluation("UNDETERMINED"),raw);
        java.util.Map<?, ?> atomic=(Map<?,?>)result.get("atomicCheck");java.util.Map<?, ?> display=(Map<?,?>)result.get("businessPresentation");
        assertEquals("PASS",atomic.get("businessDecision"));assertEquals("UNDETERMINED",atomic.get("finalBusinessStatus"));
        assertEquals("SYSTEM_INCOMPLETE",display.get("state"));assertEquals(false,display.get("userActionRequired"));
        assertFalse(((Map<?,?>)atomic.get("presentation")).get("state").equals("COMPLETED"));
        assertFalse(((Map<?,?>)raw.get("atomicCheck")).containsKey("finalBusinessStatus"));
        String html=ReportService.atomicCheckHtml((Map<String,Object>)atomic);
        assertFalse(html.contains("条件核验已完成"));assertTrue(html.contains("参考时点已确认"));
    }
    @Test void modelFailureIsSystemWorkEvenWhenVisualReviewAlsoExists(){
        java.util.Map<java.lang.String, java.lang.Object> assessment=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("adjustments", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "MODEL_TIMEOUT")), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true));
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals("SYSTEM",p.get("owner"));assertEquals(false,p.get("userActionRequired"));
    }
    @Test void evidenceNotFoundIsNotAnInstructionToSubmitMissingMaterials(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "CERTIFICATE_EVIDENCE_REQUIRED")))));
        assertEquals("EVIDENCE_PENDING",p.get("state"));assertEquals(false,p.get("userActionRequired"));assertTrue(p.get("nextStep").toString().contains("只有确认具体缺口"));
    }
    @Test void comparisonMappingAndRegistrationBindingsAreSystemWorkWithoutChangingOutcome(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("COMPARISON_FIELD_MAPPING_REQUIRED", "COMPARISON_RECORD_IDENTITY_UNPROVEN", "REGISTRATION_EMPLOYER_BINDING_REQUIRED")){
            java.util.Map<java.lang.String, java.lang.Object> assessment=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code, "description", "证书单位与任职单位尚未对应"))), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true));String before=assessment.toString();
            java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals("SYSTEM",p.get("owner"));assertEquals(false,p.get("userActionRequired"));assertEquals("UNDETERMINED",p.get("finalStatus"));assertEquals("证书单位与任职单位尚未对应",p.get("reason"));assertTrue(p.get("nextStep").toString().contains("系统需继续"));assertEquals(before,assessment.toString());
            assertEquals("SATISFIED",BusinessResultPresentation.describe(evaluation("PASS"),assessment).get("state"));
            java.util.Map<java.lang.String, java.lang.Object> stopped=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment,cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", "BLOCKED_NO_PROGRESS"));assertTrue(stopped.get("nextStep").toString().contains("已停止"));
        }
        java.util.Map<java.lang.String, java.lang.Object> missing=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REGISTRATION_CERTIFICATE_REQUIRED"))), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true)));assertEquals("EVIDENCE_PENDING",missing.get("state"));assertEquals(false,missing.get("userActionRequired"));
    }
    @Test void requestAloneDoesNotProveExternalExecutionOrCreateUserTask(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "PLATFORM")))));
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals(false,p.get("userActionRequired"));
    }
    @Test void successfulPublishedResultKeepsSeparateVisualTaskAndRecoveredFailure(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("PASS"),cn.bidcheck.compat.Java8Api.mapOf("ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true), "adjustments", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "MODEL_TIMEOUT"))));
        assertEquals("SATISFIED",p.get("state"));assertEquals(true,p.get("userActionRequired"));
    }
    @Test void warningIsRiskWithoutChangingAutomaticStatus(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("WARNING"),cn.bidcheck.compat.Java8Api.mapOf());assertEquals("RISK",p.get("state"));assertEquals("WARNING",p.get("finalStatus"));
    }
    @Test void completenessSeparatesUnparsedInventoryFromMissingBodyEvidence(){
        java.util.Map<java.lang.String, java.lang.Object> unresolved=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("guards", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REQUIREMENT_COMPLETENESS_UNPROVEN")), "coverage", cn.bidcheck.compat.Java8Api.mapOf("completeness", cn.bidcheck.compat.Java8Api.mapOf("inventoryResolved", false)));
        java.util.Map<java.lang.String, java.lang.Object> resolved=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("guards", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REQUIREMENT_COMPLETENESS_UNPROVEN")), "coverage", cn.bidcheck.compat.Java8Api.mapOf("completeness", cn.bidcheck.compat.Java8Api.mapOf("inventoryResolved", true, "coreRequirementDeferred", false)));
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),unresolved).get("state"));
        java.util.Map<java.lang.String, java.lang.Object> result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),resolved);assertEquals("EVIDENCE_PENDING",result.get("state"));assertEquals(false,result.get("userActionRequired"));
    }
    @Test void genericOcrNoteDoesNotAssignPrimaryEvidenceWorkToUser(){
        for(Object before:cn.bidcheck.compat.Java8Api.listOf(evaluation("UNDETERMINED"), cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED"))){
            java.util.Map<java.lang.String, java.lang.Object> result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("preOcrEvaluation", before, "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true)));
            assertEquals("EVIDENCE_PENDING",result.get("state"));assertEquals(false,result.get("userActionRequired"));
        }
        java.util.Map<java.lang.String, java.lang.Object> relation=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "SUBJECT_RELATION_UNPROVEN"))), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true));
        assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),relation).get("state"));
    }
    @Test void checklistExecutionOrApplicabilityGapIsNotMissingUserMaterial(){
        java.util.Map<java.lang.String, java.lang.Object> batch=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("guards", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "CHECKLIST_BATCH_REQUIRED")));
        java.util.Map<java.lang.String, java.lang.Object> condition=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("guards", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "REQUIREMENT_COMPLETENESS_UNPROVEN")), "coverage", cn.bidcheck.compat.Java8Api.mapOf("completeness", cn.bidcheck.compat.Java8Api.mapOf("inventoryResolved", true, "itemResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "REQUIREMENT_UNRESOLVED")))));
        for(java.util.Map<java.lang.String, java.lang.Object> assessment:cn.bidcheck.compat.Java8Api.listOf(batch, condition)){
            java.util.Map<java.lang.String, java.lang.Object> result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);
            assertEquals("SYSTEM_INCOMPLETE",result.get("state"));assertEquals(false,result.get("userActionRequired"));
        }
    }
    @Test void unexecutedExternalQueryRemainsSystemWorkWhileVisualOnlyDowngradeRemainsVisible(){
        java.util.Map<java.lang.String, java.lang.Object> external=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("preOcrEvaluation", evaluation("UNDETERMINED"), "atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("externalRequests", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("kind", "PLATFORM"))), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true));
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),external).get("state"));
        java.util.Map<java.lang.String, java.lang.Object> visual=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("preOcrEvaluation", evaluation("PASS"), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true));
        assertEquals("USER_ACTION",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),visual).get("state"));
    }
    @Test void systemOwnedOcrSourceIsCollapsedAndNotAnActionableWarningInHtml(){
        for(java.util.Map<java.lang.String, java.lang.Object> extra:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("preOcrEvaluation", evaluation("UNDETERMINED")), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("adjustments", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "MODEL_TIMEOUT"))))){
            java.util.LinkedHashMap<java.lang.String, java.lang.Object> assessment=new LinkedHashMap<String,Object>(extra);assessment.put("ocrReview",cn.bidcheck.compat.Java8Api.mapOf("required", true, "reason", "历史识别说明<保留>", "locations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("label", "投标文件第 26 页"))));
            cn.bidcheck.domain.Domain.Finding finding=new cn.bidcheck.domain.Domain.Finding("f","r",null,evaluation("UNDETERMINED"),assessment);
            String rendered=ReportService.originalReviewHtml(finding,cn.bidcheck.compat.Java8Api.mapOf());
            assertTrue(rendered.startsWith("<details class=recognition-source-record>"));assertTrue(rendered.contains("识别来源记录"));assertTrue(rendered.contains("投标文件第 26 页"));assertTrue(rendered.contains("历史识别说明&lt;保留&gt;"));assertFalse(rendered.contains("原图复核提示"));assertFalse(rendered.contains("<details open"));assertTrue(Boolean.TRUE.equals(((Map<?,?>)assessment.get("ocrReview")).get("required")));
        }
    }
    @Test void passedAndRiskFindingsKeepActionableOriginalImageWarning(){
        for(String status:cn.bidcheck.compat.Java8Api.listOf("PASS", "WARNING")){
            cn.bidcheck.domain.Domain.Finding finding=new cn.bidcheck.domain.Domain.Finding("f","r",null,evaluation(status),cn.bidcheck.compat.Java8Api.mapOf("ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true, "reason", "请核对所列原图")));
            String rendered=ReportService.originalReviewHtml(finding,cn.bidcheck.compat.Java8Api.mapOf());assertTrue(rendered.contains("class=ocr-review-note"));assertTrue(rendered.contains("原图复核提示"));assertFalse(rendered.contains("recognition-source-record"));
        }
    }
    @Test void systemOwnershipDoesNotPretendRecoveryIsRunning(){
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("adjustments", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "MODEL_TIMEOUT"))));
        assertEquals("SYSTEM",p.get("owner"));assertEquals("FINISHED",dimension(p,"execution").get("state"));assertEquals(false,dimension(p,"execution").get("active"));assertEquals(false,dimension(p,"conclusion").get("determined"));
        java.util.Map<java.lang.String, java.lang.Object> noId=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf("status", "RUNNING"));assertEquals(false,dimension(noId,"execution").get("active"));
    }
    @Test void durableRecoveryJobHasIndependentLifecycleAndCannotPromoteConclusion(){
        for(String state:cn.bidcheck.compat.Java8Api.listOf("QUEUED", "RUNNING", "RETRY_WAIT", "BLOCKED_NO_PROGRESS", "WAITING_EXTERNAL", "WAITING_USER", "FAILED", "SUCCEEDED")){
            java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf("id", "job-1", "status", state, "stopReason", "输入未改变"));
            assertEquals(cn.bidcheck.compat.Java8Api.setOf("QUEUED", "RUNNING", "RETRY_WAIT").contains(state),dimension(p,"execution").get("active"));assertEquals("UNDETERMINED",dimension(p,"conclusion").get("state"));assertEquals(false,dimension(p,"conclusion").get("determined"));
        }
    }
    @Test void warningIsSuspectedRiskAndNotAProvenBusinessFailure(){
        java.util.Map<java.lang.String, java.lang.Object> warning=BusinessResultPresentation.describe(evaluation("WARNING"),cn.bidcheck.compat.Java8Api.mapOf());assertEquals("SUSPECTED_RISK",dimension(warning,"conclusion").get("state"));assertEquals(false,dimension(warning,"conclusion").get("determined"));
        java.util.Map<java.lang.String, java.lang.Object> fail=BusinessResultPresentation.describe(evaluation("FAIL"),cn.bidcheck.compat.Java8Api.mapOf());assertEquals("NOT_SATISFIED",dimension(fail,"conclusion").get("state"));assertEquals(true,dimension(fail,"conclusion").get("determined"));
    }
    @Test void allBodiesLocatedDoNotBecomeWholeRequirementEvidenceSufficient(){
        java.util.Map<java.lang.String, java.lang.Object> a=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("completeness", cn.bidcheck.compat.Java8Api.mapOf("inventoryResolved", true, "complete", true, "proofScope", "BODY_PRESENCE_ONLY", "itemResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "VERIFIED")))));
        java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),a);
        assertEquals("PARTIAL",dimension(p,"evidence").get("state"));assertEquals("UNDETERMINED",dimension(p,"conclusion").get("state"));
    }
    @Test void exportsExposeExecutionEvidenceAndConclusionWithoutChangingFinalDecision(){
        java.util.Map<java.lang.String, java.lang.Object> a=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("resolutionJob", cn.bidcheck.compat.Java8Api.mapOf("id", "job", "status", "BLOCKED_NO_PROGRESS", "stopReason", "无新增<证据>"));
        cn.bidcheck.domain.Domain.Finding f=new cn.bidcheck.domain.Domain.Finding("f","r",null,evaluation("WARNING"),a);
        String html=ReportService.resolutionDimensionsHtml(f),summary=ReportService.resolutionSummaryHtml(cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf("parentRunId", "old-run"),cn.bidcheck.compat.Java8Api.listOf(f));
        assertTrue(html.contains("补查已停止：无新进展"));assertTrue(html.contains("疑似风险，待确认"));assertTrue(html.contains("无新增&lt;证据&gt;"));assertTrue(summary.contains("仍有 1 条尚未形成确定结论"));assertTrue(summary.contains("old-run"));assertEquals("WARNING",f.evaluation().status());
    }
    @Test void unsupportedRuleIsSystemGapWhileExternalInputIsNotRunning(){
        java.util.Map<java.lang.String, java.lang.Object> unsupported=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("model", cn.bidcheck.compat.Java8Api.mapOf("decision", "RULE_UNSUPPORTED")));
        assertEquals("SYSTEM_INCOMPLETE",unsupported.get("state"));assertEquals(false,unsupported.get("userActionRequired"));
        java.util.Map<java.lang.String, java.lang.Object> external=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("model", cn.bidcheck.compat.Java8Api.mapOf("decision", "EXTERNAL_INPUT_REQUIRED"), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true)));
        assertEquals("EVIDENCE_PENDING",external.get("state"));assertEquals(false,dimension(external,"execution").get("active"));
    }
    @Test void quoteUniquenessScopeIsUnfinishedSystemWorkRatherThanMissingUserMaterial(){
        java.util.Map<java.lang.String, java.lang.Object> atomic=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "UNIQUE_PRICE_SCOPE_REQUIRED", "description", "局部一致，报价范围尚未核全")));
        java.util.Map<java.lang.String, java.lang.Object> assessment=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("atomicCheck", atomic, "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true));
        java.util.Map<java.lang.String, java.lang.Object> result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);
        assertEquals("SYSTEM_INCOMPLETE",result.get("state"));assertEquals("SYSTEM",result.get("owner"));
        assertEquals(false,result.get("userActionRequired"));assertTrue(result.get("nextStep").toString().contains("备选方案"));
        assertFalse(dimension(result,"conclusion").get("determined").equals(true));
        java.util.Map<java.lang.String, java.lang.Object> stopped=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment,cn.bidcheck.compat.Java8Api.mapOf("id", "j", "status", "BLOCKED_NO_PROGRESS"));
        assertTrue(stopped.get("nextStep").toString().contains("已停止"));assertEquals(false,dimension(stopped,"execution").get("active"));
        assertEquals("RISK",BusinessResultPresentation.describe(evaluation("FAIL"),assessment).get("state"));
        assertEquals("报价范围尚未核全",((Map<?,?>)((List<?>)FocusedCheckEvaluator.presentation(atomic).get("issues")).get(0)).get("label"));
    }
    @Test void permittedDurationResponseIsDisplayedAsUndertakingNotObservedNumber(){
        java.util.Map<java.lang.String, java.io.Serializable> evidence=cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "page", 7, "quote", "承诺工期为满足");
        java.util.Map<java.lang.String, java.lang.Object> condition=cn.bidcheck.compat.Java8Api.mapOf("predicate", "NUMBER", "conditionRole", "REQUIREMENT", "status", "PASS", "evaluated", true, "proof", cn.bidcheck.compat.Java8Api.mapOf("proofScope", "DURATION_UNDERTAKING_ONLY", "numericObservationSynthesized", false), "evidences", cn.bidcheck.compat.Java8Api.listOf(evidence), "gaps", cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> assessment=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("complete", true, "gaps", cn.bidcheck.compat.Java8Api.listOf(), "conditions", cn.bidcheck.compat.Java8Api.listOf(condition)));
        java.util.Map<java.lang.String, java.lang.Object> actual=BusinessResultPresentation.describe(evaluation("PASS"),assessment);
        assertEquals("SATISFIED",actual.get("state"));assertEquals("工期承诺已响应",actual.get("label"));assertTrue(actual.get("nextStep").toString().contains("未将“满足”转写"));
        assertEquals("DURATION_UNDERTAKING_ONLY",((Map<?,?>)((Map<?,?>)actual.get("dimensions")).get("proofScope")).get("state"));
    }
    @Test void unresolvedFormAndRequirementBindingsRemainSystemOwned(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("FORM_TEMPLATE_REFERENCE_REQUIRED", "FORM_INSTANCE_AMBIGUOUS", "FORM_CONTENT_COVERAGE_REQUIRED", "FORM_CONDITION_COVERAGE_REQUIRED", "DURATION_REQUIREMENT_SOURCE_REQUIRED", "DURATION_REQUIREMENT_FIELD_UNBOUND")){
            java.util.Map<java.lang.String, java.lang.Object> actual=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", code)))));
            assertEquals("SYSTEM_INCOMPLETE",actual.get("state"),code);assertEquals(false,actual.get("userActionRequired"),code);
        }
    }
    @Test void previousHumanReviewIsClearlyHistoricalAndScopedToOriginalFinding(){
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("resultId", "parent-f", "status", "PASS", "note", "原批次依据", "reviewer", "审核人", "runId", "parent-run"));
        assertEquals("",ReportService.inheritedReviewText(rows,"other-f"));assertEquals("",ReportService.inheritedReviewText(rows,""));
        assertTrue(ReportService.inheritedReviewText(rows,"parent-f").contains("未自动套用"));assertTrue(ReportService.inheritedReviewText(rows,null).contains("parent-run"));
    }
    @Test void scopedLetterAndConditionalSubmissionKeepTheirLimitedMeaning(){
        for(java.util.Map.Entry<java.lang.String, java.lang.String> entry:cn.bidcheck.compat.Java8Api.mapOf("QUALITY_UNDERTAKING_ONLY", "质量承诺已响应", "BID_VALIDITY_UNDERTAKING_ONLY", "投标有效期已响应", "CONDITIONAL_MATERIAL_SUBMISSION_ONLY", "材料提交要求已满足").entrySet()){
            java.util.Map<java.lang.String, java.lang.Object> condition=cn.bidcheck.compat.Java8Api.mapOf("predicate", "DECLARATION", "conditionRole", "REQUIREMENT", "status", "PASS", "evaluated", true, "proof", cn.bidcheck.compat.Java8Api.mapOf("proofScope", entry.getKey()), "evidences", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 7)), "gaps", cn.bidcheck.compat.Java8Api.listOf());
            java.util.Map<java.lang.String, java.lang.Object> assessment=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("complete", true, "gaps", cn.bidcheck.compat.Java8Api.listOf(), "conditions", cn.bidcheck.compat.Java8Api.listOf(condition)));
            java.util.Map<java.lang.String, java.lang.Object> result=BusinessResultPresentation.describe(evaluation("PASS"),assessment);
            assertEquals("SATISFIED",result.get("state"));assertEquals(entry.getValue(),result.get("label"));
        }
    }
    @Test void completedStructuralScanShowsReviewRatherThanMissingMaterialsButCannotHideFailures(){
        java.util.Map<java.lang.String, java.lang.String> reviewGap=cn.bidcheck.compat.Java8Api.mapOf("code", "QUALITY_GRADE_REVIEW_REQUIRED");
        java.util.Map<java.lang.String, java.lang.Object> condition=cn.bidcheck.compat.Java8Api.mapOf("predicate", "QUALITY_ADVISORY", "conditionRole", "REQUIREMENT", "status", "UNDETERMINED", "evaluated", true, "proof", cn.bidcheck.compat.Java8Api.mapOf("decisionBasis", "PROGRAM_STRUCTURAL_COVERAGE", "coverageComplete", true), "evidences", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 50)), "gaps", cn.bidcheck.compat.Java8Api.listOf(reviewGap));
        java.util.Map<java.lang.String, java.lang.Object> atomic=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("complete", false, "conditions", cn.bidcheck.compat.Java8Api.listOf(condition), "gaps", cn.bidcheck.compat.Java8Api.listOf(reviewGap));
        java.util.Map<java.lang.String, java.lang.Object> result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
        assertEquals("USER_ACTION",result.get("state"));assertEquals("评分档次待评审",result.get("label"));assertTrue(result.get("nextStep").toString().contains("不代表达到最高档"));
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> failed=new LinkedHashMap<>(atomic);failed.put("gaps",cn.bidcheck.compat.Java8Api.listOf(reviewGap, cn.bidcheck.compat.Java8Api.mapOf("code", "MODEL_TIMEOUT")));
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", failed)).get("state"));
        java.util.Map<java.lang.String, java.lang.Object> unverified=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("gaps", cn.bidcheck.compat.Java8Api.listOf(reviewGap));
        assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", unverified)).get("state"));
    }
}

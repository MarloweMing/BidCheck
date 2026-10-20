package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Evaluation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessResultPresentationTest {
    @Test void verifiedSemanticWrittenProofHasSameNarrowScopeAsAtomicDeclaration(){
        var evidence=new cn.bidcheck.domain.Domain.Evidence("bid","投标文件",1,"PAGE","我方承诺遵守相关管理规定。","p1",1);
        var evaluation=new Evaluation("PASS","承诺文字已提供","",List.of(evidence),0,"SEMANTIC");
        var proof=Map.<String,Object>of("validated",true,"scope","WRITTEN_RESPONSE","evidenceIds",List.of("C1"));
        var assessment=Map.<String,Object>of("coverage",Map.of("semanticProof",proof));
        var p=BusinessResultPresentation.describe(evaluation,assessment);assertEquals("书面要求已满足",p.get("label"));assertEquals("WRITTEN_RESPONSE",dimension(p,"proofScope").get("state"));assertTrue(p.get("nextStep").toString().contains("不等于实际资格状态"));
        assertEquals("UNSPECIFIED",dimension(BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment),"proofScope").get("state"));
        assertEquals("UNSPECIFIED",dimension(BusinessResultPresentation.describe(evaluation("PASS"),assessment),"proofScope").get("state"));
    }
    @Test void stoppedRecoveryDoesNotPromiseContinuingBackgroundWorkOrAskForMaterials(){
        for(String status:List.of("BLOCKED_NO_PROGRESS","FAILED")){
            var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of(),Map.of("id","job","status",status));
            assertTrue(p.get("nextStep").toString().contains("已停止"));assertFalse((boolean)p.get("userActionRequired"));assertEquals(false,dimension(p,"execution").get("active"));
        }
    }
    @Test void validatedGapSeparatesSystemRetrievalAndExternalRecordButFailureStillWins(){
        for(String kind:List.of("SYSTEM_REQUIREMENT","SYSTEM_RETRIEVAL","SYSTEM_RELATION","EXTERNAL_INPUT")){
            var a=Map.<String,Object>of("coverage",Map.of("semanticGap",Map.of("validated",true,"kind",kind)));
            var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),a);assertEquals(kind.equals("EXTERNAL_INPUT")?"USER_ACTION":"SYSTEM_INCOMPLETE",p.get("state"));
            var failed=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),a,Map.of("id","job","status","FAILED"));assertEquals("SYSTEM_INCOMPLETE",failed.get("state"));
        }
    }
    @Test void observedOccurrenceScopeDoesNotBecomeCertificateOrFullDocumentPass(){
        var d=new LinkedHashMap<String,Object>(Map.of("status","PASS","proofScope","OBSERVED_OCCURRENCES","comparisonCoverageComplete",true,"documentCoverageComplete",false,"originalOccurrenceIds",List.of("o1","o2"),"unresolvedOccurrenceIds",List.of()));
        var p=BusinessResultPresentation.describe(evaluation("PASS"),Map.of("derivedConclusion",d));assertEquals("已识别记录一致",p.get("label"));assertEquals("OBSERVED_OCCURRENCES",dimension(p,"proofScope").get("state"));
        d.put("unresolvedOccurrenceIds",List.of("o2"));assertEquals("UNSPECIFIED",dimension(BusinessResultPresentation.describe(evaluation("PASS"),Map.of("derivedConclusion",d)),"proofScope").get("state"));
    }
    @SuppressWarnings("unchecked") Map<String,Object> dimension(Map<String,Object> p,String name){return (Map<String,Object>)((Map<?,?>)p.get("dimensions")).get(name);}
    Evaluation evaluation(String status){return new Evaluation(status,"最终依据说明","原始建议",List.of(),0,"FOCUSED_ATOMIC_RULES");}
    Map<String,Object> provenCondition(String predicate){return Map.of("predicate",predicate,"conditionRole","REQUIREMENT","status","PASS","evaluated",true,"proof",Map.of("sourceBound",true),"evidences",List.of(Map.of("documentId","d","page",1,"quote","对应原文")),"gaps",List.of());}
    @Test void writtenResponseIsNotPresentedAsExternalFactVerification(){
        var p=BusinessResultPresentation.describe(evaluation("PASS"),Map.of("atomicCheck",Map.of("conditions",List.of(provenCondition("DECLARATION"),provenCondition("MATERIAL")))));
        assertEquals("WRITTEN_RESPONSE",dimension(p,"proofScope").get("state"));assertEquals("书面要求已满足",dimension(p,"conclusion").get("label"));assertEquals("SATISFIED",p.get("state"));
    }
    @Test void factualConditionsHaveDistinctScopeAndMixedOrIncompleteStructureDoesNotGuess(){
        var factual=BusinessResultPresentation.describe(evaluation("PASS"),Map.of("atomicCheck",Map.of("conditions",List.of(provenCondition("NUMBER"),provenCondition("VALIDITY")))));
        assertEquals("BUSINESS_CONDITIONS",dimension(factual,"proofScope").get("state"));assertEquals("业务条件已验证",dimension(factual,"conclusion").get("label"));
        for(List<Map<String,Object>> rows:List.of(List.of(provenCondition("NUMBER"),provenCondition("DECLARATION")),List.of(Map.<String,Object>of("conditionRole","REQUIREMENT","predicate","NUMBER","status","PASS")),List.of(Map.<String,Object>of("conditionRole","AUXILIARY","predicate","REFERENCE_TIME","status","PASS")))){
            var p=BusinessResultPresentation.describe(evaluation("PASS"),Map.of("atomicCheck",Map.of("conditions",rows)));assertEquals("UNSPECIFIED",dimension(p,"proofScope").get("state"));assertEquals("满足要求",dimension(p,"conclusion").get("label"));
        }
    }
    @Test void actualFailedVisualJobCannotBeHiddenBehindStageOrExternalUserAction(){
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("atomicCheck",Map.of("workflowState","WAIT_STAGE","externalRequests",List.of(Map.of("kind","PLATFORM")))),Map.of("id","job","status","FAILED","diagnostics",List.of(Map.of("code","VISUAL_ENGINE_FAILURE"))));
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals(false,p.get("userActionRequired"));assertEquals("FAILED",dimension(p,"execution").get("state"));assertEquals(false,dimension(p,"execution").get("active"));
    }
    @Test void failedFieldIsSystemWorkButReadNotFoundRemainsEvidencePending(){for(var state:List.of("UNREAD","FAILED","READ_NOT_FOUND","EXTRACTED")){
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("atomicCheck",Map.of("fieldCoverage",List.of(Map.of("status",state)))));assertEquals(Set.of("UNREAD","FAILED").contains(state)?"SYSTEM_INCOMPLETE":"EVIDENCE_PENDING",p.get("state"));assertEquals(false,p.get("userActionRequired"));
    }}
    @Test void finalGuardOutranksInternalCompletedAndPreservesOriginalProof(){
        Map<String,Object> raw=Map.of("atomicCheck",Map.of("businessDecision","PASS","workflowState","COMPLETED","complete",true,"conditions",List.of(Map.of("predicate","REFERENCE_TIME","status","PASS"))));
        var result=FocusedCheckEvaluator.reconcileAssessment(evaluation("UNDETERMINED"),raw);
        var atomic=(Map<?,?>)result.get("atomicCheck");var display=(Map<?,?>)result.get("businessPresentation");
        assertEquals("PASS",atomic.get("businessDecision"));assertEquals("UNDETERMINED",atomic.get("finalBusinessStatus"));
        assertEquals("SYSTEM_INCOMPLETE",display.get("state"));assertEquals(false,display.get("userActionRequired"));
        assertFalse(((Map<?,?>)atomic.get("presentation")).get("state").equals("COMPLETED"));
        assertFalse(((Map<?,?>)raw.get("atomicCheck")).containsKey("finalBusinessStatus"));
        String html=ReportService.atomicCheckHtml((Map<String,Object>)atomic);
        assertFalse(html.contains("条件核验已完成"));assertTrue(html.contains("参考时点已确认"));
    }
    @Test void modelFailureIsSystemWorkEvenWhenVisualReviewAlsoExists(){
        var assessment=Map.<String,Object>of("adjustments",List.of(Map.of("code","MODEL_TIMEOUT")),"ocrReview",Map.of("required",true));
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals("SYSTEM",p.get("owner"));assertEquals(false,p.get("userActionRequired"));
    }
    @Test void evidenceNotFoundIsNotAnInstructionToSubmitMissingMaterials(){
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("atomicCheck",Map.of("gaps",List.of(Map.of("code","CERTIFICATE_EVIDENCE_REQUIRED")))));
        assertEquals("EVIDENCE_PENDING",p.get("state"));assertEquals(false,p.get("userActionRequired"));assertTrue(p.get("nextStep").toString().contains("只有确认具体缺口"));
    }
    @Test void comparisonMappingAndRegistrationBindingsAreSystemWorkWithoutChangingOutcome(){
        for(String code:List.of("COMPARISON_FIELD_MAPPING_REQUIRED","COMPARISON_RECORD_IDENTITY_UNPROVEN","REGISTRATION_EMPLOYER_BINDING_REQUIRED")){
            var assessment=Map.<String,Object>of("atomicCheck",Map.of("gaps",List.of(Map.of("code",code,"description","证书单位与任职单位尚未对应"))),"ocrReview",Map.of("required",true));String before=assessment.toString();
            var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals("SYSTEM",p.get("owner"));assertEquals(false,p.get("userActionRequired"));assertEquals("UNDETERMINED",p.get("finalStatus"));assertEquals("证书单位与任职单位尚未对应",p.get("reason"));assertTrue(p.get("nextStep").toString().contains("系统需继续"));assertEquals(before,assessment.toString());
            assertEquals("SATISFIED",BusinessResultPresentation.describe(evaluation("PASS"),assessment).get("state"));
            var stopped=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment,Map.of("id","job","status","BLOCKED_NO_PROGRESS"));assertTrue(stopped.get("nextStep").toString().contains("已停止"));
        }
        var missing=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("atomicCheck",Map.of("gaps",List.of(Map.of("code","REGISTRATION_CERTIFICATE_REQUIRED"))),"ocrReview",Map.of("required",true)));assertEquals("EVIDENCE_PENDING",missing.get("state"));assertEquals(false,missing.get("userActionRequired"));
    }
    @Test void explicitExternalActionIsSeparatedFromSystemProcessing(){
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("atomicCheck",Map.of("externalRequests",List.of(Map.of("kind","PLATFORM")))));
        assertEquals("USER_ACTION",p.get("state"));assertEquals(true,p.get("userActionRequired"));
    }
    @Test void successfulPublishedResultKeepsSeparateVisualTaskAndRecoveredFailure(){
        var p=BusinessResultPresentation.describe(evaluation("PASS"),Map.of("ocrReview",Map.of("required",true),"adjustments",List.of(Map.of("code","MODEL_TIMEOUT"))));
        assertEquals("SATISFIED",p.get("state"));assertEquals(true,p.get("userActionRequired"));
    }
    @Test void warningIsRiskWithoutChangingAutomaticStatus(){
        var p=BusinessResultPresentation.describe(evaluation("WARNING"),Map.of());assertEquals("RISK",p.get("state"));assertEquals("WARNING",p.get("finalStatus"));
    }
    @Test void completenessSeparatesUnparsedInventoryFromMissingBodyEvidence(){
        var unresolved=Map.<String,Object>of("guards",List.of(Map.of("code","REQUIREMENT_COMPLETENESS_UNPROVEN")),"coverage",Map.of("completeness",Map.of("inventoryResolved",false)));
        var resolved=Map.<String,Object>of("guards",List.of(Map.of("code","REQUIREMENT_COMPLETENESS_UNPROVEN")),"coverage",Map.of("completeness",Map.of("inventoryResolved",true,"coreRequirementDeferred",false)));
        assertEquals("SYSTEM_INCOMPLETE",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),unresolved).get("state"));
        var result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),resolved);assertEquals("EVIDENCE_PENDING",result.get("state"));assertEquals(false,result.get("userActionRequired"));
    }
    @Test void genericOcrNoteDoesNotAssignPrimaryEvidenceWorkToUser(){
        for(Object before:List.of(evaluation("UNDETERMINED"),Map.of("status","UNDETERMINED"))){
            var result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("preOcrEvaluation",before,"ocrReview",Map.of("required",true)));
            assertEquals("EVIDENCE_PENDING",result.get("state"));assertEquals(false,result.get("userActionRequired"));
        }
        var relation=Map.<String,Object>of("atomicCheck",Map.of("gaps",List.of(Map.of("code","SUBJECT_RELATION_UNPROVEN"))),"ocrReview",Map.of("required",true));
        assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),relation).get("state"));
    }
    @Test void checklistExecutionOrApplicabilityGapIsNotMissingUserMaterial(){
        var batch=Map.<String,Object>of("guards",List.of(Map.of("code","CHECKLIST_BATCH_REQUIRED")));
        var condition=Map.<String,Object>of("guards",List.of(Map.of("code","REQUIREMENT_COMPLETENESS_UNPROVEN")),"coverage",Map.of("completeness",Map.of("inventoryResolved",true,"itemResults",List.of(Map.of("status","REQUIREMENT_UNRESOLVED")))));
        for(var assessment:List.of(batch,condition)){
            var result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);
            assertEquals("SYSTEM_INCOMPLETE",result.get("state"));assertEquals(false,result.get("userActionRequired"));
        }
    }
    @Test void explicitUserActionAndVisualOnlyDowngradeRemainVisible(){
        var external=Map.<String,Object>of("preOcrEvaluation",evaluation("UNDETERMINED"),"atomicCheck",Map.of("externalRequests",List.of(Map.of("kind","PLATFORM"))),"ocrReview",Map.of("required",true));
        assertEquals("USER_ACTION",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),external).get("state"));
        var visual=Map.<String,Object>of("preOcrEvaluation",evaluation("PASS"),"ocrReview",Map.of("required",true));
        assertEquals("USER_ACTION",BusinessResultPresentation.describe(evaluation("UNDETERMINED"),visual).get("state"));
    }
    @Test void systemOwnedOcrSourceIsCollapsedAndNotAnActionableWarningInHtml(){
        for(var extra:List.of(Map.<String,Object>of("preOcrEvaluation",evaluation("UNDETERMINED")),Map.<String,Object>of("adjustments",List.of(Map.of("code","MODEL_TIMEOUT"))))){
            var assessment=new LinkedHashMap<String,Object>(extra);assessment.put("ocrReview",Map.of("required",true,"reason","历史识别说明<保留>","locations",List.of(Map.of("label","投标文件第 26 页"))));
            var finding=new cn.bidcheck.domain.Domain.Finding("f","r",null,evaluation("UNDETERMINED"),assessment);
            String rendered=ReportService.originalReviewHtml(finding,Map.of());
            assertTrue(rendered.startsWith("<details class=recognition-source-record>"));assertTrue(rendered.contains("识别来源记录"));assertTrue(rendered.contains("投标文件第 26 页"));assertTrue(rendered.contains("历史识别说明&lt;保留&gt;"));assertFalse(rendered.contains("原图复核提示"));assertFalse(rendered.contains("<details open"));assertTrue(Boolean.TRUE.equals(((Map<?,?>)assessment.get("ocrReview")).get("required")));
        }
    }
    @Test void passedAndRiskFindingsKeepActionableOriginalImageWarning(){
        for(String status:List.of("PASS","WARNING")){
            var finding=new cn.bidcheck.domain.Domain.Finding("f","r",null,evaluation(status),Map.of("ocrReview",Map.of("required",true,"reason","请核对所列原图")));
            String rendered=ReportService.originalReviewHtml(finding,Map.of());assertTrue(rendered.contains("class=ocr-review-note"));assertTrue(rendered.contains("原图复核提示"));assertFalse(rendered.contains("recognition-source-record"));
        }
    }
    @Test void systemOwnershipDoesNotPretendRecoveryIsRunning(){
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("adjustments",List.of(Map.of("code","MODEL_TIMEOUT"))));
        assertEquals("SYSTEM",p.get("owner"));assertEquals("FINISHED",dimension(p,"execution").get("state"));assertEquals(false,dimension(p,"execution").get("active"));assertEquals(false,dimension(p,"conclusion").get("determined"));
        var noId=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of(),Map.of("status","RUNNING"));assertEquals(false,dimension(noId,"execution").get("active"));
    }
    @Test void durableRecoveryJobHasIndependentLifecycleAndCannotPromoteConclusion(){
        for(String state:List.of("QUEUED","RUNNING","RETRY_WAIT","BLOCKED_NO_PROGRESS","WAITING_EXTERNAL","WAITING_USER","FAILED","SUCCEEDED")){
            var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of(),Map.of("id","job-1","status",state,"stopReason","输入未改变"));
            assertEquals(Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(state),dimension(p,"execution").get("active"));assertEquals("UNDETERMINED",dimension(p,"conclusion").get("state"));assertEquals(false,dimension(p,"conclusion").get("determined"));
        }
    }
    @Test void warningIsSuspectedRiskAndNotAProvenBusinessFailure(){
        var warning=BusinessResultPresentation.describe(evaluation("WARNING"),Map.of());assertEquals("SUSPECTED_RISK",dimension(warning,"conclusion").get("state"));assertEquals(false,dimension(warning,"conclusion").get("determined"));
        var fail=BusinessResultPresentation.describe(evaluation("FAIL"),Map.of());assertEquals("NOT_SATISFIED",dimension(fail,"conclusion").get("state"));assertEquals(true,dimension(fail,"conclusion").get("determined"));
    }
    @Test void allBodiesLocatedDoNotBecomeWholeRequirementEvidenceSufficient(){
        var a=Map.<String,Object>of("coverage",Map.of("completeness",Map.of("inventoryResolved",true,"complete",true,"proofScope","BODY_PRESENCE_ONLY","itemResults",List.of(Map.of("status","VERIFIED")))));
        var p=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),a);
        assertEquals("PARTIAL",dimension(p,"evidence").get("state"));assertEquals("UNDETERMINED",dimension(p,"conclusion").get("state"));
    }
    @Test void exportsExposeExecutionEvidenceAndConclusionWithoutChangingFinalDecision(){
        var a=Map.<String,Object>of("resolutionJob",Map.of("id","job","status","BLOCKED_NO_PROGRESS","stopReason","无新增<证据>"));
        var f=new cn.bidcheck.domain.Domain.Finding("f","r",null,evaluation("WARNING"),a);
        String html=ReportService.resolutionDimensionsHtml(f),summary=ReportService.resolutionSummaryHtml(Map.of(),Map.of("parentRunId","old-run"),List.of(f));
        assertTrue(html.contains("补查已停止：无新进展"));assertTrue(html.contains("疑似风险，待确认"));assertTrue(html.contains("无新增&lt;证据&gt;"));assertTrue(summary.contains("仍有 1 条尚未形成确定结论"));assertTrue(summary.contains("old-run"));assertEquals("WARNING",f.evaluation().status());
    }
    @Test void unsupportedRuleIsSystemGapWhileExternalInputIsNotRunning(){
        var unsupported=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("model",Map.of("decision","RULE_UNSUPPORTED")));
        assertEquals("SYSTEM_INCOMPLETE",unsupported.get("state"));assertEquals(false,unsupported.get("userActionRequired"));
        var external=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),Map.of("model",Map.of("decision","EXTERNAL_INPUT_REQUIRED"),"ocrReview",Map.of("required",true)));
        assertEquals("EVIDENCE_PENDING",external.get("state"));assertEquals(false,dimension(external,"execution").get("active"));
    }
    @Test void quoteUniquenessScopeIsUnfinishedSystemWorkRatherThanMissingUserMaterial(){
        var atomic=Map.<String,Object>of("gaps",List.of(Map.of("code","UNIQUE_PRICE_SCOPE_REQUIRED","description","局部一致，报价范围尚未核全")));
        var assessment=Map.<String,Object>of("atomicCheck",atomic,"ocrReview",Map.of("required",true));
        var result=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment);
        assertEquals("SYSTEM_INCOMPLETE",result.get("state"));assertEquals("SYSTEM",result.get("owner"));
        assertEquals(false,result.get("userActionRequired"));assertTrue(result.get("nextStep").toString().contains("备选方案"));
        assertFalse(dimension(result,"conclusion").get("determined").equals(true));
        var stopped=BusinessResultPresentation.describe(evaluation("UNDETERMINED"),assessment,Map.of("id","j","status","BLOCKED_NO_PROGRESS"));
        assertTrue(stopped.get("nextStep").toString().contains("已停止"));assertEquals(false,dimension(stopped,"execution").get("active"));
        assertEquals("RISK",BusinessResultPresentation.describe(evaluation("FAIL"),assessment).get("state"));
        assertEquals("报价范围尚未核全",((Map<?,?>)((List<?>)FocusedCheckEvaluator.presentation(atomic).get("issues")).get(0)).get("label"));
    }
    @Test void previousHumanReviewIsClearlyHistoricalAndScopedToOriginalFinding(){
        var rows=List.of(Map.<String,Object>of("resultId","parent-f","status","PASS","note","原批次依据","reviewer","审核人","runId","parent-run"));
        assertEquals("",ReportService.inheritedReviewText(rows,"other-f"));assertEquals("",ReportService.inheritedReviewText(rows,""));
        assertTrue(ReportService.inheritedReviewText(rows,"parent-f").contains("未自动套用"));assertTrue(ReportService.inheritedReviewText(rows,null).contains("parent-run"));
    }
}

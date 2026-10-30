package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrustedVisualReviewTest {
    Map<String,Object> observation(String outcome){return new LinkedHashMap<>(Map.ofEntries(
        Map.entry("observationId","v1"),Map.entry("obligationId","FORM-1"),Map.entry("sourceBindingVerified",true),Map.entry("coverage","CURRENT_FULL_PAGE"),
        Map.entry("documentId","bid"),Map.entry("documentVersion",2),Map.entry("documentHash","doc-hash"),Map.entry("imageHash","image-hash"),
        Map.entry("location",276),Map.entry("type","SIGNATURE"),Map.entry("subjects",List.of("张三")),Map.entry("outcome",outcome),Map.entry("requestedSourceIds",List.of("C276"))));}
    Evidence evidence(){return new Evidence("bid","bid.pdf",276,"PAGE","[原图已核验：签字位置]","image:image-hash",0);}
    Map<String,Object> assessment(String outcome){
        boolean missing=outcome.equals("NOT_OBSERVED");
        Map<String,Object> proof=new LinkedHashMap<>(Map.of("decisionBasis","VISUAL_OBSERVATION","observationOutcome",missing?"NOT_OBSERVED":"UNREADABLE_OR_PARTIAL_REVIEW",
            "automaticRejection",false,"authenticityAssessed",false,"observations",List.of(observation(outcome))));
        if(missing)proof.put("missingVisibleSubjects",List.of("张三"));
        Map<String,Object> condition=new LinkedHashMap<>(Map.of("id","FORM-1","status",missing?"FAIL":"UNDETERMINED","conditionRole","REQUIREMENT","evaluated",true,
            "predicate","VISUAL","proof",proof,"evidences",List.of(evidence())));
        Map<String,Object> atomic=new LinkedHashMap<>(Map.of("conditions",List.of(condition),"gaps",missing?List.of():List.of(Map.of("code","VISUAL_MARK_UNREADABLE")),
            "documentIdentities",List.of(Map.of("id","bid","version",2,"sha256","doc-hash"))));
        return new LinkedHashMap<>(Map.of("atomicCheck",atomic,"preOcrEvaluation",Map.of("status","UNDETERMINED"),"ocrReview",Map.of("required",true)));
    }
    Finding finding(String status,Map<String,Object> a){return new Finding("f","r",null,new Evaluation(status,"原图检查结果","",List.of(evidence()),0,"RULE"),a);}
    Map<String,Object> atomic(Map<String,Object> a){return (Map<String,Object>)a.get("atomicCheck");}
    Map<String,Object> condition(Map<String,Object> a){return (Map<String,Object>)((List<?>)atomic(a).get("conditions")).get(0);}
    Map<String,Object> observed(Map<String,Object> a){return (Map<String,Object>)((List<?>)((Map<?,?>)condition(a).get("proof")).get("observations")).get(0);}
    @Test void checkedUnreadableMarkWaitsForUserWithoutRepeatingIdenticalPage(){
        var a=assessment("UNREADABLE");atomic(a).put("visualRequests",List.of(Map.of("obligationId","FORM-1","documentId","bid","location",276,"locationType","PAGE","task","SIGNATURE_VISIBILITY","fields",List.of("张三"),"sourceIds",List.of("C276"))));
        Finding f=finding("UNDETERMINED",a);var presentation=BusinessResultPresentation.describe(f.evaluation(),a);
        assertEquals("USER_ACTION",presentation.get("state"));assertTrue(presentation.get("nextStep").toString().contains("原图已检查"));
        assertEquals("WAITING_USER",CheckResolution.plan(f).get("status"));assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));
    }
    @Test void realNegativeObservationIsRiskWithOriginalPageRatherThanUnexaminedWarning(){
        var a=assessment("NOT_OBSERVED");Finding f=finding("FAIL",a);var p=BusinessResultPresentation.describe(f.evaluation(),a);
        assertEquals("RISK",p.get("state"));assertTrue(p.get("nextStep").toString().contains("不自动作出否决"));assertEquals("SUCCEEDED",CheckResolution.plan(f).get("status"));
        String html=ReportService.originalReviewHtml(f,Map.of());assertTrue(html.contains("原图核验记录"));assertTrue(html.contains("276"));assertFalse(html.contains("原图复核提示"));
    }
    @Test void wrongPageOldVersionWrongHashWrongObligationAndUnvalidatedReceiptsCannotAssignUserWork(){
        for(var mutation:List.<Map<String,Object>>of(Map.of("location",275),Map.of("documentVersion",1),Map.of("documentHash","old"),Map.of("imageHash","other"),Map.of("obligationId","OTHER"),Map.of("sourceBindingVerified",false),Map.of("coverage","CROP"),Map.of("requestedSourceIds",List.of()))){
            var a=assessment("UNREADABLE");observed(a).putAll(mutation);var f=finding("UNDETERMINED",a);
            assertFalse(TrustedVisualReview.inspect(a).reviewed(),mutation.toString());assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(f.evaluation(),a).get("state"));assertNotEquals("WAITING_USER",CheckResolution.plan(f).get("status"));
        }
    }
    @Test void legacyGapWithoutDocumentSnapshotCannotBeUpgradedToCompletedImageReview(){
        var a=assessment("UNREADABLE");atomic(a).remove("documentIdentities");assertFalse(TrustedVisualReview.inspect(a).reviewed());
        assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(finding("UNDETERMINED",a).evaluation(),a).get("state"));
    }
    @Test void technicalFailuresOutrankReviewedUnreadableMark(){
        var a=assessment("UNREADABLE");atomic(a).put("modelDiagnostics",Map.of("unresolvedFailures",1,"failures",List.of(Map.of("errorCode","MODEL_TIMEOUT"))));
        Finding f=finding("UNDETERMINED",a);var p=BusinessResultPresentation.describe(f.evaluation(),a);
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals(false,p.get("userActionRequired"));assertEquals("RETRY_FAILED_STEP",CheckResolution.plan(f).get("strategy"));
    }
    @Test void unsupportedClauseOrSubjectRelationCannotBeDelegatedToUserBecauseOneMarkIsUnreadable(){
        for(String code:List.of("REQUIREMENT_UNRESOLVED","SUBJECT_RELATION_UNPROVEN","REGISTRATION_EMPLOYER_BINDING_REQUIRED")){
            var a=assessment("UNREADABLE");atomic(a).put("gaps",List.of(Map.of("code","VISUAL_MARK_UNREADABLE"),Map.of("code",code)));
            assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
            assertFalse((boolean)BusinessResultPresentation.describe(finding("UNDETERMINED",a).evaluation(),a).get("userActionRequired"));
        }
    }
    @Test void unresolvedOtherLeafAndUnstartedFieldCannotBecomeImageConfirmationOnly(){
        var a=assessment("UNREADABLE");var good=condition(a);atomic(a).put("conditions",List.of(good,Map.of("id","OTHER","status","UNDETERMINED","evaluated",true,"conditionRole","REQUIREMENT")));
        assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
        a=assessment("UNREADABLE");atomic(a).put("fieldCoverage",List.of(Map.of("status","UNREAD")));assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
    }
    @Test void anotherUnexaminedPageStillRequiresSourceWork(){
        var a=assessment("UNREADABLE");atomic(a).put("visualRequests",List.of(Map.of("obligationId","FORM-1","documentId","bid","location",277,"locationType","PAGE","task","SIGNATURE_VISIBILITY","fields",List.of("张三"),"sourceIds",List.of("C277"))));
        assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertEquals("VERIFY_SOURCE_PAGES",CheckResolution.plan(finding("UNDETERMINED",a)).get("strategy"));
    }
    @Test void actualRecoveryInputOmitsReviewedPageWhenAnotherPageOfTheSameObligationRemains(){
        var a=assessment("UNREADABLE");Map<String,Object> reviewed=Map.of("obligationId","FORM-1","documentId","bid","location",276,"locationType","PAGE","task","SIGNATURE_VISIBILITY","fields",List.of("张三"),"sourceIds",List.of("C276"));
        Map<String,Object> pending=new LinkedHashMap<>(reviewed);pending.put("location",277);pending.put("sourceIds",List.of("C277"));
        atomic(a).put("visualRequests",List.of(reviewed,pending));Finding f=finding("UNDETERMINED",a);
        assertEquals(List.of(pending),RecoveryInputs.visualRequest(f).get("visualRequests"));String key=RecoveryInputs.inputKey(f,"VERIFY_SOURCE_PAGES");
        atomic(a).put("visualRequests",List.of(pending));assertEquals(key,RecoveryInputs.inputKey(finding("UNDETERMINED",a),"VERIFY_SOURCE_PAGES"));
    }
    @Test void absenceAtOneCandidateCannotBecomeAProvenNegativeWhenTheLeafIsStillUnknown(){
        var a=assessment("UNREADABLE");observed(a).put("outcome","NOT_OBSERVED");assertTrue(TrustedVisualReview.inspect(a).reviewed());assertFalse(TrustedVisualReview.inspect(a).negative());
        var negative=assessment("NOT_OBSERVED");((Map<?,?>)condition(negative).get("proof")).remove("missingVisibleSubjects");assertFalse(TrustedVisualReview.inspect(negative).negative());
    }
    @Test void semanticScopeOrModelAvailabilityGapsCannotYieldWaitingUser(){
        for(Map<String,Object> extra:List.of(Map.<String,Object>of("coverage",Map.of("semanticGap",Map.of("validated",true,"kind","SYSTEM_RELATION"))),
            Map.<String,Object>of("coverage",Map.of("completeness",Map.of("coreRequirementDeferred",true))),Map.<String,Object>of("model",Map.of("state","UNAVAILABLE")))){
            var a=assessment("UNREADABLE");a.putAll(extra);assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
        }
    }
    @Test void mixedSignedLocationObservationsKeepAbsenceSeparateFromUnreadableSignature(){
        var a=assessment("NOT_OBSERVED");var other=assessment("UNREADABLE");observed(a).put("type","SEAL");atomic(a).put("conditions",List.of(condition(a),condition(other)));
        String html=ReportService.originalReviewHtml(finding("FAIL",a),Map.of());assertTrue(html.contains("盖章位置：未观察到"));assertTrue(html.contains("签字位置：无法辨清"));
    }
    @Test void nestedTemplateSignatureProofUsesActualLeafBindingAndIgnoresUnusedAlternative(){
        var a=assessment("UNREADABLE");var leaf=new LinkedHashMap<>(condition(a));condition(a).put("predicate","MATERIAL");condition(a).put("proof",Map.of("signatureCoverage",Map.of("id","FORM-1-SIGNATURES","status","UNDETERMINED","proof",Map.of("children",List.of(leaf)))));
        assertTrue(TrustedVisualReview.inspect(a).unreadableOnly());
        condition(a).put("status","PASS");assertFalse(TrustedVisualReview.inspect(a).reviewed());
    }
}

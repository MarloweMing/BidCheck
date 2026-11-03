package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrustedVisualReviewTest {
    Map<String,Object> observation(String outcome){return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOfEntries(cn.bidcheck.compat.Java8Api.entry("observationId", "v1"), cn.bidcheck.compat.Java8Api.entry("obligationId", "FORM-1"), cn.bidcheck.compat.Java8Api.entry("sourceBindingVerified", true), cn.bidcheck.compat.Java8Api.entry("coverage", "CURRENT_FULL_PAGE"), cn.bidcheck.compat.Java8Api.entry("documentId", "bid"), cn.bidcheck.compat.Java8Api.entry("documentVersion", 2), cn.bidcheck.compat.Java8Api.entry("documentHash", "doc-hash"), cn.bidcheck.compat.Java8Api.entry("imageHash", "image-hash"), cn.bidcheck.compat.Java8Api.entry("location", 276), cn.bidcheck.compat.Java8Api.entry("type", "SIGNATURE"), cn.bidcheck.compat.Java8Api.entry("subjects", cn.bidcheck.compat.Java8Api.listOf("张三")), cn.bidcheck.compat.Java8Api.entry("outcome", outcome), cn.bidcheck.compat.Java8Api.entry("requestedSourceIds", cn.bidcheck.compat.Java8Api.listOf("C276"))));}
    Evidence evidence(){return new Evidence("bid","bid.pdf",276,"PAGE","[原图已核验：签字位置]","image:image-hash",0);}
    Map<String,Object> assessment(String outcome){
        boolean missing=outcome.equals("NOT_OBSERVED");
        Map<String,Object> proof=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("decisionBasis", "VISUAL_OBSERVATION", "observationOutcome", missing?"NOT_OBSERVED":"UNREADABLE_OR_PARTIAL_REVIEW", "automaticRejection", false, "authenticityAssessed", false, "observations", cn.bidcheck.compat.Java8Api.listOf(observation(outcome))));
        if(missing)proof.put("missingVisibleSubjects",cn.bidcheck.compat.Java8Api.listOf("张三"));
        Map<String,Object> condition=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "FORM-1", "status", missing?"FAIL":"UNDETERMINED", "conditionRole", "REQUIREMENT", "evaluated", true, "predicate", "VISUAL", "proof", proof, "evidences", cn.bidcheck.compat.Java8Api.listOf(evidence())));
        Map<String,Object> atomic=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(condition), "gaps", missing?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_MARK_UNREADABLE")), "documentIdentities", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "bid", "version", 2, "sha256", "doc-hash"))));
        return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic, "preOcrEvaluation", cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED"), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true)));
    }
    Finding finding(String status,Map<String,Object> a){return new Finding("f","r",null,new Evaluation(status,"原图检查结果","",cn.bidcheck.compat.Java8Api.listOf(evidence()),0,"RULE"),a);}
    Map<String,Object> atomic(Map<String,Object> a){return (Map<String,Object>)a.get("atomicCheck");}
    Map<String,Object> condition(Map<String,Object> a){return (Map<String,Object>)((List<?>)atomic(a).get("conditions")).get(0);}
    Map<String,Object> observed(Map<String,Object> a){return (Map<String,Object>)((List<?>)((Map<?,?>)condition(a).get("proof")).get("observations")).get(0);}
    @Test void checkedUnreadableMarkWaitsForUserWithoutRepeatingIdenticalPage(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");atomic(a).put("visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("obligationId", "FORM-1", "documentId", "bid", "location", 276, "locationType", "PAGE", "task", "SIGNATURE_VISIBILITY", "fields", cn.bidcheck.compat.Java8Api.listOf("张三"), "sourceIds", cn.bidcheck.compat.Java8Api.listOf("C276"))));
        Finding f=finding("UNDETERMINED",a);java.util.Map<java.lang.String, java.lang.Object> presentation=BusinessResultPresentation.describe(f.evaluation(),a);
        assertEquals("USER_ACTION",presentation.get("state"));assertTrue(presentation.get("nextStep").toString().contains("原图已检查"));
        assertEquals("WAITING_USER",CheckResolution.plan(f).get("status"));assertEquals("BLOCKED",CheckResolution.plan(f).get("strategy"));
    }
    @Test void realNegativeObservationIsRiskWithOriginalPageRatherThanUnexaminedWarning(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("NOT_OBSERVED");Finding f=finding("FAIL",a);java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(f.evaluation(),a);
        assertEquals("RISK",p.get("state"));assertTrue(p.get("nextStep").toString().contains("不自动作出否决"));assertEquals("SUCCEEDED",CheckResolution.plan(f).get("status"));
        String html=ReportService.originalReviewHtml(f,cn.bidcheck.compat.Java8Api.mapOf());assertTrue(html.contains("原图核验记录"));assertTrue(html.contains("276"));assertFalse(html.contains("原图复核提示"));
    }
    @Test void wrongPageOldVersionWrongHashWrongObligationAndUnvalidatedReceiptsCannotAssignUserWork(){
        for(java.util.Map<java.lang.String, java.lang.Object> mutation:cn.bidcheck.compat.Java8Api.<Map<String,Object>>listOf(cn.bidcheck.compat.Java8Api.mapOf("location", 275), cn.bidcheck.compat.Java8Api.mapOf("documentVersion", 1), cn.bidcheck.compat.Java8Api.mapOf("documentHash", "old"), cn.bidcheck.compat.Java8Api.mapOf("imageHash", "other"), cn.bidcheck.compat.Java8Api.mapOf("obligationId", "OTHER"), cn.bidcheck.compat.Java8Api.mapOf("sourceBindingVerified", false), cn.bidcheck.compat.Java8Api.mapOf("coverage", "CROP"), cn.bidcheck.compat.Java8Api.mapOf("requestedSourceIds", cn.bidcheck.compat.Java8Api.listOf()))){
            java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");observed(a).putAll(mutation);cn.bidcheck.domain.Domain.Finding f=finding("UNDETERMINED",a);
            assertFalse(TrustedVisualReview.inspect(a).reviewed(),mutation.toString());assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(f.evaluation(),a).get("state"));assertNotEquals("WAITING_USER",CheckResolution.plan(f).get("status"));
        }
    }
    @Test void legacyGapWithoutDocumentSnapshotCannotBeUpgradedToCompletedImageReview(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");atomic(a).remove("documentIdentities");assertFalse(TrustedVisualReview.inspect(a).reviewed());
        assertEquals("EVIDENCE_PENDING",BusinessResultPresentation.describe(finding("UNDETERMINED",a).evaluation(),a).get("state"));
    }
    @Test void technicalFailuresOutrankReviewedUnreadableMark(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");atomic(a).put("modelDiagnostics",cn.bidcheck.compat.Java8Api.mapOf("unresolvedFailures", 1, "failures", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("errorCode", "MODEL_TIMEOUT"))));
        Finding f=finding("UNDETERMINED",a);java.util.Map<java.lang.String, java.lang.Object> p=BusinessResultPresentation.describe(f.evaluation(),a);
        assertEquals("SYSTEM_INCOMPLETE",p.get("state"));assertEquals(false,p.get("userActionRequired"));assertEquals("RETRY_FAILED_STEP",CheckResolution.plan(f).get("strategy"));
    }
    @Test void unsupportedClauseOrSubjectRelationCannotBeDelegatedToUserBecauseOneMarkIsUnreadable(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("REQUIREMENT_UNRESOLVED", "SUBJECT_RELATION_UNPROVEN", "REGISTRATION_EMPLOYER_BINDING_REQUIRED")){
            java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");atomic(a).put("gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "VISUAL_MARK_UNREADABLE"), cn.bidcheck.compat.Java8Api.mapOf("code", code)));
            assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
            assertFalse((boolean)BusinessResultPresentation.describe(finding("UNDETERMINED",a).evaluation(),a).get("userActionRequired"));
        }
    }
    @Test void unresolvedOtherLeafAndUnstartedFieldCannotBecomeImageConfirmationOnly(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");java.util.Map<java.lang.String, java.lang.Object> good=condition(a);atomic(a).put("conditions",cn.bidcheck.compat.Java8Api.listOf(good, cn.bidcheck.compat.Java8Api.mapOf("id", "OTHER", "status", "UNDETERMINED", "evaluated", true, "conditionRole", "REQUIREMENT")));
        assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
        a=assessment("UNREADABLE");atomic(a).put("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("status", "UNREAD")));assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
    }
    @Test void anotherUnexaminedPageStillRequiresSourceWork(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");atomic(a).put("visualRequests",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("obligationId", "FORM-1", "documentId", "bid", "location", 277, "locationType", "PAGE", "task", "SIGNATURE_VISIBILITY", "fields", cn.bidcheck.compat.Java8Api.listOf("张三"), "sourceIds", cn.bidcheck.compat.Java8Api.listOf("C277"))));
        assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertEquals("VERIFY_SOURCE_PAGES",CheckResolution.plan(finding("UNDETERMINED",a)).get("strategy"));
    }
    @Test void actualRecoveryInputOmitsReviewedPageWhenAnotherPageOfTheSameObligationRemains(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");Map<String,Object> reviewed=cn.bidcheck.compat.Java8Api.mapOf("obligationId", "FORM-1", "documentId", "bid", "location", 276, "locationType", "PAGE", "task", "SIGNATURE_VISIBILITY", "fields", cn.bidcheck.compat.Java8Api.listOf("张三"), "sourceIds", cn.bidcheck.compat.Java8Api.listOf("C276"));
        Map<String,Object> pending=new LinkedHashMap<>(reviewed);pending.put("location",277);pending.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("C277"));
        atomic(a).put("visualRequests",cn.bidcheck.compat.Java8Api.listOf(reviewed, pending));Finding f=finding("UNDETERMINED",a);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(pending),RecoveryInputs.visualRequest(f).get("visualRequests"));String key=RecoveryInputs.inputKey(f,"VERIFY_SOURCE_PAGES");
        atomic(a).put("visualRequests",cn.bidcheck.compat.Java8Api.listOf(pending));assertEquals(key,RecoveryInputs.inputKey(finding("UNDETERMINED",a),"VERIFY_SOURCE_PAGES"));
    }
    @Test void absenceAtOneCandidateCannotBecomeAProvenNegativeWhenTheLeafIsStillUnknown(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");observed(a).put("outcome","NOT_OBSERVED");assertTrue(TrustedVisualReview.inspect(a).reviewed());assertFalse(TrustedVisualReview.inspect(a).negative());
        java.util.Map<java.lang.String, java.lang.Object> negative=assessment("NOT_OBSERVED");((Map<?,?>)condition(negative).get("proof")).remove("missingVisibleSubjects");assertFalse(TrustedVisualReview.inspect(negative).negative());
    }
    @Test void semanticScopeOrModelAvailabilityGapsCannotYieldWaitingUser(){
        for(Map<String,Object> extra:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("semanticGap", cn.bidcheck.compat.Java8Api.mapOf("validated", true, "kind", "SYSTEM_RELATION"))), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("completeness", cn.bidcheck.compat.Java8Api.mapOf("coreRequirementDeferred", true))), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("model", cn.bidcheck.compat.Java8Api.mapOf("state", "UNAVAILABLE")))){
            java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");a.putAll(extra);assertFalse(TrustedVisualReview.inspect(a).unreadableOnly());assertNotEquals("WAITING_USER",CheckResolution.plan(finding("UNDETERMINED",a)).get("status"));
        }
    }
    @Test void mixedSignedLocationObservationsKeepAbsenceSeparateFromUnreadableSignature(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("NOT_OBSERVED");java.util.Map<java.lang.String, java.lang.Object> other=assessment("UNREADABLE");observed(a).put("type","SEAL");atomic(a).put("conditions",cn.bidcheck.compat.Java8Api.listOf(condition(a), condition(other)));
        String html=ReportService.originalReviewHtml(finding("FAIL",a),cn.bidcheck.compat.Java8Api.mapOf());assertTrue(html.contains("盖章位置：未观察到"));assertTrue(html.contains("签字位置：无法辨清"));
    }
    @Test void nestedTemplateSignatureProofUsesActualLeafBindingAndIgnoresUnusedAlternative(){
        java.util.Map<java.lang.String, java.lang.Object> a=assessment("UNREADABLE");java.util.LinkedHashMap<java.lang.String, java.lang.Object> leaf=new LinkedHashMap<>(condition(a));condition(a).put("predicate","MATERIAL");condition(a).put("proof",cn.bidcheck.compat.Java8Api.mapOf("signatureCoverage", cn.bidcheck.compat.Java8Api.mapOf("id", "FORM-1-SIGNATURES", "status", "UNDETERMINED", "proof", cn.bidcheck.compat.Java8Api.mapOf("children", cn.bidcheck.compat.Java8Api.listOf(leaf)))));
        assertTrue(TrustedVisualReview.inspect(a).unreadableOnly());
        condition(a).put("status","PASS");assertFalse(TrustedVisualReview.inspect(a).reviewed());
    }
}

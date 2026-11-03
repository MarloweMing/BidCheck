package cn.bidcheck.service;

import cn.bidcheck.checktemplate.ConditionResult;
import cn.bidcheck.checktemplate.ResultProjection;
import cn.bidcheck.checktemplate.TemplateTypes;
import cn.bidcheck.domain.Domain.Evaluation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemplateResultReconciliationTest {
    @Test void frozenEvidenceQueriesDeclareTheDocumentRolesForTheFinalGuard() {
        Map<String,Object> tenderOnly=cn.bidcheck.compat.Java8Api.mapOf("templateEvidence", cn.bidcheck.compat.Java8Api.mapOf("evidenceQueries", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentRoles", cn.bidcheck.compat.Java8Api.listOf("TENDER")), cn.bidcheck.compat.Java8Api.mapOf("documentRoles", cn.bidcheck.compat.Java8Api.listOf("TENDER")))));
        Map<String,Object> crossDocument=cn.bidcheck.compat.Java8Api.mapOf("templateEvidence", cn.bidcheck.compat.Java8Api.mapOf("evidenceQueries", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentRoles", cn.bidcheck.compat.Java8Api.listOf("BID")), cn.bidcheck.compat.Java8Api.mapOf("documentRoles", cn.bidcheck.compat.Java8Api.listOf("TENDER")))));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("TENDER"),TemplateResultFinalizer.requiredProofDocumentRoles(tenderOnly));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("BID", "TENDER"),TemplateResultFinalizer.requiredProofDocumentRoles(crossDocument));
        assertTrue(TemplateResultFinalizer.requiredProofDocumentRoles(cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
    }

    @Test void finalEvidenceGuardDowngradesTheTemplateProjectionToo() {
        ResultProjection preliminary=projection(TemplateTypes.ConclusionState.SATISFIED,
            TemplateTypes.EvidenceState.SUFFICIENT,TemplateTypes.ExecutionState.FINISHED,
            TemplateTypes.ActionOwner.NONE,"NONE",TemplateTypes.VerificationMode.DOCUMENT);
        Evaluation guarded=new Evaluation("UNDETERMINED","原图覆盖不足，不能发布通过结论","核对指定原图",cn.bidcheck.compat.Java8Api.listOf(),0,"DETERMINISTIC/OCR_REVIEW");

        Map<String,Object> assessment=FocusedCheckEvaluator.reconcileAssessment(guarded,cn.bidcheck.compat.Java8Api.mapOf("templateResult", preliminary, "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", true, "incomplete", true)));
        ResultProjection result=(ResultProjection)assessment.get("resultProjection");
        assertTrue(result.finalReconciled());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,result.conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,result.review().automaticConclusion());
        assertEquals(TemplateTypes.EvidenceState.PARTIAL,result.evidence().state());
        assertEquals(TemplateTypes.ExecutionState.BLOCKED,result.execution().state());
        assertTrue(result.execution().retryable());
        assertEquals(TemplateTypes.ActionOwner.SYSTEM,result.action().owner());
        assertEquals("RESOLVE_FINAL_GUARD",result.action().type());
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(result.action().actionId()));
        assertTrue(result.missingInputs().stream().anyMatch(value->value.startsWith("system.finalGuard.FINAL_")));
        assertTrue(result.nextStep().contains("系统需处理最终保护项"));
        assertTrue(result.nextStep().contains("核对指定原图"));
        Map<?,?> business=(Map<?,?>)assessment.get("businessPresentation");
        assertEquals("SYSTEM_INCOMPLETE",business.get("state"));
        assertEquals("UNDETERMINED",business.get("finalStatus"));
    }

    @Test void reconciledGuardedUnknownCannotKeepContradictoryPassFields() {
        ResultProjection preliminary=new ResultProjection("template-result-v1","business","spec",1,7,
            TemplateTypes.Category.QUALIFICATION,TemplateTypes.Purpose.MANDATORY,TemplateTypes.VerificationMode.DOCUMENT,
            new ResultProjection.Execution(TemplateTypes.ExecutionState.FINISHED,false,"job-7"),
            new ResultProjection.Conclusion(TemplateTypes.ConclusionState.SATISFIED,"模板通过",TemplateTypes.ProofStandard.FACTUAL_CONDITION,"PASS"),
            new ResultProjection.Evidence(TemplateTypes.EvidenceState.SUFFICIENT,cn.bidcheck.compat.Java8Api.listOf("doc:1:PAGE:1")),
            new ResultProjection.Action(TemplateTypes.ActionOwner.NONE,"NONE",null),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf("original.input"),cn.bidcheck.compat.Java8Api.listOf(),
            new ResultProjection.Review(TemplateTypes.ConclusionState.SATISFIED,null),"test-evaluator","无需处理",false);

        ResultProjection result=TemplateResultFinalizer.reconcile(
            new Evaluation("UNDETERMINED","保护条件未满足","",cn.bidcheck.compat.Java8Api.listOf(),0,"FINAL_GUARD"),preliminary);

        assertAll(
            ()->assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,result.conclusion().state()),
            ()->assertNotEquals(TemplateTypes.EvidenceState.SUFFICIENT,result.evidence().state()),
            ()->assertEquals(TemplateTypes.ExecutionState.BLOCKED,result.execution().state()),
            ()->assertEquals("job-7",result.execution().jobId()),
            ()->assertEquals(TemplateTypes.ActionOwner.SYSTEM,result.action().owner()),
            ()->assertNotEquals("NONE",result.action().type()),
            ()->assertTrue(result.missingInputs().contains("original.input")),
            ()->assertTrue(result.missingInputs().stream().anyMatch(input->input.startsWith("system.finalGuard."))),
            ()->assertFalse(result.nextStep().equals("无需处理")),
            ()->assertTrue(result.finalReconciled())
        );
    }

    @Test void templateFailureIsSystemWorkAndExternalModeIsAnExplicitUserAction() {
        Evaluation unknown=new Evaluation("UNDETERMINED","尚未形成结论","",cn.bidcheck.compat.Java8Api.listOf(),0,"TEMPLATE");
        java.util.Map<java.lang.String, java.lang.Object> failed=FocusedCheckEvaluator.reconcileAssessment(unknown,cn.bidcheck.compat.Java8Api.mapOf("templateResult", projection(
            TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.SCOPE_UNRESOLVED,
            TemplateTypes.ExecutionState.FAILED,TemplateTypes.ActionOwner.SYSTEM,"RETRY_TEMPLATE",TemplateTypes.VerificationMode.DOCUMENT)));
        assertEquals("SYSTEM_INCOMPLETE",((Map<?,?>)failed.get("businessPresentation")).get("state"));

        java.util.Map<java.lang.String, java.lang.Object> external=FocusedCheckEvaluator.reconcileAssessment(unknown,cn.bidcheck.compat.Java8Api.mapOf("templateResult", projection(
            TemplateTypes.ConclusionState.UNDETERMINED,TemplateTypes.EvidenceState.NOT_LOCATED,
            TemplateTypes.ExecutionState.FINISHED,TemplateTypes.ActionOwner.EXTERNAL,"WAIT_EXTERNAL",TemplateTypes.VerificationMode.EXTERNAL)));
        Map<?,?> presentation=(Map<?,?>)external.get("businessPresentation");
        assertEquals("USER_ACTION",presentation.get("state"));
        assertEquals(true,presentation.get("userActionRequired"));
    }

    private ResultProjection projection(TemplateTypes.ConclusionState conclusion,TemplateTypes.EvidenceState evidence,
                                        TemplateTypes.ExecutionState execution,TemplateTypes.ActionOwner owner,String action,
                                        TemplateTypes.VerificationMode mode) {
        return new ResultProjection("template-result-v1","business","spec",1,1,
            TemplateTypes.Category.QUALIFICATION,TemplateTypes.Purpose.MANDATORY,mode,
            new ResultProjection.Execution(execution,false,null),
            new ResultProjection.Conclusion(conclusion,"模板初步结论",TemplateTypes.ProofStandard.FACTUAL_CONDITION,"TEST"),
            new ResultProjection.Evidence(evidence,cn.bidcheck.compat.Java8Api.listOf("doc:1:PAGE:1")),
            new ResultProjection.Action(owner,action,null),cn.bidcheck.compat.Java8Api.<ConditionResult>listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),
            new ResultProjection.Review(conclusion,null),"test-evaluator","按状态继续处理",false);
    }
}

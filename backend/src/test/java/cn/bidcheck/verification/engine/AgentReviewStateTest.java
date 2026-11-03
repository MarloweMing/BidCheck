package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentReviewStateTest {
    private static ModelGateway gateway(String identity){return new ModelGateway(){
        public boolean enabled(){return true;}public String identity(){return identity;}
        public JsonNode request(String operation,Map<String,Object> input){throw new AssertionError("Replay must not call a model");}
    };}
    private static RunSession session(List<Map<String,Object>> steps){return new RunSession(gateway("model-v1"),new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("priorSteps", steps)),"checkpoint-and-frozen-documents-v1");}
    private static Map<String,Object> planOutput(){return cn.bidcheck.compat.Java8Api.mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("obligationId", "C1")), "coverageExplanation", "complete", "review", cn.bidcheck.compat.Java8Api.mapOf("approved", true), "requirementFingerprint", "requirements-v1");}
    private static Map<String,Object> judgmentOutput(String revision){return cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "C1", "planRevision", "plan-v1", "proposal", cn.bidcheck.compat.Java8Api.mapOf("status", "PASS"), "review", cn.bidcheck.compat.Java8Api.mapOf("approved", true), "sourceIds", cn.bidcheck.compat.Java8Api.listOf("B1"), "reviewInputFingerprint", "review-v1", "targetEvidenceRevision", revision, "dependencySources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "B1", "hash", "source-v1")));}
    private static Map<String,Object> planStep(){RunSession recorder=session(cn.bidcheck.compat.Java8Api.listOf());recorder.record(AgentReviewState.PLAN,cn.bidcheck.compat.Java8Api.mapOf("planRevision", "plan-v1"),planOutput());return recorder.steps.get(0);}
    private static Map<String,Object> judgmentStep(String revision){RunSession recorder=session(cn.bidcheck.compat.Java8Api.listOf());recorder.record(AgentReviewState.JUDGMENT,cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "C1", "planRevision", "plan-v1"),judgmentOutput(revision));return recorder.steps.get(0);}

    @Test void reusesActualSessionRecordedPlanAndJudgmentsWithoutModelCalls(){
        RunSession resumed=session(cn.bidcheck.compat.Java8Api.listOf(planStep(), judgmentStep("evidence-v1")));
        assertEquals(planOutput(),AgentReviewState.findApprovedPlan(resumed,"requirements-v1","plan-v1"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(judgmentOutput("evidence-v1")),AgentReviewState.acceptedJudgments(resumed,"plan-v1"));
        assertEquals(0,resumed.modelCalls);
    }
    @Test void resultProjectionAndArbitraryProofAreNotAReplayChannel(){
        RunSession resumed=session(cn.bidcheck.compat.Java8Api.listOf());resumed.context.put("previousAtomicCheck",cn.bidcheck.compat.Java8Api.mapOf("steps", cn.bidcheck.compat.Java8Api.listOf(planStep(), judgmentStep("evidence-v1")), "agentReview", cn.bidcheck.compat.Java8Api.mapOf("planRevision", "plan-v1")));
        assertTrue(AgentReviewState.findApprovedPlan(resumed,"requirements-v1","plan-v1").isEmpty());
        assertTrue(AgentReviewState.acceptedJudgments(resumed,"plan-v1").isEmpty());
    }
    @Test void metadataMismatchAndIncompleteStepsNeverAuthorizeReplay(){
        for(String key:cn.bidcheck.compat.Java8Api.listOf("status", "schemaVersion", "modelIdentity", "inputFingerprint", "type")){
            Map<String,Object> plan=new LinkedHashMap<>(planStep()),judgment=new LinkedHashMap<>(judgmentStep("evidence-v1"));plan.put(key,"WRONG");judgment.put(key,"WRONG");RunSession resumed=session(cn.bidcheck.compat.Java8Api.listOf(plan, judgment));
            assertTrue(AgentReviewState.findApprovedPlan(resumed,"requirements-v1","plan-v1").isEmpty(),key);
            assertTrue(AgentReviewState.acceptedJudgments(resumed,"plan-v1").isEmpty(),key);
        }
    }
    @Test void changedScopePlanAndRequirementsCannotBorrowAnApproval(){
        List<Map<String,Object>> steps=cn.bidcheck.compat.Java8Api.listOf(planStep(), judgmentStep("evidence-v1"));RunSession resumed=session(steps);
        assertTrue(AgentReviewState.findApprovedPlan(resumed,"requirements-v2","plan-v1").isEmpty());
        assertTrue(AgentReviewState.findApprovedPlan(resumed,"requirements-v1","plan-v2").isEmpty());
        assertTrue(AgentReviewState.acceptedJudgments(resumed,"plan-v2").isEmpty());
        RunSession foreign=new RunSession(gateway("model-v1"),cn.bidcheck.compat.Java8Api.mapOf("priorSteps", steps),"another-checkpoint");
        assertTrue(AgentReviewState.findApprovedPlan(foreign,"requirements-v1","plan-v1").isEmpty());
        assertTrue(AgentReviewState.acceptedJudgments(foreign,"plan-v1").isEmpty());
    }
    @Test void targetIsBoundToOriginalStepInputAndMissingOutputIsRejected(){
        Map<String,Object> source=judgmentStep("evidence-v1"),output=new LinkedHashMap<>(judgmentOutput("evidence-v1"));output.put("targetObligationId","C2");Map<String,Object> changed=new LinkedHashMap<>(source);changed.put("output",output);
        assertTrue(AgentReviewState.acceptedJudgments(session(cn.bidcheck.compat.Java8Api.listOf(changed)),"plan-v1").isEmpty());
        changed=new LinkedHashMap<>(source);changed.put("output",cn.bidcheck.compat.Java8Api.listOf(output));assertTrue(AgentReviewState.acceptedJudgments(session(cn.bidcheck.compat.Java8Api.listOf(changed)),"plan-v1").isEmpty());
    }
    @Test void preservesEligibleEvidenceRevisionsForCallerValidationAndReturnsCopies(){
        List<Map<String,Object>> steps=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(judgmentStep("evidence-v1"), judgmentStep("evidence-v2")));RunSession resumed=session(steps);
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> candidates=AgentReviewState.acceptedJudgments(resumed,"plan-v1");assertEquals(cn.bidcheck.compat.Java8Api.listOf("evidence-v1", "evidence-v2"),cn.bidcheck.compat.Java8Api.toList(candidates.stream().map(c->c.get("targetEvidenceRevision"))));
        candidates.get(0).put("targetObligationId","changed");assertEquals("C1",AgentReviewState.acceptedJudgments(resumed,"plan-v1").get(0).get("targetObligationId"));
    }
}

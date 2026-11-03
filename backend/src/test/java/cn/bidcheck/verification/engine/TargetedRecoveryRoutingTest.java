package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class TargetedRecoveryRoutingTest {
    @Test void anUnexpandedVisualReferenceRepairsTheRequirementInsteadOfReopeningImages(){
        Rule rule=new Rule("R1","引用条款签章要求","VISUAL","投标人","签章","EXISTS","","",cn.bidcheck.compat.Java8Api.mapOf(),"符合第3.2.4条","ALL",cn.bidcheck.compat.Java8Api.listOf());
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(rule),"SOURCE_RULE",cn.bidcheck.compat.Java8Api.listOf());
        Decision decision=new Decision("R1",rule.description(),"UNDETERMINED","引用范围未展开",cn.bidcheck.compat.Java8Api.listOf(),
            cn.bidcheck.compat.Java8Api.listOf(new Gap("REQUIREMENT_VISUAL_REFERENCE_UNRESOLVED","签章要求的引用条款尚未展开","展开条款","REQUIREMENT_MAPPING_CHANGED")),cn.bidcheck.compat.Java8Api.mapOf());
        List<Map<String,Object>> actions=ObligationRecovery.actions(plan,cn.bidcheck.compat.Java8Api.mapOf("R1", decision),cn.bidcheck.compat.Java8Api.listOf(),new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"scope"));
        assertEquals(1,actions.size());Map<String,Object> action=actions.get(0);
        assertEquals("REPAIR_REQUIREMENT",action.get("strategy"));assertEquals("RECOMPILE_REQUIREMENT",action.get("executionMode"));
        assertEquals(false,action.get("modelRequestsAllowed"));assertEquals(false,action.get("businessProof"));assertEquals("R1",action.get("obligationId"));
    }
    @Test void anActualVisualObservationGapKeepsItsSourcePageExecutor(){
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(),"SOURCE_RULE",cn.bidcheck.compat.Java8Api.listOf());
        Decision decision=new Decision("R1","授权页盖章","UNDETERMINED","未观察",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("VISUAL_OBSERVATION_REQUIRED","需观察已定位授权页")),cn.bidcheck.compat.Java8Api.mapOf());
        List<Map<String,Object>> actions=ObligationRecovery.actions(plan,cn.bidcheck.compat.Java8Api.mapOf("R1", decision),cn.bidcheck.compat.Java8Api.listOf(),new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"scope"));
        assertEquals("OBSERVE_SOURCE_PAGE",actions.get(0).get("strategy"));
    }
    @Test void requirementDimensionsAndFieldEvidenceHaveDistinctExecutors(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("PRICE_REQUIREMENT_DIMENSIONS_REQUIRED", "PERSON_COUNT_COMPARISON_REQUIRED", "PERSON_COUNT_ROLE_REQUIRED", "QUALIFICATION_PROOF_PARENT_REQUIRED", "COMPARISON_FIELD_MAPPING_REQUIRED", "COMPARISON_RECORD_IDENTITY_UNPROVEN", "REGISTRATION_CERTIFICATE_REQUIRED")){
            Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(),"SOURCE_RULE",cn.bidcheck.compat.Java8Api.listOf());
            Decision decision=new Decision("R1","条件","UNDETERMINED","缺口",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap(code,"缺口")),cn.bidcheck.compat.Java8Api.mapOf());
            List<Map<String,Object>> actions=ObligationRecovery.actions(plan,cn.bidcheck.compat.Java8Api.mapOf("R1", decision),cn.bidcheck.compat.Java8Api.listOf(),new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"scope"));
            assertEquals(RecoveryFailureRouting.requirement(code)?"REPAIR_REQUIREMENT":"COMPARISON_RECORD_IDENTITY_UNPROVEN".equals(code)?"RESOLVE_SUBJECT":"RETRIEVE_EVIDENCE",actions.get(0).get("strategy"),code);
        }
    }
    @Test void scoringSubjectiveReviewSourceAmbiguityAndMissingExecutorDoNotRetryEvidence(){
        for(java.util.Map.Entry<java.lang.String, java.lang.String> entry:cn.bidcheck.compat.Java8Api.mapOf("QUALITY_GRADE_REVIEW_REQUIRED", "REVIEW_QUALITY_GRADE", "SCORE_SOURCE_LIST_AMBIGUOUS", "CLARIFY_REQUIREMENT_SOURCE", "SCORING_EXCEPTION_RULE_REQUIRED", "REPAIR_REQUIREMENT").entrySet()){
            Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(),"SOURCE_RULE",cn.bidcheck.compat.Java8Api.listOf());Decision decision=new Decision("R1","评分","UNDETERMINED","缺口",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap(entry.getKey(),"精确缺口")),cn.bidcheck.compat.Java8Api.mapOf());
            assertEquals(entry.getValue(),ObligationRecovery.actions(plan,cn.bidcheck.compat.Java8Api.mapOf("R1", decision),cn.bidcheck.compat.Java8Api.listOf(),new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"scope")).get(0).get("strategy"));
        }
    }
}

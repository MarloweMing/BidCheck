package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class TargetedRecoveryRoutingTest {
    @Test void anUnexpandedVisualReferenceRepairsTheRequirementInsteadOfReopeningImages(){
        Rule rule=new Rule("R1","引用条款签章要求","VISUAL","投标人","签章","EXISTS","","",Map.of(),"符合第3.2.4条","ALL",List.of());
        Plan plan=new Plan("ALL",List.of(rule),"SOURCE_RULE",List.of());
        Decision decision=new Decision("R1",rule.description(),"UNDETERMINED","引用范围未展开",List.of(),
            List.of(new Gap("REQUIREMENT_VISUAL_REFERENCE_UNRESOLVED","签章要求的引用条款尚未展开","展开条款","REQUIREMENT_MAPPING_CHANGED")),Map.of());
        List<Map<String,Object>> actions=ObligationRecovery.actions(plan,Map.of("R1",decision),List.of(),new RunSession(ModelGateway.disabled(),Map.of(),"scope"));
        assertEquals(1,actions.size());Map<String,Object> action=actions.get(0);
        assertEquals("REPAIR_REQUIREMENT",action.get("strategy"));assertEquals("RECOMPUTE_ONLY",action.get("executionMode"));
        assertEquals(false,action.get("modelRequestsAllowed"));assertEquals(false,action.get("businessProof"));assertEquals("R1",action.get("obligationId"));
    }
    @Test void anActualVisualObservationGapKeepsItsSourcePageExecutor(){
        Plan plan=new Plan("ALL",List.of(),"SOURCE_RULE",List.of());
        Decision decision=new Decision("R1","授权页盖章","UNDETERMINED","未观察",List.of(),List.of(gap("VISUAL_OBSERVATION_REQUIRED","需观察已定位授权页")),Map.of());
        List<Map<String,Object>> actions=ObligationRecovery.actions(plan,Map.of("R1",decision),List.of(),new RunSession(ModelGateway.disabled(),Map.of(),"scope"));
        assertEquals("OBSERVE_SOURCE_PAGE",actions.get(0).get("strategy"));
    }
    @Test void requirementDimensionsAndFieldEvidenceHaveDistinctExecutors(){
        for(String code:List.of("PRICE_REQUIREMENT_DIMENSIONS_REQUIRED","PERSON_COUNT_COMPARISON_REQUIRED","PERSON_COUNT_ROLE_REQUIRED","COMPARISON_FIELD_MAPPING_REQUIRED","COMPARISON_RECORD_IDENTITY_UNPROVEN","REGISTRATION_CERTIFICATE_REQUIRED")){
            Plan plan=new Plan("ALL",List.of(),"SOURCE_RULE",List.of());
            Decision decision=new Decision("R1","条件","UNDETERMINED","缺口",List.of(),List.of(gap(code,"缺口")),Map.of());
            List<Map<String,Object>> actions=ObligationRecovery.actions(plan,Map.of("R1",decision),List.of(),new RunSession(ModelGateway.disabled(),Map.of(),"scope"));
            assertEquals(RecoveryFailureRouting.requirement(code)?"REPAIR_REQUIREMENT":"COMPARISON_RECORD_IDENTITY_UNPROVEN".equals(code)?"RESOLVE_SUBJECT":"RETRIEVE_EVIDENCE",actions.get(0).get("strategy"),code);
        }
    }
}

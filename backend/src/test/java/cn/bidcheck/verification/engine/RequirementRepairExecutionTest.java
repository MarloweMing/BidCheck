package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class RequirementRepairExecutionTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ModelGateway forbidden=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "no-network";}
        public JsonNode request(String operation,Map<String,Object> input){fail("requirement repair called model: "+operation);return null;}};
    private Rule rule(String id,String predicate,String quote){return new Rule(id,"",predicate,"","material","EXISTS","承诺书","",Map.of(),quote,"",List.of());}
    private Plan plan(Rule... rules){return new Plan("ALL",List.of(rules),"DETERMINISTIC_TEMPLATE_PARTIAL",List.of(),List.of(),Map.of());}
    private RunSession session(Plan old){return new RunSession(forbidden,new LinkedHashMap<>(Map.of("previousAtomicCheck",Map.of("plan",JSON.convertValue(old,Map.class)),
        "recoveryStrategy","REPAIR_REQUIREMENT","recoveryMode","RECOMPILE_REQUIREMENT","recoveryModelAllowed",false,"allowModelRuleCompilation",true)),"repair");}
    @Test void repairCallsCurrentDeterministicCompilerRatherThanRevalidatingUnsupportedPlan()throws Exception{
        String source="须提交《人员承诺书》。";Checkpoint cp=new Checkpoint("cp","人员承诺书","组成","HIGH","SEMANTIC_RESPONSE","material","EXISTS",source,"",true,true,true,null);
        Plan known=new RequirementCompiler().compile(cp,new RunSession(ModelGateway.disabled(),Map.of(),"base"));
        assertFalse(known.rules().isEmpty());Rule business=known.rules().get(0);
        Rule failed=new Rule("legacy-gap",business.description(),"UNSUPPORTED",business.subject(),business.field(),business.operator(),business.expected(),business.unit(),Map.of(),business.sourceQuote(),"",List.of());
        RunSession s=session(plan(failed));Plan repaired=new RequirementCompiler().compile(cp,s);
        assertEquals("DETERMINISTIC_REQUIREMENT_REPAIR",repaired.method(),repaired.issues().toString());
        assertTrue(repaired.rules().stream().anyMatch(r->r.id().equals("legacy-gap")&&!r.predicate().equals("UNSUPPORTED")));
        assertEquals(0,s.modelCalls);assertTrue(s.steps.stream().noneMatch(step->Set.of("COMPILE","EXTRACT").contains(step.get("type"))));
    }
    @Test void preservesIndependentProofIdsAcrossCompilerMetadataChanges(){
        Rule stable=rule("stable","MATERIAL","须提交承诺书"),gap=rule("gap","UNSUPPORTED","须提交授权书");
        Plan before=plan(stable,gap);Rule renamed=rule("new-stable","MATERIAL","须提交承诺书");
        Plan after=RequirementRepairExecution.publish(plan(renamed,rule("new-gap","MATERIAL","须提交授权书")),session(before));
        assertEquals("DETERMINISTIC_REQUIREMENT_REPAIR",after.method());assertEquals(List.of("stable","gap"),after.rules().stream().map(Rule::id).toList());
    }
    @Test void deletionWeakeningAndSourceReplacementAreRejected(){
        Plan before=plan(rule("stable","MATERIAL","须提交承诺书"),rule("gap","UNSUPPORTED","须提交授权书"));
        for(Plan invalid:List.of(plan(rule("gap","MATERIAL","须提交授权书")),
            new Plan("ANY",List.of(rule("stable","MATERIAL","须提交承诺书"),rule("gap","MATERIAL","须提交授权书")),"NEW",List.of()),
            plan(rule("stable","MATERIAL","须提交承诺书"),rule("gap","MATERIAL","另一项材料")))){
            Plan rejected=RequirementRepairExecution.publish(invalid,session(before));
            assertEquals("REQUIREMENT_REPAIR_REJECTED",rejected.method());assertEquals(before.rules(),rejected.rules());
        }
    }
    @Test void anUnsupportedBusinessRuleCannotBeRepairedIntoAnAuxiliaryInput(){
        Rule invalid=new Rule("gap","","UNSUPPORTED","","有效期","","","",Map.of("originalPredicate","VALIDITY"),"证书须有效","",List.of());
        Plan after=RequirementRepairExecution.publish(plan(rule("gap","REFERENCE_TIME","证书须有效")),session(plan(invalid)));
        assertEquals("REQUIREMENT_REPAIR_REJECTED",after.method());
    }
    @Test void emptyPlanRequiresAuditedSourceRestorationAndNonemptyValidatedRules(){
        Plan before=new Plan("ALL",List.of(),"SCOPE_UNRESOLVED",List.of("source mismatch"));
        Plan candidate=plan(rule("restored","MATERIAL","须提交承诺书"));
        assertEquals("REQUIREMENT_REPAIR_REJECTED",RequirementRepairExecution.publish(candidate,session(before)).method());
        Map<String,Object> scope=Map.of("sourceContext",Map.of("identity",Map.of("sourceRepair",Map.of("status","RESTORED","version","source-v2"))));
        Plan sourced=new Plan("ALL",candidate.rules(),"RESTORED",List.of(),List.of(),scope);
        Plan repaired=RequirementRepairExecution.publish(sourced,session(before));assertEquals("DETERMINISTIC_REQUIREMENT_REPAIR",repaired.method());
        assertEquals(true,((Map<?,?>)repaired.requirementScope().get("requirementRepair")).get("sourceRestored"));
        assertEquals(false,((Map<?,?>)repaired.requirementScope().get("requirementRepair")).get("businessProof"));
    }
    @Test void preservingNodeIdsDoesNotPermitMovingMandatoryObligationsUnderNewAlternatives(){
        Rule authorization=rule("A","UNSUPPORTED","须提交授权书"),undertaking=rule("B","MATERIAL","须提交承诺书");Plan original=plan(authorization,undertaking);
        for(String logic:List.of("ANY","CONDITIONAL")){
            List<Rule> children=logic.equals("ANY")?List.of(authorization,undertaking):List.of(rule("condition","INPUT","如适用"),authorization,undertaking);
            Rule bypass=new Rule("G","","GROUP","","","","","",Map.of(),"须提交授权书。须提交承诺书",logic,children);
            Plan rejected=RequirementRepairExecution.publish(plan(bypass),session(original));assertEquals("REQUIREMENT_REPAIR_REJECTED",rejected.method(),logic);assertEquals(original.rules(),rejected.rules());assertTrue(rejected.issues().stream().anyMatch(s->s.contains("强制执行路径")));
        }
        Rule conjunction=new Rule("G","","GROUP","","","","","",Map.of(),"须提交授权书。须提交承诺书","ALL",List.of(authorization,undertaking));
        assertEquals("DETERMINISTIC_REQUIREMENT_REPAIR",RequirementRepairExecution.publish(plan(conjunction),session(original)).method(),"non-skippable ALL wrappers are allowed");
    }
    @Test void emptySourceRestorationWithOnlyUnsupportedOrAuxiliaryNodesIsNotPublishedAsRuleRepair(){
        Plan before=new Plan("ALL",List.of(),"SCOPE_UNRESOLVED",List.of("source mismatch"));Map<String,Object> scope=Map.of("sourceContext",Map.of("identity",Map.of("sourceRepair",Map.of("status","RESTORED","version","source-v2"))));
        for(String predicate:List.of("UNSUPPORTED","INPUT","REFERENCE_TIME","NOT_APPLICABLE")){
            Plan candidate=new Plan("ALL",List.of(rule("restored",predicate,"须提交承诺书")),"RESTORED",List.of(),List.of(),scope);
            Plan after=RequirementRepairExecution.publish(candidate,session(before));assertEquals("SOURCE_RESTORED_REQUIREMENT_UNRESOLVED",after.method(),predicate);
            Map<?,?> audit=(Map<?,?>)after.requirementScope().get("requirementRepair");assertEquals("SOURCE_RESTORED_ONLY",audit.get("state"));assertEquals(true,audit.get("sourceRestored"));assertEquals(false,audit.get("ruleCompilationProgress"));assertFalse(after.issues().isEmpty());
        }
    }
    @Test void unknownBusinessRequirementCannotBecomeAnUnprovenInapplicableBranch(){
        Rule unknown=rule("A","UNSUPPORTED","须提交授权书"),inapplicable=rule("A","NOT_APPLICABLE","须提交授权书");
        Plan after=RequirementRepairExecution.publish(plan(inapplicable),session(plan(unknown)));assertEquals("REQUIREMENT_REPAIR_REJECTED",after.method());assertEquals(List.of(unknown),after.rules());
    }
}

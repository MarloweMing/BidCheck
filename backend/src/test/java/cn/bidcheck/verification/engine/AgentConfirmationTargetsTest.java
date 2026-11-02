package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class AgentConfirmationTargetsTest {
    private final ProgrammaticInferenceCandidatesTest f=new ProgrammaticInferenceCandidatesTest();
    private RuleEvaluator evaluator(){
        Map<String,Object> context=new HashMap<>();context.put("enableProgrammaticInferences",true);
        Decision decision=InferenceClaims.discover(f.rule,f.unresolved(f.candidate()),List.of(),f.corpus,context);
        assertEquals("NEEDS_CONFIRMATION",decision.status());RuleEvaluator evaluator=new RuleEvaluator(List.of(),f.corpus,context);
        evaluator.decisions.put(f.rule.id(),decision);return evaluator;
    }
    private Map<String,Object> receipt(RuleEvaluator evaluator,List<Rule> rules){return AgentInvestigationScope.targetReceipt(rules,new Plan("ALL",rules,"FIXTURE",List.of()),evaluator);}
    @Test void realProgramCandidatePausesOnlyAcquisitionAndRetainsBusinessRemainingTarget(){
        var result=receipt(evaluator(),List.of(f.rule));assertEquals(List.of(f.rule.id()),result.get("remainingTargetObligationIds"));
        assertEquals(List.of(f.rule.id()),result.get("pendingConfirmationTargetObligationIds"));assertEquals(List.of(),result.get("completedTargetObligationIds"));
        assertEquals(true,result.get("waitingForConfirmation"));assertFalse(AgentInvestigationScope.completed(List.of(f.rule),new Plan("ALL",List.of(f.rule),"FIXTURE",List.of()),evaluator()));
    }
    @Test void anotherContentGapKeepsWorkSchedulable(){
        var ev=evaluator();Rule other=new Rule("material","正文","MATERIAL","","material","","证明文件","",Map.of(),"须提供证明文件","",List.of());
        ev.decisions.put(other.id(),new Decision(other.id(),other.description(),"UNDETERMINED","缺口",List.of(),List.of(gap("CONTENT_EVIDENCE_REQUIRED","未核全")),Map.of()));
        var receipt=receipt(ev,List.of(f.rule,other));assertEquals(false,receipt.get("waitingForConfirmation"));assertEquals(List.of(f.rule.id(),other.id()),receipt.get("remainingTargetObligationIds"));
        assertEquals(List.of(f.rule.id()),receipt.get("pendingConfirmationTargetObligationIds"));
    }
    @Test void aCardDoesNotHideNonConfirmationGapOnSameTarget(){
        var ev=evaluator();Decision before=ev.decisions.get(f.rule.id());ev.decisions.put(f.rule.id(),new Decision(before.id(),before.description(),"NEEDS_CONFIRMATION",before.reason(),before.evidences(),List.of(gap("INFERENCE_CONFIRMATION_REQUIRED","确认"),gap("CONTENT_EVIDENCE_REQUIRED","未核全")),before.proof()));
        assertEquals(List.of(),receipt(ev,List.of(f.rule)).get("pendingConfirmationTargetObligationIds"));
    }
    @Test void persistedProofWithoutCurrentServerCandidateIsNotWaitingAuthority(){
        var ev=evaluator();ev.context.remove("inferredFacts");assertEquals(List.of(),receipt(ev,List.of(f.rule)).get("pendingConfirmationTargetObligationIds"));
    }
    @Test void changedDocumentHashOrQuoteInvalidatesWaitingCard(){
        for(String key:List.of("documentHash","quote")){
            var ev=evaluator();Map<String,Object> claim=RuleEvaluator.maps(ev.context.get("inferredFacts")).get(0);
            Map<String,Object> citation=new LinkedHashMap<>(RuleEvaluator.maps(claim.get("evidences")).get(0));citation.put(key,"changed-source");
            Map<String,Object> changed=new LinkedHashMap<>(claim);changed.put("evidences",List.of(citation));ev.context.put("inferredFacts",List.of(changed));
            Decision original=ev.decisions.get(f.rule.id());ev.decisions.put(f.rule.id(),new Decision(original.id(),original.description(),original.status(),original.reason(),original.evidences(),original.gaps(),Map.of("inferredFacts",List.of(changed))));
            assertEquals(List.of(),receipt(ev,List.of(f.rule)).get("pendingConfirmationTargetObligationIds"),key);
        }
    }
    @Test void changedRequirementCannotReuseOldCandidateReceipt(){
        var ev=evaluator();Rule changed=new Rule(f.rule.id(),f.rule.description(),f.rule.predicate(),f.rule.subject(),f.rule.field(),f.rule.operator(),f.rule.expected(),f.rule.unit(),f.rule.parameters(),"改变的任职要求","",List.of());
        assertEquals(List.of(),receipt(ev,List.of(changed)).get("pendingConfirmationTargetObligationIds"));
    }
}

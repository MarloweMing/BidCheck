package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class AgentConfirmationTargetsTest {
    private final ProgrammaticInferenceCandidatesTest f=new ProgrammaticInferenceCandidatesTest();
    private RuleEvaluator evaluator(){
        Map<String,Object> context=new HashMap<>();context.put("enableProgrammaticInferences",true);
        Decision decision=InferenceClaims.discover(f.rule,f.unresolved(f.candidate()),cn.bidcheck.compat.Java8Api.listOf(),f.corpus,context);
        assertEquals("NEEDS_CONFIRMATION",decision.status());RuleEvaluator evaluator=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),f.corpus,context);
        evaluator.decisions.put(f.rule.id(),decision);return evaluator;
    }
    private Map<String,Object> receipt(RuleEvaluator evaluator,List<Rule> rules){return AgentInvestigationScope.targetReceipt(rules,new Plan("ALL",rules,"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),evaluator);}
    @Test void realProgramCandidatePausesOnlyAcquisitionAndRetainsBusinessRemainingTarget(){
        java.util.Map<java.lang.String, java.lang.Object> result=receipt(evaluator(),cn.bidcheck.compat.Java8Api.listOf(f.rule));assertEquals(cn.bidcheck.compat.Java8Api.listOf(f.rule.id()),result.get("remainingTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(f.rule.id()),result.get("pendingConfirmationTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));
        assertEquals(true,result.get("waitingForConfirmation"));assertFalse(AgentInvestigationScope.completed(cn.bidcheck.compat.Java8Api.listOf(f.rule),new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(f.rule),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),evaluator()));
    }
    @Test void anotherContentGapKeepsWorkSchedulable(){
        cn.bidcheck.verification.engine.RuleEvaluator ev=evaluator();Rule other=new Rule("material","正文","MATERIAL","","material","","证明文件","",cn.bidcheck.compat.Java8Api.mapOf(),"须提供证明文件","",cn.bidcheck.compat.Java8Api.listOf());
        ev.decisions.put(other.id(),new Decision(other.id(),other.description(),"UNDETERMINED","缺口",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("CONTENT_EVIDENCE_REQUIRED","未核全")),cn.bidcheck.compat.Java8Api.mapOf()));
        java.util.Map<java.lang.String, java.lang.Object> receipt=receipt(ev,cn.bidcheck.compat.Java8Api.listOf(f.rule, other));assertEquals(false,receipt.get("waitingForConfirmation"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(f.rule.id(), other.id()),receipt.get("remainingTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(f.rule.id()),receipt.get("pendingConfirmationTargetObligationIds"));
    }
    @Test void aCardDoesNotHideNonConfirmationGapOnSameTarget(){
        cn.bidcheck.verification.engine.RuleEvaluator ev=evaluator();Decision before=ev.decisions.get(f.rule.id());ev.decisions.put(f.rule.id(),new Decision(before.id(),before.description(),"NEEDS_CONFIRMATION",before.reason(),before.evidences(),cn.bidcheck.compat.Java8Api.listOf(gap("INFERENCE_CONFIRMATION_REQUIRED","确认"), gap("CONTENT_EVIDENCE_REQUIRED","未核全")),before.proof()));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt(ev,cn.bidcheck.compat.Java8Api.listOf(f.rule)).get("pendingConfirmationTargetObligationIds"));
    }
    @Test void persistedProofWithoutCurrentServerCandidateIsNotWaitingAuthority(){
        cn.bidcheck.verification.engine.RuleEvaluator ev=evaluator();ev.context.remove("inferredFacts");assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt(ev,cn.bidcheck.compat.Java8Api.listOf(f.rule)).get("pendingConfirmationTargetObligationIds"));
    }
    @Test void changedDocumentHashOrQuoteInvalidatesWaitingCard(){
        for(String key:cn.bidcheck.compat.Java8Api.listOf("documentHash", "quote")){
            cn.bidcheck.verification.engine.RuleEvaluator ev=evaluator();Map<String,Object> claim=RuleEvaluator.maps(ev.context.get("inferredFacts")).get(0);
            Map<String,Object> citation=new LinkedHashMap<>(RuleEvaluator.maps(claim.get("evidences")).get(0));citation.put(key,"changed-source");
            Map<String,Object> changed=new LinkedHashMap<>(claim);changed.put("evidences",cn.bidcheck.compat.Java8Api.listOf(citation));ev.context.put("inferredFacts",cn.bidcheck.compat.Java8Api.listOf(changed));
            Decision original=ev.decisions.get(f.rule.id());ev.decisions.put(f.rule.id(),new Decision(original.id(),original.description(),original.status(),original.reason(),original.evidences(),original.gaps(),cn.bidcheck.compat.Java8Api.mapOf("inferredFacts", cn.bidcheck.compat.Java8Api.listOf(changed))));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt(ev,cn.bidcheck.compat.Java8Api.listOf(f.rule)).get("pendingConfirmationTargetObligationIds"),key);
        }
    }
    @Test void changedRequirementCannotReuseOldCandidateReceipt(){
        cn.bidcheck.verification.engine.RuleEvaluator ev=evaluator();Rule changed=new Rule(f.rule.id(),f.rule.description(),f.rule.predicate(),f.rule.subject(),f.rule.field(),f.rule.operator(),f.rule.expected(),f.rule.unit(),f.rule.parameters(),"改变的任职要求","",cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt(ev,cn.bidcheck.compat.Java8Api.listOf(changed)).get("pendingConfirmationTargetObligationIds"));
    }
}

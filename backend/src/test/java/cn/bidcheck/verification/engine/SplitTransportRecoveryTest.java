package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SplitTransportRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private Rule rule(String id){return new Rule(id,"资格字段","CERTIFICATE","甲公司","certificate","","资格证书","",Map.of(),"提供资格证书","",List.of());}
    private Map<String,Object> packet(int sources,int rules){List<Map<String,Object>> windows=new ArrayList<>();for(int i=0;i<sources;i++)windows.add(linked("id","S"+i,"documentId","b","locationType","PAGE","location",i+1,"quote","该来源是原始证照正文"));List<Rule> targets=new ArrayList<>();for(int i=0;i<rules;i++)targets.add(rule("R"+i));return ExtractionBatches.plan(linked("rules",targets,"sources",windows,"knownFacts",List.of(),"layoutCells",List.of()),null,Map.of()).batches().get(0);}
    private String key(Map<String,Object> p){return ExtractionBatches.sourceIds(p)+"/"+ExtractionBatches.rules(p).stream().map(Rule::id).toList();}
    private RunSession session(Set<String> fail){return new RunSession(new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "split-transport-test";}
        public JsonNode request(String op,Map<String,Object> input)throws IOException{if(fail.contains(key(input)))throw new VerificationModelClient.ModelRequestException("INPUT_LIMIT",413,false,true);return json.valueToTree(Map.of("facts",List.of()));}},Map.of("unlimitedModelCalls",true),"local");}
    private List<Map<String,Object>> failAndSplit(RunSession session,Map<String,Object> parent)throws Exception{IOException failure=assertThrows(IOException.class,()->session.model("EXTRACT",parent));var children=ExtractionBatches.splitFailed(parent);session.recordExtractionSplit(parent,children,RunSession.failure(failure));return children;}
    private List<Map<String,Object>> failures(RunSession s){return RuleEvaluator.maps(s.diagnostics().get("failures"));}

    @Test void allExactChildPacketsRecoverTransportAndPreserveFailedAttempt()throws Exception{
        var parent=packet(2,1);var session=session(Set.of(key(parent)));var children=failAndSplit(session,parent);for(var child:children)session.model("EXTRACT",child);
        assertEquals(1,session.diagnostics().get("failedAttempts"));assertEquals(1L,session.diagnostics().get("recoveredAttempts"));assertEquals(0L,session.diagnostics().get("unresolvedFailures"));
        assertEquals(true,failures(session).get(0).get("recoveredBySplit"));assertEquals(false,failures(session).get(0).get("recoveredByRetry"));assertEquals(true,failures(session).get(0).get("transportRecovered"));
        assertTrue(session.steps.stream().anyMatch(s->"FAILED".equals(s.get("status"))));
    }
    @Test void fieldRejectionDoesNotTurnTransportRecoveryIntoBusinessProof()throws Exception{
        var parent=packet(2,1);var session=session(Set.of(key(parent)));var children=failAndSplit(session,parent);for(var child:children){session.model("EXTRACT",child);
            var rejected=new ProofVerifier().verify(json.valueToTree(Map.of("facts",List.of(Map.of("kind","FIELD","rawValue","虚构","value","虚构","sourceIds",List.of("not-sent"))))),List.of());
            assertEquals(1,rejected.rejected().size());session.record("FACT_VALIDATION",Map.of("packetId",child.get("packetId")),linked("facts",rejected.facts(),"rejected",rejected.rejected()));}
        assertEquals(0L,session.diagnostics().get("unresolvedFailures"));assertEquals(true,failures(session).get(0).get("businessValidationSeparate"));
        assertFalse(session.steps.stream().anyMatch(s->"RULE_COMPUTATION".equals(s.get("type"))));
    }
    @Test void recoveredSplitDoesNotRequeueItsOldFailureWhileBusinessEvidenceIsStillMissing()throws Exception{
        var parent=packet(2,1);var session=session(Set.of(key(parent)));var children=failAndSplit(session,parent);
        for(var child:children)session.model("EXTRACT",child);
        Rule target=rule("R0");Plan plan=new Plan("ALL",List.of(target),"DETERMINISTIC_TEMPLATE_COMPILED",List.of(),List.of(),Map.of());
        Decision missing=new Decision(target.id(),target.description(),"UNDETERMINED","尚未找到对应证书",List.of(),
                List.of(new Gap("EVIDENCE_NOT_FOUND","尚未找到对应证书","继续定位该主体的资格证书","RETRIEVE_EVIDENCE")),Map.of());
        List<Map<String,Object>> actions=ObligationRecovery.actions(plan,Map.of(target.id(),missing),List.of(),session);
        assertEquals(0L,session.diagnostics().get("unresolvedFailures"));
        assertTrue(actions.stream().noneMatch(a->Set.of("SPLIT_FAILED_REQUEST","RETRY_FAILED_STEP","RESTORE_SERVICE").contains(a.get("strategy"))),actions.toString());
        assertTrue(actions.stream().anyMatch(a->"RETRIEVE_EVIDENCE".equals(a.get("strategy"))&&"EVIDENCE_NOT_FOUND".equals(a.get("reasonCode"))),actions.toString());
        assertEquals("UNDETERMINED",missing.status());assertFalse(missing.gaps().isEmpty());
        assertFalse(actions.stream().anyMatch(a->Boolean.TRUE.equals(a.get("businessProof"))));
    }
    @Test void oneChildFailureLeavesParentAndChildUnresolved()throws Exception{
        var parent=packet(2,1);var expected=ExtractionBatches.splitFailed(parent);var session=session(Set.of(key(parent),key(expected.get(1))));var children=failAndSplit(session,parent);
        session.model("EXTRACT",children.get(0));assertThrows(IOException.class,()->session.model("EXTRACT",children.get(1)));assertEquals(2L,session.diagnostics().get("unresolvedFailures"));
    }
    @Test void nestedSplitClosesOnlyAfterEveryLeafCompletes()throws Exception{
        var parent=packet(4,1);var expected=ExtractionBatches.splitFailed(parent);var session=session(Set.of(key(parent),key(expected.get(0))));var children=failAndSplit(session,parent);var grandchildren=failAndSplit(session,children.get(0));
        session.model("EXTRACT",grandchildren.get(0));session.model("EXTRACT",children.get(1));assertEquals(2L,session.diagnostics().get("unresolvedFailures"));
        session.model("EXTRACT",grandchildren.get(1));assertEquals(0L,session.diagnostics().get("unresolvedFailures"));assertEquals(2L,session.diagnostics().get("recoveredAttempts"));
    }
    @Test void splittingByObligationPreservesEveryRequiredField()throws Exception{var parent=packet(1,2);var session=session(Set.of(key(parent)));var children=failAndSplit(session,parent);session.model("EXTRACT",children.get(0));assertEquals(1L,session.diagnostics().get("unresolvedFailures"));session.model("EXTRACT",children.get(1));assertEquals(0L,session.diagnostics().get("unresolvedFailures"));}
    @Test void laterSuccessOnDifferentFingerprintDoesNotClosePlannedChild()throws Exception{
        var parent=packet(2,1);var session=session(Set.of(key(parent)));var children=failAndSplit(session,parent);session.model("EXTRACT",children.get(0));var changed=new LinkedHashMap<>(children.get(1));changed.put("differentContext",true);session.model("EXTRACT",changed);assertEquals(1L,session.diagnostics().get("unresolvedFailures"));
    }
    @Test void missingSourceOrRuleCannotBeAdvertisedAsCompleteSplit()throws Exception{
        var parent=packet(4,1);var session=session(Set.of(key(parent)));IOException failure=assertThrows(IOException.class,()->session.model("EXTRACT",parent));var firstHalf=ExtractionBatches.splitFailed(parent).get(0);var incomplete=ExtractionBatches.splitFailed(firstHalf);
        session.recordExtractionSplit(parent,incomplete,RunSession.failure(failure));for(var child:incomplete)session.model("EXTRACT",child);assertEquals(1L,session.diagnostics().get("unresolvedFailures"));
        var receipt=session.steps.stream().filter(s->"EXTRACTION_FAILURE_SPLIT".equals(s.get("type"))).findFirst().orElseThrow();assertEquals(false,((Map<?,?>)receipt.get("output")).get("sourceCoveragePreserved"));
    }
    @Test void oldChildSuccessCannotHideNewParentFailure()throws Exception{
        var parent=packet(2,1);var session=session(Set.of(key(parent)));for(var child:ExtractionBatches.splitFailed(parent))session.model("EXTRACT",child);failAndSplit(session,parent);assertEquals(1L,session.diagnostics().get("unresolvedFailures"));
    }
    @Test void explicitValidatedCacheReplayAfterSplitCanCloseChildWithoutNewCall()throws Exception{
        var parent=packet(2,1);var previous=session(Set.of(key(parent)));for(var child:ExtractionBatches.splitFailed(parent))previous.model("EXTRACT",child);
        var next=new RunSession(previous.model,Map.of("unlimitedModelCalls",true,"priorSteps",previous.steps),"local");var children=failAndSplit(next,parent);
        assertEquals(1L,next.diagnostics().get("unresolvedFailures"));for(var child:children)next.model("EXTRACT",child);
        assertEquals(1,next.modelCalls);assertEquals(2,next.resumedSteps);assertEquals(0L,next.diagnostics().get("unresolvedFailures"));
    }
}

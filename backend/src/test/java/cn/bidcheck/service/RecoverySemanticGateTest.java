package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

class RecoverySemanticGateTest {
    private static final String TARGET="Dd1601e9a251f3f31f88c-WORKLOAD-RESPONSE";
    private Map<String,Object> rule(String id,String field){return fields("id",id,"predicate","DECLARATION","subject","","field",field,"expected","未超出最大承接数量","parameters",Map.of());}
    private Map<String,Object> fact(String field,String value,String basis){return fields("kind","DECLARATION","subject","","field",field,"value",value,"basis",basis,"scope","CURRENT_PROJECT","sourceIds",List.of("Sa7e6cb0f0dfbb7808607"));}
    private Finding finding(List<Map<String,Object>> facts){
        Checkpoint cp=new Checkpoint("5f8bcc61-e8f5-3ad6-851f-a93408ae75c1","人员要求","人员","HIGH","SEMANTIC_RESPONSE","commitment","EQ","未超出最大承接数量","",true,true,true,null);
        return new Finding("finding","run",cp,new Evaluation("UNDETERMINED","存在未完成条件","",List.of(),0,"RULE"),Map.of("atomicCheck",fields(
            "plan",fields("rules",List.of(rule(TARGET,"commitment"),rule("INDEPENDENT","other"))),"facts",facts,
            "conditions",List.of(fields("id",TARGET,"status","UNDETERMINED","gaps",List.of(fields("code","CONTENT_EVIDENCE_REQUIRED"))),fields("id","INDEPENDENT","status","UNDETERMINED")),
            "gaps",List.of(fields("code","CONTENT_EVIDENCE_REQUIRED")))));
    }
    private Map<String,Object> context(String... targets){return fields("recoveryTargetObligationIds",List.of(targets));}
    private List<Map<String,Object>> consumed(Finding f,String... targets){return List.of(fields("strategy","TARGETED_EVIDENCE","status","NO_PROGRESS","semanticConsumedInputs",RecoverySemanticGate.inputs(f,context(targets),"engine-v1")));}
    private Map<String,Object> gate(Finding f,Map<String,Object> context,List<Map<String,Object>> attempts){return RecoverySemanticGate.inspect(f,"TARGETED_EVIDENCE",context,attempts,"engine-v1");}

    @Test void firstCapabilityIsAllowedButRawVisualReceiptDoesNotReopenConsumedEvidence(){
        Finding f=finding(List.of(fact("commitment","未超出最大数量","人员承诺书")));
        assertEquals(true,gate(f,context(TARGET),List.of()).get("allowed"));
        var changed=context(TARGET);changed.put("visualObservations",List.of(fields("page",99,"label","社保记录","value","新观察")));
        changed.put("recoveryDependencyRevision","a-new-global-revision");
        var result=gate(f,changed,consumed(f,TARGET));assertEquals(false,result.get("allowed"));
        assertEquals("TARGET_SEMANTIC_INPUT_CONSUMED",result.get("reasonCode"));assertEquals(false,result.get("businessProof"));
    }

    @Test void explanationChangeAndUnrelatedFactCannotWakeTarget(){
        Finding f=finding(List.of(fact("commitment","未超出最大数量","人员承诺书")));
        Finding after=finding(List.of(fact("commitment","未超出最大数量","明确承诺人员没有超限"),fact("other","真实但无关的新事实","其他页面")));
        assertEquals(false,gate(after,context(TARGET),consumed(f,TARGET)).get("allowed"));
    }

    @Test void relevantNewFactAndRuleVersionCanReopenConsumedTarget(){
        Finding f=finding(List.of()),changed=finding(List.of(fact("commitment","未超出最大数量","人员承诺书")));
        assertEquals(true,gate(changed,context(TARGET),consumed(f,TARGET)).get("allowed"));
        assertEquals(true,RecoverySemanticGate.inspect(f,"TARGETED_EVIDENCE",context(TARGET),consumed(f,TARGET),"engine-v2").get("allowed"));
    }

    @Test void contraryFactWakesTheTargetButRemovingOldProofOnlyAllowsLocalRevalidation(){
        Finding old=finding(List.of(fact("commitment","未超出最大数量","人员承诺书")));
        Finding contrary=finding(List.of(fact("commitment","已超出最大数量","补充声明")));
        assertEquals(true,gate(contrary,context(TARGET),consumed(old,TARGET)).get("allowed"),"new counterevidence must not be suppressed");
        Finding removed=finding(List.of());
        assertEquals(false,gate(removed,context(TARGET),consumed(old,TARGET)).get("allowed"),"losing old proof alone is not a reason to repeat model calls");
        assertEquals(true,RecoverySemanticGate.inspect(removed,"RECOMPUTE_SHARED_EVIDENCE",context(TARGET),consumed(old,TARGET),"engine-v1").get("allowed"),"local revalidation/revocation remains permitted");
    }

    @Test void consumedTargetDoesNotStopIndependentTargetOrLocalRevalidation(){
        Finding f=finding(List.of());var result=gate(f,context(TARGET,"INDEPENDENT"),consumed(f,TARGET));
        assertEquals(true,result.get("allowed"));assertEquals(List.of("INDEPENDENT"),result.get("eligibleTargetIds"));
        var scoped=context(TARGET,"INDEPENDENT");RecoverySemanticGate.applyTargets(scoped,result);assertEquals(List.of("INDEPENDENT"),scoped.get("recoveryTargetObligationIds"));
        assertEquals(true,RecoverySemanticGate.inspect(f,"RECOMPUTE_SHARED_EVIDENCE",context(TARGET),consumed(f,TARGET),"engine-v1").get("allowed"));
    }

    @Test void policyGateDoesNotClaimBusinessCompletionOrEraseExecutionResponsibility(){
        Finding f=finding(List.of());var transition=RecoveryTransition.afterAttempt(f,f,"VERIFY_SOURCE_PAGES",fields("strategy","TARGETED_EVIDENCE","status","QUEUED"),false,false,true,List.of());
        RecoveryTransition.applySemanticGate(transition,gate(f,context(TARGET),consumed(f,TARGET)));
        assertEquals("BLOCKED_NO_PROGRESS",transition.get("status"));assertEquals(false,transition.get("businessComplete"));assertEquals("UNDETERMINED",f.evaluation().status());
        assertEquals("TARGET_SEMANTIC_INPUT_CONSUMED",transition.get("reasonCode"));
    }

    @Test void trustedSourceAndValidatorChangesReopenButRawInputRevisionDoesNot(){
        Finding f=finding(List.of());Map<String,Object> atomic=new LinkedHashMap<>(atomic(f));
        atomic.put("recoveryDependencies",fields("sourceRevision","source-v1","validationIdentity","validator-v1","inputRevision","raw-v1"));
        Finding original=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),Map.of("atomicCheck",atomic));
        var history=consumed(original,TARGET);
        for(String field:List.of("sourceRevision","validationIdentity")){
            Map<String,Object> changed=new LinkedHashMap<>(atomic);Map<String,Object> dependencies=new LinkedHashMap<>(map(atomic.get("recoveryDependencies")));dependencies.put(field,"changed");changed.put("recoveryDependencies",dependencies);
            Finding refreshed=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),Map.of("atomicCheck",changed));
            assertEquals(true,gate(refreshed,context(TARGET),history).get("allowed"),field);
        }
        Map<String,Object> changed=new LinkedHashMap<>(atomic);changed.put("recoveryDependencies",fields("sourceRevision","source-v1","validationIdentity","validator-v1","inputRevision","raw-v2"));
        assertEquals(false,gate(new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),Map.of("atomicCheck",changed)),context(TARGET),history).get("allowed"));
    }

    @Test void sourceBindingCorrectionIsAnExecutableInputChangeEvenWithSameRuleText(){
        Finding f=finding(List.of());Map<String,Object> before=new LinkedHashMap<>(atomic(f)),after=new LinkedHashMap<>(atomic(f));
        Map<String,Object> first=rule(TARGET,"commitment"),second=rule(TARGET,"commitment");
        first.put("parameters",fields("requirementSourceContext",fields("identity",fields("documentHash","tender","location",70,"sourceHash","old-range")),"requirementBindings",fields("subject",fields("value","上述人员","start",0,"end",53))));
        second.put("parameters",fields("requirementSourceContext",fields("identity",fields("documentHash","tender","location",70,"sourceHash","corrected-range")),"requirementBindings",fields("subject",fields("value","上述人员","start",0,"end",205))));
        before.put("plan",fields("rules",List.of(first)));after.put("plan",fields("rules",List.of(second)));
        Finding old=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),Map.of("atomicCheck",before)),corrected=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),Map.of("atomicCheck",after));
        assertEquals(true,gate(corrected,context(TARGET),consumed(old,TARGET)).get("allowed"));
    }
    @Test void reorderedSourceReferencesCannotWakeConsumedTarget(){
        Map<String,Object> first=fact("commitment","未超出最大数量","人员承诺书");first.put("sourceIds",List.of("S1","S2"));
        Map<String,Object> reordered=new LinkedHashMap<>(first);reordered.put("sourceIds",List.of("S2","S1","S2"));
        Finding before=finding(List.of(first)),after=finding(List.of(reordered));
        assertEquals(false,gate(after,context(TARGET),consumed(before,TARGET)).get("allowed"));
    }
}

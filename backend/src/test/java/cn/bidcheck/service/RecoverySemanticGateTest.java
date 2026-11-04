package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

class RecoverySemanticGateTest {
    @Test void consumedReadChildrenLeaveUnvisitedSiblingAvailableAndNewParentProofReopens()throws Exception{
        Map<String,Object> rule=cn.bidcheck.verification.engine.Ticket09ReadTargetTest.scoreMap();String parent=text(rule,"id"),read=parent+"-READ-TITLE",other=parent+"-READ-SOCIAL";
        Finding f=finding(Collections.emptyList());Map<String,Object> a=new LinkedHashMap<>(atomic(f));a.put("plan",fields("rules",Collections.singletonList(rule)));
        a.put("conditions",Collections.singletonList(fields("id",parent,"status","UNDETERMINED","gaps",Collections.singletonList(fields("code","COMMON_PROOF_REQUIRED")))));
        a.put("agentInvestigation",fields("requestId","synthetic-read-request","status","NO_PROGRESS","code","REPEATED_ACTION_WITHOUT_NEW_EVIDENCE","failedToolCount",0,
            "targetReceiptAuthority","CURRENT_SERVER_PROOF","remainingTargetObligationIds",Arrays.asList(read,other),"exhaustedTargetObligationIds",Collections.emptyList(),
            "targetProgressLedger",Arrays.asList(fields("targetObligationId",read,"visited",true,"navigationCount",3),fields("targetObligationId",other,"visited",false))));
        Finding saved=capture(new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),fields("atomicCheck",a)),Collections.emptyMap());
        Map<String,Object> result=gate(saved,context(parent),Collections.emptyList());
        assertEquals(Collections.singletonList(read),result.get("blockedTargetIds"));assertEquals(Collections.singletonList(other),result.get("eligibleTargetIds"));
        Map<String,Object> changed=new LinkedHashMap<>(atomic(saved));changed.put("conditions",Collections.singletonList(fields("id",parent,"status","UNDETERMINED","gaps",Collections.singletonList(fields("code","COMMON_PROOF_REQUIRED")),"proof",fields("factIds",Collections.singletonList("new-verified-parent-fact")))));
        assertEquals(Collections.emptyList(),gate(new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),fields("atomicCheck",changed)),context(parent),Collections.emptyList()).get("blockedTargetIds"));
    }
    private Finding investigated(Finding f,String code,boolean visited){
        Map<String,Object> a=new LinkedHashMap<>(atomic(f));
        a.put("agentInvestigation",fields("requestId","synthetic-first-request","status","NO_PROGRESS","code",code,"failedToolCount",0,
            "targetReceiptAuthority","CURRENT_SERVER_PROOF","remainingTargetObligationIds",Collections.singletonList(TARGET),
            "targetProgressLedger",Collections.singletonList(fields("targetObligationId",TARGET,"visited",visited,"navigationCount",3,"acceptedFactCount",0)),
            "exhaustedTargetObligationIds",Collections.emptyList()));
        return new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),fields("atomicCheck",a));
    }
    private Finding capture(Finding f,Map<String,Object> context)throws Exception{
        java.lang.reflect.Method m=RecoverySemanticGate.class.getDeclaredMethod("captureFirstRound",Finding.class,Map.class,String.class);
        m.setAccessible(true);return (Finding)m.invoke(null,f,context,"engine-v1");
    }
    @Test void actualReadOnlyFirstRoundStopsSameInputEvenWithoutSemanticExhaustion()throws Exception{
        Finding first=investigated(finding(Collections.emptyList()),"REPEATED_ACTION_WITHOUT_NEW_EVIDENCE",true);
        Finding saved=capture(first,context(TARGET));
        assertEquals(false,gate(saved,context(TARGET),Collections.emptyList()).get("allowed"));
        assertEquals("UNDETERMINED",saved.evaluation().status());assertEquals(atomic(first).get("gaps"),atomic(saved).get("gaps"));
        assertEquals(Collections.emptyList(),map(atomic(saved).get("agentInvestigation")).get("exhaustedTargetObligationIds"));
        Map<String,Object> changed=new LinkedHashMap<>(atomic(saved));changed.put("facts",Collections.singletonList(fact("commitment","新的相关事实","原文")));
        Finding newEvidence=new Finding(saved.id(),saved.runId(),saved.checkpoint(),saved.evaluation(),fields("atomicCheck",changed));
        assertEquals(true,gate(newEvidence,context(TARGET),Collections.emptyList()).get("allowed"));
        assertEquals(true,gate(saved,context("INDEPENDENT"),Collections.emptyList()).get("allowed"));
        assertEquals(true,RecoverySemanticGate.inspect(saved,"VERIFY_SOURCE_PAGES",context(TARGET),Collections.emptyList(),"engine-v1").get("allowed"));
    }
    @Test void timeoutUnvisitedAndChangedRuleCannotBeDisguisedAsConsumedText()throws Exception{
        for(Finding first:Arrays.asList(investigated(finding(Collections.emptyList()),"INVESTIGATION_TIMEOUT",true),investigated(finding(Collections.emptyList()),"REPEATED_ACTION_WITHOUT_NEW_EVIDENCE",false)))
            assertEquals(true,gate(capture(first,context(TARGET)),context(TARGET),Collections.emptyList()).get("allowed"));
        Finding saved=capture(investigated(finding(Collections.emptyList()),"REPEATED_ACTION_WITHOUT_NEW_EVIDENCE",true),context(TARGET));
        assertEquals(true,RecoverySemanticGate.inspect(saved,"TARGETED_EVIDENCE",context(TARGET),Collections.emptyList(),"new-policy").get("allowed"));
        Map<String,Object> recovery=context(TARGET);recovery.put("recoveryStrategy","TARGETED_EVIDENCE");
        assertFalse(atomic(capture(investigated(finding(Collections.emptyList()),"REPEATED_ACTION_WITHOUT_NEW_EVIDENCE",true),recovery)).containsKey("firstRoundSemanticInputs"));
    }
    private static final String TARGET="Dd1601e9a251f3f31f88c-WORKLOAD-RESPONSE";
    private Map<String,Object> rule(String id,String field){return fields("id",id,"predicate","DECLARATION","subject","","field",field,"expected","未超出最大承接数量","parameters",cn.bidcheck.compat.Java8Api.mapOf());}
    private Map<String,Object> fact(String field,String value,String basis){return fields("kind","DECLARATION","subject","","field",field,"value",value,"basis",basis,"scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf("Sa7e6cb0f0dfbb7808607"));}
    private Finding finding(List<Map<String,Object>> facts){
        Checkpoint cp=new Checkpoint("5f8bcc61-e8f5-3ad6-851f-a93408ae75c1","人员要求","人员","HIGH","SEMANTIC_RESPONSE","commitment","EQ","未超出最大承接数量","",true,true,true,null);
        return new Finding("finding","run",cp,new Evaluation("UNDETERMINED","存在未完成条件","",cn.bidcheck.compat.Java8Api.listOf(),0,"RULE"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields(
            "plan",fields("rules",cn.bidcheck.compat.Java8Api.listOf(rule(TARGET,"commitment"), rule("INDEPENDENT","other"))),"facts",facts,
            "conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id",TARGET,"status","UNDETERMINED","gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","CONTENT_EVIDENCE_REQUIRED"))), fields("id","INDEPENDENT","status","UNDETERMINED")),
            "gaps",cn.bidcheck.compat.Java8Api.listOf(fields("code","CONTENT_EVIDENCE_REQUIRED")))));
    }
    private Map<String,Object> context(String... targets){return fields("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf(targets));}
    private List<Map<String,Object>> consumed(Finding f,String... targets){return cn.bidcheck.compat.Java8Api.listOf(fields("strategy","TARGETED_EVIDENCE","status","NO_PROGRESS","semanticConsumedInputs",RecoverySemanticGate.inputs(f,context(targets),"engine-v1")));}
    private Map<String,Object> gate(Finding f,Map<String,Object> context,List<Map<String,Object>> attempts){return RecoverySemanticGate.inspect(f,"TARGETED_EVIDENCE",context,attempts,"engine-v1");}

    @Test void unchangedSharedRecomputationIsConsumedOnlyBySameStrategy(){
        Finding f=finding(Collections.emptyList());String strategy="RECOMPUTE_SHARED_EVIDENCE";
        List<Map<String,Object>> attempts=Collections.singletonList(fields("strategy",strategy,"status","NO_PROGRESS","semanticConsumedInputs",RecoverySemanticGate.inputs(f,context(TARGET),"v1")));
        assertEquals(false,RecoverySemanticGate.inspect(f,strategy,context(TARGET),attempts,"v1").get("allowed"));
        Finding changed=finding(Collections.singletonList(fact("commitment","超出最大数量","声明")));
        assertEquals(true,RecoverySemanticGate.inspect(changed,strategy,context(TARGET),attempts,"v1").get("allowed"));
    }
    @Test void coverFactDoesNotWakeAnotherDocumentType(){
        Map<String,Object> cover=fields("kind","MATERIAL","subject","","field","material","value","投标文件（技术部分）","scope","CURRENT_PROJECT");
        Map<String,Object> event=fields("recordType","FACT","itemId","new-cover","fact",cover,"sources",Collections.singletonList(fields("id","page-of-cover")));
        Map<String,Object> target=fields("predicate","MATERIAL","field","material","targetExpected","法定代表人身份证明");
        assertFalse(OrdinaryRecoveryDependencies.matches(fields("target",target),event));
        target.put("targetExpected","第二分册 技术投标文件");assertTrue(OrdinaryRecoveryDependencies.matches(fields("target",target),event));
        target.put("targetExpected","第三分册 报价文件");assertFalse(OrdinaryRecoveryDependencies.matches(fields("target",target),event));
    }
    @Test void firstCapabilityIsAllowedButRawVisualReceiptDoesNotReopenConsumedEvidence(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf(fact("commitment","未超出最大数量","人员承诺书")));
        assertEquals(true,gate(f,context(TARGET),cn.bidcheck.compat.Java8Api.listOf()).get("allowed"));
        java.util.Map<java.lang.String, java.lang.Object> changed=context(TARGET);changed.put("visualObservations",cn.bidcheck.compat.Java8Api.listOf(fields("page",99,"label","社保记录","value","新观察")));
        changed.put("recoveryDependencyRevision","a-new-global-revision");
        java.util.Map<java.lang.String, java.lang.Object> result=gate(f,changed,consumed(f,TARGET));assertEquals(false,result.get("allowed"));
        assertEquals("TARGET_SEMANTIC_INPUT_CONSUMED",result.get("reasonCode"));assertEquals(false,result.get("businessProof"));
    }

    @Test void explanationChangeAndUnrelatedFactCannotWakeTarget(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf(fact("commitment","未超出最大数量","人员承诺书")));
        Finding after=finding(cn.bidcheck.compat.Java8Api.listOf(fact("commitment","未超出最大数量","明确承诺人员没有超限"), fact("other","真实但无关的新事实","其他页面")));
        assertEquals(false,gate(after,context(TARGET),consumed(f,TARGET)).get("allowed"));
    }

    @Test void relevantNewFactAndRuleVersionCanReopenConsumedTarget(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf()),changed=finding(cn.bidcheck.compat.Java8Api.listOf(fact("commitment","未超出最大数量","人员承诺书")));
        assertEquals(true,gate(changed,context(TARGET),consumed(f,TARGET)).get("allowed"));
        assertEquals(true,RecoverySemanticGate.inspect(f,"TARGETED_EVIDENCE",context(TARGET),consumed(f,TARGET),"engine-v2").get("allowed"));
    }

    @Test void contraryFactWakesTheTargetButRemovingOldProofOnlyAllowsLocalRevalidation(){
        Finding old=finding(cn.bidcheck.compat.Java8Api.listOf(fact("commitment","未超出最大数量","人员承诺书")));
        Finding contrary=finding(cn.bidcheck.compat.Java8Api.listOf(fact("commitment","已超出最大数量","补充声明")));
        assertEquals(true,gate(contrary,context(TARGET),consumed(old,TARGET)).get("allowed"),"new counterevidence must not be suppressed");
        Finding removed=finding(cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(false,gate(removed,context(TARGET),consumed(old,TARGET)).get("allowed"),"losing old proof alone is not a reason to repeat model calls");
        assertEquals(true,RecoverySemanticGate.inspect(removed,"RECOMPUTE_SHARED_EVIDENCE",context(TARGET),consumed(old,TARGET),"engine-v1").get("allowed"),"local revalidation/revocation remains permitted");
    }

    @Test void consumedTargetDoesNotStopIndependentTargetOrLocalRevalidation(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf());java.util.Map<java.lang.String, java.lang.Object> result=gate(f,context(TARGET,"INDEPENDENT"),consumed(f,TARGET));
        assertEquals(true,result.get("allowed"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("INDEPENDENT"),result.get("eligibleTargetIds"));
        java.util.Map<java.lang.String, java.lang.Object> scoped=context(TARGET,"INDEPENDENT");RecoverySemanticGate.applyTargets(scoped,result);assertEquals(cn.bidcheck.compat.Java8Api.listOf("INDEPENDENT"),scoped.get("recoveryTargetObligationIds"));
        assertEquals(true,RecoverySemanticGate.inspect(f,"RECOMPUTE_SHARED_EVIDENCE",context(TARGET),consumed(f,TARGET),"engine-v1").get("allowed"));
    }

    @Test void policyGateDoesNotClaimBusinessCompletionOrEraseExecutionResponsibility(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf());java.util.Map<java.lang.String, java.lang.Object> transition=RecoveryTransition.afterAttempt(f,f,"VERIFY_SOURCE_PAGES",fields("strategy","TARGETED_EVIDENCE","status","QUEUED"),false,false,true,cn.bidcheck.compat.Java8Api.listOf());
        RecoveryTransition.applySemanticGate(transition,gate(f,context(TARGET),consumed(f,TARGET)));
        assertEquals("BLOCKED_NO_PROGRESS",transition.get("status"));assertEquals(false,transition.get("businessComplete"));assertEquals("UNDETERMINED",f.evaluation().status());
        assertEquals("TARGET_SEMANTIC_INPUT_CONSUMED",transition.get("reasonCode"));
    }

    @Test void trustedSourceAndValidatorChangesReopenButRawInputRevisionDoesNot(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf());Map<String,Object> atomic=new LinkedHashMap<>(atomic(f));
        atomic.put("recoveryDependencies",fields("sourceRevision","source-v1","validationIdentity","validator-v1","inputRevision","raw-v1"));
        Finding original=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", atomic));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> history=consumed(original,TARGET);
        for(String field:cn.bidcheck.compat.Java8Api.listOf("sourceRevision", "validationIdentity")){
            Map<String,Object> changed=new LinkedHashMap<>(atomic);Map<String,Object> dependencies=new LinkedHashMap<>(map(atomic.get("recoveryDependencies")));dependencies.put(field,"changed");changed.put("recoveryDependencies",dependencies);
            Finding refreshed=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", changed));
            assertEquals(true,gate(refreshed,context(TARGET),history).get("allowed"),field);
        }
        Map<String,Object> changed=new LinkedHashMap<>(atomic);changed.put("recoveryDependencies",fields("sourceRevision","source-v1","validationIdentity","validator-v1","inputRevision","raw-v2"));
        assertEquals(false,gate(new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", changed)),context(TARGET),history).get("allowed"));
    }

    @Test void sourceBindingCorrectionIsAnExecutableInputChangeEvenWithSameRuleText(){
        Finding f=finding(cn.bidcheck.compat.Java8Api.listOf());Map<String,Object> before=new LinkedHashMap<>(atomic(f)),after=new LinkedHashMap<>(atomic(f));
        Map<String,Object> first=rule(TARGET,"commitment"),second=rule(TARGET,"commitment");
        first.put("parameters",fields("requirementSourceContext",fields("identity",fields("documentHash","tender","location",70,"sourceHash","old-range")),"requirementBindings",fields("subject",fields("value","上述人员","start",0,"end",53))));
        second.put("parameters",fields("requirementSourceContext",fields("identity",fields("documentHash","tender","location",70,"sourceHash","corrected-range")),"requirementBindings",fields("subject",fields("value","上述人员","start",0,"end",205))));
        before.put("plan",fields("rules",cn.bidcheck.compat.Java8Api.listOf(first)));after.put("plan",fields("rules",cn.bidcheck.compat.Java8Api.listOf(second)));
        Finding old=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", before)),corrected=new Finding(f.id(),f.runId(),f.checkpoint(),f.evaluation(),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", after));
        assertEquals(true,gate(corrected,context(TARGET),consumed(old,TARGET)).get("allowed"));
    }
    @Test void reorderedSourceReferencesCannotWakeConsumedTarget(){
        Map<String,Object> first=fact("commitment","未超出最大数量","人员承诺书");first.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("S1", "S2"));
        Map<String,Object> reordered=new LinkedHashMap<>(first);reordered.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("S2", "S1", "S2"));
        Finding before=finding(cn.bidcheck.compat.Java8Api.listOf(first)),after=finding(cn.bidcheck.compat.Java8Api.listOf(reordered));
        assertEquals(false,gate(after,context(TARGET),consumed(before,TARGET)).get("allowed"));
    }
}

package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;

class AgentGroupRecoveryTest {
    private final AgentBusinessGroupReuse.Membership group=new AgentBusinessGroupReuse.Membership("group","leader",cn.bidcheck.compat.Java8Api.listOf("leader", "follower"),cn.bidcheck.compat.Java8Api.mapOf());
    private Checkpoint checkpoint(String id){return new Checkpoint(id,"营业执照","资格检查","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS","投标人须提交有效营业执照","",true,true,true,null);}
    private Finding before(String id){return new Finding("finding-"+id,"run",checkpoint(id),new Evaluation("UNDETERMINED","模型调查失败","",cn.bidcheck.compat.Java8Api.listOf(),0,"AGENT_LED_REVIEW"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields(
        "complete",false,"conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","conditionRole","REQUIREMENT","status","UNDETERMINED","predicate","MATERIAL","field","营业执照")),
        "agentInvestigation",fields("status","FAILED","errorCode","WORKER_DISCONNECTED"),"gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "AGENT_WORKER_FAILED")))));}
    private final Evidence evidence=new Evidence("bid","投标",12,"PAGE","营业执照：甲单位","p12",1);
    private Finding passed(Finding before){return new Finding(before.id(),before.runId(),before.checkpoint(),new Evaluation("PASS","本地规则已核验全部义务","",cn.bidcheck.compat.Java8Api.listOf(evidence),1,"RULE"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields(
        "complete",true,"gaps",cn.bidcheck.compat.Java8Api.listOf(),"conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","R1","conditionRole","REQUIREMENT","status","PASS","predicate","MATERIAL","field","营业执照","gaps",cn.bidcheck.compat.Java8Api.listOf(),"evidences",cn.bidcheck.compat.Java8Api.listOf(evidence))))));}
    private Map<String,Object> snapshot(String id,String hash){return fields("id",id,"checkpointId","leader","type",AgentBusinessGroupReuse.FACT_SNAPSHOT,"status","COMPLETED","schemaVersion","schema","engineVersion","engine","modelIdentity","model",
        "output",fields("sources",cn.bidcheck.compat.Java8Api.listOf(fields("id","S1","projectId","p","documentId","bid","documentHash",hash,"documentVersion",1,"parserFingerprint","parser","sourceFingerprint","page12")),
            "sourceIds",cn.bidcheck.compat.Java8Api.listOf("S1"),"facts",cn.bidcheck.compat.Java8Api.listOf(fields("id","F"+id,"kind","MATERIAL","subject","甲单位","field","营业执照","rawValue","营业执照：甲单位","value","营业执照","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf("S1"),"attributes",cn.bidcheck.compat.Java8Api.mapOf()))));}
    private Map<String,Object> context(List<Map<String,Object>> persisted){return fields("priorSteps",cn.bidcheck.compat.Java8Api.listCopyOf(persisted),"reviewMode",CheckReviewMode.AGENT,
        "stepObserver",(Consumer<Map<String,Object>>)step->persisted.add(new LinkedHashMap<>(step)));}
    private AgentGroupRecovery.Outcome execute(Finding before,List<Map<String,Object>> persisted,Map<String,Object> snapshot,java.util.function.Function<Map<String,Object>,Finding> evaluate){
        return AgentGroupRecovery.execute(before,group,cn.bidcheck.compat.Java8Api.listOf(before("leader"), before),id->cn.bidcheck.compat.Java8Api.listOf(snapshot),context(persisted),"policy",evaluate);
    }

    @Test void restartDoesNotRepeatConsumedReceiptsOrModelInvestigation(){
        Finding before=before("follower");List<Map<String,Object>> persisted=new ArrayList<>();AtomicInteger local=new AtomicInteger();
        cn.bidcheck.service.AgentGroupRecovery.Outcome first=execute(before,persisted,snapshot("snapshot1","hash"),ctx->{local.incrementAndGet();assertEquals(false,ctx.get("recoveryModelAllowed"));assertEquals("EVIDENCE_RECOMPUTE",ctx.get("recoveryMode"));
            assertFalse(((java.util.function.BooleanSupplier)ctx.get("modelAttemptPermit")).getAsBoolean());return before;});
        assertTrue(first.attempted());assertFalse(first.published());assertSame(before,first.finding());persisted.add(first.consumedStep());
        cn.bidcheck.service.AgentGroupRecovery.Outcome restarted=execute(before,persisted,snapshot("resaved-snapshot-id","hash"),ctx->{fail("a new snapshot ID is not a new observation");return before;});
        assertFalse(restarted.attempted());assertEquals(1,local.get());assertSame(before,restarted.finding());
        assertEquals(0,first.audit().get("newModelCalls"));assertFalse(restarted.context().containsKey("recoveryChangedSourceIds"));
    }
    @Test void changedFrozenSourceCanResumeAfterAConsumedNoProgressResult(){
        Finding before=before("follower");List<Map<String,Object>> persisted=new ArrayList<>();
        cn.bidcheck.service.AgentGroupRecovery.Outcome first=execute(before,persisted,snapshot("snapshot","hash1"),ctx->before);persisted.add(first.consumedStep());
        cn.bidcheck.service.AgentGroupRecovery.Outcome changed=execute(before,persisted,snapshot("snapshot","hash2"),ctx->{assertEquals(cn.bidcheck.compat.Java8Api.listOf("S1"),ctx.get("recoveryChangedSourceIds"));return passed(before);});
        assertTrue(changed.attempted());assertTrue(changed.published());assertEquals("PASS",changed.finding().evaluation().status());
        assertNotEquals(first.consumedStep().get("id"),changed.consumedStep().get("id"));
    }
    @Test void clearingAnAgentErrorOrAddingOnlyCitationsCannotBecomeAnImprovement(){
        Finding before=before("follower");
        Finding cleared=new Finding(before.id(),before.runId(),before.checkpoint(),new Evaluation("UNDETERMINED","需要补材料","",cn.bidcheck.compat.Java8Api.listOf(evidence),0,"RULE"),cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields(
            "complete",false,"conditions",CheckResolution.atomic(before).get("conditions"),"gaps",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "EVIDENCE_MISSING")))));
        cn.bidcheck.service.AgentGroupRecovery.Outcome result=execute(before,new ArrayList<>(),snapshot("snapshot","hash"),ctx->cleared);
        assertTrue(result.attempted());assertFalse(result.published());assertSame(before,result.finding());
        assertEquals("WORKER_DISCONNECTED",text(CheckResolution.map(CheckResolution.atomic(result.finding()).get("agentInvestigation")),"errorCode"));
        assertEquals(false,CheckResolution.map(result.consumedStep().get("output")).get("businessProofProgress"));
    }
    @Test void statusWithoutCompletedBusinessProofCannotPublish(){
        Finding before=before("follower");Finding unsupported=new Finding(before.id(),before.runId(),before.checkpoint(),new Evaluation("PASS","找到相关材料","",cn.bidcheck.compat.Java8Api.listOf(evidence),1,"RULE"),
            cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", fields("complete",true,"gaps",cn.bidcheck.compat.Java8Api.listOf(),"conditions",cn.bidcheck.compat.Java8Api.listOf(fields("id","support","conditionRole","SUPPORT","status","PASS","evidences",cn.bidcheck.compat.Java8Api.listOf(evidence))))));
        cn.bidcheck.service.AgentGroupRecovery.Outcome result=execute(before,new ArrayList<>(),snapshot("snapshot","hash"),ctx->unsupported);
        assertFalse(result.published());assertSame(before,result.finding());
    }
    @Test void aCrashBeforeConsumptionStillAllowsOneLocalReplay(){
        Finding before=before("follower");List<Map<String,Object>> persisted=new ArrayList<>();
        cn.bidcheck.service.AgentBusinessGroupReuse.EvidenceReceipt receipt=AgentBusinessGroupReuse.evidenceReceipt(group,"follower",before("leader"),cn.bidcheck.compat.Java8Api.listOf(snapshot("snapshot","hash")));
        persisted.add(receipt.step());
        cn.bidcheck.service.AgentGroupRecovery.Outcome result=execute(before,persisted,snapshot("snapshot","hash"),ctx->passed(before));
        assertTrue(result.attempted());assertTrue(result.published());assertEquals(1,persisted.size(),"the delivery itself is not duplicated");
    }
    @Test void newReceiptIsCarriedOnlyToExistingMissingTargets(){
        Finding before=before("follower");cn.bidcheck.service.AgentGroupRecovery.Outcome result=execute(before,new ArrayList<>(),snapshot("snapshot","hash"),ctx->before);
        Map<String,Object> old=fields("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("R1"),"recoveryModelAllowed",true,"recoveryDependencyTrigger",fields("semanticEvidenceKeys",cn.bidcheck.compat.Java8Api.listOf("already-scoped")));
        AgentGroupRecovery.carryChangedInputs(result,old);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("R1"),old.get("recoveryTargetObligationIds"));assertEquals(true,old.get("recoveryModelAllowed"));
        assertEquals(2,((Collection<?>)CheckResolution.map(old.get("recoveryDependencyTrigger")).get("semanticEvidenceKeys")).size());
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(text(old,"recoveryDependencyRevision")));
    }
}

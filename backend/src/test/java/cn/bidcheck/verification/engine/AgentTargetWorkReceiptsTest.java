package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTargetWorkReceiptsTest {
    private Rule rule(String id){return new Rule(id,"证书证明","CERTIFICATE","李明","证书","EXISTS","A类","",cn.bidcheck.compat.Java8Api.mapOf(),"有效证书","",cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> rejected(){return linked("status","REJECTED","error","FACT_OUTSIDE_TARGET","acceptedFactIds",cn.bidcheck.compat.Java8Api.listOf(),"targetCheckCompleted",false,"businessCheckCompleted",false);}
    private Map<String,Object> attach(String tool,Map<String,Object> result,String target,AgentTargetWorkflow workflow,String revision){
        return AgentTargetWorkReceipts.complete(tool,cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", target),result,cn.bidcheck.compat.Java8Api.listOf(rule("A"), rule("B")),workflow,r->"person:"+r.id(),r->revision+":"+r.id());
    }
    private Map<String,Object> first(Map<String,Object> result){return RuleEvaluator.maps(result.get("semanticWorkItems")).get(0);}

    @Test void inferredEarlyRejectionGetsOnlyCalledTargetAndRemainsRejected(){
        Map<String,Object> result=rejected();java.util.Map<java.lang.String, java.lang.Object> audit=attach("submit_candidate_fact",result,"B",new AgentTargetWorkflow(),"r1");
        assertEquals("REJECTED",result.get("status"));assertEquals("FACT_OUTSIDE_TARGET",result.get("error"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("acceptedFactIds"));assertEquals(false,result.get("targetCheckCompleted"));assertEquals(false,result.get("businessCheckCompleted"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("B"),result.get("affectedTargetObligationIds"));assertEquals(1,RuleEvaluator.maps(result.get("semanticWorkItems")).size());
        assertEquals("B",first(result).get("targetObligationId"));assertEquals("r1:B",first(result).get("evidenceRevision"));
        assertEquals("EARLY_REJECTION_SCOPED_AND_OBSERVED",audit.get("action"));assertEquals(false,first(result).get("terminalForRevision"));
    }
    @Test void repeatedEarlyRejectionExhaustsOnlySameTargetAndRelevantRevisionReopens(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();java.util.Map<java.lang.String, java.lang.Object> first=rejected();attach("submit_candidate_fact",first,"B",workflow,"r1");
        java.util.Map<java.lang.String, java.lang.Object> repeated=rejected();attach("submit_candidate_fact",repeated,"B",workflow,"r1");
        assertEquals(true,first(repeated).get("targetExhaustedForRevision"));
        assertEquals(false,workflow.state(rule("A"),"person:A","r1:A").get("targetExhaustedForRevision"));
        java.util.Map<java.lang.String, java.lang.Object> refreshed=rejected();attach("submit_candidate_fact",refreshed,"B",workflow,"r2");assertEquals(false,first(refreshed).get("targetExhaustedForRevision"));
    }
    @Test void ordinaryValidationReceiptIsNotObservedTwice(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();java.util.Map<java.lang.String, java.lang.Object> work=workflow.observe(rule("B"),"person:B","r1:B",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "FACT_OUTSIDE_TARGET")));
        java.util.Map<java.lang.String, java.lang.Object> result=rejected();result.put("semanticWorkItems",cn.bidcheck.compat.Java8Api.listOf(work));java.util.Map<java.lang.String, java.lang.Object> audit=attach("submit_candidate_fact",result,"B",workflow,"r1");
        assertEquals(work,first(result));assertEquals("EXPLICIT_SINGLE_TARGET_RECEIPT_PRESERVED",audit.get("action"));
        assertEquals(false,workflow.state(rule("B"),"person:B","r1:B").get("terminalForRevision"));
    }
    @Test void explicitForeignOrMalformedReceiptFailsClosedInsteadOfFabricatingAValidOne(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();
        for(Object invalid:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(workflow.state(rule("A"),"person:A","r1:A")), cn.bidcheck.compat.Java8Api.listOf(), cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "B", "workKey", "", "evidenceRevision", "r1:B")))){
            java.util.Map<java.lang.String, java.lang.Object> result=rejected();result.put("semanticWorkItems",invalid);java.util.Map<java.lang.String, java.lang.Object> audit=attach("submit_candidate_fact",result,"B",workflow,"r1");
            assertEquals("FAILED",result.get("status"));assertEquals("HOST_TARGET_RECEIPT_INVALID",result.get("error"));
            assertEquals("FACT_OUTSIDE_TARGET",result.get("receiptOriginalError"));assertEquals("EXPLICIT_RECEIPT_REJECTED",audit.get("action"));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("semanticWorkItems"));assertEquals(false,result.get("businessCheckCompleted"));
        }
    }
    @Test void technicalFailuresDoNotBecomeSemanticExhaustionAndUnknownTargetHasNoInventedIdentity(){
        cn.bidcheck.verification.engine.AgentTargetWorkflow workflow=new AgentTargetWorkflow();
        for(int i=0;i<3;i++){java.util.Map<java.lang.String, java.lang.Object> failure=linked("status","FAILED","error","HOST_TOOL_FAILED");attach("submit_candidate_fact",failure,"A",workflow,"r1");assertEquals(false,first(failure).get("targetExhaustedForRevision"));}
        java.util.Map<java.lang.String, java.lang.Object> invalid=linked("status","REJECTED","error","INVALID_TARGET_RECEIPT");java.util.Map<java.lang.String, java.lang.Object> audit=attach("submit_candidate_fact",invalid,"foreign",workflow,"r1");
        assertEquals("INVALID_TARGET_RECEIPT",invalid.get("error"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),invalid.get("semanticWorkItems"));assertEquals("INVALID_CALL_TARGET_NO_WORK_IDENTITY",audit.get("action"));
    }
    @Test void navigationAndRecomputeRetainGlobalHostSnapshot(){
        java.util.Map<java.lang.String, java.lang.Object> result=linked("status","COMPLETED");attach("read_section",result,"B",new AgentTargetWorkflow(),"r1");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B"),cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(result.get("semanticWorkItems")).stream().map(w->str(w,"targetObligationId"))));
        assertFalse(result.containsKey("businessCheckCompleted"));assertFalse(result.containsKey("acceptedFactIds"));
    }
}

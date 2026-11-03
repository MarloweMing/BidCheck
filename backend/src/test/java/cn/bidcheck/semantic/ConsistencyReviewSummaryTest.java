package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyReviewSummaryTest {
    Finding finding(String type,Map<String,Object> assessment){return new Finding(type,"run",new Checkpoint(type,"name","全文信息检查","HIGH",type,"x","REVIEW","x","",true,true,true,null),
        new Evaluation("UNDETERMINED","待核","",cn.bidcheck.compat.Java8Api.listOf(),0,"TEST"),assessment);}
    Map<String,Object> run(){return cn.bidcheck.compat.Java8Api.mapOf("checkMode", "RULES_RETRIEVAL", "onDemandAttemptsReserved", 4, "onDemandAttemptBudget", 24, "consistencyReviewAttemptsReserved", 2);}
    @Test void totalHttpCountsBothScopesWithoutInflatingTenderCheckpointCount(){
        cn.bidcheck.domain.Domain.Finding original=finding("SEMANTIC_RESPONSE",cn.bidcheck.compat.Java8Api.mapOf("model", cn.bidcheck.compat.Java8Api.mapOf("requested", true, "decision", "MODEL_ESCALATED", "attempts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("state", "VALID_OUTPUT", "httpStatus", 200)))));
        cn.bidcheck.domain.Domain.Finding consistency=finding("FULL_TEXT_REVIEW",cn.bidcheck.compat.Java8Api.mapOf("consistencyReview", cn.bidcheck.compat.Java8Api.mapOf("modelRequested", true, "model", cn.bidcheck.compat.Java8Api.mapOf("attempts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("state", "GUARDED_OUTPUT", "httpStatus", 200))))));
        Map<?,?> model=(Map<?,?>)OnDemandSummary.summarize(run(),cn.bidcheck.compat.Java8Api.listOf(original, consistency),1).get("onDemandModel");
        assertEquals(2,model.get("httpAttempts"));assertEquals(1,model.get("checkpointCount"));assertEquals(1,model.get("modelRequestedCheckpoints"));
        assertEquals(1,model.get("consistencyModelRequestedGroups"));assertEquals(1,model.get("consistencyHttpAttempts"));assertEquals(1,model.get("guardedOutputs"));assertEquals(0,model.get("unknownCheckpoints"));
    }
    @Test void interruptedReceiptsAreUnknownNotUnrequested(){
        cn.bidcheck.domain.Domain.Finding consistency=finding("FULL_TEXT_REVIEW",cn.bidcheck.compat.Java8Api.mapOf("consistencyReview", cn.bidcheck.compat.Java8Api.mapOf("requestOutcomeUnknown", true, "model", cn.bidcheck.compat.Java8Api.mapOf("attempts", cn.bidcheck.compat.Java8Api.listOf()))));
        Map<?,?> model=(Map<?,?>)OnDemandSummary.summarize(run(),cn.bidcheck.compat.Java8Api.listOf(consistency),0).get("onDemandModel");
        assertEquals(1,model.get("consistencyUnknownRequestGroups"));assertEquals(0,model.get("consistencyModelRequestedGroups"));assertEquals(0,model.get("httpAttempts"));assertEquals(4,model.get("attemptsReserved"));
    }
}

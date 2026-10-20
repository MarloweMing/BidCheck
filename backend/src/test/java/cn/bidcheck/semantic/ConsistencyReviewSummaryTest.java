package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyReviewSummaryTest {
    Finding finding(String type,Map<String,Object> assessment){return new Finding(type,"run",new Checkpoint(type,"name","全文信息检查","HIGH",type,"x","REVIEW","x","",true,true,true,null),
        new Evaluation("UNDETERMINED","待核","",List.of(),0,"TEST"),assessment);}
    Map<String,Object> run(){return Map.of("checkMode","RULES_RETRIEVAL","onDemandAttemptsReserved",4,"onDemandAttemptBudget",24,"consistencyReviewAttemptsReserved",2);}
    @Test void totalHttpCountsBothScopesWithoutInflatingTenderCheckpointCount(){
        var original=finding("SEMANTIC_RESPONSE",Map.of("model",Map.of("requested",true,"decision","MODEL_ESCALATED","attempts",List.of(Map.of("state","VALID_OUTPUT","httpStatus",200)))));
        var consistency=finding("FULL_TEXT_REVIEW",Map.of("consistencyReview",Map.of("modelRequested",true,"model",Map.of("attempts",List.of(Map.of("state","GUARDED_OUTPUT","httpStatus",200))))));
        Map<?,?> model=(Map<?,?>)OnDemandSummary.summarize(run(),List.of(original,consistency),1).get("onDemandModel");
        assertEquals(2,model.get("httpAttempts"));assertEquals(1,model.get("checkpointCount"));assertEquals(1,model.get("modelRequestedCheckpoints"));
        assertEquals(1,model.get("consistencyModelRequestedGroups"));assertEquals(1,model.get("consistencyHttpAttempts"));assertEquals(1,model.get("guardedOutputs"));assertEquals(0,model.get("unknownCheckpoints"));
    }
    @Test void interruptedReceiptsAreUnknownNotUnrequested(){
        var consistency=finding("FULL_TEXT_REVIEW",Map.of("consistencyReview",Map.of("requestOutcomeUnknown",true,"model",Map.of("attempts",List.of()))));
        Map<?,?> model=(Map<?,?>)OnDemandSummary.summarize(run(),List.of(consistency),0).get("onDemandModel");
        assertEquals(1,model.get("consistencyUnknownRequestGroups"));assertEquals(0,model.get("consistencyModelRequestedGroups"));assertEquals(0,model.get("httpAttempts"));assertEquals(4,model.get("attemptsReserved"));
    }
}

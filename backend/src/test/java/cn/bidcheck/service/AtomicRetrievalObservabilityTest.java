package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.semantic.OnDemandSummary;
import cn.bidcheck.verification.engine.VerificationEngine;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AtomicRetrievalObservabilityTest {
    private final Checkpoint checkpoint=new Checkpoint("c","工期要求","响应","HIGH","SEMANTIC_RESPONSE","","","", "",true,true,true,null);
    private Map<String,Object> receipt(String id,int round,String mode){
        return Map.of("id",id,"executionId",id,"type","RETRIEVE_"+round+"_bid","status","COMPLETED","output",
            Map.of("mode",mode,"round",round,"returnedHits",16,"mappedSourceCount",18,"unmappedHitCount",0,
                "diagnostics",Map.of("returned",16,"vectorSearchExecuted",mode.startsWith("HYBRID"),"embeddingCalls",1,"rerankCalls",1)));
    }
    private FocusedCheckEvaluator.Result assess(List<Map<String,Object>> steps,List<Map<String,Object>> prior){
        VerificationEngine engine=mock(VerificationEngine.class);
        when(engine.evaluate(any(),anyList(),isNull(),anyMap())).thenReturn(
            Map.of("businessDecision","UNDETERMINED","workflowState","WAIT_INPUT","complete",false,"steps",steps,"plan",Map.of("rules",List.of())));
        return new FocusedCheckEvaluator(engine).assess(checkpoint,null,null,Map.of("priorSteps",prior));
    }
    private Map<?,?> summary(Map<String,Object> assessment){
        Finding finding=new Finding("f","run",checkpoint,new Evaluation("UNDETERMINED","","",List.of(),0,"FOCUSED_ATOMIC_RULES"),assessment);
        return (Map<?,?>)OnDemandSummary.summarize(Map.of("checkMode","RULES_RETRIEVAL"),List.of(finding),1).get("retrievalEvidence");
    }
    @Test void suffixedAtomicReceiptsSurviveProjectionAndRecoveryWithoutDoubleCounting(){
        Map<String,Object> first=receipt("a",0,"HYBRID_RERANKED"),second=receipt("b",1,"HYBRID_RRF_DEGRADED");
        var result=assess(List.of(first,second),List.of(first));
        var summary=summary(result.assessment());
        assertEquals(1,summary.get("checkpointsWithRetrieval"));assertEquals(2,summary.get("searchAttempts"));
        assertEquals(2,summary.get("vectorSearches"));assertEquals(1,summary.get("followUpCheckpoints"));
        assertEquals(32,summary.get("candidateCount"));assertEquals(36,summary.get("mappedSourceCount"));
        assertEquals(1,summary.get("degradedSearches"));assertEquals(true,summary.get("telemetryComplete"));
        assertEquals(true,summary.get("sourceMappingTelemetryComplete"));assertNull(summary.get("selectedRetrievedSourceCount"));
    }
    @Test void historicalStrippedReceiptsAreUnknownRatherThanZeroCandidates(){
        var summary=summary(Map.of("atomicCheck",Map.of("complete",false),"structuredSteps",List.of(Map.of("id","a","type","RETRIEVE_0_bid","status","COMPLETED"))));
        assertEquals(1,summary.get("checkpointsWithRetrieval"));assertEquals(1,summary.get("unknownSearches"));
        assertNull(summary.get("candidateCount"));assertEquals(false,summary.get("telemetryComplete"));assertNull(summary.get("vectorSearches"));
        assertNull(summary.get("mappedSourceCount"));assertNull(summary.get("selectedRetrievedSourceCount"));
    }
    @Test void indexPreparationIsNotCountedAsVectorSearch(){
        var summary=summary(assess(List.of(receipt("a",0,"BM25_DEGRADED")),List.of()).assessment());
        assertEquals(0,summary.get("vectorSearches"));assertEquals(1,summary.get("lexicalOnlySearches"));
    }
    @Test void selectionCountsAreMeasuredPerRoundAndDeduplicated(){
        Map<String,Object> selection=Map.of("id","s","executionId","s","type","RETRIEVAL_SELECTION_0","status","COMPLETED",
            "output",Map.of("selectedRetrievedSourceCount",5));
        var summary=summary(assess(List.of(receipt("a",0,"HYBRID_RERANKED"),selection),List.of(selection)).assessment());
        assertEquals(5,summary.get("selectedRetrievedSourceCount"));assertEquals(true,summary.get("selectionTelemetryComplete"));
        assertEquals(0,summary.get("unknownSelectionRounds"));
    }
}

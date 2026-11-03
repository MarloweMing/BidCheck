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
        return cn.bidcheck.compat.Java8Api.mapOf("id", id, "executionId", id, "type", "RETRIEVE_"+round+"_bid", "status", "COMPLETED", "output", cn.bidcheck.compat.Java8Api.mapOf("mode", mode, "round", round, "returnedHits", 16, "mappedSourceCount", 18, "unmappedHitCount", 0, "diagnostics", cn.bidcheck.compat.Java8Api.mapOf("returned", 16, "vectorSearchExecuted", mode.startsWith("HYBRID"), "embeddingCalls", 1, "rerankCalls", 1)));
    }
    private FocusedCheckEvaluator.Result assess(List<Map<String,Object>> steps,List<Map<String,Object>> prior){
        VerificationEngine engine=mock(VerificationEngine.class);
        when(engine.evaluate(any(),anyList(),isNull(),anyMap())).thenReturn(
            cn.bidcheck.compat.Java8Api.mapOf("businessDecision", "UNDETERMINED", "workflowState", "WAIT_INPUT", "complete", false, "steps", steps, "plan", cn.bidcheck.compat.Java8Api.mapOf("rules", cn.bidcheck.compat.Java8Api.listOf())));
        return new FocusedCheckEvaluator(engine).assess(checkpoint,null,null,cn.bidcheck.compat.Java8Api.mapOf("priorSteps", prior));
    }
    private Map<?,?> summary(Map<String,Object> assessment){
        Finding finding=new Finding("f","run",checkpoint,new Evaluation("UNDETERMINED","","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES"),assessment);
        return (Map<?,?>)OnDemandSummary.summarize(cn.bidcheck.compat.Java8Api.mapOf("checkMode", "RULES_RETRIEVAL"),cn.bidcheck.compat.Java8Api.listOf(finding),1).get("retrievalEvidence");
    }
    @Test void suffixedAtomicReceiptsSurviveProjectionAndRecoveryWithoutDoubleCounting(){
        Map<String,Object> first=receipt("a",0,"HYBRID_RERANKED"),second=receipt("b",1,"HYBRID_RRF_DEGRADED");
        cn.bidcheck.service.FocusedCheckEvaluator.Result result=assess(cn.bidcheck.compat.Java8Api.listOf(first, second),cn.bidcheck.compat.Java8Api.listOf(first));
        java.util.Map<?, ?> summary=summary(result.assessment());
        assertEquals(1,summary.get("checkpointsWithRetrieval"));assertEquals(2,summary.get("searchAttempts"));
        assertEquals(2,summary.get("vectorSearches"));assertEquals(1,summary.get("followUpCheckpoints"));
        assertEquals(32,summary.get("candidateCount"));assertEquals(36,summary.get("mappedSourceCount"));
        assertEquals(1,summary.get("degradedSearches"));assertEquals(true,summary.get("telemetryComplete"));
        assertEquals(true,summary.get("sourceMappingTelemetryComplete"));assertNull(summary.get("selectedRetrievedSourceCount"));
    }
    @Test void historicalStrippedReceiptsAreUnknownRatherThanZeroCandidates(){
        java.util.Map<?, ?> summary=summary(cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("complete", false), "structuredSteps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "a", "type", "RETRIEVE_0_bid", "status", "COMPLETED"))));
        assertEquals(1,summary.get("checkpointsWithRetrieval"));assertEquals(1,summary.get("unknownSearches"));
        assertNull(summary.get("candidateCount"));assertEquals(false,summary.get("telemetryComplete"));assertNull(summary.get("vectorSearches"));
        assertNull(summary.get("mappedSourceCount"));assertNull(summary.get("selectedRetrievedSourceCount"));
    }
    @Test void indexPreparationIsNotCountedAsVectorSearch(){
        java.util.Map<?, ?> summary=summary(assess(cn.bidcheck.compat.Java8Api.listOf(receipt("a",0,"BM25_DEGRADED")),cn.bidcheck.compat.Java8Api.listOf()).assessment());
        assertEquals(0,summary.get("vectorSearches"));assertEquals(1,summary.get("lexicalOnlySearches"));
    }
    @Test void selectionCountsAreMeasuredPerRoundAndDeduplicated(){
        Map<String,Object> selection=cn.bidcheck.compat.Java8Api.mapOf("id", "s", "executionId", "s", "type", "RETRIEVAL_SELECTION_0", "status", "COMPLETED", "output", cn.bidcheck.compat.Java8Api.mapOf("selectedRetrievedSourceCount", 5));
        java.util.Map<?, ?> summary=summary(assess(cn.bidcheck.compat.Java8Api.listOf(receipt("a",0,"HYBRID_RERANKED"), selection),cn.bidcheck.compat.Java8Api.listOf(selection)).assessment());
        assertEquals(5,summary.get("selectedRetrievedSourceCount"));assertEquals(true,summary.get("selectionTelemetryComplete"));
        assertEquals(0,summary.get("unknownSelectionRounds"));
    }
}

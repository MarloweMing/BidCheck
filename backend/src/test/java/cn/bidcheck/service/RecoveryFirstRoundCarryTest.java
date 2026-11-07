package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.fields;
import static cn.bidcheck.service.CheckResolution.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryFirstRoundCarryTest {
    @Test void pendingTableCursorSurvivesLocalRecomputeAndSiblingReviewUntilTheActualTailIsRead() {
        Finding first=fixture("role",false);
        Map<String,Object> cursor=fields("action","READ_TABLE_CONTINUATION","sourceId","source","rowOffset",80,
            "tableLayoutRevision","table-v1","documentId","doc","documentVersion",1,"documentHash","hash","page",199,"locationType","PAGE");
        Map<String,Object> original=tableAudit("TARGET",Collections.singletonList(cursor));
        first=withAudit(first,original);
        Finding local=RecoverySemanticGate.preserveFirstRound(first,fixture("role",false));
        assertEquals(Collections.singletonList(cursor),PendingTableWork.pending(local,"TARGET"));
        Finding sibling=RecoverySemanticGate.preserveFirstRound(local,withAudit(fixture("role",false),tableAudit("OTHER",Collections.emptyList())));
        assertEquals(80,PendingTableWork.pending(sibling,"TARGET").get(0).get("rowOffset"));
        assertEquals(local.evaluation().status(),sibling.evaluation().status());
        Map<String,Object> done=tableAudit("TARGET",Collections.emptyList());
        done.put("tableContinuationExecutions",Collections.singletonList(fields("targetObligationId","TARGET","sourceId","source",
            "status","COMPLETED","returnedRows",1,"businessProof",false)));
        Finding finish=RecoverySemanticGate.preserveFirstRound(sibling,withAudit(fixture("role",false),done));
        assertTrue(PendingTableWork.pending(finish,"TARGET").isEmpty());
    }
    private Map<String,Object> tableAudit(String target,List<Map<String,Object>> pending) {
        Map<String,Object> work=fields("targetObligationId",target,"evidenceRevision","evidence","pendingReadActions",pending);
        return fields("requestId","request","targetReceiptAuthority","CURRENT_SERVER_PROOF","serverPlanRevision","plan",
            "targetEvidenceRevisions",fields(target,"evidence"),"serverTargetInputConsumption",fields("version","server-target-input-consumption-1",
                "businessProof",false,"authority","CURRENT_SERVER_PROOF","requestId","request","serverPlanRevision","plan","targets",Collections.singletonList(work)));
    }
    private Finding withAudit(Finding finding,Map<String,Object> audit) {
        Map<String,Object> body=new LinkedHashMap<>(atomic(finding));body.put("agentInvestigation",audit);
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),finding.evaluation(),fields("atomicCheck",body));
    }
    private Finding fixture(String field, boolean newFact) {
        Map<String,Object> rule = fields("id", "TARGET", "predicate", "DECLARATION", "subject", "",
            "field", field, "expected", "承诺", "parameters", Collections.emptyMap());
        Map<String,Object> atomic = fields("plan", fields("rules", Collections.singletonList(rule)),
            "conditions", Collections.singletonList(fields("id", "TARGET", "status", "UNDETERMINED")));
        if (newFact) atomic.put("facts", Collections.singletonList(fields("id", "NEW", "kind", "DECLARATION",
            "subject", "", "field", field, "value", "新增承诺原文", "sourceIds", Collections.singletonList("S1"))));
        Checkpoint cp = new Checkpoint("cp", "合成", "人员", "HIGH", "SEMANTIC_RESPONSE", "compliance",
            "RESPONDS", "合成原文", "", true, true, true, null);
        return new Finding("f", "r", cp, new Evaluation("UNDETERMINED", "保留缺口", "",
            Collections.emptyList(), 0, "RULE"), fields("atomicCheck", atomic));
    }

    private Finding captured() {
        Finding f = fixture("commitment", false);
        Map<String,Object> atomic = atomic(f);
        atomic.put("agentInvestigation", fields("requestId", "synthetic-first", "status", "NO_PROGRESS",
            "code", "REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", "failedToolCount", 0,
            "targetReceiptAuthority", "CURRENT_SERVER_PROOF", "remainingTargetObligationIds", Collections.singletonList("TARGET"),
            "targetProgressLedger", Collections.singletonList(fields("targetObligationId", "TARGET",
                "visited", true, "navigationCount", 1))));
        Finding first = new Finding(f.id(), f.runId(), f.checkpoint(), f.evaluation(), fields("atomicCheck", atomic));
        Finding captured = RecoverySemanticGate.captureFirstRound(first, Collections.emptyMap(), "v1");
        assertTrue(atomic(captured).containsKey("firstRoundSemanticInputs"));
        return captured;
    }

    private Finding carry(Finding before, Finding after) throws Exception {
        try {
            Method method = RecoverySemanticGate.class.getDeclaredMethod("preserveFirstRound", Finding.class, Finding.class);
            method.setAccessible(true);
            return (Finding) method.invoke(null, before, after);
        } catch (NoSuchMethodException baseline) {
            return after;
        }
    }

    private boolean allowed(Finding finding, String target) {
        return Boolean.TRUE.equals(RecoverySemanticGate.inspect(finding, "TARGETED_EVIDENCE",
            fields("recoveryTargetObligationIds", Collections.singletonList(target)), Collections.emptyList(), "v1").get("allowed"));
    }

    @Test void sharedRecomputationRetainsConsumedInputsWithoutRestoringOldBusinessProof() throws Exception {
        Finding before = captured();
        Finding after = fixture("commitment", false);
        Map<String,Object> atomic = atomic(after);
        atomic.put("proof", fields("newRecomputedProof", true));
        after = new Finding(after.id(), after.runId(), after.checkpoint(), after.evaluation(), fields("atomicCheck", atomic));
        Finding carried = carry(before, after);
        assertFalse(allowed(carried, "TARGET"));
        assertEquals(fields("newRecomputedProof", true), atomic(carried).get("proof"));
        assertEquals("UNDETERMINED", carried.evaluation().status());
        assertTrue(allowed(carried, "UNVISITED"));
    }

    @Test void newEvidenceOrRuleStillReopensAfterCarry() throws Exception {
        Finding before = captured();
        assertTrue(allowed(carry(before, fixture("commitment", true)), "TARGET"));
        assertTrue(allowed(carry(before, fixture("changed-rule", false)), "TARGET"));
    }
}

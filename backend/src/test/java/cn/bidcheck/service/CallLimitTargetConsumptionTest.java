package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

/** Synthetic multi-row server receipts only; no private helper, document or provider. */
class CallLimitTargetConsumptionTest {
    private static final String CLOSED_INPUT = "row-complete";
    private static final String UNREAD = "row-unread";
    private static final String REPAIR = "row-repair";
    private static final String UNVISITED = "row-unvisited";
    private static final List<String> TARGETS = Arrays.asList(CLOSED_INPUT, UNREAD, REPAIR, UNVISITED);

    @Test void wholeCallLimitDoesNotReopenTheOneStableCompleteRow() {
        Finding original = finding();
        Finding captured = capture(original);
        Map<String,Object> gate = gate(captured, context(), Collections.emptyList());
        assertEquals(Collections.singletonList(CLOSED_INPUT), gate.get("blockedTargetIds"));
        assertEquals(Arrays.asList(REPAIR, UNREAD, UNVISITED), gate.get("eligibleTargetIds"));
        assertEquals("UNDETERMINED", captured.evaluation().status());
        assertEquals(body(original).get("gaps"), body(captured).get("gaps"));
        assertEquals(false, work(captured, CLOSED_INPUT).get("consumed"));
        assertEquals(false, work(captured, CLOSED_INPUT).get("terminalForRevision"));
    }

    @Test void recoveryAttemptKeepsStableRowConsumedWithoutConsumingUnreadSiblings() {
        Finding current = finding();
        Map<String,Object> initial = gate(current, context(), Collections.emptyList());
        List<Map<String,Object>> attempts = Collections.singletonList(fields(
            "strategy", "TARGETED_EVIDENCE", "status", "NO_PROGRESS",
            "semanticConsumedInputs", initial.get("inputs")));
        Map<String,Object> decision = gate(current, context(), attempts);
        assertEquals(Collections.singletonList(CLOSED_INPUT), decision.get("blockedTargetIds"));
        assertEquals(Arrays.asList(REPAIR, UNREAD, UNVISITED), decision.get("eligibleTargetIds"));
    }

    @Test void missingInvalidStaleAndLegacyCountersCannotBorrowCallLimitAuthority() {
        for (String key : Arrays.asList("ruleVersion", "relevantEvidenceRevision", "noNewFactRequestId",
                "noNewFactCounterVersion", "noNewFactModelRounds", "noNewFactStopReason")) {
            for (boolean missing : Arrays.asList(false, true)) {
                Finding f = finding();
                Map<String,Object> row = ledger(f, CLOSED_INPUT);
                row.put("noEvidenceProgressRounds", 3);
                if (missing) row.remove(key); else row.put(key, "INVALID");
                assertTrue(eligible(capture(f), CLOSED_INPUT), key + " missing=" + missing);
            }
        }
        Finding legacy = finding();
        Map<String,Object> row = ledger(legacy, CLOSED_INPUT);
        for (String key : new ArrayList<>(row.keySet())) {
            if (key.startsWith("noNewFact")) row.remove(key);
        }
        row.put("noEvidenceProgressRounds", 3);
        assertTrue(eligible(capture(legacy), CLOSED_INPUT));
    }

    @Test void incompleteCoveragePendingReadsAndRealValidationAreNeverConsumed() {
        for (String change : Arrays.asList("packet", "coverage", "pending", "validation", "unknown-origin")) {
            Finding f = finding();
            Map<String,Object> work = work(f, CLOSED_INPUT);
            if (change.equals("packet")) work.put("readCoverage", fields("complete", false));
            if (change.equals("coverage")) work.put("readCoverageComplete", false);
            if (change.equals("pending")) work.put("pendingReadActions",
                Collections.singletonList(fields("action", "READ_SECTION_CONTINUATION", "sourceId", "tail")));
            if (change.equals("validation")) work.put("repairActionOrigin", "VALIDATION_REPAIR");
            if (change.equals("unknown-origin")) work.remove("repairActionOrigin");
            assertTrue(eligible(capture(f), CLOSED_INPUT), change);
        }
    }

    @Test void currentRequestPlanAuthorityAndSuccessfulTransportRemainMandatory() {
        for (String change : Arrays.asList("request", "plan", "authority", "failed", "failed-tool", "model-only")) {
            Finding f = finding();
            if (change.equals("request")) receipt(f).put("requestId", "old-request");
            if (change.equals("plan")) receipt(f).put("serverPlanRevision", "old-plan");
            if (change.equals("authority")) receipt(f).put("authority", "MODEL");
            if (change.equals("failed")) audit(f).put("status", "FAILED");
            if (change.equals("failed-tool")) audit(f).put("failedToolCount", 1);
            if (change.equals("model-only")) audit(f).put("targetReceiptAuthority", "MODEL");
            assertTrue(eligible(capture(f), CLOSED_INPUT), change);
        }
    }

    @Test void newSourcesRulesAndCounterfactsStillReopenPreviouslyConsumedInputs() {
        Finding captured = capture(finding());
        assertFalse(eligible(captured, CLOSED_INPUT));
        Map<String,Object> newSource = context();
        newSource.put("recoverySourceRevision", "new-original");
        assertTrue(((List<?>) gate(captured, newSource, Collections.emptyList())
            .get("eligibleTargetIds")).contains(CLOSED_INPUT));
        Map<String,Object> rule = rules(captured).get(0);
        rule.put("expected", "new-requirement");
        assertTrue(eligible(captured, CLOSED_INPUT));
        Finding contrary = capture(finding());
        Map<String,Object> trigger = context();
        trigger.put("recoveryDependencyTrigger", fields("semanticEvidenceKeys", Collections.singletonList("validated-counterfact")));
        assertTrue(((List<?>) gate(contrary, trigger, Collections.emptyList())
            .get("eligibleTargetIds")).contains(CLOSED_INPUT));
    }

    private Finding capture(Finding f) {
        return RecoverySemanticGate.captureFirstRound(f, context(), "policy");
    }
    private Map<String,Object> context() {
        return fields("recoveryTargetObligationIds", TARGETS);
    }
    private Map<String,Object> gate(Finding f, Map<String,Object> ctx, List<Map<String,Object>> attempts) {
        return RecoverySemanticGate.inspect(f, "TARGETED_EVIDENCE", ctx, attempts, "policy");
    }
    private boolean eligible(Finding f, String id) {
        return ((List<?>) gate(f, context(), Collections.emptyList()).get("eligibleTargetIds")).contains(id);
    }
    private Map<String,Object> body(Finding f) {
        return (Map<String,Object>) f.assessment().get("atomicCheck");
    }
    private Map<String,Object> audit(Finding f) {
        return (Map<String,Object>) body(f).get("agentInvestigation");
    }
    private Map<String,Object> receipt(Finding f) {
        return (Map<String,Object>) audit(f).get("serverTargetInputConsumption");
    }
    private List<Map<String,Object>> rules(Finding f) {
        return (List<Map<String,Object>>) ((Map<?,?>) body(f).get("plan")).get("rules");
    }
    private Map<String,Object> work(Finding f, String id) {
        return target((List<Map<String,Object>>) receipt(f).get("targets"), id);
    }
    private Map<String,Object> ledger(Finding f, String id) {
        return target((List<Map<String,Object>>) audit(f).get("targetProgressLedger"), id);
    }
    private Map<String,Object> target(List<Map<String,Object>> values, String id) {
        return values.stream().filter(row -> id.equals(row.get("targetObligationId"))).findFirst().get();
    }
    private Finding finding() {
        List<Map<String,Object>> rules = new ArrayList<>();
        List<Map<String,Object>> works = new ArrayList<>();
        List<Map<String,Object>> ledger = new ArrayList<>();
        List<Map<String,Object>> conditions = new ArrayList<>();
        Map<String,Object> revisions = new LinkedHashMap<>();
        for (String id : TARGETS) {
            rules.add(fields("id", id, "predicate", "CONTRACT", "subject", "example contractor",
                "field", "contractName", "expected", "synthetic project " + id,
                "parameters", fields("declaredPerformanceRow", fields("documentId", "synthetic", "proofPageStart", 2, "proofPageEnd", 4))));
            boolean complete = !UNREAD.equals(id) && !UNVISITED.equals(id);
            works.add(fields("targetObligationId", id, "evidenceRevision", "revision-" + id,
                "repairActionOrigin", REPAIR.equals(id) ? "VALIDATION_REPAIR" : "DEFAULT_GUIDANCE",
                "remainingRepairActions", Collections.singletonList("READ_COMPLETE_RECORD"),
                "terminalForRevision", false, "consumed", false, "readCoverageComplete", complete,
                "readCoverage", fields("complete", complete), "pendingReadActions", complete ? Collections.emptyList()
                    : Collections.singletonList(fields("action", "READ_SECTION_CONTINUATION", "pageStart", 3, "pageEnd", 4))));
            ledger.add(fields("targetObligationId", id, "visited", !UNVISITED.equals(id), "navigationCount", 1,
                "noEvidenceProgressRounds", 0, "ruleVersion", "plan", "relevantEvidenceRevision", "revision-" + id,
                "noNewFactCounterVersion", "java-agent-target-rounds-1", "noNewFactRequestId", "request",
                "noNewFactModelRounds", 3, "noNewFactStopReason", "THREE_ROUNDS_NO_NEW_FACT", "businessProof", false));
            revisions.put(id, "revision-" + id);
            conditions.add(fields("id", id, "status", "UNDETERMINED"));
        }
        Map<String,Object> receipt = fields("version", "server-target-input-consumption-1", "authority", "CURRENT_SERVER_PROOF",
            "requestId", "request", "serverPlanRevision", "plan", "repairActionOriginVersion", "host-repair-action-origin-1",
            "businessProof", false, "targets", works);
        Map<String,Object> audit = fields("requestId", "request", "status", "NO_PROGRESS", "code", "INVESTIGATION_CALL_LIMIT",
            "failedToolCount", 0, "targetReceiptAuthority", "CURRENT_SERVER_PROOF", "serverPlanRevision", "plan",
            "targetEvidenceRevisions", revisions, "remainingTargetObligationIds", TARGETS,
            "serverTargetInputConsumption", receipt, "targetProgressLedger", ledger);
        Map<String,Object> body = fields("plan", fields("rules", rules), "agentInvestigation", audit,
            "conditions", conditions, "gaps", Collections.singletonList(fields("code", "PERFORMANCE_PROOF_COVERAGE_REQUIRED")));
        Checkpoint cp = new Checkpoint("cp", "Synthetic", "test", "HIGH", "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null);
        return new Finding("finding", "run", cp, new Evaluation("UNDETERMINED", "pending", "", Collections.emptyList(), 0, "RULE"), fields("atomicCheck", body));
    }
}

package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;
import static cn.bidcheck.service.CheckResolution.*;

/** Synthetic server receipts; no real documents or provider. */
class DefaultGuidanceConsumptionTest {
    private static final String TARGET = "commitment";

    @Test void threeStableRoundsConsumeDefaultGuidanceWithoutClosingBusinessTarget() {
        Finding before = finding("DEFAULT_GUIDANCE", 3);
        Finding captured = capture(before);
        assertEquals(false, gate(captured, context()).get("allowed"));
        assertEquals("UNDETERMINED", captured.evaluation().status());
        assertEquals(atomic(before).get("gaps"), atomic(captured).get("gaps"));
        assertEquals(false, work(captured).get("terminalForRevision"));
        assertEquals(false, work(captured).get("consumed"));
    }

    @Test void staleOrMissingLedgerVersionsCannotLendRoundsToCurrentInputs() {
        for (String field : Arrays.asList("ruleVersion", "relevantEvidenceRevision")) {
            for (boolean missing : Arrays.asList(false, true)) {
                Finding before = finding("DEFAULT_GUIDANCE", 3);
                Map<String,Object> row = (Map<String,Object>) ((List<?>) audit(before).get("targetProgressLedger")).get(0);
                if (missing) row.remove(field); else row.put(field, "previous-version");
                assertEquals(true, gate(capture(before), context()).get("allowed"), field + " missing=" + missing);
            }
        }
    }

    @Test void fewerRoundsUnknownOriginAndRealValidationRemainExecutable() {
        for (String origin : Arrays.asList("", "VALIDATION_REPAIR", "UNKNOWN")) {
            assertEquals(true, gate(capture(finding(origin, 3)), context()).get("allowed"));
        }
        for (int rounds : Arrays.asList(0, 1, 2)) {
            assertEquals(true, gate(capture(finding("DEFAULT_GUIDANCE", rounds)), context()).get("allowed"));
        }
    }

    @Test void partialTableAndInheritedIncompleteReadsProtectRealInput() {
        for (String kind : Arrays.asList("READ_SECTION_CONTINUATION", "READ_TABLE_CONTINUATION", "INITIAL_INCOMPLETE_READ")) {
            Finding before = finding("DEFAULT_GUIDANCE", 3);
            work(before).put("pendingReadActions", Collections.singletonList(fields("action", kind, "sourceId", "source")));
            work(before).put("readCoverageComplete", false);
            assertEquals(true, gate(capture(before), context()).get("allowed"));
        }
    }

    @Test void unknownReceiptVersionAndModelOnlyDefaultCannotAuthorizeConsumption() {
        Finding before = finding("DEFAULT_GUIDANCE", 3);
        receipt(before).remove("repairActionOriginVersion");
        assertEquals(true, gate(capture(before), context()).get("allowed"));
        before = finding("DEFAULT_GUIDANCE", 3);
        receipt(before).put("authority", "MODEL");
        assertEquals(true, gate(capture(before), context()).get("allowed"));
        before = finding("DEFAULT_GUIDANCE", 3);
        receipt(before).put("requestId", "wrong-request");
        assertEquals(true, gate(capture(before), context()).get("allowed"));
    }

    @Test void newSourceCounterfactAndValidationRepairReopenSameTarget() {
        Finding captured = capture(finding("DEFAULT_GUIDANCE", 3));
        assertEquals(false, gate(captured, context()).get("allowed"));
        Map<String,Object> changed = context();
        changed.put("recoverySourceRevision", "new-source");
        assertEquals(true, gate(captured, changed).get("allowed"));
        body(captured).put("facts", Collections.singletonList(fields("id", "counterfact", "kind", "DECLARATION",
            "subject", "", "field", "commitment", "value", "contradiction", "rawValue", "contradiction", "sourceIds", Collections.singletonList("source"))));
        assertEquals(true, gate(captured, context()).get("allowed"));
        Finding repaired = capture(finding("DEFAULT_GUIDANCE", 3));
        work(repaired).put("repairActionOrigin", "VALIDATION_REPAIR");
        assertEquals(true, gate(repaired, context()).get("allowed"));
    }

    @Test void failedTransportAndUnvisitedSiblingAreNeverHidden() {
        Finding captured = capture(finding("DEFAULT_GUIDANCE", 3));
        assertEquals(false, gate(captured, context()).get("allowed"));
        Map<String,Object> ctx = fields("recoveryTargetObligationIds", Arrays.asList(TARGET, "other"));
        assertEquals(Collections.singletonList("other"), gate(captured, ctx).get("eligibleTargetIds"));
        audit(captured).put("status", "FAILED");
        audit(captured).put("failedToolCount", 1);
        assertEquals(true, gate(captured, context()).get("allowed"));
    }

    @Test void declaredContractStillRequiresCompletePacket() {
        Finding before = finding("DEFAULT_GUIDANCE", 3);
        Map<String,Object> rule = (Map<String,Object>) ((List<?>) ((Map<?,?>) body(before).get("plan")).get("rules")).get(0);
        rule.put("predicate", "CONTRACT");
        rule.put("parameters", fields("declaredPerformanceRow", fields("documentId", "doc", "proofPageStart", 2, "proofPageEnd", 4)));
        work(before).put("readCoverage", fields("complete", false, "pendingPages", Arrays.asList(3, 4)));
        assertEquals(true, gate(capture(before), context()).get("allowed"));
    }

    private Finding capture(Finding f) { return RecoverySemanticGate.captureFirstRound(f, context(), "policy"); }
    private Map<String,Object> context() { return fields("recoveryTargetObligationIds", Collections.singletonList(TARGET)); }
    private Map<String,Object> gate(Finding f, Map<String,Object> ctx) {
        return RecoverySemanticGate.inspect(f, "TARGETED_EVIDENCE", ctx, Collections.emptyList(), "policy");
    }
    private Map<String,Object> body(Finding f) { return (Map<String,Object>) f.assessment().get("atomicCheck"); }
    private Map<String,Object> audit(Finding f) { return (Map<String,Object>) body(f).get("agentInvestigation"); }
    private Map<String,Object> receipt(Finding f) { return (Map<String,Object>) audit(f).get("serverTargetInputConsumption"); }
    private Map<String,Object> work(Finding f) { return (Map<String,Object>) ((List<?>) receipt(f).get("targets")).get(0); }
    private Finding finding(String origin, int rounds) {
        Map<String,Object> rule = fields("id", TARGET, "predicate", "DECLARATION", "subject", "", "field", "commitment", "expected", "commitment", "parameters", fields());
        Map<String,Object> work = fields("targetObligationId", TARGET, "evidenceRevision", "evidence",
            "repairActionOrigin", origin, "remainingRepairActions", Collections.singletonList("READ_COMPLETE_RECORD"),
            "terminalForRevision", false, "consumed", false, "readCoverageComplete", true, "pendingReadActions", Collections.emptyList());
        Map<String,Object> receipt = fields("version", "server-target-input-consumption-1", "authority", "CURRENT_SERVER_PROOF",
            "requestId", "request", "serverPlanRevision", "plan", "repairActionOriginVersion", "host-repair-action-origin-1",
            "businessProof", false, "targets", Collections.singletonList(work));
        Map<String,Object> audit = fields("requestId", "request", "status", "NO_PROGRESS", "code", "REPEATED_ACTION_WITHOUT_NEW_EVIDENCE",
            "failedToolCount", 0, "targetReceiptAuthority", "CURRENT_SERVER_PROOF", "serverPlanRevision", "plan",
            "targetEvidenceRevisions", fields(TARGET, "evidence"), "remainingTargetObligationIds", Collections.singletonList(TARGET),
            "serverTargetInputConsumption", receipt, "targetProgressLedger", Collections.singletonList(fields("targetObligationId", TARGET,
                "visited", true, "navigationCount", 3, "noEvidenceProgressRounds", rounds,
                "ruleVersion", "plan", "relevantEvidenceRevision", "evidence")));
        Map<String,Object> body = fields("plan", fields("rules", Collections.singletonList(rule)), "agentInvestigation", audit,
            "conditions", Collections.singletonList(fields("id", TARGET, "status", "UNDETERMINED")), "gaps", Collections.singletonList(fields("code", "MISSING_COMMITMENT")));
        Checkpoint cp = new Checkpoint("cp", "Synthetic", "test", "HIGH", "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null);
        return new Finding("finding", "run", cp, new Evaluation("UNDETERMINED", "pending", "", Collections.emptyList(), 0, "RULE"), fields("atomicCheck", body));
    }
}

package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Synthetic server receipts only; no original documents or provider. */
class SemanticRejectionConsumptionTest {
    private static final String TARGET = "synthetic-contract-row";

    @Test void verifiedSemanticStopConsumesOnlyCoveredVisitedTarget() {
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
        Finding captured = RecoverySemanticGate.captureFirstRound(before, context(), "policy");
        Map<String,Object> gate = inspect(captured);
        assertEquals(false, gate.get("allowed"));
        assertEquals(Collections.singleton(TARGET), new HashSet<>((Collection<?>) gate.get("firstRoundConsumedTargetIds")));
        assertEquals("UNDETERMINED", captured.evaluation().status());
        assertEquals(atomic(before).get("gaps"), atomic(captured).get("gaps"));
        assertEquals(Collections.emptyList(), map(atomic(captured).get("agentInvestigation")).get("exhaustedTargetObligationIds"));
    }

    @Test void nakedStopCodeUnreadPacketAndUnvisitedTargetDoNotConsume() {
        for (Finding before : Arrays.asList(
                finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", false, true),
                finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, false))) {
            assertEquals(true, inspect(RecoverySemanticGate.captureFirstRound(before, context(), "policy")).get("allowed"));
        }
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
        Map<String,Object> audit = mutableAudit(before);
        audit.put("targetProgressLedger", Collections.singletonList(fields("targetObligationId", TARGET, "visited", false)));
        assertEquals(true, inspect(RecoverySemanticGate.captureFirstRound(before, context(), "policy")).get("allowed"));
    }

    @Test void failedTransportAndWrongReceiptRequestCannotConsume() {
        for (String code : Arrays.asList("INVESTIGATION_TIMEOUT", "MODEL_OR_SDK_ERROR")) {
            Finding before = finding(code, true, true);
            assertEquals(true, inspect(RecoverySemanticGate.captureFirstRound(before, context(), "policy")).get("allowed"));
        }
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
        Map<String,Object> receipt = (Map<String,Object>) mutableAudit(before).get("serverTargetInputConsumption");
        receipt.put("requestId", "other-request");
        assertEquals(true, inspect(RecoverySemanticGate.captureFirstRound(before, context(), "policy")).get("allowed"));
    }

    @Test void changedPolicyAndUnvisitedSiblingRemainExecutable() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        assertEquals(false, inspect(captured).get("allowed"));
        assertEquals(true, RecoverySemanticGate.inspect(captured, "TARGETED_EVIDENCE", context(), Collections.emptyList(), "new-policy").get("allowed"));
        Map<String,Object> other = fields("recoveryTargetObligationIds", Collections.singletonList("independent"));
        assertEquals(true, RecoverySemanticGate.inspect(captured, "TARGETED_EVIDENCE", other, Collections.emptyList(), "policy").get("allowed"));
    }

    @SuppressWarnings("unchecked") private Map<String,Object> mutableAudit(Finding finding) {
        return (Map<String,Object>) ((Map<String,Object>) finding.assessment().get("atomicCheck")).get("agentInvestigation");
    }

    @Test void repeatedStopCannotConsumeAnUnreadDeclaredPacketEither() {
        Finding before = finding("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", true, false);
        assertEquals(true, inspect(RecoverySemanticGate.captureFirstRound(before, context(), "policy")).get("allowed"));
    }

    @Test void serverPendingRepairOrDifferentEvidenceRevisionRemainExecutable() {
        for (String change : Arrays.asList("revision", "terminal", "repair")) {
            Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
            Map<String,Object> receipt = (Map<String,Object>) mutableAudit(before).get("serverTargetInputConsumption");
            Map<String,Object> target = (Map<String,Object>) ((List<?>) receipt.get("targets")).get(0);
            if (change.equals("revision")) target.put("evidenceRevision", "old-evidence");
            if (change.equals("terminal")) target.put("terminalForRevision", false);
            if (change.equals("repair")) target.put("remainingRepairActions", Collections.singletonList("READ_MISSING_PAGE"));
            assertEquals(true, inspect(RecoverySemanticGate.captureFirstRound(before, context(), "policy")).get("allowed"));
        }
    }

    @Test void genuineNewSourceRevisionReopensConsumedTarget() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        assertEquals(false, inspect(captured).get("allowed"));
        Map<String,Object> changed = context();
        changed.put("recoverySourceRevision", "new-source-version");
        assertEquals(true, RecoverySemanticGate.inspect(captured, "TARGETED_EVIDENCE", changed, Collections.emptyList(), "policy").get("allowed"));
    }

    @Test void newlyVerifiedRepairActionReopensAnOtherwiseConsumedInput() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        assertEquals(false, inspect(captured).get("allowed"));
        Map<String,Object> receipt = (Map<String,Object>) mutableAudit(captured).get("serverTargetInputConsumption");
        Map<String,Object> target = (Map<String,Object>) ((List<?>) receipt.get("targets")).get(0);
        target.put("remainingRepairActions", Collections.singletonList("READ_NEW_VERIFIED_RECORD"));
        target.put("consumed", false);
        assertEquals(true, inspect(captured).get("allowed"));
    }

    @Test void legacyTextReceiptCannotBypassUnreadPacketCoverage() {
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, false);
        mutableAudit(before).put("firstRoundTextWork", fields("version", "first-round-consumed-text-1",
            "requestId", "synthetic-request", "key", "admission-key", "targets", Collections.singletonList(TARGET)));
        Map<String,Object> ctx = context();
        ctx.put("agentAdmissionKey", "admission-key");
        assertEquals(true, RecoverySemanticGate.inspect(before, "TARGETED_EVIDENCE", ctx, Collections.emptyList(), "policy").get("allowed"));
    }

    @Test void persistedAndRecoveryConsumptionCannotBypassPendingBodyCoverage() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        assertEquals(false, inspect(captured).get("allowed"));
        Map<String,Object> receipt = (Map<String,Object>) mutableAudit(captured).get("serverTargetInputConsumption");
        Map<String,Object> target = (Map<String,Object>) ((List<?>) receipt.get("targets")).get(0);
        target.put("readCoverageComplete", false);
        target.put("readCoverage", fields("complete", false, "pendingPages", Collections.singletonList(3)));
        assertEquals(true, inspect(captured).get("allowed"));
        List<Map<String,Object>> history = Collections.singletonList(fields("strategy", "TARGETED_EVIDENCE", "status", "NO_PROGRESS",
            "semanticConsumedInputs", RecoverySemanticGate.inputs(captured, context(), "policy")));
        assertEquals(true, RecoverySemanticGate.inspect(captured, "TARGETED_EVIDENCE", context(), history, "policy").get("allowed"));
    }

    @Test void legacyReceiptCannotOverrideANewTrustedRepairAction() {
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
        Map<String,Object> audit = mutableAudit(before);
        audit.put("firstRoundTextWork", fields("version", "first-round-consumed-text-1",
            "requestId", "synthetic-request", "key", "admission-key", "targets", Collections.singletonList(TARGET)));
        Map<String,Object> receipt = (Map<String,Object>) audit.get("serverTargetInputConsumption");
        Map<String,Object> target = (Map<String,Object>) ((List<?>) receipt.get("targets")).get(0);
        target.put("remainingRepairActions", Collections.singletonList("READ_NEW_VERIFIED_RECORD"));
        target.put("consumed", false);
        Map<String,Object> ctx = context();
        ctx.put("agentAdmissionKey", "admission-key");
        assertEquals(true, RecoverySemanticGate.inspect(before, "TARGETED_EVIDENCE", ctx, Collections.emptyList(), "policy").get("allowed"));
        List<Map<String,Object>> history = Collections.singletonList(fields("strategy", "TARGETED_EVIDENCE", "status", "NO_PROGRESS",
            "semanticConsumedInputs", RecoverySemanticGate.inputs(before, ctx, "policy")));
        assertEquals(true, RecoverySemanticGate.inspect(before, "TARGETED_EVIDENCE", ctx, history, "policy").get("allowed"));
    }

    @Test void contractLegacyTargetsNeedComparableSnapshotWhileNonContractLegacyBehaviorRemains() {
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
        mutableAudit(before).put("firstRoundTextWork", fields("version", "first-round-consumed-text-1",
            "requestId", "synthetic-request", "key", "admission-key", "targets", Collections.singletonList(TARGET)));
        Map<String,Object> ctx = context();
        ctx.put("agentAdmissionKey", "admission-key");
        assertEquals(true, RecoverySemanticGate.inspect(before, "TARGETED_EVIDENCE", ctx, Collections.emptyList(), "policy").get("allowed"),
            "A legacy target list has no comparable per-target input snapshot even when coverage is complete");
        Map<String,Object> atomic = (Map<String,Object>) before.assessment().get("atomicCheck");
        Map<String,Object> plan = (Map<String,Object>) atomic.get("plan");
        Map<String,Object> rule = (Map<String,Object>) ((List<?>) plan.get("rules")).get(0);
        rule.put("predicate", "DECLARATION");
        rule.put("parameters", fields());
        assertEquals(false, RecoverySemanticGate.inspect(before, "TARGETED_EVIDENCE", ctx, Collections.emptyList(), "policy").get("allowed"));
    }

    @Test void localRevalidationPreservesCompletePacketConsumptionButNewSourcesReopen() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        assertEquals(false, inspect(captured).get("allowed"));
        Finding local = finding("", false, false);
        mutableAudit(local).clear();
        Finding preserved = RecoverySemanticGate.preserveFirstRound(captured, local);
        assertFalse(map(atomic(preserved).get("firstRoundInvestigation")).isEmpty());
        assertEquals(false, inspect(preserved).get("allowed"),
            "A model-disabled local recomputation must not forget previously verified complete reads");
        Map<String,Object> changed = context();
        changed.put("recoverySourceRevision", "new-source-version");
        assertEquals(true, RecoverySemanticGate.inspect(preserved, "TARGETED_EVIDENCE", changed,
            Collections.emptyList(), "policy").get("allowed"));
        Map<String,Object> body = (Map<String,Object>) preserved.assessment().get("atomicCheck");
        Map<String,Object> condition = (Map<String,Object>) ((List<?>) body.get("conditions")).get(0);
        condition.put("proof", fields("factIds", Collections.singletonList("new-verified-fact")));
        assertEquals(true, inspect(preserved).get("allowed"));
    }

    @Test void currentAuthoritativePendingCoverageOrRepairOverridesPreservedAudit() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        for (boolean complete : Arrays.asList(false, true)) {
            Finding current = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, complete);
            if (complete) {
                Map<String,Object> receipt = (Map<String,Object>) mutableAudit(current).get("serverTargetInputConsumption");
                Map<String,Object> target = (Map<String,Object>) ((List<?>) receipt.get("targets")).get(0);
                target.put("remainingRepairActions", Collections.singletonList("READ_NEW_VERIFIED_RECORD"));
            }
            assertEquals(true, inspect(RecoverySemanticGate.preserveFirstRound(captured, current)).get("allowed"));
        }
    }

    @Test void currentTransportFailureCannotBeHiddenByPreservedCompletePacket() {
        Finding captured = RecoverySemanticGate.captureFirstRound(
            finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true), context(), "policy");
        for (boolean authoritative : Arrays.asList(false, true)) {
            Finding failed = finding("MODEL_OR_SDK_ERROR", false, false);
            Map<String,Object> audit = mutableAudit(failed);
            audit.put("status", "FAILED");
            audit.put("failedToolCount", 1);
            if (!authoritative) audit.remove("targetReceiptAuthority");
            assertEquals(true, inspect(RecoverySemanticGate.preserveFirstRound(captured, failed)).get("allowed"));
        }
    }

    private Map<String,Object> context() {
        return fields("recoveryTargetObligationIds", Collections.singletonList(TARGET));
    }

    @Test void fullyReadContractPacketWithStableRereadStopsOnlyThatInputRevision() {
        Finding before = finding("SEMANTIC_REJECTION_WITHOUT_NEW_EVIDENCE", true, true);
        Map<String,Object> audit = mutableAudit(before);
        Map<String,Object> packet = fields("targetObligationId", TARGET, "authority", "CURRENT_SERVER_PROOF",
            "businessProof", false, "scopeAvailable", true, "complete", true,
            "documentId", "doc", "documentHash", "hash-1", "documentVersion", 1,
            "availablePages", Arrays.asList(153,154,155,156,157,158,159), "readSourceIds", Arrays.asList("source-153","source-159"),
            "pendingPages", Collections.emptyList(), "missingSourcePages", Collections.emptyList());
        Map<String,Object> work = fields("targetObligationId", TARGET, "evidenceRevision", "evidence-1",
            "terminalForRevision", false, "consumed", false, "readCoverageComplete", true, "readCoverage", packet,
            "repairActionOrigin", "VALIDATION_REPAIR", "pendingReadActions", Collections.emptyList(),
            "remainingRepairActions", Collections.singletonList("READ_COMPLETE_RECORD"));
        audit.put("targetProgressLedger", Collections.singletonList(fields("targetObligationId", TARGET, "visited", true,
            "navigationCount", 3, "relevantEvidenceRevision", "evidence-1", "ruleVersion", "plan-1",
            "noNewFactCounterVersion", "java-agent-target-rounds-1", "noNewFactRequestId", audit.get("requestId"),
            "noNewFactStopReason", "THREE_ROUNDS_NO_NEW_FACT", "noNewFactModelRounds", 3)));
        audit.put("serverTargetInputConsumption", fields("version", "server-target-input-consumption-1",
            "repairActionOriginVersion", "host-repair-action-origin-1", "authority", "CURRENT_SERVER_PROOF",
            "requestId", audit.get("requestId"), "serverPlanRevision", "plan-1", "targets", Collections.singletonList(work),
            "businessProof", false));
        Finding captured = RecoverySemanticGate.captureFirstRound(before, context(), "policy");
        assertEquals(false, inspect(captured).get("allowed"));
        assertEquals("UNDETERMINED", captured.evaluation().status());
        assertEquals(true, RecoverySemanticGate.inspect(captured,"TARGETED_EVIDENCE",context(),Collections.emptyList(),"new-policy").get("allowed"));
        packet.put("pendingPages", Collections.singletonList(159));
        assertEquals(true, inspect(captured).get("allowed"));
        packet.put("pendingPages", Collections.emptyList());
        work.put("remainingRepairActions", Collections.singletonList("RESOLVE_SUBJECT_FIRST"));
        assertEquals(true, inspect(captured).get("allowed"));
    }

    private Map<String,Object> inspect(Finding finding) {
        return RecoverySemanticGate.inspect(finding, "TARGETED_EVIDENCE", context(), Collections.emptyList(), "policy");
    }

    private Finding finding(String code, boolean serverReceipt, boolean readComplete) {
        Map<String,Object> rule = fields("id", TARGET, "predicate", "CONTRACT", "subject", "", "field", "", "parameters", fields("declaredPerformanceRow", fields("documentId", "doc", "proofPageStart", 2, "proofPageEnd", 3)));
        Map<String,Object> audit = fields("requestId", "synthetic-request", "status", "NO_PROGRESS", "code", code,
            "failedToolCount", 0, "targetReceiptAuthority", "CURRENT_SERVER_PROOF", "serverPlanRevision", "plan-1",
            "targetEvidenceRevisions", fields(TARGET, "evidence-1"),
            "remainingTargetObligationIds", Collections.singletonList(TARGET),
            "exhaustedTargetObligationIds", Collections.emptyList(),
            "targetProgressLedger", Collections.singletonList(fields("targetObligationId", TARGET, "visited", true, "navigationCount", 2)));
        if (serverReceipt) audit.put("serverTargetInputConsumption", fields(
            "version", "server-target-input-consumption-1", "authority", "CURRENT_SERVER_PROOF", "requestId", "synthetic-request",
            "serverPlanRevision", "plan-1", "businessProof", false,
            "targets", Collections.singletonList(fields("targetObligationId", TARGET, "evidenceRevision", "evidence-1",
                "terminalForRevision", true, "remainingRepairActions", Collections.emptyList(), "readCoverageComplete", readComplete,
                "consumed", readComplete, "readCoverage", fields("complete", readComplete)))));
        Checkpoint cp = new Checkpoint("synthetic", "Synthetic contract", "test", "HIGH", "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null);
        Map<String,Object> atomic = fields("plan", fields("rules", Collections.singletonList(rule)),
            "conditions", Collections.singletonList(fields("id", TARGET, "status", "UNDETERMINED", "gaps", Collections.singletonList(fields("code", "CONTRACT_REQUIRED")))),
            "gaps", Collections.singletonList(fields("code", "CONTRACT_REQUIRED")), "agentInvestigation", audit);
        return new Finding("finding", "run", cp, new Evaluation("UNDETERMINED", "pending", "", Collections.emptyList(), 0, "RULE"), fields("atomicCheck", atomic));
    }
}

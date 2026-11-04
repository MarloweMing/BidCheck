package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.agent.RunnerNoNewFactFixture;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

class RunnerRoundConsumptionIntegrationTest {
    @Test void navigationAndCachedTurnsKeepLegacyCountsButReachTheRealGate() {
        Map<String,Object> run = RunnerNoNewFactFixture.run(false);
        assertEquals("REPEATED_ACTION_WITHOUT_NEW_EVIDENCE", run.get("code"), run.toString());
        assertEquals(3L, ((Number) run.get("modelCalls")).longValue());
        Map<String,Object> row = ledger(run);
        assertTrue(((Number) row.get("noEvidenceProgressRounds")).longValue() < 3);
        assertEquals(2L, ((Number) row.get("navigationCount")).longValue());
        assertFalse(allowed(finding(run)), "Actual three fruitless rounds must consume only stable default guidance");
        assertEquals(3L, ((Number) row.get("noNewFactModelRounds")).longValue());
        assertEquals("THREE_ROUNDS_NO_NEW_FACT", row.get("noNewFactStopReason"));
        assertEquals(false, row.get("businessProof"));
        assertEquals(Collections.emptyList(), run.get("completedTargetObligationIds"));
    }

    @Test void changedRevisionStartsItsOwnThreeRoundsInsteadOfBorrowingOldOnes() {
        Map<String,Object> run = RunnerNoNewFactFixture.run(true);
        assertEquals("v2", ledger(run).get("relevantEvidenceRevision"));
        assertTrue(((Number) run.get("modelCalls")).longValue() >= 5, run.toString());
        assertEquals(3L, ((Number) ledger(run).get("noNewFactModelRounds")).longValue());
        assertFalse(allowed(finding(run)), run.toString());
    }

    @Test void currentVersionRequestAndVisitAreRequired() {
        for (String key : Arrays.asList("ruleVersion", "relevantEvidenceRevision", "noNewFactRequestId", "noNewFactCounterVersion")) {
            Map<String,Object> run = RunnerNoNewFactFixture.run(false);
            Finding before = finding(run);
            ledger(run).put(key, "wrong");
            assertTrue(allowed(before), key);
        }
        Map<String,Object> run = RunnerNoNewFactFixture.run(false);
        ledger(run).put("visited", false);
        assertTrue(allowed(finding(run)));
    }

    @Test void unreadSourcesValidationRepairAndTransportRemainExecutable() {
        for (String condition : Arrays.asList("unread", "repair", "transport")) {
            Finding finding = finding(RunnerNoNewFactFixture.run(false));
            Map<String,Object> audit = audit(finding);
            Map<String,Object> work = work(audit);
            if (condition.equals("unread")) {
                work.put("readCoverageComplete", false);
                work.put("pendingReadActions", Collections.singletonList(fields("action", "READ_SECTION_CONTINUATION", "sourceId", "tail")));
            } else if (condition.equals("repair")) {
                work.put("repairActionOrigin", "VALIDATION_REPAIR");
                work.put("remainingRepairActions", Collections.singletonList("CORRECT_SOURCE_REFERENCE"));
            } else {
                audit.put("status", "FAILED");
                audit.put("failedToolCount", 1);
            }
            assertTrue(allowed(finding), condition);
        }
    }

    @Test void invalidNewProtocolCannotBecomeLegacyAfterActualSdkFiltering() throws Exception {
        String[] fields = {"noNewFactCounterVersion", "noNewFactRequestId", "noNewFactModelRounds", "noNewFactStopReason"};
        for (String field : fields) {
            for (boolean missing : Arrays.asList(false, true)) {
                Map<String,Object> row = new LinkedHashMap<>(ledger(RunnerNoNewFactFixture.run(false)));
                row.put("noEvidenceProgressRounds", 3);
                if (missing) row.remove(field); else row.put(field, "invalid");
                Map<String,Object> filtered = RunnerNoNewFactFixture.filterLedger(row);
                assertEquals("NO_PROGRESS", filtered.get("status"), filtered.toString());
                assertTrue(allowed(finding(filtered)), "SDK must not downgrade " + field + " missing=" + missing);
                assertEquals("INVALID", ledger(filtered).get("noNewFactCounterVersion"));
            }
        }
        for (Number count : Arrays.<Number>asList(-1, 4, 3.5d)) {
            Map<String,Object> row = new LinkedHashMap<>(ledger(RunnerNoNewFactFixture.run(false)));
            row.put("noEvidenceProgressRounds", 3);
            row.put("noNewFactModelRounds", count);
            Map<String,Object> filtered = RunnerNoNewFactFixture.filterLedger(row);
            assertEquals("NO_PROGRESS", filtered.get("status"), filtered.toString());
            assertTrue(allowed(finding(filtered)), "Invalid real-round counter " + count);
        }
    }

    @Test void pureLegacyStillWorksButAnyOrphanNewFieldPreventsDowngrade() throws Exception {
        Map<String,Object> row = new LinkedHashMap<>(ledger(RunnerNoNewFactFixture.run(false)));
        String[] fields = {"noNewFactCounterVersion", "noNewFactRequestId", "noNewFactModelRounds", "noNewFactStopReason"};
        for (String field : fields) row.remove(field);
        row.put("noEvidenceProgressRounds", 3);
        assertFalse(allowed(finding(RunnerNoNewFactFixture.filterLedger(row))));
        for (String field : fields) {
            Map<String,Object> orphan = new LinkedHashMap<>(row);
            orphan.put(field, "orphan");
            assertTrue(allowed(finding(RunnerNoNewFactFixture.filterLedger(orphan))), field);
        }
    }
    private boolean allowed(Finding original) {
        Map<String,Object> context = fields("recoveryTargetObligationIds", Collections.singletonList("A"));
        Finding captured = RecoverySemanticGate.captureFirstRound(original, context, "policy");
        return Boolean.TRUE.equals(RecoverySemanticGate.inspect(captured, "TARGETED_EVIDENCE", context,
            Collections.emptyList(), "policy").get("allowed"));
    }
    private Finding finding(Map<String,Object> actualRun) {
        Map<String,Object> audit = new LinkedHashMap<>(actualRun);
        String revision = (String) ledger(actualRun).get("relevantEvidenceRevision");
        Map<String,Object> work = fields("targetObligationId", "A", "evidenceRevision", revision,
            "repairActionOrigin", "DEFAULT_GUIDANCE", "remainingRepairActions", Collections.singletonList("READ_COMPLETE_RECORD"),
            "readCoverageComplete", true, "pendingReadActions", Collections.emptyList(), "businessProof", false);
        audit.putAll(fields("targetReceiptAuthority", "CURRENT_SERVER_PROOF", "serverPlanRevision", "plan",
            "targetEvidenceRevisions", fields("A", revision), "serverTargetInputConsumption",
            fields("version", "server-target-input-consumption-1", "authority", "CURRENT_SERVER_PROOF",
                "requestId", actualRun.get("requestId"), "serverPlanRevision", "plan", "businessProof", false,
                "repairActionOriginVersion", "host-repair-action-origin-1", "targets", Collections.singletonList(work))));
        Map<String,Object> body = fields("plan", fields("rules", Collections.singletonList(fields("id", "A", "predicate", "DECLARATION",
            "subject", "", "field", "commitment", "expected", "commitment", "parameters", fields()))),
            "agentInvestigation", audit, "conditions", Collections.singletonList(fields("id", "A", "status", "UNDETERMINED")),
            "gaps", Collections.singletonList(fields("code", "MISSING_COMMITMENT")));
        Checkpoint cp = new Checkpoint("cp", "synthetic", "test", "HIGH", "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null);
        return new Finding("finding", "run", cp, new Evaluation("UNDETERMINED", "pending", "", Collections.emptyList(), 0, "RULE"), fields("atomicCheck", body));
    }
    private static Map<String,Object> ledger(Map<String,Object> run) {
        return (Map<String,Object>) ((List<?>) run.get("targetProgressLedger")).get(0);
    }
    private static Map<String,Object> audit(Finding finding) {
        return (Map<String,Object>) ((Map<?,?>) finding.assessment().get("atomicCheck")).get("agentInvestigation");
    }
    private static Map<String,Object> work(Map<String,Object> audit) {
        return (Map<String,Object>) ((List<?>) ((Map<?,?>) audit.get("serverTargetInputConsumption")).get("targets")).get(0);
    }
}

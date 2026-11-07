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

    @Test void verifiedValidationRereadStopsOnlyForReturnedOriginalsAndUnchangedEvidence() {
        for(String disposition:Arrays.asList("APPOINTMENT_ENDPOINT_UNCITED","OBSERVED_CERTIFICATE_RECORDS_READ")) {
            Finding before=finding("VALIDATION_REPAIR",3);
            Map<String,Object> audit=audit(before);
            audit.put("status","EVIDENCE_UPDATED");
            Map<String,Object> row=(Map<String,Object>)((List<?>)audit.get("targetProgressLedger")).get(0);
            row.putAll(fields("noNewFactCounterVersion","java-agent-target-rounds-1","noNewFactRequestId","request",
                "noNewFactStopReason","THREE_ROUNDS_NO_NEW_FACT","noNewFactModelRounds",3));
            Map<String,Object> originals=fields("version","validated-read-work-1","authority","CURRENT_SERVER_PROOF",
                "targetObligationId",TARGET,"evidenceRevision","evidence","reason",disposition,"businessProof",false,
                "requiredSourceIds",Arrays.asList("original","independent"),"readSourceIds",Arrays.asList("original","independent"),
                "pendingSourceIds",Collections.emptyList());
            work(before).put("validationReadWork",originals);
            Finding captured=capture(before);
            assertEquals(false,gate(captured,context()).get("allowed"),disposition);
            assertEquals("UNDETERMINED",captured.evaluation().status());
            Map<String,Object> changed=context();changed.put("recoverySourceRevision","new-bid-document");
            assertEquals(true,gate(captured,changed).get("allowed"),disposition);
            originals.put("pendingSourceIds",Collections.singletonList("unread"));
            assertEquals(true,gate(captured,context()).get("allowed"),disposition);
            originals.put("pendingSourceIds",Collections.emptyList());originals.put("readSourceIds",Collections.singletonList("original"));
            assertEquals(true,gate(captured,context()).get("allowed"),disposition);
            originals.put("readSourceIds",Arrays.asList("original","independent"));audit.put("status","FAILED");
            assertEquals(true,gate(captured,context()).get("allowed"),"a transport retry is independent");
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

    @Test void callLimitRetainsIndependentlyStoppedFlatCertificateUrlOnly() {
        Finding before=flatUrl();
        Finding captured=capture(before);
        assertEquals(false,gate(captured,context()).get("allowed"));
        assertEquals("UNDETERMINED",captured.evaluation().status());
        assertEquals(atomic(before).get("gaps"),atomic(captured).get("gaps"));
        assertEquals(false,work(captured).get("consumed"));
        assertEquals(Collections.singletonList("unvisited-screenshot"),gate(captured,
            fields("recoveryTargetObligationIds",Arrays.asList(TARGET,"unvisited-screenshot"))).get("eligibleTargetIds"));
        Map<String,Object> changed=context();changed.put("recoverySourceRevision","new-original-version");
        assertEquals(true,gate(captured,changed).get("allowed"));
    }

    @Test void flatCallLimitNeedsSameHostPlanRevisionAndThreeTargetRounds() {
        for(String invalid:Arrays.asList("ruleVersion","relevantEvidenceRevision","noNewFactRequestId","short-rounds",
                "pending-read","unknown-authority","missing-flat-shape","incomplete-nested-shape",
                "no-evidence","wrong-target","foreign-fact","unlocated-source")) {
            Finding before=flatUrl();
            Map<String,Object> ledger=(Map<String,Object>)((List<?>)audit(before).get("targetProgressLedger")).get(0);
            if(Arrays.asList("ruleVersion","relevantEvidenceRevision","noNewFactRequestId").contains(invalid))ledger.put(invalid,"stale");
            if("short-rounds".equals(invalid))ledger.put("noNewFactModelRounds",2);
            if("pending-read".equals(invalid))work(before).put("pendingReadActions",Collections.singletonList(fields("action","READ_SECTION_CONTINUATION")));
            if("unknown-authority".equals(invalid))receipt(before).put("authority","MODEL");
            if("missing-flat-shape".equals(invalid))work(before).remove("readCoverage");
            if("incomplete-nested-shape".equals(invalid))work(before).put("readCoverage",fields("complete",false));
            Map<String,Object> witness=(Map<String,Object>)work(before).get("flatReadEvidence");
            if("no-evidence".equals(invalid))work(before).remove("flatReadEvidence");
            if("wrong-target".equals(invalid))witness.put("targetObligationId","another-target");
            if("foreign-fact".equals(invalid))audit(before).put("acceptedFactIds",Collections.singletonList("unrelated-fact"));
            if("unlocated-source".equals(invalid))((Map<String,Object>)((List<?>)witness.get("sources")).get(0)).put("documentVersion",0);
            assertEquals(true,gate(capture(before),context()).get("allowed"),invalid);
        }
    }

    @Test void actuallyReturnedBoundQueryOriginalCanWitnessAReadWithoutNewFacts() {
        Finding before=flatUrl();
        Map<String,Object> witness=(Map<String,Object>)work(before).get("flatReadEvidence");
        witness.put("acceptedFacts",Collections.emptyList());witness.put("acceptedFactIds",Collections.emptyList());
        witness.put("readSourceIds",Collections.singletonList("query-original"));
        assertEquals(false,gate(capture(before),context()).get("allowed"));
        witness.put("readSourceIds",Collections.emptyList());witness.put("sources",Collections.emptyList());
        assertEquals(true,gate(capture(before),context()).get("allowed"));
    }

    @Test void contractCannotUseFlatCoverageEvenWhenAllThreeRoundsStopped() {
        for(boolean declared:Arrays.asList(true,false)) {
            Finding before=flatUrl();
            Map<String,Object> rule=(Map<String,Object>)((List<?>)map(body(before).get("plan")).get("rules")).get(0);
            rule.put("predicate","CONTRACT");
            if(declared)rule.put("parameters",fields("declaredPerformanceRow",fields("documentId","doc","proofPageStart",2,"proofPageEnd",4)));
            assertEquals(true,gate(capture(before),context()).get("allowed"));
        }
    }

    /** Reduced shape of the host URL receipt from 115a7b00 (full109-04).
     * The other three certificate targets were unvisited and remain independent. */
    private Finding flatUrl() {
        Finding before=finding("DEFAULT_GUIDANCE",1);
        Map<String,Object> rule=(Map<String,Object>)((List<?>)map(body(before).get("plan")).get("rules")).get(0);
        rule.put("predicate","MATERIAL");rule.put("field","material");rule.put("expected","查询网址");
        rule.put("parameters",fields("certificateProofCategory","职业健康安全管理体系认证"));
        audit(before).put("status","EVIDENCE_UPDATED");audit(before).put("code","INVESTIGATION_CALL_LIMIT");
        work(before).put("readCoverage",Collections.emptyMap());
        audit(before).put("acceptedFactIds",Collections.singletonList("url-fact"));
        work(before).put("flatReadEvidence",fields("version","server-flat-target-read-1","authority","CURRENT_SERVER_PROOF",
            "businessProof",false,"requestId","request","serverPlanRevision","plan","targetObligationId",TARGET,
            "evidenceRevision","evidence","readSourceIds",Collections.emptyList(),"acceptedFactIds",Collections.singletonList("url-fact"),
            "acceptedFacts",Collections.singletonList(fields("factId","url-fact","sourceIds",Collections.singletonList("query-original"))),
            "sources",Collections.singletonList(fields("id","query-original","documentId","bid",
                "documentHash","0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","documentVersion",1,
                "page",454,"locationType","PAGE","parserFingerprint","frozen-parser"))));
        Map<String,Object> ledger=(Map<String,Object>)((List<?>)audit(before).get("targetProgressLedger")).get(0);
        ledger.putAll(fields("navigationCount",0,"noNewFactCounterVersion","java-agent-target-rounds-1",
            "noNewFactModelRounds",3,"noNewFactRequestId","request","noNewFactStopReason","THREE_ROUNDS_NO_NEW_FACT"));
        return before;
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

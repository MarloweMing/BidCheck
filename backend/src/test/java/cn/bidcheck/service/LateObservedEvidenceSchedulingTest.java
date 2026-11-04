package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.engine.LateObservedEvidenceFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.bidcheck.service.BidService.*;

class LateObservedEvidenceSchedulingTest {
    static final class Scenario {
        final Map<String,Stored> rows = new LinkedHashMap<>();
        final Store store;
        final Finding finding;
        final Map<String,Object> change;
        final String jobId = CheckResolution.jobId("run", "cp");
        Scenario(boolean selected, boolean executed) throws Exception {
            this(selected, executed, false);
        }
        Scenario(boolean selected, boolean executed, boolean scalar) throws Exception {
            this(LateObservedEvidenceFixture.dependency(selected, executed, scalar, Collections.emptyMap()));
        }
        Scenario(Map<String,Object> dependency) throws Exception {
            RecordMapper mapper = mock(RecordMapper.class);
            when(mapper.get(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
            doAnswer(c -> { Stored row = c.getArgument(0); rows.put(row.id, row); return null; }).when(mapper).save(any(Stored.class));
            when(mapper.getMany(anyList())).thenAnswer(c -> {
                List<Stored> result = new ArrayList<>();
                for (Object id : (List<?>) c.getArgument(0)) if (rows.containsKey(id)) result.add(rows.get(id));
                return result;
            });
            when(mapper.list(anyString(), anyString(), anyString())).thenAnswer(c -> {
                List<Stored> result = new ArrayList<>();
                for (Stored row : rows.values()) if (row.kind.equals(c.getArgument(0)) && row.projectId.equals(c.getArgument(1)) && row.parentId.equals(c.getArgument(2))) result.add(row);
                return result;
            });
            store = new Store(mapper, new ObjectMapper());
            change = CheckResolution.map(dependency.remove("fixtureChange"));
            Map<String,Object> rule = new LinkedHashMap<>(CheckResolution.map(dependency.get("target")));
            rule.put("id", "price");
            Map<String,Object> atomic = fields("complete", false, "plan", fields("rules", Arrays.asList(rule)),
                "conditions", Arrays.asList(fields("id", "price", "status", "UNDETERMINED")),
                "gaps", Arrays.asList(fields("code", "PRICE_EVIDENCE_REQUIRED")),
                "recoveryEvidenceDependencies", Arrays.asList(dependency));
            Checkpoint cp = new Checkpoint("cp", "合成报价", "报价", "HIGH", "SEMANTIC_RESPONSE", "price", "<=", "100", "元", true, true, true, null);
            finding = new Finding(stableId("run:cp"), "run", cp, new Evaluation("UNDETERMINED", "缺报价", "", Collections.emptyList(), 0, "FOCUSED_ATOMIC_RULES"), fields("atomicCheck", atomic));
            store.save("finding", finding.id(), "project", "run", finding);
            store.save("check_resolution", jobId, "project", "run", fields("id", jobId, "checkpointId", "cp", "status", "BLOCKED_NO_PROGRESS", "strategy", "TARGETED_EVIDENCE"));
            OrdinaryRecoveryDependencies.bind(store, "project", "run", finding);
        }
        int publish() {
            OrdinaryRecoveryDependencies.publishChanges(store, "project", "run", "other", fields("changedEvidence", Arrays.asList(change)));
            return OrdinaryRecoveryDependencies.schedule(store, "project", "run");
        }
    }

    @Test void delayedPublicationOfActuallyObservedIdentityDoesNotReopen() throws Exception {
        Scenario s = new Scenario(true, true);
        assertEquals(0, s.publish(), "the actual engine read already used this identity on this frozen original");
        assertEquals("BLOCKED_NO_PROGRESS", s.store.getMap("check_resolution", s.jobId).get("status"));
        assertEquals(1, s.store.maps("ordinary_recovery_change", "project", "run").size(), "retain the real publication event");
    }

    @Test void changedValueLotOrUnitStillSchedulesAndEntersLocalRecoveryGate() throws Exception {
        for (String field : Arrays.asList("value", "lot", "unit")) {
            Scenario s = new Scenario(true, true);
            Map<String,Object> fact = CheckResolution.map(s.change.get("fact"));
            if (field.equals("lot")) {
                fact.put("scope", "CURRENT_PROJECT");
                fact.put("basis", "updated explicitly verified role basis");
                fact.put("attributes", fields("object", "合成建设有限公司", "lot", "新标段"));
            } else fact.put(field, "new-" + field);
            s.change.put("fact", fact);
            assertEquals(1, s.publish(), field);
            Map<String,Object> job = s.store.getMap("check_resolution", s.jobId);
            Map<String,Object> context = RecoveryInputs.context(s.finding, "RECOMPUTE_SHARED_EVIDENCE", Collections.emptyMap());
            assertTrue(RecoveryInputs.applyDependencyTrigger(context, job, s.finding));
            assertEquals(false, context.get("recoveryModelAllowed"));
            assertEquals(Arrays.asList("price"), context.get("recoveryTargetObligationIds"));
            assertEquals(true, RecoverySemanticGate.inspect(s.finding, "RECOMPUTE_SHARED_EVIDENCE", context, Collections.emptyList(), "policy").get("allowed"));
        }
    }

    @Test void changedOriginalHashVersionOrParserRemainsNewEvidence() throws Exception {
        for (String key : Arrays.asList("documentHash", "documentVersion", "parserFingerprint")) {
            Scenario s = new Scenario(true, true);
            Map<String,Object> source = new LinkedHashMap<>(CheckResolution.maps(s.change.get("sources")).get(0));
            source.put(key, key.equals("documentVersion") ? 2 : "changed");
            s.change.put("sources", Arrays.asList(source));
            assertEquals(1, s.publish(), key);
        }
    }

    @Test void unselectedOrNeverExecutedRuleCannotBorrowGlobalFactsAsObserved() throws Exception {
        assertEquals(1, new Scenario(false, true).publish());
        assertEquals(1, new Scenario(true, false).publish());
    }

    @Test void missingSourceIdentityDoesNotSuppressLateEvidence() throws Exception {
        Scenario s = new Scenario(true, true);
        Map<String,Object> body = new LinkedHashMap<>(CheckResolution.atomic(s.finding));
        Map<String,Object> dep = new LinkedHashMap<>(CheckResolution.maps(body.get("recoveryEvidenceDependencies")).get(0));
        dep.put("observedEvidence", fields("version", "untrusted-model-claim", "authority", "MODEL", "inputs", Arrays.asList(s.change)));
        body.put("recoveryEvidenceDependencies", Arrays.asList(dep));
        Finding forged = new Finding(s.finding.id(), "run", s.finding.checkpoint(), s.finding.evaluation(), fields("atomicCheck", body));
        OrdinaryRecoveryDependencies.bind(s.store, "project", "run", forged);
        assertEquals(1, s.publish());
    }
    @Test void observedScalarCounterevidenceIsNotReprocessedWhenPublishedLater() throws Exception {
        Scenario s = new Scenario(true, true, true);
        assertEquals(0, s.publish());
    }

    @Test void genuinelyChangedScalarScopeStillTriggersRevalidation() throws Exception {
        Scenario s = new Scenario(true, true, true);
        Map<String,Object> fact = CheckResolution.map(s.change.get("fact"));
        fact.put("scope", "HISTORICAL_PROJECT");
        s.change.put("fact", fact);
        assertEquals(1, s.publish(), "changed scope must be re-evaluated, not treated as the earlier current-project amount");
    }

    @Test void unvisitedBranchRetainsItsOwnOldObservationInsteadOfBorrowingCurrentScalar() throws Exception {
        Map<String,Object> old = LateObservedEvidenceFixture.dependency(true, true);
        Map<String,Object> next = LateObservedEvidenceFixture.dependency(false, true, true, old);
        assertNotNull(old.get("observedEvidence"));
        assertEquals(old.get("observedEvidence"), next.get("observedEvidence"));
    }

    @Test void currentProtocolWithIncompleteSourceIdentityCannotSuppress() throws Exception {
        Scenario s = new Scenario(true, true);
        Map<String,Object> body = new LinkedHashMap<>(CheckResolution.atomic(s.finding));
        Map<String,Object> dep = new LinkedHashMap<>(CheckResolution.maps(body.get("recoveryEvidenceDependencies")).get(0));
        Map<String,Object> receipt = new LinkedHashMap<>(CheckResolution.map(dep.get("observedEvidence")));
        Map<String,Object> input = new LinkedHashMap<>(s.change);
        Map<String,Object> source = new LinkedHashMap<>(CheckResolution.maps(input.get("sources")).get(0));
        source.remove("parserFingerprint");
        input.put("sources", Arrays.asList(source));
        receipt.put("inputs", Arrays.asList(input));
        dep.put("observedEvidence", receipt);
        body.put("recoveryEvidenceDependencies", Arrays.asList(dep));
        Finding incomplete = new Finding(s.finding.id(), "run", s.finding.checkpoint(), s.finding.evaluation(), fields("atomicCheck", body));
        OrdinaryRecoveryDependencies.bind(s.store, "project", "run", incomplete);
        assertEquals(1, s.publish());
    }

    @Test void transportFailureKeepsItsExistingRetryRoute() throws Exception {
        Scenario s = new Scenario(true, true);
        Map<String,Object> atomic = new LinkedHashMap<>(CheckResolution.atomic(s.finding));
        atomic.put("agentInvestigation", fields("status", "FAILED", "errorCode", "WORKER_DISCONNECTED", "failedToolCount", 1));
        Finding failed = new Finding(s.finding.id(), "run", s.finding.checkpoint(), s.finding.evaluation(), fields("atomicCheck", atomic));
        s.store.save("finding", failed.id(), "project", "run", failed);
        s.store.save("check_resolution", s.jobId, "project", "run", fields("id", s.jobId, "checkpointId", "cp", "status", "FAILED", "strategy", "RETRY_FAILED_STEP"));
        assertEquals(0, s.publish());
        assertEquals("RETRY_FAILED_STEP", s.store.getMap("check_resolution", s.jobId).get("strategy"));
        assertEquals("FAILED", s.store.getMap("check_resolution", s.jobId).get("status"));
    }

    @Test void completeBidderProjectionIgnoresLateMemberAndLeadEventsButRetainsAllThree() throws Exception {
        Map<String,Object> dep = LateObservedEvidenceFixture.consortium(true, false);
        List<Map<String,Object>> changes = CheckResolution.maps(dep.remove("fixtureChanges"));
        assertEquals(3, changes.size(), "two member facts and one lead fact, not identical bidder facts");
        Scenario s = new Scenario(dep);
        OrdinaryRecoveryDependencies.publishChanges(s.store, "project", "run", "other", fields("changedEvidence", changes));
        assertEquals(0, OrdinaryRecoveryDependencies.schedule(s.store, "project", "run"));
        assertEquals(3, CheckResolution.maps(s.store.maps("ordinary_recovery_change", "project", "run").get(0).get("changes")).size());
    }

    @Test void projectionDoesNotHideNewMemberOrChangedOriginal() throws Exception {
        for (String changed : Arrays.asList("member", "quote", "documentHash", "documentVersion", "parserFingerprint", "scope", "unit")) {
            Map<String,Object> dep = LateObservedEvidenceFixture.consortium(true, false);
            Map<String,Object> change = CheckResolution.maps(dep.remove("fixtureChanges")).get(0);
            Map<String,Object> fact = new LinkedHashMap<>(CheckResolution.map(change.get("fact")));
            Map<String,Object> attrs = new LinkedHashMap<>(CheckResolution.map(fact.get("attributes")));
            if (changed.equals("member")) {
                attrs.put("members", Arrays.asList("合成建设有限公司", "合成设计有限公司", "合成新增有限公司"));
            } else if (changed.equals("scope")) {
                attrs.put("lot", "new-lot");
            } else if (changed.equals("unit")) {
                fact.put("unit", "changed");
            } else {
                Map<String,Object> source = new LinkedHashMap<>(CheckResolution.maps(change.get("sources")).get(0));
                if (changed.equals("quote")) {
                    Map<String,Object> evidence = new LinkedHashMap<>(CheckResolution.map(source.get("evidence")));
                    evidence.put("quote", "different original text"); source.put("evidence", evidence);
                } else source.put(changed, changed.equals("documentVersion") ? 2 : "changed");
                change.put("sources", Arrays.asList(source));
            }
            fact.put("attributes", attrs); change.put("fact", fact);
            Scenario s = new Scenario(dep);
            OrdinaryRecoveryDependencies.publishChanges(s.store, "project", "run", "other", fields("changedEvidence", Arrays.asList(change)));
            assertEquals(1, OrdinaryRecoveryDependencies.schedule(s.store, "project", "run"), changed);
        }
    }

    @Test void missingCompleteSetCannotClaimProjectionAndLeadDependentRuleStillWatchesLead() throws Exception {
        Map<String,Object> complete = LateObservedEvidenceFixture.consortium(true, false);
        List<Map<String,Object>> changes = CheckResolution.maps(complete.remove("fixtureChanges"));
        Scenario incomplete = new Scenario(LateObservedEvidenceFixture.consortium(false, false));
        // Missing complete set cannot resolve any party, but the target bidder relation remains relevant.
        Map<String,Object> bidder = new LinkedHashMap<>(changes.get(0));
        Map<String,Object> fact = new LinkedHashMap<>(CheckResolution.map(bidder.get("fact")));
        fact.put("subject", "投标人"); bidder.put("fact", fact);
        OrdinaryRecoveryDependencies.publishChanges(incomplete.store, "project", "run", "other", fields("changedEvidence", Arrays.asList(bidder)));
        assertEquals(1, OrdinaryRecoveryDependencies.schedule(incomplete.store, "project", "run"));
        Map<String,Object> leadDep = LateObservedEvidenceFixture.consortium(true, true);
        leadDep.remove("fixtureChanges");
        // A lead rule with a not-yet-observed lead fact must still schedule it.
        Map<String,Object> leadReceipt = new LinkedHashMap<>(CheckResolution.map(leadDep.get("observedEvidence")));
        leadReceipt.remove("inputs"); leadDep.put("observedEvidence", leadReceipt);
        Scenario lead = new Scenario(leadDep);
        OrdinaryRecoveryDependencies.publishChanges(lead.store, "project", "run", "other", fields("changedEvidence", changes));
        assertEquals(1, OrdinaryRecoveryDependencies.schedule(lead.store, "project", "run"));
    }

    @Test void explicitLeadDependencyAndContraryLeadDoNotBorrowBidderProjection() throws Exception {
        for (boolean targetChanged : Arrays.asList(true, false)) {
            Map<String,Object> dep = LateObservedEvidenceFixture.consortium(true, false);
            List<Map<String,Object>> changes = CheckResolution.maps(dep.remove("fixtureChanges"));
            Map<String,Object> lead = changes.stream().filter(c -> "联合体牵头人".equals(CheckResolution.map(c.get("fact")).get("subject"))).findFirst().get();
            if (targetChanged) {
                Map<String,Object> target = new LinkedHashMap<>(CheckResolution.map(dep.get("target")));
                target.put("parameters", fields("employer", "联合体牵头人"));
                dep.put("target", target);
            } else {
                Map<String,Object> fact = new LinkedHashMap<>(CheckResolution.map(lead.get("fact")));
                Map<String,Object> attrs = new LinkedHashMap<>(CheckResolution.map(fact.get("attributes")));
                fact.put("value", "合成设计有限公司");fact.put("rawValue", "合成设计有限公司");
                attrs.put("object", "合成设计有限公司");fact.put("attributes", attrs);lead.put("fact", fact);
            }
            Scenario s = new Scenario(dep);
            OrdinaryRecoveryDependencies.publishChanges(s.store, "project", "run", "other", fields("changedEvidence", Arrays.asList(lead)));
            assertEquals(1, OrdinaryRecoveryDependencies.schedule(s.store, "project", "run"), "explicit dependency or contrary role must revalidate");
        }
    }

}

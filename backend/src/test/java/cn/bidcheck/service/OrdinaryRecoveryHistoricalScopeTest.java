package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrdinaryRecoveryHistoricalScopeTest {
    @Test void historicalMonthsCannotWakeConcreteRoleOrUnresolvedSocialTargets() {
        for (String subject : Arrays.asList("Alex", "项目负责人", "")) {
            assertFalse(OrdinaryRecoveryDependencies.matches(binding(subject), change("MONTHS", "HISTORICAL_PROJECT", "source-one", "3")), subject);
        }
    }

    @Test void currentCounterevidenceAndNewCurrentSourceRemainEligible() {
        Map<String,Object> dependency = binding("项目负责人");
        for (Map<String,Object> event : Arrays.asList(change("MONTHS", "CURRENT_PROJECT", "new-source", "3"),
                change("MONTHS", "CURRENT_PROJECT", "old-source", "1"))) {
            assertTrue(OrdinaryRecoveryDependencies.matches(dependency, event));
        }
    }

    @Test void missingAndUnresolvedScopesAreNotSilentlyClassifiedAsHistorical() {
        for (String scope : Arrays.asList("", "UNRESOLVED", "UNRESOLVED_DOCUMENT")) {
            assertTrue(OrdinaryRecoveryDependencies.matches(binding("Alex"), change("MONTHS", scope, "source-one", "3")));
        }
        Map<String,Object> withoutScope = change("MONTHS", "", "source-one", "3");
        ((Map<String,Object>) withoutScope.get("fact")).remove("scope");
        assertTrue(OrdinaryRecoveryDependencies.matches(binding("Alex"), withoutScope));
    }

    @Test void historicalContractEvidenceIsNotSuppressedByTheSocialScopeRule() {
        Map<String,Object> dependency = fields("target", fields("predicate", "CONTRACT", "subject", "Alex"));
        assertTrue(OrdinaryRecoveryDependencies.matches(dependency, change("CONTRACT", "HISTORICAL_PROJECT", "contract-source", "3")));
    }

    @Test void historicalEventIsRejectedBeforeSchedulingButCurrentEvidenceStillQueues() {
        assertEquals(0, schedule("HISTORICAL_PROJECT"));
        assertEquals(1, schedule("CURRENT_PROJECT"));
        assertEquals(1, schedule("UNRESOLVED"));
    }

    private int schedule(String scope) {
        Map<String,Stored> rows = new LinkedHashMap<>();
        RecordMapper mapper = mock(RecordMapper.class);
        Store store = new Store(mapper, new ObjectMapper());
        when(mapper.get(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        doAnswer(call -> { Stored row = call.getArgument(0); rows.put(row.id, row); return null; }).when(mapper).save(any());
        when(mapper.list(anyString(), any(), any())).thenAnswer(call -> {
            List<Stored> found = new ArrayList<>();
            for (Stored row : rows.values()) if (row.kind.equals(call.getArgument(0))
                    && Objects.equals(row.projectId, call.getArgument(1)) && Objects.equals(row.parentId, call.getArgument(2))) found.add(row);
            return found;
        });
        Checkpoint cp = new Checkpoint("cp", "Synthetic social", "person", "HIGH", "SEMANTIC_RESPONSE", "social", "EXISTS", "", "", true, true, true, null);
        Finding finding = new Finding(stableId("run:cp"), "run", cp,
            new Evaluation("UNDETERMINED", "proof unresolved", "", Collections.emptyList(), 0, "RULE"),
            fields("atomicCheck", fields("conditions", Collections.singletonList(fields("id", "SOCIAL", "status", "UNDETERMINED")),
                "gaps", Collections.singletonList(fields("code", "CONTENT_EVIDENCE_REQUIRED")))));
        store.save("finding", finding.id(), "project", "run", finding);
        store.save("ordinary_recovery_binding", "binding", "project", "run", binding("Alex"));
        store.save("ordinary_recovery_change", "event", "project", "run", fields("id", "event", "revision", 2,
            "producerCheckpointId", "other", "changes", Collections.singletonList(change("MONTHS", scope, "new-source", "3"))));
        store.save("check_resolution", "job", "project", "run", fields("id", "job", "checkpointId", "cp", "status", "BLOCKED_NO_PROGRESS"));
        int scheduled = OrdinaryRecoveryDependencies.schedule(store, "project", "run");
        assertEquals(scope.equals("HISTORICAL_PROJECT") ? "BLOCKED_NO_PROGRESS" : "QUEUED", store.getMap("check_resolution", "job").get("status"));
        return scheduled;
    }

    private Map<String,Object> binding(String subject) {
        return fields("id", "binding", "checkpointId", "cp", "obligationId", "SOCIAL", "observationKey", "social-key", "observedRevision", 1,
            "target", fields("predicate", "MONTHS", "subject", subject, "field", "socialInsurance", "unit", "月"),
            "resolvedSubjects", Collections.singletonList("Alex"));
    }

    private Map<String,Object> change(String kind, String scope, String source, String value) {
        return fields("recordType", "FACT", "itemId", "fact-event", "fact", fields("kind", kind, "subject", "Alex", "field", "socialInsurance",
            "value", value, "unit", "月", "scope", scope, "basis", "actual contributions", "sourceIds", Collections.singletonList(source),
            "attributes", fields("person", "Alex", "employer", "Acme", "months", Arrays.asList("2025-01", "2025-02", "2025-03"))),
            "sources", Collections.singletonList(fields("id", source, "documentId", "synthetic-bid", "documentHash", "hash", "documentVersion", 1, "page", 8)));
    }
}

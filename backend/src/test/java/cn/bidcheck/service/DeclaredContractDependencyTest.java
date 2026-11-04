package cn.bidcheck.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;
class DeclaredContractDependencyTest {
    @Test void anotherNamedContractOutsideTheDeclaredPacketDoesNotWakeTheRow() {
        assertFalse(OrdinaryRecoveryDependencies.matches(binding(), change(20, "Other contract")));
    }
    @Test void sameRowCounterEvidenceAndSameNamedSupplementRemainRelevant() {
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(), change(11, "Alpha contract")));
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(), change(20, "Alpha contract")));
    }
    @Test void incompleteSourceIdentityAndNonContractDependenciesRemainConservative() {
        Map<String,Object> missing = change(20, "Other contract");
        missing.put("sources", Collections.singletonList(fields("id", "source")));
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(), missing));
        Map<String,Object> generic = binding();
        ((Map<String,Object>) generic.get("target")).put("parameters", fields());
        assertTrue(OrdinaryRecoveryDependencies.matches(generic, change(20, "Other contract")));
        Map<String,Object> numbered = change(20, "Other contract");
        numbered.put("fact", fields("kind", "CONTRACT", "field", "contractNumber", "value", "OTHER-123",
            "attributes", fields("contractNumber", "OTHER-123"), "sourceIds", Collections.singletonList("source")));
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(), numbered), "Number-only identity remains conservative without a verified row number");
    }
    @Test void semanticGateIdOnlySourcesAndCurrentRolePrerequisiteRemainExecutable() {
        Map<String,Object> idOnly = change(20, "Other contract");
        idOnly.put("sources", Collections.singletonList(fields("id", "source")));
        assertTrue(OrdinaryRecoveryDependencies.matches(binding(), idOnly), "No page identity must not erase a potential counterfact");
        Map<String,Object> target = binding();
        ((Map<String,Object>) target.get("target")).put("subject", "项目负责人");
        Map<String,Object> role = change(20, "Other contract");
        role.put("fact", fields("kind", "PERSON_ROLE", "scope", "CURRENT_PROJECT", "subject", "项目负责人",
            "value", "Synthetic Person", "sourceIds", Collections.singletonList("source")));
        assertTrue(OrdinaryRecoveryDependencies.matches(target, role));
    }

    private Map<String,Object> binding() {
        return fields("target", fields("predicate", "CONTRACT", "parameters", fields("declaredPerformanceRow",
            fields("documentId", "doc", "documentHash", "hash", "documentVersion", 1,
                "proofPageStart", 10, "proofPageEnd", 12, "separatorTitle", "Alpha contract"))));
    }
    private Map<String,Object> change(int page, String name) {
        return fields("itemId", "fact-id", "recordType", "FACT", "fact", fields("kind", "CONTRACT", "field", "contractName",
            "value", name, "sourceIds", Collections.singletonList("source")), "sources", Collections.singletonList(
                fields("id", "source", "documentId", "doc", "documentHash", "hash", "documentVersion", 1,
                    "locationType", "PAGE", "location", page)));
    }
}

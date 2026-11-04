package cn.bidcheck.verification.engine;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementSourceReadinessTest {
    private Decision decision(String executor, String code) {
        Map<String,Object> proof = linked("executor", executor, "baseConfiguration",
            linked("status", "UNDETERMINED", "gaps", Collections.singletonList(linked("code", code))));
        return new Decision("EXTRA", "合成额外人员评分", "UNDETERMINED", "基础配置待核",
            Collections.emptyList(), Collections.singletonList(gap("SCORING_BASE_CONFIGURATION_REQUIRED", "待核")), proof);
    }

    @Test void missingTenderSourceDoesNotRequestMoreBidFactsOrBecomeCompleted() {
        Decision unresolved = decision("source-extra-personnel-score-1", "SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED");
        assertFalse(AgentInvestigationScope.requiresEvidence(unresolved));
        assertFalse(AgentInvestigationScope.closed(unresolved));
        assertEquals("UNDETERMINED", unresolved.status());
        assertFalse(unresolved.gaps().isEmpty());
    }

    @Test void missingPersonnelEvidenceAndUnrecognizedProofStillRequireInvestigation() {
        assertTrue(AgentInvestigationScope.requiresEvidence(
            decision("source-extra-personnel-score-1", "PERSON_ROLE_REQUIRED")));
        assertTrue(AgentInvestigationScope.requiresEvidence(
            decision("other-executor", "SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED")));
    }
}

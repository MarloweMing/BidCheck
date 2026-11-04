package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.fields;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTargetReadinessTest {
    private Map<String,Object> rule(String id, String predicate) {
        return fields("id", id, "description", "合成条件", "predicate", predicate,
            "subject", "", "field", "material", "operator", "EXISTS", "expected", "承诺书",
            "unit", "", "parameters", Collections.emptyMap(), "sourceQuote", "合成原文",
            "logic", "", "children", Collections.emptyList());
    }

    private Finding finding(List<Map<String,Object>> rules, List<Map<String,Object>> conditions) {
        Checkpoint cp = new Checkpoint("synthetic", "合成", "人员", "HIGH", "SEMANTIC_RESPONSE",
            "compliance", "RESPONDS", "合成原文", "", true, true, true, null);
        return new Finding("f", "r", cp, new Evaluation("UNDETERMINED", "缺口保留", "",
            Collections.emptyList(), 0, "RULE"), fields("atomicCheck", fields(
                "plan", fields("rules", rules), "conditions", conditions)));
    }

    private Map<String,Object> inspect(Finding finding, String strategy, String... targets) {
        return RecoverySemanticGate.inspect(finding, strategy,
            fields("recoveryTargetObligationIds", Arrays.asList(targets)), Collections.emptyList(), "v1");
    }

    @Test void rubricWithoutFactContractDoesNotScheduleAnEmptyInvestigation() {
        Finding f = finding(Arrays.asList(rule("RUBRIC", "QUALITY_ADVISORY"), rule("MATERIAL", "MATERIAL")),
            Collections.emptyList());
        assertEquals(false, inspect(f, "TARGETED_EVIDENCE", "RUBRIC").get("allowed"));
        assertEquals(Collections.singletonList("MATERIAL"),
            inspect(f, "TARGETED_EVIDENCE", "RUBRIC", "MATERIAL").get("eligibleTargetIds"));
        assertEquals("UNDETERMINED", f.evaluation().status());
        assertEquals(true, inspect(f, "RECOMPUTE_SHARED_EVIDENCE", "RUBRIC").get("allowed"));
        Finding changed = finding(Collections.singletonList(rule("RUBRIC", "MATERIAL")), Collections.emptyList());
        assertEquals(true, inspect(changed, "TARGETED_EVIDENCE", "RUBRIC").get("allowed"));
    }

    @Test void knownIdentitySuffixWithoutExecutableRuleDoesNotScheduleAnEmptyInvestigation() {
        Finding f = finding(Collections.singletonList(rule("SIGN", "VISUAL")), Collections.emptyList());
        assertEquals(false, inspect(f, "TARGETED_EVIDENCE", "SIGN:identity").get("allowed"));
        assertEquals(true, inspect(f, "TARGETED_EVIDENCE", "SIGN").get("allowed"));
        assertEquals(true, inspect(f, "VERIFY_SOURCE_PAGES", "SIGN:identity").get("allowed"));
        assertEquals(true, inspect(f, "TARGETED_EVIDENCE", "LEGACY_UNKNOWN").get("allowed"));
    }

    @Test void ambiguousTenderBaseCannotBeResolvedByMoreBidAppointments() {
        Map<String,Object> proof = fields("executor", "source-extra-personnel-score-1",
            "baseConfiguration", fields("status", "UNDETERMINED", "gaps", Collections.singletonList(
                fields("code", "SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED"))));
        Map<String,Object> condition = fields("id", "EXTRA", "status", "UNDETERMINED", "proof", proof);
        Finding f = finding(Arrays.asList(rule("EXTRA", "PERSON_ROLE"), rule("OTHER", "MATERIAL")),
            Collections.singletonList(condition));
        assertEquals(false, inspect(f, "TARGETED_EVIDENCE", "EXTRA").get("allowed"));
        assertEquals(Collections.singletonList("OTHER"),
            inspect(f, "TARGETED_EVIDENCE", "EXTRA", "OTHER").get("eligibleTargetIds"));
        assertEquals(true, inspect(f, "RECOMPUTE_SHARED_EVIDENCE", "EXTRA").get("allowed"));
        proof.put("baseConfiguration", fields("status", "UNDETERMINED", "gaps", Collections.singletonList(
            fields("code", "PERSON_ROLE_REQUIRED"))));
        assertEquals(true, inspect(f, "TARGETED_EVIDENCE", "EXTRA").get("allowed"));
    }
}

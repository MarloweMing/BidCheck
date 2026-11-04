package cn.bidcheck.verification.engine;

import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;

/** Actual Host reads over invented pages; no private document or model calls. */
class DeclaredRowNumberCounterEvidenceTest {
    private static final String OTHER = "合成乙展厅装饰装修工程采购合同";

    @Test void actualHostNumberReadProducesRowBoundCounterEvidenceWithoutReadDecision() throws Exception {
        Fixture f = accepted();
        assertFalse(f.host.evaluator.decisions.containsKey(f.target));
        Map<String,Object> binding = binding(f, metadata(f));
        assertFalse(map(binding.get("rowNumberEvidence")).isEmpty());
        assertTrue(matches(binding, change(f, NUMBER, OTHER, false)), binding.toString());
    }

    @Test void sameNumberInAnotherCurrentBidDocumentStillWakesIdentityCounterEvidence() throws Exception {
        Fixture f = accepted();
        Map<String,Object> binding = binding(f, metadata(f));
        Map<String,Object> event = change(f, NUMBER, OTHER, true);
        assertTrue(matches(binding, event));
        map(event.get("fact")).put("field", "contractName");
        map(event.get("fact")).put("value", OTHER);
        map(event.get("fact")).put("rawValue", OTHER);
        map(map(event.get("fact")).get("attributes")).put("contractName", OTHER);
        assertTrue(matches(binding, event), "Named paths must also preserve same-number counterevidence");
    }

    @Test void missingContractSuffixIsAnExactSameTitleAlternative() throws Exception {
        Fixture f = accepted();
        Map<String,Object> binding = binding(f, metadata(f));
        binding.remove("rowNumberEvidence");
        row(binding).put("separatorTitle", NAME.substring(0, NAME.length() - 2));
        assertTrue(matches(binding, change(f, "OTHER-2026-999", NAME, false)));
        assertTrue(matches(binding, change(f, "OTHER-2026-999", "甲中心2027年装饰装修工程施工合同", false)), "Missing snapshot cannot establish absence of a shared number");
    }

    @Test void missingReceiptAndBroadObservedValuesRemainConservative() throws Exception {
        Fixture f = accepted();
        Map<String,Object> binding = binding(f, metadata(f));
        binding.remove("rowNumberEvidence");
        binding.put("observedValues", Collections.singletonList(linked("kind", "CONTRACT", "field", "contractNumber", "value", NUMBER)));
        assertTrue(matches(binding, change(f, NUMBER, OTHER, false)));
    }

    @Test void wrongRuleOrChangedRowCannotReuseTheNumberReceipt() throws Exception {
        for (String field : Arrays.asList("obligationId", "observationKey", "row")) {
            Fixture f = accepted();
            Map<String,Object> binding = binding(f, metadata(f));
            Map<String,Object> receipt = map(binding.get("rowNumberEvidence"));
            receipt.put(field, "stale-other-row");
            assertTrue(matches(binding, change(f, NUMBER, OTHER, false)), field);
        }
    }

    @Test void missingOrStaleSourceMetadataCannotPublishKnownRowNumber() throws Exception {
        Fixture f = accepted();
        assertTrue(map(binding(f, Collections.emptyMap()).get("rowNumberEvidence")).isEmpty());
        Map<String,Map<String,Object>> metadata = metadata(f);
        metadata.get(source(f.corpus, 4).id()).put("documentVersion", 99);
        assertTrue(map(binding(f, metadata).get("rowNumberEvidence")).isEmpty());
    }

    @Test void unreadOrInvalidNumberDoesNotCreateRowNumberKnowledge() throws Exception {
        Fixture f = new Fixture(identityBody(), "其他条款未读取", false);
        assertTrue(ProofVerifier.strings(f.submit(f.candidate("contractNumber", NUMBER)).get("acceptedFactIds")).isEmpty());
        assertTrue(RuleEvaluator.maps(map(binding(f, metadata(f)).get("rowNumberEvidence")).get("inputs")).isEmpty());
        f.read(4);
        assertTrue(ProofVerifier.strings(f.submit(f.candidate("contractNumber", "WRONG-001")).get("acceptedFactIds")).isEmpty());
        assertTrue(RuleEvaluator.maps(map(binding(f, metadata(f)).get("rowNumberEvidence")).get("inputs")).isEmpty());
    }

    @Test void truncatedFactLookupCannotEmitAnEmptyAbsenceSnapshot() throws Exception {
        Fixture f = accepted();
        List<Rule> rules = SourcePerformanceScore.evidenceRules(f.parent);
        for (Map<String,Object> dependency : dependencies(f, metadata(f), false)) {
            assertFalse(dependency.containsKey("rowNumberEvidence"));
        }
    }

    @Test void sameNumberTypographyUsedByContractIdentityCannotHideCounterEvidence() throws Exception {
        for (String variant : Arrays.asList("syn-2025-001", "ＳＹＮ—２０２５—００１")) {
            Fixture f = accepted();
            Map<String,Object> event = change(f, variant, OTHER, false);
            Map<String,Object> fact = map(event.get("fact"));
            fact.put("field", "contractName");
            fact.put("value", OTHER);
            fact.put("rawValue", OTHER);
            map(fact.get("attributes")).put("contractName", OTHER);
            assertTrue(matches(binding(f, metadata(f)), event), variant);
        }
    }

    @Test void nameOnlyOriginalPrintingTheKnownNumberStillWakesCounterEvidence() throws Exception {
        Fixture f = accepted();
        for (boolean otherDocument : Arrays.asList(false, true)) {
            Map<String,Object> event = change(f, NUMBER, OTHER, otherDocument);
            Map<String,Object> fact = map(event.get("fact"));
            fact.put("field", "contractName");
            fact.put("rawValue", OTHER);
            fact.put("value", OTHER);
            fact.put("attributes", linked("contractName", OTHER));
            String before = event.toString();
            assertTrue(matches(binding(f, metadata(f)), event));
            assertEquals(before, event.toString(), "The scheduling projection must not add fact attributes");
            Map<String,Object> oldBinding = binding(f, metadata(f));
            oldBinding.remove("rowNumberEvidence");
            assertTrue(matches(oldBinding, event), "A missing row snapshot cannot rule out the printed-number counterfact");
        }
    }

    @Test void nameOnlyTableNumberCounterEvidenceUsesTheExistingScoringFormat() throws Exception {
        Fixture f = accepted();
        Map<String,Object> event = change(f, NUMBER, OTHER, false);
        Map<String,Object> fact = map(event.get("fact"));
        fact.put("field", "contractName");
        fact.put("rawValue", OTHER);
        fact.put("value", OTHER);
        fact.put("attributes", linked("contractName", OTHER));
        Map<String,Object> original = RuleEvaluator.maps(event.get("sources")).get(0);
        map(original.get("evidence")).put("quote", "合同名称：" + OTHER + "\n| 合同编号 | " + NUMBER + " |");
        String before = event.toString();
        assertTrue(matches(binding(f, metadata(f)), event));
        assertEquals(before, event.toString());
    }

    private Fixture accepted() throws Exception {
        Fixture f = fixture();
        assertFalse(ProofVerifier.strings(f.submit(f.candidate("contractNumber", NUMBER)).get("acceptedFactIds")).isEmpty());
        return f;
    }

    private Map<String,Object> binding(Fixture f, Map<String,Map<String,Object>> metadata) {
        List<Rule> rules = SourcePerformanceScore.evidenceRules(f.parent);
        return dependencies(f, metadata, true).stream()
            .filter(value -> f.target.equals(value.get("obligationId"))).findFirst().get();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> dependencies(Fixture f, Map<String,Map<String,Object>> metadata, boolean complete) {
        List<Rule> rules = SourcePerformanceScore.evidenceRules(f.parent);
        try {
            Method current = VerificationEngine.class.getDeclaredMethod("evidenceDependencies", List.class,
                List.class, List.class, Map.class, String.class, RuleEvaluator.class, Map.class, boolean.class);
            return (List<Map<String,Object>>) current.invoke(null, rules, rules, Collections.emptyList(),
                dates(), "synthetic", f.host.evaluator, metadata, complete);
        } catch (NoSuchMethodException baseline) {
            return VerificationEngine.evidenceDependencies(rules, rules, Collections.emptyList(),
                dates(), "synthetic", f.host.evaluator, metadata);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Map<String,Map<String,Object>> metadata(Fixture f) {
        Map<String,Map<String,Object>> result = new LinkedHashMap<>();
        new SharedFactReuse(f.corpus, dates(), "synthetic", "synthetic").sourceMetadata()
            .forEach((id, value) -> result.put(id, new LinkedHashMap<>(value)));
        return result;
    }

    private Map<String,Object> row(Map<String,Object> binding) {
        return map(map(map(binding.get("target"))
            .get("parameters")).get("declaredPerformanceRow"));
    }

    private Map<String,Object> change(Fixture f, String number, String title, boolean otherDocument) {
        String doc = otherDocument ? "another-current-bid" : "synthetic-bid";
        String quote = "合同名称：" + title + "\n合同编号：" + number;
        return linked("recordType", "FACT", "itemId", "synthetic-event",
            "fact", linked("kind", "CONTRACT", "field", "contractNumber", "rawValue", number,
                "value", number, "attributes", linked("contractNumber", number), "sourceIds", Collections.singletonList("outside")),
            "sources", Collections.singletonList(linked("id", "outside", "documentHash", "synthetic-hash",
                "documentVersion", 1, "sourceFingerprint", "synthetic-fingerprint", "parserFingerprint", "synthetic-parser",
                "evidence", linked("documentId", doc, "page", 20, "locationType", "PAGE", "quote", quote))));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map<?,?> ? (Map<String,Object>) value : new LinkedHashMap<>(); }
    private boolean matches(Map<String,Object> binding, Map<String,Object> event) throws Exception {
        Method method = Class.forName("cn.bidcheck.service.OrdinaryRecoveryDependencies")
            .getDeclaredMethod("matches", Map.class, Map.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, binding, event);
    }
}

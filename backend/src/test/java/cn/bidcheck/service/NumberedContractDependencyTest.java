package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.fields;

/** Invented current source metadata and text; no model or private document fixture. */
class NumberedContractDependencyTest {
    private static final String TITLE = "合成甲中心装饰装修工程施工合同";
    private static final String OTHER = "合成乙展厅装饰装修工程采购合同";
    private static final String NUMBER = "SYN-2026-002";

    @Test void uniquePrintedTitleAndNumberExcludeOnlyAnOutsideDifferentRow() {
        assertFalse(matches(change(20, body(OTHER))));
        assertFalse(matches(change(20, "合同名称：" + OTHER + "\n合同编号：" + NUMBER)));
    }

    @Test void sameTitleAndAnySourceInsideTheTargetStayExecutable() {
        assertTrue(matches(change(20, body(TITLE))));
        assertTrue(matches(change(11, body(OTHER))));
        Map<String,Object> value = change(20, body(OTHER));
        sources(value).add(source("inside", 11, body(OTHER)));
        fact(value).put("sourceIds", Arrays.asList("source", "inside"));
        assertTrue(matches(value));
    }

    @Test void missingMetadataChangedVersionAndIdOnlySourcesStayConservative() {
        for (String key : Arrays.asList("documentHash", "documentVersion", "sourceFingerprint", "parserFingerprint")) {
            Map<String,Object> value = change(20, body(OTHER));
            sources(value).get(0).remove(key);
            assertTrue(matches(value), key);
        }
        for (String key : Arrays.asList("documentId", "locationType", "page", "quote")) {
            Map<String,Object> value = change(20, body(OTHER));
            evidence(value).remove(key);
            assertTrue(matches(value), key);
        }
        for (String key : Arrays.asList("documentId", "locationType", "location", "page", "quote")) {
            Map<String,Object> conflict = change(11, body(OTHER));
            sources(conflict).get(0).put(key, key.equals("page") || key.equals("location") ? 20 : "conflicting-metadata");
            assertTrue(matches(conflict), "Conflicting top-level " + key);
        }
        Map<String,Object> ids = change(20, body(OTHER));
        ids.put("sources", Collections.singletonList(fields("id", "source")));
        assertTrue(matches(ids));
        Map<String,Object> changed = change(20, body(OTHER));
        sources(changed).get(0).put("documentVersion", 2);
        assertTrue(matches(changed));
        changed = change(20, body(OTHER));
        sources(changed).get(0).put("documentHash", "changed-original");
        assertTrue(matches(changed));
        changed = change(20, body(OTHER));
        evidence(changed).put("documentId", "unverified-other-document");
        assertTrue(matches(changed));
    }

    @Test void generalMentionsMultipleContractsAndTemplatesDoNotCreateIdentity() {
        for (String quote : Arrays.asList(
                "有关" + OTHER + "另行讨论。\n合同编号：" + NUMBER,
                "# 关于" + OTHER + "\n合同编号：" + NUMBER,
                body(OTHER) + "\n# " + TITLE,
                body(OTHER) + "\n合同编号：SYN-2026-003",
                body(OTHER) + "\n| 合同编号 | SYN-2026-003 |",
                body(OTHER) + "\n填写示例",
                body(OTHER) + "\n空白合同",
                "# 建设工程施工合同\n合同编号：" + NUMBER,
                OTHER + "\n合同编号：" + NUMBER)) {
            assertTrue(matches(change(20, quote)), quote);
        }
    }

    @Test void numberMustMatchTheFactAndTheOneSourceRecord() {
        assertTrue(matches(change(20, body(OTHER).replace(NUMBER, "OTHER-999"))));
        Map<String,Object> value = change(20, body(OTHER));
        fact(value).put("rawValue", "OTHER-999");
        assertTrue(matches(value));
        value = change(20, body(OTHER));
        attributes(value).put("contractNumber", "OTHER-999");
        assertTrue(matches(value));
        value = change(20, body(OTHER));
        fact(value).put("sourceIds", Arrays.asList("source", "unavailable"));
        assertTrue(matches(value));
    }

    @Test void unclearAdditionalReferencesCannotBorrowTheKnownTitle() {
        Map<String,Object> value = change(20, body(OTHER));
        sources(value).add(source("unknown", 21, "履约条款待核对"));
        fact(value).put("sourceIds", Arrays.asList("source", "unknown"));
        assertTrue(matches(value));
        value = change(20, body(OTHER));
        sources(value).add(source("source", 21, body(TITLE)));
        assertTrue(matches(value), "Conflicting metadata for the same ID cannot be resolved by last-write-wins");
    }

    @Test void schedulingProjectionNeverMutatesFactsSourcesOrTarget() {
        Map<String,Object> binding = binding(), value = change(20, body(OTHER));
        String before = binding.toString() + value;
        assertFalse(OrdinaryRecoveryDependencies.matches(binding, value));
        assertEquals(before, binding.toString() + value);
        assertFalse(attributes(value).containsKey("contractName"));
    }

    @Test void existingNamedPunctuationAndCaseEquivalenceRemainsConservative() {
        for (String variant : Arrays.asList("Alpha合成中心装饰-装修工程合同", "alpha合成中心装饰装修工程合同")) {
            Map<String,Object> binding = binding();
            Map<String,Object> target = (Map<String,Object>) binding.get("target");
            Map<String,Object> row = (Map<String,Object>) ((Map<String,Object>) target.get("parameters")).get("declaredPerformanceRow");
            row.put("separatorTitle", "Alpha合成中心装饰装修工程合同");
            Map<String,Object> value = change(20, body(variant));
            fact(value).put("field", "contractName");
            fact(value).put("rawValue", variant);
            fact(value).put("value", variant);
            fact(value).put("attributes", fields("contractName", variant));
            assertTrue(OrdinaryRecoveryDependencies.matches(binding, value), variant);
        }
    }

    private boolean matches(Map<String,Object> change) {
        return OrdinaryRecoveryDependencies.matches(binding(), change);
    }
    private String body(String title) { return "合同编号：" + NUMBER + "\n# " + title; }
    private Map<String,Object> binding() {
        Map<String,Object> row = fields("documentId", "doc", "documentHash", "frozen-hash", "documentVersion", 1,
            "proofPageStart", 10, "proofPageEnd", 12, "separatorTitle", TITLE);
        Map<String,Object> target = fields("predicate", "CONTRACT", "parameters", fields("declaredPerformanceRow", row));
        String key = cn.bidcheck.verification.facts.FieldCoverage.key(target);
        return fields("obligationId", "synthetic-row", "observationKey", key, "target", target,
            "rowNumberEvidence", fields("version", "server-declared-row-number-1", "authority", "CURRENT_SERVER_VERIFIED_ROW",
                "obligationId", "synthetic-row", "observationKey", key, "row", row,
                "complete", true, "inputs", Collections.emptyList(), "businessProof", false));
    }
    private Map<String,Object> change(int page, String quote) {
        return fields("itemId", "synthetic-fact", "recordType", "FACT", "fact", fields("kind", "CONTRACT",
            "field", "contractNumber", "rawValue", NUMBER, "value", NUMBER,
            "attributes", fields("contractNumber", NUMBER), "sourceIds", Collections.singletonList("source")),
            "sources", new ArrayList<>(Collections.singletonList(source("source", page, quote))));
    }
    private Map<String,Object> source(String id, int page, String quote) {
        return fields("id", id, "documentHash", "frozen-hash", "documentVersion", 1,
            "sourceFingerprint", "source-fingerprint-" + id, "parserFingerprint", "parser-version",
            "evidence", fields("documentId", "doc", "locationType", "PAGE", "page", page, "quote", quote));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> fact(Map<String,Object> change) {
        return (Map<String,Object>) change.get("fact");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> attributes(Map<String,Object> change) {
        return (Map<String,Object>) fact(change).get("attributes");
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> sources(Map<String,Object> change) {
        return (List<Map<String,Object>>) change.get("sources");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> evidence(Map<String,Object> change) {
        return (Map<String,Object>) sources(change).get(0).get("evidence");
    }
}

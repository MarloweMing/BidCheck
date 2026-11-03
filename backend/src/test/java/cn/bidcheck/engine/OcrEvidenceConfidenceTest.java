package cn.bidcheck.engine;

import cn.bidcheck.document.ProfileExtractor;
import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class OcrEvidenceConfidenceTest {
    private static final String TEXT = "项目编号：OCR-001\n投标人名称：测试科技有限公司\n投标有效期：不少于90天";

    @Test void extractedFactsKeepSourceLocationsWithoutInventingOcrAccuracy() {
        for (String parser : cn.bidcheck.compat.Java8Api.listOf("PDF_OCR", "POI_NATIVE_WITH_OCR", "IMAGE_OCR", "POI_NATIVE")) {
            Document document = document(parser, TEXT);
            List<Fact> facts = new FactExtractor().extract(document);
            assertTrue(facts.stream().anyMatch(fact -> "projectNumber".equals(fact.field())));
            assertTrue(facts.stream().anyMatch(fact -> "companyName".equals(fact.field())));
            assertTrue(facts.stream().anyMatch(fact -> "bidValidity".equals(fact.field())));
            facts.forEach(fact -> assertEvidence(fact.evidence(), document, parser.contains("OCR") ? 0d : 1d));
        }
    }

    @Test void extractedRequirementsRetainUnknownOcrAccuracyAndBuiltInsStayWithoutSources() {
        for (String parser : cn.bidcheck.compat.Java8Api.listOf("PDF_OCR", "POI_NATIVE_WITH_OCR", "POI_NATIVE")) {
            Document document = document(parser, TEXT);
            List<Checkpoint> checkpoints = new CheckpointExtractor().extract(document);
            assertTrue(checkpoints.stream().anyMatch(rule -> "projectNumber".equals(rule.field()) && rule.source() != null));
            assertTrue(checkpoints.stream().anyMatch(rule -> "bidValidity".equals(rule.field()) && rule.source() != null));
            checkpoints.stream().map(Checkpoint::source).filter(Objects::nonNull)
                    .forEach(source -> assertEvidence(source, document, parser.contains("OCR") ? 0d : 1d));
            assertTrue(checkpoints.stream().anyMatch(rule -> "companyName".equals(rule.field()) && rule.source() == null));
        }
        // Older native documents may predate the parser field.
        Document legacy = document(null, TEXT);
        new FactExtractor().extract(legacy).forEach(fact -> assertEvidence(fact.evidence(), legacy, 1d));
    }

    @Test void profileLabelsAndSourceExcerptsBothPreserveUnknownOcrAccuracy() {
        String text = TEXT + "\n人员信息\n张三持有一级建造师资格证，证书编号 ABC123。";
        for (String parser : cn.bidcheck.compat.Java8Api.listOf("POI_NATIVE_WITH_OCR", "POI_NATIVE")) {
            Document document = document(parser, text);
            Map<String, Object> profile = new ProfileExtractor().extract(document);
            List<?> groups = (List<?>) profile.get("groups");
            List<Map<?, ?>> items = cn.bidcheck.compat.Java8Api.toList(groups.stream().map(group -> (Map<?, ?>) group)
                    .flatMap(group -> ((List<?>) group.get("items")).stream())
                    .<Map<?, ?>>map(item -> (Map<?, ?>) item));
            assertTrue(items.stream().anyMatch(item -> "原文摘录".equals(item.get("label"))));
            assertTrue(items.stream().anyMatch(item -> !"原文摘录".equals(item.get("label"))));
            for (Map<?, ?> item : items) {
                double nativeConfidence = "原文摘录".equals(item.get("label")) ? 0.85d : 0.96d;
                assertEvidence((Evidence) item.get("evidence"), document,
                        parser.contains("OCR") ? 0d : nativeConfidence);
            }
        }
    }

    private static Document document(String parser, String text) {
        return new Document("doc", "project", "TENDER", "fixture.docx", 1, "hash", "now",
                cn.bidcheck.compat.Java8Api.listOf(new Page(7, text, "BLOCK", parser, "")), cn.bidcheck.compat.Java8Api.listOf());
    }

    private static void assertEvidence(Evidence evidence, Document document, double confidence) {
        assertNotNull(evidence);
        assertEquals(confidence, evidence.confidence());
        assertEquals(document.id(), evidence.documentId());
        assertEquals(7, evidence.page());
        assertEquals("BLOCK", evidence.locationType());
        assertTrue(evidence.anchor().startsWith("p7-l"));
        assertTrue(document.pages().get(0).text().contains(evidence.quote()));
    }
}

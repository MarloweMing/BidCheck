package cn.bidcheck;

import cn.bidcheck.document.OcrUnitObserver;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.retrieval.RetrievalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the wire and value contracts retained when records become Java 8 classes. */
class Java8ValueCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void documentRoundTripKeepsComponentNamesAndNestedValues() throws Exception {
        Document document = new Document("d", "p", "BID", "投标文件", 1, "digest", "now",
                Collections.singletonList(new Page(1, "原文", "PAGE", "TEXT", null)),
                Collections.emptyList());

        JsonNode tree = json.valueToTree(document);
        Set<String> fields = new HashSet<>();
        tree.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id", "projectId", "role", "name", "version",
                "sha256", "createdAt", "pages", "warnings")), fields);
        assertEquals("原文", tree.path("pages").get(0).path("text").asText());
        assertEquals(document, json.readValue(json.writeValueAsBytes(document), Document.class));
    }

    @Test
    void evidencePreservesRecordEqualityHashAndString() {
        Evidence left = new Evidence("d", "文件", 3, "PAGE", "原文", "p3", 0.9);
        Evidence right = new Evidence("d", "文件", 3, "PAGE", "原文", "p3", 0.9);
        assertEquals(left, right);
        int expected = 0;
        for (Object component : Arrays.asList("d", "文件", 3, "PAGE", "原文", "p3", 0.9)) {
            expected = 31 * expected + component.hashCode();
        }
        assertEquals(expected, left.hashCode());
        assertEquals("Evidence[documentId=d, documentName=文件, page=3, locationType=PAGE, "
                + "quote=原文, anchor=p3, confidence=0.9]", left.toString());
        assertNotEquals(new Evidence("d", "文件", 3, "PAGE", "原文", "p3", 0.0),
                new Evidence("d", "文件", 3, "PAGE", "原文", "p3", -0.0));
    }

    @Test
    void compactConstructorStillCopiesCollectionsAndSurvivesJsonRoundTrip() throws Exception {
        List<Evidence> hits = new ArrayList<>();
        hits.add(new Evidence("d", "文件", 1, "PAGE", "原文", "p1", 1.0));
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("mode", "LEXICAL");
        RetrievalResult result = new RetrievalResult(hits, "LEXICAL", diagnostics);

        hits.clear();
        diagnostics.clear();
        assertEquals(1, result.hits().size());
        assertEquals("LEXICAL", result.diagnostics().get("mode"));
        assertThrows(UnsupportedOperationException.class, () -> result.hits().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
        assertEquals(result, json.readValue(json.writeValueAsBytes(result), RetrievalResult.class));
    }

    @Test
    void interfaceNestedValueRetainsPublicConstruction() {
        OcrUnitObserver.Result result = new OcrUnitObserver.Result("原文", null, null);
        assertEquals("原文", result.text());
    }
}

package cn.bidcheck.verification.source;

import cn.bidcheck.persistence.Stored;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static cn.bidcheck.compat.Java8Api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Normal collector, in-memory Store and synthetic PDF served by local HTTP only. */
class VisualMarkCacheBindingTest {
    @TempDir Path temp;
    VisualWindowTest fixture;
    String responseType = "SIGNATURE";
    String responseLabel = "李明";
    boolean bothMarks;
    boolean secondPerson;
    boolean positive;
    boolean printedOnly;

    @BeforeEach
    void setup() throws Exception {
        fixture = new VisualWindowTest() {
            @Override ObjectNode pageAnswer(int width, int height) {
                ObjectNode answer = json.createObjectNode().put("coordinateSpace", "IMAGE_PIXELS")
                    .put("imageWidth", width).put("imageHeight", height);
                answer.putArray("limitations");
                if (printedOnly) ((ArrayNode) answer.get("limitations")).add("仅为印刷姓名，未观察到实际手写签名");
                ArrayNode observations = answer.putArray("observations");
                mark(observations, responseType, responseLabel);
                if (bothMarks) mark(observations, "SEAL", responseLabel);
                if (secondPerson) mark(observations, "SIGNATURE", "王强");
                return answer;
            }
        };
        fixture.temp = temp;
        fixture.setup();
    }

    private void mark(ArrayNode observations, String type, String label) {
        ObjectNode observation = observations.addObject().put("type", type).put("label", label)
            .put("value", positive ? label : "").put("readability", positive ? "CLEAR" : "NOT_OBSERVED");
        if (positive) observation.putArray("bbox").add(1).add(1).add(20).add(20);
        else observation.putNull("bbox");
    }

    @AfterEach
    void stop() {
        if (fixture != null && fixture.server != null) fixture.stop();
    }

    private Map<String, Object> request(String task, String label, String source) {
        Map<String, Object> request = new LinkedHashMap<>(fixture.requested(fixture.document, 6));
        request.put("task", task);
        request.put("fields", listOf(label));
        request.put("sourceIds", listOf(source));
        return request;
    }

    @Test
    void onePhysicalObservationRetainsSeparateSealAndSignatureBindings() throws Exception {
        bothMarks = true;
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        List<Map<String, Object>> requests = listOf(request("SEAL_VISIBILITY", "李明", "seal-source"),
            request("SIGNATURE_VISIBILITY", "李明", "signature-source"));
        harness.collect(listOf(fixture.document), requests);
        int before = fixture.calls.get();
        List<Map<String, Object>> rebound = fixture.cached(harness, listOf(fixture.document), requests);
        assertTrue(rebound.stream().anyMatch(o -> "SIGNATURE".equals(o.get("type")) &&
            "SIGNATURE_VISIBILITY".equals(o.get("requestedTask"))), rebound.toString());
        assertTrue(rebound.stream().anyMatch(o -> "SEAL".equals(o.get("type")) &&
            "SEAL_VISIBILITY".equals(o.get("requestedTask"))), rebound.toString());
        assertEquals(before, fixture.calls.get(), "Cache rebind cannot create a provider call");
    }

    @Test
    void sourceAndFieldScopesCannotBeUnionedIntoAnEarlierBinding() throws Exception {
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        Map<String, Object> first = request("SIGNATURE_VISIBILITY", "李明", "source-a");
        Map<String, Object> second = request("SIGNATURE_VISIBILITY", "李明", "source-b");
        List<Map<String, Object>> observations = harness.collect(listOf(fixture.document), listOf(first, second));
        assertEquals(2, observations.size());
        assertTrue(observations.stream().anyMatch(o -> listOf("source-a").equals(o.get("requestedSourceIds"))));
        assertTrue(observations.stream().anyMatch(o -> listOf("source-b").equals(o.get("requestedSourceIds"))));
        assertEquals(1, fixture.calls.get(), "Different proof bindings still share the same physical read");
    }

    @Test
    void separateRequestedFieldsKeepTheirOwnBindingsAndPromptRequiresTypedMarks() throws Exception {
        secondPerson = true;
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        List<Map<String, Object>> requests = listOf(request("SIGNATURE_VISIBILITY", "李明", "source-a"),
            request("SIGNATURE_VISIBILITY", "王强", "source-a"));
        List<Map<String, Object>> observations = harness.collect(listOf(fixture.document), requests);
        assertEquals(4, observations.size(), "Both physical observations retain each requested field scope");
        assertTrue(observations.stream().anyMatch(o -> listOf("李明").equals(o.get("requestedFields"))));
        assertTrue(observations.stream().anyMatch(o -> listOf("王强").equals(o.get("requestedFields"))));
        assertTrue(fixture.payloads.get(0).path("messages").get(0).path("content").asText()
            .contains("SIGNATURE_VISIBILITY 使用 type=SIGNATURE"));
    }

    @Test
    void aFieldOnlyLegacyCacheIsNotACompletedSignatureAtAnyDurableLayer() throws Exception {
        for (String layer : listOf("evidence_operation", "verification_visual", "verification_visual_page")) {
            VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
            Map<String, Object> request = request("SIGNATURE_VISIBILITY", "李明", "source-a");
            harness.collect(listOf(fixture.document), listOf(request));
            harness.records.entrySet().removeIf(e -> !layer.equals(e.getValue().kind) &&
                (e.getValue().kind.startsWith("verification_visual") || "evidence_operation".equals(e.getValue().kind)));
            List<String> raw = new ArrayList<>();
            for (Stored stored : harness.records.values()) if (layer.equals(stored.kind)) {
                stored.body = stored.body.replace("\"SIGNATURE\"", "\"FIELD\"");
                raw.add(stored.body);
            }
            assertFalse(raw.isEmpty());
            harness.restart(12, 2);
            int before = fixture.calls.get();
            assertTrue(fixture.cached(harness, listOf(fixture.document), listOf(request)).isEmpty(), layer);
            assertEquals(before, fixture.calls.get(), "Cache-only cannot repair by calling a provider");
            List<Map<String, Object>> repaired = harness.collect(listOf(fixture.document), listOf(request));
            assertEquals(before + 1, fixture.calls.get(), layer);
            assertTrue(repaired.stream().anyMatch(o -> "SIGNATURE".equals(o.get("type"))));
            harness.restart(12, 2);
            assertFalse(fixture.cached(harness, listOf(fixture.document), listOf(request)).isEmpty());
            assertEquals(before + 1, fixture.calls.get(), "Valid repaired cache remains reusable");
        }
    }

    @Test
    void fieldAbsenceAndWrongSubjectDoNotBecomeSignatureAbsenceOrCompletedCache() throws Exception {
        for (String bad : listOf("FIELD", "WRONG_LABEL")) {
            responseType = "FIELD".equals(bad) ? "FIELD" : "SIGNATURE";
            responseLabel = "WRONG_LABEL".equals(bad) ? "王强" : "李明";
            VisualVerificationService client = fixture.client(2);
            Map<String, Object> request = request("SIGNATURE_VISIBILITY", "李明", "source-a");
            Map<String, Object> result = client.verify(fixture.document, fixture.pdf, 6, request);
            assertEquals("VISUAL_MARK_RESULT_INCOMPLETE", result.get("errorCode"));
            assertFalse(VisualVerificationService.cacheable(result));
            assertTrue(fixture.observations(result).isEmpty());
            int before = fixture.calls.get();
            client.verify(fixture.document, fixture.pdf, 6, request);
            assertEquals(before + 1, fixture.calls.get(), "Invalid mark response must not become an in-memory hit");
        }
    }

    @Test
    void invalidSingleMarkHasOneRepairAndRemainsAnEngineFailure() throws Exception {
        responseType = "FIELD";
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        List<Map<String, Object>> observed = harness.collect(listOf(fixture.document),
            listOf(request("SIGNATURE_VISIBILITY", "李明", "source-a")));
        assertTrue(observed.isEmpty());
        assertEquals(2, fixture.calls.get(), "One original attempt plus one bounded same-page repair");
        assertEquals(0, harness.pageRecords());
        assertTrue(harness.diagnostics.toString().contains("VISUAL_ENGINE_FAILURE"));
        assertEquals(2, harness.records.values().stream().filter(r -> "verification_visual_call".equals(r.kind)).count());
    }

    @Test
    void correctlyTypedNegativeIsCachedWithoutChangingItsMeaning() throws Exception {
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        Map<String, Object> request = request("SIGNATURE_VISIBILITY", "李明", "source-a");
        List<Map<String, Object>> observations = harness.collect(listOf(fixture.document), listOf(request));
        assertEquals(1, observations.size());
        assertEquals("SIGNATURE", observations.get(0).get("type"));
        assertEquals("NOT_OBSERVED", observations.get(0).get("readability"));
        harness.restart(12, 2);
        assertEquals(1, fixture.cached(harness, listOf(fixture.document), listOf(request)).size());
        assertEquals(1, fixture.calls.get());
    }

    @Test
    void legacyPositiveOfAnotherSubjectDoesNotCompleteTheRequestedMark() throws Exception {
        positive = true;
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        Map<String, Object> request = request("SIGNATURE_VISIBILITY", "李明", "source-a");
        harness.collect(listOf(fixture.document), listOf(request));
        for (Stored stored : harness.records.values()) if (stored.body != null) {
            stored.body = stored.body.replace("李明", "王强");
        }
        harness.restart(12, 2);
        assertTrue(fixture.cached(harness, listOf(fixture.document), listOf(request)).isEmpty());
        assertEquals(1, fixture.calls.get());
        assertFalse(harness.collect(listOf(fixture.document), listOf(request)).isEmpty());
        assertEquals(2, fixture.calls.get());
    }

    @Test
    void legacyTypedNegativeWithGenericLabelRetainsItsOriginalRequestCoverage() throws Exception {
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        Map<String, Object> request = request("SIGNATURE_VISIBILITY", "李明", "source-a");
        harness.collect(listOf(fixture.document), listOf(request));
        for (Stored stored : harness.records.values()) if (stored.body != null) {
            stored.body = stored.body.replace("\"label\":\"李明\"", "\"label\":\"签字位置\"");
        }
        harness.restart(12, 2);
        List<Map<String, Object>> cached = fixture.cached(harness, listOf(fixture.document), listOf(request));
        assertEquals(1, cached.size());
        assertEquals("NOT_OBSERVED", cached.get(0).get("readability"));
        assertEquals(1, fixture.calls.get());
    }

    @Test
    void printedNameLimitationCannotCompleteAPositiveSignatureResponse() throws Exception {
        positive = true;
        printedOnly = true;
        Map<String, Object> result = fixture.client(2).verify(fixture.document, fixture.pdf, 6,
            request("SIGNATURE_VISIBILITY", "李明", "source-a"));
        assertEquals("VISUAL_MARK_RESULT_INCOMPLETE", result.get("errorCode"));
        assertFalse(VisualVerificationService.cacheable(result));
        printedOnly = false;
        VisualWindowTest.CollectionHarness harness = fixture.new CollectionHarness(12, 2);
        Map<String, Object> request = request("SIGNATURE_VISIBILITY", "李明", "source-a");
        harness.collect(listOf(fixture.document), listOf(request));
        for (Stored stored : harness.records.values()) if (stored.body != null) {
            stored.body = stored.body.replace("\"modelLimitations\":[]",
                "\"modelLimitations\":[\"仅为印刷姓名，未观察到实际手写签名\"]");
        }
        harness.restart(12, 2);
        int before = fixture.calls.get();
        assertTrue(fixture.cached(harness, listOf(fixture.document), listOf(request)).isEmpty());
        assertEquals(before, fixture.calls.get());
    }
}

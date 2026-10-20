package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ensures an exported artifact can always be traced back to its project and immutable run. */
class ReportTraceabilityTest {
    private static final String PROJECT_ID = "project-trace-001";
    private static final String RUN_ID = "run-trace-001";

    @Test void htmlCsvAndJsonExposeProjectAndRunIdentifiers() throws Exception {
        ReportService reports = reports();

        String html = text(reports.generate(RUN_ID, "html"));
        assertTrue(html.contains("项目ID：" + PROJECT_ID), html);
        assertTrue(html.contains("检查批次ID：" + RUN_ID), html);

        List<List<String>> csv = rows(text(reports.generate(RUN_ID, "csv")));
        assertEquals("项目ID", csv.get(0).get(0));
        assertEquals("检查批次ID", csv.get(0).get(1));
        assertEquals(PROJECT_ID, csv.get(1).get(0));
        assertEquals(RUN_ID, csv.get(1).get(1));
        assertEquals(csv.get(0).size(), csv.get(1).size());

        JsonNode json = new ObjectMapper().readTree(text(reports.generate(RUN_ID, "json")));
        assertEquals(PROJECT_ID, json.path("projectId").asText());
        assertEquals(RUN_ID, json.path("runId").asText());
        assertEquals(PROJECT_ID, json.path("run").path("projectId").asText());
        assertEquals(RUN_ID, json.path("run").path("id").asText());
    }

    private ReportService reports() throws Exception {
        BidService service = mock(BidService.class);
        Store store = mock(Store.class);
        var field = BidService.class.getField("store");
        field.setAccessible(true);
        field.set(service, store);
        ObjectMapper mapper = new ObjectMapper();
        when(store.encode(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0)));
        when(store.getMap(eq("project"), anyString())).thenReturn(
                new LinkedHashMap<>(Map.of("id", PROJECT_ID, "name", "追踪测试项目")));
        when(service.publishedTemplateResultVersion(eq(RUN_ID), anyString())).thenReturn(Map.of());
        Document document = new Document("bid", PROJECT_ID, "BID", "投标文件.pdf", 1,
                "sha256", "2026-10-19T00:00:00Z", List.of(), List.of());
        when(service.document("bid")).thenReturn(document);
        Finding finding = new Finding("finding-1", RUN_ID,
                new Checkpoint("checkpoint-1", "测试检查项", "资格检查", "HIGH",
                        "SEMANTIC_RESPONSE", "", "", "", "", true, true, true, null),
                new Evaluation("UNDETERMINED", "证据待定位", "继续定位证据", List.of(), 0,
                        "TEST"));
        when(service.run(RUN_ID, false)).thenReturn(Map.of(
                "run", Map.of("id", RUN_ID, "projectId", PROJECT_ID, "documentId", "bid",
                        "status", "COMPLETED"),
                "results", List.of(finding), "reviews", List.of(), "documents", List.of()));
        return new ReportService(service);
    }

    private static String text(ReportService.Report report) {
        return new String(report.bytes(), StandardCharsets.UTF_8);
    }

    private static List<List<String>> rows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = csv.charAt(0) == '\uFEFF' ? 1 : 0; i < csv.length(); i++) {
            char current = csv.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (current == '\n' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (current != '\r' || quoted) cell.append(current);
        }
        return rows;
    }
}

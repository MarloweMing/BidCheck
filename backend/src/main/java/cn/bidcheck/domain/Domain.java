package cn.bidcheck.domain;

import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {}
    public record Page(int number, String text, String locationType, String parser, String warning, cn.bidcheck.ocr.OcrLayout layout) {
        public Page(int number, String text, String locationType, String parser, String warning) { this(number, text, locationType, parser, warning, null); }
    }
    public record ParsedDocument(List<Page> pages, List<String> warnings) {}
    public record Document(String id, String projectId, String role, String name, int version,
                           String sha256, String createdAt, List<Page> pages, List<String> warnings) {}
    public record Evidence(String documentId, String documentName, int page, String locationType,
                           String quote, String anchor, double confidence) {}
    public record Checkpoint(String id, String title, String category, String severity, String type,
                             String field, String operator, String expected, String unit,
                             boolean required, boolean confirmed, boolean enabled, Evidence source) {}
    public record Fact(String entityType, String entityKey, String field, String rawValue,
                       String normalizedValue, Evidence evidence) {}
    public record Evaluation(String status, String reason, String suggestion, List<Evidence> evidences,
                             double confidence, String method) {}
    public record Finding(String id, String runId, Checkpoint checkpoint, Evaluation evaluation,
                          Map<String,Object> assessment) {
        public Finding(String id, String runId, Checkpoint checkpoint, Evaluation evaluation) {
            this(id,runId,checkpoint,evaluation,null);
        }
    }
}

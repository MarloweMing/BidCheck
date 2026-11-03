package cn.bidcheck.domain;

import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Page {
        private final int number;
        private final String text;
        private final String locationType;
        private final String parser;
        private final String warning;
        private final cn.bidcheck.ocr.OcrLayout layout;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Page(@com.fasterxml.jackson.annotation.JsonProperty("number") int number, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("parser") String parser, @com.fasterxml.jackson.annotation.JsonProperty("warning") String warning, @com.fasterxml.jackson.annotation.JsonProperty("layout") cn.bidcheck.ocr.OcrLayout layout) { this.number = number; this.text = text; this.locationType = locationType; this.parser = parser; this.warning = warning; this.layout = layout; }
        public int number() { return number; }
        public String text() { return text; }
        public String locationType() { return locationType; }
        public String parser() { return parser; }
        public String warning() { return warning; }
        public cn.bidcheck.ocr.OcrLayout layout() { return layout; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Page that = (Page) other; return java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.parser, that.parser) && java.util.Objects.equals(this.warning, that.warning) && java.util.Objects.equals(this.layout, that.layout); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.parser); hash = 31 * hash + java.util.Objects.hashCode(this.warning); hash = 31 * hash + java.util.Objects.hashCode(this.layout); return hash; }
        @Override public String toString() { return "Page[" + "number=" + number + ", text=" + text + ", locationType=" + locationType + ", parser=" + parser + ", warning=" + warning + ", layout=" + layout + "]"; }

        public Page(int number, String text, String locationType, String parser, String warning) { this(number, text, locationType, parser, warning, null); }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class ParsedDocument {
        private final List<Page> pages;
        private final List<String> warnings;
        @com.fasterxml.jackson.annotation.JsonCreator
        public ParsedDocument(@com.fasterxml.jackson.annotation.JsonProperty("pages") List<Page> pages, @com.fasterxml.jackson.annotation.JsonProperty("warnings") List<String> warnings) { this.pages = pages; this.warnings = warnings; }
        public List<Page> pages() { return pages; }
        public List<String> warnings() { return warnings; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ParsedDocument that = (ParsedDocument) other; return java.util.Objects.equals(this.pages, that.pages) && java.util.Objects.equals(this.warnings, that.warnings); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.pages); hash = 31 * hash + java.util.Objects.hashCode(this.warnings); return hash; }
        @Override public String toString() { return "ParsedDocument[" + "pages=" + pages + ", warnings=" + warnings + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Document {
        private final String id;
        private final String projectId;
        private final String role;
        private final String name;
        private final int version;
        private final String sha256;
        private final String createdAt;
        private final List<Page> pages;
        private final List<String> warnings;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Document(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("projectId") String projectId, @com.fasterxml.jackson.annotation.JsonProperty("role") String role, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("version") int version, @com.fasterxml.jackson.annotation.JsonProperty("sha256") String sha256, @com.fasterxml.jackson.annotation.JsonProperty("createdAt") String createdAt, @com.fasterxml.jackson.annotation.JsonProperty("pages") List<Page> pages, @com.fasterxml.jackson.annotation.JsonProperty("warnings") List<String> warnings) { this.id = id; this.projectId = projectId; this.role = role; this.name = name; this.version = version; this.sha256 = sha256; this.createdAt = createdAt; this.pages = pages; this.warnings = warnings; }
        public String id() { return id; }
        public String projectId() { return projectId; }
        public String role() { return role; }
        public String name() { return name; }
        public int version() { return version; }
        public String sha256() { return sha256; }
        public String createdAt() { return createdAt; }
        public List<Page> pages() { return pages; }
        public List<String> warnings() { return warnings; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Document that = (Document) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.projectId, that.projectId) && java.util.Objects.equals(this.role, that.role) && java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.version, that.version) && java.util.Objects.equals(this.sha256, that.sha256) && java.util.Objects.equals(this.createdAt, that.createdAt) && java.util.Objects.equals(this.pages, that.pages) && java.util.Objects.equals(this.warnings, that.warnings); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.projectId); hash = 31 * hash + java.util.Objects.hashCode(this.role); hash = 31 * hash + java.util.Objects.hashCode(this.name); hash = 31 * hash + java.util.Objects.hashCode(this.version); hash = 31 * hash + java.util.Objects.hashCode(this.sha256); hash = 31 * hash + java.util.Objects.hashCode(this.createdAt); hash = 31 * hash + java.util.Objects.hashCode(this.pages); hash = 31 * hash + java.util.Objects.hashCode(this.warnings); return hash; }
        @Override public String toString() { return "Document[" + "id=" + id + ", projectId=" + projectId + ", role=" + role + ", name=" + name + ", version=" + version + ", sha256=" + sha256 + ", createdAt=" + createdAt + ", pages=" + pages + ", warnings=" + warnings + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Evidence {
        private final String documentId;
        private final String documentName;
        private final int page;
        private final String locationType;
        private final String quote;
        private final String anchor;
        private final double confidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Evidence(@com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("documentName") String documentName, @com.fasterxml.jackson.annotation.JsonProperty("page") int page, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("anchor") String anchor, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) { this.documentId = documentId; this.documentName = documentName; this.page = page; this.locationType = locationType; this.quote = quote; this.anchor = anchor; this.confidence = confidence; }
        public String documentId() { return documentId; }
        public String documentName() { return documentName; }
        public int page() { return page; }
        public String locationType() { return locationType; }
        public String quote() { return quote; }
        public String anchor() { return anchor; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Evidence that = (Evidence) other; return java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.documentName, that.documentName) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.anchor, that.anchor) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.documentName); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.anchor); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "Evidence[" + "documentId=" + documentId + ", documentName=" + documentName + ", page=" + page + ", locationType=" + locationType + ", quote=" + quote + ", anchor=" + anchor + ", confidence=" + confidence + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Checkpoint {
        private final String id;
        private final String title;
        private final String category;
        private final String severity;
        private final String type;
        private final String field;
        private final String operator;
        private final String expected;
        private final String unit;
        private final boolean required;
        private final boolean confirmed;
        private final boolean enabled;
        private final Evidence source;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Checkpoint(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("category") String category, @com.fasterxml.jackson.annotation.JsonProperty("severity") String severity, @com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("operator") String operator, @com.fasterxml.jackson.annotation.JsonProperty("expected") String expected, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("required") boolean required, @com.fasterxml.jackson.annotation.JsonProperty("confirmed") boolean confirmed, @com.fasterxml.jackson.annotation.JsonProperty("enabled") boolean enabled, @com.fasterxml.jackson.annotation.JsonProperty("source") Evidence source) { this.id = id; this.title = title; this.category = category; this.severity = severity; this.type = type; this.field = field; this.operator = operator; this.expected = expected; this.unit = unit; this.required = required; this.confirmed = confirmed; this.enabled = enabled; this.source = source; }
        public String id() { return id; }
        public String title() { return title; }
        public String category() { return category; }
        public String severity() { return severity; }
        public String type() { return type; }
        public String field() { return field; }
        public String operator() { return operator; }
        public String expected() { return expected; }
        public String unit() { return unit; }
        public boolean required() { return required; }
        public boolean confirmed() { return confirmed; }
        public boolean enabled() { return enabled; }
        public Evidence source() { return source; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Checkpoint that = (Checkpoint) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.severity, that.severity) && java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.operator, that.operator) && java.util.Objects.equals(this.expected, that.expected) && java.util.Objects.equals(this.unit, that.unit) && java.util.Objects.equals(this.required, that.required) && java.util.Objects.equals(this.confirmed, that.confirmed) && java.util.Objects.equals(this.enabled, that.enabled) && java.util.Objects.equals(this.source, that.source); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.severity); hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.operator); hash = 31 * hash + java.util.Objects.hashCode(this.expected); hash = 31 * hash + java.util.Objects.hashCode(this.unit); hash = 31 * hash + java.util.Objects.hashCode(this.required); hash = 31 * hash + java.util.Objects.hashCode(this.confirmed); hash = 31 * hash + java.util.Objects.hashCode(this.enabled); hash = 31 * hash + java.util.Objects.hashCode(this.source); return hash; }
        @Override public String toString() { return "Checkpoint[" + "id=" + id + ", title=" + title + ", category=" + category + ", severity=" + severity + ", type=" + type + ", field=" + field + ", operator=" + operator + ", expected=" + expected + ", unit=" + unit + ", required=" + required + ", confirmed=" + confirmed + ", enabled=" + enabled + ", source=" + source + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Fact {
        private final String entityType;
        private final String entityKey;
        private final String field;
        private final String rawValue;
        private final String normalizedValue;
        private final Evidence evidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Fact(@com.fasterxml.jackson.annotation.JsonProperty("entityType") String entityType, @com.fasterxml.jackson.annotation.JsonProperty("entityKey") String entityKey, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("rawValue") String rawValue, @com.fasterxml.jackson.annotation.JsonProperty("normalizedValue") String normalizedValue, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence) { this.entityType = entityType; this.entityKey = entityKey; this.field = field; this.rawValue = rawValue; this.normalizedValue = normalizedValue; this.evidence = evidence; }
        public String entityType() { return entityType; }
        public String entityKey() { return entityKey; }
        public String field() { return field; }
        public String rawValue() { return rawValue; }
        public String normalizedValue() { return normalizedValue; }
        public Evidence evidence() { return evidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fact that = (Fact) other; return java.util.Objects.equals(this.entityType, that.entityType) && java.util.Objects.equals(this.entityKey, that.entityKey) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.rawValue, that.rawValue) && java.util.Objects.equals(this.normalizedValue, that.normalizedValue) && java.util.Objects.equals(this.evidence, that.evidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.entityType); hash = 31 * hash + java.util.Objects.hashCode(this.entityKey); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.rawValue); hash = 31 * hash + java.util.Objects.hashCode(this.normalizedValue); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); return hash; }
        @Override public String toString() { return "Fact[" + "entityType=" + entityType + ", entityKey=" + entityKey + ", field=" + field + ", rawValue=" + rawValue + ", normalizedValue=" + normalizedValue + ", evidence=" + evidence + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Evaluation {
        private final String status;
        private final String reason;
        private final String suggestion;
        private final List<Evidence> evidences;
        private final double confidence;
        private final String method;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Evaluation(@com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("suggestion") String suggestion, @com.fasterxml.jackson.annotation.JsonProperty("evidences") List<Evidence> evidences, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence, @com.fasterxml.jackson.annotation.JsonProperty("method") String method) { this.status = status; this.reason = reason; this.suggestion = suggestion; this.evidences = evidences; this.confidence = confidence; this.method = method; }
        public String status() { return status; }
        public String reason() { return reason; }
        public String suggestion() { return suggestion; }
        public List<Evidence> evidences() { return evidences; }
        public double confidence() { return confidence; }
        public String method() { return method; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Evaluation that = (Evaluation) other; return java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.suggestion, that.suggestion) && java.util.Objects.equals(this.evidences, that.evidences) && java.util.Objects.equals(this.confidence, that.confidence) && java.util.Objects.equals(this.method, that.method); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.suggestion); hash = 31 * hash + java.util.Objects.hashCode(this.evidences); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); hash = 31 * hash + java.util.Objects.hashCode(this.method); return hash; }
        @Override public String toString() { return "Evaluation[" + "status=" + status + ", reason=" + reason + ", suggestion=" + suggestion + ", evidences=" + evidences + ", confidence=" + confidence + ", method=" + method + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Finding {
        private final String id;
        private final String runId;
        private final Checkpoint checkpoint;
        private final Evaluation evaluation;
        private final Map<String,Object> assessment;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Finding(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("runId") String runId, @com.fasterxml.jackson.annotation.JsonProperty("checkpoint") Checkpoint checkpoint, @com.fasterxml.jackson.annotation.JsonProperty("evaluation") Evaluation evaluation, @com.fasterxml.jackson.annotation.JsonProperty("assessment") Map<String,Object> assessment) { this.id = id; this.runId = runId; this.checkpoint = checkpoint; this.evaluation = evaluation; this.assessment = assessment; }
        public String id() { return id; }
        public String runId() { return runId; }
        public Checkpoint checkpoint() { return checkpoint; }
        public Evaluation evaluation() { return evaluation; }
        public Map<String,Object> assessment() { return assessment; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Finding that = (Finding) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.runId, that.runId) && java.util.Objects.equals(this.checkpoint, that.checkpoint) && java.util.Objects.equals(this.evaluation, that.evaluation) && java.util.Objects.equals(this.assessment, that.assessment); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.runId); hash = 31 * hash + java.util.Objects.hashCode(this.checkpoint); hash = 31 * hash + java.util.Objects.hashCode(this.evaluation); hash = 31 * hash + java.util.Objects.hashCode(this.assessment); return hash; }
        @Override public String toString() { return "Finding[" + "id=" + id + ", runId=" + runId + ", checkpoint=" + checkpoint + ", evaluation=" + evaluation + ", assessment=" + assessment + "]"; }

        public Finding(String id, String runId, Checkpoint checkpoint, Evaluation evaluation) {
            this(id,runId,checkpoint,evaluation,null);
        }
    }
}

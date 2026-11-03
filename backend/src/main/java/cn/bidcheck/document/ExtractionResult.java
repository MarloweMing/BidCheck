package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.ParsedDocument;
import java.util.Map;

/** Parsed evidence and request-scoped extraction diagnostics; Domain wire records remain unchanged. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class ExtractionResult {
        private final ParsedDocument document;
        private final Map<String,Object> report;
        public ParsedDocument document() { return document; }
        public Map<String,Object> report() { return report; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ExtractionResult that = (ExtractionResult) other; return java.util.Objects.equals(this.document, that.document) && java.util.Objects.equals(this.report, that.report); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.document); hash = 31 * hash + java.util.Objects.hashCode(this.report); return hash; }
        @Override public String toString() { return "ExtractionResult[" + "document=" + document + ", report=" + report + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public ExtractionResult(@com.fasterxml.jackson.annotation.JsonProperty("document") ParsedDocument document, @com.fasterxml.jackson.annotation.JsonProperty("report") Map<String,Object> report) { report=cn.bidcheck.compat.Java8Api.mapCopyOf(report);
            this.document = document; this.report = report;
        }
}

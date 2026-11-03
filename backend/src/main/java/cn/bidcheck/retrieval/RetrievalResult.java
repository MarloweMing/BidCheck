package cn.bidcheck.retrieval;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.List;
import java.util.Map;

/** Retrieval scores express relevance, never evidential confidence or verified identity. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class RetrievalResult {
        private final List<Evidence> hits;
        private final String mode;
        private final Map<String, Object> diagnostics;
        public List<Evidence> hits() { return hits; }
        public String mode() { return mode; }
        public Map<String, Object> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RetrievalResult that = (RetrievalResult) other; return java.util.Objects.equals(this.hits, that.hits) && java.util.Objects.equals(this.mode, that.mode) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.hits); hash = 31 * hash + java.util.Objects.hashCode(this.mode); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "RetrievalResult[" + "hits=" + hits + ", mode=" + mode + ", diagnostics=" + diagnostics + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public RetrievalResult(@com.fasterxml.jackson.annotation.JsonProperty("hits") List<Evidence> hits, @com.fasterxml.jackson.annotation.JsonProperty("mode") String mode, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") Map<String, Object> diagnostics) {
        hits = cn.bidcheck.compat.Java8Api.listCopyOf(hits);
        diagnostics = cn.bidcheck.compat.Java8Api.mapCopyOf(diagnostics);

            this.hits = hits; this.mode = mode; this.diagnostics = diagnostics;
        }
}

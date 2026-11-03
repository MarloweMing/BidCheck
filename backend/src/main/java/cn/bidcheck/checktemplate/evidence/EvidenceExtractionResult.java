package cn.bidcheck.checktemplate.evidence;

import cn.bidcheck.checktemplate.Observation;

import java.util.List;
import java.util.Map;

/** Source-preserving result returned by a deterministic local extractor. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceExtractionResult {
        private final Disposition disposition;
        private final List<Observation> observations;
        private final EvidenceScope scope;
        private final String message;
        private final Map<String, String> diagnostics;
        public Disposition disposition() { return disposition; }
        public List<Observation> observations() { return observations; }
        public EvidenceScope scope() { return scope; }
        public String message() { return message; }
        public Map<String, String> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceExtractionResult that = (EvidenceExtractionResult) other; return java.util.Objects.equals(this.disposition, that.disposition) && java.util.Objects.equals(this.observations, that.observations) && java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.message, that.message) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.disposition); hash = 31 * hash + java.util.Objects.hashCode(this.observations); hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.message); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "EvidenceExtractionResult[" + "disposition=" + disposition + ", observations=" + observations + ", scope=" + scope + ", message=" + message + ", diagnostics=" + diagnostics + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceExtractionResult(@com.fasterxml.jackson.annotation.JsonProperty("disposition") Disposition disposition, @com.fasterxml.jackson.annotation.JsonProperty("observations") List<Observation> observations, @com.fasterxml.jackson.annotation.JsonProperty("scope") EvidenceScope scope, @com.fasterxml.jackson.annotation.JsonProperty("message") String message, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") Map<String, String> diagnostics) {
        if (disposition == null) throw new IllegalArgumentException("disposition is required");
        observations = observations == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(observations);
        if (scope == null) throw new IllegalArgumentException("scope is required");
        message = message == null ? "" : message.trim();
        diagnostics = diagnostics == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(diagnostics);
        switch (disposition) {case POSITIVE:{
                if (observations.isEmpty()) throw new IllegalArgumentException("positive result requires observations");
            } break;case NOT_FOUND:{
                if (!scope.closed()) throw new IllegalArgumentException("negative result requires a closed scope");
                if (!observations.isEmpty()) throw new IllegalArgumentException("negative result cannot contain observations");
            } break;case PARTIAL:{
                if (scope.closed()) throw new IllegalArgumentException("partial result requires an open scope");
            } break;}

            this.disposition = disposition; this.observations = observations; this.scope = scope; this.message = message; this.diagnostics = diagnostics;
        }

    public static EvidenceExtractionResult positive(List<Observation> observations, EvidenceScope scope) {
        return new EvidenceExtractionResult(Disposition.POSITIVE, observations, scope, "", cn.bidcheck.compat.Java8Api.mapOf());
    }

    public static EvidenceExtractionResult notFound(EvidenceScope closedScope, String message) {
        return new EvidenceExtractionResult(Disposition.NOT_FOUND, cn.bidcheck.compat.Java8Api.listOf(), closedScope, message, cn.bidcheck.compat.Java8Api.mapOf());
    }

    public static EvidenceExtractionResult partial(List<Observation> observations, EvidenceScope scope, String message) {
        return new EvidenceExtractionResult(Disposition.PARTIAL, observations, scope, message, cn.bidcheck.compat.Java8Api.mapOf());
    }

    public enum Disposition { POSITIVE, NOT_FOUND, PARTIAL }
}

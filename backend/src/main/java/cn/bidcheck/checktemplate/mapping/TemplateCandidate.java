package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;

/** A non-executable template suggestion. It must be confirmed and validated as a CheckSpec first. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class TemplateCandidate {
        private final String templateId;
        private final String templateVersion;
        private final double confidence;
        private final Origin origin;
        private final String reason;
        private final Map<String, Object> parameters;
        private final Map<String, List<SourceRef>> parameterSources;
        private final List<SourceRef> requirementRefs;
        private final List<String> missingParameters;
        public String templateId() { return templateId; }
        public String templateVersion() { return templateVersion; }
        public double confidence() { return confidence; }
        public Origin origin() { return origin; }
        public String reason() { return reason; }
        public Map<String, Object> parameters() { return parameters; }
        public Map<String, List<SourceRef>> parameterSources() { return parameterSources; }
        public List<SourceRef> requirementRefs() { return requirementRefs; }
        public List<String> missingParameters() { return missingParameters; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TemplateCandidate that = (TemplateCandidate) other; return java.util.Objects.equals(this.templateId, that.templateId) && java.util.Objects.equals(this.templateVersion, that.templateVersion) && java.util.Objects.equals(this.confidence, that.confidence) && java.util.Objects.equals(this.origin, that.origin) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.parameterSources, that.parameterSources) && java.util.Objects.equals(this.requirementRefs, that.requirementRefs) && java.util.Objects.equals(this.missingParameters, that.missingParameters); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.templateId); hash = 31 * hash + java.util.Objects.hashCode(this.templateVersion); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); hash = 31 * hash + java.util.Objects.hashCode(this.origin); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.parameters); hash = 31 * hash + java.util.Objects.hashCode(this.parameterSources); hash = 31 * hash + java.util.Objects.hashCode(this.requirementRefs); hash = 31 * hash + java.util.Objects.hashCode(this.missingParameters); return hash; }
        @Override public String toString() { return "TemplateCandidate[" + "templateId=" + templateId + ", templateVersion=" + templateVersion + ", confidence=" + confidence + ", origin=" + origin + ", reason=" + reason + ", parameters=" + parameters + ", parameterSources=" + parameterSources + ", requirementRefs=" + requirementRefs + ", missingParameters=" + missingParameters + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public TemplateCandidate(@com.fasterxml.jackson.annotation.JsonProperty("templateId") String templateId, @com.fasterxml.jackson.annotation.JsonProperty("templateVersion") String templateVersion, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence, @com.fasterxml.jackson.annotation.JsonProperty("origin") Origin origin, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("parameters") Map<String, Object> parameters, @com.fasterxml.jackson.annotation.JsonProperty("parameterSources") Map<String, List<SourceRef>> parameterSources, @com.fasterxml.jackson.annotation.JsonProperty("requirementRefs") List<SourceRef> requirementRefs, @com.fasterxml.jackson.annotation.JsonProperty("missingParameters") List<String> missingParameters) {
        if (templateId == null || cn.bidcheck.compat.Java8Api.isBlank(templateId)) throw new IllegalArgumentException("templateId is required");
        templateId = templateId.trim();
        templateVersion = templateVersion == null || cn.bidcheck.compat.Java8Api.isBlank(templateVersion) ? "1.0" : templateVersion.trim();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        origin = origin == null ? Origin.DETERMINISTIC_RULE : origin;
        reason = reason == null ? "" : reason.trim();
        parameters = parameters == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(parameters);
        parameterSources = parameterSources == null ? cn.bidcheck.compat.Java8Api.mapOf() : parameterSources.entrySet().stream()
                .collect(cn.bidcheck.compat.Java8Api.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue() == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(entry.getValue())));
        requirementRefs = requirementRefs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(requirementRefs);
        missingParameters = missingParameters == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(missingParameters.stream()
                .filter(value -> value != null && !cn.bidcheck.compat.Java8Api.isBlank(value)).map(String::trim).distinct());

            this.templateId = templateId; this.templateVersion = templateVersion; this.confidence = confidence; this.origin = origin; this.reason = reason; this.parameters = parameters; this.parameterSources = parameterSources; this.requirementRefs = requirementRefs; this.missingParameters = missingParameters;
        }

    public enum Origin { DETERMINISTIC_RULE, RESTRICTED_CLASSIFIER }
}

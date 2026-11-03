package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Optional, restricted extension point for unresolved fragments.
 *
 * <p>The implementation may only select an ID supplied in {@code allowedTemplateIds}; its result
 * is still a draft and never becomes executable without normal source binding and confirmation.</p>
 */
@FunctionalInterface
public interface RestrictedTemplateClassifier {
    Optional<Suggestion> classify(Request request);

    static RestrictedTemplateClassifier disabled() { return request -> Optional.empty(); }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Request {
        private final String fragmentId;
        private final String text;
        private final SourceContext sourceContext;
        private final SourceRef sourceRef;
        private final Set<String> allowedTemplateIds;
        public String fragmentId() { return fragmentId; }
        public String text() { return text; }
        public SourceContext sourceContext() { return sourceContext; }
        public SourceRef sourceRef() { return sourceRef; }
        public Set<String> allowedTemplateIds() { return allowedTemplateIds; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Request that = (Request) other; return java.util.Objects.equals(this.fragmentId, that.fragmentId) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.sourceContext, that.sourceContext) && java.util.Objects.equals(this.sourceRef, that.sourceRef) && java.util.Objects.equals(this.allowedTemplateIds, that.allowedTemplateIds); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.fragmentId); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.sourceContext); hash = 31 * hash + java.util.Objects.hashCode(this.sourceRef); hash = 31 * hash + java.util.Objects.hashCode(this.allowedTemplateIds); return hash; }
        @Override public String toString() { return "Request[" + "fragmentId=" + fragmentId + ", text=" + text + ", sourceContext=" + sourceContext + ", sourceRef=" + sourceRef + ", allowedTemplateIds=" + allowedTemplateIds + "]"; }


        @com.fasterxml.jackson.annotation.JsonCreator
        public Request(@com.fasterxml.jackson.annotation.JsonProperty("fragmentId") String fragmentId, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("sourceContext") SourceContext sourceContext, @com.fasterxml.jackson.annotation.JsonProperty("sourceRef") SourceRef sourceRef, @com.fasterxml.jackson.annotation.JsonProperty("allowedTemplateIds") Set<String> allowedTemplateIds) {
            if (fragmentId == null || cn.bidcheck.compat.Java8Api.isBlank(fragmentId)) throw new IllegalArgumentException("fragmentId is required");
            text = text == null ? "" : text.trim();
            if (sourceContext == null) throw new IllegalArgumentException("sourceContext is required");
            if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
            allowedTemplateIds = allowedTemplateIds == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(allowedTemplateIds);

            this.fragmentId = fragmentId; this.text = text; this.sourceContext = sourceContext; this.sourceRef = sourceRef; this.allowedTemplateIds = allowedTemplateIds;
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Suggestion {
        private final String templateId;
        private final String templateVersion;
        private final double confidence;
        private final Map<String, Object> parameters;
        private final List<String> unresolvedFragments;
        private final String explanation;
        public String templateId() { return templateId; }
        public String templateVersion() { return templateVersion; }
        public double confidence() { return confidence; }
        public Map<String, Object> parameters() { return parameters; }
        public List<String> unresolvedFragments() { return unresolvedFragments; }
        public String explanation() { return explanation; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Suggestion that = (Suggestion) other; return java.util.Objects.equals(this.templateId, that.templateId) && java.util.Objects.equals(this.templateVersion, that.templateVersion) && java.util.Objects.equals(this.confidence, that.confidence) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.unresolvedFragments, that.unresolvedFragments) && java.util.Objects.equals(this.explanation, that.explanation); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.templateId); hash = 31 * hash + java.util.Objects.hashCode(this.templateVersion); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); hash = 31 * hash + java.util.Objects.hashCode(this.parameters); hash = 31 * hash + java.util.Objects.hashCode(this.unresolvedFragments); hash = 31 * hash + java.util.Objects.hashCode(this.explanation); return hash; }
        @Override public String toString() { return "Suggestion[" + "templateId=" + templateId + ", templateVersion=" + templateVersion + ", confidence=" + confidence + ", parameters=" + parameters + ", unresolvedFragments=" + unresolvedFragments + ", explanation=" + explanation + "]"; }


        @com.fasterxml.jackson.annotation.JsonCreator
        public Suggestion(@com.fasterxml.jackson.annotation.JsonProperty("templateId") String templateId, @com.fasterxml.jackson.annotation.JsonProperty("templateVersion") String templateVersion, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence, @com.fasterxml.jackson.annotation.JsonProperty("parameters") Map<String, Object> parameters, @com.fasterxml.jackson.annotation.JsonProperty("unresolvedFragments") List<String> unresolvedFragments, @com.fasterxml.jackson.annotation.JsonProperty("explanation") String explanation) {
            if (templateId == null || cn.bidcheck.compat.Java8Api.isBlank(templateId)) throw new IllegalArgumentException("templateId is required");
            templateId = templateId.trim();
            templateVersion = templateVersion == null || cn.bidcheck.compat.Java8Api.isBlank(templateVersion) ? "1.0" : templateVersion.trim();
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            parameters = parameters == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(parameters);
            unresolvedFragments = unresolvedFragments == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(unresolvedFragments.stream()
                    .filter(value -> value != null && !cn.bidcheck.compat.Java8Api.isBlank(value)).map(String::trim));
            explanation = explanation == null ? "" : explanation.trim();

            this.templateId = templateId; this.templateVersion = templateVersion; this.confidence = confidence; this.parameters = parameters; this.unresolvedFragments = unresolvedFragments; this.explanation = explanation;
        }
    }
}

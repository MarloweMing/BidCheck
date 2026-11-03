package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;

/** Source-bound draft generated from one requirement fragment. It is deliberately not executable. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class RequirementDraft {
        private final String id;
        private final String clauseId;
        private final String fragmentId;
        private final String text;
        private final SourceContext sourceContext;
        private final List<TemplateCandidate> templateCandidates;
        private final Map<String, Object> parameters;
        private final Map<String, List<SourceRef>> parameterSources;
        private final List<SourceRef> requirementRefs;
        private final List<UnsupportedFragment> unsupportedFragments;
        private final MappingState mappingState;
        private final boolean classifierConsulted;
        public String id() { return id; }
        public String clauseId() { return clauseId; }
        public String fragmentId() { return fragmentId; }
        public String text() { return text; }
        public SourceContext sourceContext() { return sourceContext; }
        public List<TemplateCandidate> templateCandidates() { return templateCandidates; }
        public Map<String, Object> parameters() { return parameters; }
        public Map<String, List<SourceRef>> parameterSources() { return parameterSources; }
        public List<SourceRef> requirementRefs() { return requirementRefs; }
        public List<UnsupportedFragment> unsupportedFragments() { return unsupportedFragments; }
        public MappingState mappingState() { return mappingState; }
        public boolean classifierConsulted() { return classifierConsulted; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RequirementDraft that = (RequirementDraft) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.clauseId, that.clauseId) && java.util.Objects.equals(this.fragmentId, that.fragmentId) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.sourceContext, that.sourceContext) && java.util.Objects.equals(this.templateCandidates, that.templateCandidates) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.parameterSources, that.parameterSources) && java.util.Objects.equals(this.requirementRefs, that.requirementRefs) && java.util.Objects.equals(this.unsupportedFragments, that.unsupportedFragments) && java.util.Objects.equals(this.mappingState, that.mappingState) && java.util.Objects.equals(this.classifierConsulted, that.classifierConsulted); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.clauseId); hash = 31 * hash + java.util.Objects.hashCode(this.fragmentId); hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.sourceContext); hash = 31 * hash + java.util.Objects.hashCode(this.templateCandidates); hash = 31 * hash + java.util.Objects.hashCode(this.parameters); hash = 31 * hash + java.util.Objects.hashCode(this.parameterSources); hash = 31 * hash + java.util.Objects.hashCode(this.requirementRefs); hash = 31 * hash + java.util.Objects.hashCode(this.unsupportedFragments); hash = 31 * hash + java.util.Objects.hashCode(this.mappingState); hash = 31 * hash + java.util.Objects.hashCode(this.classifierConsulted); return hash; }
        @Override public String toString() { return "RequirementDraft[" + "id=" + id + ", clauseId=" + clauseId + ", fragmentId=" + fragmentId + ", text=" + text + ", sourceContext=" + sourceContext + ", templateCandidates=" + templateCandidates + ", parameters=" + parameters + ", parameterSources=" + parameterSources + ", requirementRefs=" + requirementRefs + ", unsupportedFragments=" + unsupportedFragments + ", mappingState=" + mappingState + ", classifierConsulted=" + classifierConsulted + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public RequirementDraft(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("clauseId") String clauseId, @com.fasterxml.jackson.annotation.JsonProperty("fragmentId") String fragmentId, @com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("sourceContext") SourceContext sourceContext, @com.fasterxml.jackson.annotation.JsonProperty("templateCandidates") List<TemplateCandidate> templateCandidates, @com.fasterxml.jackson.annotation.JsonProperty("parameters") Map<String, Object> parameters, @com.fasterxml.jackson.annotation.JsonProperty("parameterSources") Map<String, List<SourceRef>> parameterSources, @com.fasterxml.jackson.annotation.JsonProperty("requirementRefs") List<SourceRef> requirementRefs, @com.fasterxml.jackson.annotation.JsonProperty("unsupportedFragments") List<UnsupportedFragment> unsupportedFragments, @com.fasterxml.jackson.annotation.JsonProperty("mappingState") MappingState mappingState, @com.fasterxml.jackson.annotation.JsonProperty("classifierConsulted") boolean classifierConsulted) {
        id = required(id, "id");
        clauseId = required(clauseId, "clauseId");
        fragmentId = required(fragmentId, "fragmentId");
        text = text == null ? "" : text.trim();
        if (sourceContext == null) throw new IllegalArgumentException("sourceContext is required");
        templateCandidates = templateCandidates == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(templateCandidates);
        parameters = parameters == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(parameters);
        parameterSources = parameterSources == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(parameterSources);
        requirementRefs = requirementRefs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(requirementRefs);
        unsupportedFragments = unsupportedFragments == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(unsupportedFragments);
        mappingState = mappingState == null ? MappingState.UNSUPPORTED : mappingState;

            this.id = id; this.clauseId = clauseId; this.fragmentId = fragmentId; this.text = text; this.sourceContext = sourceContext; this.templateCandidates = templateCandidates; this.parameters = parameters; this.parameterSources = parameterSources; this.requirementRefs = requirementRefs; this.unsupportedFragments = unsupportedFragments; this.mappingState = mappingState; this.classifierConsulted = classifierConsulted;
        }

    public enum MappingState {
        READY_FOR_CONFIRMATION,
        NEEDS_CONFIRMATION,
        AMBIGUOUS,
        EXTERNAL_REQUIRED,
        UNSUPPORTED
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class UnsupportedFragment {
        private final String text;
        private final String reasonCode;
        private final String explanation;
        private final SourceRef sourceRef;
        public String text() { return text; }
        public String reasonCode() { return reasonCode; }
        public String explanation() { return explanation; }
        public SourceRef sourceRef() { return sourceRef; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; UnsupportedFragment that = (UnsupportedFragment) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.reasonCode, that.reasonCode) && java.util.Objects.equals(this.explanation, that.explanation) && java.util.Objects.equals(this.sourceRef, that.sourceRef); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); hash = 31 * hash + java.util.Objects.hashCode(this.explanation); hash = 31 * hash + java.util.Objects.hashCode(this.sourceRef); return hash; }
        @Override public String toString() { return "UnsupportedFragment[" + "text=" + text + ", reasonCode=" + reasonCode + ", explanation=" + explanation + ", sourceRef=" + sourceRef + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public UnsupportedFragment(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode, @com.fasterxml.jackson.annotation.JsonProperty("explanation") String explanation, @com.fasterxml.jackson.annotation.JsonProperty("sourceRef") SourceRef sourceRef) {
            text = text == null ? "" : text.trim();
            reasonCode = required(reasonCode, "reasonCode");
            explanation = explanation == null ? "" : explanation.trim();
            if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");

            this.text = text; this.reasonCode = reasonCode; this.explanation = explanation; this.sourceRef = sourceRef;
        }
    }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

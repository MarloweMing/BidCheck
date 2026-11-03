package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;

/** Safe public metadata. evaluatorId is selected from a code-owned allow-list, never a class name. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class TemplateMetadata {
        private final String id;
        private final String version;
        private final String title;
        private final String evaluatorId;
        private final TemplateTypes.ProofStandard proofStandard;
        private final Map<String, ParameterDefinition> parameters;
        private final List<String> fixedSubchecks;
        private final TemplateTypes.VerificationMode requiredVerificationMode;
        public String id() { return id; }
        public String version() { return version; }
        public String title() { return title; }
        public String evaluatorId() { return evaluatorId; }
        public TemplateTypes.ProofStandard proofStandard() { return proofStandard; }
        public Map<String, ParameterDefinition> parameters() { return parameters; }
        public List<String> fixedSubchecks() { return fixedSubchecks; }
        public TemplateTypes.VerificationMode requiredVerificationMode() { return requiredVerificationMode; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TemplateMetadata that = (TemplateMetadata) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.version, that.version) && java.util.Objects.equals(this.title, that.title) && java.util.Objects.equals(this.evaluatorId, that.evaluatorId) && java.util.Objects.equals(this.proofStandard, that.proofStandard) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.fixedSubchecks, that.fixedSubchecks) && java.util.Objects.equals(this.requiredVerificationMode, that.requiredVerificationMode); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.version); hash = 31 * hash + java.util.Objects.hashCode(this.title); hash = 31 * hash + java.util.Objects.hashCode(this.evaluatorId); hash = 31 * hash + java.util.Objects.hashCode(this.proofStandard); hash = 31 * hash + java.util.Objects.hashCode(this.parameters); hash = 31 * hash + java.util.Objects.hashCode(this.fixedSubchecks); hash = 31 * hash + java.util.Objects.hashCode(this.requiredVerificationMode); return hash; }
        @Override public String toString() { return "TemplateMetadata[" + "id=" + id + ", version=" + version + ", title=" + title + ", evaluatorId=" + evaluatorId + ", proofStandard=" + proofStandard + ", parameters=" + parameters + ", fixedSubchecks=" + fixedSubchecks + ", requiredVerificationMode=" + requiredVerificationMode + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public TemplateMetadata(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("version") String version, @com.fasterxml.jackson.annotation.JsonProperty("title") String title, @com.fasterxml.jackson.annotation.JsonProperty("evaluatorId") String evaluatorId, @com.fasterxml.jackson.annotation.JsonProperty("proofStandard") TemplateTypes.ProofStandard proofStandard, @com.fasterxml.jackson.annotation.JsonProperty("parameters") Map<String, ParameterDefinition> parameters, @com.fasterxml.jackson.annotation.JsonProperty("fixedSubchecks") List<String> fixedSubchecks, @com.fasterxml.jackson.annotation.JsonProperty("requiredVerificationMode") TemplateTypes.VerificationMode requiredVerificationMode) {
        if (id == null || cn.bidcheck.compat.Java8Api.isBlank(id)) throw new IllegalArgumentException("template id is required");
        if (version == null || cn.bidcheck.compat.Java8Api.isBlank(version)) throw new IllegalArgumentException("template version is required");
        if (evaluatorId == null || cn.bidcheck.compat.Java8Api.isBlank(evaluatorId)) throw new IllegalArgumentException("evaluatorId is required");
        title = title == null ? id : title;
        parameters = parameters == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(parameters);
        fixedSubchecks = fixedSubchecks == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(fixedSubchecks);
        requiredVerificationMode = requiredVerificationMode == null ? defaultVerificationMode(id) : requiredVerificationMode;

            this.id = id; this.version = version; this.title = title; this.evaluatorId = evaluatorId; this.proofStandard = proofStandard; this.parameters = parameters; this.fixedSubchecks = fixedSubchecks; this.requiredVerificationMode = requiredVerificationMode;
        }

    /** Keeps programmatic metadata fixtures source-compatible while publishing the required mode through the API. */
    public TemplateMetadata(String id,String version,String title,String evaluatorId,TemplateTypes.ProofStandard proofStandard,
                            Map<String,ParameterDefinition> parameters,List<String> fixedSubchecks) {
        this(id,version,title,evaluatorId,proofStandard,parameters,fixedSubchecks,null);
    }

    private static TemplateTypes.VerificationMode defaultVerificationMode(String id) {
        switch (id) {case "EXTERNAL_CHECK":return TemplateTypes.VerificationMode.EXTERNAL;case "QUALITY_ADVISORY":return TemplateTypes.VerificationMode.HUMAN_JUDGMENT;default:return TemplateTypes.VerificationMode.DOCUMENT;}
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class ParameterDefinition {
        private final String type;
        private final boolean required;
        private final List<String> allowedValues;
        private final Object defaultValue;
        public String type() { return type; }
        public boolean required() { return required; }
        public List<String> allowedValues() { return allowedValues; }
        public Object defaultValue() { return defaultValue; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ParameterDefinition that = (ParameterDefinition) other; return java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.required, that.required) && java.util.Objects.equals(this.allowedValues, that.allowedValues) && java.util.Objects.equals(this.defaultValue, that.defaultValue); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.required); hash = 31 * hash + java.util.Objects.hashCode(this.allowedValues); hash = 31 * hash + java.util.Objects.hashCode(this.defaultValue); return hash; }
        @Override public String toString() { return "ParameterDefinition[" + "type=" + type + ", required=" + required + ", allowedValues=" + allowedValues + ", defaultValue=" + defaultValue + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public ParameterDefinition(@com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("required") boolean required, @com.fasterxml.jackson.annotation.JsonProperty("allowedValues") List<String> allowedValues, @com.fasterxml.jackson.annotation.JsonProperty("defaultValue") Object defaultValue) {
            if (type == null || cn.bidcheck.compat.Java8Api.isBlank(type)) throw new IllegalArgumentException("parameter type is required");
            allowedValues = allowedValues == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(allowedValues);

            this.type = type; this.required = required; this.allowedValues = allowedValues; this.defaultValue = defaultValue;
        }
    }
}

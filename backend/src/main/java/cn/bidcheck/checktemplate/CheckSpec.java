package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen executable requirement. Display text is deliberately not executable. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class CheckSpec {
        private final String id;
        private final int revision;
        private final String businessItemId;
        private final String checkpointId;
        private final TemplateTypes.Category category;
        private final TemplateTypes.Purpose purpose;
        private final TemplateTypes.VerificationMode verificationMode;
        private final String templateId;
        private final String templateVersion;
        private final SubjectSelector subjectSelector;
        private final Map<String, Object> parameters;
        private final Applicability applicability;
        private final TemplateTypes.ProofStandard proofStandard;
        private final List<SourceRef> requirementRefs;
        private final List<ParameterBinding> parameterBindings;
        private final Map<String, Object> requiredFields;
        private final Map<String, Object> evidenceContract;
        private final Map<String, Object> sourceCoverage;
        private final boolean confirmed;
        private final Confirmation confirmation;
        private final String semanticSignature;
        public String id() { return id; }
        public int revision() { return revision; }
        public String businessItemId() { return businessItemId; }
        public String checkpointId() { return checkpointId; }
        public TemplateTypes.Category category() { return category; }
        public TemplateTypes.Purpose purpose() { return purpose; }
        public TemplateTypes.VerificationMode verificationMode() { return verificationMode; }
        public String templateId() { return templateId; }
        public String templateVersion() { return templateVersion; }
        public SubjectSelector subjectSelector() { return subjectSelector; }
        public Map<String, Object> parameters() { return parameters; }
        public Applicability applicability() { return applicability; }
        public TemplateTypes.ProofStandard proofStandard() { return proofStandard; }
        public List<SourceRef> requirementRefs() { return requirementRefs; }
        public List<ParameterBinding> parameterBindings() { return parameterBindings; }
        public Map<String, Object> requiredFields() { return requiredFields; }
        public Map<String, Object> evidenceContract() { return evidenceContract; }
        public Map<String, Object> sourceCoverage() { return sourceCoverage; }
        public boolean confirmed() { return confirmed; }
        public Confirmation confirmation() { return confirmation; }
        public String semanticSignature() { return semanticSignature; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; CheckSpec that = (CheckSpec) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.businessItemId, that.businessItemId) && java.util.Objects.equals(this.checkpointId, that.checkpointId) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.purpose, that.purpose) && java.util.Objects.equals(this.verificationMode, that.verificationMode) && java.util.Objects.equals(this.templateId, that.templateId) && java.util.Objects.equals(this.templateVersion, that.templateVersion) && java.util.Objects.equals(this.subjectSelector, that.subjectSelector) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.applicability, that.applicability) && java.util.Objects.equals(this.proofStandard, that.proofStandard) && java.util.Objects.equals(this.requirementRefs, that.requirementRefs) && java.util.Objects.equals(this.parameterBindings, that.parameterBindings) && java.util.Objects.equals(this.requiredFields, that.requiredFields) && java.util.Objects.equals(this.evidenceContract, that.evidenceContract) && java.util.Objects.equals(this.sourceCoverage, that.sourceCoverage) && java.util.Objects.equals(this.confirmed, that.confirmed) && java.util.Objects.equals(this.confirmation, that.confirmation) && java.util.Objects.equals(this.semanticSignature, that.semanticSignature); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.businessItemId); hash = 31 * hash + java.util.Objects.hashCode(this.checkpointId); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.purpose); hash = 31 * hash + java.util.Objects.hashCode(this.verificationMode); hash = 31 * hash + java.util.Objects.hashCode(this.templateId); hash = 31 * hash + java.util.Objects.hashCode(this.templateVersion); hash = 31 * hash + java.util.Objects.hashCode(this.subjectSelector); hash = 31 * hash + java.util.Objects.hashCode(this.parameters); hash = 31 * hash + java.util.Objects.hashCode(this.applicability); hash = 31 * hash + java.util.Objects.hashCode(this.proofStandard); hash = 31 * hash + java.util.Objects.hashCode(this.requirementRefs); hash = 31 * hash + java.util.Objects.hashCode(this.parameterBindings); hash = 31 * hash + java.util.Objects.hashCode(this.requiredFields); hash = 31 * hash + java.util.Objects.hashCode(this.evidenceContract); hash = 31 * hash + java.util.Objects.hashCode(this.sourceCoverage); hash = 31 * hash + java.util.Objects.hashCode(this.confirmed); hash = 31 * hash + java.util.Objects.hashCode(this.confirmation); hash = 31 * hash + java.util.Objects.hashCode(this.semanticSignature); return hash; }
        @Override public String toString() { return "CheckSpec[" + "id=" + id + ", revision=" + revision + ", businessItemId=" + businessItemId + ", checkpointId=" + checkpointId + ", category=" + category + ", purpose=" + purpose + ", verificationMode=" + verificationMode + ", templateId=" + templateId + ", templateVersion=" + templateVersion + ", subjectSelector=" + subjectSelector + ", parameters=" + parameters + ", applicability=" + applicability + ", proofStandard=" + proofStandard + ", requirementRefs=" + requirementRefs + ", parameterBindings=" + parameterBindings + ", requiredFields=" + requiredFields + ", evidenceContract=" + evidenceContract + ", sourceCoverage=" + sourceCoverage + ", confirmed=" + confirmed + ", confirmation=" + confirmation + ", semanticSignature=" + semanticSignature + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public CheckSpec(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("revision") int revision, @com.fasterxml.jackson.annotation.JsonProperty("businessItemId") String businessItemId, @com.fasterxml.jackson.annotation.JsonProperty("checkpointId") String checkpointId, @com.fasterxml.jackson.annotation.JsonProperty("category") TemplateTypes.Category category, @com.fasterxml.jackson.annotation.JsonProperty("purpose") TemplateTypes.Purpose purpose, @com.fasterxml.jackson.annotation.JsonProperty("verificationMode") TemplateTypes.VerificationMode verificationMode, @com.fasterxml.jackson.annotation.JsonProperty("templateId") String templateId, @com.fasterxml.jackson.annotation.JsonProperty("templateVersion") String templateVersion, @com.fasterxml.jackson.annotation.JsonProperty("subjectSelector") SubjectSelector subjectSelector, @com.fasterxml.jackson.annotation.JsonProperty("parameters") Map<String, Object> parameters, @com.fasterxml.jackson.annotation.JsonProperty("applicability") Applicability applicability, @com.fasterxml.jackson.annotation.JsonProperty("proofStandard") TemplateTypes.ProofStandard proofStandard, @com.fasterxml.jackson.annotation.JsonProperty("requirementRefs") List<SourceRef> requirementRefs, @com.fasterxml.jackson.annotation.JsonProperty("parameterBindings") List<ParameterBinding> parameterBindings, @com.fasterxml.jackson.annotation.JsonProperty("requiredFields") Map<String, Object> requiredFields, @com.fasterxml.jackson.annotation.JsonProperty("evidenceContract") Map<String, Object> evidenceContract, @com.fasterxml.jackson.annotation.JsonProperty("sourceCoverage") Map<String, Object> sourceCoverage, @com.fasterxml.jackson.annotation.JsonProperty("confirmed") boolean confirmed, @com.fasterxml.jackson.annotation.JsonProperty("confirmation") Confirmation confirmation, @com.fasterxml.jackson.annotation.JsonProperty("semanticSignature") String semanticSignature) {
        id = required(id, "id");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        businessItemId = required(businessItemId, "businessItemId");
        checkpointId = checkpointId == null ? "" : checkpointId.trim();
        category = Objects.requireNonNull(category, "category");
        purpose = Objects.requireNonNull(purpose, "purpose");
        verificationMode = Objects.requireNonNull(verificationMode, "verificationMode");
        templateId = required(templateId, "templateId");
        templateVersion = required(templateVersion, "templateVersion");
        subjectSelector = Objects.requireNonNull(subjectSelector, "subjectSelector");
        parameters = parameters == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(parameters);
        applicability = applicability == null ? Applicability.applies() : applicability;
        proofStandard = Objects.requireNonNull(proofStandard, "proofStandard");
        requirementRefs = requirementRefs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(requirementRefs);
        parameterBindings = parameterBindings == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(parameterBindings);
        requiredFields = requiredFields == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(requiredFields);
        evidenceContract = evidenceContract == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(evidenceContract);
        sourceCoverage = sourceCoverage == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(sourceCoverage);
        confirmation = confirmation == null ? new Confirmation(confirmed ? "CONFIRMED" : "DRAFT", "", "", "") : confirmation;
        semanticSignature = semanticSignature == null ? "" : semanticSignature.trim();

            this.id = id; this.revision = revision; this.businessItemId = businessItemId; this.checkpointId = checkpointId; this.category = category; this.purpose = purpose; this.verificationMode = verificationMode; this.templateId = templateId; this.templateVersion = templateVersion; this.subjectSelector = subjectSelector; this.parameters = parameters; this.applicability = applicability; this.proofStandard = proofStandard; this.requirementRefs = requirementRefs; this.parameterBindings = parameterBindings; this.requiredFields = requiredFields; this.evidenceContract = evidenceContract; this.sourceCoverage = sourceCoverage; this.confirmed = confirmed; this.confirmation = confirmation; this.semanticSignature = semanticSignature;
        }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

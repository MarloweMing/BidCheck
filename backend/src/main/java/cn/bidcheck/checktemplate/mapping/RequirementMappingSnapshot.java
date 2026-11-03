package cn.bidcheck.checktemplate.mapping;

import java.util.List;
import java.util.Map;

/** Persisted mapping draft. It is an inventory artifact and is never an executable CheckSpec. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class RequirementMappingSnapshot {
        private final String schema;
        private final String mappingVersion;
        private final String projectId;
        private final String tenderDocumentId;
        private final int tenderDocumentVersion;
        private final String tenderDocumentHash;
        private final String generatedAt;
        private final String executionState;
        private final boolean affectsBusinessConclusion;
        private final Map<String, Object> summary;
        private final List<RequirementDraft> drafts;
        private final List<CoverageItem> coverage;
        private final RequirementMappingResult.Diagnostics diagnostics;
        public String schema() { return schema; }
        public String mappingVersion() { return mappingVersion; }
        public String projectId() { return projectId; }
        public String tenderDocumentId() { return tenderDocumentId; }
        public int tenderDocumentVersion() { return tenderDocumentVersion; }
        public String tenderDocumentHash() { return tenderDocumentHash; }
        public String generatedAt() { return generatedAt; }
        public String executionState() { return executionState; }
        public boolean affectsBusinessConclusion() { return affectsBusinessConclusion; }
        public Map<String, Object> summary() { return summary; }
        public List<RequirementDraft> drafts() { return drafts; }
        public List<CoverageItem> coverage() { return coverage; }
        public RequirementMappingResult.Diagnostics diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RequirementMappingSnapshot that = (RequirementMappingSnapshot) other; return java.util.Objects.equals(this.schema, that.schema) && java.util.Objects.equals(this.mappingVersion, that.mappingVersion) && java.util.Objects.equals(this.projectId, that.projectId) && java.util.Objects.equals(this.tenderDocumentId, that.tenderDocumentId) && java.util.Objects.equals(this.tenderDocumentVersion, that.tenderDocumentVersion) && java.util.Objects.equals(this.tenderDocumentHash, that.tenderDocumentHash) && java.util.Objects.equals(this.generatedAt, that.generatedAt) && java.util.Objects.equals(this.executionState, that.executionState) && java.util.Objects.equals(this.affectsBusinessConclusion, that.affectsBusinessConclusion) && java.util.Objects.equals(this.summary, that.summary) && java.util.Objects.equals(this.drafts, that.drafts) && java.util.Objects.equals(this.coverage, that.coverage) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.schema); hash = 31 * hash + java.util.Objects.hashCode(this.mappingVersion); hash = 31 * hash + java.util.Objects.hashCode(this.projectId); hash = 31 * hash + java.util.Objects.hashCode(this.tenderDocumentId); hash = 31 * hash + java.util.Objects.hashCode(this.tenderDocumentVersion); hash = 31 * hash + java.util.Objects.hashCode(this.tenderDocumentHash); hash = 31 * hash + java.util.Objects.hashCode(this.generatedAt); hash = 31 * hash + java.util.Objects.hashCode(this.executionState); hash = 31 * hash + java.util.Objects.hashCode(this.affectsBusinessConclusion); hash = 31 * hash + java.util.Objects.hashCode(this.summary); hash = 31 * hash + java.util.Objects.hashCode(this.drafts); hash = 31 * hash + java.util.Objects.hashCode(this.coverage); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "RequirementMappingSnapshot[" + "schema=" + schema + ", mappingVersion=" + mappingVersion + ", projectId=" + projectId + ", tenderDocumentId=" + tenderDocumentId + ", tenderDocumentVersion=" + tenderDocumentVersion + ", tenderDocumentHash=" + tenderDocumentHash + ", generatedAt=" + generatedAt + ", executionState=" + executionState + ", affectsBusinessConclusion=" + affectsBusinessConclusion + ", summary=" + summary + ", drafts=" + drafts + ", coverage=" + coverage + ", diagnostics=" + diagnostics + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public RequirementMappingSnapshot(@com.fasterxml.jackson.annotation.JsonProperty("schema") String schema, @com.fasterxml.jackson.annotation.JsonProperty("mappingVersion") String mappingVersion, @com.fasterxml.jackson.annotation.JsonProperty("projectId") String projectId, @com.fasterxml.jackson.annotation.JsonProperty("tenderDocumentId") String tenderDocumentId, @com.fasterxml.jackson.annotation.JsonProperty("tenderDocumentVersion") int tenderDocumentVersion, @com.fasterxml.jackson.annotation.JsonProperty("tenderDocumentHash") String tenderDocumentHash, @com.fasterxml.jackson.annotation.JsonProperty("generatedAt") String generatedAt, @com.fasterxml.jackson.annotation.JsonProperty("executionState") String executionState, @com.fasterxml.jackson.annotation.JsonProperty("affectsBusinessConclusion") boolean affectsBusinessConclusion, @com.fasterxml.jackson.annotation.JsonProperty("summary") Map<String, Object> summary, @com.fasterxml.jackson.annotation.JsonProperty("drafts") List<RequirementDraft> drafts, @com.fasterxml.jackson.annotation.JsonProperty("coverage") List<CoverageItem> coverage, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") RequirementMappingResult.Diagnostics diagnostics) {
        summary = summary == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(summary);
        drafts = drafts == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(drafts);
        coverage = coverage == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(coverage);

            this.schema = schema; this.mappingVersion = mappingVersion; this.projectId = projectId; this.tenderDocumentId = tenderDocumentId; this.tenderDocumentVersion = tenderDocumentVersion; this.tenderDocumentHash = tenderDocumentHash; this.generatedAt = generatedAt; this.executionState = executionState; this.affectsBusinessConclusion = affectsBusinessConclusion; this.summary = summary; this.drafts = drafts; this.coverage = coverage; this.diagnostics = diagnostics;
        }
}

package cn.bidcheck.checktemplate.mapping;

import java.util.List;
import java.util.Map;

/** Complete local mapping result and its coverage ledger. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class RequirementMappingResult {
        private final List<RequirementDraft> drafts;
        private final List<CoverageItem> coverage;
        private final Diagnostics diagnostics;
        public List<RequirementDraft> drafts() { return drafts; }
        public List<CoverageItem> coverage() { return coverage; }
        public Diagnostics diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RequirementMappingResult that = (RequirementMappingResult) other; return java.util.Objects.equals(this.drafts, that.drafts) && java.util.Objects.equals(this.coverage, that.coverage) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.drafts); hash = 31 * hash + java.util.Objects.hashCode(this.coverage); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "RequirementMappingResult[" + "drafts=" + drafts + ", coverage=" + coverage + ", diagnostics=" + diagnostics + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public RequirementMappingResult(@com.fasterxml.jackson.annotation.JsonProperty("drafts") List<RequirementDraft> drafts, @com.fasterxml.jackson.annotation.JsonProperty("coverage") List<CoverageItem> coverage, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") Diagnostics diagnostics) {
        drafts = drafts == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(drafts);
        coverage = coverage == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(coverage);
        diagnostics = diagnostics == null ? Diagnostics.localOnly(coverage) : diagnostics;

            this.drafts = drafts; this.coverage = coverage; this.diagnostics = diagnostics;
        }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Diagnostics {
        private final boolean localOnly;
        private final int classifierCalls;
        private final int clauseCount;
        private final int fragmentCount;
        private final Map<CoverageItem.Status, Long> coverageCounts;
        public boolean localOnly() { return localOnly; }
        public int classifierCalls() { return classifierCalls; }
        public int clauseCount() { return clauseCount; }
        public int fragmentCount() { return fragmentCount; }
        public Map<CoverageItem.Status, Long> coverageCounts() { return coverageCounts; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Diagnostics that = (Diagnostics) other; return java.util.Objects.equals(this.localOnly, that.localOnly) && java.util.Objects.equals(this.classifierCalls, that.classifierCalls) && java.util.Objects.equals(this.clauseCount, that.clauseCount) && java.util.Objects.equals(this.fragmentCount, that.fragmentCount) && java.util.Objects.equals(this.coverageCounts, that.coverageCounts); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.localOnly); hash = 31 * hash + java.util.Objects.hashCode(this.classifierCalls); hash = 31 * hash + java.util.Objects.hashCode(this.clauseCount); hash = 31 * hash + java.util.Objects.hashCode(this.fragmentCount); hash = 31 * hash + java.util.Objects.hashCode(this.coverageCounts); return hash; }
        @Override public String toString() { return "Diagnostics[" + "localOnly=" + localOnly + ", classifierCalls=" + classifierCalls + ", clauseCount=" + clauseCount + ", fragmentCount=" + fragmentCount + ", coverageCounts=" + coverageCounts + "]"; }


        @com.fasterxml.jackson.annotation.JsonCreator
        public Diagnostics(@com.fasterxml.jackson.annotation.JsonProperty("localOnly") boolean localOnly, @com.fasterxml.jackson.annotation.JsonProperty("classifierCalls") int classifierCalls, @com.fasterxml.jackson.annotation.JsonProperty("clauseCount") int clauseCount, @com.fasterxml.jackson.annotation.JsonProperty("fragmentCount") int fragmentCount, @com.fasterxml.jackson.annotation.JsonProperty("coverageCounts") Map<CoverageItem.Status, Long> coverageCounts) {
            if (classifierCalls < 0 || clauseCount < 0 || fragmentCount < 0) {
                throw new IllegalArgumentException("diagnostic counters must be non-negative");
            }
            coverageCounts = coverageCounts == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(coverageCounts);

            this.localOnly = localOnly; this.classifierCalls = classifierCalls; this.clauseCount = clauseCount; this.fragmentCount = fragmentCount; this.coverageCounts = coverageCounts;
        }

        static Diagnostics localOnly(List<CoverageItem> coverage) {
            List<CoverageItem> safe = coverage == null ? cn.bidcheck.compat.Java8Api.listOf() : coverage;
            return new Diagnostics(true, 0, 0, safe.size(), safe.stream().collect(
                    java.util.stream.Collectors.groupingBy(CoverageItem::status, java.util.stream.Collectors.counting())));
        }
    }
}

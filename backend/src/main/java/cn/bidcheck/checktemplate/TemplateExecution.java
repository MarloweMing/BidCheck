package cn.bidcheck.checktemplate;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class TemplateExecution {
        private final CheckSpec spec;
        private final List<EvidenceNeed> evidenceNeeds;
        private final TemplateResult result;
        private final ResultProjection projection;
        private final String executionRoute;
        public CheckSpec spec() { return spec; }
        public List<EvidenceNeed> evidenceNeeds() { return evidenceNeeds; }
        public TemplateResult result() { return result; }
        public ResultProjection projection() { return projection; }
        public String executionRoute() { return executionRoute; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TemplateExecution that = (TemplateExecution) other; return java.util.Objects.equals(this.spec, that.spec) && java.util.Objects.equals(this.evidenceNeeds, that.evidenceNeeds) && java.util.Objects.equals(this.result, that.result) && java.util.Objects.equals(this.projection, that.projection) && java.util.Objects.equals(this.executionRoute, that.executionRoute); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.spec); hash = 31 * hash + java.util.Objects.hashCode(this.evidenceNeeds); hash = 31 * hash + java.util.Objects.hashCode(this.result); hash = 31 * hash + java.util.Objects.hashCode(this.projection); hash = 31 * hash + java.util.Objects.hashCode(this.executionRoute); return hash; }
        @Override public String toString() { return "TemplateExecution[" + "spec=" + spec + ", evidenceNeeds=" + evidenceNeeds + ", result=" + result + ", projection=" + projection + ", executionRoute=" + executionRoute + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public TemplateExecution(@com.fasterxml.jackson.annotation.JsonProperty("spec") CheckSpec spec, @com.fasterxml.jackson.annotation.JsonProperty("evidenceNeeds") List<EvidenceNeed> evidenceNeeds, @com.fasterxml.jackson.annotation.JsonProperty("result") TemplateResult result, @com.fasterxml.jackson.annotation.JsonProperty("projection") ResultProjection projection, @com.fasterxml.jackson.annotation.JsonProperty("executionRoute") String executionRoute) {
        evidenceNeeds = evidenceNeeds == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(evidenceNeeds);
        executionRoute = executionRoute == null ? "TEMPLATE" : executionRoute;

            this.spec = spec; this.evidenceNeeds = evidenceNeeds; this.result = result; this.projection = projection; this.executionRoute = executionRoute;
        }
}

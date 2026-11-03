package cn.bidcheck.checktemplate;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class ConditionResult {
        private final String id;
        private final String label;
        private final TemplateTypes.ConditionState state;
        private final String reasonCode;
        private final String summary;
        private final List<String> evidenceRefs;
        private final List<String> missingInputs;
        public String id() { return id; }
        public String label() { return label; }
        public TemplateTypes.ConditionState state() { return state; }
        public String reasonCode() { return reasonCode; }
        public String summary() { return summary; }
        public List<String> evidenceRefs() { return evidenceRefs; }
        public List<String> missingInputs() { return missingInputs; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ConditionResult that = (ConditionResult) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.label, that.label) && java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.reasonCode, that.reasonCode) && java.util.Objects.equals(this.summary, that.summary) && java.util.Objects.equals(this.evidenceRefs, that.evidenceRefs) && java.util.Objects.equals(this.missingInputs, that.missingInputs); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.label); hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); hash = 31 * hash + java.util.Objects.hashCode(this.summary); hash = 31 * hash + java.util.Objects.hashCode(this.evidenceRefs); hash = 31 * hash + java.util.Objects.hashCode(this.missingInputs); return hash; }
        @Override public String toString() { return "ConditionResult[" + "id=" + id + ", label=" + label + ", state=" + state + ", reasonCode=" + reasonCode + ", summary=" + summary + ", evidenceRefs=" + evidenceRefs + ", missingInputs=" + missingInputs + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public ConditionResult(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("label") String label, @com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ConditionState state, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode, @com.fasterxml.jackson.annotation.JsonProperty("summary") String summary, @com.fasterxml.jackson.annotation.JsonProperty("evidenceRefs") List<String> evidenceRefs, @com.fasterxml.jackson.annotation.JsonProperty("missingInputs") List<String> missingInputs) {
        if (id == null || cn.bidcheck.compat.Java8Api.isBlank(id)) throw new IllegalArgumentException("id is required");
        id = id.trim();
        label = label == null ? "" : label.trim();
        if (state == null) throw new IllegalArgumentException("state is required");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        summary = summary == null ? "" : summary.trim();
        evidenceRefs = evidenceRefs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(evidenceRefs);
        missingInputs = missingInputs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(missingInputs);

            this.id = id; this.label = label; this.state = state; this.reasonCode = reasonCode; this.summary = summary; this.evidenceRefs = evidenceRefs; this.missingInputs = missingInputs;
        }
}

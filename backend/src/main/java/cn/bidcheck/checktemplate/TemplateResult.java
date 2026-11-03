package cn.bidcheck.checktemplate;

import java.util.List;

/** Pure evaluator output before service-level OCR/security guards reconcile the final result. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class TemplateResult {
        private final TemplateTypes.ConclusionState conclusion;
        private final TemplateTypes.EvidenceState evidence;
        private final String reasonCode;
        private final String summary;
        private final List<ConditionResult> conditions;
        private final List<String> evidenceRefs;
        private final List<String> missingInputs;
        private final List<String> dependencies;
        private final TemplateTypes.ActionOwner actionOwner;
        private final String actionType;
        private final String nextStep;
        public TemplateTypes.ConclusionState conclusion() { return conclusion; }
        public TemplateTypes.EvidenceState evidence() { return evidence; }
        public String reasonCode() { return reasonCode; }
        public String summary() { return summary; }
        public List<ConditionResult> conditions() { return conditions; }
        public List<String> evidenceRefs() { return evidenceRefs; }
        public List<String> missingInputs() { return missingInputs; }
        public List<String> dependencies() { return dependencies; }
        public TemplateTypes.ActionOwner actionOwner() { return actionOwner; }
        public String actionType() { return actionType; }
        public String nextStep() { return nextStep; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; TemplateResult that = (TemplateResult) other; return java.util.Objects.equals(this.conclusion, that.conclusion) && java.util.Objects.equals(this.evidence, that.evidence) && java.util.Objects.equals(this.reasonCode, that.reasonCode) && java.util.Objects.equals(this.summary, that.summary) && java.util.Objects.equals(this.conditions, that.conditions) && java.util.Objects.equals(this.evidenceRefs, that.evidenceRefs) && java.util.Objects.equals(this.missingInputs, that.missingInputs) && java.util.Objects.equals(this.dependencies, that.dependencies) && java.util.Objects.equals(this.actionOwner, that.actionOwner) && java.util.Objects.equals(this.actionType, that.actionType) && java.util.Objects.equals(this.nextStep, that.nextStep); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.conclusion); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); hash = 31 * hash + java.util.Objects.hashCode(this.summary); hash = 31 * hash + java.util.Objects.hashCode(this.conditions); hash = 31 * hash + java.util.Objects.hashCode(this.evidenceRefs); hash = 31 * hash + java.util.Objects.hashCode(this.missingInputs); hash = 31 * hash + java.util.Objects.hashCode(this.dependencies); hash = 31 * hash + java.util.Objects.hashCode(this.actionOwner); hash = 31 * hash + java.util.Objects.hashCode(this.actionType); hash = 31 * hash + java.util.Objects.hashCode(this.nextStep); return hash; }
        @Override public String toString() { return "TemplateResult[" + "conclusion=" + conclusion + ", evidence=" + evidence + ", reasonCode=" + reasonCode + ", summary=" + summary + ", conditions=" + conditions + ", evidenceRefs=" + evidenceRefs + ", missingInputs=" + missingInputs + ", dependencies=" + dependencies + ", actionOwner=" + actionOwner + ", actionType=" + actionType + ", nextStep=" + nextStep + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public TemplateResult(@com.fasterxml.jackson.annotation.JsonProperty("conclusion") TemplateTypes.ConclusionState conclusion, @com.fasterxml.jackson.annotation.JsonProperty("evidence") TemplateTypes.EvidenceState evidence, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode, @com.fasterxml.jackson.annotation.JsonProperty("summary") String summary, @com.fasterxml.jackson.annotation.JsonProperty("conditions") List<ConditionResult> conditions, @com.fasterxml.jackson.annotation.JsonProperty("evidenceRefs") List<String> evidenceRefs, @com.fasterxml.jackson.annotation.JsonProperty("missingInputs") List<String> missingInputs, @com.fasterxml.jackson.annotation.JsonProperty("dependencies") List<String> dependencies, @com.fasterxml.jackson.annotation.JsonProperty("actionOwner") TemplateTypes.ActionOwner actionOwner, @com.fasterxml.jackson.annotation.JsonProperty("actionType") String actionType, @com.fasterxml.jackson.annotation.JsonProperty("nextStep") String nextStep) {
        if (conclusion == null) throw new IllegalArgumentException("conclusion is required");
        if (evidence == null) throw new IllegalArgumentException("evidence is required");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        summary = summary == null ? "" : summary.trim();
        conditions = conditions == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(conditions);
        evidenceRefs = evidenceRefs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(evidenceRefs);
        missingInputs = missingInputs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(missingInputs);
        dependencies = dependencies == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(dependencies);
        actionOwner = actionOwner == null ? TemplateTypes.ActionOwner.NONE : actionOwner;
        actionType = actionType == null ? "NONE" : actionType.trim();
        nextStep = nextStep == null ? "" : nextStep.trim();

            this.conclusion = conclusion; this.evidence = evidence; this.reasonCode = reasonCode; this.summary = summary; this.conditions = conditions; this.evidenceRefs = evidenceRefs; this.missingInputs = missingInputs; this.dependencies = dependencies; this.actionOwner = actionOwner; this.actionType = actionType; this.nextStep = nextStep;
        }
}

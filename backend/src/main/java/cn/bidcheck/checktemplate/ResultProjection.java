package cn.bidcheck.checktemplate;

import java.util.List;

/** Single typed result contract shared by UI and exports. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class ResultProjection {
        private final String schemaVersion;
        private final String businessItemId;
        private final String checkSpecId;
        private final int specRevision;
        private final long resultRevision;
        private final TemplateTypes.Category category;
        private final TemplateTypes.Purpose purpose;
        private final TemplateTypes.VerificationMode verificationMode;
        private final Execution execution;
        private final Conclusion conclusion;
        private final Evidence evidence;
        private final Action action;
        private final List<ConditionResult> subchecks;
        private final List<String> missingInputs;
        private final List<String> dependencies;
        private final Review review;
        private final String evaluatorVersion;
        private final String nextStep;
        private final boolean finalReconciled;
        public String schemaVersion() { return schemaVersion; }
        public String businessItemId() { return businessItemId; }
        public String checkSpecId() { return checkSpecId; }
        public int specRevision() { return specRevision; }
        public long resultRevision() { return resultRevision; }
        public TemplateTypes.Category category() { return category; }
        public TemplateTypes.Purpose purpose() { return purpose; }
        public TemplateTypes.VerificationMode verificationMode() { return verificationMode; }
        public Execution execution() { return execution; }
        public Conclusion conclusion() { return conclusion; }
        public Evidence evidence() { return evidence; }
        public Action action() { return action; }
        public List<ConditionResult> subchecks() { return subchecks; }
        public List<String> missingInputs() { return missingInputs; }
        public List<String> dependencies() { return dependencies; }
        public Review review() { return review; }
        public String evaluatorVersion() { return evaluatorVersion; }
        public String nextStep() { return nextStep; }
        public boolean finalReconciled() { return finalReconciled; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ResultProjection that = (ResultProjection) other; return java.util.Objects.equals(this.schemaVersion, that.schemaVersion) && java.util.Objects.equals(this.businessItemId, that.businessItemId) && java.util.Objects.equals(this.checkSpecId, that.checkSpecId) && java.util.Objects.equals(this.specRevision, that.specRevision) && java.util.Objects.equals(this.resultRevision, that.resultRevision) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.purpose, that.purpose) && java.util.Objects.equals(this.verificationMode, that.verificationMode) && java.util.Objects.equals(this.execution, that.execution) && java.util.Objects.equals(this.conclusion, that.conclusion) && java.util.Objects.equals(this.evidence, that.evidence) && java.util.Objects.equals(this.action, that.action) && java.util.Objects.equals(this.subchecks, that.subchecks) && java.util.Objects.equals(this.missingInputs, that.missingInputs) && java.util.Objects.equals(this.dependencies, that.dependencies) && java.util.Objects.equals(this.review, that.review) && java.util.Objects.equals(this.evaluatorVersion, that.evaluatorVersion) && java.util.Objects.equals(this.nextStep, that.nextStep) && java.util.Objects.equals(this.finalReconciled, that.finalReconciled); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.schemaVersion); hash = 31 * hash + java.util.Objects.hashCode(this.businessItemId); hash = 31 * hash + java.util.Objects.hashCode(this.checkSpecId); hash = 31 * hash + java.util.Objects.hashCode(this.specRevision); hash = 31 * hash + java.util.Objects.hashCode(this.resultRevision); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.purpose); hash = 31 * hash + java.util.Objects.hashCode(this.verificationMode); hash = 31 * hash + java.util.Objects.hashCode(this.execution); hash = 31 * hash + java.util.Objects.hashCode(this.conclusion); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); hash = 31 * hash + java.util.Objects.hashCode(this.action); hash = 31 * hash + java.util.Objects.hashCode(this.subchecks); hash = 31 * hash + java.util.Objects.hashCode(this.missingInputs); hash = 31 * hash + java.util.Objects.hashCode(this.dependencies); hash = 31 * hash + java.util.Objects.hashCode(this.review); hash = 31 * hash + java.util.Objects.hashCode(this.evaluatorVersion); hash = 31 * hash + java.util.Objects.hashCode(this.nextStep); hash = 31 * hash + java.util.Objects.hashCode(this.finalReconciled); return hash; }
        @Override public String toString() { return "ResultProjection[" + "schemaVersion=" + schemaVersion + ", businessItemId=" + businessItemId + ", checkSpecId=" + checkSpecId + ", specRevision=" + specRevision + ", resultRevision=" + resultRevision + ", category=" + category + ", purpose=" + purpose + ", verificationMode=" + verificationMode + ", execution=" + execution + ", conclusion=" + conclusion + ", evidence=" + evidence + ", action=" + action + ", subchecks=" + subchecks + ", missingInputs=" + missingInputs + ", dependencies=" + dependencies + ", review=" + review + ", evaluatorVersion=" + evaluatorVersion + ", nextStep=" + nextStep + ", finalReconciled=" + finalReconciled + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public ResultProjection(@com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") String schemaVersion, @com.fasterxml.jackson.annotation.JsonProperty("businessItemId") String businessItemId, @com.fasterxml.jackson.annotation.JsonProperty("checkSpecId") String checkSpecId, @com.fasterxml.jackson.annotation.JsonProperty("specRevision") int specRevision, @com.fasterxml.jackson.annotation.JsonProperty("resultRevision") long resultRevision, @com.fasterxml.jackson.annotation.JsonProperty("category") TemplateTypes.Category category, @com.fasterxml.jackson.annotation.JsonProperty("purpose") TemplateTypes.Purpose purpose, @com.fasterxml.jackson.annotation.JsonProperty("verificationMode") TemplateTypes.VerificationMode verificationMode, @com.fasterxml.jackson.annotation.JsonProperty("execution") Execution execution, @com.fasterxml.jackson.annotation.JsonProperty("conclusion") Conclusion conclusion, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence, @com.fasterxml.jackson.annotation.JsonProperty("action") Action action, @com.fasterxml.jackson.annotation.JsonProperty("subchecks") List<ConditionResult> subchecks, @com.fasterxml.jackson.annotation.JsonProperty("missingInputs") List<String> missingInputs, @com.fasterxml.jackson.annotation.JsonProperty("dependencies") List<String> dependencies, @com.fasterxml.jackson.annotation.JsonProperty("review") Review review, @com.fasterxml.jackson.annotation.JsonProperty("evaluatorVersion") String evaluatorVersion, @com.fasterxml.jackson.annotation.JsonProperty("nextStep") String nextStep, @com.fasterxml.jackson.annotation.JsonProperty("finalReconciled") boolean finalReconciled) {
        schemaVersion = schemaVersion == null ? "template-result-v1" : schemaVersion;
        subchecks = subchecks == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(subchecks);
        missingInputs = missingInputs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(missingInputs);
        dependencies = dependencies == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(dependencies);
        nextStep = nextStep == null ? "" : nextStep;

            this.schemaVersion = schemaVersion; this.businessItemId = businessItemId; this.checkSpecId = checkSpecId; this.specRevision = specRevision; this.resultRevision = resultRevision; this.category = category; this.purpose = purpose; this.verificationMode = verificationMode; this.execution = execution; this.conclusion = conclusion; this.evidence = evidence; this.action = action; this.subchecks = subchecks; this.missingInputs = missingInputs; this.dependencies = dependencies; this.review = review; this.evaluatorVersion = evaluatorVersion; this.nextStep = nextStep; this.finalReconciled = finalReconciled;
        }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Execution {
        private final TemplateTypes.ExecutionState state;
        private final boolean retryable;
        private final String jobId;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Execution(@com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ExecutionState state, @com.fasterxml.jackson.annotation.JsonProperty("retryable") boolean retryable, @com.fasterxml.jackson.annotation.JsonProperty("jobId") String jobId) { this.state = state; this.retryable = retryable; this.jobId = jobId; }
        public TemplateTypes.ExecutionState state() { return state; }
        public boolean retryable() { return retryable; }
        public String jobId() { return jobId; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Execution that = (Execution) other; return java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.retryable, that.retryable) && java.util.Objects.equals(this.jobId, that.jobId); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.retryable); hash = 31 * hash + java.util.Objects.hashCode(this.jobId); return hash; }
        @Override public String toString() { return "Execution[" + "state=" + state + ", retryable=" + retryable + ", jobId=" + jobId + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Conclusion {
        private final TemplateTypes.ConclusionState state;
        private final String summary;
        private final TemplateTypes.ProofStandard proofScope;
        private final String reasonCode;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Conclusion(@com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ConclusionState state, @com.fasterxml.jackson.annotation.JsonProperty("summary") String summary, @com.fasterxml.jackson.annotation.JsonProperty("proofScope") TemplateTypes.ProofStandard proofScope, @com.fasterxml.jackson.annotation.JsonProperty("reasonCode") String reasonCode) { this.state = state; this.summary = summary; this.proofScope = proofScope; this.reasonCode = reasonCode; }
        public TemplateTypes.ConclusionState state() { return state; }
        public String summary() { return summary; }
        public TemplateTypes.ProofStandard proofScope() { return proofScope; }
        public String reasonCode() { return reasonCode; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Conclusion that = (Conclusion) other; return java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.summary, that.summary) && java.util.Objects.equals(this.proofScope, that.proofScope) && java.util.Objects.equals(this.reasonCode, that.reasonCode); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.summary); hash = 31 * hash + java.util.Objects.hashCode(this.proofScope); hash = 31 * hash + java.util.Objects.hashCode(this.reasonCode); return hash; }
        @Override public String toString() { return "Conclusion[" + "state=" + state + ", summary=" + summary + ", proofScope=" + proofScope + ", reasonCode=" + reasonCode + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Evidence {
        private final TemplateTypes.EvidenceState state;
        private final List<String> refs;
        public TemplateTypes.EvidenceState state() { return state; }
        public List<String> refs() { return refs; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Evidence that = (Evidence) other; return java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.refs, that.refs); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.refs); return hash; }
        @Override public String toString() { return "Evidence[" + "state=" + state + ", refs=" + refs + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Evidence(@com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.EvidenceState state, @com.fasterxml.jackson.annotation.JsonProperty("refs") List<String> refs) { refs = refs == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(refs);
            this.state = state; this.refs = refs;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Action {
        private final TemplateTypes.ActionOwner owner;
        private final String type;
        private final String actionId;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Action(@com.fasterxml.jackson.annotation.JsonProperty("owner") TemplateTypes.ActionOwner owner, @com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("actionId") String actionId) { this.owner = owner; this.type = type; this.actionId = actionId; }
        public TemplateTypes.ActionOwner owner() { return owner; }
        public String type() { return type; }
        public String actionId() { return actionId; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Action that = (Action) other; return java.util.Objects.equals(this.owner, that.owner) && java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.actionId, that.actionId); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.owner); hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.actionId); return hash; }
        @Override public String toString() { return "Action[" + "owner=" + owner + ", type=" + type + ", actionId=" + actionId + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Review {
        private final TemplateTypes.ConclusionState automaticConclusion;
        private final TemplateTypes.ConclusionState humanConclusion;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Review(@com.fasterxml.jackson.annotation.JsonProperty("automaticConclusion") TemplateTypes.ConclusionState automaticConclusion, @com.fasterxml.jackson.annotation.JsonProperty("humanConclusion") TemplateTypes.ConclusionState humanConclusion) { this.automaticConclusion = automaticConclusion; this.humanConclusion = humanConclusion; }
        public TemplateTypes.ConclusionState automaticConclusion() { return automaticConclusion; }
        public TemplateTypes.ConclusionState humanConclusion() { return humanConclusion; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Review that = (Review) other; return java.util.Objects.equals(this.automaticConclusion, that.automaticConclusion) && java.util.Objects.equals(this.humanConclusion, that.humanConclusion); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.automaticConclusion); hash = 31 * hash + java.util.Objects.hashCode(this.humanConclusion); return hash; }
        @Override public String toString() { return "Review[" + "automaticConclusion=" + automaticConclusion + ", humanConclusion=" + humanConclusion + "]"; }
 }
}

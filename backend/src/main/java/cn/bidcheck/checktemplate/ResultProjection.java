package cn.bidcheck.checktemplate;

import java.util.List;

/** Single typed result contract shared by UI and exports. */
public record ResultProjection(
        String schemaVersion,
        String businessItemId,
        String checkSpecId,
        int specRevision,
        long resultRevision,
        TemplateTypes.Category category,
        TemplateTypes.Purpose purpose,
        TemplateTypes.VerificationMode verificationMode,
        Execution execution,
        Conclusion conclusion,
        Evidence evidence,
        Action action,
        List<ConditionResult> subchecks,
        List<String> missingInputs,
        List<String> dependencies,
        Review review,
        String evaluatorVersion,
        String nextStep,
        boolean finalReconciled) {

    public ResultProjection {
        schemaVersion = schemaVersion == null ? "template-result-v1" : schemaVersion;
        subchecks = subchecks == null ? List.of() : List.copyOf(subchecks);
        missingInputs = missingInputs == null ? List.of() : List.copyOf(missingInputs);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        nextStep = nextStep == null ? "" : nextStep;
    }

    public record Execution(TemplateTypes.ExecutionState state, boolean retryable, String jobId) { }
    public record Conclusion(TemplateTypes.ConclusionState state, String summary,
                             TemplateTypes.ProofStandard proofScope, String reasonCode) { }
    public record Evidence(TemplateTypes.EvidenceState state, List<String> refs) {
        public Evidence { refs = refs == null ? List.of() : List.copyOf(refs); }
    }
    public record Action(TemplateTypes.ActionOwner owner, String type, String actionId) { }
    public record Review(TemplateTypes.ConclusionState automaticConclusion,
                         TemplateTypes.ConclusionState humanConclusion) { }
}

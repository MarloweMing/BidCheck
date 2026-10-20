package cn.bidcheck.checktemplate;

import java.util.List;

/** Pure evaluator output before service-level OCR/security guards reconcile the final result. */
public record TemplateResult(
        TemplateTypes.ConclusionState conclusion,
        TemplateTypes.EvidenceState evidence,
        String reasonCode,
        String summary,
        List<ConditionResult> conditions,
        List<String> evidenceRefs,
        List<String> missingInputs,
        List<String> dependencies,
        TemplateTypes.ActionOwner actionOwner,
        String actionType,
        String nextStep) {
    public TemplateResult {
        if (conclusion == null) throw new IllegalArgumentException("conclusion is required");
        if (evidence == null) throw new IllegalArgumentException("evidence is required");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        summary = summary == null ? "" : summary.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        missingInputs = missingInputs == null ? List.of() : List.copyOf(missingInputs);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        actionOwner = actionOwner == null ? TemplateTypes.ActionOwner.NONE : actionOwner;
        actionType = actionType == null ? "NONE" : actionType.trim();
        nextStep = nextStep == null ? "" : nextStep.trim();
    }
}

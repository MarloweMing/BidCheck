package cn.bidcheck.checktemplate;

import java.util.List;

public record ConditionResult(
        String id,
        String label,
        TemplateTypes.ConditionState state,
        String reasonCode,
        String summary,
        List<String> evidenceRefs,
        List<String> missingInputs) {
    public ConditionResult {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        id = id.trim();
        label = label == null ? "" : label.trim();
        if (state == null) throw new IllegalArgumentException("state is required");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        summary = summary == null ? "" : summary.trim();
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        missingInputs = missingInputs == null ? List.of() : List.copyOf(missingInputs);
    }
}

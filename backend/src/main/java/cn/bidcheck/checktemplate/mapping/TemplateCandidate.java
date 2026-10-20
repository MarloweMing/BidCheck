package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;

/** A non-executable template suggestion. It must be confirmed and validated as a CheckSpec first. */
public record TemplateCandidate(
        String templateId,
        String templateVersion,
        double confidence,
        Origin origin,
        String reason,
        Map<String, Object> parameters,
        Map<String, List<SourceRef>> parameterSources,
        List<SourceRef> requirementRefs,
        List<String> missingParameters) {

    public TemplateCandidate {
        if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("templateId is required");
        templateId = templateId.trim();
        templateVersion = templateVersion == null || templateVersion.isBlank() ? "1.0" : templateVersion.trim();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        origin = origin == null ? Origin.DETERMINISTIC_RULE : origin;
        reason = reason == null ? "" : reason.trim();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        parameterSources = parameterSources == null ? Map.of() : parameterSources.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null ? List.of() : List.copyOf(entry.getValue())));
        requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
        missingParameters = missingParameters == null ? List.of() : missingParameters.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    public enum Origin { DETERMINISTIC_RULE, RESTRICTED_CLASSIFIER }
}

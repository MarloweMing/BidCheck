package cn.bidcheck.checktemplate.mapping;

import cn.bidcheck.checktemplate.SourceRef;

import java.util.List;
import java.util.Map;

/** Source-bound draft generated from one requirement fragment. It is deliberately not executable. */
public record RequirementDraft(
        String id,
        String clauseId,
        String fragmentId,
        String text,
        SourceContext sourceContext,
        List<TemplateCandidate> templateCandidates,
        Map<String, Object> parameters,
        Map<String, List<SourceRef>> parameterSources,
        List<SourceRef> requirementRefs,
        List<UnsupportedFragment> unsupportedFragments,
        MappingState mappingState,
        boolean classifierConsulted) {

    public RequirementDraft {
        id = required(id, "id");
        clauseId = required(clauseId, "clauseId");
        fragmentId = required(fragmentId, "fragmentId");
        text = text == null ? "" : text.trim();
        if (sourceContext == null) throw new IllegalArgumentException("sourceContext is required");
        templateCandidates = templateCandidates == null ? List.of() : List.copyOf(templateCandidates);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        parameterSources = parameterSources == null ? Map.of() : Map.copyOf(parameterSources);
        requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
        unsupportedFragments = unsupportedFragments == null ? List.of() : List.copyOf(unsupportedFragments);
        mappingState = mappingState == null ? MappingState.UNSUPPORTED : mappingState;
    }

    public enum MappingState {
        READY_FOR_CONFIRMATION,
        NEEDS_CONFIRMATION,
        AMBIGUOUS,
        EXTERNAL_REQUIRED,
        UNSUPPORTED
    }

    public record UnsupportedFragment(String text, String reasonCode, String explanation, SourceRef sourceRef) {
        public UnsupportedFragment {
            text = text == null ? "" : text.trim();
            reasonCode = required(reasonCode, "reasonCode");
            explanation = explanation == null ? "" : explanation.trim();
            if (sourceRef == null) throw new IllegalArgumentException("sourceRef is required");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

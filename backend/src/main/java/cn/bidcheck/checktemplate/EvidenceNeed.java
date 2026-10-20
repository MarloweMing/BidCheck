package cn.bidcheck.checktemplate;

import java.util.Map;
import java.util.Set;

/** A bounded evidence request. It describes data, never an executable expression. */
public record EvidenceNeed(
        String key,
        TemplateTypes.NeedKind kind,
        SubjectSelector subjectSelector,
        Set<String> requiredFields,
        Map<String, String> constraints,
        boolean required,
        boolean scopeClosureRequired) {
    public EvidenceNeed {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        key = key.trim();
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (subjectSelector == null) throw new IllegalArgumentException("subjectSelector is required");
        requiredFields = requiredFields == null ? Set.of() : Set.copyOf(requiredFields);
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }
}

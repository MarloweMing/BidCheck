package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;

/** Selects a business subject without embedding a project-specific person or company name. */
public record SubjectSelector(String entityType, String role, String scope, Map<String, String> constraints) {
    public SubjectSelector {
        entityType = required(entityType, "entityType");
        role = role == null ? "" : role.trim();
        scope = scope == null || scope.isBlank() ? "CURRENT_PROJECT" : scope.trim();
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }

    public static SubjectSelector bidder() { return new SubjectSelector("ORGANIZATION", "BIDDER", "CURRENT_PROJECT", Map.of()); }
    public static SubjectSelector personnel(String role) { return new SubjectSelector("PERSON", role, "CURRENT_PROJECT", Map.of()); }

    public List<String> validationErrors() {
        if (scope.equals("CURRENT_PROJECT") || scope.equals("CURRENT_LOT") || scope.equals("DOCUMENT")) return List.of();
        return List.of("subjectSelector.scope is unsupported");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

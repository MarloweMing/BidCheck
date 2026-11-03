package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;

/** Selects a business subject without embedding a project-specific person or company name. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class SubjectSelector {
        private final String entityType;
        private final String role;
        private final String scope;
        private final Map<String, String> constraints;
        public String entityType() { return entityType; }
        public String role() { return role; }
        public String scope() { return scope; }
        public Map<String, String> constraints() { return constraints; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SubjectSelector that = (SubjectSelector) other; return java.util.Objects.equals(this.entityType, that.entityType) && java.util.Objects.equals(this.role, that.role) && java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.constraints, that.constraints); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.entityType); hash = 31 * hash + java.util.Objects.hashCode(this.role); hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.constraints); return hash; }
        @Override public String toString() { return "SubjectSelector[" + "entityType=" + entityType + ", role=" + role + ", scope=" + scope + ", constraints=" + constraints + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public SubjectSelector(@com.fasterxml.jackson.annotation.JsonProperty("entityType") String entityType, @com.fasterxml.jackson.annotation.JsonProperty("role") String role, @com.fasterxml.jackson.annotation.JsonProperty("scope") String scope, @com.fasterxml.jackson.annotation.JsonProperty("constraints") Map<String, String> constraints) {
        entityType = required(entityType, "entityType");
        role = role == null ? "" : role.trim();
        scope = scope == null || cn.bidcheck.compat.Java8Api.isBlank(scope) ? "CURRENT_PROJECT" : scope.trim();
        constraints = constraints == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(constraints);

            this.entityType = entityType; this.role = role; this.scope = scope; this.constraints = constraints;
        }

    public static SubjectSelector bidder() { return new SubjectSelector("ORGANIZATION", "BIDDER", "CURRENT_PROJECT", cn.bidcheck.compat.Java8Api.mapOf()); }
    public static SubjectSelector personnel(String role) { return new SubjectSelector("PERSON", role, "CURRENT_PROJECT", cn.bidcheck.compat.Java8Api.mapOf()); }

    public List<String> validationErrors() {
        if (scope.equals("CURRENT_PROJECT") || scope.equals("CURRENT_LOT") || scope.equals("DOCUMENT")) return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.listOf("subjectSelector.scope is unsupported");
    }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

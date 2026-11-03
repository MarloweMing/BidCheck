package cn.bidcheck.checktemplate;

import java.util.Map;
import java.util.Set;

/** A bounded evidence request. It describes data, never an executable expression. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceNeed {
        private final String key;
        private final TemplateTypes.NeedKind kind;
        private final SubjectSelector subjectSelector;
        private final Set<String> requiredFields;
        private final Map<String, String> constraints;
        private final boolean required;
        private final boolean scopeClosureRequired;
        public String key() { return key; }
        public TemplateTypes.NeedKind kind() { return kind; }
        public SubjectSelector subjectSelector() { return subjectSelector; }
        public Set<String> requiredFields() { return requiredFields; }
        public Map<String, String> constraints() { return constraints; }
        public boolean required() { return required; }
        public boolean scopeClosureRequired() { return scopeClosureRequired; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceNeed that = (EvidenceNeed) other; return java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.kind, that.kind) && java.util.Objects.equals(this.subjectSelector, that.subjectSelector) && java.util.Objects.equals(this.requiredFields, that.requiredFields) && java.util.Objects.equals(this.constraints, that.constraints) && java.util.Objects.equals(this.required, that.required) && java.util.Objects.equals(this.scopeClosureRequired, that.scopeClosureRequired); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.kind); hash = 31 * hash + java.util.Objects.hashCode(this.subjectSelector); hash = 31 * hash + java.util.Objects.hashCode(this.requiredFields); hash = 31 * hash + java.util.Objects.hashCode(this.constraints); hash = 31 * hash + java.util.Objects.hashCode(this.required); hash = 31 * hash + java.util.Objects.hashCode(this.scopeClosureRequired); return hash; }
        @Override public String toString() { return "EvidenceNeed[" + "key=" + key + ", kind=" + kind + ", subjectSelector=" + subjectSelector + ", requiredFields=" + requiredFields + ", constraints=" + constraints + ", required=" + required + ", scopeClosureRequired=" + scopeClosureRequired + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceNeed(@com.fasterxml.jackson.annotation.JsonProperty("key") String key, @com.fasterxml.jackson.annotation.JsonProperty("kind") TemplateTypes.NeedKind kind, @com.fasterxml.jackson.annotation.JsonProperty("subjectSelector") SubjectSelector subjectSelector, @com.fasterxml.jackson.annotation.JsonProperty("requiredFields") Set<String> requiredFields, @com.fasterxml.jackson.annotation.JsonProperty("constraints") Map<String, String> constraints, @com.fasterxml.jackson.annotation.JsonProperty("required") boolean required, @com.fasterxml.jackson.annotation.JsonProperty("scopeClosureRequired") boolean scopeClosureRequired) {
        if (key == null || cn.bidcheck.compat.Java8Api.isBlank(key)) throw new IllegalArgumentException("key is required");
        key = key.trim();
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (subjectSelector == null) throw new IllegalArgumentException("subjectSelector is required");
        requiredFields = requiredFields == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(requiredFields);
        constraints = constraints == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(constraints);

            this.key = key; this.kind = kind; this.subjectSelector = subjectSelector; this.requiredFields = requiredFields; this.constraints = constraints; this.required = required; this.scopeClosureRequired = scopeClosureRequired;
        }
}

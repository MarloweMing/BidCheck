package cn.bidcheck.checktemplate;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class ValidationResult {
        private final boolean valid;
        private final List<ValidationError> errors;
        public boolean valid() { return valid; }
        public List<ValidationError> errors() { return errors; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ValidationResult that = (ValidationResult) other; return java.util.Objects.equals(this.valid, that.valid) && java.util.Objects.equals(this.errors, that.errors); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.valid); hash = 31 * hash + java.util.Objects.hashCode(this.errors); return hash; }
        @Override public String toString() { return "ValidationResult[" + "valid=" + valid + ", errors=" + errors + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public ValidationResult(@com.fasterxml.jackson.annotation.JsonProperty("valid") boolean valid, @com.fasterxml.jackson.annotation.JsonProperty("errors") List<ValidationError> errors) { errors = errors == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(errors);
            this.valid = valid; this.errors = errors;
        }
    public static ValidationResult ok() { return new ValidationResult(true, cn.bidcheck.compat.Java8Api.listOf()); }
    public static ValidationResult errors(List<ValidationError> errors) { return new ValidationResult(false, errors); }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class ValidationError {
        private final String field;
        private final String code;
        private final String message;
        @com.fasterxml.jackson.annotation.JsonCreator
        public ValidationError(@com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("message") String message) { this.field = field; this.code = code; this.message = message; }
        public String field() { return field; }
        public String code() { return code; }
        public String message() { return message; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ValidationError that = (ValidationError) other; return java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.message, that.message); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.message); return hash; }
        @Override public String toString() { return "ValidationError[" + "field=" + field + ", code=" + code + ", message=" + message + "]"; }
 }
}

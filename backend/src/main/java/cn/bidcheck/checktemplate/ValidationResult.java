package cn.bidcheck.checktemplate;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors) {
    public ValidationResult { errors = errors == null ? List.of() : List.copyOf(errors); }
    public static ValidationResult ok() { return new ValidationResult(true, List.of()); }
    public static ValidationResult errors(List<ValidationError> errors) { return new ValidationResult(false, errors); }

    public record ValidationError(String field, String code, String message) { }
}

package cn.bidcheck.checktemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small closed schema validator. It rejects unknown properties and never evaluates expressions. */
public final class ParameterSchema {
    private final Map<String, TemplateMetadata.ParameterDefinition> definitions;

    public ParameterSchema(Map<String, TemplateMetadata.ParameterDefinition> definitions) {
        this.definitions = definitions == null ? Map.of() : Map.copyOf(definitions);
    }

    public Map<String, TemplateMetadata.ParameterDefinition> definitions() { return definitions; }

    public ValidationResult validate(Map<String, Object> parameters) {
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        List<ValidationResult.ValidationError> errors = new ArrayList<>();
        for (String name : values.keySet()) {
            if (!definitions.containsKey(name)) errors.add(error(name, "UNKNOWN_PARAMETER", "不支持的模板参数"));
        }
        for (var entry : definitions.entrySet()) {
            String name = entry.getKey();
            TemplateMetadata.ParameterDefinition definition = entry.getValue();
            Object value = values.get(name);
            if (value == null) {
                if (definition.required() && definition.defaultValue() == null) errors.add(error(name, "REQUIRED", "缺少必要参数"));
                continue;
            }
            if (!validType(value, definition.type())) {
                errors.add(error(name, "TYPE_MISMATCH", "参数类型应为 " + definition.type()));
                continue;
            }
            if (!definition.allowedValues().isEmpty() && !definition.allowedValues().contains(String.valueOf(value))) {
                errors.add(error(name, "VALUE_NOT_ALLOWED", "参数值不在允许范围内"));
            }
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.errors(errors);
    }

    public Map<String, Object> withDefaults(Map<String, Object> parameters) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        if (parameters != null) result.putAll(parameters);
        definitions.forEach((name, definition) -> {
            if (!result.containsKey(name) && definition.defaultValue() != null) result.put(name, definition.defaultValue());
        });
        return Map.copyOf(result);
    }

    private boolean validType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String text && !text.isBlank();
            case "boolean" -> value instanceof Boolean;
            case "integer" -> integral(value);
            case "decimal" -> decimal(value);
            case "date" -> date(value);
            case "string-array" -> value instanceof List<?> values && !values.isEmpty()
                    && values.stream().allMatch(item -> item instanceof String text && !text.isBlank());
            default -> false;
        };
    }

    private boolean integral(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return true;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().scale() <= 0;
        if (!(value instanceof String text)) return false;
        try { new java.math.BigInteger(text); return true; } catch (NumberFormatException ignored) { return false; }
    }

    private boolean decimal(Object value) {
        if (value instanceof Number) return true;
        if (!(value instanceof String text)) return false;
        try { new BigDecimal(text); return true; } catch (NumberFormatException ignored) { return false; }
    }

    private boolean date(Object value) {
        if (!(value instanceof String text)) return false;
        try { LocalDate.parse(text); return true; } catch (DateTimeParseException ignored) { return false; }
    }

    private ValidationResult.ValidationError error(String field, String code, String message) {
        return new ValidationResult.ValidationError(field, code, message);
    }
}

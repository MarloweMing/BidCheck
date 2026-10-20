package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Objects;

/** The only supported composition operators. Empty sets never pass. */
public final class RestrictedLogic {
    private RestrictedLogic() { }

    public static TemplateTypes.ConditionState allOf(List<TemplateTypes.ConditionState> states) {
        List<TemplateTypes.ConditionState> values = values(states);
        if (values.isEmpty()) return TemplateTypes.ConditionState.UNKNOWN;
        if (values.stream().anyMatch(v -> v == TemplateTypes.ConditionState.NOT_SATISFIED)) return TemplateTypes.ConditionState.NOT_SATISFIED;
        List<TemplateTypes.ConditionState> applicable = values.stream().filter(v -> v != TemplateTypes.ConditionState.NOT_APPLICABLE).toList();
        if (applicable.isEmpty()) return TemplateTypes.ConditionState.NOT_APPLICABLE;
        if (applicable.stream().anyMatch(v -> v == TemplateTypes.ConditionState.UNKNOWN)) return TemplateTypes.ConditionState.UNKNOWN;
        return TemplateTypes.ConditionState.SATISFIED;
    }

    public static TemplateTypes.ConditionState anyOf(List<TemplateTypes.ConditionState> states) {
        List<TemplateTypes.ConditionState> values = values(states);
        if (values.isEmpty()) return TemplateTypes.ConditionState.UNKNOWN;
        if (values.stream().anyMatch(v -> v == TemplateTypes.ConditionState.SATISFIED)) return TemplateTypes.ConditionState.SATISFIED;
        List<TemplateTypes.ConditionState> applicable = values.stream().filter(v -> v != TemplateTypes.ConditionState.NOT_APPLICABLE).toList();
        if (applicable.isEmpty()) return TemplateTypes.ConditionState.NOT_APPLICABLE;
        if (applicable.stream().anyMatch(v -> v == TemplateTypes.ConditionState.UNKNOWN)) return TemplateTypes.ConditionState.UNKNOWN;
        return TemplateTypes.ConditionState.NOT_SATISFIED;
    }

    public static TemplateTypes.ConditionState atLeastN(List<TemplateTypes.ConditionState> states, int required) {
        if (required < 1) throw new IllegalArgumentException("required must be positive");
        List<TemplateTypes.ConditionState> applicable = values(states).stream()
                .filter(v -> v != TemplateTypes.ConditionState.NOT_APPLICABLE).toList();
        if (applicable.isEmpty()) return TemplateTypes.ConditionState.NOT_APPLICABLE;
        long met = applicable.stream().filter(v -> v == TemplateTypes.ConditionState.SATISFIED).count();
        long unknown = applicable.stream().filter(v -> v == TemplateTypes.ConditionState.UNKNOWN).count();
        if (met >= required) return TemplateTypes.ConditionState.SATISFIED;
        if (met + unknown < required) return TemplateTypes.ConditionState.NOT_SATISFIED;
        return TemplateTypes.ConditionState.UNKNOWN;
    }

    public static TemplateTypes.ConditionState ifApplies(TemplateTypes.ApplicabilityState applicability,
                                                          TemplateTypes.ConditionState result) {
        Objects.requireNonNull(applicability, "applicability");
        Objects.requireNonNull(result, "result");
        return switch (applicability) {
            case DOES_NOT_APPLY -> TemplateTypes.ConditionState.NOT_APPLICABLE;
            case UNKNOWN -> TemplateTypes.ConditionState.UNKNOWN;
            case APPLIES -> result;
        };
    }

    /** FOR_EACH_SUBJECT requires a closed subject set; an empty closed set is not a pass. */
    public static TemplateTypes.ConditionState forEachSubject(List<TemplateTypes.ConditionState> states, boolean subjectSetClosed) {
        if (!subjectSetClosed || states == null || states.isEmpty()) return TemplateTypes.ConditionState.UNKNOWN;
        return allOf(states);
    }

    private static List<TemplateTypes.ConditionState> values(List<TemplateTypes.ConditionState> states) {
        return states == null ? List.of() : states.stream().filter(Objects::nonNull).toList();
    }
}

package cn.bidcheck.checktemplate;

import java.util.List;

/** Records where a critical parameter came from; manual confirmation never masquerades as extraction. */
public record ParameterBinding(String parameter, Object value, String origin, String reason, List<SourceRef> sources) {
    public ParameterBinding {
        if (parameter == null || parameter.isBlank()) throw new IllegalArgumentException("parameter is required");
        parameter = parameter.trim();
        origin = origin == null ? "UNKNOWN" : origin.trim();
        reason = reason == null ? "" : reason.trim();
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

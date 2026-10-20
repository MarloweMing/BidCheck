package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Objects;

public record Applicability(TemplateTypes.ApplicabilityState state, String reason, List<SourceRef> sources) {
    public Applicability {
        state = Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason.trim();
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static Applicability applies() {
        return new Applicability(TemplateTypes.ApplicabilityState.APPLIES, "", List.of());
    }
}

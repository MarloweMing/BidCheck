package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Objects;

@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class Applicability {
        private final TemplateTypes.ApplicabilityState state;
        private final String reason;
        private final List<SourceRef> sources;
        public TemplateTypes.ApplicabilityState state() { return state; }
        public String reason() { return reason; }
        public List<SourceRef> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Applicability that = (Applicability) other; return java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Applicability[" + "state=" + state + ", reason=" + reason + ", sources=" + sources + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public Applicability(@com.fasterxml.jackson.annotation.JsonProperty("state") TemplateTypes.ApplicabilityState state, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources) {
        state = Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason.trim();
        sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);

            this.state = state; this.reason = reason; this.sources = sources;
        }

    public static Applicability applies() {
        return new Applicability(TemplateTypes.ApplicabilityState.APPLIES, "", cn.bidcheck.compat.Java8Api.listOf());
    }
}

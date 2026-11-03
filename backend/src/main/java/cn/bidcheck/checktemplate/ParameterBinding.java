package cn.bidcheck.checktemplate;

import java.util.List;

/** Records where a critical parameter came from; manual confirmation never masquerades as extraction. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class ParameterBinding {
        private final String parameter;
        private final Object value;
        private final String origin;
        private final String reason;
        private final List<SourceRef> sources;
        public String parameter() { return parameter; }
        public Object value() { return value; }
        public String origin() { return origin; }
        public String reason() { return reason; }
        public List<SourceRef> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ParameterBinding that = (ParameterBinding) other; return java.util.Objects.equals(this.parameter, that.parameter) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.origin, that.origin) && java.util.Objects.equals(this.reason, that.reason) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.parameter); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.origin); hash = 31 * hash + java.util.Objects.hashCode(this.reason); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "ParameterBinding[" + "parameter=" + parameter + ", value=" + value + ", origin=" + origin + ", reason=" + reason + ", sources=" + sources + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public ParameterBinding(@com.fasterxml.jackson.annotation.JsonProperty("parameter") String parameter, @com.fasterxml.jackson.annotation.JsonProperty("value") Object value, @com.fasterxml.jackson.annotation.JsonProperty("origin") String origin, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources) {
        if (parameter == null || cn.bidcheck.compat.Java8Api.isBlank(parameter)) throw new IllegalArgumentException("parameter is required");
        parameter = parameter.trim();
        origin = origin == null ? "UNKNOWN" : origin.trim();
        reason = reason == null ? "" : reason.trim();
        sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);

            this.parameter = parameter; this.value = value; this.origin = origin; this.reason = reason; this.sources = sources;
        }
}

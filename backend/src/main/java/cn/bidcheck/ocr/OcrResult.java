package cn.bidcheck.ocr;

@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class OcrResult {
        private final String text;
        private final String provider;
        private final String model;
        private final boolean cached;
        private final OcrLayout layout;
        @com.fasterxml.jackson.annotation.JsonCreator
        public OcrResult(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("provider") String provider, @com.fasterxml.jackson.annotation.JsonProperty("model") String model, @com.fasterxml.jackson.annotation.JsonProperty("cached") boolean cached, @com.fasterxml.jackson.annotation.JsonProperty("layout") OcrLayout layout) { this.text = text; this.provider = provider; this.model = model; this.cached = cached; this.layout = layout; }
        public String text() { return text; }
        public String provider() { return provider; }
        public String model() { return model; }
        public boolean cached() { return cached; }
        public OcrLayout layout() { return layout; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; OcrResult that = (OcrResult) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.provider, that.provider) && java.util.Objects.equals(this.model, that.model) && java.util.Objects.equals(this.cached, that.cached) && java.util.Objects.equals(this.layout, that.layout); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.provider); hash = 31 * hash + java.util.Objects.hashCode(this.model); hash = 31 * hash + java.util.Objects.hashCode(this.cached); hash = 31 * hash + java.util.Objects.hashCode(this.layout); return hash; }
        @Override public String toString() { return "OcrResult[" + "text=" + text + ", provider=" + provider + ", model=" + model + ", cached=" + cached + ", layout=" + layout + "]"; }

    public OcrResult(String text, String provider, String model, boolean cached) { this(text, provider, model, cached, null); }
}

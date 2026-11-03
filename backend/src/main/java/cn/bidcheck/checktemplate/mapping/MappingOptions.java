package cn.bidcheck.checktemplate.mapping;

/** Mapping is local-only unless the caller explicitly enables the restricted classifier. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class MappingOptions {
        private final boolean enableRestrictedClassifier;
        private final double readyConfidenceThreshold;
        public boolean enableRestrictedClassifier() { return enableRestrictedClassifier; }
        public double readyConfidenceThreshold() { return readyConfidenceThreshold; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; MappingOptions that = (MappingOptions) other; return java.util.Objects.equals(this.enableRestrictedClassifier, that.enableRestrictedClassifier) && java.util.Objects.equals(this.readyConfidenceThreshold, that.readyConfidenceThreshold); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.enableRestrictedClassifier); hash = 31 * hash + java.util.Objects.hashCode(this.readyConfidenceThreshold); return hash; }
        @Override public String toString() { return "MappingOptions[" + "enableRestrictedClassifier=" + enableRestrictedClassifier + ", readyConfidenceThreshold=" + readyConfidenceThreshold + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public MappingOptions(@com.fasterxml.jackson.annotation.JsonProperty("enableRestrictedClassifier") boolean enableRestrictedClassifier, @com.fasterxml.jackson.annotation.JsonProperty("readyConfidenceThreshold") double readyConfidenceThreshold) {
        if (!Double.isFinite(readyConfidenceThreshold)
                || readyConfidenceThreshold < 0 || readyConfidenceThreshold > 1) {
            throw new IllegalArgumentException("readyConfidenceThreshold must be between 0 and 1");
        }

            this.enableRestrictedClassifier = enableRestrictedClassifier; this.readyConfidenceThreshold = readyConfidenceThreshold;
        }

    public static MappingOptions localOnly() { return new MappingOptions(false, 0.80d); }
    public static MappingOptions withRestrictedClassifier() { return new MappingOptions(true, 0.80d); }
}

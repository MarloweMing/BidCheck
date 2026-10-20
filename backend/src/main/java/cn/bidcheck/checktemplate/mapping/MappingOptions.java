package cn.bidcheck.checktemplate.mapping;

/** Mapping is local-only unless the caller explicitly enables the restricted classifier. */
public record MappingOptions(boolean enableRestrictedClassifier, double readyConfidenceThreshold) {
    public MappingOptions {
        if (!Double.isFinite(readyConfidenceThreshold)
                || readyConfidenceThreshold < 0 || readyConfidenceThreshold > 1) {
            throw new IllegalArgumentException("readyConfidenceThreshold must be between 0 and 1");
        }
    }

    public static MappingOptions localOnly() { return new MappingOptions(false, 0.80d); }
    public static MappingOptions withRestrictedClassifier() { return new MappingOptions(true, 0.80d); }
}

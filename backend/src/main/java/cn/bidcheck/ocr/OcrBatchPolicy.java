package cn.bidcheck.ocr;

/** Limits apply to one request; caller memory and shared provider capacity remain separate bounds. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class OcrBatchPolicy {
        private final boolean enabled;
        private final int maxPages;
        private final long maxWaitMillis;
        private final long maxBytes;
        private final long maxPixels;
        private final int maxEstimatedTokens;
        public boolean enabled() { return enabled; }
        public int maxPages() { return maxPages; }
        public long maxWaitMillis() { return maxWaitMillis; }
        public long maxBytes() { return maxBytes; }
        public long maxPixels() { return maxPixels; }
        public int maxEstimatedTokens() { return maxEstimatedTokens; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; OcrBatchPolicy that = (OcrBatchPolicy) other; return java.util.Objects.equals(this.enabled, that.enabled) && java.util.Objects.equals(this.maxPages, that.maxPages) && java.util.Objects.equals(this.maxWaitMillis, that.maxWaitMillis) && java.util.Objects.equals(this.maxBytes, that.maxBytes) && java.util.Objects.equals(this.maxPixels, that.maxPixels) && java.util.Objects.equals(this.maxEstimatedTokens, that.maxEstimatedTokens); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.enabled); hash = 31 * hash + java.util.Objects.hashCode(this.maxPages); hash = 31 * hash + java.util.Objects.hashCode(this.maxWaitMillis); hash = 31 * hash + java.util.Objects.hashCode(this.maxBytes); hash = 31 * hash + java.util.Objects.hashCode(this.maxPixels); hash = 31 * hash + java.util.Objects.hashCode(this.maxEstimatedTokens); return hash; }
        @Override public String toString() { return "OcrBatchPolicy[" + "enabled=" + enabled + ", maxPages=" + maxPages + ", maxWaitMillis=" + maxWaitMillis + ", maxBytes=" + maxBytes + ", maxPixels=" + maxPixels + ", maxEstimatedTokens=" + maxEstimatedTokens + "]"; }

    @com.fasterxml.jackson.annotation.JsonCreator
        public OcrBatchPolicy(@com.fasterxml.jackson.annotation.JsonProperty("enabled") boolean enabled, @com.fasterxml.jackson.annotation.JsonProperty("maxPages") int maxPages, @com.fasterxml.jackson.annotation.JsonProperty("maxWaitMillis") long maxWaitMillis, @com.fasterxml.jackson.annotation.JsonProperty("maxBytes") long maxBytes, @com.fasterxml.jackson.annotation.JsonProperty("maxPixels") long maxPixels, @com.fasterxml.jackson.annotation.JsonProperty("maxEstimatedTokens") int maxEstimatedTokens) {
        if(maxPages<1||maxPages>16||maxWaitMillis<0||maxWaitMillis>2000||maxBytes<1024||maxBytes>64L*1024*1024
                ||maxPixels<100_000||maxPixels>64_000_000||maxEstimatedTokens<512||maxEstimatedTokens>64000)
            throw new IllegalArgumentException("OCR 批次预算超出允许范围。");

            this.enabled = enabled; this.maxPages = maxPages; this.maxWaitMillis = maxWaitMillis; this.maxBytes = maxBytes; this.maxPixels = maxPixels; this.maxEstimatedTokens = maxEstimatedTokens;
        }
    public static OcrBatchPolicy single(){return new OcrBatchPolicy(false,1,0,6L*1024*1024,16_000_000,20000);}
    public static OcrBatchPolicy defaults(){return new OcrBatchPolicy(true,8,300,16L*1024*1024,16_000_000,20000);}
}

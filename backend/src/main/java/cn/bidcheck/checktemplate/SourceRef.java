package cn.bidcheck.checktemplate;

import java.util.Objects;

/** Immutable reference to the exact document version and source fragment used by an observation. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class SourceRef {
        private final String documentId;
        private final int documentVersion;
        private final String documentHash;
        private final String parserFingerprint;
        private final String sourceHash;
        private final String locationType;
        private final int location;
        private final String quote;
        private final Integer startOffset;
        private final Integer endOffset;
        public String documentId() { return documentId; }
        public int documentVersion() { return documentVersion; }
        public String documentHash() { return documentHash; }
        public String parserFingerprint() { return parserFingerprint; }
        public String sourceHash() { return sourceHash; }
        public String locationType() { return locationType; }
        public int location() { return location; }
        public String quote() { return quote; }
        public Integer startOffset() { return startOffset; }
        public Integer endOffset() { return endOffset; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SourceRef that = (SourceRef) other; return java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.documentVersion, that.documentVersion) && java.util.Objects.equals(this.documentHash, that.documentHash) && java.util.Objects.equals(this.parserFingerprint, that.parserFingerprint) && java.util.Objects.equals(this.sourceHash, that.sourceHash) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.location, that.location) && java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.startOffset, that.startOffset) && java.util.Objects.equals(this.endOffset, that.endOffset); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.documentVersion); hash = 31 * hash + java.util.Objects.hashCode(this.documentHash); hash = 31 * hash + java.util.Objects.hashCode(this.parserFingerprint); hash = 31 * hash + java.util.Objects.hashCode(this.sourceHash); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.location); hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.startOffset); hash = 31 * hash + java.util.Objects.hashCode(this.endOffset); return hash; }
        @Override public String toString() { return "SourceRef[" + "documentId=" + documentId + ", documentVersion=" + documentVersion + ", documentHash=" + documentHash + ", parserFingerprint=" + parserFingerprint + ", sourceHash=" + sourceHash + ", locationType=" + locationType + ", location=" + location + ", quote=" + quote + ", startOffset=" + startOffset + ", endOffset=" + endOffset + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public SourceRef(@com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("documentVersion") int documentVersion, @com.fasterxml.jackson.annotation.JsonProperty("documentHash") String documentHash, @com.fasterxml.jackson.annotation.JsonProperty("parserFingerprint") String parserFingerprint, @com.fasterxml.jackson.annotation.JsonProperty("sourceHash") String sourceHash, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("location") int location, @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("startOffset") Integer startOffset, @com.fasterxml.jackson.annotation.JsonProperty("endOffset") Integer endOffset) {
        documentId = require(documentId, "documentId");
        if (documentVersion < 0) throw new IllegalArgumentException("documentVersion must be non-negative");
        documentHash = text(documentHash);
        parserFingerprint = text(parserFingerprint);
        sourceHash = text(sourceHash);
        locationType = require(locationType, "locationType");
        if (location < 0) throw new IllegalArgumentException("location must be non-negative");
        quote = cn.bidcheck.compat.Java8Api.requireNonNullElse(quote, "");
        if (startOffset != null && startOffset < 0) throw new IllegalArgumentException("startOffset must be non-negative");
        if (endOffset != null && startOffset != null && endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset must not precede startOffset");
        }

            this.documentId = documentId; this.documentVersion = documentVersion; this.documentHash = documentHash; this.parserFingerprint = parserFingerprint; this.sourceHash = sourceHash; this.locationType = locationType; this.location = location; this.quote = quote; this.startOffset = startOffset; this.endOffset = endOffset;
        }

    public static SourceRef legacy(String documentId, int version, String hash, String locationType,
                                   int location, String quote, String anchor) {
        return new SourceRef(documentId, version, hash, text(anchor), "", locationType, location, quote, null, null);
    }

    private static String require(String value, String name) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}

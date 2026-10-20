package cn.bidcheck.checktemplate;

import java.util.Objects;

/** Immutable reference to the exact document version and source fragment used by an observation. */
public record SourceRef(
        String documentId,
        int documentVersion,
        String documentHash,
        String parserFingerprint,
        String sourceHash,
        String locationType,
        int location,
        String quote,
        Integer startOffset,
        Integer endOffset) {

    public SourceRef {
        documentId = require(documentId, "documentId");
        if (documentVersion < 0) throw new IllegalArgumentException("documentVersion must be non-negative");
        documentHash = text(documentHash);
        parserFingerprint = text(parserFingerprint);
        sourceHash = text(sourceHash);
        locationType = require(locationType, "locationType");
        if (location < 0) throw new IllegalArgumentException("location must be non-negative");
        quote = Objects.requireNonNullElse(quote, "");
        if (startOffset != null && startOffset < 0) throw new IllegalArgumentException("startOffset must be non-negative");
        if (endOffset != null && startOffset != null && endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset must not precede startOffset");
        }
    }

    public static SourceRef legacy(String documentId, int version, String hash, String locationType,
                                   int location, String quote, String anchor) {
        return new SourceRef(documentId, version, hash, text(anchor), "", locationType, location, quote, null, null);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}

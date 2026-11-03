package cn.bidcheck.checktemplate.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import cn.bidcheck.compat.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable identity of one bounded evidence lookup.
 *
 * <p>The identity deliberately includes every input that can change the answer.  Document order
 * and dimension map order are normalized, so two callers asking the same question share one task.
 * A changed document version/hash, parser fingerprint, scope dimension or extractor version creates
 * a new key and therefore cannot accidentally reuse stale positive or negative evidence.</p>
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final class EvidenceNeedKey {
        private final List<DocumentVersion> documents;
        private final String subjectKey;
        private final String factType;
        private final String field;
        private final Map<String, String> dimensions;
        private final String extractorId;
        private final String extractorVersion;
        public List<DocumentVersion> documents() { return documents; }
        public String subjectKey() { return subjectKey; }
        public String factType() { return factType; }
        public String field() { return field; }
        public Map<String, String> dimensions() { return dimensions; }
        public String extractorId() { return extractorId; }
        public String extractorVersion() { return extractorVersion; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; EvidenceNeedKey that = (EvidenceNeedKey) other; return java.util.Objects.equals(this.documents, that.documents) && java.util.Objects.equals(this.subjectKey, that.subjectKey) && java.util.Objects.equals(this.factType, that.factType) && java.util.Objects.equals(this.field, that.field) && java.util.Objects.equals(this.dimensions, that.dimensions) && java.util.Objects.equals(this.extractorId, that.extractorId) && java.util.Objects.equals(this.extractorVersion, that.extractorVersion); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documents); hash = 31 * hash + java.util.Objects.hashCode(this.subjectKey); hash = 31 * hash + java.util.Objects.hashCode(this.factType); hash = 31 * hash + java.util.Objects.hashCode(this.field); hash = 31 * hash + java.util.Objects.hashCode(this.dimensions); hash = 31 * hash + java.util.Objects.hashCode(this.extractorId); hash = 31 * hash + java.util.Objects.hashCode(this.extractorVersion); return hash; }
        @Override public String toString() { return "EvidenceNeedKey[" + "documents=" + documents + ", subjectKey=" + subjectKey + ", factType=" + factType + ", field=" + field + ", dimensions=" + dimensions + ", extractorId=" + extractorId + ", extractorVersion=" + extractorVersion + "]"; }


    @com.fasterxml.jackson.annotation.JsonCreator
        public EvidenceNeedKey(@com.fasterxml.jackson.annotation.JsonProperty("documents") List<DocumentVersion> documents, @com.fasterxml.jackson.annotation.JsonProperty("subjectKey") String subjectKey, @com.fasterxml.jackson.annotation.JsonProperty("factType") String factType, @com.fasterxml.jackson.annotation.JsonProperty("field") String field, @com.fasterxml.jackson.annotation.JsonProperty("dimensions") Map<String, String> dimensions, @com.fasterxml.jackson.annotation.JsonProperty("extractorId") String extractorId, @com.fasterxml.jackson.annotation.JsonProperty("extractorVersion") String extractorVersion) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("documents are required");
        }
        ArrayList<DocumentVersion> sortedDocuments = new ArrayList<>(documents);
        sortedDocuments.sort(Comparator.comparing(DocumentVersion::documentId)
                .thenComparingLong(DocumentVersion::version)
                .thenComparing(DocumentVersion::contentHash)
                .thenComparing(DocumentVersion::parserFingerprint));
        documents = cn.bidcheck.compat.Java8Api.listCopyOf(sortedDocuments);
        subjectKey = required(subjectKey, "subjectKey");
        factType = required(factType, "factType");
        field = required(field, "field");
        TreeMap<String, String> normalizedDimensions = new TreeMap<>();
        if (dimensions != null) {
            dimensions.forEach((key, value) -> normalizedDimensions.put(
                    required(key, "dimension key"), required(value, "dimension value")));
        }
        dimensions = cn.bidcheck.compat.Java8Api.mapCopyOf(normalizedDimensions);
        extractorId = required(extractorId, "extractorId");
        extractorVersion = required(extractorVersion, "extractorVersion");

            this.documents = documents; this.subjectKey = subjectKey; this.factType = factType; this.field = field; this.dimensions = dimensions; this.extractorId = extractorId; this.extractorVersion = extractorVersion;
        }

    /** Content-addressed id suitable for a database primary key. */
    public String id() {
        return sha256(canonical());
    }

    /** Unambiguous canonical form used only for hashing and audit diagnostics. */
    public String canonical() {
        StringBuilder value = new StringBuilder("evidence-need-v1");
        documents.forEach(document -> {
            append(value, document.documentId());
            append(value, Long.toString(document.version()));
            append(value, document.contentHash());
            append(value, document.parserFingerprint());
        });
        append(value, subjectKey);
        append(value, factType);
        append(value, field);
        new TreeMap<>(dimensions).forEach((key, item) -> {
            append(value, key);
            append(value, item);
        });
        append(value, extractorId);
        append(value, extractorVersion);
        return value.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class DocumentVersion {
        private final String documentId;
        private final long version;
        private final String contentHash;
        private final String parserFingerprint;
        public String documentId() { return documentId; }
        public long version() { return version; }
        public String contentHash() { return contentHash; }
        public String parserFingerprint() { return parserFingerprint; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DocumentVersion that = (DocumentVersion) other; return java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.version, that.version) && java.util.Objects.equals(this.contentHash, that.contentHash) && java.util.Objects.equals(this.parserFingerprint, that.parserFingerprint); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.version); hash = 31 * hash + java.util.Objects.hashCode(this.contentHash); hash = 31 * hash + java.util.Objects.hashCode(this.parserFingerprint); return hash; }
        @Override public String toString() { return "DocumentVersion[" + "documentId=" + documentId + ", version=" + version + ", contentHash=" + contentHash + ", parserFingerprint=" + parserFingerprint + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public DocumentVersion(@com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("version") long version, @com.fasterxml.jackson.annotation.JsonProperty("contentHash") String contentHash, @com.fasterxml.jackson.annotation.JsonProperty("parserFingerprint") String parserFingerprint) {
            documentId = required(documentId, "documentId");
            if (version < 0) throw new IllegalArgumentException("document version must be non-negative");
            contentHash = required(contentHash, "contentHash");
            parserFingerprint = required(parserFingerprint, "parserFingerprint");

            this.documentId = documentId; this.version = version; this.contentHash = contentHash; this.parserFingerprint = parserFingerprint;
        }
    }
}

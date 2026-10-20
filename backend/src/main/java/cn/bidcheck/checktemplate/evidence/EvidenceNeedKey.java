package cn.bidcheck.checktemplate.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
public record EvidenceNeedKey(
        List<DocumentVersion> documents,
        String subjectKey,
        String factType,
        String field,
        Map<String, String> dimensions,
        String extractorId,
        String extractorVersion) {

    public EvidenceNeedKey {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("documents are required");
        }
        ArrayList<DocumentVersion> sortedDocuments = new ArrayList<>(documents);
        sortedDocuments.sort(Comparator.comparing(DocumentVersion::documentId)
                .thenComparingLong(DocumentVersion::version)
                .thenComparing(DocumentVersion::contentHash)
                .thenComparing(DocumentVersion::parserFingerprint));
        documents = List.copyOf(sortedDocuments);
        subjectKey = required(subjectKey, "subjectKey");
        factType = required(factType, "factType");
        field = required(field, "field");
        TreeMap<String, String> normalizedDimensions = new TreeMap<>();
        if (dimensions != null) {
            dimensions.forEach((key, value) -> normalizedDimensions.put(
                    required(key, "dimension key"), required(value, "dimension value")));
        }
        dimensions = Map.copyOf(normalizedDimensions);
        extractorId = required(extractorId, "extractorId");
        extractorVersion = required(extractorVersion, "extractorVersion");
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public record DocumentVersion(
            String documentId,
            long version,
            String contentHash,
            String parserFingerprint) {
        public DocumentVersion {
            documentId = required(documentId, "documentId");
            if (version < 0) throw new IllegalArgumentException("document version must be non-negative");
            contentHash = required(contentHash, "contentHash");
            parserFingerprint = required(parserFingerprint, "parserFingerprint");
        }
    }
}

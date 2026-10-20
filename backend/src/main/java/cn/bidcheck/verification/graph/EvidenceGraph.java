package cn.bidcheck.verification.graph;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, source-grounded project evidence graph.  The graph is deliberately small and
 * domain-specific; it is persisted in the application's existing SQL store and does not require
 * a graph database.
 */
public final class EvidenceGraph {
    private EvidenceGraph() { }

    public enum EntityType { PERSON, COMPANY, CERTIFICATE, CONSORTIUM, PROJECT, CONTRACT, ROLE, RESPONSIBILITY, UNKNOWN }
    public enum RelationStatus { CANDIDATE, SUPPORTED, REJECTED }

    public static final Set<String> PREDICATES = Set.of(
            "MEMBER_OF", "LEADS", "RESPONSIBLE_FOR", "APPOINTED_TO", "EMPLOYED_BY",
            "HOLDS_CERTIFICATE", "REGISTERED_WITH", "INSURED_BY", "PARTY_TO",
            "CONTRACT_FOR", "SERVED_AS", "BIDDER_FOR", "RELATED_TO");

    public record DocumentRevision(String documentId, int version, String sha256) {
        public DocumentRevision {
            documentId = required(documentId, "documentId");
            if (version < 1) throw new IllegalArgumentException("GRAPH_DOCUMENT_VERSION_INVALID");
            if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("GRAPH_DOCUMENT_HASH_INVALID");
            sha256 = sha256.toLowerCase();
        }
    }

    /** Complete provenance needed to reopen and revalidate the exact original page. */
    public record SourceRef(String sourceId, String sourceFingerprint, String documentId,
                            int documentVersion, String documentHash, String parserFingerprint,
                            String locationType, int locationNumber, String quote, String anchor,
                            double confidence) {
        public SourceRef {
            sourceId = required(sourceId, "sourceId");
            sourceFingerprint = required(sourceFingerprint, "sourceFingerprint");
            documentId = required(documentId, "documentId");
            if (documentVersion < 1) throw new IllegalArgumentException("GRAPH_SOURCE_VERSION_INVALID");
            if (documentHash == null || !documentHash.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("GRAPH_SOURCE_HASH_INVALID");
            documentHash = documentHash.toLowerCase();
            parserFingerprint = required(parserFingerprint, "parserFingerprint");
            locationType = required(locationType, "locationType");
            if (locationNumber < 0) throw new IllegalArgumentException("GRAPH_SOURCE_LOCATION_INVALID");
            quote = required(quote, "quote");
            anchor = text(anchor);
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new IllegalArgumentException("GRAPH_SOURCE_CONFIDENCE_INVALID");
        }
    }

    public record Entity(String revisionId, String entityKey, EntityType type,
                         String identityStrength, String displayName,
                         List<String> sourceOccurrenceIds, Map<String,Object> attributes) {
        public Entity {
            revisionId = required(revisionId, "revisionId");
            entityKey = required(entityKey, "entityKey");
            type = Objects.requireNonNull(type, "type");
            identityStrength = required(identityStrength, "identityStrength");
            displayName = text(displayName);
            sourceOccurrenceIds = sourceOccurrenceIds == null ? List.of() : sourceOccurrenceIds.stream().filter(Objects::nonNull).map(String::trim).filter(v->!v.isEmpty()).distinct().sorted().toList();
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** Every edge has two entity endpoints and at least one exact source location. */
    public record Relation(String id, String revisionId, String subjectEntityKey, String predicate,
                           String objectEntityKey, String sourceOccurrenceId, List<SourceRef> sources,
                           String scope, String lot, String effectiveAt, RelationStatus status,
                           String resolution, Map<String,Object> attributes) {
        public Relation {
            id = required(id, "id");
            revisionId = required(revisionId, "revisionId");
            subjectEntityKey = required(subjectEntityKey, "subjectEntityKey");
            predicate = required(predicate, "predicate");
            if (!PREDICATES.contains(predicate)) throw new IllegalArgumentException("GRAPH_PREDICATE_INVALID");
            objectEntityKey = required(objectEntityKey, "objectEntityKey");
            sourceOccurrenceId = required(sourceOccurrenceId, "sourceOccurrenceId");
            sources = sources == null ? List.of() : List.copyOf(sources);
            if (sources.isEmpty()) throw new IllegalArgumentException("GRAPH_RELATION_SOURCE_REQUIRED");
            scope = required(scope, "scope");
            if (!Set.of("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").contains(scope))
                throw new IllegalArgumentException("GRAPH_SCOPE_INVALID");
            lot = text(lot); effectiveAt = text(effectiveAt);
            status = Objects.requireNonNull(status, "status");
            resolution = required(resolution, "resolution");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public Set<String> dependencyKeys() {
            return Set.of("graph:entity:" + subjectEntityKey, "graph:entity:" + objectEntityKey,
                    "graph:predicate:" + predicate,
                    "graph:edge:" + subjectEntityKey + ":" + predicate + ":" + objectEntityKey,
                    "graph:occurrence:" + sourceOccurrenceId);
        }
    }

    public record Revision(String id, String projectId, String engineIdentity, String manifestDigest,
                           String occurrenceDigest, String builderVersion, String contentDigest,
                           List<DocumentRevision> documents, String createdAt) {
        public Revision {
            id = required(id, "id"); projectId = required(projectId, "projectId");
            engineIdentity = required(engineIdentity, "engineIdentity");
            manifestDigest = required(manifestDigest, "manifestDigest");
            occurrenceDigest = required(occurrenceDigest, "occurrenceDigest");
            builderVersion = required(builderVersion, "builderVersion");
            contentDigest = required(contentDigest, "contentDigest");
            documents = documents == null ? List.of() : List.copyOf(documents);
            createdAt = text(createdAt);
        }
    }

    public record Snapshot(Revision revision, List<Entity> entities, List<Relation> relations,
                           Map<String,Object> diagnostics) {
        public Snapshot {
            revision = Objects.requireNonNull(revision, "revision");
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
            String scopedRevisionId=revision.id();
            if (entities.stream().anyMatch(entity -> !scopedRevisionId.equals(entity.revisionId()))
                    || relations.stream().anyMatch(relation -> !scopedRevisionId.equals(relation.revisionId())))
                throw new IllegalArgumentException("GRAPH_REVISION_SCOPE_MISMATCH");
            Set<String> keys = entities.stream().map(Entity::entityKey).collect(java.util.stream.Collectors.toSet());
            if (relations.stream().anyMatch(relation -> !keys.contains(relation.subjectEntityKey())
                    || !keys.contains(relation.objectEntityKey())))
                throw new IllegalArgumentException("GRAPH_RELATION_ENDPOINT_MISSING");
        }
    }

    public record BuildResult(Snapshot snapshot, boolean reused, String previousRevisionId,
                              Set<String> changedDependencyKeys) {
        public BuildResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            previousRevisionId = text(previousRevisionId);
            changedDependencyKeys = changedDependencyKeys == null ? Set.of() : Set.copyOf(changedDependencyKeys);
        }
    }

    public record ProofPath(boolean found, String revisionId, String subjectEntityKey,
                            String objectEntityKey, List<Relation> relations,
                            List<SourceRef> sources, String reason) {
        public ProofPath {
            revisionId = required(revisionId, "revisionId");
            subjectEntityKey = required(subjectEntityKey, "subjectEntityKey");
            objectEntityKey = required(objectEntityKey, "objectEntityKey");
            relations = relations == null ? List.of() : List.copyOf(relations);
            sources = sources == null ? List.of() : List.copyOf(sources);
            reason = text(reason);
            if (found && relations.isEmpty() && !subjectEntityKey.equals(objectEntityKey))
                throw new IllegalArgumentException("GRAPH_PROOF_PATH_EMPTY");
        }
    }

    private static String required(String value,String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("GRAPH_" + field.toUpperCase() + "_REQUIRED");
        return value.trim();
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}

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

    public static final Set<String> PREDICATES = cn.bidcheck.compat.Java8Api.setOf("MEMBER_OF", "LEADS", "RESPONSIBLE_FOR", "APPOINTED_TO", "EMPLOYED_BY", "HOLDS_CERTIFICATE", "REGISTERED_WITH", "INSURED_BY", "PARTY_TO", "CONTRACT_FOR", "SERVED_AS", "BIDDER_FOR", "RELATED_TO");

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class DocumentRevision {
        private final String documentId;
        private final int version;
        private final String sha256;
        public String documentId() { return documentId; }
        public int version() { return version; }
        public String sha256() { return sha256; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DocumentRevision that = (DocumentRevision) other; return java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.version, that.version) && java.util.Objects.equals(this.sha256, that.sha256); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.version); hash = 31 * hash + java.util.Objects.hashCode(this.sha256); return hash; }
        @Override public String toString() { return "DocumentRevision[" + "documentId=" + documentId + ", version=" + version + ", sha256=" + sha256 + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public DocumentRevision(@com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("version") int version, @com.fasterxml.jackson.annotation.JsonProperty("sha256") String sha256) {
            documentId = required(documentId, "documentId");
            if (version < 1) throw new IllegalArgumentException("GRAPH_DOCUMENT_VERSION_INVALID");
            if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("GRAPH_DOCUMENT_HASH_INVALID");
            sha256 = sha256.toLowerCase();

            this.documentId = documentId; this.version = version; this.sha256 = sha256;
        }
    }

    /** Complete provenance needed to reopen and revalidate the exact original page. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class SourceRef {
        private final String sourceId;
        private final String sourceFingerprint;
        private final String documentId;
        private final int documentVersion;
        private final String documentHash;
        private final String parserFingerprint;
        private final String locationType;
        private final int locationNumber;
        private final String quote;
        private final String anchor;
        private final double confidence;
        public String sourceId() { return sourceId; }
        public String sourceFingerprint() { return sourceFingerprint; }
        public String documentId() { return documentId; }
        public int documentVersion() { return documentVersion; }
        public String documentHash() { return documentHash; }
        public String parserFingerprint() { return parserFingerprint; }
        public String locationType() { return locationType; }
        public int locationNumber() { return locationNumber; }
        public String quote() { return quote; }
        public String anchor() { return anchor; }
        public double confidence() { return confidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; SourceRef that = (SourceRef) other; return java.util.Objects.equals(this.sourceId, that.sourceId) && java.util.Objects.equals(this.sourceFingerprint, that.sourceFingerprint) && java.util.Objects.equals(this.documentId, that.documentId) && java.util.Objects.equals(this.documentVersion, that.documentVersion) && java.util.Objects.equals(this.documentHash, that.documentHash) && java.util.Objects.equals(this.parserFingerprint, that.parserFingerprint) && java.util.Objects.equals(this.locationType, that.locationType) && java.util.Objects.equals(this.locationNumber, that.locationNumber) && java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.anchor, that.anchor) && java.util.Objects.equals(this.confidence, that.confidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.sourceId); hash = 31 * hash + java.util.Objects.hashCode(this.sourceFingerprint); hash = 31 * hash + java.util.Objects.hashCode(this.documentId); hash = 31 * hash + java.util.Objects.hashCode(this.documentVersion); hash = 31 * hash + java.util.Objects.hashCode(this.documentHash); hash = 31 * hash + java.util.Objects.hashCode(this.parserFingerprint); hash = 31 * hash + java.util.Objects.hashCode(this.locationType); hash = 31 * hash + java.util.Objects.hashCode(this.locationNumber); hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.anchor); hash = 31 * hash + java.util.Objects.hashCode(this.confidence); return hash; }
        @Override public String toString() { return "SourceRef[" + "sourceId=" + sourceId + ", sourceFingerprint=" + sourceFingerprint + ", documentId=" + documentId + ", documentVersion=" + documentVersion + ", documentHash=" + documentHash + ", parserFingerprint=" + parserFingerprint + ", locationType=" + locationType + ", locationNumber=" + locationNumber + ", quote=" + quote + ", anchor=" + anchor + ", confidence=" + confidence + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public SourceRef(@com.fasterxml.jackson.annotation.JsonProperty("sourceId") String sourceId, @com.fasterxml.jackson.annotation.JsonProperty("sourceFingerprint") String sourceFingerprint, @com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId, @com.fasterxml.jackson.annotation.JsonProperty("documentVersion") int documentVersion, @com.fasterxml.jackson.annotation.JsonProperty("documentHash") String documentHash, @com.fasterxml.jackson.annotation.JsonProperty("parserFingerprint") String parserFingerprint, @com.fasterxml.jackson.annotation.JsonProperty("locationType") String locationType, @com.fasterxml.jackson.annotation.JsonProperty("locationNumber") int locationNumber, @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("anchor") String anchor, @com.fasterxml.jackson.annotation.JsonProperty("confidence") double confidence) {
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

            this.sourceId = sourceId; this.sourceFingerprint = sourceFingerprint; this.documentId = documentId; this.documentVersion = documentVersion; this.documentHash = documentHash; this.parserFingerprint = parserFingerprint; this.locationType = locationType; this.locationNumber = locationNumber; this.quote = quote; this.anchor = anchor; this.confidence = confidence;
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Entity {
        private final String revisionId;
        private final String entityKey;
        private final EntityType type;
        private final String identityStrength;
        private final String displayName;
        private final List<String> sourceOccurrenceIds;
        private final Map<String,Object> attributes;
        public String revisionId() { return revisionId; }
        public String entityKey() { return entityKey; }
        public EntityType type() { return type; }
        public String identityStrength() { return identityStrength; }
        public String displayName() { return displayName; }
        public List<String> sourceOccurrenceIds() { return sourceOccurrenceIds; }
        public Map<String,Object> attributes() { return attributes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Entity that = (Entity) other; return java.util.Objects.equals(this.revisionId, that.revisionId) && java.util.Objects.equals(this.entityKey, that.entityKey) && java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.identityStrength, that.identityStrength) && java.util.Objects.equals(this.displayName, that.displayName) && java.util.Objects.equals(this.sourceOccurrenceIds, that.sourceOccurrenceIds) && java.util.Objects.equals(this.attributes, that.attributes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.revisionId); hash = 31 * hash + java.util.Objects.hashCode(this.entityKey); hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.identityStrength); hash = 31 * hash + java.util.Objects.hashCode(this.displayName); hash = 31 * hash + java.util.Objects.hashCode(this.sourceOccurrenceIds); hash = 31 * hash + java.util.Objects.hashCode(this.attributes); return hash; }
        @Override public String toString() { return "Entity[" + "revisionId=" + revisionId + ", entityKey=" + entityKey + ", type=" + type + ", identityStrength=" + identityStrength + ", displayName=" + displayName + ", sourceOccurrenceIds=" + sourceOccurrenceIds + ", attributes=" + attributes + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Entity(@com.fasterxml.jackson.annotation.JsonProperty("revisionId") String revisionId, @com.fasterxml.jackson.annotation.JsonProperty("entityKey") String entityKey, @com.fasterxml.jackson.annotation.JsonProperty("type") EntityType type, @com.fasterxml.jackson.annotation.JsonProperty("identityStrength") String identityStrength, @com.fasterxml.jackson.annotation.JsonProperty("displayName") String displayName, @com.fasterxml.jackson.annotation.JsonProperty("sourceOccurrenceIds") List<String> sourceOccurrenceIds, @com.fasterxml.jackson.annotation.JsonProperty("attributes") Map<String,Object> attributes) {
            revisionId = required(revisionId, "revisionId");
            entityKey = required(entityKey, "entityKey");
            type = Objects.requireNonNull(type, "type");
            identityStrength = required(identityStrength, "identityStrength");
            displayName = text(displayName);
            sourceOccurrenceIds = sourceOccurrenceIds == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.toList(sourceOccurrenceIds.stream().filter(Objects::nonNull).map(String::trim).filter(v->!v.isEmpty()).distinct().sorted());
            attributes = attributes == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(attributes);

            this.revisionId = revisionId; this.entityKey = entityKey; this.type = type; this.identityStrength = identityStrength; this.displayName = displayName; this.sourceOccurrenceIds = sourceOccurrenceIds; this.attributes = attributes;
        }
    }

    /** Every edge has two entity endpoints and at least one exact source location. */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Relation {
        private final String id;
        private final String revisionId;
        private final String subjectEntityKey;
        private final String predicate;
        private final String objectEntityKey;
        private final String sourceOccurrenceId;
        private final List<SourceRef> sources;
        private final String scope;
        private final String lot;
        private final String effectiveAt;
        private final RelationStatus status;
        private final String resolution;
        private final Map<String,Object> attributes;
        public String id() { return id; }
        public String revisionId() { return revisionId; }
        public String subjectEntityKey() { return subjectEntityKey; }
        public String predicate() { return predicate; }
        public String objectEntityKey() { return objectEntityKey; }
        public String sourceOccurrenceId() { return sourceOccurrenceId; }
        public List<SourceRef> sources() { return sources; }
        public String scope() { return scope; }
        public String lot() { return lot; }
        public String effectiveAt() { return effectiveAt; }
        public RelationStatus status() { return status; }
        public String resolution() { return resolution; }
        public Map<String,Object> attributes() { return attributes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Relation that = (Relation) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.revisionId, that.revisionId) && java.util.Objects.equals(this.subjectEntityKey, that.subjectEntityKey) && java.util.Objects.equals(this.predicate, that.predicate) && java.util.Objects.equals(this.objectEntityKey, that.objectEntityKey) && java.util.Objects.equals(this.sourceOccurrenceId, that.sourceOccurrenceId) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.scope, that.scope) && java.util.Objects.equals(this.lot, that.lot) && java.util.Objects.equals(this.effectiveAt, that.effectiveAt) && java.util.Objects.equals(this.status, that.status) && java.util.Objects.equals(this.resolution, that.resolution) && java.util.Objects.equals(this.attributes, that.attributes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.revisionId); hash = 31 * hash + java.util.Objects.hashCode(this.subjectEntityKey); hash = 31 * hash + java.util.Objects.hashCode(this.predicate); hash = 31 * hash + java.util.Objects.hashCode(this.objectEntityKey); hash = 31 * hash + java.util.Objects.hashCode(this.sourceOccurrenceId); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.scope); hash = 31 * hash + java.util.Objects.hashCode(this.lot); hash = 31 * hash + java.util.Objects.hashCode(this.effectiveAt); hash = 31 * hash + java.util.Objects.hashCode(this.status); hash = 31 * hash + java.util.Objects.hashCode(this.resolution); hash = 31 * hash + java.util.Objects.hashCode(this.attributes); return hash; }
        @Override public String toString() { return "Relation[" + "id=" + id + ", revisionId=" + revisionId + ", subjectEntityKey=" + subjectEntityKey + ", predicate=" + predicate + ", objectEntityKey=" + objectEntityKey + ", sourceOccurrenceId=" + sourceOccurrenceId + ", sources=" + sources + ", scope=" + scope + ", lot=" + lot + ", effectiveAt=" + effectiveAt + ", status=" + status + ", resolution=" + resolution + ", attributes=" + attributes + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Relation(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("revisionId") String revisionId, @com.fasterxml.jackson.annotation.JsonProperty("subjectEntityKey") String subjectEntityKey, @com.fasterxml.jackson.annotation.JsonProperty("predicate") String predicate, @com.fasterxml.jackson.annotation.JsonProperty("objectEntityKey") String objectEntityKey, @com.fasterxml.jackson.annotation.JsonProperty("sourceOccurrenceId") String sourceOccurrenceId, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("scope") String scope, @com.fasterxml.jackson.annotation.JsonProperty("lot") String lot, @com.fasterxml.jackson.annotation.JsonProperty("effectiveAt") String effectiveAt, @com.fasterxml.jackson.annotation.JsonProperty("status") RelationStatus status, @com.fasterxml.jackson.annotation.JsonProperty("resolution") String resolution, @com.fasterxml.jackson.annotation.JsonProperty("attributes") Map<String,Object> attributes) {
            id = required(id, "id");
            revisionId = required(revisionId, "revisionId");
            subjectEntityKey = required(subjectEntityKey, "subjectEntityKey");
            predicate = required(predicate, "predicate");
            if (!PREDICATES.contains(predicate)) throw new IllegalArgumentException("GRAPH_PREDICATE_INVALID");
            objectEntityKey = required(objectEntityKey, "objectEntityKey");
            sourceOccurrenceId = required(sourceOccurrenceId, "sourceOccurrenceId");
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            if (sources.isEmpty()) throw new IllegalArgumentException("GRAPH_RELATION_SOURCE_REQUIRED");
            scope = required(scope, "scope");
            if (!cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT", "HISTORICAL_PROJECT", "UNRESOLVED").contains(scope))
                throw new IllegalArgumentException("GRAPH_SCOPE_INVALID");
            lot = text(lot); effectiveAt = text(effectiveAt);
            status = Objects.requireNonNull(status, "status");
            resolution = required(resolution, "resolution");
            attributes = attributes == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(attributes);

            this.id = id; this.revisionId = revisionId; this.subjectEntityKey = subjectEntityKey; this.predicate = predicate; this.objectEntityKey = objectEntityKey; this.sourceOccurrenceId = sourceOccurrenceId; this.sources = sources; this.scope = scope; this.lot = lot; this.effectiveAt = effectiveAt; this.status = status; this.resolution = resolution; this.attributes = attributes;
        }

        public Set<String> dependencyKeys() {
            return cn.bidcheck.compat.Java8Api.setOf("graph:entity:" + subjectEntityKey, "graph:entity:" + objectEntityKey, "graph:predicate:" + predicate, "graph:edge:" + subjectEntityKey + ":" + predicate + ":" + objectEntityKey, "graph:occurrence:" + sourceOccurrenceId);
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Revision {
        private final String id;
        private final String projectId;
        private final String engineIdentity;
        private final String manifestDigest;
        private final String occurrenceDigest;
        private final String builderVersion;
        private final String contentDigest;
        private final List<DocumentRevision> documents;
        private final String createdAt;
        public String id() { return id; }
        public String projectId() { return projectId; }
        public String engineIdentity() { return engineIdentity; }
        public String manifestDigest() { return manifestDigest; }
        public String occurrenceDigest() { return occurrenceDigest; }
        public String builderVersion() { return builderVersion; }
        public String contentDigest() { return contentDigest; }
        public List<DocumentRevision> documents() { return documents; }
        public String createdAt() { return createdAt; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Revision that = (Revision) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.projectId, that.projectId) && java.util.Objects.equals(this.engineIdentity, that.engineIdentity) && java.util.Objects.equals(this.manifestDigest, that.manifestDigest) && java.util.Objects.equals(this.occurrenceDigest, that.occurrenceDigest) && java.util.Objects.equals(this.builderVersion, that.builderVersion) && java.util.Objects.equals(this.contentDigest, that.contentDigest) && java.util.Objects.equals(this.documents, that.documents) && java.util.Objects.equals(this.createdAt, that.createdAt); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.projectId); hash = 31 * hash + java.util.Objects.hashCode(this.engineIdentity); hash = 31 * hash + java.util.Objects.hashCode(this.manifestDigest); hash = 31 * hash + java.util.Objects.hashCode(this.occurrenceDigest); hash = 31 * hash + java.util.Objects.hashCode(this.builderVersion); hash = 31 * hash + java.util.Objects.hashCode(this.contentDigest); hash = 31 * hash + java.util.Objects.hashCode(this.documents); hash = 31 * hash + java.util.Objects.hashCode(this.createdAt); return hash; }
        @Override public String toString() { return "Revision[" + "id=" + id + ", projectId=" + projectId + ", engineIdentity=" + engineIdentity + ", manifestDigest=" + manifestDigest + ", occurrenceDigest=" + occurrenceDigest + ", builderVersion=" + builderVersion + ", contentDigest=" + contentDigest + ", documents=" + documents + ", createdAt=" + createdAt + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Revision(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("projectId") String projectId, @com.fasterxml.jackson.annotation.JsonProperty("engineIdentity") String engineIdentity, @com.fasterxml.jackson.annotation.JsonProperty("manifestDigest") String manifestDigest, @com.fasterxml.jackson.annotation.JsonProperty("occurrenceDigest") String occurrenceDigest, @com.fasterxml.jackson.annotation.JsonProperty("builderVersion") String builderVersion, @com.fasterxml.jackson.annotation.JsonProperty("contentDigest") String contentDigest, @com.fasterxml.jackson.annotation.JsonProperty("documents") List<DocumentRevision> documents, @com.fasterxml.jackson.annotation.JsonProperty("createdAt") String createdAt) {
            id = required(id, "id"); projectId = required(projectId, "projectId");
            engineIdentity = required(engineIdentity, "engineIdentity");
            manifestDigest = required(manifestDigest, "manifestDigest");
            occurrenceDigest = required(occurrenceDigest, "occurrenceDigest");
            builderVersion = required(builderVersion, "builderVersion");
            contentDigest = required(contentDigest, "contentDigest");
            documents = documents == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(documents);
            createdAt = text(createdAt);

            this.id = id; this.projectId = projectId; this.engineIdentity = engineIdentity; this.manifestDigest = manifestDigest; this.occurrenceDigest = occurrenceDigest; this.builderVersion = builderVersion; this.contentDigest = contentDigest; this.documents = documents; this.createdAt = createdAt;
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Snapshot {
        private final Revision revision;
        private final List<Entity> entities;
        private final List<Relation> relations;
        private final Map<String,Object> diagnostics;
        public Revision revision() { return revision; }
        public List<Entity> entities() { return entities; }
        public List<Relation> relations() { return relations; }
        public Map<String,Object> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Snapshot that = (Snapshot) other; return java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.entities, that.entities) && java.util.Objects.equals(this.relations, that.relations) && java.util.Objects.equals(this.diagnostics, that.diagnostics); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.entities); hash = 31 * hash + java.util.Objects.hashCode(this.relations); hash = 31 * hash + java.util.Objects.hashCode(this.diagnostics); return hash; }
        @Override public String toString() { return "Snapshot[" + "revision=" + revision + ", entities=" + entities + ", relations=" + relations + ", diagnostics=" + diagnostics + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Snapshot(@com.fasterxml.jackson.annotation.JsonProperty("revision") Revision revision, @com.fasterxml.jackson.annotation.JsonProperty("entities") List<Entity> entities, @com.fasterxml.jackson.annotation.JsonProperty("relations") List<Relation> relations, @com.fasterxml.jackson.annotation.JsonProperty("diagnostics") Map<String,Object> diagnostics) {
            revision = Objects.requireNonNull(revision, "revision");
            entities = entities == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(entities);
            relations = relations == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(relations);
            diagnostics = diagnostics == null ? cn.bidcheck.compat.Java8Api.mapOf() : cn.bidcheck.compat.Java8Api.mapCopyOf(diagnostics);
            String scopedRevisionId=revision.id();
            if (entities.stream().anyMatch(entity -> !scopedRevisionId.equals(entity.revisionId()))
                    || relations.stream().anyMatch(relation -> !scopedRevisionId.equals(relation.revisionId())))
                throw new IllegalArgumentException("GRAPH_REVISION_SCOPE_MISMATCH");
            Set<String> keys = entities.stream().map(Entity::entityKey).collect(java.util.stream.Collectors.toSet());
            if (relations.stream().anyMatch(relation -> !keys.contains(relation.subjectEntityKey())
                    || !keys.contains(relation.objectEntityKey())))
                throw new IllegalArgumentException("GRAPH_RELATION_ENDPOINT_MISSING");

            this.revision = revision; this.entities = entities; this.relations = relations; this.diagnostics = diagnostics;
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class BuildResult {
        private final Snapshot snapshot;
        private final boolean reused;
        private final String previousRevisionId;
        private final Set<String> changedDependencyKeys;
        public Snapshot snapshot() { return snapshot; }
        public boolean reused() { return reused; }
        public String previousRevisionId() { return previousRevisionId; }
        public Set<String> changedDependencyKeys() { return changedDependencyKeys; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; BuildResult that = (BuildResult) other; return java.util.Objects.equals(this.snapshot, that.snapshot) && java.util.Objects.equals(this.reused, that.reused) && java.util.Objects.equals(this.previousRevisionId, that.previousRevisionId) && java.util.Objects.equals(this.changedDependencyKeys, that.changedDependencyKeys); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.snapshot); hash = 31 * hash + java.util.Objects.hashCode(this.reused); hash = 31 * hash + java.util.Objects.hashCode(this.previousRevisionId); hash = 31 * hash + java.util.Objects.hashCode(this.changedDependencyKeys); return hash; }
        @Override public String toString() { return "BuildResult[" + "snapshot=" + snapshot + ", reused=" + reused + ", previousRevisionId=" + previousRevisionId + ", changedDependencyKeys=" + changedDependencyKeys + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public BuildResult(@com.fasterxml.jackson.annotation.JsonProperty("snapshot") Snapshot snapshot, @com.fasterxml.jackson.annotation.JsonProperty("reused") boolean reused, @com.fasterxml.jackson.annotation.JsonProperty("previousRevisionId") String previousRevisionId, @com.fasterxml.jackson.annotation.JsonProperty("changedDependencyKeys") Set<String> changedDependencyKeys) {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            previousRevisionId = text(previousRevisionId);
            changedDependencyKeys = changedDependencyKeys == null ? cn.bidcheck.compat.Java8Api.setOf() : cn.bidcheck.compat.Java8Api.setCopyOf(changedDependencyKeys);

            this.snapshot = snapshot; this.reused = reused; this.previousRevisionId = previousRevisionId; this.changedDependencyKeys = changedDependencyKeys;
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class ProofPath {
        private final boolean found;
        private final String revisionId;
        private final String subjectEntityKey;
        private final String objectEntityKey;
        private final List<Relation> relations;
        private final List<SourceRef> sources;
        private final String reason;
        public boolean found() { return found; }
        public String revisionId() { return revisionId; }
        public String subjectEntityKey() { return subjectEntityKey; }
        public String objectEntityKey() { return objectEntityKey; }
        public List<Relation> relations() { return relations; }
        public List<SourceRef> sources() { return sources; }
        public String reason() { return reason; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ProofPath that = (ProofPath) other; return java.util.Objects.equals(this.found, that.found) && java.util.Objects.equals(this.revisionId, that.revisionId) && java.util.Objects.equals(this.subjectEntityKey, that.subjectEntityKey) && java.util.Objects.equals(this.objectEntityKey, that.objectEntityKey) && java.util.Objects.equals(this.relations, that.relations) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.reason, that.reason); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.found); hash = 31 * hash + java.util.Objects.hashCode(this.revisionId); hash = 31 * hash + java.util.Objects.hashCode(this.subjectEntityKey); hash = 31 * hash + java.util.Objects.hashCode(this.objectEntityKey); hash = 31 * hash + java.util.Objects.hashCode(this.relations); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.reason); return hash; }
        @Override public String toString() { return "ProofPath[" + "found=" + found + ", revisionId=" + revisionId + ", subjectEntityKey=" + subjectEntityKey + ", objectEntityKey=" + objectEntityKey + ", relations=" + relations + ", sources=" + sources + ", reason=" + reason + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public ProofPath(@com.fasterxml.jackson.annotation.JsonProperty("found") boolean found, @com.fasterxml.jackson.annotation.JsonProperty("revisionId") String revisionId, @com.fasterxml.jackson.annotation.JsonProperty("subjectEntityKey") String subjectEntityKey, @com.fasterxml.jackson.annotation.JsonProperty("objectEntityKey") String objectEntityKey, @com.fasterxml.jackson.annotation.JsonProperty("relations") List<Relation> relations, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<SourceRef> sources, @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) {
            revisionId = required(revisionId, "revisionId");
            subjectEntityKey = required(subjectEntityKey, "subjectEntityKey");
            objectEntityKey = required(objectEntityKey, "objectEntityKey");
            relations = relations == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(relations);
            sources = sources == null ? cn.bidcheck.compat.Java8Api.listOf() : cn.bidcheck.compat.Java8Api.listCopyOf(sources);
            reason = text(reason);
            if (found && relations.isEmpty() && !subjectEntityKey.equals(objectEntityKey))
                throw new IllegalArgumentException("GRAPH_PROOF_PATH_EMPTY");

            this.found = found; this.revisionId = revisionId; this.subjectEntityKey = subjectEntityKey; this.objectEntityKey = objectEntityKey; this.relations = relations; this.sources = sources; this.reason = reason;
        }
    }

    private static String required(String value,String field) {
        if (value == null || cn.bidcheck.compat.Java8Api.isBlank(value)) throw new IllegalArgumentException("GRAPH_" + field.toUpperCase() + "_REQUIRED");
        return value.trim();
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}

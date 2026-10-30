package cn.bidcheck.persistence;

/** Explicit SQL projections; JSON is limited to multivalue attributes and provenance audit. */
public final class FactRows {
 private FactRows(){}
 public static final class Entity {
  public String id;
  public String entityId;
  public String projectId;
  public String engineKey;
  public String engineIdentity;
  public String entityType;
  public String identityStrength;
  public String displayName;
  public String identifiersJson;
  public String claimsJson;
  public String auditJson;
  public String contentHash;
 }
 public static final class Occurrence {
  public String id;
  public String projectId;
  public String engineKey;
  public String engineIdentity;
  public String entityKey;
  public String kind;
  public String subject;
  public String field;
  public String fieldHash;
  public String rawValue;
  public String value;
  public String unit;
  public String basis;
  public String scope;
  public String modelFactId;
  public String attributesJson;
  public String entityAuditJson;
  public String layoutIdentity;
  public String createdAt;
  public String ledgerVersion;
  public String documentId;
  public int documentVersion;
  public String documentHash;
  public String contentHash;
  public String sourceDomain;
  public String extractionMethod;
 }
 public static final class Evidence {
  public String id;
  public String projectId;
  public String engineKey;
  public String documentId;
  public String documentName;
  public String documentHash;
  public int documentVersion;
  public String parserFingerprint;
  public String sourceFingerprint;
  public String locationType;
  public int locationNumber;
  public String quote;
  public String anchor;
  public double confidence;
  public String contentHash;
 }
 public static final class Link {
  public String id;
  public String occurrenceId;
  public String projectId;
  public String engineKey;
  public String subjectEntityKey;
  public String kind;
  public String predicate;
  public String predicateHash;
  public String object;
  public String scope;
  public String resolution;
  public String attributesJson;
 }
 public static final class NativeFact {
  public String occurrenceId;
  public String attributesJson;
  public String field;
  public String rawValue;
  public String value;
  public String projectId;
  public String documentId;
  public String documentName;
  public String documentHash;
  public int documentVersion;
  public String parserFingerprint;
  public String sourceFingerprint;
  public int locationNumber;
  public String locationType;
  public String quote;
  public String anchor;
  public double confidence;
  public String sourceId;
  public int sourceOrder;
 }
 public static final class Source extends EvidenceBase { public String occurrenceId; }
 public static class EvidenceBase {
  public String id;
  public String projectId;
  public String engineKey;
  public String documentId;
  public String documentName;
  public String documentHash;
  public int documentVersion;
  public String parserFingerprint;
  public String sourceFingerprint;
  public String locationType;
  public int locationNumber;
  public String quote;
  public String anchor;
  public double confidence;
  public String contentHash;
  public String sourceId;
  public int sourceOrder;
 }
}

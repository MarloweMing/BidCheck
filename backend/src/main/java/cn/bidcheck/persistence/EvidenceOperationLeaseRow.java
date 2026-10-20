package cn.bidcheck.persistence;

/** Mutable, short-lived ownership record for a durable evidence operation. */
public final class EvidenceOperationLeaseRow {
    public String operationKey;
    public String projectId;
    public String owner;
    public long leaseUntil;
    public long acquiredAt;
}

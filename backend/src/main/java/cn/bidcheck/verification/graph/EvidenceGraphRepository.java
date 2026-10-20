package cn.bidcheck.verification.graph;

import java.util.Optional;

/** Durable immutable graph snapshots, scoped by project and content-derived revision id. */
public interface EvidenceGraphRepository {
    Optional<EvidenceGraph.Snapshot> find(String projectId,String revisionId);
    EvidenceGraph.Snapshot publish(EvidenceGraph.Snapshot snapshot);
}

package cn.bidcheck.verification.graph;

import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores the whole immutable graph revision in qc_record (H2/MySQL), using the existing index. */
@Repository
public class StoreEvidenceGraphRepository implements EvidenceGraphRepository {
    static final String KIND="evidence_graph_snapshot";
    private final Store store;
    public StoreEvidenceGraphRepository(Store store){this.store=Objects.requireNonNull(store);}

    @Override public Optional<EvidenceGraph.Snapshot> find(String projectId,String revisionId) {
        if(projectId==null||projectId.isBlank()||revisionId==null||revisionId.isBlank())return Optional.empty();
        Stored row=store.mapper().get(revisionId);
        if(row==null||!KIND.equals(row.kind)||!projectId.equals(row.projectId)||!revisionId.equals(row.parentId))return Optional.empty();
        EvidenceGraph.Snapshot snapshot=store.decode(row.body,EvidenceGraph.Snapshot.class);
        if(!revisionId.equals(snapshot.revision().id())||!projectId.equals(snapshot.revision().projectId()))
            throw new IllegalStateException("GRAPH_STORED_SCOPE_MISMATCH");
        return Optional.of(snapshot);
    }

    @Override @Transactional
    public EvidenceGraph.Snapshot publish(EvidenceGraph.Snapshot snapshot) {
        Objects.requireNonNull(snapshot,"snapshot");EvidenceGraph.Revision revision=snapshot.revision();
        Optional<EvidenceGraph.Snapshot> previous=find(revision.projectId(),revision.id());
        if(previous.isPresent())return same(previous.get(),snapshot);
        try {
            store.insertImmutableIfAbsent(KIND,revision.id(),revision.projectId(),revision.id(),snapshot);
        } catch(IllegalStateException conflict) {
            Optional<EvidenceGraph.Snapshot> raced=find(revision.projectId(),revision.id());
            if(raced.isPresent())return same(raced.get(),snapshot);
            throw conflict;
        }
        return snapshot;
    }

    private static EvidenceGraph.Snapshot same(EvidenceGraph.Snapshot stored,EvidenceGraph.Snapshot proposed) {
        if(!stored.revision().contentDigest().equals(proposed.revision().contentDigest())
                ||!stored.revision().manifestDigest().equals(proposed.revision().manifestDigest())
                ||!stored.revision().occurrenceDigest().equals(proposed.revision().occurrenceDigest()))
            throw new IllegalStateException("GRAPH_IMMUTABLE_REVISION_CONFLICT");
        return stored;
    }
}

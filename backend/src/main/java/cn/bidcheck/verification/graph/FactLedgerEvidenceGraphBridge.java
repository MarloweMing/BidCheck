package cn.bidcheck.verification.graph;

import cn.bidcheck.checktemplate.EvidenceNeed;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.verification.facts.FactLedgerService;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Low-risk integration boundary: FactLedger remains authoritative for facts and source
 * revalidation; this bridge projects its run view into a reusable evidence graph.
 */
@Service
public final class FactLedgerEvidenceGraphBridge {
    private final FactLedgerService ledger;
    private final EvidenceGraphService graphs;
    public FactLedgerEvidenceGraphBridge(FactLedgerService ledger,EvidenceGraphService graphs){this.ledger=Objects.requireNonNull(ledger);this.graphs=Objects.requireNonNull(graphs);}

    public RefreshResult refresh(String projectId,String runId,String engineIdentity,List<Document> manifest,
                                 String previousRevisionId,Collection<EvidenceNeed> activeNeeds) {
        Map<String,Object> snapshot=ledger.view(projectId,runId,engineIdentity,manifest);
        EvidenceGraph.BuildResult result=graphs.build(projectId,engineIdentity,manifest,snapshot,previousRevisionId);
        Set<String> changedNeeds=EvidenceGraphDependencyAdapter.affectedNeedKeys(activeNeeds,result.changedDependencyKeys());
        return new RefreshResult(result,changedNeeds);
    }

    /**
     * A full run can publish facts through the consistency and focused-check ledgers.  This method
     * merges their already revalidated occurrences into one project graph without copying or
     * weakening source provenance.
     */
    public RefreshResult refreshCombined(String projectId,String runId,Collection<String> ledgerIdentities,
                                         List<Document> manifest,String previousRevisionId,
                                         Collection<EvidenceNeed> activeNeeds) {
        LinkedHashMap<String,Map<String,Object>> occurrences=new LinkedHashMap<>();
        LinkedHashSet<String> engines=new LinkedHashSet<>();
        for(String identity:ledgerIdentities==null?List.<String>of():ledgerIdentities){if(identity==null||identity.isBlank()||!engines.add(identity))continue;
            Map<String,Object> view=ledger.view(projectId,runId,identity,manifest);
            for(Map<String,Object> occurrence:maps(view.get("occurrences"))){String id=Objects.toString(occurrence.get("occurrenceId"),"");if(!id.isBlank())occurrences.putIfAbsent(id,occurrence);}
        }
        String graphIdentity="project-evidence|"+String.join("|",engines.stream().sorted().toList());
        Map<String,Object> merged=new LinkedHashMap<>();merged.put("occurrences",new ArrayList<>(occurrences.values()));
        merged.put("sourceLedgerIdentities",engines.stream().sorted().toList());
        EvidenceGraph.BuildResult result=graphs.build(projectId,graphIdentity,manifest,merged,previousRevisionId);
        return new RefreshResult(result,EvidenceGraphDependencyAdapter.affectedNeedKeys(activeNeeds,result.changedDependencyKeys()));
    }

    public EvidenceGraph.ProofPath proofPath(String projectId,String revisionId,String from,String to,
                                             Set<String> predicates,int maxDepth) {
        return graphs.proofPath(projectId,revisionId,from,to,predicates,maxDepth);
    }

    public EvidenceGraph.ProofPath proofPath(String projectId,String revisionId,String from,String to,
                                             Set<String> predicates,Set<String> scopes,int maxDepth) {
        return graphs.proofPath(projectId,revisionId,from,to,predicates,scopes,maxDepth);
    }

    /**
     * Supplies only source-supported, current-project occurrences from an earlier immutable graph
     * revision.  The ledger then revalidates the underlying original occurrence against the current
     * manifest; candidate/unresolved/historical edges never enter the rule engine through this path.
     */
    public PriorEvidenceReuse reusableCurrentProjectOccurrences(String projectId,String revisionId,
                                                                 List<Document> manifest) {
        if(projectId==null||projectId.isBlank()||revisionId==null||revisionId.isBlank())
            return PriorEvidenceReuse.empty(revisionId,"GRAPH_REVISION_NOT_SELECTED");
        var found=graphs.find(projectId,revisionId);
        if(found.isEmpty())return PriorEvidenceReuse.empty(revisionId,"GRAPH_REVISION_NOT_FOUND");
        EvidenceGraph.Snapshot snapshot=found.get();
        if(!EvidenceGraphService.VERSION.equals(snapshot.revision().builderVersion()))
            return PriorEvidenceReuse.empty(revisionId,"GRAPH_BUILDER_VERSION_CHANGED");
        Set<String> currentManifest=new LinkedHashSet<>();
        for(Document document:manifest==null?List.<Document>of():manifest){
            if(document==null||!projectId.equals(document.projectId()))
                return PriorEvidenceReuse.empty(revisionId,"GRAPH_MANIFEST_PROJECT_MISMATCH");
            currentManifest.add(document.id()+"@"+document.version()+"@"+document.sha256().toLowerCase());
        }
        Set<String> storedManifest=snapshot.revision().documents().stream()
                .map(document->document.documentId()+"@"+document.version()+"@"+document.sha256().toLowerCase())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if(currentManifest.isEmpty()||!currentManifest.equals(storedManifest))
            return PriorEvidenceReuse.empty(revisionId,"GRAPH_MANIFEST_CHANGED");
        List<EvidenceGraph.Relation> eligible=snapshot.relations().stream()
                .filter(edge->edge.status()==EvidenceGraph.RelationStatus.SUPPORTED)
                .filter(edge->"CURRENT_PROJECT".equals(edge.scope())).toList();
        Set<String> occurrenceIds=eligible.stream().map(EvidenceGraph.Relation::sourceOccurrenceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String,Object>> revalidated=ledger.revalidateOccurrences(projectId,occurrenceIds,manifest).stream()
                .filter(row->{Object fact=row.get("fact");return fact instanceof Map<?,?> value
                        &&"CURRENT_PROJECT".equals(Objects.toString(value.get("scope"),""));})
                .toList();
        Map<String,Object> audit=Map.of("revisionId",revisionId,"status","REVALIDATED",
                "graphRelationCount",snapshot.relations().size(),"supportedCurrentRelationCount",eligible.size(),
                "selectedOccurrenceCount",occurrenceIds.size(),"acceptedOccurrenceCount",revalidated.size(),
                "candidateEdgesAccepted",0,"historicalEdgesAccepted",0,"unresolvedEdgesAccepted",0,
                "businessDecisionReused",false);
        return new PriorEvidenceReuse(revalidated,audit);
    }

    /** changedEvidenceNeedKeys can be passed directly to TemplateDependencyGraphService.plan(). */
    public record RefreshResult(EvidenceGraph.BuildResult graph,Set<String> changedEvidenceNeedKeys) {
        public RefreshResult {graph=Objects.requireNonNull(graph);changedEvidenceNeedKeys=changedEvidenceNeedKeys==null?Set.of():Set.copyOf(changedEvidenceNeedKeys);}
    }

    public record PriorEvidenceReuse(List<Map<String,Object>> occurrences,Map<String,Object> audit) {
        public PriorEvidenceReuse {occurrences=occurrences==null?List.of():List.copyOf(occurrences);audit=audit==null?Map.of():Map.copyOf(audit);}
        static PriorEvidenceReuse empty(String revisionId,String reason){return new PriorEvidenceReuse(List.of(),Map.of(
                "revisionId",Objects.toString(revisionId,""),"status","NOT_REUSED","reason",reason,
                "acceptedOccurrenceCount",0,"candidateEdgesAccepted",0,"historicalEdgesAccepted",0,
                "unresolvedEdgesAccepted",0,"businessDecisionReused",false));}
    }

    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof Collection<?> rows))return List.of();List<Map<String,Object>> out=new ArrayList<>();for(Object row:rows)if(row instanceof Map<?,?> map){Map<String,Object> copy=new LinkedHashMap<>();map.forEach((key,item)->copy.put(String.valueOf(key),item));out.add(copy);}return out;}
}

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
        for(String identity:ledgerIdentities==null?cn.bidcheck.compat.Java8Api.<String>listOf():ledgerIdentities){if(identity==null||cn.bidcheck.compat.Java8Api.isBlank(identity)||!engines.add(identity))continue;
            Map<String,Object> view=ledger.view(projectId,runId,identity,manifest);
            for(Map<String,Object> occurrence:maps(view.get("occurrences"))){String id=Objects.toString(occurrence.get("occurrenceId"),"");if(!cn.bidcheck.compat.Java8Api.isBlank(id))occurrences.putIfAbsent(id,occurrence);}
        }
        String graphIdentity="project-evidence|"+String.join("|",cn.bidcheck.compat.Java8Api.toList(engines.stream().sorted()));
        Map<String,Object> merged=new LinkedHashMap<>();merged.put("occurrences",new ArrayList<>(occurrences.values()));
        merged.put("sourceLedgerIdentities",cn.bidcheck.compat.Java8Api.toList(engines.stream().sorted()));
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
        if(projectId==null||cn.bidcheck.compat.Java8Api.isBlank(projectId)||revisionId==null||cn.bidcheck.compat.Java8Api.isBlank(revisionId))
            return PriorEvidenceReuse.empty(revisionId,"GRAPH_REVISION_NOT_SELECTED");
        java.util.Optional<cn.bidcheck.verification.graph.EvidenceGraph.Snapshot> found=graphs.find(projectId,revisionId);
        if((!found.isPresent()))return PriorEvidenceReuse.empty(revisionId,"GRAPH_REVISION_NOT_FOUND");
        EvidenceGraph.Snapshot snapshot=found.get();
        if(!EvidenceGraphService.VERSION.equals(snapshot.revision().builderVersion()))
            return PriorEvidenceReuse.empty(revisionId,"GRAPH_BUILDER_VERSION_CHANGED");
        Set<String> currentManifest=new LinkedHashSet<>();
        for(Document document:manifest==null?cn.bidcheck.compat.Java8Api.<Document>listOf():manifest){
            if(document==null||!projectId.equals(document.projectId()))
                return PriorEvidenceReuse.empty(revisionId,"GRAPH_MANIFEST_PROJECT_MISMATCH");
            currentManifest.add(document.id()+"@"+document.version()+"@"+document.sha256().toLowerCase());
        }
        Set<String> storedManifest=snapshot.revision().documents().stream()
                .map(document->document.documentId()+"@"+document.version()+"@"+document.sha256().toLowerCase())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if(currentManifest.isEmpty()||!currentManifest.equals(storedManifest))
            return PriorEvidenceReuse.empty(revisionId,"GRAPH_MANIFEST_CHANGED");
        List<EvidenceGraph.Relation> eligible=cn.bidcheck.compat.Java8Api.toList(snapshot.relations().stream()
                .filter(edge->edge.status()==EvidenceGraph.RelationStatus.SUPPORTED)
                .filter(edge->"CURRENT_PROJECT".equals(edge.scope())));
        Set<String> occurrenceIds=eligible.stream().map(EvidenceGraph.Relation::sourceOccurrenceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String,Object>> revalidated=cn.bidcheck.compat.Java8Api.toList(ledger.revalidateOccurrences(projectId,occurrenceIds,manifest).stream()
                .filter(row->{Object fact=row.get("fact");return fact instanceof Map<?,?>
                        &&"CURRENT_PROJECT".equals(Objects.toString(((Map<?,?>) (fact)).get("scope"),""));}));
        Map<String,Object> audit=cn.bidcheck.compat.Java8Api.mapOf("revisionId", revisionId, "status", "REVALIDATED", "graphRelationCount", snapshot.relations().size(), "supportedCurrentRelationCount", eligible.size(), "selectedOccurrenceCount", occurrenceIds.size(), "acceptedOccurrenceCount", revalidated.size(), "candidateEdgesAccepted", 0, "historicalEdgesAccepted", 0, "unresolvedEdgesAccepted", 0, "businessDecisionReused", false);
        return new PriorEvidenceReuse(revalidated,audit);
    }

    /** changedEvidenceNeedKeys can be passed directly to TemplateDependencyGraphService.plan(). */
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class RefreshResult {
        private final EvidenceGraph.BuildResult graph;
        private final Set<String> changedEvidenceNeedKeys;
        public EvidenceGraph.BuildResult graph() { return graph; }
        public Set<String> changedEvidenceNeedKeys() { return changedEvidenceNeedKeys; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RefreshResult that = (RefreshResult) other; return java.util.Objects.equals(this.graph, that.graph) && java.util.Objects.equals(this.changedEvidenceNeedKeys, that.changedEvidenceNeedKeys); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.graph); hash = 31 * hash + java.util.Objects.hashCode(this.changedEvidenceNeedKeys); return hash; }
        @Override public String toString() { return "RefreshResult[" + "graph=" + graph + ", changedEvidenceNeedKeys=" + changedEvidenceNeedKeys + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public RefreshResult(@com.fasterxml.jackson.annotation.JsonProperty("graph") EvidenceGraph.BuildResult graph, @com.fasterxml.jackson.annotation.JsonProperty("changedEvidenceNeedKeys") Set<String> changedEvidenceNeedKeys) {graph=Objects.requireNonNull(graph);changedEvidenceNeedKeys=changedEvidenceNeedKeys==null?cn.bidcheck.compat.Java8Api.setOf():cn.bidcheck.compat.Java8Api.setCopyOf(changedEvidenceNeedKeys);
            this.graph = graph; this.changedEvidenceNeedKeys = changedEvidenceNeedKeys;
        }
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class PriorEvidenceReuse {
        private final List<Map<String,Object>> occurrences;
        private final Map<String,Object> audit;
        public List<Map<String,Object>> occurrences() { return occurrences; }
        public Map<String,Object> audit() { return audit; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PriorEvidenceReuse that = (PriorEvidenceReuse) other; return java.util.Objects.equals(this.occurrences, that.occurrences) && java.util.Objects.equals(this.audit, that.audit); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.occurrences); hash = 31 * hash + java.util.Objects.hashCode(this.audit); return hash; }
        @Override public String toString() { return "PriorEvidenceReuse[" + "occurrences=" + occurrences + ", audit=" + audit + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public PriorEvidenceReuse(@com.fasterxml.jackson.annotation.JsonProperty("occurrences") List<Map<String,Object>> occurrences, @com.fasterxml.jackson.annotation.JsonProperty("audit") Map<String,Object> audit) {occurrences=occurrences==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(occurrences);audit=audit==null?cn.bidcheck.compat.Java8Api.mapOf():cn.bidcheck.compat.Java8Api.mapCopyOf(audit);
            this.occurrences = occurrences; this.audit = audit;
        }
        static PriorEvidenceReuse empty(String revisionId,String reason){return new PriorEvidenceReuse(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("revisionId", Objects.toString(revisionId,""), "status", "NOT_REUSED", "reason", reason, "acceptedOccurrenceCount", 0, "candidateEdgesAccepted", 0, "historicalEdgesAccepted", 0, "unresolvedEdgesAccepted", 0, "businessDecisionReused", false));}
    }

    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();List<Map<String,Object>> out=new ArrayList<>();for(Object row:((Collection<?>) (value)))if(row instanceof Map<?,?>){Map<String,Object> copy=new LinkedHashMap<>();((Map<?,?>) (row)).forEach((key,item)->copy.put(String.valueOf(key),item));out.add(copy);}return out;}
}

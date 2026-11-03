package cn.bidcheck.verification.graph;

import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Run-scoped, read-only access to an immutable evidence-graph revision.
 *
 * <p>The project and revision are deliberately resolved from the stored run.  Callers cannot
 * select either value and therefore cannot use this read API to cross a project or revision
 * boundary.</p>
 */
@Service
public final class EvidenceGraphReadService {
    private static final Set<String> CURRENT_SCOPE=cn.bidcheck.compat.Java8Api.setOf("CURRENT_PROJECT");
    private final Store store;
    private final EvidenceGraphService graphs;

    public EvidenceGraphReadService(Store store,EvidenceGraphService graphs) {
        this.store=Objects.requireNonNull(store);this.graphs=Objects.requireNonNull(graphs);
    }

    /** Returns source-supported current-project relations and only their endpoint entities. */
    public GraphView graph(String runId,Collection<String> predicateFilters) {
        ScopedGraph scoped=resolve(runId);Set<String> predicates=predicates(predicateFilters);
        List<EvidenceGraph.Relation> relations=cn.bidcheck.compat.Java8Api.toList(scoped.snapshot().relations().stream()
                .filter(relation->relation.status()==EvidenceGraph.RelationStatus.SUPPORTED)
                .filter(relation->"CURRENT_PROJECT".equals(relation.scope()))
                .filter(relation->predicates.isEmpty()||predicates.contains(relation.predicate()))
                .sorted(Comparator.comparing(EvidenceGraph.Relation::id)));
        Set<String> endpoints=new LinkedHashSet<>();
        relations.forEach(relation->{endpoints.add(relation.subjectEntityKey());endpoints.add(relation.objectEntityKey());});
        List<EvidenceGraph.Entity> entities=cn.bidcheck.compat.Java8Api.toList(scoped.snapshot().entities().stream()
                .filter(entity->endpoints.contains(entity.entityKey()))
                .sorted(Comparator.comparing(EvidenceGraph.Entity::entityKey)));
        return new GraphView(scoped.snapshot().revision(),entities,relations);
    }

    /** Finds a bounded directed path over source-supported current-project relations only. */
    public EvidenceGraph.ProofPath proof(String runId,String from,String to,
                                         Collection<String> predicateFilters,int maxDepth) {
        String subject=required(from,"from"),object=required(to,"to");
        if(maxDepth<1||maxDepth>6)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"maxDepth 必须在 1 到 6 之间");
        ScopedGraph scoped=resolve(runId);Set<String> predicates=predicates(predicateFilters);
        return graphs.proofPath(scoped.projectId(),scoped.snapshot().revision().id(),subject,object,
                predicates,CURRENT_SCOPE,maxDepth);
    }

    private ScopedGraph resolve(String runId) {
        String id=required(runId,"runId");Stored row;
        try{row=store.require("run",id);}catch(ResponseStatusException missing){
            if(missing.getStatus()!=HttpStatus.NOT_FOUND)throw missing;
            row=store.require("verification_run",id);
        }
        String projectId=requiredStored(row.projectId,"RUN_PROJECT_SCOPE_MISSING");
        Map<String,Object> run=store.map(row.body);String recordedProject=text(run.get("projectId"));
        if(!cn.bidcheck.compat.Java8Api.isBlank(recordedProject)&&!projectId.equals(recordedProject))
            throw new IllegalStateException("RUN_PROJECT_SCOPE_MISMATCH");
        Map<String,Object> selected=map(run.get("evidenceGraph"));String revisionId=text(selected.get("revisionId"));
        if(cn.bidcheck.compat.Java8Api.isBlank(revisionId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该检查尚未生成证据图");
        EvidenceGraph.Snapshot snapshot=graphs.find(projectId,revisionId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"该检查的证据图版本不存在"));
        if(!revisionId.equals(snapshot.revision().id())||!projectId.equals(snapshot.revision().projectId()))
            throw new IllegalStateException("GRAPH_RUN_SCOPE_MISMATCH");
        return new ScopedGraph(projectId,snapshot);
    }

    private static Set<String> predicates(Collection<String> filters) {
        LinkedHashSet<String> values=new LinkedHashSet<>();
        for(String filter:filters==null?cn.bidcheck.compat.Java8Api.<String>listOf():filters)if(filter!=null)
            for(String item:filter.split(",")){String value=item.trim().toUpperCase(Locale.ROOT);if(!cn.bidcheck.compat.Java8Api.isBlank(value))values.add(value);}
        if(!EvidenceGraph.PREDICATES.containsAll(values))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"predicate 包含不支持的关系类型");
        return cn.bidcheck.compat.Java8Api.setCopyOf(values);
    }

    private static String required(String value,String field) {
        if(value==null||cn.bidcheck.compat.Java8Api.isBlank(value))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,field+" 不能为空");
        return value.trim();
    }
    private static String requiredStored(String value,String code) {
        if(value==null||cn.bidcheck.compat.Java8Api.isBlank(value))throw new IllegalStateException(code);return value.trim();
    }
    private static String text(Object value){return value==null?"":String.valueOf(value).trim();}
    private static Map<String,Object> map(Object value){
        if(!(value instanceof Map<?,?>))return cn.bidcheck.compat.Java8Api.mapOf();
        java.util.LinkedHashMap<String,Object> copy=new java.util.LinkedHashMap<>();
        ((Map<?,?>) (value)).forEach((key,item)->copy.put(String.valueOf(key),item));return copy;
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class GraphView {
        private final EvidenceGraph.Revision revision;
        private final List<EvidenceGraph.Entity> entities;
        private final List<EvidenceGraph.Relation> relations;
        public EvidenceGraph.Revision revision() { return revision; }
        public List<EvidenceGraph.Entity> entities() { return entities; }
        public List<EvidenceGraph.Relation> relations() { return relations; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; GraphView that = (GraphView) other; return java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.entities, that.entities) && java.util.Objects.equals(this.relations, that.relations); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.entities); hash = 31 * hash + java.util.Objects.hashCode(this.relations); return hash; }
        @Override public String toString() { return "GraphView[" + "revision=" + revision + ", entities=" + entities + ", relations=" + relations + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public GraphView(@com.fasterxml.jackson.annotation.JsonProperty("revision") EvidenceGraph.Revision revision, @com.fasterxml.jackson.annotation.JsonProperty("entities") List<EvidenceGraph.Entity> entities, @com.fasterxml.jackson.annotation.JsonProperty("relations") List<EvidenceGraph.Relation> relations) {
            revision=Objects.requireNonNull(revision);entities=entities==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(entities);
            relations=relations==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(relations);

            this.revision = revision; this.entities = entities; this.relations = relations;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ScopedGraph {
        private final String projectId;
        private final EvidenceGraph.Snapshot snapshot;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ScopedGraph(@com.fasterxml.jackson.annotation.JsonProperty("projectId") String projectId, @com.fasterxml.jackson.annotation.JsonProperty("snapshot") EvidenceGraph.Snapshot snapshot) { this.projectId = projectId; this.snapshot = snapshot; }
        public String projectId() { return projectId; }
        public EvidenceGraph.Snapshot snapshot() { return snapshot; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ScopedGraph that = (ScopedGraph) other; return java.util.Objects.equals(this.projectId, that.projectId) && java.util.Objects.equals(this.snapshot, that.snapshot); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.projectId); hash = 31 * hash + java.util.Objects.hashCode(this.snapshot); return hash; }
        @Override public String toString() { return "ScopedGraph[" + "projectId=" + projectId + ", snapshot=" + snapshot + "]"; }
}
}

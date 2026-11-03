package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.incremental.TemplateDependencyGraph.Edge;
import cn.bidcheck.checktemplate.incremental.TemplateDependencyGraph.Fragment;
import cn.bidcheck.checktemplate.incremental.TemplateDependencyGraph.Node;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.Stored;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import cn.bidcheck.compat.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists a run's reverse dependency index and produces bounded incremental work plans. */
@Service
public class TemplateDependencyGraphService {
    public static final String SCHEMA="template-dependency-snapshot-v1";
    private final Store store;private final ObjectMapper json;private final TransactionTemplate tx;
    public TemplateDependencyGraphService(Store store,ObjectMapper json,PlatformTransactionManager manager){this.store=store;this.json=json;this.tx=new TransactionTemplate(manager);}

    public Fragment fragment(cn.bidcheck.checktemplate.TemplateExecution execution){return TemplateDependencyGraph.from(execution);}

    public Map<String,Object> publish(String projectId,String runId,Collection<Fragment> fragments){return publish(projectId,runId,fragments,"");}

    /**
     * Publishes the graph together with the exact template finding snapshot that produced it.
     * The source digest lets a later incremental request detect an out-of-band historical result
     * edit even when the dependency graph revision itself was not changed.
     */
    public Map<String,Object> publish(String projectId,String runId,Collection<Fragment> fragments,String sourceResultDigest){
        Objects.requireNonNull(fragments,"fragments");return tx.execute(ignored->{store.lock("project",projectId);String id=id(runId);Stored previous=store.mapper().get(id);
            int revision=previous==null?0:number(store.map(previous.body).get("revision"),0);Map<String,Fragment> byCheckpoint=new LinkedHashMap<>();
            fragments.stream().filter(Objects::nonNull).sorted(Comparator.comparing(Fragment::checkpointId)).forEach(fragment->byCheckpoint.put(fragment.checkpointId(),fragment));
            List<Node> nodes=cn.bidcheck.compat.Java8Api.toList(byCheckpoint.values().stream().flatMap(fragment->fragment.nodes().stream()).collect(java.util.stream.Collectors.toMap(Node::id,node->node,(left,right)->left,LinkedHashMap::new)).values().stream());
            List<Edge> edges=cn.bidcheck.compat.Java8Api.toList(byCheckpoint.values().stream().flatMap(fragment->fragment.edges().stream()).distinct().sorted(Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparing(edge->edge.type().name())));
            Map<String,List<String>> reverse=new LinkedHashMap<>();for(Fragment fragment:byCheckpoint.values())for(String key:fragment.evidenceNeedKeys())reverse.computeIfAbsent(key,unused->new ArrayList<>()).add(fragment.checkpointId());
            reverse.replaceAll((key,values)->cn.bidcheck.compat.Java8Api.toList(values.stream().distinct().sorted()));Map<String,Object> graph=new LinkedHashMap<>();graph.put("schemaVersion",SCHEMA);graph.put("id",id);graph.put("projectId",projectId);graph.put("runId",runId);graph.put("revision",revision+1);
            graph.put("fragments",byCheckpoint.values());graph.put("nodes",nodes);graph.put("edges",edges);graph.put("reverseNeedIndex",reverse);graph.put("sourceResultDigest",sourceResultDigest==null?"":sourceResultDigest.trim());graph.put("updatedAt",Instant.now().toString());graph.put("contentDigest",digest(canonical(graph)));
            store.save("template_dependency_graph",id,projectId,runId,graph);return graph;});
    }

    public Map<String,Object> get(String runId){Stored row=store.mapper().get(id(runId));if(row==null||!"template_dependency_graph".equals(row.kind)||!runId.equals(row.parentId))return cn.bidcheck.compat.Java8Api.mapOf();return store.map(row.body);}

    public RecalculationPlan plan(String runId,long expectedRevision,Collection<String> changedNeedKeys){Map<String,Object> graph=get(runId);if(graph.isEmpty())throw new IllegalStateException("DEPENDENCY_GRAPH_MISSING");long actual=number(graph.get("revision"),0);
        if(actual!=expectedRevision)throw new IllegalStateException("DEPENDENCY_GRAPH_REVISION_CHANGED:"+actual);Map<String,Object> reverse=map(graph.get("reverseNeedIndex"));Set<String> changed=new LinkedHashSet<>(),checkpoints=new LinkedHashSet<>();
        for(String key:changedNeedKeys==null?cn.bidcheck.compat.Java8Api.<String>listOf():changedNeedKeys)if(key!=null&&!cn.bidcheck.compat.Java8Api.isBlank(key)){changed.add(key);Object values=reverse.get(key);if(values instanceof Collection<?>)for(Object value:((Collection<?>) (values)))if(value!=null)checkpoints.add(value.toString());}
        return new RecalculationPlan(runId,actual,cn.bidcheck.compat.Java8Api.setCopyOf(changed),cn.bidcheck.compat.Java8Api.setCopyOf(checkpoints),checkpoints.isEmpty());}

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class RecalculationPlan{
        private final String runId;
        private final long graphRevision;
        private final Set<String> changedNeedKeys;
        private final Set<String> checkpointIds;
        private final boolean noOp;
        public String runId() { return runId; }
        public long graphRevision() { return graphRevision; }
        public Set<String> changedNeedKeys() { return changedNeedKeys; }
        public Set<String> checkpointIds() { return checkpointIds; }
        public boolean noOp() { return noOp; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; RecalculationPlan that = (RecalculationPlan) other; return java.util.Objects.equals(this.runId, that.runId) && java.util.Objects.equals(this.graphRevision, that.graphRevision) && java.util.Objects.equals(this.changedNeedKeys, that.changedNeedKeys) && java.util.Objects.equals(this.checkpointIds, that.checkpointIds) && java.util.Objects.equals(this.noOp, that.noOp); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.runId); hash = 31 * hash + java.util.Objects.hashCode(this.graphRevision); hash = 31 * hash + java.util.Objects.hashCode(this.changedNeedKeys); hash = 31 * hash + java.util.Objects.hashCode(this.checkpointIds); hash = 31 * hash + java.util.Objects.hashCode(this.noOp); return hash; }
        @Override public String toString() { return "RecalculationPlan[" + "runId=" + runId + ", graphRevision=" + graphRevision + ", changedNeedKeys=" + changedNeedKeys + ", checkpointIds=" + checkpointIds + ", noOp=" + noOp + "]"; }
@com.fasterxml.jackson.annotation.JsonCreator
        public RecalculationPlan(@com.fasterxml.jackson.annotation.JsonProperty("runId") String runId, @com.fasterxml.jackson.annotation.JsonProperty("graphRevision") long graphRevision, @com.fasterxml.jackson.annotation.JsonProperty("changedNeedKeys") Set<String> changedNeedKeys, @com.fasterxml.jackson.annotation.JsonProperty("checkpointIds") Set<String> checkpointIds, @com.fasterxml.jackson.annotation.JsonProperty("noOp") boolean noOp) {changedNeedKeys=cn.bidcheck.compat.Java8Api.setCopyOf(changedNeedKeys);checkpointIds=cn.bidcheck.compat.Java8Api.setCopyOf(checkpointIds);
            this.runId = runId; this.graphRevision = graphRevision; this.changedNeedKeys = changedNeedKeys; this.checkpointIds = checkpointIds; this.noOp = noOp;
        }}
    private static String id(String run){return "tdg:"+run;}
    private String canonical(Object value){try{return json.writeValueAsString(sort(json.valueToTree(value)));}catch(JsonProcessingException failure){throw new IllegalArgumentException("依赖图无法序列化",failure);}}
    private JsonNode sort(JsonNode node){if(node.isObject()){ObjectNode out=json.createObjectNode();List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)out.set(name,sort(node.get(name)));return out;}if(node.isArray()){ArrayNode out=json.createArrayNode();node.forEach(value->out.add(sort(value)));return out;}return node;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)((Map<?,?>) (value)):cn.bidcheck.compat.Java8Api.mapOf();}
    private static int number(Object value,int fallback){return value instanceof Number?((Number) (value)).intValue():fallback;}
}

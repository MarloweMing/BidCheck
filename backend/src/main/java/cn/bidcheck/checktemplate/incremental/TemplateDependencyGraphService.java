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
import java.util.HexFormat;
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
            List<Node> nodes=byCheckpoint.values().stream().flatMap(fragment->fragment.nodes().stream()).collect(java.util.stream.Collectors.toMap(Node::id,node->node,(left,right)->left,LinkedHashMap::new)).values().stream().toList();
            List<Edge> edges=byCheckpoint.values().stream().flatMap(fragment->fragment.edges().stream()).distinct().sorted(Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparing(edge->edge.type().name())).toList();
            Map<String,List<String>> reverse=new LinkedHashMap<>();for(Fragment fragment:byCheckpoint.values())for(String key:fragment.evidenceNeedKeys())reverse.computeIfAbsent(key,unused->new ArrayList<>()).add(fragment.checkpointId());
            reverse.replaceAll((key,values)->values.stream().distinct().sorted().toList());Map<String,Object> graph=new LinkedHashMap<>();graph.put("schemaVersion",SCHEMA);graph.put("id",id);graph.put("projectId",projectId);graph.put("runId",runId);graph.put("revision",revision+1);
            graph.put("fragments",byCheckpoint.values());graph.put("nodes",nodes);graph.put("edges",edges);graph.put("reverseNeedIndex",reverse);graph.put("sourceResultDigest",sourceResultDigest==null?"":sourceResultDigest.trim());graph.put("updatedAt",Instant.now().toString());graph.put("contentDigest",digest(canonical(graph)));
            store.save("template_dependency_graph",id,projectId,runId,graph);return graph;});
    }

    public Map<String,Object> get(String runId){Stored row=store.mapper().get(id(runId));if(row==null||!"template_dependency_graph".equals(row.kind)||!runId.equals(row.parentId))return Map.of();return store.map(row.body);}

    public RecalculationPlan plan(String runId,long expectedRevision,Collection<String> changedNeedKeys){Map<String,Object> graph=get(runId);if(graph.isEmpty())throw new IllegalStateException("DEPENDENCY_GRAPH_MISSING");long actual=number(graph.get("revision"),0);
        if(actual!=expectedRevision)throw new IllegalStateException("DEPENDENCY_GRAPH_REVISION_CHANGED:"+actual);Map<String,Object> reverse=map(graph.get("reverseNeedIndex"));Set<String> changed=new LinkedHashSet<>(),checkpoints=new LinkedHashSet<>();
        for(String key:changedNeedKeys==null?List.<String>of():changedNeedKeys)if(key!=null&&!key.isBlank()){changed.add(key);Object values=reverse.get(key);if(values instanceof Collection<?> collection)for(Object value:collection)if(value!=null)checkpoints.add(value.toString());}
        return new RecalculationPlan(runId,actual,Set.copyOf(changed),Set.copyOf(checkpoints),checkpoints.isEmpty());}

    public record RecalculationPlan(String runId,long graphRevision,Set<String> changedNeedKeys,Set<String> checkpointIds,boolean noOp){public RecalculationPlan{changedNeedKeys=Set.copyOf(changedNeedKeys);checkpointIds=Set.copyOf(checkpointIds);}}
    private static String id(String run){return "tdg:"+run;}
    private String canonical(Object value){try{return json.writeValueAsString(sort(json.valueToTree(value)));}catch(JsonProcessingException failure){throw new IllegalArgumentException("依赖图无法序列化",failure);}}
    private JsonNode sort(JsonNode node){if(node.isObject()){ObjectNode out=json.createObjectNode();List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)out.set(name,sort(node.get(name)));return out;}if(node.isArray()){ArrayNode out=json.createArrayNode();node.forEach(value->out.add(sort(value)));return out;}return node;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();}
    private static int number(Object value,int fallback){return value instanceof Number number?number.intValue():fallback;}
}

package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.ConditionResult;
import cn.bidcheck.checktemplate.EvidenceNeed;
import cn.bidcheck.checktemplate.TemplateExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A source-independent dependency fragment emitted by one deterministic template execution. */
public final class TemplateDependencyGraph {
    public static final String VERSION="template-dependency-graph-1";
    private TemplateDependencyGraph() {}

    public enum NodeType { CHECK_SPEC, CONDITION, EVIDENCE_NEED, RESULT, BUSINESS_ITEM }
    public enum EdgeType { REQUIRES, CONTRIBUTES_TO, PUBLISHES }

    public record Node(String id,NodeType type,String checkpointId,Map<String,Object> attributes) {
        public Node {id=required(id,"id");type=Objects.requireNonNull(type,"type");checkpointId=text(checkpointId);attributes=attributes==null?Map.of():Map.copyOf(attributes);}
    }
    public record Edge(String from,String to,EdgeType type) {
        public Edge {from=required(from,"from");to=required(to,"to");type=Objects.requireNonNull(type,"type");}
    }
    public record Fragment(String checkpointId,String specId,int specRevision,String businessItemId,
                           List<Node> nodes,List<Edge> edges,Set<String> evidenceNeedKeys,String evaluatorVersion) {
        public Fragment {checkpointId=required(checkpointId,"checkpointId");specId=required(specId,"specId");businessItemId=required(businessItemId,"businessItemId");
            nodes=nodes==null?List.of():List.copyOf(nodes);edges=edges==null?List.of():List.copyOf(edges);
            evidenceNeedKeys=evidenceNeedKeys==null?Set.of():Set.copyOf(evidenceNeedKeys);evaluatorVersion=text(evaluatorVersion);}
    }

    public static Fragment from(TemplateExecution execution) {
        Objects.requireNonNull(execution,"execution");Objects.requireNonNull(execution.spec(),"spec");Objects.requireNonNull(execution.result(),"result");
        String checkpoint=execution.spec().checkpointId(),specNode="spec:"+execution.spec().id()+"@"+execution.spec().revision();
        String resultNode="result:"+checkpoint,businessNode="business:"+execution.spec().businessItemId();
        Map<String,Node> nodes=new LinkedHashMap<>();List<Edge> edges=new ArrayList<>();Set<String> needs=new LinkedHashSet<>();
        nodes.put(specNode,new Node(specNode,NodeType.CHECK_SPEC,checkpoint,Map.of("templateId",execution.spec().templateId(),"templateVersion",execution.spec().templateVersion(),"semanticSignature",execution.spec().semanticSignature())));
        nodes.put(resultNode,new Node(resultNode,NodeType.RESULT,checkpoint,Map.of("reasonCode",execution.result().reasonCode(),"conclusion",execution.result().conclusion().name())));
        nodes.put(businessNode,new Node(businessNode,NodeType.BUSINESS_ITEM,checkpoint,Map.of()));
        edges.add(new Edge(specNode,resultNode,EdgeType.CONTRIBUTES_TO));edges.add(new Edge(resultNode,businessNode,EdgeType.PUBLISHES));
        Map<String,String> conditionNodes=new LinkedHashMap<>();
        for(ConditionResult condition:execution.result().conditions()) {String id="condition:"+checkpoint+":"+condition.id();conditionNodes.put(condition.id(),id);
            nodes.put(id,new Node(id,NodeType.CONDITION,checkpoint,Map.of("conditionId",condition.id(),"state",condition.state().name(),"reasonCode",condition.reasonCode())));
            edges.add(new Edge(id,resultNode,EdgeType.CONTRIBUTES_TO));}
        for(EvidenceNeed need:execution.evidenceNeeds()) {String id="need:"+need.key();needs.add(need.key());
            nodes.putIfAbsent(id,new Node(id,NodeType.EVIDENCE_NEED,checkpoint,Map.of("needKey",need.key(),"kind",need.kind().name(),"required",need.required(),"scopeClosureRequired",need.scopeClosureRequired())));
            if(conditionNodes.isEmpty())edges.add(new Edge(id,resultNode,EdgeType.REQUIRES));
            else conditionNodes.values().forEach(condition->edges.add(new Edge(id,condition,EdgeType.REQUIRES)));}
        return new Fragment(checkpoint,execution.spec().id(),execution.spec().revision(),execution.spec().businessItemId(),List.copyOf(nodes.values()),List.copyOf(edges),needs,execution.projection().evaluatorVersion());
    }

    private static String required(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException(field+" is required");return value.trim();}
    private static String text(String value){return value==null?"":value.trim();}
}

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

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Node {
        private final String id;
        private final NodeType type;
        private final String checkpointId;
        private final Map<String,Object> attributes;
        public String id() { return id; }
        public NodeType type() { return type; }
        public String checkpointId() { return checkpointId; }
        public Map<String,Object> attributes() { return attributes; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Node that = (Node) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.checkpointId, that.checkpointId) && java.util.Objects.equals(this.attributes, that.attributes); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.checkpointId); hash = 31 * hash + java.util.Objects.hashCode(this.attributes); return hash; }
        @Override public String toString() { return "Node[" + "id=" + id + ", type=" + type + ", checkpointId=" + checkpointId + ", attributes=" + attributes + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Node(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("type") NodeType type, @com.fasterxml.jackson.annotation.JsonProperty("checkpointId") String checkpointId, @com.fasterxml.jackson.annotation.JsonProperty("attributes") Map<String,Object> attributes) {id=required(id,"id");type=Objects.requireNonNull(type,"type");checkpointId=text(checkpointId);attributes=attributes==null?cn.bidcheck.compat.Java8Api.mapOf():cn.bidcheck.compat.Java8Api.mapCopyOf(attributes);
            this.id = id; this.type = type; this.checkpointId = checkpointId; this.attributes = attributes;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Edge {
        private final String from;
        private final String to;
        private final EdgeType type;
        public String from() { return from; }
        public String to() { return to; }
        public EdgeType type() { return type; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Edge that = (Edge) other; return java.util.Objects.equals(this.from, that.from) && java.util.Objects.equals(this.to, that.to) && java.util.Objects.equals(this.type, that.type); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.from); hash = 31 * hash + java.util.Objects.hashCode(this.to); hash = 31 * hash + java.util.Objects.hashCode(this.type); return hash; }
        @Override public String toString() { return "Edge[" + "from=" + from + ", to=" + to + ", type=" + type + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Edge(@com.fasterxml.jackson.annotation.JsonProperty("from") String from, @com.fasterxml.jackson.annotation.JsonProperty("to") String to, @com.fasterxml.jackson.annotation.JsonProperty("type") EdgeType type) {from=required(from,"from");to=required(to,"to");type=Objects.requireNonNull(type,"type");
            this.from = from; this.to = to; this.type = type;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Fragment {
        private final String checkpointId;
        private final String specId;
        private final int specRevision;
        private final String businessItemId;
        private final List<Node> nodes;
        private final List<Edge> edges;
        private final Set<String> evidenceNeedKeys;
        private final String evaluatorVersion;
        public String checkpointId() { return checkpointId; }
        public String specId() { return specId; }
        public int specRevision() { return specRevision; }
        public String businessItemId() { return businessItemId; }
        public List<Node> nodes() { return nodes; }
        public List<Edge> edges() { return edges; }
        public Set<String> evidenceNeedKeys() { return evidenceNeedKeys; }
        public String evaluatorVersion() { return evaluatorVersion; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fragment that = (Fragment) other; return java.util.Objects.equals(this.checkpointId, that.checkpointId) && java.util.Objects.equals(this.specId, that.specId) && java.util.Objects.equals(this.specRevision, that.specRevision) && java.util.Objects.equals(this.businessItemId, that.businessItemId) && java.util.Objects.equals(this.nodes, that.nodes) && java.util.Objects.equals(this.edges, that.edges) && java.util.Objects.equals(this.evidenceNeedKeys, that.evidenceNeedKeys) && java.util.Objects.equals(this.evaluatorVersion, that.evaluatorVersion); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.checkpointId); hash = 31 * hash + java.util.Objects.hashCode(this.specId); hash = 31 * hash + java.util.Objects.hashCode(this.specRevision); hash = 31 * hash + java.util.Objects.hashCode(this.businessItemId); hash = 31 * hash + java.util.Objects.hashCode(this.nodes); hash = 31 * hash + java.util.Objects.hashCode(this.edges); hash = 31 * hash + java.util.Objects.hashCode(this.evidenceNeedKeys); hash = 31 * hash + java.util.Objects.hashCode(this.evaluatorVersion); return hash; }
        @Override public String toString() { return "Fragment[" + "checkpointId=" + checkpointId + ", specId=" + specId + ", specRevision=" + specRevision + ", businessItemId=" + businessItemId + ", nodes=" + nodes + ", edges=" + edges + ", evidenceNeedKeys=" + evidenceNeedKeys + ", evaluatorVersion=" + evaluatorVersion + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Fragment(@com.fasterxml.jackson.annotation.JsonProperty("checkpointId") String checkpointId, @com.fasterxml.jackson.annotation.JsonProperty("specId") String specId, @com.fasterxml.jackson.annotation.JsonProperty("specRevision") int specRevision, @com.fasterxml.jackson.annotation.JsonProperty("businessItemId") String businessItemId, @com.fasterxml.jackson.annotation.JsonProperty("nodes") List<Node> nodes, @com.fasterxml.jackson.annotation.JsonProperty("edges") List<Edge> edges, @com.fasterxml.jackson.annotation.JsonProperty("evidenceNeedKeys") Set<String> evidenceNeedKeys, @com.fasterxml.jackson.annotation.JsonProperty("evaluatorVersion") String evaluatorVersion) {checkpointId=required(checkpointId,"checkpointId");specId=required(specId,"specId");businessItemId=required(businessItemId,"businessItemId");
            nodes=nodes==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(nodes);edges=edges==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(edges);
            evidenceNeedKeys=evidenceNeedKeys==null?cn.bidcheck.compat.Java8Api.setOf():cn.bidcheck.compat.Java8Api.setCopyOf(evidenceNeedKeys);evaluatorVersion=text(evaluatorVersion);
            this.checkpointId = checkpointId; this.specId = specId; this.specRevision = specRevision; this.businessItemId = businessItemId; this.nodes = nodes; this.edges = edges; this.evidenceNeedKeys = evidenceNeedKeys; this.evaluatorVersion = evaluatorVersion;
        }
    }

    public static Fragment from(TemplateExecution execution) {
        Objects.requireNonNull(execution,"execution");Objects.requireNonNull(execution.spec(),"spec");Objects.requireNonNull(execution.result(),"result");
        String checkpoint=execution.spec().checkpointId(),specNode="spec:"+execution.spec().id()+"@"+execution.spec().revision();
        String resultNode="result:"+checkpoint,businessNode="business:"+execution.spec().businessItemId();
        Map<String,Node> nodes=new LinkedHashMap<>();List<Edge> edges=new ArrayList<>();Set<String> needs=new LinkedHashSet<>();
        nodes.put(specNode,new Node(specNode,NodeType.CHECK_SPEC,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("templateId", execution.spec().templateId(), "templateVersion", execution.spec().templateVersion(), "semanticSignature", execution.spec().semanticSignature())));
        nodes.put(resultNode,new Node(resultNode,NodeType.RESULT,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("reasonCode", execution.result().reasonCode(), "conclusion", execution.result().conclusion().name())));
        nodes.put(businessNode,new Node(businessNode,NodeType.BUSINESS_ITEM,checkpoint,cn.bidcheck.compat.Java8Api.mapOf()));
        edges.add(new Edge(specNode,resultNode,EdgeType.CONTRIBUTES_TO));edges.add(new Edge(resultNode,businessNode,EdgeType.PUBLISHES));
        Map<String,String> conditionNodes=new LinkedHashMap<>();
        for(ConditionResult condition:execution.result().conditions()) {String id="condition:"+checkpoint+":"+condition.id();conditionNodes.put(condition.id(),id);
            nodes.put(id,new Node(id,NodeType.CONDITION,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("conditionId", condition.id(), "state", condition.state().name(), "reasonCode", condition.reasonCode())));
            edges.add(new Edge(id,resultNode,EdgeType.CONTRIBUTES_TO));}
        for(EvidenceNeed need:execution.evidenceNeeds()) {String id="need:"+need.key();needs.add(need.key());
            nodes.putIfAbsent(id,new Node(id,NodeType.EVIDENCE_NEED,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("needKey", need.key(), "kind", need.kind().name(), "required", need.required(), "scopeClosureRequired", need.scopeClosureRequired())));
            if(conditionNodes.isEmpty())edges.add(new Edge(id,resultNode,EdgeType.REQUIRES));
            else conditionNodes.values().forEach(condition->edges.add(new Edge(id,condition,EdgeType.REQUIRES)));}
        return new Fragment(checkpoint,execution.spec().id(),execution.spec().revision(),execution.spec().businessItemId(),cn.bidcheck.compat.Java8Api.listCopyOf(nodes.values()),cn.bidcheck.compat.Java8Api.listCopyOf(edges),needs,execution.projection().evaluatorVersion());
    }

    private static String required(String value,String field){if(value==null||cn.bidcheck.compat.Java8Api.isBlank(value))throw new IllegalArgumentException(field+" is required");return value.trim();}
    private static String text(String value){return value==null?"":value.trim();}
}

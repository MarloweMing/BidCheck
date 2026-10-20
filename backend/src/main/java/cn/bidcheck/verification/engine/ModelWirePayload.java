package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.util.*;

/** Lossless transport projection only. Server rules and source verification never use model-created references. */
final class ModelWirePayload {
    static final String VERSION="verification-wire-1-shared-source-context";
    private static final String REF="$sourceContextRef";
    private static final ObjectMapper JSON=new ObjectMapper();
    private ModelWirePayload(){}

    static String serialize(String operation,Map<String,Object> input)throws IOException {
        return JSON.writeValueAsString(encode(operation,input));
    }
    static ObjectNode encode(String operation,Map<String,Object> input) {
        ObjectNode envelope=JSON.createObjectNode();envelope.put("operation",operation);JsonNode original=JSON.valueToTree(input);
        rejectReservedReference(original);
        Map<String,JsonNode> contexts=new TreeMap<>();Map<String,Integer> counts=new HashMap<>();collect(original,contexts,counts);
        Set<String> repeated=new HashSet<>();counts.forEach((id,count)->{if(count>1)repeated.add(id);});
        envelope.set("input",project(original,repeated));
        if(!repeated.isEmpty()){
            ObjectNode wire=envelope.putObject("wireFormat");wire.put("version",VERSION);
            wire.put("instructions","$sourceContextRef 是服务器对完全相同的招标来源上下文所作的无损引用，按 sourceContexts 中对应键展开后理解原有规则；每个上下文的文档身份、版本、位置与原文均须保留。字典只作招标要求来源，不是投标证据；EXTRACT 事实仍只能引用 input.sources 的实际 sourceId。按现有响应 schema 返回完整业务规则/事实，无需回传服务器来源上下文或校验元数据，不得输出引用对象、改换来源或省略义务。");
            ObjectNode dictionary=wire.putObject("sourceContexts");contexts.forEach((id,value)->{if(repeated.contains(id))dictionary.set(id,value.deepCopy());});
        }
        return envelope;
    }
    private static void rejectReservedReference(JsonNode value){
        if(value.isObject()&&value.has(REF))throw new IllegalArgumentException("WIRE_RESERVED_REFERENCE_IN_INPUT");
        if(value.isContainerNode())value.elements().forEachRemaining(ModelWirePayload::rejectReservedReference);
    }
    private static boolean sourceContext(JsonNode value){return value.isObject()&&RequirementSourceContext.VERSION.equals(value.path("version").asText())&&value.path("identity").isObject()&&value.path("sources").isArray();}
    private static void collect(JsonNode value,Map<String,JsonNode> contexts,Map<String,Integer> counts){
        if(sourceContext(value)){String id=id(value);JsonNode old=contexts.putIfAbsent(id,value);if(old!=null&&!old.equals(value))throw new IllegalArgumentException("WIRE_CONTEXT_ID_COLLISION");counts.merge(id,1,Integer::sum);return;}
        if(value.isContainerNode())value.elements().forEachRemaining(child->collect(child,contexts,counts));
    }
    private static JsonNode project(JsonNode value,Set<String> repeated){
        if(sourceContext(value)){String id=id(value);if(repeated.contains(id))return JSON.createObjectNode().put(REF,id);return value.deepCopy();}
        if(value.isObject()){ObjectNode out=JSON.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),project(e.getValue(),repeated)));return out;}
        if(value.isArray()){ArrayNode out=JSON.createArrayNode();value.forEach(v->out.add(project(v,repeated)));return out;}
        return value.deepCopy();
    }
    private static String id(JsonNode value){return "SC_"+VerificationTypes.identity(canonical(value).toString());}
    private static JsonNode canonical(JsonNode value){
        if(value.isObject()){ObjectNode out=JSON.createObjectNode();List<String> keys=new ArrayList<>();value.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);keys.forEach(k->out.set(k,canonical(value.get(k))));return out;}
        if(value.isArray()){ArrayNode out=JSON.createArrayNode();value.forEach(v->out.add(canonical(v)));return out;}
        return value;
    }
    /** Independent round-trip check for transport tests/audit. Never applied to a provider response. */
    static ObjectNode restore(ObjectNode envelope){
        JsonNode wire=envelope.path("wireFormat");if(wire.isMissingNode())return envelope.deepCopy();
        if(!VERSION.equals(wire.path("version").asText())||!wire.path("sourceContexts").isObject())throw new IllegalArgumentException("WIRE_CONTEXT_PROTOCOL_INVALID");
        JsonNode dictionary=wire.path("sourceContexts");dictionary.fields().forEachRemaining(e->{if(!sourceContext(e.getValue())||!id(e.getValue()).equals(e.getKey()))throw new IllegalArgumentException("WIRE_CONTEXT_INTEGRITY_INVALID");});
        ObjectNode restored=JSON.createObjectNode();restored.set("operation",envelope.path("operation").deepCopy());restored.set("input",restoreNode(envelope.path("input"),dictionary));return restored;
    }
    private static JsonNode restoreNode(JsonNode value,JsonNode dictionary){
        if(value.isObject()&&value.size()==1&&value.has(REF)){JsonNode target=dictionary.get(value.path(REF).asText());if(target==null)throw new IllegalArgumentException("WIRE_CONTEXT_REFERENCE_MISSING");return target.deepCopy();}
        if(value.isObject()){ObjectNode out=JSON.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),restoreNode(e.getValue(),dictionary)));return out;}
        if(value.isArray()){ArrayNode out=JSON.createArrayNode();value.forEach(v->out.add(restoreNode(v,dictionary)));return out;}
        return value.deepCopy();
    }
}

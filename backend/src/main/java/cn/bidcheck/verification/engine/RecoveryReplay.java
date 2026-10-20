package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Replays observations, never previous PASS decisions, without a paid model request. */
final class RecoveryReplay {
    private RecoveryReplay() {}
    static boolean localOnly(Map<String,Object> context){return Boolean.FALSE.equals(context.get("recoveryModelAllowed"))&&Set.of("RECOMPUTE_ONLY","REPAIR_REQUIREMENT").contains(str(context,"recoveryMode"));}
    static JsonNode plan(Map<String,Object> context,ObjectMapper json){
        Map<String,Object> previous=map(context.get("previousAtomicCheck"));Object value=previous.get("plan");if(value==null)return null;
        JsonNode tree=json.valueToTree(value);if(!tree.path("rules").isArray())return null;
        restore(tree);return tree;
    }
    private static void restore(JsonNode node){
        if(node.isObject()&&"UNSUPPORTED".equals(node.path("predicate").asText())){
            String predicate=node.path("parameters").path("originalPredicate").asText();
            if(PREDICATES.contains(predicate)&&!"UNSUPPORTED".equals(predicate))((ObjectNode)node).put("predicate",predicate);
        }
        for(String key:List.of("rules","children"))if(node.path(key).isArray())node.path(key).forEach(RecoveryReplay::restore);
    }
    static List<Source> sources(Map<String,Object> context,EvidenceCorpus corpus,String modelIdentity){
        Set<String> ids=new LinkedHashSet<>();for(Map<String,Object> step:steps(context,modelIdentity)){
            Map<String,Object> output=map(step.get("output"));ids.addAll(ProofVerifier.strings(output.get("sourceIds")));
            for(Map<String,Object> fact:RuleEvaluator.maps(output.get("facts")))ids.addAll(ProofVerifier.strings(fact.get("sourceIds")));
        }
        // Native-only proofs have read windows but no FACT_VALIDATION packet. Their exact frozen
        // source ids authorize rereading the original text, never reusing the former decision.
        for(Map<String,Object> step:RuleEvaluator.maps(context.get("priorSteps")))if(str(step,"type").startsWith("EVIDENCE_PLAN_")&&current(step,modelIdentity))
            for(Map<String,Object> candidate:RuleEvaluator.maps(map(step.get("output")).get("candidates"))){String id=str(candidate,"id");if(!id.isBlank())ids.add(id);}
        for(Source source:corpus.all)if(source.id().startsWith("V"))ids.add(source.id());
        return ids.stream().map(corpus.byId::get).filter(Objects::nonNull).toList();
    }
    static ProofVerifier.Verified facts(Map<String,Object> context,List<Source> sources,List<Map<String,Object>> layout,String modelIdentity){
        ObjectMapper json=new ObjectMapper();Map<String,Fact> accepted=new LinkedHashMap<>();List<Map<String,Object>> rejected=new ArrayList<>();
        Set<String> available=new HashSet<>(sources.stream().map(Source::id).toList());
        for(Map<String,Object> step:steps(context,modelIdentity)){
            Map<String,Object> output=map(step.get("output"));List<String> refs=ProofVerifier.strings(output.get("sourceIds"));
            if(refs.isEmpty()||!available.containsAll(refs))continue;
            List<Source> sent=sources.stream().filter(s->refs.contains(s.id())).toList();
            ProofVerifier.Verified verified=new ProofVerifier().verify(json.valueToTree(Map.of("facts",RuleEvaluator.maps(output.get("facts")))),sent,layout);
            verified.facts().stream().map(SharedFactReuse::canonical).forEach(f->accepted.put(f.id(),f));rejected.addAll(verified.rejected());
        }
        return new ProofVerifier.Verified(List.copyOf(accepted.values()),List.copyOf(rejected));
    }
    private static List<Map<String,Object>> steps(Map<String,Object> context,String modelIdentity){
        return RuleEvaluator.maps(context.get("priorSteps")).stream().filter(s->str(s,"type").startsWith("FACT_VALIDATION_")&&current(s,modelIdentity)).toList();
    }
    private static boolean current(Map<String,Object> step,String modelIdentity){return "COMPLETED".equals(str(step,"status"))&&VERSION.equals(str(step,"schemaVersion"))&&modelIdentity.equals(str(step,"modelIdentity"));}
    private static Map<String,Object> map(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?> m)m.forEach((k,v)->result.put(String.valueOf(k),v));return result;}
}

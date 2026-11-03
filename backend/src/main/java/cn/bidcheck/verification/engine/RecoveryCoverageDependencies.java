package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.FieldCoverage;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Unfinished source coverage is a dependency, not a verdict and not an extracted fact. */
final class RecoveryCoverageDependencies {
    private static final String CONTEXT_KEY="_recoveryCoverageDependencies";
    private static final Set<String> CODES=cn.bidcheck.compat.Java8Api.setOf("SHARED_SOURCE_REVIEW_REQUIRED", "EVIDENCE_LOOKUP_INCOMPLETE");
    private final Map<String,Map<String,Object>> obligations=new LinkedHashMap<>();
    private final Set<String> active;
    RecoveryCoverageDependencies(Map<String,Object> context,List<Rule> rules,List<Rule> selected,List<Source> priorSources,String modelIdentity){
        active=new HashSet<>(cn.bidcheck.compat.Java8Api.toList(selected.stream().map(Rule::id)));if(!RecoveryTargets.active(context))return;
        Map<String,Rule> byId=new LinkedHashMap<>();rules.forEach(r->byId.put(r.id(),r));Map<String,Object> previous=map(context.get("previousAtomicCheck"));
        // The latest atomic result owns unresolved dependencies, including an explicitly empty
        // set after a successful read. Old snapshots remain evidence, not an eternal blocker.
        List<Map<String,Object>> persisted=RuleEvaluator.maps(previous.get("coverageDependencies"));
        if(!previous.containsKey("coverageDependencies"))for(Map<String,Object> step:RuleEvaluator.maps(context.get("priorSteps")))if(RecoveryReplay.FACT_SNAPSHOT.equals(str(step,"type"))&&"COMPLETED".equals(str(step,"status"))&&VERSION.equals(str(step,"schemaVersion"))&&modelIdentity.equals(str(step,"modelIdentity")))
            persisted=RuleEvaluator.maps(map(step.get("output")).get("coverageDependencies"));
        for(Map<String,Object> dependency:persisted){Rule rule=byId.get(str(dependency,"obligationId"));if(rule!=null&&key(rule).equals(str(dependency,"observationKey"))&&CODES.contains(str(dependency,"gapCode")))merge(dependency);}
        List<Map<String,Object>> conditions=new ArrayList<>(RuleEvaluator.maps(previous.get("conditions")));conditions.addAll(RuleEvaluator.maps(previous.get("obligations")));
        List<Map<String,Object>> fields=RuleEvaluator.maps(previous.get("fieldCoverage"));
        for(Map<String,Object> condition:conditions){Rule rule=byId.get(str(condition,"id"));if(rule==null)continue;
            for(Map<String,Object> gap:RuleEvaluator.maps(condition.get("gaps")))if(CODES.contains(str(gap,"code")))add(rule,str(gap,"code"),sourceIds(rule,fields,priorSources));}
        boolean truncated=RuleEvaluator.maps(previous.get("gaps")).stream().anyMatch(g->"EVIDENCE_LOOKUP_INCOMPLETE".equals(str(g,"code")));
        if(truncated){List<Map<String,Object>> details=RuleEvaluator.maps(map(previous.get("metrics")).get("lookupTruncations"));if(details.isEmpty())details=RuleEvaluator.maps(map(previous.get("proof")).get("lookupTruncations"));
            for(Rule rule:rules){Set<String> refs=new LinkedHashSet<>();boolean relevant=details.isEmpty();for(Map<String,Object> detail:details){List<String> keys=ProofVerifier.strings(detail.get("observationKeys"));if(keys.isEmpty()||keys.contains(key(rule))){relevant=true;refs.addAll(ProofVerifier.strings(detail.get("sourceIds")));}}
                if(relevant)add(rule,"EVIDENCE_LOOKUP_INCOMPLETE",refs.isEmpty()?sourceIds(rule,fields,priorSources):cn.bidcheck.compat.Java8Api.listCopyOf(refs));}
        }
    }
    void apply(Map<String,Object> context,SharedFactReuse shared){
        Map<String,Map<String,Object>> pending=new LinkedHashMap<>();
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> entry:obligations.entrySet())if(!resolved(entry.getValue(),shared))pending.put(entry.getKey(),entry.getValue());
        context.put(CONTEXT_KEY,pending);
    }
    private boolean resolved(Map<String,Object> dependency,SharedFactReuse shared){
        if(!active.contains(str(dependency,"obligationId"))||shared.factLookupTruncated())return false;
        List<String> required=ProofVerifier.strings(dependency.get("sourceIds"));if(required.isEmpty())return false;
        Set<String> read=new HashSet<>();
        for(Map<String,Object> field:shared.coverage())if(str(dependency,"observationKey").equals(str(field,"observationKey"))&&!Boolean.TRUE.equals(field.get("recoveryRevalidated"))){
            List<String> refs=ProofVerifier.strings(field.get("sourceIds"));
            if(FieldCoverage.completeRead(field))read.addAll(refs);
            else if(refs.stream().anyMatch(required::contains))return false;
        }
        return read.containsAll(required);
    }
    static Decision guard(Rule rule,Decision decision,Map<String,Object> context){
        return guard(rule,decision,context,null);
    }
    static Decision guard(Rule rule,Decision decision,RuleEvaluator evaluator){
        return guard(rule,decision,evaluator.context,evaluator);
    }
    private static Decision guard(Rule rule,Decision decision,Map<String,Object> context,RuleEvaluator evaluator){
        Map<String,Object> dependency=map(map(context.get(CONTEXT_KEY)).get(rule.id()));
        if(dependency.isEmpty()&&SourceScoringRules.applies(rule)&&SourceScoringRules.issue(rule)==null)
            for(Rule read:SourceScoringRules.evidenceRules(rule)){
                Map<String,Object> pending=map(map(context.get(CONTEXT_KEY)).get(read.id()));
                if(!pending.isEmpty()){dependency=pending;break;}
            }
        if(dependency.isEmpty()||!cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL").contains(decision.status()))return decision;
        // A proven single-record positive may close the same source-review dependency.
        // Truncated shared-fact lookup remains blocked: its unread counterevidence is not
        // present in the frozen corpus. Never let a serialized proof authorize this path.
        if(evaluator!=null&&"SHARED_SOURCE_REVIEW_REQUIRED".equals(str(dependency,"gapCode"))
            &&rule.id().equals(str(dependency,"obligationId"))&&key(rule).equals(str(dependency,"observationKey"))){
            Decision closed=evaluator.closeSourceReview(rule,decision);
            if(closed!=null){
                Map<String,Object> pending=map(context.get(CONTEXT_KEY));pending.remove(rule.id());context.put(CONTEXT_KEY,pending);
                return closed;
            }
        }
        Map<String,Object> proof=new LinkedHashMap<>(decision.proof());proof.put("unresolvedCoverageDependency",dependency);
        return new Decision(decision.id(),decision.description(),"UNDETERMINED","此前未完成的来源读取仍未补齐，已重新校验的局部事实不能代替完整覆盖。",decision.evidences(),
            cn.bidcheck.compat.Java8Api.listOf(gap(str(dependency,"gapCode"),"需要补齐该条件所依赖的全部来源读取后重新计算。")),proof);
    }
    List<Map<String,Object>> snapshot(Map<String,Object> context,List<Rule> rules,Map<String,Decision> decisions,SharedFactReuse shared,List<Source> candidates){
        Map<String,Map<String,Object>> retained=new LinkedHashMap<>();map(context.get(CONTEXT_KEY)).forEach((id,value)->retained.put(id,map(value)));
        for(Rule rule:rules){Decision decision=decisions.get(rule.id());
            if(decision==null&&SourceScoringRules.VERSION.equals(str(rule.parameters(),"sourceScoringReadVersion")))decision=decisions.get(str(rule.parameters(),"scoringParentRuleId"));
            if(decision==null)continue;for(Gap gap:decision.gaps())if(CODES.contains(gap.code())){
            retained.putIfAbsent(rule.id(),linked("obligationId",rule.id(),"observationKey",key(rule),"gapCode",gap.code(),"sourceIds",sourceIds(rule,shared.coverage(),candidates),"businessProof",false));}}
        if(shared.factLookupTruncated())for(Rule rule:rules){Set<String> refs=new LinkedHashSet<>();boolean relevant=false;
            for(Map<String,Object> detail:shared.lookupTruncations()){List<String> keys=ProofVerifier.strings(detail.get("observationKeys"));if(keys.isEmpty()||keys.contains(key(rule))){relevant=true;refs.addAll(ProofVerifier.strings(detail.get("sourceIds")));}}
            if(relevant)retained.put(rule.id(),linked("obligationId",rule.id(),"observationKey",key(rule),"gapCode","EVIDENCE_LOOKUP_INCOMPLETE","sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(refs),"businessProof",false));}
        return cn.bidcheck.compat.Java8Api.listCopyOf(retained.values());
    }
    private void add(Rule rule,String code,List<String> refs){merge(linked("obligationId",rule.id(),"observationKey",key(rule),"gapCode",code,"sourceIds",refs,"businessProof",false));}
    private void merge(Map<String,Object> dependency){String id=str(dependency,"obligationId");Map<String,Object> old=obligations.get(id);Set<String> refs=new LinkedHashSet<>(ProofVerifier.strings(dependency.get("sourceIds")));
        if(old!=null)refs.addAll(ProofVerifier.strings(old.get("sourceIds")));Map<String,Object> value=new LinkedHashMap<>(dependency);
        if(old!=null&&"EVIDENCE_LOOKUP_INCOMPLETE".equals(str(old,"gapCode")))value.put("gapCode","EVIDENCE_LOOKUP_INCOMPLETE");
        value.put("sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(refs));obligations.put(id,value);}
    private static List<String> sourceIds(Rule rule,List<Map<String,Object>> fields,List<Source> fallback){Set<String> refs=new LinkedHashSet<>();for(Map<String,Object> field:fields)if(key(rule).equals(str(field,"observationKey")))refs.addAll(ProofVerifier.strings(field.get("sourceIds")));if(refs.isEmpty())fallback.forEach(s->refs.add(s.id()));return cn.bidcheck.compat.Java8Api.listCopyOf(refs);}
    private static String key(Rule rule){return FieldCoverage.key(SharedFactReuse.observationTarget(rule));}
    private static Map<String,Object> map(Object raw){Map<String,Object> out=new LinkedHashMap<>();if(raw instanceof Map<?,?>)((Map<?,?>) (raw)).forEach((k,v)->out.put(String.valueOf(k),v));return out;}
}

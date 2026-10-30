package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Per-target consumed evidence, not a model-call budget or a business verdict. */
final class RecoverySemanticGate {
    static final String VERSION="recovery-semantic-gate-1-consumed-target-inputs";
    private static final String LEGACY_TARGET="__CHECKPOINT__";
    private RecoverySemanticGate() {}

    static Map<String,Object> inspect(Finding finding,String strategy,Map<String,Object> context,
            List<Map<String,Object>> attempts,String policy) {
        if(!"TARGETED_EVIDENCE".equals(strategy))return fields("version",VERSION,"allowed",true,"reasonCode","LOCAL_OR_DISTINCT_CAPABILITY","inputs",List.of());
        List<Map<String,Object>> inputs=inputs(finding,context,policy);
        List<Map<String,Object>> consumed=new ArrayList<>();
        for(Map<String,Object> attempt:attempts)if("TARGETED_EVIDENCE".equals(text(attempt,"strategy"))
                &&Set.of("FINISHED","NO_PROGRESS").contains(text(attempt,"status")))
            for(Map<String,Object> input:maps(attempt.get("semanticConsumedInputs")))
                if(VERSION.equals(text(input,"version")))consumed.add(input);
        List<String> eligible=new ArrayList<>(),blocked=new ArrayList<>();
        for(Map<String,Object> input:inputs){String id=text(input,"targetId");
            (consumed.stream().anyMatch(prior->consumed(prior,input))?blocked:eligible).add(id);}
        boolean allowed=!eligible.isEmpty();
        return fields("version",VERSION,"allowed",allowed,"reasonCode",allowed
                ?blocked.isEmpty()?"NEW_TARGET_OR_RELEVANT_EVIDENCE":"PARTIAL_TARGET_PROGRESS":"TARGET_SEMANTIC_INPUT_CONSUMED",
            "eligibleTargetIds",eligible.stream().filter(id->!LEGACY_TARGET.equals(id)).toList(),
            "blockedTargetIds",List.copyOf(blocked),"inputs",inputs,"businessProof",false,
            "description",allowed?"仅调查具有未处理输入的目标条件。":"同一条件的规则、事实及失败原因未发生实质变化，已停止重复调查；原有缺口和处理责任保持不变。");
    }

    static List<Map<String,Object>> inputs(Finding finding,Map<String,Object> context,String policy) {
        List<String> targets=stringList(context.get("recoveryTargetObligationIds"));
        if(targets.isEmpty())targets=RecoveryInputs.targets(finding,"TARGETED_EVIDENCE");
        if(targets.isEmpty())targets=List.of(LEGACY_TARGET);
        Map<String,Object> atomic=atomic(finding);Map<String,Map<String,Object>> rules=new LinkedHashMap<>();
        index(maps(map(atomic.get("plan")).get("rules")),rules);
        Map<String,Map<String,Object>> dependencies=new HashMap<>();
        for(Map<String,Object> dependency:maps(atomic.get("recoveryEvidenceDependencies")))dependencies.put(text(dependency,"obligationId"),dependency);
        List<Map<String,Object>> result=new ArrayList<>();
        for(String targetId:targets){
            Map<String,Object> rule=rules.getOrDefault(targetId,Map.of());
            List<Map<String,Object>> bindings=new ArrayList<>();collectBindings(rule,dependencies,bindings);
            if(bindings.isEmpty()&&dependencies.containsKey(targetId))bindings.add(dependencies.get(targetId));
            Set<String> relevantFacts=new TreeSet<>();
            for(Map<String,Object> fact:maps(atomic.get("facts"))){
                List<Map<String,Object>> sources=stringList(fact.get("sourceIds")).stream().map(id->fields("id",id)).toList();
                if(sources.isEmpty())continue;
                Map<String,Object> change=fields("itemId","semantic-input","recordType","FACT","fact",fact,"sources",sources);
                if(bindings.stream().anyMatch(binding->OrdinaryRecoveryDependencies.matches(binding,change)))relevantFacts.add(RecoveryEvidenceIdentity.key(change));
            }
            Set<String> gapCodes=new TreeSet<>(),proofFactIds=new TreeSet<>();
            List<Map<String,Object>> conditions=new ArrayList<>(maps(atomic.get("conditions")));conditions.addAll(maps(atomic.get("obligations")));
            for(Map<String,Object> condition:conditions)if(targetId.equals(text(condition,"id"))){
                maps(condition.get("gaps")).forEach(g->gapCodes.add(text(g,"code")));
                collectFactIds(condition.get("proof"),proofFactIds);
            }
            if(LEGACY_TARGET.equals(targetId))maps(atomic.get("gaps")).forEach(g->gapCodes.add(text(g,"code")));
            gapCodes.remove("");
            // Published findings deliberately omit the full fact corpus. Their per-condition
            // proof contains canonical, source-validated fact ids. Use those lightweight
            // dependencies instead of issuing another full shared-fact database query.
            Map<String,Object> trustedDependencies=map(atomic.get("recoveryDependencies"));
            String sourceRevision=text(context,"recoverySourceRevision"),validationIdentity=text(context,"recoveryValidationIdentity");
            if(sourceRevision.isBlank())sourceRevision=text(trustedDependencies,"sourceRevision");
            if(validationIdentity.isBlank())validationIdentity=text(trustedDependencies,"validationIdentity");
            // The source/validator identities are server-issued dependencies. Do not use
            // inputRevision or the global fact clock: raw observations change those too.
            Map<String,Object> base=fields("policy",policy,"rule",ruleInput(rule),"gapCodes",gapCodes,
                "sourceRevision",sourceRevision,"validationIdentity",validationIdentity);
            String dependencyKey=hash(RecoveryEvidenceIdentity.canonical(base));
            Set<String> evidenceKeys=new TreeSet<>();relevantFacts.forEach(k->evidenceKeys.add("FACT:"+k));proofFactIds.forEach(k->evidenceKeys.add("PROOF:"+k));
            Map<String,Object> value=fields("dependencyKey",dependencyKey,"evidenceKeys",evidenceKeys);
            result.add(fields("version",VERSION,"targetId",targetId,"key",hash(RecoveryEvidenceIdentity.canonical(value)),
                "dependencyKey",dependencyKey,"evidenceKeys",List.copyOf(evidenceKeys),
                "relevantFactCount",Math.max(relevantFacts.size(),proofFactIds.size()),"gapCodes",List.copyOf(gapCodes)));
        }
        return List.copyOf(result);
    }

    /** Raw observations authorize local revalidation, never a second investigation by themselves. */
    static void applyTargets(Map<String,Object> context,Map<String,Object> receipt){
        List<String> eligible=stringList(receipt.get("eligibleTargetIds"));
        if(!eligible.isEmpty())context.put("recoveryTargetObligationIds",eligible);
    }
    private static void collectBindings(Map<String,Object> rule,Map<String,Map<String,Object>> dependencies,List<Map<String,Object>> out){
        if(rule.isEmpty())return;
        List<Map<String,Object>> children=maps(rule.get("children"));
        if(!children.isEmpty()){children.forEach(child->collectBindings(child,dependencies,out));return;}
        Map<String,Object> binding=dependencies.get(text(rule,"id"));
        out.add(binding!=null?binding:fields("target",ruleInput(rule),"resolvedSubjects",List.of()));
    }
    private static Map<String,Object> ruleInput(Map<String,Object> rule){
        Map<String,Object> out=new TreeMap<>();for(String key:List.of("predicate","subject","field","operator","expected","unit","sourceQuote","logic"))if(rule.containsKey(key))out.put(key,rule.get(key));
        Map<String,Object> parameters=new TreeMap<>(map(rule.get("parameters")));
        for(String key:List.of("obligationIdentity","ruleId","parentRuleId","compilationIssue","focusedScopeIssue","requirementContextIssue","repairReason"))parameters.remove(key);
        out.put("parameters",parameters);out.put("children",maps(rule.get("children")).stream().map(RecoverySemanticGate::ruleInput).toList());return out;
    }
    private static void index(List<Map<String,Object>> rules,Map<String,Map<String,Object>> result){for(Map<String,Object> rule:rules){result.put(text(rule,"id"),rule);index(maps(rule.get("children")),result);}}
    private static void collectFactIds(Object value,Set<String> out){
        if(value instanceof Map<?,?> raw){Map<String,Object> proof=map(raw);out.addAll(stringList(proof.get("factIds")));proof.values().forEach(v->collectFactIds(v,out));}
        else if(value instanceof Collection<?> all)all.forEach(v->collectFactIds(v,out));
    }
    private static boolean consumed(Map<String,Object> prior,Map<String,Object> current){
        if(!text(prior,"targetId").equals(text(current,"targetId")))return false;
        if(text(prior,"key").equals(text(current,"key")))return true;
        // Dropping an old observation is not new information that warrants another call.
        return !text(prior,"dependencyKey").isBlank()&&text(prior,"dependencyKey").equals(text(current,"dependencyKey"))
            &&new HashSet<>(stringList(prior.get("evidenceKeys"))).containsAll(stringList(current.get("evidenceKeys")));
    }
    private static List<String> stringList(Object value){return value instanceof Collection<?> all?all.stream().map(v->Objects.toString(v,"")).filter(v->!v.isBlank()).distinct().sorted().toList():List.of();}
}

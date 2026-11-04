package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Per-target consumed evidence, not a model-call budget or a business verdict. */
final class RecoverySemanticGate {
    static final String VERSION="recovery-semantic-gate-1-consumed-target-inputs";
    static final String POLICY="recovery-admission-3-executable-target-prerequisites";
    private static final String LEGACY_TARGET="__CHECKPOINT__";
    private static final String FIRST_ROUND="first-round-target-inputs-1";
    private RecoverySemanticGate() {}

    /** Carry consumption history across local recomputation, never an earlier business verdict. */
    static Finding preserveFirstRound(Finding before, Finding after) {
        if (before == after) return after;
        Map<String,Object> original = atomic(before);
        Map<String,Object> receipt = map(original.get("firstRoundSemanticInputs"));
        if (!FIRST_ROUND.equals(text(receipt, "version")) || text(receipt, "requestId").isEmpty()
                || !Boolean.FALSE.equals(receipt.get("businessProof"))) return after;
        Map<String,Object> updated = new LinkedHashMap<>(atomic(after));
        if (updated.containsKey("firstRoundSemanticInputs")) return after;
        updated.put("firstRoundSemanticInputs", receipt);
        Object investigation = original.getOrDefault("firstRoundInvestigation", original.get("agentInvestigation"));
        if (investigation instanceof Map<?,?>) updated.putIfAbsent("firstRoundInvestigation", investigation);
        Map<String,Object> assessment = new LinkedHashMap<>(after.assessment());
        assessment.put("atomicCheck", updated);
        return new Finding(after.id(), after.runId(), after.checkpoint(), after.evaluation(), assessment);
    }

    /** A completed text investigation consumes its current inputs, not its unresolved business
     * obligation. In particular, reading/searching can stop without exhausting every semantic
     * repair path. Keep that distinction instead of demanding an impossible exhaustion receipt. */
    static Finding captureFirstRound(Finding finding,Map<String,Object> context,String policy){
        if(context.containsKey("recoveryStrategy")||context.containsKey("recoveryMode"))return finding;
        Map<String,Object> atomic=atomic(finding),audit=map(atomic.get("agentInvestigation"));
        if(!cn.bidcheck.compat.Java8Api.setOf("NO_PROGRESS","EVIDENCE_UPDATED").contains(text(audit,"status"))
                ||!"REPEATED_ACTION_WITHOUT_NEW_EVIDENCE".equals(text(audit,"code"))
                ||!"CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority"))
                ||text(audit,"requestId").isEmpty()||!(audit.get("failedToolCount") instanceof Number)
                ||((Number)audit.get("failedToolCount")).longValue()!=0)return finding;
        Set<String> remaining=new HashSet<>(stringList(audit.get("remainingTargetObligationIds")));
        remaining.removeAll(stringList(audit.get("pendingConfirmationTargetObligationIds")));
        Set<String> visited=new TreeSet<>();
        for(Map<String,Object> row:maps(audit.get("targetProgressLedger"))){String id=text(row,"targetObligationId");
            boolean read=row.get("navigationCount") instanceof Number&&((Number)row.get("navigationCount")).longValue()>0;
            boolean attempted=row.get("noEvidenceProgressRounds") instanceof Number&&((Number)row.get("noEvidenceProgressRounds")).longValue()>0;
            if(remaining.contains(id)&&Boolean.TRUE.equals(row.get("visited"))&&(read||attempted))visited.add(id);
        }
        if(visited.isEmpty())return finding;
        Map<String,Object> scoped=new LinkedHashMap<>(context);scoped.put("recoveryTargetObligationIds",new ArrayList<>(visited));
        Map<String,Object> updated=new LinkedHashMap<>(atomic);
        updated.put("firstRoundSemanticInputs",fields("version",FIRST_ROUND,"requestId",audit.get("requestId"),
            "strategy","TARGETED_EVIDENCE","inputs",inputs(finding,scoped,policy),"businessProof",false,
            "reason","FIRST_INVESTIGATION_STOPPED_WITHOUT_NEW_FACTS","exhaustedBusinessTargets",false));
        Map<String,Object> assessment=new LinkedHashMap<>(map(finding.assessment()));assessment.put("atomicCheck",updated);
        return new Finding(finding.id(),finding.runId(),finding.checkpoint(),finding.evaluation(),assessment);
    }

    static Map<String,Object> inspect(Finding finding,String strategy,Map<String,Object> context,
            List<Map<String,Object>> attempts,String policy) {

        List<Map<String,Object>> inputs=inputs(finding,context,policy);
        Map<String,String> unavailable = "TARGETED_EVIDENCE".equals(strategy)
            ? cn.bidcheck.verification.engine.RecoveryTargets.unavailableEvidenceTargets(
                map(atomic(finding).get("plan")).get("rules"), atomic(finding).get("conditions"),
                cn.bidcheck.compat.Java8Api.toList(inputs.stream().map(input -> text(input, "targetId"))))
            : Collections.emptyMap();
        List<Map<String,Object>> consumed=new ArrayList<>();
        for(Map<String,Object> attempt:attempts)if(strategy.equals(text(attempt,"strategy"))
                &&cn.bidcheck.compat.Java8Api.setOf("FINISHED", "NO_PROGRESS").contains(text(attempt,"status")))
            for(Map<String,Object> input:maps(attempt.get("semanticConsumedInputs")))
                if(VERSION.equals(text(input,"version")))consumed.add(input);
        Set<String> firstRound=firstRoundConsumed(strategy,context,map(map(atomic(finding).get("agentInvestigation")).get("firstRoundTextWork")));
        firstRound=new TreeSet<>(firstRound);
        Map<String,Object> firstReceipt=map(atomic(finding).get("firstRoundSemanticInputs"));
        if("TARGETED_EVIDENCE".equals(strategy)&&FIRST_ROUND.equals(text(firstReceipt,"version"))
                &&!text(firstReceipt,"requestId").isEmpty()&&Boolean.FALSE.equals(firstReceipt.get("businessProof")))
            for(Map<String,Object> current:inputs)if(maps(firstReceipt.get("inputs")).stream().anyMatch(prior->consumed(prior,current)))firstRound.add(text(current,"targetId"));
        List<String> eligible=new ArrayList<>(),blocked=new ArrayList<>();
        for(Map<String,Object> input:inputs){String id=text(input,"targetId");
            (unavailable.containsKey(id)||firstRound.contains(id)||consumed.stream().anyMatch(prior->consumed(prior,input))?blocked:eligible).add(id);}
        boolean allowed=!eligible.isEmpty();
        return fields("version",VERSION,"allowed",allowed,"reasonCode",allowed
                ?blocked.isEmpty()?"NEW_TARGET_OR_RELEVANT_EVIDENCE":"PARTIAL_TARGET_PROGRESS"
                :unavailable.isEmpty()?"TARGET_SEMANTIC_INPUT_CONSUMED":"NO_EXECUTABLE_EVIDENCE_TARGET",
            "unavailableEvidenceTargets",unavailable,
            "eligibleTargetIds",cn.bidcheck.compat.Java8Api.toList(eligible.stream().filter(id->!LEGACY_TARGET.equals(id))),
            "blockedTargetIds",cn.bidcheck.compat.Java8Api.listCopyOf(blocked),"firstRoundConsumedTargetIds",firstRound,"inputs",inputs,"businessProof",false,
            "description",allowed?"仅调查具有未处理输入的目标条件。"
                :unavailable.isEmpty()?"同一条件的规则、事实及失败原因未发生实质变化，已停止重复调查；原有缺口和处理责任保持不变。"
                :"当前目标没有可执行的取证规则，或招标基础要求来源尚未确定；保留未完成缺口及系统处理责任，待相应输入变化后重算。");
    }

    static Set<String> firstRoundConsumed(String strategy,Map<String,Object> context,Map<String,Object> receipt){
        if(!"TARGETED_EVIDENCE".equals(strategy)||!"first-round-consumed-text-1".equals(text(receipt,"version"))
                ||text(receipt,"requestId").isEmpty()||text(receipt,"key").isEmpty()
                ||!text(receipt,"key").equals(text(context,"agentAdmissionKey"))
                ||!map(context.get("recoveryDependencyTrigger")).isEmpty()||!stringList(context.get("recoveryChangedSourceIds")).isEmpty()
                ||!stringList(context.get("recoveryChangedLocations")).isEmpty())return Collections.emptySet();
        return new TreeSet<>(stringList(receipt.get("targets")));
    }

    static List<Map<String,Object>> inputs(Finding finding,Map<String,Object> context,String policy) {
        List<String> targets=stringList(context.get("recoveryTargetObligationIds"));
        if(targets.isEmpty())targets=RecoveryInputs.targets(finding,"TARGETED_EVIDENCE");
        if(targets.isEmpty())targets=cn.bidcheck.compat.Java8Api.listOf(LEGACY_TARGET);
        Map<String,Object> atomic=atomic(finding);Map<String,Map<String,Object>> rules=new LinkedHashMap<>();
        index(maps(map(atomic.get("plan")).get("rules")),rules);
        Map<String,Map<String,Object>> reads=cn.bidcheck.verification.engine.RecoveryTargets.readDependencies(map(atomic.get("plan")).get("rules"));
        Map<String,Object> audit=map(atomic.get("agentInvestigation"));
        if(!"CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority")))audit=map(atomic.get("firstRoundInvestigation"));
        Set<String> remaining=new HashSet<>(stringList(audit.get("remainingTargetObligationIds")));
        if("CURRENT_SERVER_PROOF".equals(text(audit,"targetReceiptAuthority"))){
            Set<String> expanded=new TreeSet<>();
            for(String target:targets){List<String> children=new ArrayList<>();
                reads.forEach((id,read)->{if(target.equals(text(read,"parentId"))&&remaining.contains(id))children.add(id);});
                if(children.isEmpty())expanded.add(target);else expanded.addAll(children);
            }
            targets=new ArrayList<>(expanded);
        }
        Map<String,Map<String,Object>> dependencies=new HashMap<>();
        for(Map<String,Object> dependency:maps(atomic.get("recoveryEvidenceDependencies")))dependencies.put(text(dependency,"obligationId"),dependency);
        List<Map<String,Object>> result=new ArrayList<>();
        for(String targetId:targets){
            Map<String,Object> read=reads.getOrDefault(targetId,Collections.emptyMap());
            String parentId=text(read,"parentId");
            Map<String,Object> rule=rules.getOrDefault(targetId,map(read.get("rule")));
            List<Map<String,Object>> bindings=new ArrayList<>();collectBindings(rule,dependencies,bindings);
            if(bindings.isEmpty()&&dependencies.containsKey(targetId))bindings.add(dependencies.get(targetId));
            Set<String> relevantFacts=new TreeSet<>();
            for(Map<String,Object> fact:maps(atomic.get("facts"))){
                List<Map<String,Object>> sources=cn.bidcheck.compat.Java8Api.toList(stringList(fact.get("sourceIds")).stream().map(id->fields("id",id)));
                if(sources.isEmpty())continue;
                Map<String,Object> change=fields("itemId","semantic-input","recordType","FACT","fact",fact,"sources",sources);
                if(bindings.stream().anyMatch(binding->OrdinaryRecoveryDependencies.matches(binding,change)))relevantFacts.add(RecoveryEvidenceIdentity.key(change));
            }
            Set<String> gapCodes=new TreeSet<>(),proofFactIds=new TreeSet<>();
            List<Map<String,Object>> conditions=new ArrayList<>(maps(atomic.get("conditions")));conditions.addAll(maps(atomic.get("obligations")));
            for(Map<String,Object> condition:conditions)if(targetId.equals(text(condition,"id"))||(!parentId.isEmpty()&&parentId.equals(text(condition,"id")))){
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
            if(cn.bidcheck.compat.Java8Api.isBlank(sourceRevision))sourceRevision=text(trustedDependencies,"sourceRevision");
            if(cn.bidcheck.compat.Java8Api.isBlank(validationIdentity))validationIdentity=text(trustedDependencies,"validationIdentity");
            // The source/validator identities are server-issued dependencies. Do not use
            // inputRevision or the global fact clock: raw observations change those too.
            if(!"".equals(targetId)){
                Map<String,Object> trigger=map(context.get("recoveryDependencyTrigger"));
                if(stringList(context.get("recoveryTargetObligationIds")).contains(targetId))relevantFacts.addAll(stringList(trigger.get("semanticEvidenceKeys")));
            }
            Map<String,Object> base=fields("policy",policy,"status",finding.evaluation().status(),"rule",ruleInput(rule),"gapCodes",gapCodes,
                "sourceRevision",sourceRevision,"validationIdentity",validationIdentity);
            if(!parentId.isEmpty())base.put("parentRule",ruleInput(rules.getOrDefault(parentId,Collections.emptyMap())));
            String dependencyKey=hash(RecoveryEvidenceIdentity.canonical(base));
            Set<String> evidenceKeys=new TreeSet<>();relevantFacts.forEach(k->evidenceKeys.add("FACT:"+k));proofFactIds.forEach(k->evidenceKeys.add("PROOF:"+k));
            Map<String,Object> value=fields("dependencyKey",dependencyKey,"evidenceKeys",evidenceKeys);
            result.add(fields("version",VERSION,"targetId",targetId,"key",hash(RecoveryEvidenceIdentity.canonical(value)),
                "dependencyKey",dependencyKey,"evidenceKeys",cn.bidcheck.compat.Java8Api.listCopyOf(evidenceKeys),
                "relevantFactCount",Math.max(relevantFacts.size(),proofFactIds.size()),"gapCodes",cn.bidcheck.compat.Java8Api.listCopyOf(gapCodes)));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result);
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
        out.add(binding!=null?binding:fields("target",ruleInput(rule),"resolvedSubjects",cn.bidcheck.compat.Java8Api.listOf()));
    }
    private static Map<String,Object> ruleInput(Map<String,Object> rule){
        Map<String,Object> out=new TreeMap<>();for(String key:cn.bidcheck.compat.Java8Api.listOf("predicate", "subject", "field", "operator", "expected", "unit", "sourceQuote", "logic"))if(rule.containsKey(key))out.put(key,rule.get(key));
        Map<String,Object> parameters=new TreeMap<>(map(rule.get("parameters")));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("obligationIdentity", "ruleId", "parentRuleId", "compilationIssue", "focusedScopeIssue", "requirementContextIssue", "repairReason"))parameters.remove(key);
        out.put("parameters",parameters);out.put("children",cn.bidcheck.compat.Java8Api.toList(maps(rule.get("children")).stream().map(RecoverySemanticGate::ruleInput)));return out;
    }
    private static void index(List<Map<String,Object>> rules,Map<String,Map<String,Object>> result){for(Map<String,Object> rule:rules){result.put(text(rule,"id"),rule);index(maps(rule.get("children")),result);}}
    private static void collectFactIds(Object value,Set<String> out){
        if(value instanceof Map<?,?>){Map<String,Object> proof=map(((Map<?,?>) (value)));out.addAll(stringList(proof.get("factIds")));proof.values().forEach(v->collectFactIds(v,out));}
        else if(value instanceof Collection<?>)((Collection<?>) (value)).forEach(v->collectFactIds(v,out));
    }
    private static boolean consumed(Map<String,Object> prior,Map<String,Object> current){
        if(!text(prior,"targetId").equals(text(current,"targetId")))return false;
        if(text(prior,"key").equals(text(current,"key")))return true;
        // Dropping an old observation is not new information that warrants another call.
        return !cn.bidcheck.compat.Java8Api.isBlank(text(prior,"dependencyKey"))&&text(prior,"dependencyKey").equals(text(current,"dependencyKey"))
            &&new HashSet<>(stringList(prior.get("evidenceKeys"))).containsAll(stringList(current.get("evidenceKeys")));
    }
    private static List<String> stringList(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(v->Objects.toString(v,"")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().sorted()):cn.bidcheck.compat.Java8Api.listOf();}
}

package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.verification.engine.RequirementRepairPolicy;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Recovery carries the failed nodes and changed inputs, never an instruction to repeat the whole check. */
final class RecoveryInputs {
    private RecoveryInputs() {}

    static Map<String,Object> context(Finding before,String strategy,Map<String,Object> current){
        Map<String,Object> out=new LinkedHashMap<>(current),atomic=atomic(before);
        out.put("recoveryStrategy",strategy);out.put("recoveryGaps",maps(atomic.get("gaps")));
        out.put("previousAtomicCheck",atomic);out.put("recoveryTargetObligationIds",targets(before,strategy));
        // Restore per-target receipts across local recomputations, which need not contain
        // a new Agent audit. The Worker itself requires exact rule/evidence revisions.
        Object ledger=map(atomic.get("agentInvestigation")).get("targetProgressLedger");
        if(ledger==null)for(Map<String,Object> step:maps(current.get("priorSteps")))
            if("AGENT_INVESTIGATION".equals(text(step,"type"))&&map(step.get("output")).containsKey("targetProgressLedger"))ledger=map(step.get("output")).get("targetProgressLedger");
        if(ledger!=null)out.put("targetProgressLedger",ledger);
        if(RequirementRepairPolicy.requirementWork(strategy)){
            out.putAll(RequirementRepairPolicy.versions());
            out.put("requirementSourcePackageRevision",hash(canonical(fields("source",before.checkpoint().source(),"reviewedExpected",before.checkpoint().expected(),
                "tenderSources",current.getOrDefault("tenderDocuments",cn.bidcheck.compat.Java8Api.listOf()) instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (current.getOrDefault("tenderDocuments",cn.bidcheck.compat.Java8Api.listOf()))).stream()
                    .map(d->d instanceof cn.bidcheck.domain.Domain.Document?fields("id",((cn.bidcheck.domain.Domain.Document) (d)).id(),"hash",((cn.bidcheck.domain.Domain.Document) (d)).sha256(),"version",((cn.bidcheck.domain.Domain.Document) (d)).version()):d)):cn.bidcheck.compat.Java8Api.listOf()))));
        }
        if("RESTORE_REQUIREMENT_SOURCE".equals(strategy)&&targets(before,strategy).isEmpty())out.remove("recoveryTargetObligationIds");
        out.put("recoveryDependencyRevision",dependencyKey(out));
        // A page observation changes an input to the rule; it does not invalidate all text already read.
        out.put("recoveryMode",RequirementRepairPolicy.requirementWork(strategy)?"RESTORE_REQUIREMENT_SOURCE".equals(strategy)?"RESTORE_REQUIREMENT_SOURCE":"RECOMPILE_REQUIREMENT"
            :cn.bidcheck.compat.Java8Api.setOf("VERIFY_SOURCE_PAGES", "QUERY_EXTERNAL", "RECOMPUTE_SHARED_EVIDENCE").contains(strategy)?"EVIDENCE_RECOMPUTE":"TARGETED");
        if(RequirementRepairPolicy.requirementWork(strategy)||cn.bidcheck.compat.Java8Api.setOf("VERIFY_SOURCE_PAGES", "QUERY_EXTERNAL", "RECOMPUTE_SHARED_EVIDENCE").contains(strategy))out.put("recoveryModelAllowed",false);
        return out;
    }

    /** A dependency wakeup can never widen itself to unrelated, previously investigated gaps. */
    static boolean applyDependencyTrigger(Map<String,Object> context,Map<String,Object> pending,Finding finding){
        if(!(pending.get("dependencyTargetObligationIds") instanceof Collection<?>))return true;
        // New counterevidence may revoke a prior PASS. Recompute those targets locally first;
        // only an investigation, after that revalidation, may discard completed targets.
        List<String> targets="RECOMPUTE_SHARED_EVIDENCE".equals(text(pending,"strategy"))
            ?cn.bidcheck.compat.Java8Api.toList(stringList(((Collection<?>) (pending.get("dependencyTargetObligationIds")))).stream().distinct().sorted()):pendingTargets(finding,((Collection<?>) (pending.get("dependencyTargetObligationIds"))));
        context.put("recoveryTargetObligationIds",targets);context.put("recoveryDependencyTrigger",pending.getOrDefault("dependencyTrigger",cn.bidcheck.compat.Java8Api.mapOf()));
        context.put("recoveryChangedSourceIds",map(pending.get("dependencyTrigger")).getOrDefault("sourceIds",cn.bidcheck.compat.Java8Api.listOf()));
        context.put("recoveryDependencyRevision",dependencyKey(context));
        return !targets.isEmpty();
    }

    /** Returns the scoped metadata to retain on the next job, after local evaluation. */
    static Map<String,Object> dependencyContinuation(Map<String,Object> pending,Finding after,Map<String,Object> nextPlan){
        if(!(pending.get("dependencyTargetObligationIds") instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.mapOf();
        List<String> remaining=pendingTargets(after,((Collection<?>) (pending.get("dependencyTargetObligationIds"))));Map<String,Object> trigger=map(pending.get("dependencyTrigger"));
        if(!unresolved(after))return cn.bidcheck.compat.Java8Api.mapOf();
        if(remaining.isEmpty()){
            nextPlan.putAll(fields("status","BLOCKED_NO_PROGRESS","strategy","BLOCKED","reason","新增证据所影响的条件已经完成，其他旧缺口没有新输入。","nextAction","保留现有证明，等待其他缺口所需的新证据。"));
            return cn.bidcheck.compat.Java8Api.mapOf();
        }
        // Only a real semantic change can authorize a second-stage investigation. An old
        // revision number alone authorizes local reconciliation, never another model call.
        if("RECOMPUTE_SHARED_EVIDENCE".equals(text(pending,"strategy"))&&stringList(trigger.get("semanticEvidenceKeys")).isEmpty()){
            nextPlan.putAll(fields("status","BLOCKED_NO_PROGRESS","strategy","BLOCKED","reason","本次依赖仅有版本记录，没有可用于继续调查的新证据。","nextAction","等待可追溯的新证据。"));return cn.bidcheck.compat.Java8Api.mapOf();
        }
        return ACTIVE.contains(text(nextPlan,"status"))?fields("dependencyTargetObligationIds",remaining,"dependencyTrigger",trigger):cn.bidcheck.compat.Java8Api.mapOf();
    }

    static Set<String> closedTargets(Finding finding){
        Set<String> closed=new HashSet<>(),open=new HashSet<>();Map<String,Object> atomic=atomic(finding);
        List<Map<String,Object>> conditions=new ArrayList<>(maps(atomic.get("conditions")));conditions.addAll(maps(atomic.get("obligations")));
        for(Map<String,Object> condition:conditions)if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())closed.add(text(condition,"id"));else open.add(text(condition,"id"));
        closed.removeAll(open);closed.remove("");return closed;
    }
    static List<String> pendingTargets(Finding finding,Collection<?> requested){
        Set<String> closed=closedTargets(finding),out=new TreeSet<>();for(Object raw:requested){String id=Objects.toString(raw,"");if(!cn.bidcheck.compat.Java8Api.isBlank(id)&&!closed.contains(id))out.add(id);}return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }

    /** Operational receipts/timestamps cannot create work; actual values and canonical revisions can. */
    static String dependencyKey(Map<String,Object> context){
        Map<String,Object> dependencies=new TreeMap<>();
        // A global fact clock also changes for unrelated claims and receipt-only duplicates.
        // Its value is audit data; matching semantic changes arrive via the dependency trigger.
        for(String key:cn.bidcheck.compat.Java8Api.listOf("referenceDate", "referenceDateEvidence", "referenceDates", "evaluationStage", "applicabilityInputs", "requirementSourcePackageRevision", "requirementRepairVersion", "requirementCompilerRevision", "requirementSourceRepairRevision", "requirementMappingRevision", "requirementValidationRevision")){
            Object value=context.get(key);if(value!=null&&!cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(value,""))&&!cn.bidcheck.compat.Java8Api.mapOf().equals(value)&&!cn.bidcheck.compat.Java8Api.listOf().equals(value))dependencies.put(key,value);
        }
        Map<String,Object> trigger=map(context.get("recoveryDependencyTrigger"));
        List<String> semantic=cn.bidcheck.compat.Java8Api.toList(stringList(trigger.get("semanticEvidenceKeys")).stream().distinct().sorted());
        if(!semantic.isEmpty())dependencies.put("sharedEvidence",fields("semanticEvidenceKeys",semantic,"targets",cn.bidcheck.compat.Java8Api.toList(stringList(context.get("recoveryTargetObligationIds")).stream().distinct().sorted())));
        List<String> visual=cn.bidcheck.compat.Java8Api.toList(maps(context.get("visualObservations")).stream().map(RecoveryInputs::observationKey).distinct().sorted());
        List<String> external=cn.bidcheck.compat.Java8Api.toList(maps(context.get("externalRecords")).stream().map(RecoveryInputs::externalRecordKey).distinct().sorted());
        if(!visual.isEmpty())dependencies.put("visual",visual);if(!external.isEmpty())dependencies.put("external",external);
        return dependencies.isEmpty()?"":hash(canonical(dependencies));
    }

    static List<String> targets(Finding finding,String strategy){
        Map<String,Object> atomic=atomic(finding);Set<String> completed=new HashSet<>(),pending=new LinkedHashSet<>();
        for(Map<String,Object> condition:maps(atomic.get("conditions"))){String id=text(condition,"id");if(cn.bidcheck.compat.Java8Api.isBlank(id))continue;
            if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())completed.add(id);
            else pending.add(id);
        }
        Set<String> selected=new TreeSet<>();
        for(Map<String,Object> action:maps(atomic.get("recoveryActions"))){
            if(!matches(strategy,text(action,"strategy")))continue;String id=text(action,"obligationId");
            if(!cn.bidcheck.compat.Java8Api.isBlank(id)&&!completed.contains(id))selected.add(id);
        }
        if("VERIFY_SOURCE_PAGES".equals(strategy))for(Map<String,Object> request:pendingVisual(finding)){
            String id=text(request,"obligationId");if(!cn.bidcheck.compat.Java8Api.isBlank(id)&&!completed.contains(id))selected.add(id);
        }
        if("QUERY_EXTERNAL".equals(strategy))for(Map<String,Object> request:maps(atomic.get("externalRequests"))){
            String id=text(request,"obligationId");if(!cn.bidcheck.compat.Java8Api.isBlank(id)&&!completed.contains(id))selected.add(id);
        }
        // Legacy reports may omit per-gap ids. Pending nodes are a safe fallback, never completed nodes.
        if(selected.isEmpty())selected.addAll(pending);
        return cn.bidcheck.compat.Java8Api.listCopyOf(selected);
    }
    private static boolean matches(String selected,String action){
        return selected.equals(action)||(java.util.Objects.equals((selected), "RECOMPILE_REQUIREMENT") || java.util.Objects.equals((selected), "REPAIR_REQUIREMENT") ? cn.bidcheck.compat.Java8Api.setOf("RECOMPILE_REQUIREMENT", "REPAIR_REQUIREMENT").contains(action) : (java.util.Objects.equals((selected), "VERIFY_SOURCE_PAGES") ? "OBSERVE_SOURCE_PAGE".equals(action) : (java.util.Objects.equals((selected), "TARGETED_EVIDENCE") ? cn.bidcheck.compat.Java8Api.setOf("RETRIEVE_EVIDENCE", "RESOLVE_SUBJECT", "RESOLVE_APPLICABILITY").contains(action) : false)));
    }
    static Map<String,Object> visualRequest(Finding before){
        Map<String,Object> out=new LinkedHashMap<>(atomic(before));Set<String> completed=new HashSet<>();
        for(Map<String,Object> condition:maps(out.get("conditions")))if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())completed.add(text(condition,"id"));
        out.put("visualRequests",cn.bidcheck.compat.Java8Api.toList(pendingVisual(before).stream().filter(r->cn.bidcheck.compat.Java8Api.isBlank(text(r,"obligationId"))||!completed.contains(text(r,"obligationId")))));
        return out;
    }
    private static List<Map<String,Object>> pendingVisual(Finding finding){
        TrustedVisualReview.Result review=TrustedVisualReview.inspect(map(finding.assessment()));
        return TrustedVisualReview.pendingRequests(atomic(finding),review.observations());
    }
    static Map<String,Object> externalRequest(Finding before){
        Map<String,Object> out=new LinkedHashMap<>(atomic(before));Set<String> completed=new HashSet<>();
        for(Map<String,Object> condition:maps(out.get("conditions")))if(cn.bidcheck.compat.Java8Api.setOf("PASS", "FAIL", "NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())completed.add(text(condition,"id"));
        out.put("externalRequests",cn.bidcheck.compat.Java8Api.toList(maps(out.get("externalRequests")).stream().filter(r->cn.bidcheck.compat.Java8Api.isBlank(text(r,"obligationId"))||!completed.contains(text(r,"obligationId")))));
        return out;
    }
    static List<Map<String,Object>> changedObservations(Object before,Object after){
        Set<String> seen=new HashSet<>();maps(before).forEach(o->seen.add(observationKey(o)));
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> observation:maps(after))if(seen.add(observationKey(observation)))out.add(observation);
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    static List<Map<String,Object>> changedExternalRecords(Object before,Object after){
        Set<String> seen=new HashSet<>();maps(before).forEach(record->seen.add(externalRecordKey(record)));
        List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> record:maps(after))if(seen.add(externalRecordKey(record)))out.add(record);
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }
    private static String externalRecordKey(Map<String,Object> record){return canonical(select(record,cn.bidcheck.compat.Java8Api.listOf("type", "subject", "source", "issuer", "effectiveAt", "evaluationStage", "coverage", "coverageComplete", "rawSnapshotHash", "result", "provenanceStatus")));}
    static void changedInputs(Map<String,Object> context,List<Map<String,Object>> added){
        Set<String> targets=new TreeSet<>();List<Map<String,Object>> locations=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Map<String,Object> observation:added){String id=text(observation,"obligationId");if(!cn.bidcheck.compat.Java8Api.isBlank(id))targets.add(id);
            Map<String,Object> source=map(observation.get("source"));Map<String,Object> location=fields("documentId",observation.getOrDefault("documentId",source.get("documentId")),
                "locationType",observation.getOrDefault("locationType","PAGE"),"location",observation.getOrDefault("location",observation.getOrDefault("page",source.get("page"))),
                "documentHash",source.getOrDefault("documentHash",""),"documentVersion",source.getOrDefault("documentVersion",0),"imageHash",observation.getOrDefault("imageHash",source.getOrDefault("renderedSha256","")));
            if(seen.add(canonical(location)))locations.add(location);
        }
        if(!targets.isEmpty())context.put("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listCopyOf(targets));
        context.put("recoveryChangedLocations",locations);context.put("recoveryNewObservationCount",added.size());
    }
    /** Match refreshed external facts to the original typed requests, not every unresolved clause. */
    static void changedExternalInputs(Map<String,Object> context,Finding before,List<Map<String,Object>> added){
        Set<String> targets=new TreeSet<>();
        for(Map<String,Object> request:maps(atomic(before).get("externalRequests")))for(Map<String,Object> record:added){
            if(text(request,"type").equals(text(record,"type"))&&text(request,"subject").equals(text(record,"subject"))
                &&text(request,"evaluationStage").equals(text(record,"evaluationStage"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(request,"obligationId")))targets.add(text(request,"obligationId"));
        }
        if(!targets.isEmpty())context.put("recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listCopyOf(targets));
        context.put("recoveryNewExternalRecordCount",added.size());context.put("recoveryDependencyRevision",dependencyKey(context));
    }
    /** Source hashes and actual observed values matter; receipt ids and timestamps do not. */
    private static String observationKey(Map<String,Object> observation){
        return canonical(select(observation,cn.bidcheck.compat.Java8Api.listOf("documentId", "locationType", "location", "page", "obligationId", "type", "label", "subject", "field", "value", "observedValue", "readability", "source", "sourceBbox", "observationIdentity", "requestedTask", "requestedFields", "imageHash")));
    }
    static String inputKey(Finding finding,String strategy){
        Map<String,Object> a=atomic(finding),plan=map(a.get("plan"));
        List<Object> rules=new ArrayList<>();for(Map<String,Object> rule:maps(plan.get("rules")))rules.add(ruleInput(rule));
        Map<String,Object> input=fields("logic",plan.getOrDefault("logic",""),"rules",rules,"requirementScope",plan.getOrDefault("requirementScope",cn.bidcheck.compat.Java8Api.mapOf()));
        if("VERIFY_SOURCE_PAGES".equals(strategy))input.put("visualRequests",cn.bidcheck.compat.Java8Api.toList(pendingVisual(finding).stream().map(r->select(r,cn.bidcheck.compat.Java8Api.listOf("documentId", "page", "locationType", "location", "task", "fields", "subject", "sourceIds"))).map(RecoveryInputs::canonical).sorted()));
        if("QUERY_EXTERNAL".equals(strategy))input.put("externalRequests",cn.bidcheck.compat.Java8Api.toList(maps(a.get("externalRequests")).stream().map(r->select(r,cn.bidcheck.compat.Java8Api.listOf("obligationId", "type", "subject", "evaluationStage", "referenceDate", "field", "operator", "expected"))).map(RecoveryInputs::canonical).sorted()));
        return canonical(input);
    }
    /** A repaired rule is routing progress, not proof that the bid meets the rule. */
    static Map<String,Object> requirementProgress(Finding before,Finding after){
        Map<String,Object> oldPlan=map(atomic(before).get("plan")),newPlan=map(atomic(after).get("plan"));
        Map<String,Map<String,Object>> oldRules=new LinkedHashMap<>(),newRules=new LinkedHashMap<>();
        boolean valid=index(maps(oldPlan.get("rules")),oldRules)&&index(maps(newPlan.get("rules")),newRules);
        List<String> repaired=new ArrayList<>(),expanded=new ArrayList<>();
        for(java.util.Map.Entry<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>> item:oldRules.entrySet()){
            Map<String,Object> original=item.getValue(),replacement=newRules.get(item.getKey());
            if(replacement==null||!text(original,"sourceQuote").equals(text(replacement,"sourceQuote"))){valid=false;continue;}
            if("UNSUPPORTED".equals(text(original,"predicate"))&&!cn.bidcheck.compat.Java8Api.isBlank(text(original,"sourceQuote"))
                &&cn.bidcheck.verification.engine.RequirementRepairPolicy.executableBusinessPredicate(text(replacement,"predicate")))repaired.add(item.getKey());
            if("UNSUPPORTED".equals(text(original,"predicate"))&&"GROUP".equals(text(replacement,"predicate"))
                &&maps(replacement.get("children")).stream().anyMatch(RecoveryInputs::containsBusinessRule))expanded.add(item.getKey());
        }
        int oldIssues=issueCount(oldPlan,oldRules),newIssues=issueCount(newPlan,newRules);
        Map<String,Object> repair=map(map(newPlan.get("requirementScope")).get("requirementRepair"));
        boolean sourceBindingProgress=oldRules.isEmpty()&&!newRules.isEmpty()&&"SCOPE_UNRESOLVED".equals(text(oldPlan,"method"))
            &&cn.bidcheck.compat.Java8Api.setOf("PUBLISHED", "SOURCE_RESTORED_ONLY").contains(text(repair,"state"))&&Boolean.TRUE.equals(repair.get("sourceRestored"))&&Boolean.TRUE.equals(repair.get("originalNodesPreserved"));
        boolean executableBusinessLeaf=maps(newPlan.get("rules")).stream().anyMatch(RecoveryInputs::containsBusinessRule);
        boolean sourceRestored=sourceBindingProgress&&"PUBLISHED".equals(text(repair,"state"))&&executableBusinessLeaf&&(!(newPlan.get("issues") instanceof Collection<?>)||((Collection<?>) (newPlan.get("issues"))).isEmpty());
        boolean branchExpanded=!expanded.isEmpty()&&"PUBLISHED".equals(text(repair,"state"))&&Boolean.TRUE.equals(repair.get("originalNodesPreserved"));
        boolean progressed=valid&&(sourceRestored||branchExpanded||!oldRules.isEmpty()&&!repaired.isEmpty()&&newIssues<oldIssues);
        return fields("progressMade",progressed,"repairedObligationIds",progressed?cn.bidcheck.compat.Java8Api.listCopyOf(repaired):cn.bidcheck.compat.Java8Api.listOf(),
            "expandedObligationIds",branchExpanded?cn.bidcheck.compat.Java8Api.listCopyOf(expanded):cn.bidcheck.compat.Java8Api.listOf(),"sourceBindingProgress",sourceBindingProgress,"ruleCompilationProgress",progressed,"sourceRestored",sourceRestored,"emptyTreeRestored",sourceRestored,"originalNodesPreserved",valid,"beforeUnresolvedNodes",oldIssues,"afterUnresolvedNodes",newIssues,"businessProof",false);
    }
    private static boolean containsBusinessRule(Map<String,Object> rule){List<Map<String,Object>> children=maps(rule.get("children"));return children.isEmpty()
        ?cn.bidcheck.verification.engine.RequirementRepairPolicy.executableBusinessPredicate(text(rule,"predicate")):children.stream().anyMatch(RecoveryInputs::containsBusinessRule);}
    private static boolean index(List<Map<String,Object>> rules,Map<String,Map<String,Object>> out){
        boolean valid=true;for(Map<String,Object> rule:rules){String id=text(rule,"id");if(cn.bidcheck.compat.Java8Api.isBlank(id)||out.putIfAbsent(id,rule)!=null)valid=false;if(!index(maps(rule.get("children")),out))valid=false;}return valid;
    }
    private static int issueCount(Map<String,Object> plan,Map<String,Map<String,Object>> rules){
        return (int)rules.values().stream().filter(r->"UNSUPPORTED".equals(text(r,"predicate"))).count()+(plan.get("issues") instanceof Collection<?>?((Collection<?>) (plan.get("issues"))).size():0);
    }
    private static Object ruleInput(Map<String,Object> rule){
        Map<String,Object> selected=select(rule,cn.bidcheck.compat.Java8Api.listOf("predicate", "subject", "field", "operator", "expected", "unit", "sourceQuote", "logic"));
        Map<String,Object> parameters=new TreeMap<>(map(rule.get("parameters")));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("obligationIdentity", "ruleId", "parentRuleId", "compilationIssue", "focusedScopeIssue", "requirementContextIssue", "repairReason"))parameters.remove(key);
        selected.put("parameters",parameters);selected.put("children",cn.bidcheck.compat.Java8Api.toList(maps(rule.get("children")).stream().map(RecoveryInputs::ruleInput)));return selected;
    }
    private static Map<String,Object> select(Map<String,Object> source,List<String> fields){Map<String,Object> out=new TreeMap<>();for(String field:fields)if(source.containsKey(field))out.put(field,source.get(field));return out;}
    private static List<String> stringList(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(v->Objects.toString(v,"")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v))):cn.bidcheck.compat.Java8Api.listOf();}
    private static String canonical(Object value){
        if(value instanceof Map<?,?>){Map<String,String> sorted=new TreeMap<>();((Map<?,?>) (value)).forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted.toString();}
        if(value instanceof Collection<?>)return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().map(RecoveryInputs::canonical)).toString();
        return Objects.toString(value,"");
    }
}

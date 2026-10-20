package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
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
        // A page observation changes an input to the rule; it does not invalidate all text already read.
        out.put("recoveryMode",Set.of("VERIFY_SOURCE_PAGES","REPAIR_REQUIREMENT","QUERY_EXTERNAL").contains(strategy)?"RECOMPUTE_ONLY":"TARGETED");
        if(Set.of("VERIFY_SOURCE_PAGES","REPAIR_REQUIREMENT","QUERY_EXTERNAL").contains(strategy))out.put("recoveryModelAllowed",false);
        return out;
    }

    static List<String> targets(Finding finding,String strategy){
        Map<String,Object> atomic=atomic(finding);Set<String> completed=new HashSet<>(),pending=new LinkedHashSet<>();
        for(Map<String,Object> condition:maps(atomic.get("conditions"))){String id=text(condition,"id");if(id.isBlank())continue;
            if(Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())completed.add(id);
            else pending.add(id);
        }
        Set<String> selected=new TreeSet<>();
        for(Map<String,Object> action:maps(atomic.get("recoveryActions"))){
            if(!matches(strategy,text(action,"strategy")))continue;String id=text(action,"obligationId");
            if(!id.isBlank()&&!completed.contains(id))selected.add(id);
        }
        if("VERIFY_SOURCE_PAGES".equals(strategy))for(Map<String,Object> request:maps(atomic.get("visualRequests"))){
            String id=text(request,"obligationId");if(!id.isBlank()&&!completed.contains(id))selected.add(id);
        }
        if("QUERY_EXTERNAL".equals(strategy))for(Map<String,Object> request:maps(atomic.get("externalRequests"))){
            String id=text(request,"obligationId");if(!id.isBlank()&&!completed.contains(id))selected.add(id);
        }
        // Legacy reports may omit per-gap ids. Pending nodes are a safe fallback, never completed nodes.
        if(selected.isEmpty())selected.addAll(pending);
        return List.copyOf(selected);
    }
    private static boolean matches(String selected,String action){
        return selected.equals(action)||switch(selected){
            case "VERIFY_SOURCE_PAGES"->"OBSERVE_SOURCE_PAGE".equals(action);
            case "TARGETED_EVIDENCE"->Set.of("RETRIEVE_EVIDENCE","RESOLVE_SUBJECT","RESOLVE_APPLICABILITY").contains(action);
            default->false;
        };
    }
    static Map<String,Object> visualRequest(Finding before){
        Map<String,Object> out=new LinkedHashMap<>(atomic(before));Set<String> completed=new HashSet<>();
        for(Map<String,Object> condition:maps(out.get("conditions")))if(Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())completed.add(text(condition,"id"));
        out.put("visualRequests",maps(out.get("visualRequests")).stream().filter(r->text(r,"obligationId").isBlank()||!completed.contains(text(r,"obligationId"))).toList());
        return out;
    }
    static Map<String,Object> externalRequest(Finding before){
        Map<String,Object> out=new LinkedHashMap<>(atomic(before));Set<String> completed=new HashSet<>();
        for(Map<String,Object> condition:maps(out.get("conditions")))if(Set.of("PASS","FAIL","NOT_APPLICABLE").contains(text(condition,"status"))&&maps(condition.get("gaps")).isEmpty())completed.add(text(condition,"id"));
        out.put("externalRequests",maps(out.get("externalRequests")).stream().filter(r->text(r,"obligationId").isBlank()||!completed.contains(text(r,"obligationId"))).toList());
        return out;
    }
    static List<Map<String,Object>> changedObservations(Object before,Object after){
        Set<String> seen=new HashSet<>();maps(before).forEach(o->seen.add(observationKey(o)));
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> observation:maps(after))if(seen.add(observationKey(observation)))out.add(observation);
        return List.copyOf(out);
    }
    static List<Map<String,Object>> changedExternalRecords(Object before,Object after){
        Set<String> seen=new HashSet<>();maps(before).forEach(record->seen.add(externalRecordKey(record)));
        List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> record:maps(after))if(seen.add(externalRecordKey(record)))out.add(record);
        return List.copyOf(out);
    }
    private static String externalRecordKey(Map<String,Object> record){return canonical(select(record,List.of("type","subject","source","issuer","effectiveAt","evaluationStage","coverage","coverageComplete","rawSnapshotHash","result","provenanceStatus")));}
    static void changedInputs(Map<String,Object> context,List<Map<String,Object>> added){
        Set<String> targets=new TreeSet<>();List<Map<String,Object>> locations=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Map<String,Object> observation:added){String id=text(observation,"obligationId");if(!id.isBlank())targets.add(id);
            Map<String,Object> source=map(observation.get("source"));Map<String,Object> location=fields("documentId",observation.getOrDefault("documentId",source.get("documentId")),
                "locationType",observation.getOrDefault("locationType","PAGE"),"location",observation.getOrDefault("location",observation.getOrDefault("page",source.get("page"))),
                "documentHash",source.getOrDefault("documentHash",""),"documentVersion",source.getOrDefault("documentVersion",0),"imageHash",observation.getOrDefault("imageHash",source.getOrDefault("renderedSha256","")));
            if(seen.add(canonical(location)))locations.add(location);
        }
        if(!targets.isEmpty())context.put("recoveryTargetObligationIds",List.copyOf(targets));
        context.put("recoveryChangedLocations",locations);context.put("recoveryNewObservationCount",added.size());
    }
    /** Source hashes and actual observed values matter; receipt ids and timestamps do not. */
    private static String observationKey(Map<String,Object> observation){
        return canonical(select(observation,List.of("documentId","locationType","location","page","obligationId","type","label","subject","field","value","observedValue","readability","source","sourceBbox","observationIdentity","requestedTask","requestedFields","imageHash")));
    }
    static String inputKey(Finding finding,String strategy){
        Map<String,Object> a=atomic(finding),plan=map(a.get("plan"));
        List<Object> rules=new ArrayList<>();for(Map<String,Object> rule:maps(plan.get("rules")))rules.add(ruleInput(rule));
        Map<String,Object> input=fields("logic",plan.getOrDefault("logic",""),"rules",rules,"requirementScope",plan.getOrDefault("requirementScope",Map.of()));
        if("VERIFY_SOURCE_PAGES".equals(strategy))input.put("visualRequests",maps(a.get("visualRequests")).stream().map(r->select(r,List.of("documentId","page","locationType","location","task","fields","subject","sourceIds"))).map(RecoveryInputs::canonical).sorted().toList());
        if("QUERY_EXTERNAL".equals(strategy))input.put("externalRequests",maps(a.get("externalRequests")).stream().map(r->select(r,List.of("obligationId","type","subject","evaluationStage","referenceDate","field","operator","expected"))).map(RecoveryInputs::canonical).sorted().toList());
        return canonical(input);
    }
    /** A repaired rule is routing progress, not proof that the bid meets the rule. */
    static Map<String,Object> requirementProgress(Finding before,Finding after){
        Map<String,Object> oldPlan=map(atomic(before).get("plan")),newPlan=map(atomic(after).get("plan"));
        Map<String,Map<String,Object>> oldRules=new LinkedHashMap<>(),newRules=new LinkedHashMap<>();
        boolean valid=index(maps(oldPlan.get("rules")),oldRules)&&index(maps(newPlan.get("rules")),newRules);
        List<String> repaired=new ArrayList<>();
        for(var item:oldRules.entrySet()){
            Map<String,Object> original=item.getValue(),replacement=newRules.get(item.getKey());
            if(replacement==null||!text(original,"sourceQuote").equals(text(replacement,"sourceQuote"))){valid=false;continue;}
            if("UNSUPPORTED".equals(text(original,"predicate"))&&!text(original,"sourceQuote").isBlank()
                &&!Set.of("","UNSUPPORTED","GROUP","INPUT","REFERENCE_TIME","NOT_APPLICABLE").contains(text(replacement,"predicate")))repaired.add(item.getKey());
        }
        int oldIssues=issueCount(oldPlan,oldRules),newIssues=issueCount(newPlan,newRules);
        boolean progressed=valid&&!oldRules.isEmpty()&&!repaired.isEmpty()&&newIssues<oldIssues;
        return fields("progressMade",progressed,"repairedObligationIds",progressed?List.copyOf(repaired):List.of(),
            "originalNodesPreserved",valid,"beforeUnresolvedNodes",oldIssues,"afterUnresolvedNodes",newIssues,"businessProof",false);
    }
    private static boolean index(List<Map<String,Object>> rules,Map<String,Map<String,Object>> out){
        boolean valid=true;for(Map<String,Object> rule:rules){String id=text(rule,"id");if(id.isBlank()||out.putIfAbsent(id,rule)!=null)valid=false;if(!index(maps(rule.get("children")),out))valid=false;}return valid;
    }
    private static int issueCount(Map<String,Object> plan,Map<String,Map<String,Object>> rules){
        return (int)rules.values().stream().filter(r->"UNSUPPORTED".equals(text(r,"predicate"))).count()+(plan.get("issues") instanceof Collection<?> issues?issues.size():0);
    }
    private static Object ruleInput(Map<String,Object> rule){
        Map<String,Object> selected=select(rule,List.of("predicate","subject","field","operator","expected","unit","sourceQuote","logic"));
        Map<String,Object> parameters=new TreeMap<>(map(rule.get("parameters")));
        for(String key:List.of("obligationIdentity","ruleId","parentRuleId","compilationIssue","focusedScopeIssue","requirementContextIssue","repairReason"))parameters.remove(key);
        selected.put("parameters",parameters);selected.put("children",maps(rule.get("children")).stream().map(RecoveryInputs::ruleInput).toList());return selected;
    }
    private static Map<String,Object> select(Map<String,Object> source,List<String> fields){Map<String,Object> out=new TreeMap<>();for(String field:fields)if(source.containsKey(field))out.put(field,source.get(field));return out;}
    private static String canonical(Object value){
        if(value instanceof Map<?,?> object){Map<String,String> sorted=new TreeMap<>();object.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted.toString();}
        if(value instanceof Collection<?> all)return all.stream().map(RecoveryInputs::canonical).toList().toString();
        return Objects.toString(value,"");
    }
}

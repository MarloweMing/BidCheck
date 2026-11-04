package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Limits new retrieval/extraction; the full original tree is still evaluated using revalidated observations. */
public final class RecoveryTargets {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private RecoveryTargets() {}
    static boolean active(Map<String,Object> context){return !cn.bidcheck.compat.Java8Api.isBlank(str(context,"recoveryStrategy"))||cn.bidcheck.compat.Java8Api.setOf("TARGETED", "RECOMPUTE_ONLY", "REPAIR_REQUIREMENT").contains(str(context,"recoveryMode"));}
    static List<Rule> select(List<Rule> roots,List<Rule> executable,Map<String,Object> context){
        if(!active(context))return executable;
        // Old persisted resume contexts predate target ids. Their existing unresolved-rule logic
        // remains valid; an explicitly supplied empty/unknown target set must never widen scope.
        if(!context.containsKey("recoveryTargetObligationIds"))return executable;
        Set<String> requested=new HashSet<>(ProofVerifier.strings(context.get("recoveryTargetObligationIds"))),selected=new HashSet<>();
        for(Rule root:roots)visit(root,requested,selected,false);
        // A scoring business condition owns several typed reads. Select only the reads
        // regenerated from that exact verified formula, never arbitrary metadata links.
        SourceScoringRules.readParents(roots,executable).forEach((read,parent)->{if(selected.contains(parent)||requested.contains(read))selected.add(read);});
        List<Rule> targets=cn.bidcheck.compat.Java8Api.toList(executable.stream().filter(r->selected.contains(r.id())));
        Set<String> subjects=new HashSet<>();for(Rule target:targets){add(subjects,target.subject());add(subjects,str(target.parameters(),"employer"));}
        // Role and employer links are prerequisites. A second unrelated material/certificate/price
        // condition sharing the same person is not a prerequisite and cannot broaden this request.
        for(Rule candidate:executable){
            if(candidate.predicate().equals("PERSON_ROLE")&&subjects.contains(PersonnelRoles.canonical(candidate.subject())))selected.add(candidate.id());
            if(candidate.predicate().equals("RELATION")&&subjects.contains(PersonnelRoles.canonical(candidate.subject()))
                &&(DomainBindings.role(candidate.subject())||DomainBindings.role(str(candidate.parameters(),"object"))||DomainBindings.employment(candidate.field())))selected.add(candidate.id());
        }
        return cn.bidcheck.compat.Java8Api.toList(executable.stream().filter(r->selected.contains(r.id())));
    }
    /** Service-layer input identities use exactly the same source-reparsed read rules as execution. */
    public static Map<String,Map<String,Object>> readDependencies(Object rawRules){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        try{for(Map<String,Object> raw:RuleEvaluator.maps(rawRules))readDependencies(JSON.convertValue(raw,Rule.class),result);}
        catch(IllegalArgumentException invalid){return Collections.emptyMap();}
        return result;
    }
    /** Resolve acquisition capability from the same executable rules used by the engine.
     * Unknown legacy ids remain compatible; known non-executable suffixes do not create work. */
    public static Map<String,String> unavailableEvidenceTargets(Object rawRules, Object rawConditions,
            Collection<String> targets) {
        List<Rule> roots = new ArrayList<>();
        try {
            for (Map<String,Object> raw : RuleEvaluator.maps(rawRules)) roots.add(JSON.convertValue(raw, Rule.class));
        } catch (IllegalArgumentException invalid) {
            return Collections.emptyMap();
        }
        if (roots.isEmpty()) return Collections.emptyMap();
        List<Rule> executable = VerificationEngine.evidenceRules(roots);
        Map<String,String> parents = SourceScoringRules.readParents(roots, executable);
        Map<String,Map<String,Object>> conditions = new HashMap<>();
        for (Map<String,Object> condition : RuleEvaluator.maps(rawConditions))
            conditions.put(str(condition, "id"), condition);
        Map<String,String> result = new LinkedHashMap<>();
        for (String target : targets) {
            Map<String,Object> context = linked("recoveryStrategy", "TARGETED_EVIDENCE",
                "recoveryTargetObligationIds", Collections.singletonList(target));
            List<Rule> selected = select(roots, executable, context);
            if (selected.isEmpty()) {
                if (knownTarget(roots, target)) result.put(target, "NO_EXECUTABLE_EVIDENCE_TARGET");
                continue;
            }
            boolean capable = false;
            boolean requirementSourceMissing = false;
            for (Rule rule : selected) {
                if (AgentInvestigationScope.kinds(rule).isEmpty()) continue;
                Map<String,Object> condition = conditions.getOrDefault(
                    parents.getOrDefault(rule.id(), rule.id()), Collections.emptyMap());
                Object proof = condition.get("proof");
                if (proof instanceof Map<?,?> && AgentInvestigationScope.awaitingRequirementSource(
                        (Map<String,Object>) proof)) {
                    requirementSourceMissing = true;
                } else {
                    capable = true;
                }
            }
            if (!capable) result.put(target, requirementSourceMissing
                ? "REQUIREMENT_SOURCE_INPUT_REQUIRED" : "NO_EXECUTABLE_EVIDENCE_TARGET");
        }
        return result;
    }

    private static boolean knownTarget(List<Rule> rules, String target) {
        for (Rule rule : rules) {
            if (target.equals(rule.id()) || target.startsWith(rule.id() + ":")
                    || knownTarget(rule.children(), target)) return true;
        }
        return false;
    }
    private static void readDependencies(Rule root,Map<String,Map<String,Object>> result){
        List<Rule> reads=SourceScoringRules.evidenceRules(root);
        Map<String,String> parents=SourceScoringRules.readParents(Collections.singletonList(root),reads);
        for(Rule read:reads)if(parents.containsKey(read.id()))result.put(read.id(),linked("parentId",root.id(),"rule",JSON.convertValue(read,Map.class)));
        root.children().forEach(child->readDependencies(child,result));
    }
    private static void add(Set<String> subjects,String value){if(!cn.bidcheck.compat.Java8Api.isBlank(value))subjects.add(PersonnelRoles.canonical(value));}
    private static boolean visit(Rule rule,Set<String> requested,Set<String> selected,boolean parentSelected){
        boolean direct=parentSelected||requested.contains(rule.id())||requested.contains(rule.id()+"::own")||requested.stream().anyMatch(id->id.startsWith(rule.id()+":subject:"));
        boolean descendant=false;for(Rule child:rule.children())descendant|=visit(child,requested,selected,direct);
        if(direct){selected.add(rule.id());if(RuleObligations.hasOwnPredicate(rule))selected.add(rule.id()+"::own");}
        if(descendant){
            if(RuleObligations.hasOwnPredicate(rule))selected.add(rule.id()+"::own");
            if("CONDITIONAL".equals(rule.logic())&&!rule.children().isEmpty())all(rule.children().get(0),selected);
        }
        return direct||descendant;
    }
    private static void all(Rule rule,Set<String> selected){selected.add(rule.id());if(RuleObligations.hasOwnPredicate(rule))selected.add(rule.id()+"::own");rule.children().forEach(child->all(child,selected));}
}

package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Limits new retrieval/extraction; the full original tree is still evaluated using revalidated observations. */
final class RecoveryTargets {
    private RecoveryTargets() {}
    static boolean active(Map<String,Object> context){return !str(context,"recoveryStrategy").isBlank()||Set.of("TARGETED","RECOMPUTE_ONLY","REPAIR_REQUIREMENT").contains(str(context,"recoveryMode"));}
    static List<Rule> select(List<Rule> roots,List<Rule> executable,Map<String,Object> context){
        if(!active(context)||RecoveryReplay.localOnly(context))return executable;
        // Old persisted resume contexts predate target ids. Their existing unresolved-rule logic
        // remains valid; an explicitly supplied empty/unknown target set must never widen scope.
        if(!context.containsKey("recoveryTargetObligationIds"))return executable;
        Set<String> requested=new HashSet<>(ProofVerifier.strings(context.get("recoveryTargetObligationIds"))),selected=new HashSet<>();
        for(Rule root:roots)visit(root,requested,selected,false);
        List<Rule> targets=executable.stream().filter(r->selected.contains(r.id())).toList();
        Set<String> subjects=new HashSet<>();for(Rule target:targets){add(subjects,target.subject());add(subjects,str(target.parameters(),"employer"));}
        // Role and employer links are prerequisites. A second unrelated material/certificate/price
        // condition sharing the same person is not a prerequisite and cannot broaden this request.
        for(Rule candidate:executable){
            if(candidate.predicate().equals("PERSON_ROLE")&&subjects.contains(PersonnelRoles.canonical(candidate.subject())))selected.add(candidate.id());
            if(candidate.predicate().equals("RELATION")&&subjects.contains(PersonnelRoles.canonical(candidate.subject()))
                &&(DomainBindings.role(candidate.subject())||DomainBindings.role(str(candidate.parameters(),"object"))||DomainBindings.employment(candidate.field())))selected.add(candidate.id());
        }
        return executable.stream().filter(r->selected.contains(r.id())).toList();
    }
    private static void add(Set<String> subjects,String value){if(!value.isBlank())subjects.add(PersonnelRoles.canonical(value));}
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

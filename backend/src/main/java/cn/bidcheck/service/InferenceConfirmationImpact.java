package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Finding;
import java.util.*;
import static cn.bidcheck.service.BidService.*;
import static cn.bidcheck.service.CheckResolution.*;

/** Computes conservative invalidation only. A dependency match never proves a business fact. */
public final class InferenceConfirmationImpact {
    private InferenceConfirmationImpact() {}
    public static Set<String> affected(List<Finding> findings,List<Map<String,Object>> priorRecords,
            List<Map<String,Object>> currentRecords,List<Map<String,Object>> decisionRows){
        Map<String,Integer> prior=versions(priorRecords),current=versions(currentRecords);List<Map<String,Object>> changes=new ArrayList<>();
        for(Map<String,Object> decision:decisionRows){String id=text(decision,"id");int revision=integer(decision.get("revision"));
            if(!"CONFIRMED".equals(text(decision,"status"))||revision<0||!Objects.equals(current.get(id),revision)||Objects.equals(prior.get(id),revision))continue;
            Map<String,Object> fact=map(decision.get("proposedFact"));List<Map<String,Object>> sources=maps(decision.get("evidences")).stream().filter(e->!text(e,"sourceId").isBlank()).map(e->{Map<String,Object> source=new LinkedHashMap<>(e);source.put("id",e.get("sourceId"));return source;}).toList();
            if(fact.isEmpty()||sources.isEmpty())continue;
            changes.add(fields("itemId",id+":"+revision,"recordType","FACT","fact",fact,"sources",sources));
        }
        if(changes.isEmpty())return Set.of();Set<String> affected=new LinkedHashSet<>();
        for(Finding finding:findings){Map<String,Object> atomic=atomic(finding);if(atomic.isEmpty()||unresolved(finding))continue;
            List<Map<String,Object>> dependencies=maps(atomic.get("recoveryEvidenceDependencies"));
            // Historical results lacking a trustworthy observation index cannot certify independence.
            boolean incomplete=dependencies.isEmpty()||dependencies.stream().anyMatch(d->text(d,"obligationId").isBlank()||text(d,"observationKey").isBlank()||map(d.get("target")).isEmpty());
            if(incomplete||changes.stream().anyMatch(change->dependencies.stream().anyMatch(d->OrdinaryRecoveryDependencies.matches(d,change)||sourceOverlap(d,change))
                    ||identityAggregate(change,atomic)))affected.add(finding.checkpoint().id());
        }
        return Set.copyOf(affected);
    }
    private static boolean sourceOverlap(Map<String,Object> dependency,Map<String,Object> change){Set<String> observed=new HashSet<>(strings(dependency.get("sourceIds")));return !observed.isEmpty()&&maps(change.get("sources")).stream().anyMatch(s->observed.contains(text(s,"id")));}
    private static boolean identityAggregate(Map<String,Object> change,Map<String,Object> atomic){Map<String,Object> fact=map(change.get("fact"));if(!Set.of("PERSON_ROLE","RELATION").contains(text(fact,"kind")))return false;
        // Identity/ownership can alter a distinct-person count or qualified/performance aggregate,
        // including members omitted from the old resolvedSubjects projection.
        ArrayDeque<Map<String,Object>> rules=new ArrayDeque<>(maps(map(atomic.get("plan")).get("rules")));
        while(!rules.isEmpty()){Map<String,Object> rule=rules.removeFirst();if(Set.of("PERSON_COUNT","ENTITY_COUNT","SCORE_COUNT").contains(text(rule,"predicate"))||"FOR_EACH".equals(text(rule,"logic")))return true;rules.addAll(maps(rule.get("children")));}
        return maps(atomic.get("recoveryEvidenceDependencies")).stream().map(d->map(d.get("target"))).anyMatch(t->Set.of("PERSON_COUNT","ENTITY_COUNT","SCORE_COUNT").contains(text(t,"predicate")));
    }
    private static Map<String,Integer> versions(List<Map<String,Object>> records){Map<String,Integer> result=new HashMap<>();for(Map<String,Object> row:records)if(!text(row,"id").isBlank()&&integer(row.get("revision"))>=0)result.put(text(row,"id"),integer(row.get("revision")));return result;}
    private static int integer(Object value){return value instanceof Number n&&n.doubleValue()==n.intValue()?n.intValue():-1;}
    private static List<String> strings(Object value){return value instanceof Collection<?> all?all.stream().filter(String.class::isInstance).map(String.class::cast).toList():List.of();}
}

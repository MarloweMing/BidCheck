package cn.bidcheck.verification.facts;

import cn.bidcheck.verification.engine.PersonnelRoles;
import java.util.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Source-scoped observation receipts. A negative observation is never proof of document absence. */
public final class FieldCoverage {
    public static final String POLICY="typed-source-observation-1";
    public static final Set<String> STATES=Set.of("UNREAD","READ_NOT_FOUND","EXTRACTED","FAILED");
    private FieldCoverage() {}
    // Only these observation keys change: older PERSON_COUNT negative reads used an incompatible
    // fact kind and cannot suppress reading a current appointment. All other keys remain stable.
    public static String key(Map<String,Object> target){return hash(POLICY+("PERSON_COUNT".equals(text(target,"predicate"))?"person-count-appointment-observation-1":"")+canonical(target));}
    public static boolean completeRead(Map<String,Object> receipt){return Boolean.TRUE.equals(receipt.get("sourceScopeComplete"))&&Set.of("EXTRACTED","READ_NOT_FOUND").contains(text(receipt,"status"));}
    public static boolean matches(Map<String,Object> target,Map<String,Object> fact){
        String predicate=text(target,"predicate"),kind=text(fact,"kind");
        if(predicate.equals("PERSON_COUNT"))return appointmentObserved(target,fact);
        Map<String,Object> parameters=map(target.get("parameters"));
        if(predicate.equals("PRICE_RECONCILE")){Set<String> fields=new LinkedHashSet<>(strings(parameters.get("componentFields")));fields.add(text(parameters,"totalField"));fields.add(text(target,"field"));fields.remove("");return Set.of("NUMBER","FIELD").contains(kind)&&fields.contains(text(fact,"field"))&&(text(target,"subject").isBlank()||text(target,"subject").equals(text(fact,"subject")));}
        if(predicate.equals("DATE_RELATION")){Set<String> fields=new LinkedHashSet<>(List.of(text(parameters,"leftRole"),text(parameters,"rightRole"),text(target,"field")));fields.remove("");return kind.equals("FIELD")&&fields.contains(text(fact,"field"))&&(text(target,"subject").isBlank()||text(target,"subject").equals(text(fact,"subject")));}
        boolean compatible=switch(predicate){case "VALIDITY"->Set.of("CERTIFICATE","DATE_RANGE").contains(kind);case "SAME_FIELD"->Set.of("FIELD","NUMBER","CERTIFICATE").contains(kind);default->predicate.equals(kind);};
        return compatible&&(text(target,"subject").isBlank()||text(target,"subject").equals(text(fact,"subject")))
            &&(text(target,"field").isBlank()||text(target,"field").equals(text(fact,"field")))
            &&(text(target,"unit").isBlank()||text(target,"unit").equals(text(fact,"unit")));
    }
    private static boolean appointmentObserved(Map<String,Object> target,Map<String,Object> fact){
        if(!"PERSON_ROLE".equals(text(fact,"kind"))||!"CURRENT_PROJECT".equals(text(fact,"scope"))
            ||!Set.of("人","名").contains(text(target,"unit"))||text(fact,"value").isBlank()
            ||strings(fact.get("sourceIds")).isEmpty()||!PersonnelRoles.sameObservationRole(text(target,"subject"),text(fact,"subject")))return false;
        Map<String,Object> parameters=map(target.get("parameters")),attributes=map(fact.get("attributes"));
        // This receipt says that an appointment was extracted, not that it is qualified, unique,
        // sufficient in number, or linked to a certificate. Keep qualified-count targets distinct.
        if(Boolean.TRUE.equals(parameters.get("qualificationRequired"))||"QUALIFIED".equals(text(parameters,"countBasis"))
            ||List.of("category","grade","profession").stream().anyMatch(k->!text(parameters,k).isBlank())
            ||text(target,"field").matches("(?i).*(?:qualified|合格|持证).*"))return false;
        for(String dimension:List.of("employer","lot"))if(!text(parameters,dimension).isBlank()
            &&!text(parameters,dimension).equals(text(attributes,dimension)))return false;
        return true;
    }
    /** Validate each field independently. Rejected unrelated fields cannot erase a verified observation. */
    static List<Map<String,Object>> validate(Object raw,Map<String,Map<String,Object>> sources,List<Map<String,Object>> entries){
        if(maps(raw).size()>120)throw new IllegalArgumentException("FACT_FIELD_COVERAGE_LIMIT");
        Map<String,Map<String,Object>> facts=new HashMap<>();for(Map<String,Object> entry:entries){String id=text(entry,"originalFactId");if(!id.isBlank())facts.put(id,map(entry.get("fact")));}
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:maps(raw)){
            Map<String,Object> target=map(row.get("target"));String status=text(row,"status");List<String> refs=strings(row.get("sourceIds")),ids=strings(row.get("factIds"));
            if(!POLICY.equals(text(row,"observationPolicy"))||!STATES.contains(status)||text(row,"modelIdentity").isBlank()||target.isEmpty()
                ||!key(target).equals(text(row,"observationKey"))||canonical(row).length()>64000||refs.isEmpty()||refs.size()>128||refs.stream().distinct().count()!=refs.size()||!sources.keySet().containsAll(refs))continue;
            if(ids.stream().distinct().count()!=ids.size()||!facts.keySet().containsAll(ids))continue;
            if("EXTRACTED".equals(status)){
                if(ids.isEmpty()||ids.stream().anyMatch(id->!matches(target,facts.get(id))||!refs.containsAll(strings(facts.get(id).get("sourceIds")))))continue;
            }else if(!ids.isEmpty()||"READ_NOT_FOUND".equals(status)&&!Boolean.TRUE.equals(row.get("sourceScopeComplete")))continue;
            Map<String,Object> value=copy(row);value.put("absenceProven",false);value.put("scope","SELECTED_SOURCES_ONLY");result.add(value);
        }
        return result;
    }
}

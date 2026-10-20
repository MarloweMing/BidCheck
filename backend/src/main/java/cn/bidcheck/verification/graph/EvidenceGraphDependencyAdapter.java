package cn.bidcheck.verification.graph;

import cn.bidcheck.checktemplate.EvidenceNeed;
import cn.bidcheck.checktemplate.TemplateTypes;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Converts graph deltas to the existing template evidence-need keys used by incremental replay. */
public final class EvidenceGraphDependencyAdapter {
    private EvidenceGraphDependencyAdapter() { }
    private static final Map<TemplateTypes.NeedKind,Set<String>> PREDICATES=predicates();

    public static Set<String> affectedNeedKeys(Collection<EvidenceNeed> needs,Set<String> changedGraphKeys) {
        if(needs==null||needs.isEmpty()||changedGraphKeys==null||changedGraphKeys.isEmpty())return Set.of();
        Set<String> changedPredicates=new LinkedHashSet<>();boolean entityChanged=false;
        for(String key:changedGraphKeys)if(key!=null){
            if(key.startsWith("graph:predicate:"))changedPredicates.add(key.substring("graph:predicate:".length()));
            if(key.startsWith("graph:entity:")||key.startsWith("graph:edge:"))entityChanged=true;
        }
        LinkedHashSet<String> affected=new LinkedHashSet<>();
        for(EvidenceNeed need:needs){Set<String> relevant=PREDICATES.getOrDefault(need.kind(),Set.of());
            if(!java.util.Collections.disjoint(relevant,changedPredicates)||(entityChanged&&identitySensitive(need.kind())))affected.add(need.key());
        }
        return Set.copyOf(affected);
    }

    /** Reads the persisted TemplateDependencyGraph shape after a run has published its fragments. */
    public static Set<String> affectedNeedKeys(Map<String,Object> dependencyGraph,Set<String> changedGraphKeys) {
        if(dependencyGraph==null||dependencyGraph.isEmpty())return Set.of();LinkedHashSet<String> affected=new LinkedHashSet<>();
        for(Map<String,Object> node:maps(dependencyGraph.get("nodes"))){Map<String,Object> attributes=map(node.get("attributes"));
            String needKey=Objects.toString(attributes.get("needKey"),"").trim(),kind=Objects.toString(attributes.get("kind"),"").trim();if(needKey.isBlank()||kind.isBlank())continue;
            try{TemplateTypes.NeedKind needKind=TemplateTypes.NeedKind.valueOf(kind);EvidenceNeed synthetic=new EvidenceNeed(needKey,needKind,cn.bidcheck.checktemplate.SubjectSelector.bidder(),Set.of(),Map.of(),true,false);affected.addAll(affectedNeedKeys(List.of(synthetic),changedGraphKeys));}catch(IllegalArgumentException ignored){/* old dependency node */}
        }return Set.copyOf(affected);
    }

    private static boolean identitySensitive(TemplateTypes.NeedKind kind){return switch(kind){case PERSONNEL,ROSTER_SCOPE,CERTIFICATE->true;default->false;};}
    private static Map<TemplateTypes.NeedKind,Set<String>> predicates(){EnumMap<TemplateTypes.NeedKind,Set<String>> out=new EnumMap<>(TemplateTypes.NeedKind.class);
        out.put(TemplateTypes.NeedKind.PERSONNEL,Set.of("APPOINTED_TO","SERVED_AS","EMPLOYED_BY","INSURED_BY","MEMBER_OF","LEADS","RESPONSIBLE_FOR"));
        out.put(TemplateTypes.NeedKind.ROSTER_SCOPE,out.get(TemplateTypes.NeedKind.PERSONNEL));
        out.put(TemplateTypes.NeedKind.CERTIFICATE,Set.of("HOLDS_CERTIFICATE","REGISTERED_WITH","EMPLOYED_BY"));
        out.put(TemplateTypes.NeedKind.MATERIAL,Set.of("MEMBER_OF","LEADS","RESPONSIBLE_FOR","PARTY_TO","CONTRACT_FOR","BIDDER_FOR"));
        return Map.copyOf(out);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> raw?(Map<String,Object>)raw:Map.of();}
    private static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?> rows?rows.stream().filter(Map.class::isInstance).map(EvidenceGraphDependencyAdapter::map).toList():List.of();}
}

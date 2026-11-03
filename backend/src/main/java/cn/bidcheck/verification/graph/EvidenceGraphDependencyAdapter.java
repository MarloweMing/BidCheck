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
        if(needs==null||needs.isEmpty()||changedGraphKeys==null||changedGraphKeys.isEmpty())return cn.bidcheck.compat.Java8Api.setOf();
        Set<String> changedPredicates=new LinkedHashSet<>();boolean entityChanged=false;
        for(String key:changedGraphKeys)if(key!=null){
            if(key.startsWith("graph:predicate:"))changedPredicates.add(key.substring("graph:predicate:".length()));
            if(key.startsWith("graph:entity:")||key.startsWith("graph:edge:"))entityChanged=true;
        }
        LinkedHashSet<String> affected=new LinkedHashSet<>();
        for(EvidenceNeed need:needs){Set<String> relevant=PREDICATES.getOrDefault(need.kind(),cn.bidcheck.compat.Java8Api.setOf());
            if(!java.util.Collections.disjoint(relevant,changedPredicates)||(entityChanged&&identitySensitive(need.kind())))affected.add(need.key());
        }
        return cn.bidcheck.compat.Java8Api.setCopyOf(affected);
    }

    /** Reads the persisted TemplateDependencyGraph shape after a run has published its fragments. */
    public static Set<String> affectedNeedKeys(Map<String,Object> dependencyGraph,Set<String> changedGraphKeys) {
        if(dependencyGraph==null||dependencyGraph.isEmpty())return cn.bidcheck.compat.Java8Api.setOf();LinkedHashSet<String> affected=new LinkedHashSet<>();
        for(Map<String,Object> node:maps(dependencyGraph.get("nodes"))){Map<String,Object> attributes=map(node.get("attributes"));
            String needKey=Objects.toString(attributes.get("needKey"),"").trim(),kind=Objects.toString(attributes.get("kind"),"").trim();if(cn.bidcheck.compat.Java8Api.isBlank(needKey)||cn.bidcheck.compat.Java8Api.isBlank(kind))continue;
            try{TemplateTypes.NeedKind needKind=TemplateTypes.NeedKind.valueOf(kind);EvidenceNeed synthetic=new EvidenceNeed(needKey,needKind,cn.bidcheck.checktemplate.SubjectSelector.bidder(),cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.mapOf(),true,false);affected.addAll(affectedNeedKeys(cn.bidcheck.compat.Java8Api.listOf(synthetic),changedGraphKeys));}catch(IllegalArgumentException ignored){/* old dependency node */}
        }return cn.bidcheck.compat.Java8Api.setCopyOf(affected);
    }

    private static boolean identitySensitive(TemplateTypes.NeedKind kind){switch (kind) {case PERSONNEL:case ROSTER_SCOPE:case CERTIFICATE:return true;default:return false;}}
    private static Map<TemplateTypes.NeedKind,Set<String>> predicates(){EnumMap<TemplateTypes.NeedKind,Set<String>> out=new EnumMap<>(TemplateTypes.NeedKind.class);
        out.put(TemplateTypes.NeedKind.PERSONNEL,cn.bidcheck.compat.Java8Api.setOf("APPOINTED_TO", "SERVED_AS", "EMPLOYED_BY", "INSURED_BY", "MEMBER_OF", "LEADS", "RESPONSIBLE_FOR"));
        out.put(TemplateTypes.NeedKind.ROSTER_SCOPE,out.get(TemplateTypes.NeedKind.PERSONNEL));
        out.put(TemplateTypes.NeedKind.CERTIFICATE,cn.bidcheck.compat.Java8Api.setOf("HOLDS_CERTIFICATE", "REGISTERED_WITH", "EMPLOYED_BY"));
        out.put(TemplateTypes.NeedKind.MATERIAL,cn.bidcheck.compat.Java8Api.setOf("MEMBER_OF", "LEADS", "RESPONSIBLE_FOR", "PARTY_TO", "CONTRACT_FOR", "BIDDER_FOR"));
        return cn.bidcheck.compat.Java8Api.mapCopyOf(out);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)((Map<?,?>) (value)):cn.bidcheck.compat.Java8Api.mapOf();}
    private static List<Map<String,Object>> maps(Object value){return value instanceof Collection<?>?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(Map.class::isInstance).map(EvidenceGraphDependencyAdapter::map)):cn.bidcheck.compat.Java8Api.listOf();}
}

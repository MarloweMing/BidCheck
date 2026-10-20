package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Size the actual wire payload. Source windows and individual typed context records stay intact. */
final class LegacyExtractionBatches {
    static final String POLICY="object-field-source-packets-2";
    static final int MAX_PAYLOAD_CHARS=70_000;
    private static final ObjectMapper JSON=new ObjectMapper();
    private LegacyExtractionBatches(){}
    record Plan(List<Map<String,Object>> batches,List<Map<String,Object>> blocked,List<Map<String,Object>> coverage){
        Map<String,Object> audit(){return linked("policy",POLICY,"limit",MAX_PAYLOAD_CHARS,"batches",batches.size(),
            "packets",batches.stream().map(b->linked("packetId",b.get("packetId"),"payloadChars",size(b),"obligationIds",rules(b).stream().map(Rule::id).toList(),"sourceIds",sourceIds(b),"contextPartition",b.get("contextPartition"))).toList(),
            "coverage",coverage,"blocked",blocked,"sourceSetPreserved",blocked.isEmpty());}
    }
    static int size(Map<String,Object> input){try{return JSON.writeValueAsString(Map.of("operation","EXTRACT","input",input)).length();}catch(Exception e){throw new IllegalArgumentException("EXTRACTION_INPUT_SERIALIZATION_INVALID",e);}}
    static List<Map<String,Object>> inputs(Map<String,Object> input,Object strategy){return plan(input,strategy,Map.of()).batches();}
    /** pending maps rule IDs to unread source IDs; a missing key means all supplied sources. */
    static Plan plan(Map<String,Object> input,Object strategy,Map<String,Set<String>> pending){
        List<Rule> rules=rules(input);List<Map<String,Object>> sources=RuleEvaluator.maps(input.get("sources"));
        Set<String> unique=new HashSet<>();for(Map<String,Object> source:sources)if(str(source,"id").isBlank()||!unique.add(str(source,"id")))throw new IllegalArgumentException("EXTRACTION_SOURCE_ID_INVALID");
        Map<String,List<Rule>> groups=new LinkedHashMap<>();
        for(Rule rule:rules){Set<String> ids=pending.getOrDefault(rule.id(),unique);String key=("SPLIT_FAILED_REQUEST".equals(strategy)?rule.id():rule.subject())+":"+new TreeSet<>(ids);groups.computeIfAbsent(key,k->new ArrayList<>()).add(rule);}
        List<Map<String,Object>> batches=new ArrayList<>(),blocked=new ArrayList<>(),coverage=new ArrayList<>();
        for(List<Rule> group:groups.values()){
            Set<String> allowed=pending.getOrDefault(group.get(0).id(),unique);List<Map<String,Object>> selected=sources.stream().filter(s->allowed.contains(str(s,"id"))).toList();
            if(selected.isEmpty())continue;
            packRules(input,group,selected,batches,blocked);
        }
        for(Rule rule:rules){Set<String> expected=pending.getOrDefault(rule.id(),unique);Set<String> sent=new LinkedHashSet<>();for(Map<String,Object> batch:batches)if(rules(batch).stream().anyMatch(r->r.id().equals(rule.id())))sent.addAll(sourceIds(batch));
            Set<String> unplanned=new TreeSet<>(expected);unplanned.removeAll(sent);coverage.add(linked("obligationId",rule.id(),"observationKey",cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(rule)),"requestedSourceIds",new TreeSet<>(expected),"plannedSourceIds",sent,"unplannedSourceIds",unplanned,"absenceProven",false));}
        return new Plan(List.copyOf(batches),List.copyOf(blocked),List.copyOf(coverage));
    }
    private static void packRules(Map<String,Object> input,List<Rule> rules,List<Map<String,Object>> sources,List<Map<String,Object>> out,List<Map<String,Object>> blocked){
        // Keep an object's related fields together; split rules only if one intact source cannot fit.
        if(rules.size()>1&&(rules.size()*sources.size()>96||sources.stream().anyMatch(s->size(packet(input,rules,List.of(s)))>MAX_PAYLOAD_CHARS))){
            int middle=rules.size()/2;packRules(input,rules.subList(0,middle),sources,out,blocked);packRules(input,rules.subList(middle,rules.size()),sources,out,blocked);return;
        }
        List<Map<String,Object>> current=new ArrayList<>();
        for(Map<String,Object> source:sources){List<Map<String,Object>> next=new ArrayList<>(current);next.add(source);Map<String,Object> candidate=packet(input,rules,next);
            if(size(candidate)<=MAX_PAYLOAD_CHARS){current=next;continue;}
            if(!current.isEmpty()){out.add(packet(input,rules,current));current=new ArrayList<>();}
            candidate=packet(input,rules,List.of(source));
            if(size(candidate)<=MAX_PAYLOAD_CHARS)current.add(source);
            else blocked.add(linked("code","EXTRACTION_ATOMIC_INPUT_TOO_LARGE","obligationIds",rules.stream().map(Rule::id).toList(),"sourceIds",List.of(str(source,"id")),"payloadChars",size(candidate),"limit",MAX_PAYLOAD_CHARS,"reason","单个完整来源及其字段/表格上下文超出单次范围，未截断、未发送，也未标为已读取"));
        }
        if(!current.isEmpty())out.add(packet(input,rules,current));
    }
    private static Map<String,Object> packet(Map<String,Object> input,List<Rule> rules,List<Map<String,Object>> sources){
        Set<String> ids=new LinkedHashSet<>(sources.stream().map(s->str(s,"id")).toList());Map<String,Object> out=new LinkedHashMap<>(input);
        out.put("rules",List.copyOf(rules));out.put("sources",List.copyOf(sources));
        if(rules.size()==1)out.put("batchObligationId",rules.get(0).id());else out.remove("batchObligationId");
        List<Map<String,Object>> layouts=RuleEvaluator.maps(input.get("layoutCells"));List<Map<String,Object>> selectedLayout=layouts.stream().filter(l->layoutIn(l,sources,ids)).toList();out.put("layoutCells",selectedLayout);
        List<Map<String,Object>> facts=objects(input.get("knownFacts"));Set<String> kinds=SharedFactReuse.kinds(rules);
        List<Map<String,Object>> selectedFacts=facts.stream().filter(f->kinds.contains(str(f,"kind"))&&ids.containsAll(ProofVerifier.strings(f.get("sourceIds")))&&!ProofVerifier.strings(f.get("sourceIds")).isEmpty()).toList();out.put("knownFacts",selectedFacts);
        List<Map<String,Object>> hints=RuleEvaluator.maps(input.get("previousRejected"));List<Map<String,Object>> selectedHints=hints.stream().filter(h->{List<String> refs=ProofVerifier.strings(h.get("sourceIds"));return refs.isEmpty()||refs.stream().anyMatch(ids::contains);}).toList();out.put("previousRejected",selectedHints);
        // Excluded context stays in the server plan/local fact graph; this packet cannot cite it.
        out.put("contextPartition",linked("policy",POLICY,"sourceScope","SUPPLIED_WINDOWS_ONLY","absenceProven",false,
            "otherSourceIds",RuleEvaluator.maps(input.get("sources")).stream().map(s->str(s,"id")).filter(id->!ids.contains(id)).toList(),
            "retainedKnownFacts",selectedFacts.size(),"outsidePacketKnownFactIds",facts.stream().filter(f->!selectedFacts.contains(f)).map(LegacyExtractionBatches::contextId).toList(),
            "outsidePacketLayoutIds",layouts.stream().filter(l->!selectedLayout.contains(l)).map(LegacyExtractionBatches::contextId).toList(),
            "outsidePacketRejectionIds",hints.stream().filter(h->!selectedHints.contains(h)).map(LegacyExtractionBatches::contextId).toList()));
        out.put("packetId",identity(POLICY+rules.stream().map(SharedFactReuse::signature).toList()+ids+JSON.valueToTree(selectedLayout)+JSON.valueToTree(selectedFacts)+JSON.valueToTree(selectedHints)).substring(0,24));
        return out;
    }
    private static boolean layoutIn(Map<String,Object> cell,List<Map<String,Object>> sources,Set<String> ids){List<String> refs=ProofVerifier.strings(cell.get("sourceIds"));if(!refs.isEmpty())return ids.containsAll(refs);return sources.stream().anyMatch(s->str(s,"documentId").equals(str(cell,"documentId"))&&str(s,"locationType").equals(str(cell,"locationType"))&&RunSession.number(s.get("location"),-1)==RunSession.number(cell.get("location"),-2));}
    private static String contextId(Map<String,Object> row){for(String key:List.of("id","spanId","observationKey"))if(!str(row,key).isBlank())return str(row,key);return identity(JSON.valueToTree(row).toString()).substring(0,20);}
    private static List<Map<String,Object>> objects(Object raw){if(!(raw instanceof Collection<?> rows))return List.of();return rows.stream().map(row->(Map<String,Object>)JSON.convertValue(row,Map.class)).toList();}
    static List<String> sourceIds(Map<String,Object> input){return RuleEvaluator.maps(input.get("sources")).stream().map(s->str(s,"id")).toList();}
    @SuppressWarnings("unchecked") static List<Rule> rules(Map<String,Object> input){return (List<Rule>)input.getOrDefault("rules",List.of());}
}

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
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Plan{
        private final List<Map<String,Object>> batches;
        private final List<Map<String,Object>> blocked;
        private final List<Map<String,Object>> coverage;
        @com.fasterxml.jackson.annotation.JsonCreator
        Plan(@com.fasterxml.jackson.annotation.JsonProperty("batches") List<Map<String,Object>> batches, @com.fasterxml.jackson.annotation.JsonProperty("blocked") List<Map<String,Object>> blocked, @com.fasterxml.jackson.annotation.JsonProperty("coverage") List<Map<String,Object>> coverage) { this.batches = batches; this.blocked = blocked; this.coverage = coverage; }
        public List<Map<String,Object>> batches() { return batches; }
        public List<Map<String,Object>> blocked() { return blocked; }
        public List<Map<String,Object>> coverage() { return coverage; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Plan that = (Plan) other; return java.util.Objects.equals(this.batches, that.batches) && java.util.Objects.equals(this.blocked, that.blocked) && java.util.Objects.equals(this.coverage, that.coverage); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.batches); hash = 31 * hash + java.util.Objects.hashCode(this.blocked); hash = 31 * hash + java.util.Objects.hashCode(this.coverage); return hash; }
        @Override public String toString() { return "Plan[" + "batches=" + batches + ", blocked=" + blocked + ", coverage=" + coverage + "]"; }

        Map<String,Object> audit(){return linked("policy",POLICY,"limit",MAX_PAYLOAD_CHARS,"batches",batches.size(),
            "packets",cn.bidcheck.compat.Java8Api.toList(batches.stream().map(b->linked("packetId",b.get("packetId"),"payloadChars",size(b),"obligationIds",cn.bidcheck.compat.Java8Api.toList(rules(b).stream().map(Rule::id)),"sourceIds",sourceIds(b),"contextPartition",b.get("contextPartition")))),
            "coverage",coverage,"blocked",blocked,"sourceSetPreserved",blocked.isEmpty());}
    }
    static int size(Map<String,Object> input){try{return JSON.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("operation", "EXTRACT", "input", input)).length();}catch(Exception e){throw new IllegalArgumentException("EXTRACTION_INPUT_SERIALIZATION_INVALID",e);}}
    static List<Map<String,Object>> inputs(Map<String,Object> input,Object strategy){return plan(input,strategy,cn.bidcheck.compat.Java8Api.mapOf()).batches();}
    /** pending maps rule IDs to unread source IDs; a missing key means all supplied sources. */
    static Plan plan(Map<String,Object> input,Object strategy,Map<String,Set<String>> pending){
        List<Rule> rules=rules(input);List<Map<String,Object>> sources=RuleEvaluator.maps(input.get("sources"));
        Set<String> unique=new HashSet<>();for(Map<String,Object> source:sources)if(cn.bidcheck.compat.Java8Api.isBlank(str(source,"id"))||!unique.add(str(source,"id")))throw new IllegalArgumentException("EXTRACTION_SOURCE_ID_INVALID");
        Map<String,List<Rule>> groups=new LinkedHashMap<>();
        for(Rule rule:rules){Set<String> ids=pending.getOrDefault(rule.id(),unique);String key=("SPLIT_FAILED_REQUEST".equals(strategy)?rule.id():rule.subject())+":"+new TreeSet<>(ids);groups.computeIfAbsent(key,k->new ArrayList<>()).add(rule);}
        List<Map<String,Object>> batches=new ArrayList<>(),blocked=new ArrayList<>(),coverage=new ArrayList<>();
        for(List<Rule> group:groups.values()){
            Set<String> allowed=pending.getOrDefault(group.get(0).id(),unique);List<Map<String,Object>> selected=cn.bidcheck.compat.Java8Api.toList(sources.stream().filter(s->allowed.contains(str(s,"id"))));
            if(selected.isEmpty())continue;
            packRules(input,group,selected,batches,blocked);
        }
        for(Rule rule:rules){Set<String> expected=pending.getOrDefault(rule.id(),unique);Set<String> sent=new LinkedHashSet<>();for(Map<String,Object> batch:batches)if(rules(batch).stream().anyMatch(r->r.id().equals(rule.id())))sent.addAll(sourceIds(batch));
            Set<String> unplanned=new TreeSet<>(expected);unplanned.removeAll(sent);coverage.add(linked("obligationId",rule.id(),"observationKey",cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(rule)),"requestedSourceIds",new TreeSet<>(expected),"plannedSourceIds",sent,"unplannedSourceIds",unplanned,"absenceProven",false));}
        return new Plan(cn.bidcheck.compat.Java8Api.listCopyOf(batches),cn.bidcheck.compat.Java8Api.listCopyOf(blocked),cn.bidcheck.compat.Java8Api.listCopyOf(coverage));
    }
    private static void packRules(Map<String,Object> input,List<Rule> rules,List<Map<String,Object>> sources,List<Map<String,Object>> out,List<Map<String,Object>> blocked){
        // Keep an object's related fields together; split rules only if one intact source cannot fit.
        if(rules.size()>1&&(rules.size()*sources.size()>96||sources.stream().anyMatch(s->size(packet(input,rules,cn.bidcheck.compat.Java8Api.listOf(s)))>MAX_PAYLOAD_CHARS))){
            int middle=rules.size()/2;packRules(input,rules.subList(0,middle),sources,out,blocked);packRules(input,rules.subList(middle,rules.size()),sources,out,blocked);return;
        }
        List<Map<String,Object>> current=new ArrayList<>();
        for(Map<String,Object> source:sources){List<Map<String,Object>> next=new ArrayList<>(current);next.add(source);Map<String,Object> candidate=packet(input,rules,next);
            if(size(candidate)<=MAX_PAYLOAD_CHARS){current=next;continue;}
            if(!current.isEmpty()){out.add(packet(input,rules,current));current=new ArrayList<>();}
            candidate=packet(input,rules,cn.bidcheck.compat.Java8Api.listOf(source));
            if(size(candidate)<=MAX_PAYLOAD_CHARS)current.add(source);
            else blocked.add(linked("code","EXTRACTION_ATOMIC_INPUT_TOO_LARGE","obligationIds",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(Rule::id)),"sourceIds",cn.bidcheck.compat.Java8Api.listOf(str(source,"id")),"payloadChars",size(candidate),"limit",MAX_PAYLOAD_CHARS,"reason","单个完整来源及其字段/表格上下文超出单次范围，未截断、未发送，也未标为已读取"));
        }
        if(!current.isEmpty())out.add(packet(input,rules,current));
    }
    private static Map<String,Object> packet(Map<String,Object> input,List<Rule> rules,List<Map<String,Object>> sources){
        Set<String> ids=new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.toList(sources.stream().map(s->str(s,"id"))));Map<String,Object> out=new LinkedHashMap<>(input);
        out.put("rules",cn.bidcheck.compat.Java8Api.listCopyOf(rules));out.put("sources",cn.bidcheck.compat.Java8Api.listCopyOf(sources));
        if(rules.size()==1)out.put("batchObligationId",rules.get(0).id());else out.remove("batchObligationId");
        List<Map<String,Object>> layouts=RuleEvaluator.maps(input.get("layoutCells"));List<Map<String,Object>> selectedLayout=cn.bidcheck.compat.Java8Api.toList(layouts.stream().filter(l->layoutIn(l,sources,ids)));out.put("layoutCells",selectedLayout);
        List<Map<String,Object>> facts=objects(input.get("knownFacts"));Set<String> kinds=SharedFactReuse.kinds(rules);
        List<Map<String,Object>> selectedFacts=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->kinds.contains(str(f,"kind"))&&ids.containsAll(ProofVerifier.strings(f.get("sourceIds")))&&!ProofVerifier.strings(f.get("sourceIds")).isEmpty()));out.put("knownFacts",selectedFacts);
        List<Map<String,Object>> hints=RuleEvaluator.maps(input.get("previousRejected"));List<Map<String,Object>> selectedHints=cn.bidcheck.compat.Java8Api.toList(hints.stream().filter(h->{List<String> refs=ProofVerifier.strings(h.get("sourceIds"));return refs.isEmpty()||refs.stream().anyMatch(ids::contains);}));out.put("previousRejected",selectedHints);
        // Excluded context stays in the server plan/local fact graph; this packet cannot cite it.
        out.put("contextPartition",linked("policy",POLICY,"sourceScope","SUPPLIED_WINDOWS_ONLY","absenceProven",false,
            "otherSourceIds",cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(input.get("sources")).stream().map(s->str(s,"id")).filter(id->!ids.contains(id))),
            "retainedKnownFacts",selectedFacts.size(),"outsidePacketKnownFactIds",cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->!selectedFacts.contains(f)).map(LegacyExtractionBatches::contextId)),
            "outsidePacketLayoutIds",cn.bidcheck.compat.Java8Api.toList(layouts.stream().filter(l->!selectedLayout.contains(l)).map(LegacyExtractionBatches::contextId)),
            "outsidePacketRejectionIds",cn.bidcheck.compat.Java8Api.toList(hints.stream().filter(h->!selectedHints.contains(h)).map(LegacyExtractionBatches::contextId))));
        out.put("packetId",identity(POLICY+cn.bidcheck.compat.Java8Api.toList(rules.stream().map(SharedFactReuse::signature))+ids+JSON.valueToTree(selectedLayout)+JSON.valueToTree(selectedFacts)+JSON.valueToTree(selectedHints)).substring(0,24));
        return out;
    }
    private static boolean layoutIn(Map<String,Object> cell,List<Map<String,Object>> sources,Set<String> ids){List<String> refs=ProofVerifier.strings(cell.get("sourceIds"));if(!refs.isEmpty())return ids.containsAll(refs);return sources.stream().anyMatch(s->str(s,"documentId").equals(str(cell,"documentId"))&&str(s,"locationType").equals(str(cell,"locationType"))&&RunSession.number(s.get("location"),-1)==RunSession.number(cell.get("location"),-2));}
    private static String contextId(Map<String,Object> row){for(String key:cn.bidcheck.compat.Java8Api.listOf("id", "spanId", "observationKey"))if(!cn.bidcheck.compat.Java8Api.isBlank(str(row,key)))return str(row,key);return identity(JSON.valueToTree(row).toString()).substring(0,20);}
    private static List<Map<String,Object>> objects(Object raw){if(!(raw instanceof Collection<?>))return cn.bidcheck.compat.Java8Api.listOf();return cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (raw)).stream().map(row->(Map<String,Object>)JSON.convertValue(row,Map.class)));}
    static List<String> sourceIds(Map<String,Object> input){return cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(input.get("sources")).stream().map(s->str(s,"id")));}
    @SuppressWarnings("unchecked") static List<Rule> rules(Map<String,Object> input){return (List<Rule>)input.getOrDefault("rules",cn.bidcheck.compat.Java8Api.listOf());}
}

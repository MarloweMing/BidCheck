package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.FieldCoverage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Restores validated read receipts, never prior business decisions or unread source coverage. */
final class RecoveryFieldCoverage {
    private static final ObjectMapper JSON=new ObjectMapper();
    private RecoveryFieldCoverage() {}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Replayed {
        private final List<Map<String,Object>> receipts;
        private final Map<String,Integer> rejected;
        @com.fasterxml.jackson.annotation.JsonCreator
        Replayed(@com.fasterxml.jackson.annotation.JsonProperty("receipts") List<Map<String,Object>> receipts, @com.fasterxml.jackson.annotation.JsonProperty("rejected") Map<String,Integer> rejected) { this.receipts = receipts; this.rejected = rejected; }
        public List<Map<String,Object>> receipts() { return receipts; }
        public Map<String,Integer> rejected() { return rejected; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Replayed that = (Replayed) other; return java.util.Objects.equals(this.receipts, that.receipts) && java.util.Objects.equals(this.rejected, that.rejected); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.receipts); hash = 31 * hash + java.util.Objects.hashCode(this.rejected); return hash; }
        @Override public String toString() { return "Replayed[" + "receipts=" + receipts + ", rejected=" + rejected + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class ReadPacket {
        private final Set<String> sources;
        private final List<Map<String,Object>> targets;
        private final List<Map<String,Object>> rejected;
        @com.fasterxml.jackson.annotation.JsonCreator
        private ReadPacket(@com.fasterxml.jackson.annotation.JsonProperty("sources") Set<String> sources, @com.fasterxml.jackson.annotation.JsonProperty("targets") List<Map<String,Object>> targets, @com.fasterxml.jackson.annotation.JsonProperty("rejected") List<Map<String,Object>> rejected) { this.sources = sources; this.targets = targets; this.rejected = rejected; }
        public Set<String> sources() { return sources; }
        public List<Map<String,Object>> targets() { return targets; }
        public List<Map<String,Object>> rejected() { return rejected; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; ReadPacket that = (ReadPacket) other; return java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.targets, that.targets) && java.util.Objects.equals(this.rejected, that.rejected); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.targets); hash = 31 * hash + java.util.Objects.hashCode(this.rejected); return hash; }
        @Override public String toString() { return "ReadPacket[" + "sources=" + sources + ", targets=" + targets + ", rejected=" + rejected + "]"; }
}

    /** Server-authored binding of the fields actually sent in this successful extraction packet. */
    static Map<String,Object> readBinding(String selection,Object packetId,List<Rule> rules,List<String> sources){
        return linked("selection",selection,"packetId",packetId,"obligations",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(Rule::id)),"sourceIds",sources,
            "readTargets",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(rule->linked("ruleSignature",SharedFactReuse.signature(rule),"observationKey",FieldCoverage.key(SharedFactReuse.observationTarget(rule)),"target",SharedFactReuse.observationTarget(rule)))));
    }

    static Replayed replay(Map<String,Object> context,List<Rule> rules,EvidenceCorpus corpus,
                           List<Fact> revalidatedFacts,RunSession session){
        String modelIdentity=session.model.identity();
        Map<String,Integer> rejected=new LinkedHashMap<>();List<Map<String,Object>> accepted=new ArrayList<>();
        Map<String,Object> previous=map(context.get("previousAtomicCheck"));
        List<Map<String,Object>> fields=RuleEvaluator.maps(previous.get("fieldCoverage"));
        if(fields.isEmpty())return new Replayed(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf());
        if(!VERSION.equals(str(map(previous.get("metrics")),"engineVersion"))){
            return new Replayed(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("RECOVERY_RECEIPT_ENGINE_CHANGED", fields.size()));
        }
        Set<String> priorSignatures=new HashSet<>();
        try{for(Map<String,Object> raw:RuleEvaluator.maps(map(previous.get("plan")).get("rules")))
            signatures(JSON.convertValue(raw,Rule.class),priorSignatures);
        }catch(IllegalArgumentException malformed){return new Replayed(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("RECOVERY_RECEIPT_PLAN_INVALID", fields.size()));}
        Map<String,Map<String,Object>> targets=new HashMap<>();
        Map<String,Set<String>> targetSignatures=new HashMap<>();
        Map<String,Rule> currentRules=new HashMap<>();
        for(Rule rule:rules)if(priorSignatures.contains(SharedFactReuse.signature(rule))){
            Map<String,Object> target=SharedFactReuse.observationTarget(rule);targets.put(FieldCoverage.key(target),target);
            targetSignatures.computeIfAbsent(FieldCoverage.key(target),key->new HashSet<>()).add(SharedFactReuse.signature(rule));
            currentRules.put(SharedFactReuse.signature(rule),rule);
        }
        Map<String,Fact> facts=new HashMap<>();revalidatedFacts.forEach(f->facts.put(f.id(),f));
        Map<String,Map<String,Object>> currentSources=new HashMap<>();corpus.inputs(corpus.all).forEach(s->currentSources.put(str(s,"id"),s));
        Set<String> verifiedSources=new HashSet<>();List<ReadPacket> completedPackets=new ArrayList<>();
        for(Map<String,Object> step:RuleEvaluator.maps(context.get("priorSteps"))){
            if(!"COMPLETED".equals(str(step,"status"))||!VERSION.equals(str(step,"schemaVersion"))||!modelIdentity.equals(str(step,"modelIdentity")))continue;
            Map<String,Object> output=map(step.get("output"));
            if(str(step,"type").startsWith("EVIDENCE_PLAN_"))for(Map<String,Object> prior:RuleEvaluator.maps(output.get("candidates"))){
                String id=str(prior,"id");Map<String,Object> current=currentSources.get(id);
                // The source id binds document hash/version, parser fingerprint and exact range;
                // compare the original quote and document scope too instead of trusting the id alone.
                if(id.startsWith("S")&&current!=null&&JSON.valueToTree(current).equals(JSON.valueToTree(prior)))verifiedSources.add(id);
            }
            if(str(step,"type").startsWith("FACT_VALIDATION_")){
                List<String> ids=ProofVerifier.strings(output.get("sourceIds"));
                Map<String,Object> binding=map(output.get("readBinding"));
                if(!binding.isEmpty()&&session.fingerprint(str(step,"type"),binding).equals(str(step,"inputFingerprint"))
                    &&ids.equals(ProofVerifier.strings(binding.get("sourceIds")))&&!ids.isEmpty()&&ids.stream().allMatch(corpus.byId::containsKey))
                    completedPackets.add(new ReadPacket(cn.bidcheck.compat.Java8Api.setCopyOf(ids),RuleEvaluator.maps(binding.get("readTargets")),RuleEvaluator.maps(output.get("rejected"))));
            }
        }
        for(Map<String,Object> field:fields){
            String reason=invalid(field,targets,targetSignatures,currentRules,verifiedSources,completedPackets,facts,modelIdentity);
            if(reason!=null){rejected.merge(reason,1,Integer::sum);continue;}
            Map<String,Object> restored=new LinkedHashMap<>(field);
            restored.put("reusedObservation",true);restored.put("observedInCurrentRun",false);
            restored.put("recoveryRevalidated",true);restored.put("absenceProven",false);
            restored.put("scope","SELECTED_SOURCES_ONLY");accepted.add(restored);
        }
        return new Replayed(cn.bidcheck.compat.Java8Api.listCopyOf(accepted),cn.bidcheck.compat.Java8Api.mapCopyOf(rejected));
    }
    private static String invalid(Map<String,Object> field,Map<String,Map<String,Object>> targets,Map<String,Set<String>> signatures,Map<String,Rule> rules,
            Set<String> sources,List<ReadPacket> packets,Map<String,Fact> facts,String model){
        if(!FieldCoverage.completeRead(field))return "RECOVERY_RECEIPT_NOT_COMPLETE";
        if(!FieldCoverage.POLICY.equals(str(field,"observationPolicy"))||!model.equals(str(field,"modelIdentity")))return "RECOVERY_RECEIPT_IDENTITY_CHANGED";
        Map<String,Object> target=map(field.get("target")),expected=targets.get(str(field,"observationKey"));
        if(expected==null||!JSON.valueToTree(expected).equals(JSON.valueToTree(target))||!FieldCoverage.key(target).equals(str(field,"observationKey")))return "RECOVERY_RECEIPT_RULE_CHANGED";
        List<String> refs=ProofVerifier.strings(field.get("sourceIds")),ids=ProofVerifier.strings(field.get("factIds"));
        if(refs.isEmpty()||refs.size()>128||refs.stream().distinct().count()!=refs.size()||!sources.containsAll(refs)
            ||packets.stream().noneMatch(packet->packet.sources().containsAll(refs)&&packet.targets().stream().anyMatch(bound->
                str(field,"observationKey").equals(str(bound,"observationKey"))&&JSON.valueToTree(target).equals(JSON.valueToTree(bound.get("target")))
                    &&signatures.getOrDefault(str(field,"observationKey"),cn.bidcheck.compat.Java8Api.setOf()).contains(str(bound,"ruleSignature"))
                    &&packet.rejected().stream().noneMatch(rejection->relevantRejection(rejection,refs,rules.get(str(bound,"ruleSignature")))))))return "RECOVERY_RECEIPT_SOURCE_CHANGED";
        if(ids.stream().distinct().count()!=ids.size())return "RECOVERY_RECEIPT_FACT_INVALID";
        if("READ_NOT_FOUND".equals(str(field,"status")))return ids.isEmpty()?null:"RECOVERY_RECEIPT_FACT_INVALID";
        if(!"EXTRACTED".equals(str(field,"status"))||ids.isEmpty())return "RECOVERY_RECEIPT_FACT_INVALID";
        for(String id:ids){Fact fact=facts.get(id);if(fact==null||!refs.containsAll(fact.sourceIds())||!FieldCoverage.matches(target,SharedFactReuse.value(fact)))return "RECOVERY_RECEIPT_FACT_INVALID";}
        return null;
    }
    private static boolean relevantRejection(Map<String,Object> rejection,List<String> refs,Rule rule){
        List<String> rejectedSources=ProofVerifier.strings(rejection.get("sourceIds"));
        return (rejectedSources.isEmpty()||rejectedSources.stream().anyMatch(refs::contains))
            &&(cn.bidcheck.compat.Java8Api.isBlank(str(rejection,"kind"))||cn.bidcheck.compat.Java8Api.isBlank(str(rejection,"field"))||rule==null||SharedFactReuse.rejectedFieldCouldMatch(rule,rejection));
    }
    private static void signatures(Rule rule,Set<String> out){
        out.add(SharedFactReuse.signature(rule));
        if(!rule.children().isEmpty()&&RuleObligations.hasOwnPredicate(rule))out.add(SharedFactReuse.signature(RuleObligations.own(rule)));
        rule.children().forEach(child->signatures(child,out));
    }
    private static Map<String,Object> map(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?>)((Map<?,?>) (value)).forEach((k,v)->result.put(String.valueOf(k),v));return result;}
}

package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Replays observations, never previous PASS decisions, without a paid model request. */
final class RecoveryReplay {
    static final String FACT_SNAPSHOT="GROUNDED_FACT_SNAPSHOT";
    private static final ObjectMapper JSON=new ObjectMapper();
    private RecoveryReplay() {}
    static boolean localOnly(Map<String,Object> context){return Boolean.FALSE.equals(context.get("recoveryModelAllowed"))
        &&(RequirementRepairPolicy.recompile(context)||cn.bidcheck.compat.Java8Api.setOf("RECOMPUTE_ONLY", "EVIDENCE_RECOMPUTE").contains(str(context,"recoveryMode")));}
    static JsonNode plan(Map<String,Object> context,ObjectMapper json){
        Map<String,Object> previous=map(context.get("previousAtomicCheck"));Object value=previous.get("plan");if(value==null)return null;
        JsonNode tree=json.valueToTree(value);if(!tree.path("rules").isArray())return null;
        restore(tree);return tree;
    }
    private static void restore(JsonNode node){
        if(node.isObject()&&"UNSUPPORTED".equals(node.path("predicate").asText())){
            String predicate=node.path("parameters").path("originalPredicate").asText();
            if(PREDICATES.contains(predicate)&&!"UNSUPPORTED".equals(predicate))((ObjectNode)node).put("predicate",predicate);
        }
        for(String key:cn.bidcheck.compat.Java8Api.listOf("rules", "children"))if(node.path(key).isArray())node.path(key).forEach(RecoveryReplay::restore);
    }
    static List<Source> sources(Map<String,Object> context,EvidenceCorpus corpus,String modelIdentity){
        // A validated canonical change can expose a window that this clause did not previously read.
        // IDs still have to resolve in the current frozen corpus; an old ID is not a source receipt.
        // Unknown IDs simply leave normal targeted retrieval enabled, never imply a completed read.
        Set<String> ids=new LinkedHashSet<>(ProofVerifier.strings(context.get("recoveryChangedSourceIds")));for(Map<String,Object> step:steps(context,modelIdentity)){
            Map<String,Object> output=map(step.get("output"));ids.addAll(ProofVerifier.strings(output.get("sourceIds")));
            for(Map<String,Object> fact:RuleEvaluator.maps(output.get("facts")))ids.addAll(ProofVerifier.strings(fact.get("sourceIds")));
        }
        // Native-only proofs have read windows but no FACT_VALIDATION packet. Their exact frozen
        // source ids authorize rereading the original text, never reusing the former decision.
        for(Map<String,Object> step:RuleEvaluator.maps(context.get("priorSteps")))if(str(step,"type").startsWith("EVIDENCE_PLAN_")&&current(step,modelIdentity))
            for(Map<String,Object> candidate:RuleEvaluator.maps(map(step.get("output")).get("candidates"))){String id=str(candidate,"id");if(!cn.bidcheck.compat.Java8Api.isBlank(id))ids.add(id);}
        for(Map<String,Object> step:snapshots(context,modelIdentity))for(Map<String,Object> metadata:RuleEvaluator.maps(map(step.get("output")).get("sources")))
            if(sourceMatches(corpus,metadata))ids.add(str(metadata,"id"));
        for(Source source:corpus.all)if(source.id().startsWith("V"))ids.add(source.id());
        return cn.bidcheck.compat.Java8Api.toList(ids.stream().map(corpus.byId::get).filter(Objects::nonNull));
    }
    static ProofVerifier.Verified facts(Map<String,Object> context,List<Source> sources,List<Map<String,Object>> layout,String modelIdentity){
        return facts(context,sources,layout,modelIdentity,new ProofVerifier());
    }
    private static ProofVerifier.Verified facts(Map<String,Object> context,List<Source> sources,List<Map<String,Object>> layout,String modelIdentity,ProofVerifier verifier){
        ObjectMapper json=new ObjectMapper();Map<String,Fact> accepted=new LinkedHashMap<>();List<Map<String,Object>> rejected=new ArrayList<>();
        Set<String> available=new HashSet<>(cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)));
        for(Map<String,Object> step:steps(context,modelIdentity)){
            Map<String,Object> output=map(step.get("output"));List<String> refs=ProofVerifier.strings(output.get("sourceIds"));
            if(refs.isEmpty()||!available.containsAll(refs))continue;
            List<Source> sent=cn.bidcheck.compat.Java8Api.toList(sources.stream().filter(s->refs.contains(s.id())));
            ProofVerifier.Verified verified=verifier.verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", RuleEvaluator.maps(output.get("facts")))),sent,layout);
            verified.facts().stream().map(SharedFactReuse::canonical).forEach(f->accepted.put(f.id(),f));rejected.addAll(verified.rejected());
        }
        return new ProofVerifier.Verified(cn.bidcheck.compat.Java8Api.listCopyOf(accepted.values()),cn.bidcheck.compat.Java8Api.listCopyOf(rejected));
    }
    /** Every origin is retained, but no previous decision or source/subject verification flag is reused. */
    static Map<String,Object> snapshot(EvidenceCorpus corpus,Collection<Fact> facts){
        List<Fact> retained=cn.bidcheck.compat.Java8Api.toList(facts.stream().filter(f->ProofVerifier.KINDS.contains(f.kind())));
        Set<String> ids=new LinkedHashSet<>();retained.forEach(f->ids.addAll(f.sourceIds()));
        return linked("facts",cn.bidcheck.compat.Java8Api.toList(retained.stream().map(SharedFactReuse::value)),"sourceIds",cn.bidcheck.compat.Java8Api.listCopyOf(ids),
            "sources",cn.bidcheck.compat.Java8Api.toList(ids.stream().map(corpus.byId::get).filter(Objects::nonNull).map(s->sourceMetadata(corpus,s))),"businessProof",false);
    }
    static ProofVerifier.Verified facts(Map<String,Object> context,EvidenceCorpus corpus,List<Source> sources,List<Map<String,Object>> layout,String modelIdentity){
        return facts(context,corpus,sources,layout,modelIdentity,Collections.emptyList());
    }
    static ProofVerifier.Verified facts(Map<String,Object> context,EvidenceCorpus corpus,List<Source> sources,List<Map<String,Object>> layout,String modelIdentity,List<Rule> currentRules){
        ProofVerifier verifier=ContractRecordBindings.verifier(corpus,currentRules,sources);
        ProofVerifier.Verified legacy=facts(context,sources,layout,modelIdentity,verifier);
        Map<String,Fact> accepted=new LinkedHashMap<>();legacy.facts().forEach(f->accepted.put(f.id(),f));
        List<Map<String,Object>> rejected=new ArrayList<>(legacy.rejected());Set<String> available=new HashSet<>(cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)));
        for(Map<String,Object> step:snapshots(context,modelIdentity)){
            Map<String,Object> output=map(step.get("output"));Set<String> validated=new HashSet<>();
            for(Map<String,Object> metadata:RuleEvaluator.maps(output.get("sources")))if(sourceMatches(corpus,metadata))validated.add(str(metadata,"id"));
            // Verify each whole observation independently: a missing source invalidates its full
            // chain, while another observation's bad source must not erase unrelated valid facts.
            // One-at-a-time verification also avoids the model packet's 120-fact capacity cap.
            for(Map<String,Object> raw:RuleEvaluator.maps(output.get("facts"))){
                List<String> refs=ProofVerifier.strings(raw.get("sourceIds"));
                if(refs.isEmpty()||!available.containsAll(refs)||!validated.containsAll(refs)){
                    rejected.add(linked("code","RECOVERY_SOURCE_REVISION_MISMATCH","sourceIds",refs));continue;
                }
                List<Source> sent=cn.bidcheck.compat.Java8Api.toList(refs.stream().map(corpus.byId::get));
                ProofVerifier.Verified verified=verifier.verify(JSON.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(raw))),sent,layout);
                rejected.addAll(verified.rejected());for(Fact observed:verified.facts()){
                    if("SECTION".equals(observed.kind())){Map<String,Object> attrs=new LinkedHashMap<>(observed.attributes());attrs.put("fullSectionRead",SectionCoverage.covered(observed,corpus,sources));
                        observed=new Fact(observed.id(),observed.kind(),observed.subject(),observed.field(),observed.rawValue(),observed.value(),observed.unit(),observed.basis(),observed.scope(),observed.sourceIds(),attrs);}
                    Fact fact=SharedFactReuse.canonical(observed);accepted.put(fact.id(),fact);
                }
            }
        }
        return new ProofVerifier.Verified(cn.bidcheck.compat.Java8Api.listCopyOf(accepted.values()),cn.bidcheck.compat.Java8Api.listCopyOf(rejected));
    }
    static Map<String,Object> sourceMetadata(EvidenceCorpus corpus,Source source){
        cn.bidcheck.domain.Domain.Document document=corpus.documents.stream().filter(d->d.id().equals(source.evidence().documentId())).findFirst().orElse(null);
        if(document==null)return cn.bidcheck.compat.Java8Api.mapOf();
        cn.bidcheck.domain.Domain.Page page=corpus.pages.get(EvidenceCorpus.pageKey(document.id(),source.evidence().page(),source.evidence().locationType()));
        return linked("id",source.id(),"projectId",document.projectId(),"documentId",document.id(),"documentHash",document.sha256(),
            "documentVersion",String.valueOf(document.version()),"parserFingerprint",page==null?"":SharedFactReuse.parserFingerprint(page),
            "sourceFingerprint",identity(JSON.valueToTree(source).toString()));
    }
    static boolean sourceMatches(EvidenceCorpus corpus,Map<String,Object> saved){
        Source current=corpus.byId.get(str(saved,"id"));return current!=null&&sourceMetadata(corpus,current).equals(saved);
    }
    private static List<Map<String,Object>> snapshots(Map<String,Object> context,String modelIdentity){
        return cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(context.get("priorSteps")).stream().filter(s->FACT_SNAPSHOT.equals(str(s,"type"))&&current(s,modelIdentity)));
    }
    private static List<Map<String,Object>> steps(Map<String,Object> context,String modelIdentity){
        return cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(context.get("priorSteps")).stream().filter(s->str(s,"type").startsWith("FACT_VALIDATION_")&&current(s,modelIdentity)));
    }
    private static boolean current(Map<String,Object> step,String modelIdentity){return "COMPLETED".equals(str(step,"status"))&&VERSION.equals(str(step,"schemaVersion"))&&modelIdentity.equals(str(step,"modelIdentity"));}
    private static Map<String,Object> map(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?>)((Map<?,?>) (value)).forEach((k,v)->result.put(String.valueOf(k),v));return result;}
}

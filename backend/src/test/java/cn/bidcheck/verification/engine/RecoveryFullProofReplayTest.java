package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import cn.bidcheck.retrieval.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class RecoveryFullProofReplayTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ModelGateway noModel=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "full-proof-replay";}public JsonNode request(String type,Map<String,Object> input){fail("Recovery must not call model: "+type);return null;}};
    private final HybridRetriever noRetriever=new HybridRetriever(){@Override public boolean enabled(){return true;}@Override public String identity(){return "no-network";}@Override public RetrievalResult search(Document d,String query,int limit){fail("Recovery must not retrieve new sources");return null;}};
    private Map<String,Object> local(Object plan,String target,List<Map<String,Object>> steps){
        return linked("recoveryMode","RECOMPUTE_ONLY","recoveryModelAllowed",false,"recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf(target),"previousAtomicCheck",cn.bidcheck.compat.Java8Api.mapOf("plan", plan),"priorSteps",steps);
    }
    private Map<String,Object> step(String type,Object output){return linked("type",type,"status","COMPLETED","schemaVersion",VERSION,"modelIdentity",noModel.identity(),"output",JSON.convertValue(output,Object.class));}
    private Rule declaration(){return new Rule("R2","提交履约承诺书","DECLARATION","","material","EXISTS","履约承诺书","",cn.bidcheck.compat.Java8Api.mapOf(),"提交履约承诺书","",cn.bidcheck.compat.Java8Api.listOf());}
    private Checkpoint checkpoint(String requirement){return new Checkpoint("cp","核验投标义务","商务","HIGH","SEMANTIC_RESPONSE","material","EXISTS",requirement,"",true,true,true,null);}
    private String status(Map<String,Object> result,String id){return RuleEvaluator.maps(result.get("obligations")).stream().filter(r->id.equals(r.get("id"))).map(r->str(r,"status")).findFirst().orElse("MISSING");}
    private Map<String,Object> evaluate(Checkpoint cp,Document doc,Map<String,Object> context){return new VerificationEngine(noModel,noRetriever).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(doc),null,context);}
    private void noNetwork(Map<String,Object> result){assertEquals(0,((Map<?,?>)result.get("metrics")).get("modelCalls"));assertTrue(RuleEvaluator.maps(result.get("steps")).stream().noneMatch(s->cn.bidcheck.compat.Java8Api.setOf("COMPILE", "EXTRACT").contains(s.get("type"))||str(s,"type").startsWith("RETRIEVE_")));}

    @Test void nativePassIsRebuiltFromOldWindowsWhenAnotherConditionIsTargeted(){
        Document bid=SharedFactReuseTest.doc("native","p",1,"本项目投标文件\n投标函\n本单位已充分阅读招标文件全部内容，现提交本投标文件并接受招标文件约定的工作范围，愿意按约定履行相应义务，承担全部合同责任。");
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid));
        Rule material=new Rule("R1","提交投标函","MATERIAL","","material","EXISTS","投标函","",cn.bidcheck.compat.Java8Api.mapOf(),"提交投标函","",cn.bidcheck.compat.Java8Api.listOf());
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(material, declaration()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());Checkpoint cp=checkpoint("提交投标函；提交履约承诺书");
        Map<String,Object> first=evaluate(cp,bid,local(plan,"R1",cn.bidcheck.compat.Java8Api.listOf(step("EVIDENCE_PLAN_0",cn.bidcheck.compat.Java8Api.mapOf("candidates", corpus.inputs(corpus.all))))));
        assertEquals("PASS",status(first,"R1"),first.toString());assertEquals("UNDETERMINED",status(first,"R2"));
        // Legacy native results had only read windows, without FACT_VALIDATION or a new snapshot.
        List<Map<String,Object>> legacy=cn.bidcheck.compat.Java8Api.listOf(step("EVIDENCE_PLAN_0",cn.bidcheck.compat.Java8Api.mapOf("candidates", corpus.inputs(corpus.all))));
        Map<String,Object> second=evaluate(cp,bid,local(first.get("plan"),"R2",legacy));
        assertEquals("PASS",status(second,"R1"),second.toString());assertEquals("UNDETERMINED",status(second,"R2"));noNetwork(second);
    }

    @Test void sharedPassSurvivesTargetedRecoveryWithoutRequeryingItsField(){
        SharedFactReuseTest fixture=new SharedFactReuseTest();Document bid=SharedFactReuseTest.priceDoc("price","80");EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid));
        Rule price=fixture.pricedRule("100");Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(price, declaration()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        Checkpoint cp=checkpoint(price.sourceQuote()+"提交履约承诺书");
        Map<String,Object> firstContext=local(plan,"price",cn.bidcheck.compat.Java8Api.listOf(step("EVIDENCE_PLAN_0",cn.bidcheck.compat.Java8Api.mapOf("candidates", corpus.inputs(corpus.all)))));
        firstContext.put("sharedFactLookup",(Function<Map<String,Object>,Object>)q->cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.entry(corpus,SharedFactReuse.value(fixture.pricedFact(corpus)))));
        Map<String,Object> first=evaluate(cp,bid,firstContext);assertEquals("PASS",status(first,"price"),first.toString());
        assertTrue(RuleEvaluator.maps(first.get("steps")).stream().noneMatch(s->str(s,"type").startsWith("FACT_VALIDATION_")));
        List<Map<String,Object>> snapshots=cn.bidcheck.compat.Java8Api.toList(RuleEvaluator.maps(first.get("steps")).stream().filter(s->RecoveryReplay.FACT_SNAPSHOT.equals(s.get("type"))));assertEquals(1,snapshots.size());
        Map<String,Object> secondContext=local(first.get("plan"),"R2",snapshots);
        String oldKey=cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(price));
        secondContext.put("sharedFactLookup",(Function<Map<String,Object>,Object>)q->{assertFalse(ProofVerifier.strings(q.get("observationKeys")).contains(oldKey));assertEquals(cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION", "DECLARATION"),new HashSet<>(ProofVerifier.strings(q.get("factKinds"))));return cn.bidcheck.compat.Java8Api.listOf();});
        Map<String,Object> second=evaluate(cp,bid,secondContext);assertEquals("PASS",status(second,"price"),second.toString());assertEquals("UNDETERMINED",status(second,"R2"));noNetwork(second);
    }

    @Test void snapshotRevalidatesProjectVersionAndEverySourceWithoutDroppingConflicts(){
        Document left=SharedFactReuseTest.priceDoc("left","80"),right=SharedFactReuseTest.priceDoc("right","120");EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(left, right));
        List<Fact> facts=new ArrayList<>();for(Source source:corpus.all){String value=source.evidence().documentId().equals("left")?"80":"120";
            facts.add(new Fact("old","NUMBER","甲公司","amount",value,value,"元","投标总报价","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source.id()),cn.bidcheck.compat.Java8Api.mapOf("lot", "不划分标段", "currency", "人民币", "taxBasis", "含税")));}
        Map<String,Object> context=cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf(step(RecoveryReplay.FACT_SNAPSHOT,RecoveryReplay.snapshot(corpus,facts))));
        cn.bidcheck.verification.engine.ProofVerifier.Verified replay=RecoveryReplay.facts(context,corpus,RecoveryReplay.sources(context,corpus,noModel.identity()),cn.bidcheck.compat.Java8Api.listOf(),noModel.identity());
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("80", "120"),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(replay.facts().stream().map(Fact::value))));
        Fact chain=new Fact("chain",facts.get(0).kind(),facts.get(0).subject(),facts.get(0).field(),facts.get(0).rawValue(),facts.get(0).value(),facts.get(0).unit(),facts.get(0).basis(),facts.get(0).scope(),cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().map(Source::id)),facts.get(0).attributes());
        context=cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf(step(RecoveryReplay.FACT_SNAPSHOT,RecoveryReplay.snapshot(corpus,cn.bidcheck.compat.Java8Api.listOf(chain)))));
        EvidenceCorpus partial=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(left));assertTrue(RecoveryReplay.facts(context,partial,RecoveryReplay.sources(context,partial,noModel.identity()),cn.bidcheck.compat.Java8Api.listOf(),noModel.identity()).facts().isEmpty());
        Document foreign=new Document(left.id(),"other-project",left.role(),left.name(),left.version(),left.sha256(),left.createdAt(),left.pages(),left.warnings());
        EvidenceCorpus foreignCorpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(foreign));assertEquals(partial.all.get(0).id(),foreignCorpus.all.get(0).id(),"source IDs alone cannot establish project ownership");
        context=cn.bidcheck.compat.Java8Api.mapOf("priorSteps", cn.bidcheck.compat.Java8Api.listOf(step(RecoveryReplay.FACT_SNAPSHOT,RecoveryReplay.snapshot(partial,cn.bidcheck.compat.Java8Api.listOf(facts.get(0))))));
        assertTrue(RecoveryReplay.sources(context,foreignCorpus,noModel.identity()).isEmpty());
        assertTrue(RecoveryReplay.facts(context,foreignCorpus,foreignCorpus.all,cn.bidcheck.compat.Java8Api.listOf(),noModel.identity()).facts().isEmpty());
        Document changed=new Document(left.id(),left.projectId(),left.role(),left.name(),2,"new-hash",left.createdAt(),left.pages(),left.warnings());EvidenceCorpus changedCorpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(changed));
        assertTrue(RecoveryReplay.facts(context,changedCorpus,changedCorpus.all,cn.bidcheck.compat.Java8Api.listOf(),noModel.identity()).facts().isEmpty());
    }

    @Test void oldPartialOrTruncatedProofCannotBecomePassByReplayingItsFacts(){
        SharedFactReuseTest fixture=new SharedFactReuseTest();Document bid=SharedFactReuseTest.priceDoc("incomplete","80");EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid));Rule price=fixture.pricedRule("100");
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(price, declaration()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());Checkpoint cp=checkpoint(price.sourceQuote()+"提交履约承诺书");
        for(String code:cn.bidcheck.compat.Java8Api.listOf("SHARED_SOURCE_REVIEW_REQUIRED", "EVIDENCE_LOOKUP_INCOMPLETE")){
            Map<String,Object> snapshot=RecoveryReplay.snapshot(corpus,cn.bidcheck.compat.Java8Api.listOf(fixture.pricedFact(corpus)));
            snapshot.put("coverageDependencies",cn.bidcheck.compat.Java8Api.listOf(linked("obligationId",price.id(),"observationKey",cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(price)),"gapCode",code,"sourceIds",cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().map(Source::id)))));
            for(String target:cn.bidcheck.compat.Java8Api.listOf("R2", "price")){
                Map<String,Object> result=evaluate(cp,bid,local(plan,target,cn.bidcheck.compat.Java8Api.listOf(step(RecoveryReplay.FACT_SNAPSHOT,snapshot))));
                assertEquals("UNDETERMINED",status(result,"price"),code+" / "+target+" / "+result);
                assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->code.equals(g.get("code"))));noNetwork(result);
            }
        }
    }

    @Test void activeRecoveryClosesCoverageOnlyAfterACompleteCurrentSourceCheckedReceipt(){
        SharedFactReuseTest fixture=new SharedFactReuseTest();Document bid=SharedFactReuseTest.priceDoc("read-completion","80");EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid));Rule price=fixture.pricedRule("100");
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(price, declaration()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());Checkpoint cp=checkpoint(price.sourceQuote()+"提交履约承诺书");
        Fact priceFact=fixture.pricedFact(corpus);Map<String,Object> snapshot=RecoveryReplay.snapshot(corpus,cn.bidcheck.compat.Java8Api.listOf(priceFact));
        snapshot.put("coverageDependencies",cn.bidcheck.compat.Java8Api.listOf(linked("obligationId",price.id(),"observationKey",cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(price)),"gapCode","SHARED_SOURCE_REVIEW_REQUIRED","sourceIds",priceFact.sourceIds())));
        Map<String,Object> context=local(plan,"price",cn.bidcheck.compat.Java8Api.listOf(step(RecoveryReplay.FACT_SNAPSHOT,snapshot)));
        assertEquals("UNDETERMINED",status(evaluate(cp,bid,context),"price"));
        Map<String,Object> receipt=fixture.readReceipt(price,corpus.all.get(0),cn.bidcheck.compat.Java8Api.listOf("price"));receipt.put("modelIdentity",noModel.identity());
        Map<String,Object> raw=SharedFactReuse.value(priceFact);raw.put("id","price");Map<String,Object> entry=SharedFactReuseTest.entry(corpus,raw);
        entry.put("fieldCoverage",cn.bidcheck.compat.Java8Api.listOf(receipt));entry.put("fieldCoverageSources",cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().map(s->SharedFactReuseTest.source(corpus,s))));
        context.put("sharedFactLookup",(Function<Map<String,Object>,Object>)q->cn.bidcheck.compat.Java8Api.listOf(entry));
        Map<String,Object> completed=evaluate(cp,bid,context);assertEquals("PASS",status(completed,"price"),completed.toString());noNetwork(completed);
        receipt.put("sourceScopeComplete",false);receipt.put("status","FAILED");receipt.put("factIds",cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> failed=evaluate(cp,bid,context);assertEquals("UNDETERMINED",status(failed,"price"),failed.toString());
    }
}

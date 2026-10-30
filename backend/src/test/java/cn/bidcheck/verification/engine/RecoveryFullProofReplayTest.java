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
        return linked("recoveryMode","RECOMPUTE_ONLY","recoveryModelAllowed",false,"recoveryTargetObligationIds",List.of(target),"previousAtomicCheck",Map.of("plan",plan),"priorSteps",steps);
    }
    private Map<String,Object> step(String type,Object output){return linked("type",type,"status","COMPLETED","schemaVersion",VERSION,"modelIdentity",noModel.identity(),"output",JSON.convertValue(output,Object.class));}
    private Rule declaration(){return new Rule("R2","提交履约承诺书","DECLARATION","","material","EXISTS","履约承诺书","",Map.of(),"提交履约承诺书","",List.of());}
    private Checkpoint checkpoint(String requirement){return new Checkpoint("cp","核验投标义务","商务","HIGH","SEMANTIC_RESPONSE","material","EXISTS",requirement,"",true,true,true,null);}
    private String status(Map<String,Object> result,String id){return RuleEvaluator.maps(result.get("obligations")).stream().filter(r->id.equals(r.get("id"))).map(r->str(r,"status")).findFirst().orElse("MISSING");}
    private Map<String,Object> evaluate(Checkpoint cp,Document doc,Map<String,Object> context){return new VerificationEngine(noModel,noRetriever).evaluate(cp,List.of(doc),null,context);}
    private void noNetwork(Map<String,Object> result){assertEquals(0,((Map<?,?>)result.get("metrics")).get("modelCalls"));assertTrue(RuleEvaluator.maps(result.get("steps")).stream().noneMatch(s->Set.of("COMPILE","EXTRACT").contains(s.get("type"))||str(s,"type").startsWith("RETRIEVE_")));}

    @Test void nativePassIsRebuiltFromOldWindowsWhenAnotherConditionIsTargeted(){
        Document bid=SharedFactReuseTest.doc("native","p",1,"本项目投标文件\n投标函\n本单位已充分阅读招标文件全部内容，现提交本投标文件并接受招标文件约定的工作范围，愿意按约定履行相应义务，承担全部合同责任。");
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));
        Rule material=new Rule("R1","提交投标函","MATERIAL","","material","EXISTS","投标函","",Map.of(),"提交投标函","",List.of());
        Plan plan=new Plan("ALL",List.of(material,declaration()),"FIXTURE",List.of());Checkpoint cp=checkpoint("提交投标函；提交履约承诺书");
        Map<String,Object> first=evaluate(cp,bid,local(plan,"R1",List.of(step("EVIDENCE_PLAN_0",Map.of("candidates",corpus.inputs(corpus.all))))));
        assertEquals("PASS",status(first,"R1"),first.toString());assertEquals("UNDETERMINED",status(first,"R2"));
        // Legacy native results had only read windows, without FACT_VALIDATION or a new snapshot.
        List<Map<String,Object>> legacy=List.of(step("EVIDENCE_PLAN_0",Map.of("candidates",corpus.inputs(corpus.all))));
        Map<String,Object> second=evaluate(cp,bid,local(first.get("plan"),"R2",legacy));
        assertEquals("PASS",status(second,"R1"),second.toString());assertEquals("UNDETERMINED",status(second,"R2"));noNetwork(second);
    }

    @Test void sharedPassSurvivesTargetedRecoveryWithoutRequeryingItsField(){
        SharedFactReuseTest fixture=new SharedFactReuseTest();Document bid=SharedFactReuseTest.priceDoc("price","80");EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));
        Rule price=fixture.pricedRule("100");Plan plan=new Plan("ALL",List.of(price,declaration()),"FIXTURE",List.of());
        Checkpoint cp=checkpoint(price.sourceQuote()+"提交履约承诺书");
        Map<String,Object> firstContext=local(plan,"price",List.of(step("EVIDENCE_PLAN_0",Map.of("candidates",corpus.inputs(corpus.all)))));
        firstContext.put("sharedFactLookup",(Function<Map<String,Object>,Object>)q->List.of(SharedFactReuseTest.entry(corpus,SharedFactReuse.value(fixture.pricedFact(corpus)))));
        Map<String,Object> first=evaluate(cp,bid,firstContext);assertEquals("PASS",status(first,"price"),first.toString());
        assertTrue(RuleEvaluator.maps(first.get("steps")).stream().noneMatch(s->str(s,"type").startsWith("FACT_VALIDATION_")));
        List<Map<String,Object>> snapshots=RuleEvaluator.maps(first.get("steps")).stream().filter(s->RecoveryReplay.FACT_SNAPSHOT.equals(s.get("type"))).toList();assertEquals(1,snapshots.size());
        Map<String,Object> secondContext=local(first.get("plan"),"R2",snapshots);
        String oldKey=cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(price));
        secondContext.put("sharedFactLookup",(Function<Map<String,Object>,Object>)q->{assertFalse(ProofVerifier.strings(q.get("observationKeys")).contains(oldKey));assertEquals(Set.of("PERSON_ROLE","RELATION","DECLARATION"),new HashSet<>(ProofVerifier.strings(q.get("factKinds"))));return List.of();});
        Map<String,Object> second=evaluate(cp,bid,secondContext);assertEquals("PASS",status(second,"price"),second.toString());assertEquals("UNDETERMINED",status(second,"R2"));noNetwork(second);
    }

    @Test void snapshotRevalidatesProjectVersionAndEverySourceWithoutDroppingConflicts(){
        Document left=SharedFactReuseTest.priceDoc("left","80"),right=SharedFactReuseTest.priceDoc("right","120");EvidenceCorpus corpus=new EvidenceCorpus(List.of(left,right));
        List<Fact> facts=new ArrayList<>();for(Source source:corpus.all){String value=source.evidence().documentId().equals("left")?"80":"120";
            facts.add(new Fact("old","NUMBER","甲公司","amount",value,value,"元","投标总报价","CURRENT_PROJECT",List.of(source.id()),Map.of("lot","不划分标段","currency","人民币","taxBasis","含税")));}
        Map<String,Object> context=Map.of("priorSteps",List.of(step(RecoveryReplay.FACT_SNAPSHOT,RecoveryReplay.snapshot(corpus,facts))));
        var replay=RecoveryReplay.facts(context,corpus,RecoveryReplay.sources(context,corpus,noModel.identity()),List.of(),noModel.identity());
        assertEquals(Set.of("80","120"),new HashSet<>(replay.facts().stream().map(Fact::value).toList()));
        Fact chain=new Fact("chain",facts.get(0).kind(),facts.get(0).subject(),facts.get(0).field(),facts.get(0).rawValue(),facts.get(0).value(),facts.get(0).unit(),facts.get(0).basis(),facts.get(0).scope(),corpus.all.stream().map(Source::id).toList(),facts.get(0).attributes());
        context=Map.of("priorSteps",List.of(step(RecoveryReplay.FACT_SNAPSHOT,RecoveryReplay.snapshot(corpus,List.of(chain)))));
        EvidenceCorpus partial=new EvidenceCorpus(List.of(left));assertTrue(RecoveryReplay.facts(context,partial,RecoveryReplay.sources(context,partial,noModel.identity()),List.of(),noModel.identity()).facts().isEmpty());
        Document foreign=new Document(left.id(),"other-project",left.role(),left.name(),left.version(),left.sha256(),left.createdAt(),left.pages(),left.warnings());
        EvidenceCorpus foreignCorpus=new EvidenceCorpus(List.of(foreign));assertEquals(partial.all.get(0).id(),foreignCorpus.all.get(0).id(),"source IDs alone cannot establish project ownership");
        context=Map.of("priorSteps",List.of(step(RecoveryReplay.FACT_SNAPSHOT,RecoveryReplay.snapshot(partial,List.of(facts.get(0))))));
        assertTrue(RecoveryReplay.sources(context,foreignCorpus,noModel.identity()).isEmpty());
        assertTrue(RecoveryReplay.facts(context,foreignCorpus,foreignCorpus.all,List.of(),noModel.identity()).facts().isEmpty());
        Document changed=new Document(left.id(),left.projectId(),left.role(),left.name(),2,"new-hash",left.createdAt(),left.pages(),left.warnings());EvidenceCorpus changedCorpus=new EvidenceCorpus(List.of(changed));
        assertTrue(RecoveryReplay.facts(context,changedCorpus,changedCorpus.all,List.of(),noModel.identity()).facts().isEmpty());
    }

    @Test void oldPartialOrTruncatedProofCannotBecomePassByReplayingItsFacts(){
        SharedFactReuseTest fixture=new SharedFactReuseTest();Document bid=SharedFactReuseTest.priceDoc("incomplete","80");EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));Rule price=fixture.pricedRule("100");
        Plan plan=new Plan("ALL",List.of(price,declaration()),"FIXTURE",List.of());Checkpoint cp=checkpoint(price.sourceQuote()+"提交履约承诺书");
        for(String code:List.of("SHARED_SOURCE_REVIEW_REQUIRED","EVIDENCE_LOOKUP_INCOMPLETE")){
            Map<String,Object> snapshot=RecoveryReplay.snapshot(corpus,List.of(fixture.pricedFact(corpus)));
            snapshot.put("coverageDependencies",List.of(linked("obligationId",price.id(),"observationKey",cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(price)),"gapCode",code,"sourceIds",corpus.all.stream().map(Source::id).toList())));
            for(String target:List.of("R2","price")){
                Map<String,Object> result=evaluate(cp,bid,local(plan,target,List.of(step(RecoveryReplay.FACT_SNAPSHOT,snapshot))));
                assertEquals("UNDETERMINED",status(result,"price"),code+" / "+target+" / "+result);
                assertTrue(RuleEvaluator.maps(result.get("gaps")).stream().anyMatch(g->code.equals(g.get("code"))));noNetwork(result);
            }
        }
    }

    @Test void activeRecoveryClosesCoverageOnlyAfterACompleteCurrentSourceCheckedReceipt(){
        SharedFactReuseTest fixture=new SharedFactReuseTest();Document bid=SharedFactReuseTest.priceDoc("read-completion","80");EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));Rule price=fixture.pricedRule("100");
        Plan plan=new Plan("ALL",List.of(price,declaration()),"FIXTURE",List.of());Checkpoint cp=checkpoint(price.sourceQuote()+"提交履约承诺书");
        Fact priceFact=fixture.pricedFact(corpus);Map<String,Object> snapshot=RecoveryReplay.snapshot(corpus,List.of(priceFact));
        snapshot.put("coverageDependencies",List.of(linked("obligationId",price.id(),"observationKey",cn.bidcheck.verification.facts.FieldCoverage.key(SharedFactReuse.observationTarget(price)),"gapCode","SHARED_SOURCE_REVIEW_REQUIRED","sourceIds",priceFact.sourceIds())));
        Map<String,Object> context=local(plan,"price",List.of(step(RecoveryReplay.FACT_SNAPSHOT,snapshot)));
        assertEquals("UNDETERMINED",status(evaluate(cp,bid,context),"price"));
        Map<String,Object> receipt=fixture.readReceipt(price,corpus.all.get(0),List.of("price"));receipt.put("modelIdentity",noModel.identity());
        Map<String,Object> raw=SharedFactReuse.value(priceFact);raw.put("id","price");Map<String,Object> entry=SharedFactReuseTest.entry(corpus,raw);
        entry.put("fieldCoverage",List.of(receipt));entry.put("fieldCoverageSources",corpus.all.stream().map(s->SharedFactReuseTest.source(corpus,s)).toList());
        context.put("sharedFactLookup",(Function<Map<String,Object>,Object>)q->List.of(entry));
        Map<String,Object> completed=evaluate(cp,bid,context);assertEquals("PASS",status(completed,"price"),completed.toString());noNetwork(completed);
        receipt.put("sourceScopeComplete",false);receipt.put("status","FAILED");receipt.put("factIds",List.of());
        Map<String,Object> failed=evaluate(cp,bid,context);assertEquals("UNDETERMINED",status(failed,"price"),failed.toString());
    }
}

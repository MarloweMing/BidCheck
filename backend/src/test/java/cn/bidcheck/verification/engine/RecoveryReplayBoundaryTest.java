package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.retrieval.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class RecoveryReplayBoundaryTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String REQUIREMENT="提交守法承诺书；提交履约承诺书";
    private final ModelGateway noModel=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "replay-boundary";}public JsonNode request(String operation,Map<String,Object> input){fail("Local recovery called model: "+operation);return null;}};
    private final HybridRetriever noRetriever=new HybridRetriever(){@Override public boolean enabled(){return true;}@Override public String identity(){return "no-network-retriever";}@Override public RetrievalResult search(Document document,String query,int limit){fail("Local recovery called retriever");return null;}};
    private Document bid(){return new Document("bid","p","BID","投标.pdf",1,"frozen-hash","",List.of(new Page(1,
        "本项目投标文件\n守法承诺书\n本单位承诺遵守本项目相关法律法规，依法履行全部投标义务并对提交材料承担责任。\n履约承诺书\n本单位承诺在中标后依法依约完成本项目工作，安排所需人员并落实合同约定的全部责任。","PAGE","PDF_TEXT","")),List.of());}
    private Checkpoint cp(){return new Checkpoint("cp","提交两份承诺书","组成检查","HIGH","SEMANTIC_RESPONSE","material","EXISTS",REQUIREMENT,"",true,true,true,null);}
    private Map<String,Object> rule(String id,String name){return linked("id",id,"description","提交"+name,"predicate","DECLARATION","subject","","field","material","operator","EXISTS","expected",name,"unit","","sourceQuote","提交"+name,"parameters",Map.of(),"children",List.of());}
    private Map<String,Object> plan(){return linked("logic","ALL","rules",List.of(rule("R1","守法承诺书"),rule("R2","履约承诺书")),"issues",List.of());}
    private Map<String,Object> fact(Document bid,String name){String sourceId=new EvidenceCorpus(List.of(bid)).all.get(0).id();return linked("id","irrelevant-old-id","kind","DECLARATION","subject","","field","material","rawValue",name,"value",name,"unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(sourceId),"attributes",Map.of());}
    private Map<String,Object> context(Document bid,List<Map<String,Object>> facts){
        List<String> sources=new EvidenceCorpus(List.of(bid)).all.stream().map(Source::id).toList();
        Map<String,Object> step=linked("type","FACT_VALIDATION_0","status","COMPLETED","schemaVersion",VERSION,"modelIdentity",noModel.identity(),"output",linked("facts",facts,"sourceIds",sources));
        return linked("recoveryMode","RECOMPUTE_ONLY","recoveryModelAllowed",false,"recoveryTargetObligationIds",List.of("R2"),
            "previousAtomicCheck",linked("plan",plan(),"complete",true,"businessDecision","PASS","conditions",List.of(Map.of("id","R1","status","PASS"),Map.of("id","R2","status","PASS"))),"priorSteps",List.of(step));
    }
    private Map<String,Object> evaluate(Document bid,Map<String,Object> context){return new VerificationEngine(noModel,noRetriever).evaluate(cp(),List.of(bid),null,context);}
    @Test void validatedObservationsRecomputeAllConditionsWithoutCompileExtractOrRetrieval(){
        Document bid=bid();Map<String,Object> result=evaluate(bid,context(bid,List.of(fact(bid,"守法承诺书"),fact(bid,"履约承诺书"))));
        assertEquals("PASS",result.get("businessDecision"));assertEquals(0,((Map<?,?>)result.get("metrics")).get("modelCalls"));
        assertEquals(2,RuleEvaluator.maps(result.get("obligations")).size());
        assertTrue(RuleEvaluator.maps(result.get("steps")).stream().noneMatch(s->Set.of("COMPILE","EXTRACT").contains(s.get("type"))||str(s,"type").startsWith("RETRIEVE_")));
    }
    @Test void savedPassFlagsCannotSubstituteForMissingProofOrDropTheOtherCondition(){
        Document bid=bid();Map<String,Object> result=evaluate(bid,context(bid,List.of(fact(bid,"守法承诺书"))));
        assertEquals("UNDETERMINED",result.get("businessDecision"));List<Map<String,Object>> decisions=RuleEvaluator.maps(result.get("obligations"));
        assertEquals(2,decisions.size());assertEquals("PASS",decisions.stream().filter(d->"R1".equals(d.get("id"))).findFirst().orElseThrow().get("status"));
        assertEquals("UNDETERMINED",decisions.stream().filter(d->"R2".equals(d.get("id"))).findFirst().orElseThrow().get("status"));
        assertFalse(Boolean.TRUE.equals(result.get("complete")));
    }
    @Test void changedFrozenDocumentRejectsPreviousReadsEvenWhenVisibleTextIsTheSame(){
        Document before=bid();Map<String,Object> context=context(before,List.of(fact(before,"守法承诺书"),fact(before,"履约承诺书")));
        Document changed=new Document(before.id(),before.projectId(),before.role(),before.name(),2,"new-hash","",before.pages(),before.warnings());
        Map<String,Object> result=evaluate(changed,context);assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(((List<?>)result.get("facts")).isEmpty());
    }
    @Test void ungroundedPreviouslyAcceptedValueIsRejectedAgain(){
        Document bid=bid();Map<String,Object> forged=fact(bid,"履约承诺书");forged.put("rawValue","并不存在于原文的证明");
        Map<String,Object> result=evaluate(bid,context(bid,List.of(fact(bid,"守法承诺书"),forged)));
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertTrue(RuleEvaluator.maps(result.get("rejectedFacts")).stream().anyMatch(r->"RAW_VALUE_UNGROUNDED".equals(r.get("code"))));
        assertEquals(2,RuleEvaluator.maps(result.get("obligations")).size());
    }
    @Test void stepsFromOtherModelOrSchemaAreNotTrustedAsCurrentReads(){
        for(String property:List.of("modelIdentity","schemaVersion","status")){
            Document bid=bid();Map<String,Object> context=context(bid,List.of(fact(bid,"守法承诺书"),fact(bid,"履约承诺书")));
            Map<String,Object> step=new LinkedHashMap<>(RuleEvaluator.maps(context.get("priorSteps")).get(0));step.put(property,"foreign");context.put("priorSteps",List.of(step));
            assertEquals("UNDETERMINED",evaluate(bid,context).get("businessDecision"),property);
        }
    }
    @Test void changedTenderSourceCannotReuseAOnceValidPlan(){
        Document bid=bid();Map<String,Object> context=context(bid,List.of(fact(bid,"守法承诺书"),fact(bid,"履约承诺书")));
        Checkpoint sourced=new Checkpoint("cp",cp().title(),cp().category(),cp().severity(),cp().type(),cp().field(),cp().operator(),REQUIREMENT,"",true,true,true,
            new Evidence("tender","招标.docx",1,"BLOCK",REQUIREMENT,"",1));
        Document tender=new Document("tender","p","TENDER","招标.docx",2,"new-tender-hash","",List.of(new Page(1,"提交另一份承诺书","BLOCK","DOCX","")),List.of());
        Map<String,Object> result=new VerificationEngine(noModel,noRetriever).evaluate(sourced,List.of(bid),tender,context);
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals("SCOPE_UNRESOLVED",JSON.valueToTree(result.get("plan")).path("method").asText());
    }
    @Test void oldUnsupportedMetadataCannotAuthorizeAnUngroundedRepairedRule(){
        Document bid=bid();Map<String,Object> context=context(bid,List.of());Map<String,Object> bad=rule("R1","守法承诺书");
        bad.put("predicate","UNSUPPORTED");bad.put("parameters",Map.of("originalPredicate","DECLARATION"));bad.put("expected","免除全部投标义务");
        context.put("previousAtomicCheck",Map.of("plan",Map.of("logic","ALL","rules",List.of(bad,rule("R2","履约承诺书")))));
        Map<String,Object> result=evaluate(bid,context);assertEquals("UNDETERMINED",result.get("businessDecision"));
        assertTrue(RuleEvaluator.maps(result.get("obligations")).stream().anyMatch(d->"UNDETERMINED".equals(d.get("status"))));
    }
    @Test void priorNativeReadWindowsAreReplayedWithoutInventingAFactValidationPacket(){
        Document bid=bid();Map<String,Object> context=context(bid,List.of());EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));
        context.put("priorSteps",List.of(linked("type","EVIDENCE_PLAN_0","status","COMPLETED","schemaVersion",VERSION,"modelIdentity",noModel.identity(),
            "output",Map.of("candidates",corpus.inputs(corpus.all)))));
        assertEquals(corpus.all,RecoveryReplay.sources(context,corpus,noModel.identity()));
        Document changed=new Document(bid.id(),bid.projectId(),bid.role(),bid.name(),2,"other-hash","",bid.pages(),bid.warnings());
        assertTrue(RecoveryReplay.sources(context,new EvidenceCorpus(List.of(changed)),noModel.identity()).isEmpty());
        assertTrue(RecoveryReplay.facts(context,corpus.all,List.of(),noModel.identity()).facts().isEmpty(),"A read window itself is not an extracted fact");
    }
}

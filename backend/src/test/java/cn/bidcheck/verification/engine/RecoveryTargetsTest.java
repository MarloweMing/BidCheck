package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class RecoveryTargetsTest {
    private final ObjectMapper json=new ObjectMapper();
    private Rule rule(String id,String predicate,String subject,String expected){return new Rule(id,"提交"+expected,predicate,subject,"material","EXISTS",expected,"",Map.of(),"提交"+expected,"",List.of());}
    private Map<String,Object> targeted(String id){return linked("recoveryMode","TARGETED","recoveryStrategy","TARGETED_EVIDENCE","recoveryTargetObligationIds",List.of(id),"allowModelRuleCompilation",true);}
    @Test void onlyNamedObligationsAndTheirSubjectPrerequisitesGetNewReads(){
        Rule target=rule("certificate","CERTIFICATE","设计负责人","注册证书"),role=rule("role","PERSON_ROLE","设计负责人",""),otherRole=rule("otherRole","PERSON_ROLE","施工负责人",""),material=rule("unrelated","MATERIAL","设计负责人","学历证明");
        List<Rule> all=List.of(target,role,otherRole,material);
        assertEquals(List.of(target,role),RecoveryTargets.select(all,all,targeted("certificate")));
        assertTrue(RecoveryTargets.select(all,all,targeted("unknown")).isEmpty());
        assertTrue(RecoveryTargets.select(all,all,Map.of("recoveryStrategy","SPLIT_FAILED_REQUEST","recoveryTargetObligationIds",List.of())).isEmpty());
        assertEquals(all,RecoveryTargets.select(all,all,Map.of("recoveryStrategy","SPLIT_FAILED_REQUEST")),"Legacy resume has no declared target selector");
        assertEquals(all,RecoveryTargets.select(all,all,Map.of()));
    }
    @Test void explicitParentIncludesItsOwnChildrenButNotAnUnrelatedBranch(){
        Rule target=rule("target","CERTIFICATE","设计负责人","注册证书"),sibling=rule("sibling","MATERIAL","设计负责人","其他证明"),unrelated=rule("outside","MATERIAL","施工负责人","安全证书");
        Rule parent=new Rule("parent","条件组","GROUP","","","","","",Map.of(),"提交两份材料","ALL",List.of(target,sibling));
        assertEquals(List.of(target,sibling),RecoveryTargets.select(List.of(parent,unrelated),List.of(target,sibling,unrelated),targeted("parent")));
        assertEquals(List.of(target),RecoveryTargets.select(List.of(parent,unrelated),List.of(target,sibling,unrelated),targeted("target")));
    }
    @Test void conditionalTargetRetainsTheApplicabilityProofAndParentOwnPredicate(){
        Rule gate=rule("gate","PERSON_ROLE","设计负责人",""),target=rule("target","CERTIFICATE","设计负责人","注册证书"),other=rule("otherwise","MATERIAL","","替代证明");
        Rule parent=new Rule("parent","复合要求","MATERIAL","设计负责人","material","EXISTS","资格材料","",Map.of(),"条件要求","CONDITIONAL",List.of(gate,target,other));
        assertEquals(List.of(RuleObligations.own(parent),gate,target),RecoveryTargets.select(List.of(parent),List.of(RuleObligations.own(parent),gate,target,other),targeted("target")));
    }
    private Document bid(){return new Document("bid","p","BID","投标.pdf",1,"hash","",List.of(new Page(1,"本项目投标文件\n守法承诺书\n本单位依法投标，承诺遵守国家法律法规并履行本项目要求和相关责任。\n履约承诺书\n本单位承诺依法依约完成本项目工作，按时配备人员并承担相关合同责任。","PAGE","PDF_TEXT","")),List.of());}
    private Map<String,Object> fact(String name,String source){return linked("kind","DECLARATION","subject","","field","material","rawValue",name,"value",name,"unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(source),"attributes",Map.of());}
    private Map<String,Object> execute(boolean withPriorProof){
        Document bid=bid();EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));String source=corpus.all.get(0).id();Rule one=rule("R1","DECLARATION","","守法承诺书"),two=rule("R2","DECLARATION","","履约承诺书");List<Rule> rules=List.of(one,two);
        AtomicInteger extracts=new AtomicInteger();ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "target-only";}public JsonNode request(String operation,Map<String,Object> input){
            if("COMPILE".equals(operation))return json.valueToTree(Map.of("logic","ALL","rules",rules));
            assertEquals("EXTRACT",operation);extracts.incrementAndGet();assertEquals(List.of("R2"),ExtractionBatches.rules(input).stream().map(Rule::id).toList(),"Non-target old failures must not become new paid requests");
            return json.valueToTree(Map.of("facts",List.of(fact("履约承诺书",source))));
        }};
        Map<String,Object> context=targeted("R2");context.put("previousAtomicCheck",Map.of("plan",Map.of("logic","ALL","rules",rules),"conditions",List.of(Map.of("id","R1","status","PASS"),Map.of("id","R2","status","PASS"))));
        context.put("priorSteps",List.of(linked("type","FACT_VALIDATION_0","status","COMPLETED","schemaVersion",VERSION,"modelIdentity",model.identity(),
            "output",linked("facts",withPriorProof?List.of(fact("守法承诺书",source)):List.of(),"sourceIds",List.of(source)))));
        Checkpoint cp=new Checkpoint("cp","两份承诺","组成","HIGH","SEMANTIC_RESPONSE","material","EXISTS","提交守法承诺书；提交履约承诺书","",true,true,true,null);
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp,List.of(bid),null,context);
        assertEquals(1,extracts.get());assertEquals(2,RuleEvaluator.maps(result.get("obligations")).size());return result;
    }
    @Test void targetedExtractionKeepsPreviouslyGroundedIndependentConditions(){assertEquals("PASS",execute(true).get("businessDecision"));}
    @Test void invalidNonTargetOldPassRemainsPendingAndIsNotSilentlyOmitted(){
        Map<String,Object> result=execute(false);assertEquals("UNDETERMINED",result.get("businessDecision"));
        assertEquals("UNDETERMINED",RuleEvaluator.maps(result.get("obligations")).stream().filter(d->"R1".equals(d.get("id"))).findFirst().orElseThrow().get("status"));
        assertEquals("PASS",RuleEvaluator.maps(result.get("obligations")).stream().filter(d->"R2".equals(d.get("id"))).findFirst().orElseThrow().get("status"));
    }
}

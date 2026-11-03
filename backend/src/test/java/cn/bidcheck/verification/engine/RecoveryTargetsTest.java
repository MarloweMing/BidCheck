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
    private Rule rule(String id,String predicate,String subject,String expected){return new Rule(id,"提交"+expected,predicate,subject,"material","EXISTS",expected,"",cn.bidcheck.compat.Java8Api.mapOf(),"提交"+expected,"",cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> targeted(String id){return linked("recoveryMode","TARGETED","recoveryStrategy","TARGETED_EVIDENCE","recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf(id),"allowModelRuleCompilation",true);}
    @Test void onlyNamedObligationsAndTheirSubjectPrerequisitesGetNewReads(){
        Rule target=rule("certificate","CERTIFICATE","设计负责人","注册证书"),role=rule("role","PERSON_ROLE","设计负责人",""),otherRole=rule("otherRole","PERSON_ROLE","施工负责人",""),material=rule("unrelated","MATERIAL","设计负责人","学历证明");
        List<Rule> all=cn.bidcheck.compat.Java8Api.listOf(target, role, otherRole, material);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(target, role),RecoveryTargets.select(all,all,targeted("certificate")));
        assertTrue(RecoveryTargets.select(all,all,targeted("unknown")).isEmpty());
        assertTrue(RecoveryTargets.select(all,all,cn.bidcheck.compat.Java8Api.mapOf("recoveryStrategy", "SPLIT_FAILED_REQUEST", "recoveryTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf())).isEmpty());
        assertEquals(all,RecoveryTargets.select(all,all,cn.bidcheck.compat.Java8Api.mapOf("recoveryStrategy", "SPLIT_FAILED_REQUEST")),"Legacy resume has no declared target selector");
        assertEquals(all,RecoveryTargets.select(all,all,cn.bidcheck.compat.Java8Api.mapOf()));
    }
    @Test void explicitParentIncludesItsOwnChildrenButNotAnUnrelatedBranch(){
        Rule target=rule("target","CERTIFICATE","设计负责人","注册证书"),sibling=rule("sibling","MATERIAL","设计负责人","其他证明"),unrelated=rule("outside","MATERIAL","施工负责人","安全证书");
        Rule parent=new Rule("parent","条件组","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"提交两份材料","ALL",cn.bidcheck.compat.Java8Api.listOf(target, sibling));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(target, sibling),RecoveryTargets.select(cn.bidcheck.compat.Java8Api.listOf(parent, unrelated),cn.bidcheck.compat.Java8Api.listOf(target, sibling, unrelated),targeted("parent")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(target),RecoveryTargets.select(cn.bidcheck.compat.Java8Api.listOf(parent, unrelated),cn.bidcheck.compat.Java8Api.listOf(target, sibling, unrelated),targeted("target")));
    }
    @Test void localRecomputeDoesNotExpandChangedDependenciesToEveryUnresolvedMaterial(){
        Rule target=rule("certificate","CERTIFICATE","设计负责人","注册证书"),role=rule("role","PERSON_ROLE","设计负责人",""),unrelated=rule("other","MATERIAL","设计负责人","另一份证明");
        Map<String,Object> context=linked("recoveryMode","RECOMPUTE_ONLY","recoveryModelAllowed",false,"recoveryStrategy","VERIFY_SOURCE_PAGES","recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("certificate"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(target, role),RecoveryTargets.select(cn.bidcheck.compat.Java8Api.listOf(target, role, unrelated),cn.bidcheck.compat.Java8Api.listOf(target, role, unrelated),context));
    }
    @Test void conditionalTargetRetainsTheApplicabilityProofAndParentOwnPredicate(){
        Rule gate=rule("gate","PERSON_ROLE","设计负责人",""),target=rule("target","CERTIFICATE","设计负责人","注册证书"),other=rule("otherwise","MATERIAL","","替代证明");
        Rule parent=new Rule("parent","复合要求","MATERIAL","设计负责人","material","EXISTS","资格材料","",cn.bidcheck.compat.Java8Api.mapOf(),"条件要求","CONDITIONAL",cn.bidcheck.compat.Java8Api.listOf(gate, target, other));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(RuleObligations.own(parent), gate, target),RecoveryTargets.select(cn.bidcheck.compat.Java8Api.listOf(parent),cn.bidcheck.compat.Java8Api.listOf(RuleObligations.own(parent), gate, target, other),targeted("target")));
    }
    private Document bid(){return new Document("bid","p","BID","投标.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"本项目投标文件\n守法承诺书\n本单位依法投标，承诺遵守国家法律法规并履行本项目要求和相关责任。\n履约承诺书\n本单位承诺依法依约完成本项目工作，按时配备人员并承担相关合同责任。","PAGE","PDF_TEXT","")),cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> fact(String name,String source){return linked("kind","DECLARATION","subject","","field","material","rawValue",name,"value",name,"unit","","basis","","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf());}
    private Map<String,Object> execute(boolean withPriorProof){
        Document bid=bid();EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid));String source=corpus.all.get(0).id();Rule one=rule("R1","DECLARATION","","守法承诺书"),two=rule("R2","DECLARATION","","履约承诺书");List<Rule> rules=cn.bidcheck.compat.Java8Api.listOf(one, two);
        AtomicInteger extracts=new AtomicInteger();ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "target-only";}public JsonNode request(String operation,Map<String,Object> input){
            if("COMPILE".equals(operation))return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", rules));
            assertEquals("EXTRACT",operation);extracts.incrementAndGet();assertEquals(cn.bidcheck.compat.Java8Api.listOf("R2"),cn.bidcheck.compat.Java8Api.toList(ExtractionBatches.rules(input).stream().map(Rule::id)),"Non-target old failures must not become new paid requests");
            return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact("履约承诺书",source))));
        }};
        Map<String,Object> context=targeted("R2");context.put("previousAtomicCheck",cn.bidcheck.compat.Java8Api.mapOf("plan", cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", rules), "conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "R1", "status", "PASS"), cn.bidcheck.compat.Java8Api.mapOf("id", "R2", "status", "PASS"))));
        context.put("priorSteps",cn.bidcheck.compat.Java8Api.listOf(linked("type","FACT_VALIDATION_0","status","COMPLETED","schemaVersion",VERSION,"modelIdentity",model.identity(),
            "output",linked("facts",withPriorProof?cn.bidcheck.compat.Java8Api.listOf(fact("守法承诺书",source)):cn.bidcheck.compat.Java8Api.listOf(),"sourceIds",cn.bidcheck.compat.Java8Api.listOf(source)))));
        Checkpoint cp=new Checkpoint("cp","两份承诺","组成","HIGH","SEMANTIC_RESPONSE","material","EXISTS","提交守法承诺书；提交履约承诺书","",true,true,true,null);
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid),null,context);
        assertEquals(1,extracts.get());assertEquals(2,RuleEvaluator.maps(result.get("obligations")).size());return result;
    }
    @Test void targetedExtractionKeepsPreviouslyGroundedIndependentConditions(){assertEquals("PASS",execute(true).get("businessDecision"));}
    @Test void invalidNonTargetOldPassRemainsPendingAndIsNotSilentlyOmitted(){
        Map<String,Object> result=execute(false);assertEquals("UNDETERMINED",result.get("businessDecision"));
        assertEquals("UNDETERMINED",RuleEvaluator.maps(result.get("obligations")).stream().filter(d->"R1".equals(d.get("id"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("status"));
        assertEquals("PASS",RuleEvaluator.maps(result.get("obligations")).stream().filter(d->"R2".equals(d.get("id"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("status"));
    }
}

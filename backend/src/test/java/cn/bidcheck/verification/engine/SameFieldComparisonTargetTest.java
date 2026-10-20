package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SameFieldComparisonTargetTest {
    private Rule rule(String quote,String expected){return new Rule("R5",quote,"SAME_FIELD","张某","查询截图信息","=",expected,"",Map.of(),quote,"",List.of());}
    private EvidenceCorpus corpus(){return new EvidenceCorpus(List.of(new Document("b","p","BID","投标文件",1,"hash","",List.of(new Page(1,"姓名：张某\n注册编号：A001","PAGE","TEXT",null)),List.of())));}
    private Fact fact(EvidenceCorpus corpus,String id,String value){return new Fact(id,"FIELD","张某","查询截图信息",value,value,"","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of());}
    @Test void referenceLabelIsNeverAnActualComparisonValue(){
        var c=corpus();var r=rule("查询截图信息应与证明文件信息一致","证明文件信息");var facts=List.of(fact(c,"f","注册编号A001"));
        var d=new RuleEvaluator(facts,c,Map.of()).evaluate(r);
        assertEquals("UNDETERMINED",d.status());assertEquals("REQUIREMENT_COMPARISON_TARGET_UNRESOLVED",d.gaps().get(0).code());
        assertEquals(true,d.proof().get("literalTargetComparisonRejected"));assertEquals(false,d.proof().get("materialMissingProven"));
    }
    @Test void compilerRetainsSourceRelationAndDoesNotInventConcreteRhs(){
        String quote="查询截图信息应与证明文件信息一致";var r=rule(quote,"证明文件信息");
        Checkpoint cp=new Checkpoint("c",quote,"资格","HIGH","SEMANTIC_RESPONSE","","",quote,"",true,true,true,null);
        var plan=new RequirementCompiler().compileResponse(cp,new ObjectMapper().valueToTree(Map.of("logic","ALL","rules",List.of(r))),quote,true,Set.of("CERTIFICATE_VALIDITY"));
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());Rule compiled=plan.rules().get(0);
        assertEquals("",compiled.expected());assertEquals("SOURCE_REFERENCE",compiled.parameters().get("comparisonOperandKind"));
        assertEquals("证明文件信息",compiled.parameters().get("comparisonTargetReference"));
    }
    @Test void twoEqualSnapshotsDoNotProveUnspecifiedDocumentInformationComplete(){
        var c=corpus();var facts=List.of(fact(c,"f1","A001"),fact(c,"f2","A001"));
        for(String expected:List.of("","证明文件信息","A001"))assertEquals("UNDETERMINED",new RuleEvaluator(facts,c,Map.of()).evaluate(rule("查询截图信息与证明文件信息一致",expected)).status());
    }
    @Test void directKnownValueAndActualConflictingValuesRemainComparable(){
        var c=corpus();var r=rule("查询截图中注册编号应为A001","A001");
        assertEquals("PASS",new RuleEvaluator(List.of(fact(c,"f","A001")),c,Map.of()).sameField(r,List.of(fact(c,"f","A001"))).status());
        assertEquals("FAIL",new RuleEvaluator(List.of(fact(c,"f","A002")),c,Map.of()).sameField(r,List.of(fact(c,"f","A002"))).status());
        var pair=List.of(fact(c,"a","A001"),fact(c,"b","A002"));
        assertEquals("FAIL",new RuleEvaluator(pair,c,Map.of()).sameField(rule("同一注册编号各处一致",""),pair).status());
    }
    @Test void forgedComparisonMetadataCannotChangeAnExplicitLiteral(){
        var original=rule("注册编号为A001","A001");var forged=new Rule(original.id(),original.description(),original.predicate(),original.subject(),original.field(),original.operator(),original.expected(),original.unit(),Map.of("comparisonOperandKind","SOURCE_REFERENCE","comparisonTargetReference","证明文件信息"),original.sourceQuote(),"",List.of());
        var normalized=SameFieldComparisonTarget.normalize(forged);assertEquals("A001",normalized.expected());assertFalse(normalized.parameters().containsKey("comparisonOperandKind"));
    }
    @Test void sourceReferenceVariantsAreRecognizedWithoutRealSampleNames(){
        for(String quote:List.of("查询结果与证书内容应完全一致","投标文件记载和登记信息保持一致","扫描件须与原件一致",
            "查询截图编号与证书编号一致","查询截图姓名与证书姓名一致","查询截图有效期与证书有效期一致",
            "查询截图注册单位与证书注册单位一致","查询截图金额与证明文件金额一致","查询截图日期与证书发证日期一致",
            "查询截图编号与证明文件中的“编号”一致","查询截图姓名与该证明文件记载姓名一致"))assertFalse(SameFieldComparisonTarget.reference(rule(quote,"")).isBlank(),quote);
        assertTrue(SameFieldComparisonTarget.reference(rule("项目名称与‘北方中心工程’一致","北方中心工程")).isBlank());
        for(String quote:List.of("单位名称与证书科技有限公司一致","项目名称与“证书”一致","注册编号与A001一致"))assertTrue(SameFieldComparisonTarget.reference(rule(quote,"")).isBlank(),quote);
    }
    @Test void referenceFieldNamesCannotCauseLiteralFailure(){
        var c=corpus();var facts=List.of(fact(c,"f","A001"));
        for(String target:List.of("证书编号","证书姓名","证书有效期","证书注册单位","证明文件金额")) {
            var d=new RuleEvaluator(facts,c,Map.of()).sameField(rule("查询截图相应字段与"+target+"一致",target),facts);
            assertEquals("UNDETERMINED",d.status(),target);assertEquals("REQUIREMENT_COMPARISON_TARGET_UNRESOLVED",d.gaps().get(0).code());
        }
    }
    @Test void referenceOnlyTypedRuleCanReadLocalSourcesWithoutCallingAModel(){
        String quote="查询截图编号与证书编号一致";AtomicInteger calls=new AtomicInteger();
        ModelGateway gateway=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "unresolved-reference";}public JsonNode request(String op,Map<String,Object> input){calls.incrementAndGet();throw new AssertionError("Reference without field mapping must not invoke "+op);}};
        Document tender=new Document("t","p","TENDER","招标文件",1,"t-hash","",List.of(new Page(1,quote,"PARAGRAPH","TEXT",null)),List.of());
        Checkpoint cp=new Checkpoint("c",quote,"资格","HIGH","ENTITY_FIELD_CONSISTENCY","查询截图编号","=","证书编号","",true,true,true,new Evidence("t","招标文件",1,"PARAGRAPH",quote,"p1",1));
        var result=new VerificationEngine(gateway,null).evaluate(cp,corpus().documents,tender,Map.of());
        assertEquals(0,calls.get());assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals("REQUIREMENT_UNPARSED",result.get("workflowState"));
        assertTrue(((Number)((Map<?,?>)result.get("metrics")).get("rounds")).intValue()<=1);
        var actions=RuleEvaluator.maps(result.get("recoveryActions"));assertFalse(actions.isEmpty());
        assertTrue(actions.stream().allMatch(a->"REPAIR_REQUIREMENT".equals(a.get("strategy"))));
        var gaps=RuleEvaluator.maps(result.get("gaps"));assertEquals("REQUIREMENT_MAPPING_CHANGED",gaps.get(0).get("resumeTrigger"));
    }
    @Test void unrelatedExecutableObligationStillReadsSourcesWhileReferenceWaitsForMapping(){
        ObjectMapper json=new ObjectMapper();AtomicInteger extracts=new AtomicInteger();
        String material="须提交守法承诺书",relation="查询截图信息与证明文件信息一致",quote=material+"。"+relation;
        ModelGateway gateway=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "mixed-reference";}public JsonNode request(String op,Map<String,Object> input){
            if(op.equals("COMPILE"))return json.valueToTree(Map.of("logic","ALL","rules",List.of(
                Map.of("id","M","description",material,"predicate","DECLARATION","expected","守法承诺书","sourceQuote",material),
                Map.of("id","R5","description",relation,"predicate","SAME_FIELD","field","查询截图信息","expected","证明文件信息","sourceQuote",relation))));
            assertEquals("EXTRACT",op);extracts.incrementAndGet();
            JsonNode sent=json.valueToTree(input.get("rules"));assertFalse(sent.toString().contains("R5"));
            var source=RuleEvaluator.maps(input.get("sources")).get(0);
            return json.valueToTree(Map.of("facts",List.of(Map.of("kind","DECLARATION","rawValue","守法承诺书","value","守法承诺书","scope","CURRENT_PROJECT","sourceIds",List.of(source.get("id"))))));
        }};
        Document bid=new Document("b","p","BID","投标文件",1,"hash","",List.of(new Page(1,"守法承诺书\n我们承诺遵守法律并承担相关责任。"+"相关义务按照本项目招标要求履行，所有声明均由本公司出具。".repeat(4),"PAGE","TEXT",null)),List.of());
        Checkpoint cp=new Checkpoint("c",quote,"资格","HIGH","SEMANTIC_RESPONSE","","",quote,"",true,true,true,null);
        var result=new VerificationEngine(gateway,null).evaluate(cp,List.of(bid),null,Map.of("allowModelRuleCompilation",true));
        assertTrue(extracts.get()>0);assertEquals("UNDETERMINED",result.get("businessDecision"));
        var decisions=RuleEvaluator.maps(result.get("obligations"));
        assertTrue(decisions.stream().anyMatch(d->"M".equals(d.get("id"))&&"PASS".equals(d.get("status"))));
        assertTrue(decisions.stream().anyMatch(d->"R5".equals(d.get("id"))&&"UNDETERMINED".equals(d.get("status"))));
        assertTrue(RuleEvaluator.maps(result.get("recoveryActions")).stream().filter(a->"R5".equals(a.get("obligationId"))).allMatch(a->"REPAIR_REQUIREMENT".equals(a.get("strategy"))));
    }
}

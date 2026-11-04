package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class BidLetterResponseTest {
    private static final String PROJECT="北方新城中心综合楼二次装修工程";
    private static final String QUALITY="质量\t合格。（见投标函）",VALIDITY="投标有效期\t满足招标文件规定。（见投标函）";
    private Document tender(String requirement,String period){return new Document("t","p","TENDER","招标文件",1,"th","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,PROJECT,"BLOCK","TEXT",null), new Page(2,requirement,"BLOCK","TEXT",null), new Page(3,"3.4.1\t投标有效期\t投标有效期："+period+"天","BLOCK","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());}
    private Document bid(String quality,String period,String project){return new Document("b","p","BID","投标文件",1,"bh","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"（"+PROJECT+"）\n投标文件\n投标人：北方装饰有限公司","PAGE","TEXT",null), new Page(7,"3.投标函\n（"+project+"）投标文件\n我方已仔细研究本项目招标文件的全部内容。\n质量为 "+quality+" （填写“合格”）。\n在招标文件规定的投标有效期（投标有效期为 "+period+" 天）内不修改、撤销投标文件。\n投标人：北方装饰有限公司。","PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());}
    private Decision evaluate(String quote,Document tender,Document bid)throws Exception{
        Checkpoint cp=new Checkpoint("cp","响应要求","响应","HIGH","SEMANTIC_RESPONSE","compliance","RESPONDS",quote,"",true,true,true,new Evidence("t","招标文件",2,"BLOCK",quote,"p2",1));
        ModelGateway model=new ModelGateway(){public boolean enabled(){return false;}public String identity(){return "offline";}public com.fasterxml.jackson.databind.JsonNode request(String op,Map<String,Object> in){throw new AssertionError("no model");}};
        RunSession session=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("originalRequirement", quote, "tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(tender), "requirementProjectId", "p", "focusedCheck", true),"test");
        Plan plan=new RequirementCompiler().compile(cp,session);assertTrue(plan.issues().isEmpty(),plan.toString());
        assertEquals(1,plan.rules().size());assertEquals("DECLARATION",plan.rules().get(0).predicate(),plan.toString());
        return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid)),cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(tender))).evaluate(plan.rules().get(0));
    }
    @Test void filledQualityFieldHasSourceBoundResponse()throws Exception{Decision d=evaluate(QUALITY,tender(QUALITY,"180"),bid("合格","180",PROJECT));assertEquals("PASS",d.status(),d.toString());assertEquals("QUALITY_UNDERTAKING_ONLY",d.proof().get("proofScope"));}
    @Test void blankInstructionAndForeignLetterDoNotPass()throws Exception{for(String value:cn.bidcheck.compat.Java8Api.listOf("", "____", "待填写"))assertNotEquals("PASS",evaluate(QUALITY,tender(QUALITY,"180"),bid(value,"180",PROJECT)).status());assertNotEquals("PASS",evaluate(QUALITY,tender(QUALITY,"180"),bid("合格","180","另一历史工程" )).status());}
    @Test void adverseQualityIsRisk()throws Exception{assertEquals("FAIL",evaluate(QUALITY,tender(QUALITY,"180"),bid("不合格","180",PROJECT)).status());}
    @Test void validityComparesObservedNumberToTenderBaseline()throws Exception{assertEquals("PASS",evaluate(VALIDITY,tender(VALIDITY,"180"),bid("合格","180",PROJECT)).status());assertEquals("FAIL",evaluate(VALIDITY,tender(VALIDITY,"180"),bid("合格","90",PROJECT)).status());assertNotEquals("PASS",evaluate(VALIDITY,tender(VALIDITY,"180"),bid("合格","满足",PROJECT)).status());}
    @Test void numericValidityAlsoReadsTheExplicitDurationFromTheBidLetter(){
        Rule rule=new Rule("duration","投标有效期","NUMBER","","bidValidity",">=","180","天",
            linked("deterministicFamily","SOURCE_VERIFIED_CONFIRMED_DURATION"),"投标有效期至少180天","",Collections.emptyList());
        for(String period:Arrays.asList("180","90")){
            EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(bid("合格",period,PROJECT)));
            Decision d=new RuleEvaluator(Collections.emptyList(),corpus,Collections.emptyMap()).evaluate(rule);
            assertEquals("180".equals(period)?"PASS":"FAIL",d.status(),d.toString());
        }
    }
    @Test void unsupportedExtraObligationCannotBeDropped(){assertNull(BidLetterResponse.compile(QUALITY+"且必须提交质量体系认证证书。"));}
    private Document append(Document original,int page,String suffix){return new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),cn.bidcheck.compat.Java8Api.toList(original.pages().stream().map(p->p.number()==page?new Page(p.number(),p.text()+suffix,p.locationType(),p.parser(),p.warning(),p.layout()):p)),original.warnings());}
    @Test void filledInstructionsCannotReplaceAnEmptyResponse()throws Exception{
        assertNotEquals("PASS",evaluate(QUALITY,tender(QUALITY,"180"),append(bid("","180",PROJECT),7,"\n填写说明\n质量：合格。" )).status());
        assertNotEquals("PASS",evaluate(VALIDITY,tender(VALIDITY,"180"),append(bid("合格","",PROJECT),7,"\n编制说明\n投标有效期：180天。" )).status());
    }
    @Test void equalDaysCannotHideADifferentStartingEvent()throws Exception{
        Document tender=append(tender(VALIDITY,"180"),3,"。投标有效期自投标截止之日起计算。");
        Document bid=append(bid("合格","180",PROJECT),7,"\n投标有效期自开标之日起计算。");
        assertEquals("FAIL",evaluate(VALIDITY,tender,bid).status());
        assertEquals("PASS",evaluate(VALIDITY,tender,append(bid("合格","180",PROJECT),7,"\n投标有效期自投标截止之日起计算。" )).status());
    }
    @Test void fullEngineReadsExplicitLetterFieldsWithoutPaidExtraction(){
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "letter-local";}public com.fasterxml.jackson.databind.JsonNode request(String op,Map<String,Object> input){throw new AssertionError("literal response must not call "+op);}};
        for(String quote:cn.bidcheck.compat.Java8Api.listOf(QUALITY, VALIDITY)){
            Checkpoint cp=new Checkpoint("cp","响应要求","响应","HIGH","SEMANTIC_RESPONSE","compliance","RESPONDS",quote,"",true,true,true,new Evidence("t","招标文件",2,"BLOCK",quote,"p2",1));
            Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid("合格","180",PROJECT)),tender(quote,"180"),cn.bidcheck.compat.Java8Api.mapOf());
            assertEquals("PASS",result.get("businessDecision"),result.toString());assertEquals(0,((Map<?,?>)result.get("metrics")).get("modelCalls"));
            assertTrue(RuleEvaluator.maps(result.get("steps")).stream().anyMatch(step->"PROGRAM_SOURCE_READ".equals(step.get("type"))));
            java.util.Map<java.lang.String, java.lang.Object> obligation=RuleEvaluator.maps(result.get("obligations")).get(0);assertTrue(((Map<?,?>)obligation.get("proof")).containsKey("programSourceRead"));
            java.util.Map<java.lang.String, java.lang.Object> replay=new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid("合格","180",PROJECT)),tender(quote,"180"),cn.bidcheck.compat.Java8Api.mapOf("recoveryMode", "EVIDENCE_RECOMPUTE", "recoveryModelAllowed", false, "previousAtomicCheck", result));assertEquals("PASS",replay.get("businessDecision"),replay.toString());
            java.util.Map<java.lang.String, java.lang.Object> changed=new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid("不合格","90",PROJECT)),tender(quote,"180"),cn.bidcheck.compat.Java8Api.mapOf("recoveryMode", "EVIDENCE_RECOMPUTE", "recoveryModelAllowed", false, "previousAtomicCheck", result));assertEquals("FAIL",changed.get("businessDecision"),changed.toString());
        }
    }
}

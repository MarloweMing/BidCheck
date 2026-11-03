package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementBusinessRoutingTest {
    private Checkpoint cp(String source,boolean required){return new Checkpoint("cp",source,"资格","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",source,"",required,true,true,new Evidence("t","招标",1,"BLOCK",source,"",1));}
    private Rule route(String source){return RequirementBusinessRouting.compile(cp(source,true),source,null,cn.bidcheck.compat.Java8Api.mapOf()).get(0);}
    private RuleEvaluator evaluator(Map<String,Object> context){return new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),context);}
    @Test void optionalAbsenceIsNotUnsupportedOrPass(){String text="其他投标人认为有必要的资料";
        Rule rule=RequirementBusinessRouting.compile(cp(text,false),text,null,cn.bidcheck.compat.Java8Api.mapOf()).get(0);
        assertEquals("NOT_APPLICABLE",RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf())).status());
        assertTrue(RequirementBusinessRouting.compile(cp(text,true),text,null,cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
        assertTrue(RequirementBusinessRouting.compile(cp("适用时提交联合体协议书",false),"适用时提交联合体协议书",null,cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
    }
    @Test void historicalDeclarationCannotProveOnlineTruth(){Rule rule=route("未被信用中国列入失信被执行人和重大税收违法失信主体。");
        Decision result=RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf("externalRecords", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_CHINA", "status", "VERIFIED", "source", "声明截图", "coverageComplete", true)))));
        assertEquals("UNDETERMINED",result.status());assertEquals("EXTERNAL_RECORD_REQUIRED",result.gaps().get(0).code());
    }
    @Test void failedConnectorIsNotMissingBidderDocument(){Rule rule=route("未被信用中国列入失信被执行人和重大税收违法失信主体。");
        Decision result=RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf("externalRecords", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_CHINA", "status", "FAILED")))));
        assertEquals("EXTERNAL_CONNECTOR_FAILED",result.gaps().get(0).code());assertEquals(false,result.proof().get("bidderDocumentRequired"));
    }
    @Test void explicitEvaluationTimeIsNotCheckedAsCurrentPrecheckTime(){Rule rule=route("未被信用中国列入失信被执行人。在投标截止时间后评标结束前进行联网核查，以现场查询结果为准。");
        Decision result=RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf("evaluationStage", "BID_SUBMISSION")));
        assertEquals("WAIT_STAGE",result.gaps().get(0).code());
    }
    @Test void costExemptionRequiresAnActualFrontTableRow(){String source="成本核算\t根据《投标人须知前附表》规定无需进行成本核算的或成本核算通过的为符合，成本核算不通过的为不符合。（现场依据投标情况进行判定）";
        Rule rule=route(source);
        Document missing=new Document("t","p","TENDER","招标",1,"th","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,source,"BLOCK","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("WAIT_STAGE",RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf("evaluationStage", "BID_SUBMISSION", "tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(missing)))).gaps().get(0).code());
        Document exempt=new Document("t","p","TENDER","招标",1,"th","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标人须知前附表","BLOCK","TEXT",null), new Page(2,"成本核算\t无需进行成本核算","BLOCK","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("NOT_APPLICABLE",RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(exempt)))).status());
    }
    @Test void fileDefinitionExamplesAreNotMandatoryDocuments(){String text="3.2.2\t投标文件应答和编写\t本条款增加以下规定： / 1.文件定义： / （1）证明文件：指投标人按招标招标文件要求递交的第三方出具的文件扫描件或影印件，如营业执照、资质、证书、合同等； / （2）其他文件：指技术方案、图纸。 / 2.本项目采用电子投标，不接收纸质投标文件。";
        List<Rule> rules=RequirementBusinessRouting.compile(cp(text,true),text,null,cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals(1,rules.size());assertEquals("EXTERNAL",rules.get(0).predicate());assertEquals("electronicSubmissionReceived",rules.get(0).field());
        assertTrue(RequirementBusinessRouting.compilationComplete(rules.get(0),text));
        assertFalse(RequirementBusinessRouting.compilationComplete(rules.get(0),text+"须另提供三份复印件。"));
        assertTrue(RequirementBusinessRouting.compile(cp(text+"须另提供三份复印件。",true),text+"须另提供三份复印件。",null,cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
    }
    @Test void onsiteSubstantiveResponseNeedsBothEvaluationFieldsAndDoesNotAskForDummyDocuments(){
        String text="响应评审\t标的物\t满足招标文件要求，对招标文件实质性响应。（现场依据投标情况进行判定）";
        List<Rule> rules=RequirementBusinessRouting.compile(cp(text,true),text,null,cn.bidcheck.compat.Java8Api.mapOf());assertEquals(2,rules.size());
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("subjectMatterMeetsTender", "substantiveResponseAccepted"),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(rules.stream().map(Rule::field))));
        for(Rule rule:rules){assertEquals("BID_EVALUATION",rule.parameters().get("evaluationStage"));assertEquals(false,rule.parameters().get("bidderDocumentRequired"));
            assertEquals("WAIT_STAGE",RequirementBusinessRouting.evaluate(rule,evaluator(cn.bidcheck.compat.Java8Api.mapOf("evaluationStage", "BID_SUBMISSION"))).gaps().get(0).code());}
    }
    @Test void penaltyWithoutBidderProofIsAnExternalCalculation(){Rule rule=route("对属于中国联通集团限制型供应商名单的综合评分扣除3分，对属于海南联通限制型供应商名单的综合评分扣除2分。本项最高扣3分。证明材料：无需提供证明材料。");
        assertEquals("restrictedSupplierPenalty",rule.field());assertEquals(3,rule.parameters().get("penaltyCap"));assertEquals(false,rule.parameters().get("bidderDocumentRequired"));
    }
}

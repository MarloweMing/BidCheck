package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceInventoryMainFlowTest {
    private final Document bid=new Document("b","p","BID","投标",1,"bh","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"项目名称：测试工程。投标人：测试工程有限公司。","PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
    private Map<String,Object> run(String source,boolean required){
        cn.bidcheck.domain.Domain.Document tender=new Document("t","p","TENDER","招标",1,"th","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,source,"BLOCK","TEXT",null), new Page(2,"相关内容详见《招标文件附件：技术规范书》。","BLOCK","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp",source,"形式检查","HIGH","SEMANTIC_RESPONSE","material","RESPONDS",source,"",required,true,true,new Evidence("t","招标",1,"BLOCK",source,"",1));
        return new VerificationEngine(ModelGateway.disabled(),null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid),tender,cn.bidcheck.compat.Java8Api.mapOf("focusedCheck", true, "allowModelRuleCompilation", false, "sourceRunId", "run", "evaluationStage", "BID_SUBMISSION"));
    }
    @Test void missingTenderAttachmentTraversesCompilerAndFinalEngineWithoutPaidOrRedundantReads(){
        java.util.Map<java.lang.String, java.lang.Object> atomic=run("技术规范书中明确要求投标人提供的其他相关文件",true);
        assertEquals("UNDETERMINED",atomic.get("businessDecision"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> gaps=RuleEvaluator.maps(atomic.get("gaps"));assertTrue(gaps.stream().anyMatch(g->"MISSING_TENDER_ATTACHMENT".equals(g.get("code"))),atomic.toString());
        assertFalse(gaps.stream().anyMatch(g->"REQUIREMENT_COMPILATION_INCOMPLETE".equals(g.get("code"))),atomic.toString());
        assertEquals(0,((Map<?,?>)atomic.get("metrics")).get("rounds"));assertEquals(0,((Map<?,?>)atomic.get("metrics")).get("modelCalls"));
    }
    @Test void explicitOptionalAbsenceRemainsNotApplicableThroughFinalCombination(){
        java.util.Map<java.lang.String, java.lang.Object> atomic=run("其他投标人认为有必要的资料",false);
        assertEquals("NOT_APPLICABLE",atomic.get("businessDecision"),atomic.toString());assertEquals(true,atomic.get("complete"));
        assertEquals(0,((Map<?,?>)atomic.get("metrics")).get("rounds"));assertTrue(RuleEvaluator.maps(atomic.get("gaps")).isEmpty());
    }
    @Test void summaryCompilesAsCurrentResultDependencyWhileKeepingTheUniquePriceLeaf()throws Exception{
        String source="报价\t投标报价唯一且有效。符合“第三章评标办法”3.1初步评审要求。";
        cn.bidcheck.domain.Domain.Document tender=new Document("t","p","TENDER","招标",1,"th","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,source,"BLOCK","TEXT",null), new Page(2,"第三章 评标办法","BLOCK","TEXT",null), new Page(3,"3.1 初步评审","BLOCK","TEXT",null), new Page(4,"3.1.1 须提供投标函","BLOCK","TEXT",null), new Page(5,"3.1.2 须提供营业执照","BLOCK","TEXT",null), new Page(6,"3.2 详细评审","BLOCK","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp",source,"响应检查","HIGH","SEMANTIC_RESPONSE","compliance","RESPONDS",source,"",true,true,true,new Evidence("t","招标",1,"BLOCK",source,"",1));
        java.util.Map<java.lang.String, java.lang.Object> context=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("focusedCheck", true, "allowModelRuleCompilation", false, "originalRequirement", source, "tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(tender), "requirementProjectId", "p");
        cn.bidcheck.verification.engine.VerificationTypes.Plan plan=new RequirementCompiler().compile(cp,new RunSession(ModelGateway.disabled(),context,"summary"));
        assertTrue(plan.issues().isEmpty(),plan.toString());
        assertTrue(RequirementCompiler.leaves(plan.rules()).stream().anyMatch(r->r.predicate().equals("SAME_FIELD")),plan.toString());
        assertTrue(plan.rules().stream().anyMatch(SourceRequirementInventory::applies),plan.toString());
        assertFalse(RequirementCompiler.leaves(plan.rules()).stream().anyMatch(r->r.predicate().equals("UNSUPPORTED")),plan.toString());
        cn.bidcheck.verification.engine.SourceRequirementInventory.Inventory referenced=SourceRequirementInventory.resolve(cp,source,context);assertFalse(referenced.members().isEmpty(),referenced.toString());
    }
}

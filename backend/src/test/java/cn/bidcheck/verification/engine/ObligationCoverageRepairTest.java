package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class ObligationCoverageRepairTest {
    private final ModelGateway disabled=new ModelGateway(){public boolean enabled(){return false;}public String identity(){return "offline";}public JsonNode request(String op,Map<String,Object> input){fail("model must not author rules");return null;}};
    private Plan plan(String text)throws Exception{
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("repair","要求","响应","HIGH","SEMANTIC_RESPONSE","compliance","",text,"",true,true,true,new Evidence("tender","招标",1,"BLOCK",text,"p1",1));
        return new RequirementCompiler().compile(cp,new RunSession(disabled,cn.bidcheck.compat.Java8Api.mapOf("originalRequirement", text, "focusedCheck", true),"offline"));
    }
    @Test void nameConsistencyDoesNotInventCertificateValidityOrRegistration()throws Exception{
        Plan p=plan("2.1\t形式评审\t投标人名称\t与营业执照、资质证书或其他组织证明一致，招标文件规定必须提交的文件中投标人名称如有变更须附有效证明文件。");
        assertEquals(1,p.rules().size());assertEquals("SAME_FIELD",p.rules().get(0).predicate());
        assertTrue(EnterpriseNameComparison.applies(p.rules().get(0)));assertTrue(p.issues().isEmpty(),p.issues().toString());
    }
    @Test void nameRouteCannotDiscardAnIndependentCertificateValidityDuty()throws Exception{
        cn.bidcheck.verification.engine.VerificationTypes.Plan p=plan("营业执照须有效且投标人名称与营业执照一致，名称如有变更须附有效证明文件。");
        assertTrue(RequirementCompiler.leaves(p.rules()).stream().anyMatch(r->!r.predicate().equals("SAME_FIELD")),p.toString());
        assertTrue(RequirementCompiler.leaves(p.rules()).stream().noneMatch(r->r.id().equals("NAME-CONSISTENCY")));
    }
    @Test void paymentReceiptDoesNotProveRequiredPayment()throws Exception{
        cn.bidcheck.verification.engine.VerificationTypes.Plan p=plan("投标保证金\t按照招标文件规定缴纳并提交凭证。");
        Rule payment=RequirementCompiler.leaves(p.rules()).stream().filter(PaymentRequirementProof::applies).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        Document bid=new Document("bid","project","BID","投标",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标保证金凭证\n已提交银行回单。","PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        Decision decision=PaymentRequirementProof.evaluate(payment,new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid)),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("UNDETERMINED",decision.status());
        assertTrue(decision.gaps().stream().anyMatch(g->g.code().equals("PAYMENT_REQUIREMENT_SOURCE_REQUIRED")),decision.toString());
    }
    @Test void actualMaterialCompositionStillDoesNotRequireAFormAudit()throws Exception{
        cn.bidcheck.verification.engine.VerificationTypes.Plan p=plan("须提交《投标函》。");
        assertTrue(RequirementCompiler.leaves(p.rules()).stream().noneMatch(r->Boolean.TRUE.equals(r.parameters().get("templateFormAudit"))));
    }
    @Test void referencedMandatoryInventoryCannotPassOnOneNamedMaterial()throws Exception{
        for(String text:cn.bidcheck.compat.Java8Api.listOf("须提交《供应商合规承诺》等必须的承诺文件（清单详见第六章中《承诺书》）。", "应提交全部必备文件，清单见第七章《资格证明书》。")){
            Plan p=plan(text);
            List<Rule> unresolved=cn.bidcheck.compat.Java8Api.toList(RequirementCompiler.leaves(p.rules()).stream().filter(r->SourceRequirementInventory.applies(r)
                ||r.predicate().equals("UNSUPPORTED")&&"REQUIREMENT_INVENTORY_UNRESOLVED".equals(r.parameters().get("deterministicFamily"))));
            assertFalse(unresolved.isEmpty(),p.toString());
            for(Rule rule:unresolved)if(SourceRequirementInventory.applies(rule)){
                Decision result=SourceRequirementInventory.evaluate(rule,new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.mapOf()));
                assertEquals("UNDETERMINED",result.status(),result.toString());
                assertFalse(result.gaps().isEmpty(),"a referenced inventory cannot be complete without its source members");
            }
        }
    }
    @Test void numberedCertificateBranchRetainsSubjectForSharedProof()throws Exception{
        for(String category:cn.bidcheck.compat.Java8Api.listOf("质量管理体系认证证书", "职业健康安全管理体系认证", "环境管理体系认证")){
            String branch="（1）施工单位具备"+category+"，得1分。";
            String proof="证明材料：需提交有效证明文件，并提供查询网址及网址查询截图。";
            Plan p=plan(branch+"\n"+proof);
            List<Rule> leaves=RequirementCompiler.leaves(p.rules());
            for(String expected:cn.bidcheck.compat.Java8Api.listOf("查询网址", "查询截图")){
                Rule r=leaves.stream().filter(x->x.expected().equals(expected)).findFirst().orElseThrow(java.util.NoSuchElementException::new);
                assertEquals("MATERIAL",r.predicate(),r.toString());assertEquals("施工单位",r.subject());
                assertEquals(proof,r.sourceQuote());assertTrue(r.parameters().containsKey("requirementBindings"));
            }
            assertTrue(leaves.stream().filter(r->r.predicate().equals("CERTIFICATE")).allMatch(r->r.sourceQuote().contains("施工单位")),p.toString());
            assertTrue(leaves.stream().noneMatch(r->r.parameters().containsKey("focusedScopeIssue")),p.toString());
        }
    }
    @Test void sharedProofBindsTheNumberedActorInFrozenTableLayout(){
        String proof="证明材料：需提交有效证明文件，并提供查询网址及网址查询截图。";
        String original="综合实力 / （商务）\t（1）施工单位具备质量管理体系认证证书，得1分； / （2）施工单位具备职业健康安全管理体系认证，得1分； / （3）施工单位具备环境管理体系认证，得1分。 / "+proof+"\t3";
        Rule url=new Rule("url","查询网址","MATERIAL","施工单位","material","","查询网址","",cn.bidcheck.compat.Java8Api.mapOf(),proof,"",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.RequirementContextBindings bindings=new RequirementContextBindings(original,"ORIGINAL_REQUIREMENT");
        Rule bound=bindings.bind(url,cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(bindings.bound(bound,"subject","施工单位"),bound.toString());
        String switched=original.replace("（3）施工单位具备","（3）设计单位具备");
        cn.bidcheck.verification.engine.RequirementContextBindings other=new RequirementContextBindings(switched,"ORIGINAL_REQUIREMENT");
        assertFalse(other.bound(other.bind(url,cn.bidcheck.compat.Java8Api.listOf()),"subject","施工单位"),"must not cross another actor's branch");
    }
    @Test void certificateKeepsActorWithoutInheritingSiblingGrade()throws Exception{
        Plan p=plan("施工单位须具有建筑装修装饰工程专业承包一级资质和有效的安全生产许可证。");
        assertTrue(p.issues().isEmpty(),p.issues().toString());
        Rule license=RequirementCompiler.leaves(p.rules()).stream().filter(r->r.predicate().equals("CERTIFICATE")&&"安全生产许可证".equals(r.parameters().get("category"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("施工单位",license.subject());assertFalse(license.sourceQuote().contains("一级"));
        assertFalse(license.parameters().containsKey("grade"));assertFalse(license.parameters().containsKey("requirementContextIssue"));
    }
    @Test void blacklistRegistrationWordDoesNotCreatePersonnelCredential()throws Exception{
        cn.bidcheck.verification.engine.VerificationTypes.Plan p=plan("严禁被列入供应商黑名单的供应商（含供应商所注册设立的其他法人或组织）或个人（作为项目负责人）在禁入期内参与本次招标。");
        assertFalse(p.requirementScope().toString().contains("PERSONNEL_CREDENTIAL"));
    }
    @Test void placeholderCopyAndBlankLinesCannotSatisfyFilledForm(){
        String template="（投标人名称）已研究（项目名称）（招标编号）招标文件。";
        assertFalse(TenderFormAudit.filledContent(template,template));
        assertFalse(TenderFormAudit.filledContent("（投标人名称）  （以下称我方）已研究招标文件。","（投标人名称）  （以下称我方）已研究招标文件。"));
        assertFalse(TenderFormAudit.filledContent(template,"____已研究____（招标编号）招标文件。"));
        assertTrue(TenderFormAudit.filledContent(template,"示例建设公司已研究（城市更新工程）（ABC-001）招标文件。"));
        assertFalse(TenderFormAudit.filledContent(template,"示例建设公司已研究城市更新工程招标文件。"));
        assertFalse(TenderFormAudit.filledContent("承诺工期为       （填写“满足”），质量为      （填写“合格”）。","承诺工期为       （填写“满足”），质量为      （填写“合格”）。"));
        assertTrue(TenderFormAudit.filledContent("承诺工期为       （填写“满足”），质量为      （填写“合格”）。","承诺工期为 满足 （填写“满足”），质量为 合格 （填写“合格”）。"));
    }
    @Test void formLocatorExcludesContentsEntryAndKeepsSourcePositions(){
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标函","BLOCK","NATIVE",""), new Page(2,"合同条款偏离表","BLOCK","NATIVE",""), new Page(3,"投标函","BLOCK","NATIVE",""), new Page(4,"投标人（投标人名称）对（项目名称）全部招标内容作出响应。并承诺按照合同约定履行。承诺工期为      （填写“满足”），质量为     （填写“合格”）。我方承诺所提交文件真实完整准确。投标人需在本位置盖章。","BLOCK","NATIVE",""), new Page(5,"合同条款偏离表","BLOCK","NATIVE",""));
        Document d=new Document("tender","project","TENDER","招标",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> rows=TenderFormAudit.locate(d,"投标函");assertEquals(1,rows.size());assertEquals(4,rows.get(0).get("location"));
    }
    @Test void anotherFormOnSamePageCannotSupplyMissingFields(){
        String page="3.投标函\n投标函\n项目名称：示例工程\n地址：\n4.合同条款偏离表\n项目名称：示例工程\n地址：完整街道123号\n";
        String window=TenderFormAudit.formWindow(page,"投标函");
        assertTrue(window.contains("地址："));assertFalse(window.contains("完整街道"));assertFalse(window.contains("合同条款偏离表"));
    }
    @Test void sourceAuthorizedCompanyCommitmentCompilesAsWrittenResponseOnly()throws Exception{
        cn.bidcheck.verification.engine.VerificationTypes.Plan p=plan("经营状况：未处于被接管、资产被冻结或破产状态，能够满足招标人的要求。须提供《企业经营状况承诺书》。");
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> leaves=RequirementCompiler.leaves(p.rules());
        assertTrue(leaves.stream().anyMatch(r->r.predicate().equals("DECLARATION")&&"WRITTEN_RESPONSE".equals(r.parameters().get("proofScope"))));
        assertTrue(leaves.stream().noneMatch(r->r.predicate().equals("UNSUPPORTED")),p.toString());
    }
    @Test void companyOperatingConditionWithoutAcceptedCommitmentDoesNotBecomeAPromise()throws Exception{
        cn.bidcheck.verification.engine.VerificationTypes.Plan p=plan("经营状况：未处于被接管、资产被冻结或破产状态。");
        assertTrue(RequirementCompiler.leaves(p.rules()).stream().noneMatch(r->r.predicate().equals("DECLARATION")));
    }
    @Test void instructionOnlyLabelValuesRemainUnfilled(){
        assertFalse(TenderFormAudit.filledLabel("地址","____（填写地址）"));
        assertFalse(TenderFormAudit.filledLabel("日期","待填写"));
        assertFalse(TenderFormAudit.filledLabel("日期","年月日"));
        assertFalse(TenderFormAudit.filledLabel("电话","待填"));
        assertTrue(TenderFormAudit.filledLabel("日期","2025年10月8日"));
        assertTrue(TenderFormAudit.filledLabel("地址","和平路123号"));
    }
}

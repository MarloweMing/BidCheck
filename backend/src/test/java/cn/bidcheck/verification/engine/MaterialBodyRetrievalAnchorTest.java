package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialBodyRetrievalAnchorTest {
    @Test void agentGenericSearchRetainsTheExactServerMaterialTarget() {
        List<Page> pages=new ArrayList<>();
        for(int i=1;i<=10;i++)pages.add(new Page(i,"5.2 投标人有效资质证明文件\n根据招标文件要求提供。\n（1）设计单位资质证明","PAGE","PDF",""));
        pages.add(new Page(20,"5. 证明等文件\n5.1 投标人基本情况\n营业执照\n名称：合成甲有限公司\n统一社会信用代码：91110101SYNTHETIC00\n"+cn.bidcheck.compat.Java8Api.repeat("登记机关：合成市市场监督管理局。经营范围：建筑施工和技术服务。",4),"PAGE","PDF",""));
        Document doc=new Document("bid","p","BID","fixture.pdf",1,"hash","",pages,Collections.emptyList());
        EvidenceCorpus c=new EvidenceCorpus(Collections.singletonList(doc));
        Rule target=new Rule("material","提交证明等文件","MATERIAL","","material","EXISTS","证明等文件","",Collections.<String,Object>singletonMap("inventoryEntry",true),"证明等文件","",Collections.emptyList());
        Map<String,Object> ctx=new LinkedHashMap<>();RunSession session=new RunSession(ModelGateway.disabled(),ctx,"material-target");
        List<cn.bidcheck.domain.Domain.Evidence> hits=new ArrayList<>();for(int i=1;i<=10;i++)hits.add(AgentRetrievalPageBudgetTest.hit(doc,i));
        EvidenceInvestigation.Host host=new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",Collections.singletonList(target),"LOCAL",Collections.emptyList()),Collections.singletonList(target),c,new LinkedHashMap<>(),ctx,new SharedFactReuse(c,ctx,"fixture","fixture"),session,AgentRetrievalPageBudgetTest.retriever(hits),new ArrayList<>(),Collections.emptySet());
        Map<String,Object> result=host.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query","证明","targetObligationId","material"));
        assertTrue(RuleEvaluator.maps(result.get("sources")).stream().anyMatch(s->Integer.valueOf(20).equals(s.get("location"))),"精确父章节原文必须进入实际Agent搜索结果");
        assertTrue(host.facts.values().stream().anyMatch(f->"MATERIAL".equals(f.kind())&&"证明等文件".equals(f.value())));
        assertEquals("PASS",new RuleEvaluator(new ArrayList<>(host.facts.values()),c,ctx).evaluate(target).status());
        Rule missing=new Rule("missing","提交专用安全承诺书","MATERIAL","","material","EXISTS","专用安全承诺书","",Collections.emptyMap(),"专用安全承诺书","",Collections.emptyList());
        assertEquals("UNDETERMINED",new RuleEvaluator(new ArrayList<>(host.facts.values()),c,ctx).evaluate(missing).status());
    }
    @Test void indexAliasHasItsOwnShortSectionEvenOnAnIndexPage() {
        EvidenceCorpus c=new PersonnelCountRoleTest().corpus("2. 商务评审索引表（适用综合评估法）\n条款号 评审因素 对应页码\n1 投标函 P7\n2 技术方案 ........ 8\n3 其他资料 ........ 9");
        assertFalse(c.materialBodyAnchors("商务技术评审索引表").isEmpty());
        assertTrue(SectionCoverage.locate("商务技术评审索引表",c).complete());
        assertTrue(MaterialTitleAliases.matches("商务评审索引表","商务技术评审索引表"));
        assertTrue(MaterialTitleAliases.matches("技术投标索引表","技术评审索引表"));
        assertFalse(MaterialTitleAliases.matches("商务评审索引表","技术评审索引表"));
        EvidenceCorpus directory=new PersonnelCountRoleTest().corpus("2. 专项资料目录\n"+cn.bidcheck.compat.Java8Api.repeat("安全管理资料 ........ 12\n质量验收资料 ........ 18\n",8));
        assertTrue(SectionCoverage.locate("专项资料目录",directory).complete());
        assertFalse(directory.materialBodyAnchors("专项资料目录").isEmpty());
        assertTrue(directory.materialBodyAnchors("安全管理资料").isEmpty());
    }
    @Test void coverAliasesUseFilledIdentityAndIncompleteOcrNeverClosesCoverage() {
        for(String type:Arrays.asList("商务","技术")){
            EvidenceCorpus c=new PersonnelCountRoleTest().corpus("投标文件（"+type+"部分）\n投标人名称：合成甲有限公司");
            assertFalse(c.materialBodyAnchors(type+"投标文件封面").isEmpty());
            assertTrue(SectionCoverage.locate(type+"投标文件封面",c).complete());
            assertTrue(new PersonnelCountRoleTest().corpus("投标文件（"+type+"部分）\n投标人名称：（单位全称）").materialBodyAnchors(type+"投标文件封面").isEmpty());
        }
        Page p=new Page(1,"2. 商务评审索引表\n条款号 评审因素 对应页码\n1 投标函 P7","PAGE","PDF","[OCR未完成] 图像待识别");
        EvidenceCorpus incomplete=new EvidenceCorpus(Collections.singletonList(new Document("bid","p","BID","fixture.pdf",1,"hash","",Collections.singletonList(p),Collections.emptyList())));
        assertFalse(SectionCoverage.locate("商务技术评审索引表",incomplete).complete());
    }
    @Test void generalParentSectionIncludesChildBodyAndNativePresenceProof() {
        String text="5. 证明等文件\n5.1 投标人基本情况\n营业执照\n名称：合成甲有限公司\n统一社会信用代码：91110101SYNTHETIC00\n"+cn.bidcheck.compat.Java8Api.repeat("登记机关：合成市市场监督管理局。经营范围：建筑施工和技术服务。",4);
        EvidenceCorpus c=new PersonnelCountRoleTest().corpus(text);
        assertFalse(c.materialBodyAnchors("证明等文件").isEmpty());
        Rule r=new Rule("proof","提交证明等文件","MATERIAL","","material","EXISTS","证明等文件","",Collections.emptyMap(),"证明等文件","",Collections.emptyList());
        List<Fact> facts=c.nativeFacts(Collections.singletonList(r),c.all);
        ProofVerifier.Verified v=new ProofVerifier().verify(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Collections.singletonMap("facts",facts)),c.all);
        assertTrue(v.rejected().isEmpty(),v.rejected().toString());
        assertEquals("PASS",new RuleEvaluator(v.facts(),c,Collections.emptyMap()).evaluate(r).status());
    }
    @Test void ordinaryMaterialCannotBorrowTheNextPeerBody() {
        for(String text:Arrays.asList("5. 证明等文件", "目录\n证明等文件 ........ 10", "5. 证明等文件\n6. 其他材料\n"+cn.bidcheck.compat.Java8Api.repeat("登记机关：合成市市场监督管理局。经营范围：建筑施工和技术服务。",4))) {
            EvidenceCorpus c=new PersonnelCountRoleTest().corpus(text);
            assertTrue(c.materialBodyAnchors("证明等文件").isEmpty());
            Rule r=new Rule("proof","提交证明等文件","MATERIAL","","material","EXISTS","证明等文件","",Collections.emptyMap(),"证明等文件","",Collections.emptyList());
            assertTrue(c.nativeFacts(Collections.singletonList(r),c.all).stream().noneMatch(f->"MATERIAL".equals(f.kind())),text);
        }
    }
    @Test void technicalChildBodyAndMissingQuoteVolumeKeepOppositeOutcomes() {
        EvidenceCorpus c=new PersonnelCountRoleTest().corpus("投标文件（商务部分）\n投标人名称：合成甲有限公司", "4. 技术建议书\n4.1 设计方案\n采用分级控制和双机备份保证现场服务连续运行。");
        assertFalse(c.materialBodyAnchors("技术建议书").isEmpty());
        for(String bad:Arrays.asList("技术建议书 ........ 5","4. 技术建议书","4. 技术建议书\n4.1 设计方案"))assertTrue(new PersonnelCountRoleTest().corpus(bad).materialBodyAnchors("技术建议书").isEmpty());
        Rule r=new Rule("quote","提交报价分册","MATERIAL","","material","EXISTS","第三分册 报价文件","",Collections.<String,Object>singletonMap("inventoryEntry",true),"报价文件","",Collections.emptyList());
        Decision d=new RuleEvaluator(Collections.emptyList(),c,Collections.emptyMap()).evaluate(r);
        assertEquals("UNDETERMINED",d.status());
        assertTrue(d.gaps().stream().anyMatch(g->"BID_VOLUME_NOT_UPLOADED".equals(g.code())));
    }
    Rule rule(){return new Rule("material","提交安全文明施工方案","MATERIAL","","material","EXISTS","安全文明施工方案","",cn.bidcheck.compat.Java8Api.mapOf(),"提交安全文明施工方案","",cn.bidcheck.compat.Java8Api.listOf());}
    @Test void directoryHybridHitsCannotConsumeAllEightPagesBeforeOriginalBody()throws Exception{
        List<Page> pages=new ArrayList<>();for(int i=1;i<=10;i++)pages.add(new Page(i,"目录\n安全文明施工方案 .............. 20\n其他方案 .............. 21\n","PAGE","TEXT",""));pages.add(new Page(20,"第五章 安全文明施工方案\n"+cn.bidcheck.compat.Java8Api.repeat("建立现场安全检查、消防巡查和施工人员培训制度，明确责任人并记录整改结果。", 5),"PAGE","TEXT",""));pages.add(new Page(21,"第六章 质量保证方案\n"+cn.bidcheck.compat.Java8Api.repeat("明确质量计划和分项验收责任。", 10),"PAGE","TEXT",""));Document d=new Document("bid","p","BID","file.pdf",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceCorpus c=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(d));java.util.List<cn.bidcheck.domain.Domain.Evidence> hits=cn.bidcheck.compat.Java8Api.toList(pages.stream().filter(p->p.number()<=10).map(p->AgentRetrievalPageBudgetTest.hit(d,p.number())));cn.bidcheck.verification.engine.RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"body-anchor");
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source> selected=c.selectForAgent(cn.bidcheck.compat.Java8Api.listOf(rule()),cn.bidcheck.compat.Java8Api.listOf(),AgentRetrievalPageBudgetTest.retriever(hits),session);assertEquals(20,selected.get(0).evidence().page());assertTrue(selected.stream().anyMatch(s->s.evidence().page()==20));assertTrue(RuleEvaluator.maps(AgentRetrievalPageBudgetTest.output(session,"RETRIEVAL_SELECTION_0").get("candidateDetails")).stream().anyMatch(row->Integer.valueOf(20).equals(row.get("page"))&&Boolean.TRUE.equals(row.get("materialBodyAnchor"))));
    }
    @Test void unnumberedExactHeadingWithBodyAnchorsButAnIndexOrPromiseNeverDoes(){
        cn.bidcheck.verification.engine.PersonnelCountRoleTest f=new PersonnelCountRoleTest();String text="安全文明施工方案\n"+cn.bidcheck.compat.Java8Api.repeat("建立现场安全检查、消防巡查和施工人员培训制度，明确责任人并记录整改结果。", 4);cn.bidcheck.verification.engine.EvidenceCorpus c=f.corpus("投标文件\n项目名称：北方项目",text);assertFalse(c.materialBodyAnchors("安全文明施工方案").isEmpty());
        for(String invalid:cn.bidcheck.compat.Java8Api.listOf("目录\n安全文明施工方案 ............ 2", "承诺提供安全文明施工方案\n"+cn.bidcheck.compat.Java8Api.repeat("其他无关内容。", 25), "安全文明施工方案\n（填写说明：应提供完整安全文明施工方案）")){cn.bidcheck.verification.engine.EvidenceCorpus other=f.corpus(invalid);assertTrue(other.materialBodyAnchors("安全文明施工方案").isEmpty(),invalid);}
    }
}

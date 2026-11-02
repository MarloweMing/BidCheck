package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialBodyRetrievalAnchorTest {
    Rule rule(){return new Rule("material","提交安全文明施工方案","MATERIAL","","material","EXISTS","安全文明施工方案","",Map.of(),"提交安全文明施工方案","",List.of());}
    @Test void directoryHybridHitsCannotConsumeAllEightPagesBeforeOriginalBody()throws Exception{
        List<Page> pages=new ArrayList<>();for(int i=1;i<=10;i++)pages.add(new Page(i,"目录\n安全文明施工方案 .............. 20\n其他方案 .............. 21\n","PAGE","TEXT",""));pages.add(new Page(20,"第五章 安全文明施工方案\n"+"建立现场安全检查、消防巡查和施工人员培训制度，明确责任人并记录整改结果。".repeat(5),"PAGE","TEXT",""));pages.add(new Page(21,"第六章 质量保证方案\n"+"明确质量计划和分项验收责任。".repeat(10),"PAGE","TEXT",""));Document d=new Document("bid","p","BID","file.pdf",1,"hash","",pages,List.of());var c=new EvidenceCorpus(List.of(d));var hits=pages.stream().filter(p->p.number()<=10).map(p->AgentRetrievalPageBudgetTest.hit(d,p.number())).toList();var session=new RunSession(ModelGateway.disabled(),Map.of(),"body-anchor");
        var selected=c.selectForAgent(List.of(rule()),List.of(),AgentRetrievalPageBudgetTest.retriever(hits),session);assertEquals(20,selected.get(0).evidence().page());assertTrue(selected.stream().anyMatch(s->s.evidence().page()==20));assertTrue(RuleEvaluator.maps(AgentRetrievalPageBudgetTest.output(session,"RETRIEVAL_SELECTION_0").get("candidateDetails")).stream().anyMatch(row->Integer.valueOf(20).equals(row.get("page"))&&Boolean.TRUE.equals(row.get("materialBodyAnchor"))));
    }
    @Test void unnumberedExactHeadingWithBodyAnchorsButAnIndexOrPromiseNeverDoes(){
        var f=new PersonnelCountRoleTest();String text="安全文明施工方案\n"+"建立现场安全检查、消防巡查和施工人员培训制度，明确责任人并记录整改结果。".repeat(4);var c=f.corpus("投标文件\n项目名称：北方项目",text);assertFalse(c.materialBodyAnchors("安全文明施工方案").isEmpty());
        for(String invalid:List.of("目录\n安全文明施工方案 ............ 2","承诺提供安全文明施工方案\n"+"其他无关内容。".repeat(25),"安全文明施工方案\n（填写说明：应提供完整安全文明施工方案）")){var other=f.corpus(invalid);assertTrue(other.materialBodyAnchors("安全文明施工方案").isEmpty(),invalid);}
    }
}

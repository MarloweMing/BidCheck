package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.retrieval.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRetrievalPageBudgetTest {
    static Rule rule(){return new Rule("role","项目负责人任职","PERSON_ROLE","项目负责人","role","","","",cn.bidcheck.compat.Java8Api.mapOf(),"本项目项目负责人","",cn.bidcheck.compat.Java8Api.listOf());}
    static HybridRetriever retriever(List<Evidence> hits){return new HybridRetriever(){
        @Override public boolean enabled(){return true;}
        @Override public RetrievalResult search(Document document,String query,int limit){return new RetrievalResult(hits,"HYBRID",cn.bidcheck.compat.Java8Api.mapOf("fixture", true));}
    };}
    static Evidence hit(Document doc,int page){Page p=doc.pages().stream().filter(x->x.number()==page).findFirst().orElseThrow(java.util.NoSuchElementException::new);return new Evidence(doc.id(),doc.name(),page,"PAGE",p.text().substring(0,Math.min(1400,p.text().length())),"p"+page+"-c0",1);}
    static EvidenceInvestigation.Host host(Document doc,RunSession session,HybridRetriever retriever){
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc));Map<String,Object> context=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(rule()),"LOCAL",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(rule()),corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"fixture","fixture"),session,retriever,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    @Test void hybridRankOneBeatsLocalKeywordsAndDuplicateLowerRankedHits()throws Exception{
        Document d=new Document("bid","p","BID","fixture",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,cn.bidcheck.compat.Java8Api.repeat("项目负责人 任职 资格 证书 本项目 ", 160),"PAGE","PDF",""), new Page(22,"姓名：测试甲\n拟在本合同任职：项目负责人","PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(d));RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"fixture");
        List<Evidence> hits=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(hit(d,22)));for(int i=0;i<15;i++)hits.add(hit(d,1));
        List<Source> selected=corpus.selectForAgent(cn.bidcheck.compat.Java8Api.listOf(rule()),cn.bidcheck.compat.Java8Api.listOf(),retriever(hits),session);
        assertEquals(22,selected.get(0).evidence().page());assertEquals(2,selected.size());
        Map<String,Object> output=output(session,"RETRIEVAL_SELECTION_0");List<Map<String,Object>> candidates=RuleEvaluator.maps(output.get("candidateDetails"));
        assertTrue(candidates.size()>selected.size());assertEquals(1,candidates.stream().filter(row->Integer.valueOf(22).equals(row.get("page"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("pageRetrievalRank"));
        assertEquals("PAGE",output.get("selectionUnit"));assertTrue(candidates.stream().anyMatch(row->"PAGE_REPRESENTED".equals(row.get("exclusionReason"))));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(output(session,"RETRIEVE_0_bid").get("query").toString()));
    }
    @Test void eightMultiChunkPagesAreReturnedInsteadOfSixChunks(){
        List<Page> pages=new ArrayList<>();for(int page=1;page<=8;page++)pages.add(new Page(page,"第"+page+"页 项目负责人\n"+cn.bidcheck.compat.Java8Api.repeat("记录原文。", 360),"PAGE","PDF",""));
        Document d=new Document("bid","p","BID","fixture",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf());List<Evidence> hits=cn.bidcheck.compat.Java8Api.toList(pages.stream().map(p->hit(d,p.number())));
        RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"fixture");cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(d,session,retriever(hits));java.util.Map<java.lang.String, java.lang.Object> result=h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));
        assertEquals(8,result.get("returnedPages"));assertTrue(((List<?>)result.get("sourceIds")).size()>8);assertEquals(8,RuleEvaluator.maps(result.get("pages")).size());
        assertEquals(result.get("sourceIds"),output(session,"AGENT_SOURCE_EXPOSURE").get("returnedSourceIds"));assertEquals(8,((List<?>)output(session,"AGENT_SOURCE_EXPOSURE").get("candidateSourceIds")).size());
    }
    @Test void veryLongFirstPageCannotConsumeAllPageRepresentatives()throws Exception{
        List<Page> pages=new ArrayList<>();pages.add(new Page(1,"项目负责人\n"+cn.bidcheck.compat.Java8Api.repeat("完整原文。", 12000),"PAGE","PDF",""));
        for(int i=2;i<=8;i++)pages.add(new Page(i,"项目负责人 材料第"+i+"页", "PAGE","PDF",""));
        Document d=new Document("bid","p","BID","fixture",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf());List<Evidence> hits=cn.bidcheck.compat.Java8Api.toList(pages.stream().map(p->hit(d,p.number())));
        RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"fixture");cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(d,session,retriever(hits));java.util.Map<java.lang.String, java.lang.Object> result=h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));
        assertEquals(8,result.get("returnedPages"));assertEquals(true,result.get("partial"));assertTrue(RuleEvaluator.maps(result.get("omitted")).stream().anyMatch(r->"PARTIAL_PAGE_CONTINUATION".equals(r.get("reason"))));
    }
    @Test void unrelatedGlobalAppointmentFactsCannotCrowdOutTheTopCertificateHit()throws Exception{
        List<Page> pages=new ArrayList<>();for(int i=1;i<=12;i++)pages.add(new Page(i,"本项目人员配置\n姓名：测试甲\n拟在本合同任职：专职安全员","PAGE","PDF",""));pages.add(new Page(22,"测试甲 一级建造师注册证书","PAGE","PDF",""));
        Document doc=new Document("bid","p","BID","fixture",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf());EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc));List<Fact> global=new ArrayList<>();
        for(Source s:corpus.all)if(s.evidence().page()!=22)global.add(new Fact("f"+s.id(),"PERSON_ROLE","专职安全员","role","测试甲","测试甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(s.id()),cn.bidcheck.compat.Java8Api.mapOf()));
        Rule target=new Rule("certificate","项目负责人资格","CERTIFICATE","项目负责人","certificate","=","一级建造师","",cn.bidcheck.compat.Java8Api.mapOf(),"项目负责人持一级建造师证书","",cn.bidcheck.compat.Java8Api.listOf());
        RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"fixture");List<Source> result=corpus.selectForAgent(cn.bidcheck.compat.Java8Api.listOf(rule()),cn.bidcheck.compat.Java8Api.listOf(target),global,retriever(cn.bidcheck.compat.Java8Api.listOf(hit(doc,22))),session);
        assertEquals(22,result.get(0).evidence().page());assertFalse(RuleEvaluator.maps(output(session,"RETRIEVAL_SELECTION_0").get("candidateDetails")).stream().anyMatch(row->Boolean.TRUE.equals(row.get("pinned"))));
    }
    @Test void plainTextLayoutCannotClaimTableReadCompleted(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=new EvidenceInvestigationTest().host("项目负责人材料");cn.bidcheck.verification.engine.VerificationTypes.Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        h.ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,Object>)r->cn.bidcheck.compat.Java8Api.listOf(linked("documentId","bid","documentHash","hash-bid","documentVersion",1,"spanId","text","kind","TEXT_SPAN","text","项目负责人材料")));
        java.util.Map<java.lang.String, java.lang.Object> result=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id()));assertEquals("NO_RECORD",result.get("status"));assertEquals(true,result.get("structureUnavailable"));assertEquals("read_section",result.get("nextAction"));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> output(RunSession session,String type){return (Map<String,Object>)session.steps.stream().filter(s->type.equals(s.get("type"))).reduce((a,b)->b).orElseThrow(java.util.NoSuchElementException::new).get("output");}
}

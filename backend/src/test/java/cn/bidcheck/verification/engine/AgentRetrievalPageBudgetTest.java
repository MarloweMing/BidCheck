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
    static Rule rule(){return new Rule("role","项目负责人任职","PERSON_ROLE","项目负责人","role","","","",Map.of(),"本项目项目负责人","",List.of());}
    static HybridRetriever retriever(List<Evidence> hits){return new HybridRetriever(){
        @Override public boolean enabled(){return true;}
        @Override public RetrievalResult search(Document document,String query,int limit){return new RetrievalResult(hits,"HYBRID",Map.of("fixture",true));}
    };}
    static Evidence hit(Document doc,int page){Page p=doc.pages().stream().filter(x->x.number()==page).findFirst().orElseThrow();return new Evidence(doc.id(),doc.name(),page,"PAGE",p.text().substring(0,Math.min(1400,p.text().length())),"p"+page+"-c0",1);}
    static EvidenceInvestigation.Host host(Document doc,RunSession session,HybridRetriever retriever){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc));Map<String,Object> context=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",List.of(rule()),"LOCAL",List.of()),List.of(rule()),corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"fixture","fixture"),session,retriever,new ArrayList<>(),Set.of());
    }
    @Test void hybridRankOneBeatsLocalKeywordsAndDuplicateLowerRankedHits()throws Exception{
        Document d=new Document("bid","p","BID","fixture",1,"hash","",List.of(new Page(1,"项目负责人 任职 资格 证书 本项目 ".repeat(160),"PAGE","PDF",""),new Page(22,"姓名：测试甲\n拟在本合同任职：项目负责人","PAGE","PDF","")),List.of());
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(d));RunSession session=new RunSession(ModelGateway.disabled(),Map.of(),"fixture");
        List<Evidence> hits=new ArrayList<>(List.of(hit(d,22)));for(int i=0;i<15;i++)hits.add(hit(d,1));
        List<Source> selected=corpus.selectForAgent(List.of(rule()),List.of(),retriever(hits),session);
        assertEquals(22,selected.get(0).evidence().page());assertEquals(2,selected.size());
        Map<String,Object> output=output(session,"RETRIEVAL_SELECTION_0");List<Map<String,Object>> candidates=RuleEvaluator.maps(output.get("candidateDetails"));
        assertTrue(candidates.size()>selected.size());assertEquals(1,candidates.stream().filter(row->Integer.valueOf(22).equals(row.get("page"))).findFirst().orElseThrow().get("pageRetrievalRank"));
        assertEquals("PAGE",output.get("selectionUnit"));assertTrue(candidates.stream().anyMatch(row->"PAGE_REPRESENTED".equals(row.get("exclusionReason"))));
        assertFalse(output(session,"RETRIEVE_0_bid").get("query").toString().isBlank());
    }
    @Test void eightMultiChunkPagesAreReturnedInsteadOfSixChunks(){
        List<Page> pages=new ArrayList<>();for(int page=1;page<=8;page++)pages.add(new Page(page,"第"+page+"页 项目负责人\n"+"记录原文。".repeat(360),"PAGE","PDF",""));
        Document d=new Document("bid","p","BID","fixture",1,"hash","",pages,List.of());List<Evidence> hits=pages.stream().map(p->hit(d,p.number())).toList();
        RunSession session=new RunSession(ModelGateway.disabled(),Map.of(),"fixture");var h=host(d,session,retriever(hits));var result=h.call("search_evidence",Map.of("query","项目负责人"));
        assertEquals(8,result.get("returnedPages"));assertTrue(((List<?>)result.get("sourceIds")).size()>8);assertEquals(8,RuleEvaluator.maps(result.get("pages")).size());
        assertEquals(result.get("sourceIds"),output(session,"AGENT_SOURCE_EXPOSURE").get("returnedSourceIds"));assertEquals(8,((List<?>)output(session,"AGENT_SOURCE_EXPOSURE").get("candidateSourceIds")).size());
    }
    @Test void veryLongFirstPageCannotConsumeAllPageRepresentatives()throws Exception{
        List<Page> pages=new ArrayList<>();pages.add(new Page(1,"项目负责人\n"+"完整原文。".repeat(12000),"PAGE","PDF",""));
        for(int i=2;i<=8;i++)pages.add(new Page(i,"项目负责人 材料第"+i+"页", "PAGE","PDF",""));
        Document d=new Document("bid","p","BID","fixture",1,"hash","",pages,List.of());List<Evidence> hits=pages.stream().map(p->hit(d,p.number())).toList();
        RunSession session=new RunSession(ModelGateway.disabled(),Map.of(),"fixture");var h=host(d,session,retriever(hits));var result=h.call("search_evidence",Map.of("query","项目负责人"));
        assertEquals(8,result.get("returnedPages"));assertEquals(true,result.get("partial"));assertTrue(RuleEvaluator.maps(result.get("omitted")).stream().anyMatch(r->"PARTIAL_PAGE_CONTINUATION".equals(r.get("reason"))));
    }
    @Test void unrelatedGlobalAppointmentFactsCannotCrowdOutTheTopCertificateHit()throws Exception{
        List<Page> pages=new ArrayList<>();for(int i=1;i<=12;i++)pages.add(new Page(i,"本项目人员配置\n姓名：测试甲\n拟在本合同任职：专职安全员","PAGE","PDF",""));pages.add(new Page(22,"测试甲 一级建造师注册证书","PAGE","PDF",""));
        Document doc=new Document("bid","p","BID","fixture",1,"hash","",pages,List.of());EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc));List<Fact> global=new ArrayList<>();
        for(Source s:corpus.all)if(s.evidence().page()!=22)global.add(new Fact("f"+s.id(),"PERSON_ROLE","专职安全员","role","测试甲","测试甲","","","CURRENT_PROJECT",List.of(s.id()),Map.of()));
        Rule target=new Rule("certificate","项目负责人资格","CERTIFICATE","项目负责人","certificate","=","一级建造师","",Map.of(),"项目负责人持一级建造师证书","",List.of());
        RunSession session=new RunSession(ModelGateway.disabled(),Map.of(),"fixture");List<Source> result=corpus.selectForAgent(List.of(rule()),List.of(target),global,retriever(List.of(hit(doc,22))),session);
        assertEquals(22,result.get(0).evidence().page());assertFalse(RuleEvaluator.maps(output(session,"RETRIEVAL_SELECTION_0").get("candidateDetails")).stream().anyMatch(row->Boolean.TRUE.equals(row.get("pinned"))));
    }
    @Test void plainTextLayoutCannotClaimTableReadCompleted(){
        var h=new EvidenceInvestigationTest().host("项目负责人材料");var source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        h.ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,Object>)r->List.of(linked("documentId","bid","documentHash","hash-bid","documentVersion",1,"spanId","text","kind","TEXT_SPAN","text","项目负责人材料")));
        var result=h.call("read_table_record",Map.of("sourceId",source.id()));assertEquals("NO_RECORD",result.get("status"));assertEquals(true,result.get("structureUnavailable"));assertEquals("read_section",result.get("nextAction"));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> output(RunSession session,String type){return (Map<String,Object>)session.steps.stream().filter(s->type.equals(s.get("type"))).reduce((a,b)->b).orElseThrow().get("output");}
}

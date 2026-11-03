package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.retrieval.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceCorpusRetrievalTest {
    private Document document(String text){return new Document("bid","project","BID","测试文件",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());}
    @Test void mismatchedChunkBoundariesKeepTheRelevantEndOfReturnedHit()throws Exception{
        String text=cn.bidcheck.compat.Java8Api.repeat("甲", 1000)+"\n"+cn.bidcheck.compat.Java8Api.repeat("乙", 500)+"目标工期45日历天"+cn.bidcheck.compat.Java8Api.repeat("丙", 800);
        Document doc=document(text);EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(doc));
        // This valid hit crosses proof-corpus windows: a prefix-only match would retain only the first.
        int start=900;Evidence hit=new Evidence("bid","测试文件",1,"PAGE",text.substring(start,Math.min(text.length(),start+1400)),"p1-c900",1);
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source> mapped=corpus.mapRetrievalHit(hit);
        assertTrue(mapped.size()>1);assertTrue(mapped.stream().anyMatch(source->source.evidence().quote().contains("目标工期45日历天")));
    }
    @Test void forgedOffsetsAndWrongDocumentCannotInjectSources(){
        String text="投标有效期90日历天";EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document(text)));
        assertTrue(corpus.mapRetrievalHit(new Evidence("bid","测试文件",1,"PAGE",text,"p1-c1",1)).isEmpty());
        assertTrue(corpus.mapRetrievalHit(new Evidence("other","测试文件",1,"PAGE",text,"p1-c0",1)).isEmpty());
        assertTrue(corpus.mapRetrievalHit(new Evidence("bid","测试文件",1,"PAGE","不存在的内容","p1-c0",1)).isEmpty());
    }
    @Test void disabledVectorSelectionIsRecordedWithoutPretendingToSearch()throws Exception{
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(document("设计工期：15日历天")));RunSession session=new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf(),"scope");
        cn.bidcheck.verification.engine.VerificationTypes.Rule rule=new VerificationTypes.Rule("r","设计工期","NUMBER","","designDuration","=","15","日",cn.bidcheck.compat.Java8Api.mapOf(),"设计工期15日历天","",cn.bidcheck.compat.Java8Api.listOf());
        corpus.select(cn.bidcheck.compat.Java8Api.listOf(rule),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),0,new HybridRetriever(),session);
        assertTrue(session.steps.stream().noneMatch(step->step.get("type").toString().startsWith("RETRIEVE_")));
        assertTrue(session.steps.toString().contains("VECTOR_DISABLED_LOCAL_SELECTION"));
    }
}

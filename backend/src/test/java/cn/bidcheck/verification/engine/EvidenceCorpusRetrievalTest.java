package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.retrieval.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceCorpusRetrievalTest {
    private Document document(String text){return new Document("bid","project","BID","测试文件",1,"sha","",List.of(new Page(1,text,"PAGE","NATIVE","")),List.of());}
    @Test void mismatchedChunkBoundariesKeepTheRelevantEndOfReturnedHit()throws Exception{
        String text="甲".repeat(1000)+"\n"+"乙".repeat(500)+"目标工期45日历天"+"丙".repeat(800);
        Document doc=document(text);EvidenceCorpus corpus=new EvidenceCorpus(List.of(doc));
        // This valid hit crosses proof-corpus windows: a prefix-only match would retain only the first.
        int start=900;Evidence hit=new Evidence("bid","测试文件",1,"PAGE",text.substring(start,Math.min(text.length(),start+1400)),"p1-c900",1);
        var mapped=corpus.mapRetrievalHit(hit);
        assertTrue(mapped.size()>1);assertTrue(mapped.stream().anyMatch(source->source.evidence().quote().contains("目标工期45日历天")));
    }
    @Test void forgedOffsetsAndWrongDocumentCannotInjectSources(){
        String text="投标有效期90日历天";EvidenceCorpus corpus=new EvidenceCorpus(List.of(document(text)));
        assertTrue(corpus.mapRetrievalHit(new Evidence("bid","测试文件",1,"PAGE",text,"p1-c1",1)).isEmpty());
        assertTrue(corpus.mapRetrievalHit(new Evidence("other","测试文件",1,"PAGE",text,"p1-c0",1)).isEmpty());
        assertTrue(corpus.mapRetrievalHit(new Evidence("bid","测试文件",1,"PAGE","不存在的内容","p1-c0",1)).isEmpty());
    }
    @Test void disabledVectorSelectionIsRecordedWithoutPretendingToSearch()throws Exception{
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(document("设计工期：15日历天")));RunSession session=new RunSession(ModelGateway.disabled(),Map.of(),"scope");
        var rule=new VerificationTypes.Rule("r","设计工期","NUMBER","","designDuration","=","15","日",Map.of(),"设计工期15日历天","",List.of());
        corpus.select(List.of(rule),List.of(),List.of(),0,new HybridRetriever(),session);
        assertTrue(session.steps.stream().noneMatch(step->step.get("type").toString().startsWith("RETRIEVE_")));
        assertTrue(session.steps.toString().contains("VECTOR_DISABLED_LOCAL_SELECTION"));
    }
}

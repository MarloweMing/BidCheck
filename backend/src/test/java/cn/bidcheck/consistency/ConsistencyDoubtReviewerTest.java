package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.retrieval.RetrievalResult;
import cn.bidcheck.semantic.SemanticAssessment;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

class ConsistencyDoubtReviewerTest {
    Document doc=new Document("d","p","BID","bid.txt",1,"hash","now",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"本次投标函\n投标企业名称：北京甲科技有限公司","PAGE","TEXT",""), new Page(2,"本次投标报价表\n投标企业名称：北京乙科技有限公司","PAGE","TEXT",""), new Page(3,"补充说明：企业名称变更仍需核实。","PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
    Evidence e(int p){Page page=doc.pages().get(p-1);return new Evidence("d",doc.name(),p,"PAGE",page.text(),"p"+p,1);}
    Map<String,Object> report(){return values("groups",cn.bidcheck.compat.Java8Api.listOf(values("id","g","field","companyName","fieldLabel","投标企业名称","title","投标企业名称存在疑似差异",
        "status","SUSPECTED","reason","相同岗位主体存在不同名称","values",cn.bidcheck.compat.Java8Api.listOf("北京甲科技有限公司", "北京乙科技有限公司"),"evidences",cn.bidcheck.compat.Java8Api.listOf(e(1), e(2)),"occurrenceIds",cn.bidcheck.compat.Java8Api.listOf("a", "b"))),
        "occurrences",cn.bidcheck.compat.Java8Api.listOf(values("id","a","value","北京甲科技有限公司","evidence",e(1)), values("id","b","value","北京乙科技有限公司","evidence",e(2))));}
    ConsistencyDoubtReviewer reviewer(ConsistencyDoubtReviewer.Judge judge){return new ConsistencyDoubtReviewer((d,q,b)->new RetrievalResult(cn.bidcheck.compat.Java8Api.listOf(e(3)),"BM25",cn.bidcheck.compat.Java8Api.mapOf()),judge,()->true);}
    SemanticAssessment answer(List<Evidence> refs){return new SemanticAssessment(null,new Evaluation("WARNING","企业名称表述有差异，需核对更名依据。","核对原件",refs,0,"MODEL"),values("model",values("requested",true,"decision","MODEL_ESCALATED","attempts",cn.bidcheck.compat.Java8Api.listOf(values("httpStatus",200,"state","VALID_OUTPUT"))),"guards",cn.bidcheck.compat.Java8Api.listOf()));}
    Map<String,Object> receipt(Map<String,Object> report){return map(maps(report.get("groups")).get(0).get("targetedReview"));}
    @Test void preservesBothSidesAndAddsEvidenceWithoutChangingOriginalConclusion(){java.util.Map<java.lang.String, java.lang.Object> r=report();List<String> events=new ArrayList<>();
        reviewer((cp,d,packet,budget)->{assertTrue(packet.contains(e(1)));assertTrue(packet.contains(e(2)));assertTrue(packet.contains(e(3)));assertTrue(budget.getAsBoolean());return answer(cn.bidcheck.compat.Java8Api.listOf(e(1), e(2)));})
            .review(doc,r,()->true,g->events.add(string(map(g.get("targetedReview")),"status")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("IN_PROGRESS", "MODEL_REVIEWED"),events);assertEquals("SUSPECTED",maps(r.get("groups")).get(0).get("status"));
        assertEquals(3,receipt(r).get("acceptedEvidenceCount"));assertEquals(true,map(receipt(r).get("recommendation")).get("humanConfirmationRequired"));}
    @Test void ignoresForeignDocumentAndFabricatedQuote(){java.util.Map<java.lang.String, java.lang.Object> r=report();cn.bidcheck.domain.Domain.Evidence bad=new Evidence("other","other",1,"PAGE",e(1).quote(),"p1",1);
        new ConsistencyDoubtReviewer((d,q,b)->new RetrievalResult(cn.bidcheck.compat.Java8Api.listOf(bad, new Evidence("d",doc.name(),99,"PAGE","伪造","p99",1)),"BM25",cn.bidcheck.compat.Java8Api.mapOf()),
            (cp,d,p,b)->{assertEquals(2,p.size());return answer(p);},()->true).review(doc,r,()->true,g->{});
        assertEquals(2,receipt(r).get("rejectedEvidenceCount"));}
    @Test void missingOriginalCannotBeReplacedBySearch(){java.util.Map<java.lang.String, java.lang.Object> r=report();maps(r.get("groups")).get(0).put("evidences",cn.bidcheck.compat.Java8Api.listOf(new Evidence("other","other",1,"PAGE",e(1).quote(),"p1",1)));
        new ConsistencyDoubtReviewer((d,q,b)->{fail("Cannot retrieve around invalid original");return null;},(a,b,c,d)->{fail();return null;},()->true).review(doc,r,()->true,g->{});
        assertEquals("EVIDENCE_INSUFFICIENT",receipt(r).get("status"));}
    @Test void oneSidedModelCitationIsGuardedAndAttemptsCounted(){java.util.Map<java.lang.String, java.lang.Object> r=report();reviewer((cp,d,p,b)->answer(cn.bidcheck.compat.Java8Api.listOf(e(1)))).review(doc,r,()->true,g->{});
        assertEquals("UNDETERMINED",map(receipt(r).get("recommendation")).get("status"));assertEquals("EVIDENCE_AUGMENTED",receipt(r).get("status"));
        assertEquals("GUARDED_OUTPUT",maps(map(receipt(r).get("model")).get("attempts")).get(0).get("state"));assertEquals(1,map(r.get("doubtReview")).get("modelAttempts"));}
    @Test void noSecondDistinctValueDoesNotCallModel(){java.util.Map<java.lang.String, java.lang.Object> r=report();maps(r.get("occurrences")).get(1).put("value","北京甲科技有限公司");
        reviewer((a,b,c,d)->{fail("No actual pair");return null;}).review(doc,r,()->true,g->{});assertEquals("EVIDENCE_INSUFFICIENT",receipt(r).get("status"));}
    @Test void completedReceiptResumesWithoutRepeatingRetrievalOrModel(){java.util.Map<java.lang.String, java.lang.Object> r=report();AtomicInteger calls=new AtomicInteger();cn.bidcheck.consistency.ConsistencyDoubtReviewer reviewer=reviewer((cp,d,p,b)->{calls.incrementAndGet();return answer(cn.bidcheck.compat.Java8Api.listOf(e(1), e(2)));});
        reviewer.review(doc,r,()->true,g->{});reviewer.review(doc,r,()->true,g->{});assertEquals(1,calls.get());}
    @Test void interruptedReceiptIsUnknownWithoutRedispatch(){java.util.Map<java.lang.String, java.lang.Object> r=report();maps(r.get("groups")).get(0).put("targetedReview",values("policyVersion",ConsistencyDoubtReviewer.VERSION,"status","IN_PROGRESS","admitted",true));
        reviewer((a,b,c,d)->{fail();return null;}).review(doc,r,()->true,g->{});assertEquals("ERROR",receipt(r).get("status"));assertTrue(string(receipt(r),"reason").contains("未知"));
        assertNull(receipt(r).get("modelRequested"));assertEquals(true,receipt(r).get("requestOutcomeUnknown"));assertEquals(1,map(r.get("doubtReview")).get("unknownRequestGroups"));}
    @Test void everyEligibleGroupIsReviewedWithoutLosingOriginalGroups(){java.util.Map<java.lang.String, java.lang.Object> r=report();List<Map<String,Object>> groups=new ArrayList<>();for(int i=0;i<10;i++){java.util.LinkedHashMap<java.lang.String, java.lang.Object> g=new LinkedHashMap<>(maps(r.get("groups")).get(0));g.put("id","g"+i);groups.add(g);}r.put("groups",groups);
        AtomicInteger calls=new AtomicInteger();reviewer((cp,d,p,b)->{calls.incrementAndGet();return answer(cn.bidcheck.compat.Java8Api.listOf(e(1), e(2)));}).review(doc,r,()->true,g->{});
        assertEquals(10,calls.get());assertEquals(10,maps(r.get("groups")).size());assertEquals(0,map(r.get("doubtReview")).get("deferredGroups"));
        assertEquals(10,map(r.get("doubtReview")).get("modelAttempts"));assertEquals(true,map(r.get("doubtReview")).get("unlimitedGroups"));assertEquals(-1,map(r.get("doubtReview")).get("maxGroups"));}
    @Test void deniedReservationIsNotModelReviewed(){java.util.Map<java.lang.String, java.lang.Object> r=report();reviewer((cp,d,p,b)->{assertFalse(b.getAsBoolean());return new SemanticAssessment(null,new Evaluation("UNDETERMINED","请求未获准","人工核对",p,0,"RULES"),values("model",values("decision","BUDGET_DEFERRED","requested",false,"attempts",cn.bidcheck.compat.Java8Api.listOf()),"guards",cn.bidcheck.compat.Java8Api.listOf()));})
        .review(doc,r,()->false,g->{});assertEquals("BUDGET_DEFERRED",receipt(r).get("status"));assertEquals(0,map(r.get("doubtReview")).get("modelAttempts"));}
    @Test void leaseLossPropagatesLeavingPersistedIntent(){java.util.Map<java.lang.String, java.lang.Object> r=report();List<String> events=new ArrayList<>();assertThrows(TaskLeaseLostException.class,()->reviewer((cp,d,p,b)->{b.getAsBoolean();return null;})
        .review(doc,r,()->{throw new TaskLeaseLostException("t");},g->events.add(string(map(g.get("targetedReview")),"status"))));assertEquals(cn.bidcheck.compat.Java8Api.listOf("IN_PROGRESS"),events);}
    @Test void disabledModelStillProvidesSearchEvidence(){java.util.Map<java.lang.String, java.lang.Object> r=report();new ConsistencyDoubtReviewer((d,q,b)->new RetrievalResult(cn.bidcheck.compat.Java8Api.listOf(e(3)),"BM25",cn.bidcheck.compat.Java8Api.mapOf()),(a,b,c,d)->{fail();return null;},()->false)
        .review(doc,r,()->true,g->{});assertEquals("MODEL_UNAVAILABLE",receipt(r).get("status"));assertEquals(3,receipt(r).get("acceptedEvidenceCount"));}
    @Test void ruleConflictDoesNotNeedModelOrRetrieval(){java.util.Map<java.lang.String, java.lang.Object> r=report();maps(r.get("groups")).get(0).put("status","CONFLICT");new ConsistencyDoubtReviewer((a,b,c)->{fail();return null;},(a,b,c,d)->{fail();return null;},()->true)
        .review(doc,r,()->true,g->{});assertEquals("RULE_CONFIRMED",receipt(r).get("status"));}
    @Test void recoveredElapsedTimeIsPreservedWithoutDeferringEligibleReview(){java.util.Map<java.lang.String, java.lang.Object> r=report();r.put("doubtReview",values("elapsedMillis",120_000L));
        AtomicInteger calls=new AtomicInteger();new ConsistencyDoubtReviewer((a,b,c)->{assertEquals(12_000L,c.toMillis());return new RetrievalResult(cn.bidcheck.compat.Java8Api.listOf(e(3)),"BM25",cn.bidcheck.compat.Java8Api.mapOf());},
            (cp,d,p,reserve)->{assertTrue(reserve.getAsBoolean());calls.incrementAndGet();return answer(cn.bidcheck.compat.Java8Api.listOf(e(1), e(2)));},()->true).review(doc,r,()->true,g->{});
        assertEquals(1,calls.get());assertEquals("MODEL_REVIEWED",receipt(r).get("status"));assertEquals(0,map(r.get("doubtReview")).get("deferredGroups"));
        assertTrue(((Number)map(r.get("doubtReview")).get("elapsedMillis")).longValue()>=120_000L);
        assertEquals(true,map(r.get("doubtReview")).get("unlimitedAdmissionTime"));assertEquals(true,map(r.get("doubtReview")).get("unlimitedModelAttempts"));}
    @Test void duplicateSearchResultsAreCountedSeparatelyFromRejectedSources(){java.util.Map<java.lang.String, java.lang.Object> r=report();new ConsistencyDoubtReviewer((a,b,c)->new RetrievalResult(cn.bidcheck.compat.Java8Api.listOf(e(1)),"BM25",cn.bidcheck.compat.Java8Api.mapOf()),
        (cp,d,p,b)->answer(cn.bidcheck.compat.Java8Api.listOf(e(1), e(2))),()->true).review(doc,r,()->true,g->{});
        assertEquals(3,receipt(r).get("candidateCount"));assertEquals(2,receipt(r).get("acceptedEvidenceCount"));assertEquals(1,receipt(r).get("deduplicatedEvidenceCount"));assertEquals(0,receipt(r).get("rejectedEvidenceCount"));}
    @Test void singleValueIsClassifiedAfterLongElapsedTimeWithoutSearch(){java.util.Map<java.lang.String, java.lang.Object> r=report();
        maps(r.get("occurrences")).get(1).put("value","北京甲科技有限公司");r.put("doubtReview",values("elapsedMillis",120_000L));
        new ConsistencyDoubtReviewer((a,b,c)->{fail("No useful comparison pair to retrieve");return null;},(a,b,c,d)->{fail();return null;},()->true).review(doc,r,()->true,g->{});
        assertEquals("EVIDENCE_INSUFFICIENT",receipt(r).get("status"));assertEquals("NO_DISTINCT_VALUES",map(receipt(r).get("triage")).get("reasonCode"));
        assertEquals(0,map(r.get("doubtReview")).get("deferredGroups"));assertEquals(1,map(r.get("doubtReview")).get("localOnlyGroups"));
        assertEquals(1,map(r.get("doubtReview")).get("pendingManualGroups"));}
    @Test void aggregatedUnalignedFactsAreNotSentAsAnArtificialPair(){java.util.Map<java.lang.String, java.lang.Object> r=report();java.util.Map<java.lang.String, java.lang.Object> g=maps(r.get("groups")).get(0);
        g.put("status","EVIDENCE_INSUFFICIENT");g.put("comparisonContext",values("aggregation","按字段和缺口原因汇总","scope","UNRESOLVED","subject","UNRESOLVED"));
        new ConsistencyDoubtReviewer((a,b,c)->{fail("Aggregated materials are not a proven pair");return null;},(a,b,c,d)->{fail();return null;},()->true).review(doc,r,()->true,x->{});
        assertEquals("UNALIGNED_AGGREGATE",map(receipt(r).get("triage")).get("reasonCode"));assertEquals(2,receipt(r).get("acceptedEvidenceCount"));
        assertEquals("EVIDENCE_INSUFFICIENT",g.get("status"));assertEquals(0,map(r.get("doubtReview")).get("modelAttempts"));}
    @Test void missingOccurrenceCannotBeSubstitutedByRetrieval(){java.util.Map<java.lang.String, java.lang.Object> r=report();maps(r.get("groups")).get(0).put("occurrenceIds",cn.bidcheck.compat.Java8Api.listOf("a", "b", "missing"));
        new ConsistencyDoubtReviewer((a,b,c)->{fail();return null;},(a,b,c,d)->{fail();return null;},()->true).review(doc,r,()->true,g->{});
        assertEquals("MISSING_COMPARISON_SIDE",map(receipt(r).get("triage")).get("reasonCode"));}
    @Test void ruleConflictStillRequiresEveryOriginalOccurrenceSource(){java.util.Map<java.lang.String, java.lang.Object> r=report();java.util.Map<java.lang.String, java.lang.Object> g=maps(r.get("groups")).get(0);
        g.put("status","CONFLICT");g.put("occurrenceIds",cn.bidcheck.compat.Java8Api.listOf("a", "missing"));
        new ConsistencyDoubtReviewer((a,b,c)->{fail();return null;},(a,b,c,d)->{fail();return null;},()->true).review(doc,r,()->true,x->{});
        assertEquals("EVIDENCE_INSUFFICIENT",receipt(r).get("status"));assertEquals("MISSING_COMPARISON_SIDE",map(receipt(r).get("triage")).get("reasonCode"));
        assertEquals("CONFLICT",maps(r.get("groups")).get(0).get("status"));}
    @Test void aValidSideMissingFromOriginalPacketIsNotSilentlyReplacedByTopK(){java.util.Map<java.lang.String, java.lang.Object> r=report();maps(r.get("groups")).get(0).put("evidences",cn.bidcheck.compat.Java8Api.listOf(e(1)));
        new ConsistencyDoubtReviewer((a,b,c)->{fail();return null;},(a,b,c,d)->{fail();return null;},()->true).review(doc,r,()->true,x->{});
        assertEquals("MISSING_COMPARISON_SIDE",map(receipt(r).get("triage")).get("reasonCode"));assertEquals(1,receipt(r).get("acceptedEvidenceCount"));}
    @Test void localOnlyGroupsDoNotPreventEligibleGroupReview(){java.util.Map<java.lang.String, java.lang.Object> r=report();List<Map<String,Object>> groups=new ArrayList<>();
        for(int i=0;i<12;i++){java.util.LinkedHashMap<java.lang.String, java.lang.Object> g=new LinkedHashMap<>(maps(r.get("groups")).get(0));g.put("id","missing"+i);g.put("occurrenceIds",cn.bidcheck.compat.Java8Api.listOf("a"));groups.add(g);}
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> actual=new LinkedHashMap<>(maps(r.get("groups")).get(0));actual.put("id","actual");groups.add(actual);r.put("groups",groups);
        AtomicInteger calls=new AtomicInteger();reviewer((cp,d,p,b)->{calls.incrementAndGet();return answer(cn.bidcheck.compat.Java8Api.listOf(e(1), e(2)));}).review(doc,r,()->true,g->{});
        assertEquals(1,calls.get());assertEquals(13,map(r.get("doubtReview")).get("triagedGroups"));assertEquals(12,map(r.get("doubtReview")).get("localOnlyGroups"));
        assertEquals(0,map(r.get("doubtReview")).get("deferredGroups"));assertEquals(13,map(r.get("doubtReview")).get("pendingManualGroups"));}
    @Test void oversizedOriginalIsPreservedWithoutWastefulSearch(){java.util.Map<java.lang.String, java.lang.Object> r=report();String large=cn.bidcheck.compat.Java8Api.repeat("甲", 1801);
        Document d=new Document("d","p","BID","bid.txt",1,"hash","now",cn.bidcheck.compat.Java8Api.listOf(doc.pages().get(0), doc.pages().get(1), new Page(3,large,"PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        Evidence extra=new Evidence("d",d.name(),3,"PAGE",large,"p3",1);maps(r.get("groups")).get(0).put("evidences",cn.bidcheck.compat.Java8Api.listOf(e(1), e(2), extra));
        new ConsistencyDoubtReviewer((a,b,c)->{fail();return null;},(a,b,c,dd)->{fail();return null;},()->true).review(d,r,()->true,g->{});
        assertEquals("ORIGINAL_PACKET_LIMIT",map(receipt(r).get("triage")).get("reasonCode"));assertEquals(3,receipt(r).get("acceptedEvidenceCount"));}
}

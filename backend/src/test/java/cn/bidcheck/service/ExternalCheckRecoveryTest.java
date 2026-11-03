package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evaluation;
import cn.bidcheck.domain.Domain.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCheckRecoveryTest {
    @Test void trustedRecordIsSavedThenReloadedAndProgrammaticallyRecomputed() {
        Finding before=finding("UNDETERMINED"),pass=finding("PASS");
        Map<String,Object> record=trustedRecord();List<Map<String,Object>> saved=new ArrayList<>();
        AtomicInteger evaluations=new AtomicInteger();
        ExternalCheckRecovery.Outcome first=ExternalCheckRecovery.execute(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(record),
            saved::addAll,()->cn.bidcheck.compat.Java8Api.mapOf("externalRecords", cn.bidcheck.compat.Java8Api.listCopyOf(saved)),context->{
                evaluations.incrementAndGet();assertEquals(cn.bidcheck.compat.Java8Api.listOf(record),context.get("externalRecords"));return pass;
            },before);
        assertTrue(first.recomputed());assertSame(pass,first.finding());assertEquals(cn.bidcheck.compat.Java8Api.listOf(record),first.newRecords());
        assertEquals(1,evaluations.get());assertEquals(cn.bidcheck.compat.Java8Api.listOf(record),saved);

        ExternalCheckRecovery.Outcome duplicate=ExternalCheckRecovery.execute(cn.bidcheck.compat.Java8Api.listCopyOf(saved),cn.bidcheck.compat.Java8Api.listOf(record),
            ignored->{},()->cn.bidcheck.compat.Java8Api.mapOf("externalRecords", cn.bidcheck.compat.Java8Api.listCopyOf(saved)),context->{evaluations.incrementAndGet();return pass;},before);
        assertFalse(duplicate.recomputed());assertSame(before,duplicate.finding());assertTrue(duplicate.newRecords().isEmpty());
        assertEquals(1,evaluations.get(),"an identical persisted receipt cannot trigger another conclusion calculation");
    }

    @Test void unauthenticatedOrIncompleteRecordNeverReachesPersistenceOrEvaluator() {
        Map<String,Object> untrusted=cn.bidcheck.compat.Java8Api.mapOf("provenanceStatus", "UPLOADED_DECLARATION", "type", "CREDIT_CHINA");
        AtomicInteger publishes=new AtomicInteger(),evaluations=new AtomicInteger();Finding before=finding("UNDETERMINED");
        ExternalCheckRecovery.Outcome outcome=ExternalCheckRecovery.execute(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(untrusted),
            records->publishes.incrementAndGet(),()->cn.bidcheck.compat.Java8Api.mapOf("externalRecords", cn.bidcheck.compat.Java8Api.listOf()),context->{evaluations.incrementAndGet();return finding("PASS");},before);
        assertFalse(outcome.recomputed());assertSame(before,outcome.finding());assertEquals(0,publishes.get());assertEquals(0,evaluations.get());
    }

    @Test void authenticatedPartialRecordIsPersistedAndRecomputedWithoutClaimingConclusion(){
        Map<String,Object> partial=new java.util.LinkedHashMap<>(trustedRecord());partial.put("coverageComplete",false);partial.put("result",cn.bidcheck.compat.Java8Api.mapOf("status", "INCONCLUSIVE", "facts", cn.bidcheck.compat.Java8Api.listOf()));
        List<Map<String,Object>> saved=new ArrayList<>();Finding before=finding("UNDETERMINED");AtomicInteger calls=new AtomicInteger();
        cn.bidcheck.service.ExternalCheckRecovery.Outcome outcome=ExternalCheckRecovery.execute(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(partial),saved::addAll,()->cn.bidcheck.compat.Java8Api.mapOf("externalRecords", saved),context->{calls.incrementAndGet();return before;},before);
        assertTrue(outcome.recomputed());assertEquals(1,calls.get());assertEquals(cn.bidcheck.compat.Java8Api.listOf(partial),saved);assertEquals("UNDETERMINED",outcome.finding().evaluation().status());
        partial.put("result",cn.bidcheck.compat.Java8Api.mapOf("status", "NOT_FOUND", "facts", cn.bidcheck.compat.Java8Api.listOf()));saved.clear();
        cn.bidcheck.service.ExternalCheckRecovery.Outcome invalid=ExternalCheckRecovery.execute(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(partial),saved::addAll,()->cn.bidcheck.compat.Java8Api.mapOf("externalRecords", saved),context->{fail("partial negative must not be accepted");return before;},before);
        assertFalse(invalid.recomputed());assertTrue(saved.isEmpty());
    }

    private static Finding finding(String status){
        Checkpoint checkpoint=new Checkpoint("cp","失信记录核验","资格检查","HIGH","SEMANTIC_RESPONSE","dishonestJudgmentDebtor","=","false","",true,true,true,null);
        return new Finding("finding","run",checkpoint,new Evaluation(status,"规则计算结果","",cn.bidcheck.compat.Java8Api.listOf(),0,"FOCUSED_ATOMIC_RULES"),cn.bidcheck.compat.Java8Api.mapOf());
    }
    private static Map<String,Object> trustedRecord(){
        return BidService.fields("id","external-1","type","CREDIT_CHINA","subject","海南测试有限公司","source","信用中国",
            "issuer","国家公共信用信息中心","queriedAt","2026-10-19T08:00:00Z","effectiveAt","2026-10-19",
            "evaluationStage","BID_SUBMISSION","coverage","指定主体失信记录","coverageComplete",true,
            "rawSnapshotHash","snapshot-sha","provenanceStatus","TRUSTED_CONNECTOR",
            "result",cn.bidcheck.compat.Java8Api.mapOf("status", "VERIFIED", "facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "dishonestJudgmentDebtor", "value", false, "rawValue", "false", "sourceQuote", "dishonestJudgmentDebtor=false"))));
    }
}

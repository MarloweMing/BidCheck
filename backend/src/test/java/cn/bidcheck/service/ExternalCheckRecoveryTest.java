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
        ExternalCheckRecovery.Outcome first=ExternalCheckRecovery.execute(List.of(),List.of(record),
            saved::addAll,()->Map.of("externalRecords",List.copyOf(saved)),context->{
                evaluations.incrementAndGet();assertEquals(List.of(record),context.get("externalRecords"));return pass;
            },before);
        assertTrue(first.recomputed());assertSame(pass,first.finding());assertEquals(List.of(record),first.newRecords());
        assertEquals(1,evaluations.get());assertEquals(List.of(record),saved);

        ExternalCheckRecovery.Outcome duplicate=ExternalCheckRecovery.execute(List.copyOf(saved),List.of(record),
            ignored->{},()->Map.of("externalRecords",List.copyOf(saved)),context->{evaluations.incrementAndGet();return pass;},before);
        assertFalse(duplicate.recomputed());assertSame(before,duplicate.finding());assertTrue(duplicate.newRecords().isEmpty());
        assertEquals(1,evaluations.get(),"an identical persisted receipt cannot trigger another conclusion calculation");
    }

    @Test void unauthenticatedOrIncompleteRecordNeverReachesPersistenceOrEvaluator() {
        Map<String,Object> untrusted=Map.of("provenanceStatus","UPLOADED_DECLARATION","type","CREDIT_CHINA");
        AtomicInteger publishes=new AtomicInteger(),evaluations=new AtomicInteger();Finding before=finding("UNDETERMINED");
        ExternalCheckRecovery.Outcome outcome=ExternalCheckRecovery.execute(List.of(),List.of(untrusted),
            records->publishes.incrementAndGet(),()->Map.of("externalRecords",List.of()),context->{evaluations.incrementAndGet();return finding("PASS");},before);
        assertFalse(outcome.recomputed());assertSame(before,outcome.finding());assertEquals(0,publishes.get());assertEquals(0,evaluations.get());
    }

    private static Finding finding(String status){
        Checkpoint checkpoint=new Checkpoint("cp","失信记录核验","资格检查","HIGH","SEMANTIC_RESPONSE","dishonestJudgmentDebtor","=","false","",true,true,true,null);
        return new Finding("finding","run",checkpoint,new Evaluation(status,"规则计算结果","",List.of(),0,"FOCUSED_ATOMIC_RULES"),Map.of());
    }
    private static Map<String,Object> trustedRecord(){
        return BidService.fields("id","external-1","type","CREDIT_CHINA","subject","海南测试有限公司","source","信用中国",
            "issuer","国家公共信用信息中心","queriedAt","2026-10-19T08:00:00Z","effectiveAt","2026-10-19",
            "evaluationStage","BID_SUBMISSION","coverage","指定主体失信记录","coverageComplete",true,
            "rawSnapshotHash","snapshot-sha","provenanceStatus","TRUSTED_CONNECTOR",
            "result",Map.of("status","VERIFIED","facts",List.of(Map.of("field","dishonestJudgmentDebtor","value",false,
                "rawValue","false","sourceQuote","dishonestJudgmentDebtor=false"))));
    }
}

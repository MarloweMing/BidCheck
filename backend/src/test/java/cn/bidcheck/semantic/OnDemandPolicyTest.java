package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnDemandPolicyTest {
    static Checkpoint check(String text){return new Checkpoint("c",text,"响应检查","HIGH","SEMANTIC_RESPONSE","technicalResponse","RESPONDS",text,"",true,true,true,null);}
    static Evidence evidence(String quote){return new Evidence("d","bid.pdf",1,"PAGE",quote,"p1",1);}
    @Test void retrievalMissIsUncertainAndNeverCallsModel(){var d=OnDemandPolicy.decide(check("提供有效营业执照证明"),List.of());assertEquals("EVIDENCE_INSUFFICIENT",d.code());assertFalse(d.requestModel());}
    @Test void unrelatedHitAndSharedGenericWordsAreNotEvidence(){assertFalse(OnDemandPolicy.decide(check("社保证明须连续六个月"),List.of(evidence("投标文件满足项目要求，本次工程提供质量方案"))).requestModel());}
    @Test void declarationCannotSubstituteIndependentProof(){assertFalse(OnDemandPolicy.decide(check("提供营业执照和资质证明"),List.of(evidence("承诺书：我方承诺提供营业执照和资质证明"))).requestModel());}
    @Test void explicitExternalInputRemainsUncertain(){var d=OnDemandPolicy.decide(check("官方实时查询信用信息"),List.of(evidence("信用信息登记结果")));assertEquals("EXTERNAL_INPUT_REQUIRED",d.code());assertFalse(d.requestModel());}
    @Test void sourceBoundComplexConditionCanEscalate(){assertTrue(OnDemandPolicy.decide(check("社保证明须连续六个月且单位归属一致"),List.of(evidence("社保证明：缴费单位甲有限公司，2026年1月至6月连续缴费"))).requestModel());}
    @Test void simpleKeywordPresenceIsNotPassOrAutomaticModel(){var d=OnDemandPolicy.decide(check("提供售后服务说明"),List.of(evidence("售后服务说明：客户可通过热线咨询")));assertEquals("RULE_UNSUPPORTED",d.code());assertFalse(d.requestModel());}
    @Test void missingAssessmentAndUnobservedReservedAttemptsRemainUnknown(){Map<String,Object> run=new LinkedHashMap<>(Map.of("checkMode","RULES_RETRIEVAL","onDemandAttemptsReserved",2,"onDemandAttemptBudget",24));
        Map<?,?> summary=(Map<?,?>)OnDemandSummary.summarize(run,List.of(),3).get("onDemandModel");assertEquals(3,summary.get("unknownCheckpoints"));assertEquals(0,summary.get("httpAttempts"));assertEquals(2,summary.get("attemptsReserved"));}
}

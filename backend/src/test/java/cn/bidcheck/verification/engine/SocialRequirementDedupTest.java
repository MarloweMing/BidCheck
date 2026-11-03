package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class SocialRequirementDedupTest {
    private static final String FROZEN="须提交有效证明文件及2024年12月（含）至文件递交截止时间任意连续三个月在本单位缴纳社保证明材料，特殊情况提交说明文件（格式自拟）及有效的证明文件（如单位设立时间不足、人员新入职、离退休、转业等）。";
    private List<Rule> flatten(List<Rule> rules){List<Rule> out=new ArrayList<>();for(Rule r:rules){out.add(r);out.addAll(flatten(r.children()));}return out;}
    @Test void theFrozenSocialProofPhraseCompilesToOneMonthsObligation(){
        assertTrue(DeterministicRequirementCompiler.socialMonthProofOnly(FROZEN));
        cn.bidcheck.domain.Domain.Checkpoint checkpoint=new Checkpoint("social-frozen","人员要求","资格","HIGH","SEMANTIC_RESPONSE","","",FROZEN,"",true,true,true,null);
        cn.bidcheck.verification.engine.DeterministicRequirementCompiler.Result compiled=new DeterministicRequirementCompiler(new ObjectMapper()).compile(checkpoint,FROZEN,null,cn.bidcheck.compat.Java8Api.mapOf());
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> rules=flatten(compiled.rules());java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> months=cn.bidcheck.compat.Java8Api.toList(rules.stream().filter(r->r.predicate().equals("MONTHS")));assertEquals(1,months.size(),compiled.audit().toString());assertEquals(3,months.get(0).parameters().get("count"));assertEquals("2024-12",months.get(0).parameters().get("earliestMonth"));assertEquals("本单位",months.get(0).parameters().get("employer"));assertTrue(rules.stream().noneMatch(r->r.predicate().equals("MATERIAL")),compiled.audit().toString());
    }
    @Test void additionalLaborContractSignatureOrUnknownExceptionCannotBeDiscarded(){
        for(String extra:cn.bidcheck.compat.Java8Api.listOf("另须提交劳动合同。", "并由法定代表人签字。", "，特殊情况须提交主管部门审批意见。"))assertFalse(DeterministicRequirementCompiler.socialMonthProofOnly(FROZEN+extra));
        assertFalse(DeterministicRequirementCompiler.socialMonthProofOnly(FROZEN.replace("社保证明材料","社保证明材料及劳动合同")));
    }
    @Test void anotherTenderCanUseOtherWindowAndCountsWithoutSpecificProjectNames(){
        assertTrue(DeterministicRequirementCompiler.socialMonthProofOnly("应提供2026年2月起至投标截止日期连续6个月在本单位缴纳社保证明。"));
        assertTrue(DeterministicRequirementCompiler.socialMonthProofOnly("须提供连续六个月在本单位缴纳社保证明材料。"));
    }
}

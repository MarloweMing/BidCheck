package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class SigningDateBlockTest {
    // Minimal title/role/date fragments of the frozen OCR, with unrelated seal details omitted.
    private static final String PARALLEL_314="**乙方（盖章）：**\n\n**法定代表人 / 签约授权代表：**\n"
        +"---\n甲方（盖章）：\n负责人/签约授权代表：___ 法定代表人/签约授权代表：___\n"
        +"单位地址：\n单位地址：\n2022年11月29日\n\n2022年11月29日";
    private static final String SIGNATURE_287="合同签字页\n甲方（盖章）中国联合网络通信有限公司山西省分公司\n"
        +"2022年4月25日\n乙方（盖章）北京中雅达国际装饰有限公司\n法人或授权代理人：\n2022年4月24日";
    private SigningDateBlock parse(String quote) {
        return SigningDateBlock.read(Collections.singletonList(source(corpus(quote),1)));
    }
    @Test void sameDateInBothPartyBlocksIsConfirmed() {
        assertTrue(parse(PARALLEL_314).confirms("2022-11-29"));
        assertTrue(parse("甲方（盖章）\n2022年11月29日\n乙方（盖章）\n2022年11月29日").confirms("2022-11-29"));
        assertEquals(SigningDateBlock.Status.NOT_FOUND,parse(PARALLEL_314.replace("负责人/签约授权代表：___ 法定代表人/签约授权代表：___","")).status);
    }
    @Test void differentPartyDatesIsConflictNotRejection() throws Exception {
        SigningDateBlock dates=parse(SIGNATURE_287);assertEquals(SigningDateBlock.Status.CONFLICT,dates.status);
        assertEquals("2022-04-25",dates.first);assertEquals("2022-04-24",dates.second);
        Fixture f=new Fixture(identityBody(),SIGNATURE_287);f.host.ctx.put("enableProgrammaticInferences",true);
        Source page=source(f.corpus,5);
        Map<String,Object> atom=linked("field","signedDate","rawValue","2022年4月25日","sourceIds",Collections.singletonList(page.id()),
            "attributes",linked("signedDate","2022-04-25"));
        Map<String,Object> receipt=ContractRecordAnchorTest.submit(f,atom);
        Map<String,Object> result=RuleEvaluator.maps(receipt.get("fieldResults")).get(0);
        assertEquals("NEEDS_CONFIRMATION",result.get("status"),result::toString);
        assertEquals("CONTRACT_SIGNING_DATE_CONFLICT",result.get("gapCode"));
        assertTrue(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());
        assertTrue(f.host.ctx.containsKey("inferredFacts"),result::toString);
        assertTrue(String.valueOf(f.host.ctx.get("inferredFacts")).contains("2022-04-25"));
        assertTrue(String.valueOf(f.host.ctx.get("inferredFacts")).contains("2022-04-24"));
    }
    @Test void constructionPeriodDateIsIgnored() {
        assertEquals(SigningDateBlock.Status.NOT_FOUND,parse("甲方（盖章）\n开工日期 2022年4月25日\n乙方（盖章）\n竣工日期 2022年4月25日").status);
    }
    @Test void singlePartyDateIsNotFound() {
        assertEquals(SigningDateBlock.Status.NOT_FOUND,parse("甲方（盖章）\n2022年4月25日\n乙方（盖章）").status);
    }
    @Test void labelledDateStillWins() {
        assertTrue(parse("合同签订日期：2022年4月30日\n"+SIGNATURE_287).confirms("2022-04-30"));
    }
}

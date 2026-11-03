package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FullTextReviewTest {
    private Document doc(String text) { return new Document("bid","p","BID","投标.pdf",1,"h","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf()); }
    private Evaluation review(String field,String expected,String text) {
        Document doc=doc("certificateCoverage".equals(field)?"本项目证件\n"+text:text);
        Checkpoint cp=new Checkpoint("c","全文检查","全文信息检查","HIGH","FULL_TEXT_REVIEW",field,"REVIEW",expected,"",true,true,true,null);
        return new CheckEngine().evaluate(cp,doc,new FactExtractor().extract(doc));
    }

    @Test void noTextAnomalyCannotProveExhaustiveGlobalPass() {
        for(String field:cn.bidcheck.compat.Java8Api.listOf("fullTextConsistency", "amountAccuracy", "missingFields", "signatureDate", "signingAfterOpening", "typos", "certificateCoverage")) {
            assertEquals("UNDETERMINED",review(field,"2026-10-20","投标人名称：甲公司\n投标人名称：甲公司\n签署日期：2026年10月19日\n证书有效期至：2027年12月31日").status(),field);
        }
    }

    @Test void bidderConflictRetainsEvidenceWhileDifferentSubjectsDoNotConflict() {
        assertEquals("FAIL",review("fullTextConsistency","","投标人名称：甲公司\n投标人名称：乙公司").status());
        assertEquals("UNDETERMINED",review("fullTextConsistency","","投标人名称：甲公司\n甲方公司名称：乙公司").status());
    }

    @Test void multiplicationMismatchIsCitedWithoutMixingRowsOrTaxes() {
        cn.bidcheck.domain.Domain.Evaluation error=review("amountAccuracy","","数量：3；单价：100元；小计：200元");
        assertEquals("WARNING",error.status());
        assertEquals("数量：3；单价：100元；小计：200元",error.evidences().get(0).quote());
        for(String text:cn.bidcheck.compat.Java8Api.listOf("数量：3；单价：100元；小计：300元", "数量：3\n单价：100元\n小计：200元", "数量：3；单价：100元；含税金额：339元", "投标报价：100元（不含税）\n投标报价：113元（含税）"))
            assertEquals("UNDETERMINED",review("amountAccuracy","",text).status(),text);
        assertEquals("WARNING",review("amountAccuracy","","投标总报价：100元\n投标总报价：120元").status());
    }

    @Test void inlineAmountsRequireCompatibleQuantityAndPriceDenominators() {
        assertEquals("WARNING",review("amountAccuracy","","品目：服务器A；数量：3台；单价：100元/台；金额：250元").status());
        for(String text:cn.bidcheck.compat.Java8Api.listOf("数量：3台；单价：100元/百台；金额：3元", "数量：3台；单价：100元／百台；金额：3元", "数量：3台；单价：100元/套；金额：250元", "数量：3；单价：100元/台；金额：250元", "数量：3台；单价：100元/月；金额：250元", "数量：3台；单价：100元/台/月；金额：250元", "数量：3台；单价：100元；金额：250元/台", "数量：3打；单价：100元；金额：250元", "数量：3台；单价：100元每套；金额：250元"))
            assertEquals("UNDETERMINED",review("amountAccuracy","",text).status(),text);
    }

    @Test void inlineAmountsRespectCurrencyAndDoNotMixDifferentObjectsOrDuplicateLabels() {
        assertEquals("WARNING",review("amountAccuracy","","数量：3台；单价：100美元/台；金额：250美元").status());
        for(String text:cn.bidcheck.compat.Java8Api.listOf("数量：3台；单价：100美元；金额：250元", "数量：3台；单价：100元；金额：250欧元", "数量：3台；单价：USD100元；金额：250元", "币种：美元；数量：3台；单价：100元；金额：250元", "品目：服务器A；数量：3台；单价：100元；品目：服务器B；金额：250元", "数量：3台；数量：待填；单价：100元；金额：250元", "数量：3台；单价：100元；单价：待确认；金额：250元", "数量：3台；单价：100元；金额：250元；小计：待确认"))
            assertEquals("UNDETERMINED",review("amountAccuracy","",text).status(),text);
    }

    @Test void inlineAmountRoundingUsesDisplayedPrecisionAndOriginalCurrencyScale() {
        for(String text:cn.bidcheck.compat.Java8Api.listOf("数量：3；单价：0.333元；小计：1.00元", "数量：3；单价：99.9元；小计：0.03万元", "数量：3；单价：999999.9元；小计：0.03亿元", "数量：3；单价：0.333美元；小计：1.00美元"))
            assertEquals("UNDETERMINED",review("amountAccuracy","",text).status(),text);
        assertEquals("WARNING",review("amountAccuracy","","数量：3；单价：0.333元；小计：1.02元").status());
    }

    @Test void expandedArithmeticGroupsFeedTheAmountCardWithTheirSourceEvidence() {
        String header="品目|数量(台)|单价(元)|行金额(元)";
        String row="服务器A|3|100|250";
        cn.bidcheck.domain.Domain.Evaluation result=review("amountAccuracy","","本项目\n"+header+"\n"+row);
        assertEquals("WARNING",result.status());
        assertTrue(result.evidences().stream().anyMatch(e->e.quote().equals(row)));
        assertTrue(result.evidences().stream().anyMatch(e->e.quote().equals(header)));
    }

    @Test void expandedEvidenceGapsAndCompatibleRoundingAreNotAmountAnomalies() {
        for(String text:cn.bidcheck.compat.Java8Api.listOf("本项目\n数量：3台\n单价：100元\n行金额：250元", "本项目\n品目|数量(台)|单价(元)|行金额(万元)\n服务器A|3|99.9|0.03", "本项目\n品目|数量(台)|单价(元/百台)|行金额(元)\n服务器A|3|100|3", "本项目\n品目|数量(台)|单价(美元)|行金额(元)\n服务器A|3|100|250"))
            assertEquals("UNDETERMINED",review("amountAccuracy","",text).status(),text);
    }

    @Test void placeholderDetectionRetainsPhysicalLineAndDoesNotFlagFollowingValue() {
        cn.bidcheck.domain.Domain.Evaluation result=review("missingFields","","正文\n\n投标人名称：____");
        assertEquals("WARNING",result.status());
        assertEquals("p1-l3",result.evidences().get(0).anchor());
        assertEquals("UNDETERMINED",review("missingFields","","投标人名称：\n北京某科技公司").status());
        assertEquals("WARNING",review("missingFields","","开户银行：\n银行账号：123456").status());
    }

    @Test void signingDateDetectsInvalidDatesAndAfterOpeningButCannotInferSameDayTime() {
        assertEquals("WARNING",review("signatureDate","","签署日期：2026年2月30日").status());
        assertEquals("WARNING",review("signingAfterOpening","2026-10-20T09:30:00","落款日期：2026年10月21日").status());
        assertEquals("UNDETERMINED",review("signingAfterOpening","2026-10-20T09:30:00","落款日期：2026年10月20日").status());
        assertEquals("UNDETERMINED",review("signingAfterOpening","","落款日期：2026年10月21日").status());
        assertEquals("UNDETERMINED",review("signingAfterOpening","2026-10-20","成立日期：2027年10月21日").status());
    }

    @Test void typoCandidatesAreWarningsAndQuoteTheOriginalText() {
        cn.bidcheck.domain.Domain.Evaluation result=review("typos","","附件：营叶执照");
        assertEquals("WARNING",result.status());
        assertTrue(result.reason().contains("营业执照"));
        assertEquals("附件：营叶执照",result.evidences().get(0).quote());
    }

    @Test void certificateCoverageDetectsExpiredNotYetEffectiveAndInvalidDates() {
        assertEquals("FAIL",review("certificateCoverage","2026-10-20","资质证书有效至：2026年10月19日").status());
        assertEquals("FAIL",review("certificateCoverage","2026-10-20","资质证书有效期：2026年10月21日至2027年10月20日").status());
        assertEquals("UNDETERMINED",review("certificateCoverage","2026-10-20","资质证书有效期：2026年2月30日至2027年10月20日").status());
        assertEquals("UNDETERMINED",review("certificateCoverage","2026-10-20","资质证书有效至：2027年10月20日\n营业执照").status());
        assertEquals("UNDETERMINED",review("certificateCoverage","","资质证书有效至：2020年10月20日").status());
    }
}

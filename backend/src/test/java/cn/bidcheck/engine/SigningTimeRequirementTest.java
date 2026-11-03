package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SigningTimeRequirementTest {
    private Evaluation evaluate(String field,String opening,String text) {
        Document document=new Document("bid","project","BID","投标文件.pdf",1,"hash","",
                cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        Checkpoint rule=new Checkpoint("signing","落款时间检查","全文信息检查","HIGH","FULL_TEXT_REVIEW",
                field,"REVIEW",opening,"",true,true,true,null);
        return new CheckEngine().evaluate(rule,document,cn.bidcheck.compat.Java8Api.listOf());
    }

    @Test void detectsSameDayLateSigningInChineseAndIsoFormatsWithOriginalEvidence() {
        for(String value:cn.bidcheck.compat.Java8Api.listOf("2026年10月20日10时15分", "2026-10-20T10:15:00", "2026/10/20 10：15")) {
            String original="落款时间："+value;
            Evaluation result=evaluate("signingAfterOpening","2026-10-20T09:30",original);
            assertEquals("WARNING",result.status(),value);
            assertEquals(original,result.evidences().get(0).quote());
            assertEquals("p1-l1",result.evidences().get(0).anchor());
        }
    }

    @Test void comparesSecondsOnlyWhenBothSourcesStateSeconds() {
        assertEquals("WARNING",evaluate("signingAfterOpening","2026-10-20T09:30:00","签署时间：2026-10-20 09:30:01").status());
        assertEquals("UNDETERMINED",evaluate("signingAfterOpening","2026-10-20T09:30","签署时间：2026-10-20 09:30:01").status());
        assertEquals("UNDETERMINED",evaluate("signingAfterOpening","2026-10-20T09:30:01","签署时间：2026-10-20 09:30").status());
    }

    @Test void missingTimesOrEarlierSigningDoNotImplyLateSigningOrGlobalPass() {
        for(String original:cn.bidcheck.compat.Java8Api.listOf("落款日期：2026年10月20日", "签署时间：2026-10-20 09:29", "签章时间：2026-10-20 09:30", "成立日期：2026-10-21 10:00"))
            assertEquals("UNDETERMINED",evaluate("signingAfterOpening","2026-10-20T09:30",original).status(),original);
        assertEquals("UNDETERMINED",evaluate("signingAfterOpening","2026-10-20","落款时间：2026-10-20 23:59").status());
        assertEquals("WARNING",evaluate("signingAfterOpening","2026-10-20","落款日期：2026-10-21").status());
    }

    @Test void invalidExplicitTimesAreReportedInsteadOfDiscarded() {
        for(String time:cn.bidcheck.compat.Java8Api.listOf("25:00", "09:61", "09:30:99", "09时90分")) {
            Evaluation result=evaluate("signatureDate","","签署时间：2026-10-20 "+time);
            assertEquals("WARNING",result.status(),time);
            assertTrue(result.reason().contains("无效"));
        }
        assertEquals("UNDETERMINED",evaluate("signingAfterOpening","2026-10-20T25:00","落款时间：2026-10-20 10:00").status());
    }
}

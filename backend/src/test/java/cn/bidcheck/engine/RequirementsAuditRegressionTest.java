package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Examples derived from the requirement document's substantive composition and validity checks. */
class RequirementsAuditRegressionTest {
    private final CheckEngine engine=new CheckEngine();
    private Document document(String text) {
        return new Document("bid","project","BID","投标文件.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
    }
    private Checkpoint rule(String type,String field,String expected) {
        return new Checkpoint("check","需求审计","DATE_VALIDITY".equals(type)?"效期检查":"形式检查","HIGH",type,field,
                "DATE_VALIDITY".equals(type)?">=":"EXISTS",expected,"",true,true,true,
                new Evidence("tender","招标文件.pdf",3,"PAGE","招标原文要求","p3-l1",1));
    }
    private Evaluation evaluate(Checkpoint cp,Document doc) {
        // These legacy date-arithmetic fixtures concern explicitly current bid credentials.
        // Unknown and historical subjects are exercised separately in FactScopePolicyTest.
        if("DATE_VALIDITY".equals(cp.type())) doc=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),doc.createdAt(),
                cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().map(p->new Page(p.number(),"本项目证件\n"+p.text(),p.locationType(),p.parser(),p.warning(),p.layout()))),doc.warnings());
        return engine.evaluate(cp,doc,new FactExtractor().extract(doc));
    }

    @Test void futureStartCannotPassMerelyBecauseTheCertificateExpiresAfterDeadline() {
        String text="资质证书\n有效期：2026年10月21日至2027年10月20日";
        Evaluation result=evaluate(rule("DATE_VALIDITY","certificateExpiry","2026-10-20"),document(text));
        assertEquals("FAIL",result.status());
        assertTrue(result.reason().contains("尚未生效"));
        assertEquals("有效期：2026年10月21日至2027年10月20日",result.evidences().get(0).quote());
        assertEquals("FAIL",evaluate(rule("DATE_VALIDITY","certificateExpiry","2026-10-20"),document("有效期：2026年10月21日至长期")).status());
    }

    @Test void validExplicitStartAndEndAreComparedWithinTheSameCertificate() {
        Checkpoint rule=rule("DATE_VALIDITY","certificateExpiry","2026-10-20");
        for(String text:cn.bidcheck.compat.Java8Api.listOf("有效期：2026年10月20日至2027年10月20日", "有效期：2025-10-20 — 2026-10-20", "有效期限：2025-10-20 ~ 2026-10-20"))
            assertEquals("PASS",evaluate(rule,document(text)).status(),text);
        assertEquals("FAIL",evaluate(rule,document("证书甲有效期：2025年10月20日至2026年10月20日\n证书乙有效期：2026年10月21日至2027年10月20日")).status());
    }

    @Test void invalidOrInvertedStartCannotBeDiscardedForTheValidEndDate() {
        Checkpoint rule=rule("DATE_VALIDITY","certificateExpiry","2026-10-20");
        for(String text:cn.bidcheck.compat.Java8Api.listOf("有效期：2026年2月30日至2027年10月20日", "有效期：2028年10月20日至2027年10月20日"))
            assertEquals("UNDETERMINED",evaluate(rule,document(text)).status(),text);
    }

    @Test void expiryOnlyDoesNotBorrowAnUnrelatedIssueOrOtherCertificateStartDate() {
        Checkpoint rule=rule("DATE_VALIDITY","certificateExpiry","2026-10-20");
        assertEquals("PASS",evaluate(rule,document("有效期至：2027年10月20日；签发日期：2026年10月21日")).status());
        assertEquals("PASS",evaluate(rule,document("有效至：2027年10月20日")).status());
    }

    @Test void alternateAuthorizationHeadingWithBodyIsAReviewCandidateNotAnAbsentChapter() {
        String body="委托人：甲公司。被授权人：张三。本公司授权张三代表我方办理本项目投标、澄清及签署相关文件，授权期限自签署日起至投标有效期结束。";
        String text="正文\n\n二、法定代表人授权书\n"+body;
        Evaluation result=evaluate(rule("MATERIAL_EXISTENCE","material","授权委托书"),document(text));
        assertEquals("WARNING",result.status());
        assertTrue(result.reason().contains("标题不同"));
        assertEquals("p1-l3",result.evidences().get(0).anchor());
        assertTrue(result.evidences().get(0).quote().contains(body));
        assertTrue(text.contains(result.evidences().get(0).quote()));
    }

    @Test void alternateHeadingNeedsSubstantiveBodyAndCannotBorrowTheNextChapter() {
        Checkpoint rule=rule("MATERIAL_EXISTENCE","material","授权委托书");
        String other="委托人：甲公司。被授权人：张三。本公司授权张三代表我方办理本项目投标、澄清及签署相关文件，授权期限自签署日起至投标有效期结束。";
        for(String text:cn.bidcheck.compat.Java8Api.listOf("法定代表人授权书", "目录\n二、法定代表人授权书\n"+other, "二、法定代表人授权书\n我方承诺提供法定代表人授权书，待中标后再提供盖章原件和所有相关证明。", "二、法定代表人授权书\n三、其他资料\n"+other))
            assertEquals("UNDETERMINED",evaluate(rule,document(text)).status(),text);
    }

    @Test void alternateWordHeadingCitesBothTheHeadingAndItsActualBody() {
        String heading="二、法定代表人授权书";
        String body="委托人：甲公司。被授权人：张三。本公司授权张三代表我方办理本项目投标、澄清及签署相关文件，授权期限自签署日起至投标有效期结束。";
        Document doc=new Document("bid","project","BID","投标文件.docx",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(3,heading,"PARAGRAPH","POI",null), new Page(4,body,"PARAGRAPH","POI",null)),cn.bidcheck.compat.Java8Api.listOf());
        Evaluation result=evaluate(rule("MATERIAL_EXISTENCE","material","授权委托书"),doc);
        assertEquals("WARNING",result.status());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(heading, body),cn.bidcheck.compat.Java8Api.toList(result.evidences().stream().map(Evidence::quote)));
    }
}

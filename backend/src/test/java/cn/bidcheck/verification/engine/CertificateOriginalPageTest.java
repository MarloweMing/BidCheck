package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.compat.Java8Api.listOf;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class CertificateOriginalPageTest {
    private static final String HOLDER = "北方建设有限公司";
    private static final String DESIGN = "建筑装饰工程设计专项";
    private static final String BUILD = "建筑装修装饰工程专业承包";

    private static String page(String category, String number, String printedEnd) {
        return "资质证书\n企业名称：" + HOLDER + "\n资质等级：" + category + "\n证书编号：" + number
                + "\n有效期：至" + printedEnd + "\n发证日期：2020年06月16日";
    }

    private static EvidenceCorpus corpus(String... text) {
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < text.length; i++) {
            pages.add(new Page(i + 1, text[i], "PAGE", "TEXT", ""));
        }
        return new EvidenceCorpus(listOf(new Document("bid", "project", "BID", "证照附件", 1, "sha", "", pages, listOf())));
    }

    private static Fact fact(EvidenceCorpus corpus, int page, String scope, String category, String number, String end) {
        Source original = corpus.all.stream().filter(s -> s.evidence().page() == page).findFirst().get();
        return new Fact("cert-" + page, "CERTIFICATE", HOLDER, "证书", number, number, "", "", scope,
                listOf(original.id()), linked("category", category, "number", number, "numberField", "证书编号",
                "validityField", "有效期", "end", end));
    }

    private static Decision evaluate(EvidenceCorpus corpus, List<Fact> facts, boolean all, boolean complete) {
        Map<String,Object> context = linked("referenceDate", "2025-08-05", "referenceDateSourceVerified", true,
                "referenceDateEvidence", linked("documentId", "tender", "quote", "投标截止日期为2025年8月5日",
                "confirmation", "USER_SELECTED_VERSION"), "certificateSetComplete", complete);
        Rule rule = new Rule("validity", "资质证书有效期", "VALIDITY", HOLDER, "有效期", "", "", "",
                linked("allApplicableCertificates", all, "category", DESIGN, "validityField", "有效期"),
                "证书在投标截止日期有效", "", listOf());
        return new RuleEvaluator(facts, corpus, context).evaluate(rule);
    }

    @Test void originalExpiryCanDisproveValidityButHistoricalExpiryCannotDenyCurrentCertificate() {
        EvidenceCorpus corpus = corpus(page(DESIGN, "A12345678", "2024年06月04日"), page(DESIGN, "A12345678", "2027年06月04日"));
        Fact expired = fact(corpus, 1, "CURRENT_PROJECT", DESIGN, "A12345678", "2024-06-04");
        Fact renewed = fact(corpus, 2, "CURRENT_PROJECT", DESIGN, "A12345678", "2027-06-04");
        Fact historical = fact(corpus, 1, "HISTORICAL", DESIGN, "A12345678", "2024-06-04");
        assertEquals("FAIL", evaluate(corpus, listOf(expired), true, true).status());
        Decision conflict = evaluate(corpus, listOf(expired, renewed), false, true);
        assertEquals("CERTIFICATE_VERSION_REQUIRED", conflict.gaps().get(0).code());
        Decision current = evaluate(corpus, listOf(historical, renewed), true, true);
        assertEquals("PASS", current.status());
        assertEquals(1, current.evidences().size());
        assertEquals(2, current.evidences().get(0).page());
        Decision partial = evaluate(corpus, listOf(renewed), true, false);
        assertEquals("UNDETERMINED", partial.status());
        assertEquals("CERTIFICATE_SET_COVERAGE_REQUIRED", partial.gaps().get(0).code());
    }

    @Test void anotherCategoryAndUnquotedEndDateCannotBecomeAdverseEvidence() {
        EvidenceCorpus corpus = corpus(page(BUILD, "B12345678", "2024年06月04日"), page(DESIGN, "A12345678", "2027年06月04日"));
        Fact other = fact(corpus, 1, "CURRENT_PROJECT", BUILD, "B12345678", "2024-06-04");
        Fact matching = fact(corpus, 2, "CURRENT_PROJECT", DESIGN, "A12345678", "2027-06-04");
        Decision selected = evaluate(corpus, listOf(other, matching), false, true);
        assertEquals("PASS", selected.status());
        assertEquals(1, selected.evidences().size());
        assertEquals(2, selected.evidences().get(0).page());
        Fact wrongDate = fact(corpus, 2, "CURRENT_PROJECT", DESIGN, "A12345678", "2024-06-04");
        Decision rejected = evaluate(corpus, listOf(wrongDate), false, true);
        assertEquals("UNDETERMINED", rejected.status());
        assertEquals("CERTIFICATE_DATE_SOURCE_UNPROVEN", rejected.gaps().get(0).code());
    }
}

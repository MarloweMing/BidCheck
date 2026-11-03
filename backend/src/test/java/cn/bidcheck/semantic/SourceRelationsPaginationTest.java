package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceRelationsPaginationTest {
    private static final String TITLE = "合成地区社会保险个人权益记录(单位职工缴费信息)";

    @Test void extractedPagesKeepTheHeaderAsUnverifiedSourceAndExposeMissingPrintedPages() {
        Document document = document(cn.bidcheck.compat.Java8Api.listOf(page(7, 1, 9, "单位名称：合成乙方设计有限公司\n查询流水号：SYNTHETIC-001"), page(8, 6, 9, "姓名：合成人员甲\n养老保险 2025年01月至2025年06月"), page(9, 9, 9, "备注：供本人核验。")));
        List<SourceRelations.Link> links = SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(evidence(document, 8)));
        assertEquals(1, links.size());
        cn.bidcheck.semantic.SourceRelations.Link link = links.get(0);
        assertEquals("PAGINATED_SOCIAL_INSURANCE_HEADER", link.type());
        assertEquals(7, link.evidence().page());
        assertTrue(document.pages().get(0).text().contains(link.evidence().quote()));
        assertTrue(link.evidence().quote().contains("单位名称：合成乙方设计有限公司"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 6, 9), link.attributes().get("printedPages"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(8), link.attributes().get("memberLocations"));
        assertEquals(true, link.attributes().get("omittedPrintedPages"));
        assertEquals(false, link.attributes().get("membershipVerified"));
        assertTrue(link.attributes().get("boundary").toString().contains("同份证明归属未自动确证"));
    }

    @Test void sequentialPagesHaveNoMissingPageFlagButStillDoNotConfirmMembership() {
        Document document = document(cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司"), page(2, 2, 3, "姓名：合成人员甲"), page(3, 3, 3, "备注")));
        java.util.Map<java.lang.String, java.lang.Object> metadata = SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(evidence(document, 2))).get(0).attributes();
        assertEquals(false, metadata.get("omittedPrintedPages")); assertEquals(false, metadata.get("membershipVerified"));
    }

    @Test void repeatedPrintedPageOneStartsANewGroupAndCannotBorrowThePreviousCompany() {
        Document document = document(cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司"), page(2, 2, 3, "姓名：前组人员"), page(3, 1, 3, "单位名称：合成乙有限公司"), page(4, 2, 3, "姓名：目标人员"), page(5, 3, 3, "备注")));
        java.util.List<cn.bidcheck.semantic.SourceRelations.Link> links = SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(evidence(document, 2), evidence(document, 4)));
        assertEquals(1, links.size()); assertEquals(3, links.get(0).evidence().page());
        assertTrue(links.get(0).evidence().quote().contains("合成乙有限公司"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(4), links.get(0).attributes().get("memberLocations"));
    }

    @Test void conflictingUnitsOrQueryNumbersCannotBeGrouped() {
        for (String conflict : cn.bidcheck.compat.Java8Api.listOf("单位名称：其他公司有限公司", "查询流水号：SYNTHETIC-002")) {
            Document document = document(cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司\n查询流水号：SYNTHETIC-001"), page(2, 2, 3, "姓名：合成人员甲\n" + conflict), page(3, 3, 3, "备注")));
            assertTrue(SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(evidence(document, 2))).isEmpty());
        }
    }

    @Test void differentTitleTotalOrPhysicalGapAndMissingLastPageAreRejected() {
        List<List<Page>> examples = cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司"), new Page(2, "其他地区社会保险个人权益记录\n姓名：人员\n第2页共3页", "PAGE", "PDFBOX_NATIVE", ""), page(3, 3, 3, "备注")), cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司"), page(2, 2, 4, "姓名：人员"), page(3, 3, 3, "备注")), cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司"), page(3, 2, 3, "姓名：人员"), page(4, 3, 3, "备注")), cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 3, "单位名称：合成甲有限公司"), page(2, 2, 3, "姓名：人员")));
        for (List<Page> pages : examples) {
            Document document = document(pages);
            assertTrue(SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(evidence(document, pages.get(1).number()))).isEmpty());
        }
    }

    @Test void unrelatedSelectedEvidenceCannotReceiveAHeaderFromAnotherGroupOrDocument() {
        Document document = document(cn.bidcheck.compat.Java8Api.listOf(page(1, 1, 2, "单位名称：合成甲有限公司"), page(2, 2, 2, "姓名：人员"), new Page(3, "一般技术说明", "PAGE", "PDFBOX_NATIVE", "")));
        assertTrue(SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(evidence(document, 3))).isEmpty());
        assertTrue(SourceRelations.supportingHeaders(document, cn.bidcheck.compat.Java8Api.listOf(new Evidence("another-doc", "另一文件", 2, "PAGE", "姓名：人员", "", 1))).isEmpty());
    }

    private static Page page(int physical, int printed, int total, String body) {
        return new Page(physical, TITLE + "\n" + body + "\n第" + printed + "页（共" + total + "页）", "PAGE", "PDFBOX_NATIVE", "");
    }
    private static Document document(List<Page> pages) { return new Document("synthetic-doc", "synthetic-project", "BID", "合成投标文件", 1, "hash", "", pages, cn.bidcheck.compat.Java8Api.listOf()); }
    private static Evidence evidence(Document document, int page) {
        Page found = document.pages().stream().filter(p -> p.number() == page).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        return new Evidence(document.id(), document.name(), page, found.locationType(), found.text(), "p" + page, 1);
    }
}

package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyEngineTest {
    private final ConsistencyEngine engine = new ConsistencyEngine();
    private Document doc(String... text) {
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < text.length; i++) pages.add(new Page(i + 1, text[i], "PAGE", "PDFBOX", null));
        return new Document("bid", "p", "BID", "投标.pdf", 1, "hash", "", pages, List.of());
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> report, String key) { return (List<Map<String,Object>>) report.get(key); }
    @SuppressWarnings("unchecked") private Map<String,Object> coverage(Map<String,Object> report) { return (Map<String,Object>) report.get("coverage"); }
    private List<Map<String,Object>> groups(String... text) { return rows(engine.scan(doc(text), List.of()), "groups"); }

    @Test void expandedSourceLimitRemainsVisibleInCombinedCoverage() {
        var report=engine.scan(doc("品目：服务器\n产品型号：X100\n"+"长".repeat(10001)+"\n"+"行".repeat(10001)),List.of());
        assertEquals("PARTIAL",coverage(report).get("status"));
        assertEquals(1,coverage(report).get("pagesUnparsed"));
        assertTrue(rows(report,"occurrences").stream().anyMatch(f->"productModel".equals(f.get("field"))));
        assertEquals(18,((List<?>)coverage(report).get("categories")).size());
    }
    @Test void comparesChineseWholeHourOpeningTimesWithoutDroppingTheTime() {
        assertTrue(groups("开标时间：2026年11月5日上午9时整", "开标时间：2026-11-05 09:00").isEmpty());
        assertEquals(1, groups("开标时间：2026年11月5日上午9时", "开标时间：2026年11月5日上午10时").size());
        assertEquals(1, groups("开标时间：2026年11月5日下午2时", "开标时间：2026-11-05 14:30").size());
    }
    private Map<String,Object> supplemental(String field, String subject, String raw, Evidence source, String context) {
        Map<String,Object> fact = new LinkedHashMap<>();
        fact.put("field", field); fact.put("subject", subject); fact.put("rawValue", raw); fact.put("value", "FORGED_NORMALIZED");
        fact.put("scope", "CURRENT_PROJECT"); fact.put("evidence", source); fact.put("contextQuote", context);
        return fact;
    }

    @Test void reportsEveryConflictCategoryAndPreservesAllSourceOccurrences() {
        var report = engine.scan(doc("投标人名称：甲公司\n投标总报价：100万元\n开标时间：2026年10月20日09:30\n项目经理：张三",
                "投标人名称：乙公司\n投标总报价：120万元\n开标时间：2026年10月21日09:30\n项目经理：李四"), List.of());
        assertEquals(Set.of("companyName", "totalPrice", "openingTime", "personName"), new HashSet<>(rows(report, "groups").stream().map(g -> (String)g.get("field")).toList()));
        assertEquals(8, rows(report, "occurrences").size());
        assertTrue(rows(report, "groups").stream().allMatch(g -> ((List<?>)g.get("occurrenceIds")).size() == 2));
        assertEquals("REVIEW_REQUIRED", report.get("overall"));
    }

    @Test void detectsTwoDistinctValuesEvenWhenTheyAppearOnSameLine() {
        var groups = groups("投标人名称：甲公司；投标人名称：乙公司");
        assertEquals(1, groups.size());
        assertEquals("CONFLICT", groups.get(0).get("status"));
    }

    @Test void retainsRepeatedIdenticalOccurrencesOnSeparatePhysicalLines() {
        var report = engine.scan(doc("投标人名称：甲公司\n投标人名称：甲公司\n投标人名称：乙公司"), List.of());
        assertEquals(3, rows(report, "occurrences").size());
        assertEquals(3, ((List<?>)rows(report, "groups").get(0).get("occurrenceIds")).size());
    }

    @Test void noDifferencesNeverMeansWholeDocumentPass() {
        var report = engine.scan(doc("投标人名称：甲公司\n投标人名称：甲公司"), List.of());
        assertTrue(rows(report, "groups").isEmpty());
        assertEquals("REVIEW_REQUIRED", report.get("overall"));
        assertFalse(((List<?>)report.get("limitations")).isEmpty());
    }

    @Test void namesBelongingToThirdPartiesAndHistoricalContractsAreExcluded() {
        assertTrue(groups("投标人名称：甲公司\n甲方公司名称：乙公司\n招标人名称：采购公司\n历史项目业绩\n投标人名称：丙公司\n项目经理：王五\n投标函\n投标人名称：甲公司").isEmpty());
    }

    @Test void commonContractHeadingsDoNotAssignHistoricalSuppliersToCurrentBidder() {
        for (String heading : List.of("合同业绩", "业绩合同", "过往合同", "历史合同：", "历史业绩：", "类似项目合同")) {
            var report = engine.scan(doc("投标人名称：甲公司\n" + heading + "\n供应商名称：乙公司\n投标总价：80万元\n项目经理：王五\n投标函\n投标人名称：甲公司\n投标总价：100万元\n项目经理：张三"), List.of());
            assertTrue(rows(report, "groups").isEmpty(), heading);
            assertTrue(rows(report, "occurrences").stream().anyMatch(o -> "乙公司".equals(o.get("rawValue")) && "HISTORICAL_PROJECT".equals(o.get("scope"))), heading);
        }
    }

    @Test void ambiguousContractSubjectRemainsUnresolvedUntilCurrentProjectSectionResumes() {
        var report = engine.scan(doc("投标人名称：甲公司\n供货合同\n供应商名称：乙公司\n投标函\n投标人名称：丙公司"), List.of());
        assertEquals("UNRESOLVED", rows(report, "occurrences").stream().filter(o -> "乙公司".equals(o.get("rawValue"))).findFirst().orElseThrow().get("scope"));
        assertEquals(List.of("甲公司", "丙公司"), rows(report, "groups").get(0).get("values"));
    }

    @Test void samePersonRoleInSeparateLotsDoesNotCollide() {
        assertTrue(groups("第一章 本次投标函\n第一标段\n项目经理：张三\n第二标段\n项目经理：李四").isEmpty());
        assertEquals(1, groups("第一章 本次投标函\n第一标段\n项目经理：张三\n标段1\n项目经理：李四").size());
    }

    @Test void unlabelledCurrentSectionCannotInheritTheLastKnownLotAsCertain() {
        var report = engine.scan(doc("第一章 本次投标函\n第一标段\n投标总价：100万元\n第二标段\n投标总价：200万元\n投标函\n投标总价：300万元"), List.of());
        assertTrue(rows(report, "groups").isEmpty());
        assertEquals("UNRESOLVED", rows(report, "occurrences").stream().filter(o -> "300万元".equals(o.get("rawValue"))).findFirst().orElseThrow().get("lot"));
        var uncertain = groups("第一章 本次投标函\n第一标段\n投标总价：100万元\n投标函\n投标总价：110万元\n投标总价：120万元");
        assertEquals("SUSPECTED", uncertain.get(0).get("status"));
        assertEquals("CONFLICT", groups("第一章 本次投标函\n第一标段\n投标总价：100万元\n第一标段投标函\n投标总价：110万元").get(0).get("status"));
    }

    @Test void explicitAllLotsSummaryRestoresProjectWideAmountScope() {
        var report = engine.scan(doc("第二标段\n投标总价：200万元\n本项目汇总\n投标总价：300万元\n全部标段\n投标总价：310万元"), List.of());
        assertEquals(1, rows(report, "groups").size());
        assertEquals("", rows(report, "groups").get(0).get("lot"));
        assertEquals(List.of("3000000", "3100000"), rows(report, "groups").get(0).get("values"));
    }

    @Test void explicitlyDeclaredCompanyAliasIsNotAConflict() {
        assertTrue(groups("投标人名称：北京甲科技有限公司（以下简称甲公司）\n投标人名称：甲公司").isEmpty());
    }

    @Test void amountsNormalizeUnitsButKeepTaxAndPeriodBasesSeparate() {
        assertTrue(groups("投标总报价：100万元\n投标总价：1,000,000元").isEmpty());
        assertTrue(groups("含税投标总价：113万元\n不含税投标总价：100万元").isEmpty());
        assertTrue(groups("年度投标总价：100万元\n投标总价：300万元").isEmpty());
        assertEquals(1, groups("含税投标总价：113万元\n含税投标总价：114万元").size());
    }

    @Test void taxesOnSameLineRemainBoundToTheirOwnAmounts() {
        assertTrue(groups("含税投标总价：113万元；不含税投标总价：100万元").isEmpty());
    }

    @Test void absentAmountUnitCannotBeSilentlyAssumedYuan() {
        assertTrue(groups("投标总报价：100\n投标总报价：100万元").isEmpty());
    }

    @Test void openingDatePrecisionDoesNotInventTimeDisagreement() {
        assertTrue(groups("开标时间：2026年10月20日\n开标日期：2026-10-20 09:30").isEmpty());
        assertTrue(groups("开标时间：2026年10月20日09:30\n开标时间：2026-10-20 09:30:15").isEmpty());
        assertEquals(1, groups("开标时间：2026-10-20\n开标时间：2026-10-20 09:30\n开标时间：2026-10-20 10:00").size());
        assertEquals(1, groups("开标时间：2026-10-20 09:30:15\n开标时间：2026-10-20 09:30:16").size());
        assertEquals(1, groups("开标时间：2026-10-20 09:30\n开标时间：2026-10-20 09:30:15\n开标时间：2026-10-20 09:30:16").size());
    }

    @Test void openingAndSubmissionDeadlineAreDifferentEvents() {
        assertTrue(groups("开标时间：2026年10月20日09:30\n投标截止时间：2026年10月19日09:30").isEmpty());
    }

    @Test void invalidDatesRemainUncomparableInsteadOfBeingNormalized() {
        var report = engine.scan(doc("开标时间：2026年2月30日\n开标时间：2026年2月28日"), List.of());
        assertTrue(rows(report, "groups").isEmpty());
        assertTrue(rows(report, "occurrences").stream().anyMatch(o -> o.get("comparability").equals("INVALID")));
    }

    @Test void amendmentCluesRequireVersionReviewBeforeCallingAConflict() {
        var groups = groups("原开标时间：2026年10月20日09:30\n开标时间：2026年10月21日09:30");
        assertEquals(1, groups.size());
        assertEquals("SUSPECTED", groups.get(0).get("status"));
        assertTrue(groups.get(0).get("reason").toString().contains("有效版本"));
    }

    @Test void positionalNamesAndPersonalRolesAreSuspectedNotExclusiveAssumptions() {
        var report = engine.scan(doc("项目经理：张三\n项目经理：李四\n姓名：张三；职务：项目经理\n姓名：张三；职务：技术负责人"), List.of());
        assertTrue(rows(report, "groups").stream().allMatch(g -> "SUSPECTED".equals(g.get("status"))));
        assertTrue(rows(report, "groups").stream().anyMatch(g -> "personRole".equals(g.get("field"))));
    }

    @Test void professionalTitleIsNotEmploymentRole() {
        assertTrue(groups("姓名：张三；职务：项目经理\n姓名：张三；职称：高级工程师").isEmpty());
    }

    @Test void horizontalTablesBindHeadersAndValuesAndRetainHeaderEvidence() {
        var report = engine.scan(doc("姓名\t职务\n张三\t项目经理\n李四\t技术负责人", "项目经理：王五"), List.of());
        var group = rows(report, "groups").stream().filter(g -> "personName".equals(g.get("field"))).findFirst().orElseThrow();
        assertTrue(((List<?>)group.get("evidences")).stream().map(e -> ((Evidence)e).quote()).anyMatch(q -> q.contains("姓名\t职务")));
        for (Map<String,Object> occurrence : rows(report, "occurrences")) assertTrue(((Evidence)occurrence.get("evidence")).quote().contains((String)occurrence.get("rawValue")));
    }

    @Test void tableColumnsRetainTheirOwnTaxBasesAndHeaderUnits() {
        assertTrue(groups("总价（含税万元）\t总价（不含税万元）\n113\t100").isEmpty());
        var report = engine.scan(doc("序号\t投标总价（万元）\n1\t100", "投标总价：110万元"), List.of());
        assertEquals(1, rows(report, "groups").size());
    }

    @Test void equalAmountsWithDifferentTaxBasesRemainSeparateOccurrencesAndIds() {
        var report = engine.scan(doc("总价（含税万元）\t总价（不含税万元）\n100\t100"), List.of());
        var occurrences = rows(report, "occurrences");
        assertEquals(2, occurrences.size());
        assertEquals(2, occurrences.stream().map(o -> o.get("id")).distinct().count());
        assertEquals(Set.of("INCLUSIVE|TOTAL", "EXCLUSIVE|TOTAL"), new HashSet<>(occurrences.stream().map(o -> o.get("basis")).toList()));
        assertTrue(rows(report, "groups").isEmpty());
        assertEquals(occurrences.stream().map(o -> o.get("id")).toList(), rows(engine.scan(doc("总价（含税万元）\t总价（不含税万元）\n100\t100"), List.of()), "occurrences").stream().map(o -> o.get("id")).toList());
    }

    @Test void tableRowsKeepSeparateLotOwnership() {
        assertTrue(groups("标段\t姓名\t职务\n第一标段\t张三\t项目经理\n第二标段\t李四\t项目经理").isEmpty());
    }

    @Test void twoColumnLotAmountTableBindsUnitsAndComparesOnlyItsOwnLot() {
        for (String heading : List.of("标段", "标包", "包件", "包号")) {
            var report = engine.scan(doc(heading + "\t投标总价（万元）\n1\t100\n1\t110\n2\t200"), List.of());
            assertEquals(3, rows(report, "occurrences").size(), heading);
            assertEquals(1, rows(report, "groups").size(), heading);
            assertEquals(List.of("1000000", "1100000"), rows(report, "groups").get(0).get("values"), heading);
            assertTrue(((List<?>) rows(report, "groups").get(0).get("evidences")).stream().map(Evidence.class::cast).anyMatch(e -> e.quote().contains("投标总价（万元）")));
        }
    }

    @Test void verticalLabelValueKeepsExactContiguousOriginalQuote() {
        var report = engine.scan(doc("投标人名称：\r\n甲公司\r\n投标人名称：\r\n乙公司"), List.of());
        assertEquals(1, rows(report, "groups").size());
        assertTrue(rows(report, "occurrences").stream().allMatch(o -> ((Evidence)o.get("evidence")).quote().contains("\r\n")));
    }

    @Test void ocrDifferencesRequireImageReviewAndMissingPagesRemainVisible() {
        Document document = new Document("bid", "p", "BID", "投标.pdf", 1, "h", "", List.of(
                new Page(1, "投标人名称：甲公司", "PAGE", "PDFBOX", null),
                new Page(2, "投标人名称：乙公司", "PAGE", "OCR", "[OCR已识别]"),
                new Page(3, "", "PAGE", "PDFBOX", "[OCR未完成]")), List.of());
        var report = engine.scan(document, List.of());
        assertEquals("SUSPECTED", rows(report, "groups").get(0).get("status"));
        assertEquals(3, coverage(report).get("pagesTotal"));
        assertEquals(2, coverage(report).get("pagesScanned"));
        assertEquals(1, coverage(report).get("pagesUnparsed"));
        assertEquals("PARTIAL", coverage(report).get("status"));
    }

    @Test void modelSourceMustMatchRealDocumentPageAndOriginalValue() {
        var document = doc("本次投标企业为乙公司。\n投标人名称：甲公司");
        var badSource = new Evidence("wrong", "投标.pdf", 1, "PAGE", "本次投标企业为乙公司。", "p1-l1", 0.9);
        var badRaw = new Evidence("bid", "投标.pdf", 1, "PAGE", "本次投标企业为乙公司。", "p1-l1", 0.9);
        var report = engine.scan(document, List.of(supplemental("companyName", "投标人", "乙公司", badSource, ""), supplemental("companyName", "投标人", "丙公司", badRaw, "")));
        assertEquals(2, coverage(report).get("modelFactsRejected"));
        assertTrue(rows(report, "groups").isEmpty());
    }

    @Test void modelSupplementCanFindNaturalLanguageDifferenceWithoutClaimingCertainty() {
        var document = doc("本次投标企业为乙公司。\n投标人名称：甲公司");
        var evidence = new Evidence("bid", "投标.pdf", 1, "PAGE", "乙公司", "p1-l1-c8", 0.9);
        var report = engine.scan(document, List.of(supplemental("companyName", "投标人", "乙公司", evidence, "本次投标企业为乙公司。")));
        assertEquals(1, rows(report, "groups").size());
        assertEquals("SUSPECTED", rows(report, "groups").get(0).get("status"));
        var model = rows(report, "occurrences").stream().filter(o -> o.get("extractionMethod").equals("MODEL_SOURCE_VERIFIED")).findFirst().orElseThrow();
        assertEquals("乙公司", model.get("value"));
        assertEquals("p1-l1-c8", ((Evidence)model.get("evidence")).anchor());
    }

    @Test void modelContextCannotBeInventedAndUnresolvedFactsAreNotCompared() {
        var document = doc("甲公司\n乙公司");
        var a = new Evidence("bid", "投标.pdf", 1, "PAGE", "甲公司", "p1-l1", 0.9);
        var b = new Evidence("bid", "投标.pdf", 1, "PAGE", "乙公司", "p1-l2", 0.9);
        var report = engine.scan(document, List.of(supplemental("companyName", "投标人", "甲公司", a, "本次投标人"), supplemental("companyName", "投标人", "乙公司", b, "")));
        assertEquals(1, coverage(report).get("modelFactsRejected"));
        assertTrue(rows(report, "groups").isEmpty());
    }

    @Test void modelCannotBorrowCurrentBidderContextFromAnotherSamePageValue() {
        var document = doc("本次投标人：甲公司。\n历史合同供货方：乙公司。");
        var evidence = new Evidence("bid", "投标.pdf", 1, "PAGE", "乙公司", "p1-l2", 0.9);
        var report = engine.scan(document, List.of(supplemental("companyName", "投标人", "乙公司", evidence, "本次投标人：甲公司。")));
        assertTrue(rows(report, "groups").isEmpty());
        var model = rows(report, "occurrences").stream().filter(o -> "MODEL_SOURCE_VERIFIED".equals(o.get("extractionMethod"))).findFirst().orElseThrow();
        assertEquals("UNRESOLVED", model.get("scope"));
        assertFalse(model.containsKey("contextEvidences"));
    }

    @Test void identicalValueCannotBorrowContextOfAnotherPhysicalOccurrence() {
        var document = doc("本次投标人：甲公司。\n历史合同供货方：甲公司。");
        var evidence = new Evidence("bid", "投标.pdf", 1, "PAGE", "甲公司", "p1-l2", 0.9);
        var candidate = supplemental("companyName", "投标人", "甲公司", evidence, "本次投标人：甲公司。");
        candidate.put("sourceOffset", document.pages().get(0).text().lastIndexOf("甲公司"));
        var report = engine.scan(document, List.of(candidate));
        var model = rows(report, "occurrences").stream().filter(o -> "MODEL_SOURCE_VERIFIED".equals(o.get("extractionMethod"))).findFirst().orElseThrow();
        assertEquals("UNRESOLVED", model.get("scope"));
        assertFalse(model.containsKey("contextEvidences"));
    }

    @Test void repeatedModelQuotesUseValidatedOffsetsInsteadOfFirstOccurrence() {
        Document document = doc("本次投标人为甲公司。\n本次投标人为甲公司。");
        Evidence first = new Evidence("bid", "投标.pdf", 1, "PAGE", "甲公司", "p1-l1-c7", 0.7);
        Evidence second = new Evidence("bid", "投标.pdf", 1, "PAGE", "甲公司", "p1-l2-c19", 0.7);
        var a = supplemental("companyName", "投标人", "甲公司", first, "本次投标人为甲公司。");
        var b = supplemental("companyName", "投标人", "甲公司", second, "本次投标人为甲公司。");
        int firstOffset = document.pages().get(0).text().indexOf("甲公司");
        int secondOffset = document.pages().get(0).text().lastIndexOf("甲公司");
        a.put("sourceOffset", firstOffset); b.put("sourceOffset", secondOffset);
        var report = engine.scan(document, List.of(a, b));
        assertEquals(2, rows(report, "occurrences").size());
        assertEquals(2, rows(report, "occurrences").stream().map(o -> o.get("id")).distinct().count());
        assertTrue(rows(report, "occurrences").stream().allMatch(o -> o.get("contextEvidences") instanceof List<?>));
    }

    @Test void anOversizedParagraphKeepsBoundedExactEvidence() {
        String text = "正文".repeat(6000) + "；开标时间：2026年10月20日；正文".repeat(2);
        Document document = doc(text, "开标时间：2026年10月21日");
        var report = engine.scan(document, List.of());
        assertEquals(1, rows(report, "groups").size());
        for (var occurrence : rows(report, "occurrences")) {
            Evidence source = (Evidence)occurrence.get("evidence");
            assertTrue(source.quote().length() <= 10000);
            assertTrue(document.pages().get(source.page() - 1).text().contains(source.quote()));
        }
    }

    @Test void emptyDocumentReportsIncompleteCoverage() {
        var report = engine.scan(doc(), List.of());
        assertEquals("PARTIAL", coverage(report).get("status"));
        assertEquals("REVIEW_REQUIRED", report.get("overall"));
    }
}

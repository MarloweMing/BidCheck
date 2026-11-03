package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.engine.CheckEngine;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredHtmlTableFactsTest {
    private static final String DOUBLE_HEADER = "<tr><th rowspan=\"2\">序号</th><th rowspan=\"2\">姓名</th><th rowspan=\"2\">社会保障号码</th>\n<th rowspan=\"2\">险种</th><th colspan=\"2\">缴费情况</th><th rowspan=\"2\">本单位实际缴费月数</th></tr>\n<tr><th>起始年月</th><th>截止年月</th></tr>\n";
    private static final String SINGLE_HEADER = "<tr><th>序号</th><th>姓名</th><th>社会保障号码</th><th>险种</th>"
            + "<th>起始年月</th><th>截止年月</th><th>本单位实际缴费月数</th></tr>";
    private static String payment(String type, String months) {
        return "<td>" + type + "</td><td>2025-01</td><td>2025-06</td><td>" + months + "</td>";
    }
    private static String namedRow() {
        return "<tr><td>1</td><td>测试甲</td><td>TEST-ID-001</td>" + payment("基本养老保险", "6") + "</tr>";
    }
    private static String table(String body) { return "<table>" + DOUBLE_HEADER + body + "</table>"; }
    private static Page page(int number, String text) { return new Page(number, text, "PAGE", "QWEN_PDF_OCR", null); }
    private static Document doc(Page... pages) { return new Document("bid", "project", "BID", "测试社保材料", 3, "hash", "2026-10-14", cn.bidcheck.compat.Java8Api.listOf(pages), cn.bidcheck.compat.Java8Api.listOf()); }
    private static StructuredHtmlTableFacts.Result analyze(String text) { Page page = page(145, text); return StructuredHtmlTableFacts.analyze(doc(page), page); }
    private static List<Fact> field(StructuredHtmlTableFacts.Result result, String field) { return cn.bidcheck.compat.Java8Api.toList(result.facts().stream().filter(f -> field.equals(f.field()))); }
    private static void rejected(String html) {
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze(html);
        assertFalse(result.complete(), "Malformed or ambiguous grid must retain visual fallback");
        assertTrue(result.facts().isEmpty(), "Do not partially publish a table rejected later in validation");
        assertFalse(result.issues().isEmpty());
    }

    @Test void doubleHeaderAndJointIdentitySpansExtractEveryPhysicalCellOnce() {
        String html = table("<tr><td rowspan='3'>1</td><td rowspan='3'>测试甲</td><td rowspan='3'>TEST-ID-001</td>"
                + payment("基本养老保险", "6") + "</tr><tr>" + payment("工伤保险", "5") + "</tr><tr>"
                + payment("失业保险", "4") + "</tr>");
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze(html);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(StructuredHtmlTableFacts.SCHEMA, result.schema());
        assertTrue(result.issues().isEmpty());
        assertEquals(15, result.facts().size()); // Three identity cells plus four cells on each insurance row.
        assertEquals(1, field(result, "insuredPersonName").size());
        assertEquals(1, field(result, "socialSecurityNumber").size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("6", "5", "4"), cn.bidcheck.compat.Java8Api.toList(field(result, "paidMonths").stream().map(Fact::normalizedValue)));
        assertEquals(3, field(result, "insuranceType").stream().map(Fact::entityKey).distinct().count());
        assertTrue(result.facts().stream().allMatch(f -> f.entityKey().contains(":identity3:")));
    }

    @Test void exactSingleHeaderIsSupportedWithLiteralDatesAndMonths() {
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze("<table><thead>" + SINGLE_HEADER + "</thead><tbody>" + namedRow().replace("<td>6</td>", "<td>2</td>") + "</tbody></table>");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(7, result.facts().size());
        assertEquals("2025-01", field(result, "contributionStart").get(0).normalizedValue());
        assertEquals("2025-06", field(result, "contributionEnd").get(0).normalizedValue());
        assertEquals("2", field(result, "paidMonths").get(0).normalizedValue(), "Do not infer six paid months from the two endpoints");
    }

    @Test void evidenceRetainsExactOriginalTableAndCharacterOffsetsWithoutInventedGeometry() {
        String html = table(namedRow().replace("测试甲", "测试甲<br/>示例"));
        String source = "社保缴费证明\n" + html + "\n第145页";
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze(source);
        assertTrue(result.complete(), result.issues().toString());
        Set<String> anchors = new HashSet<>();
        for (Fact fact : result.facts()) {
            assertEquals(html, fact.evidence().quote());
            assertEquals("bid", fact.evidence().documentId());
            assertEquals(145, fact.evidence().page());
            assertEquals("PAGE", fact.evidence().locationType());
            assertEquals(0d, fact.evidence().confidence());
            assertTrue(fact.evidence().quote().contains(fact.rawValue()));
            Matcher offsets = Pattern.compile("html-table:([0-9]+):r([0-9]+):c([0-9]+):chars:([0-9]+)-([0-9]+)").matcher(fact.evidence().anchor());
            assertTrue(offsets.matches());
            assertEquals(source.indexOf("<table>"), Integer.parseInt(offsets.group(1)));
            assertEquals(fact.rawValue(), cn.bidcheck.compat.Java8Api.strip(source.substring(Integer.parseInt(offsets.group(4)), Integer.parseInt(offsets.group(5)))));
            assertTrue(anchors.add(fact.evidence().anchor()), "One fact per physical cell, even when values repeat");
        }
        assertEquals("测试甲 示例", field(result, "insuredPersonName").get(0).normalizedValue());
    }

    @Test void fullyEmptyIdentityRowRetainsOnlyItsOwnFieldsAndRequiresReview() {
        String anonymous = "<tr><td></td><td> </td><td></td>" + payment("失业保险", "3") + "</tr>";
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze(table(namedRow() + anonymous));
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(11, result.facts().size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("存在身份信息为空的缴费记录，未关联到任何人员，需人工核对。"), result.issues());
        assertEquals(1, field(result, "insuredPersonName").size());
        java.util.List<cn.bidcheck.domain.Domain.Fact> anonymousFacts = cn.bidcheck.compat.Java8Api.toList(result.facts().stream().filter(f -> f.entityKey().endsWith(":identity4:row4")));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("insuranceType", "contributionStart", "contributionEnd", "paidMonths"), new HashSet<>(cn.bidcheck.compat.Java8Api.toList(anonymousFacts.stream().map(Fact::field))));
        assertTrue(anonymousFacts.stream().noneMatch(f -> f.entityKey().equals(field(result, "insuredPersonName").get(0).entityKey())));
        assertTrue(result.facts().stream().noneMatch(f -> f.field().toLowerCase(Locale.ROOT).contains("total")));
    }

    @Test void rejectsPartiallyMissingIdentityAndNeverPublishesEarlierGoodRows() {
        rejected(table(namedRow() + namedRow().replace("TEST-ID-001", "")));
        rejected(table(namedRow().replace("测试甲", "")));
    }

    @Test void rejectsIdentitySpanMismatchEvenWhenExpandedGridIsOtherwiseComplete() {
        String rows = "<tr><td rowspan='2'>1</td><td>测试甲</td><td rowspan='2'>TEST-ID-001</td>"
                + payment("基本养老保险", "6") + "</tr><tr><td>测试乙</td>" + payment("失业保险", "6") + "</tr>";
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze(table(rows));
        assertFalse(result.complete());
        assertTrue(result.facts().isEmpty());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("HTML_TABLE_IDENTITY_SPAN_MISMATCH"), result.issues());
    }

    @Test void rejectsCollisionOutsideSpanAndInconsistentWidth() {
        String collision = "<tr><td>1</td><td rowspan='2'>测试甲</td><td>TEST-ID-001</td>" + payment("基本养老保险", "6")
                + "</tr><tr><td colspan='2'>2</td><td>TEST-ID-002</td>" + payment("失业保险", "6") + "</tr>";
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("HTML_TABLE_SPAN_COLLISION"), analyze(table(collision)).issues());
        rejected(table(namedRow().replace("<td>1</td>", "<td rowspan='2'>1</td>")));
        rejected(table(namedRow().replace("<td>6</td>", "")));
        rejected(table(namedRow().replace("</tr>", "<td>extra</td></tr>")));
        rejected(table(namedRow().replace("<td>1</td>", "<td rowspan='0'>1</td>")));
    }

    @Test void incompleteNestedMultipleTablesAndUnknownHeadersRetainVisualFallback() {
        String valid = table(namedRow());
        rejected(valid.substring(0, valid.length() - 8));
        rejected(valid.replace("TEST-ID-001", "<table><tr><td>inner</td></tr></table>"));
        rejected(valid + valid);
        rejected(valid.replace("社会保障号码", "证书编号"));
        rejected(valid.replace("</td>", "</th>"));
        rejected(valid.replace("<td>6</td>", "<td><b>6</b></td>"));
        rejected(valid.replace("rowspan=\"2\"", "rowspan=\"2\" rowspan=\"1\""));
    }

    @Test void nonNumericMonthOrOversizedCellAndTableRemainUnresolved() {
        rejected(table(namedRow().replace("<td>6</td>", "<td>未知</td>")));
        rejected(table(namedRow().replace("测试甲", cn.bidcheck.compat.Java8Api.repeat("甲", 201))));
        rejected(table(namedRow().replace("测试甲", cn.bidcheck.compat.Java8Api.repeat("甲", StructuredHtmlTableFacts.MAX_TABLE_CHARACTERS))));
        rejected(table(cn.bidcheck.compat.Java8Api.repeat(namedRow(), 201)));
    }

    @Test void sourceMustBeBoundToTheDocumentAndNotDeferredOrIncomplete() {
        Page page = page(145, table(namedRow()));
        assertFalse(StructuredHtmlTableFacts.analyze(doc(page(146, page.text())), page).complete());
        for (String warning : cn.bidcheck.compat.Java8Api.listOf("[OCR未完成]", "[OCR_DEFERRED]")) {
            Page pending = new Page(page.number(), page.text(), page.locationType(), page.parser(), warning);
            assertFalse(StructuredHtmlTableFacts.analyze(doc(pending), pending).complete());
        }
    }

    @Test void ordinaryPageIsNotReportedAsAnHtmlTableFailure() {
        cn.bidcheck.document.StructuredHtmlTableFacts.Result result = analyze("本页为普通的施工方案正文，未提供社保明细表。");
        assertFalse(result.complete());
        assertEquals("", result.schema());
        assertTrue(result.facts().isEmpty());
        assertTrue(result.issues().isEmpty());
    }

    @Test void insuranceNamesDoNotBecomeRoleAssignmentsOrMergeAcrossPages() {
        Page first = page(145, table(namedRow())), second = page(146, table(namedRow()));
        Document document = doc(first, second);
        cn.bidcheck.document.StructuredHtmlTableFacts.Result a = StructuredHtmlTableFacts.analyze(document, first);
        cn.bidcheck.document.StructuredHtmlTableFacts.Result b = StructuredHtmlTableFacts.analyze(document, second);
        assertTrue(a.complete()); assertTrue(b.complete());
        assertTrue(a.facts().stream().allMatch(f -> "INSURANCE_RECORD".equals(f.entityType())));
        assertTrue(a.facts().stream().noneMatch(f -> cn.bidcheck.compat.Java8Api.setOf("personName", "idNumber", "warranty", "role", "continuousMonths").contains(f.field())));
        assertNotEquals(field(a, "insuredPersonName").get(0).entityKey(), field(b, "insuredPersonName").get(0).entityKey());
        Checkpoint role = new Checkpoint("role", "项目经理姓名一致性", "人员", "HIGH", "ENTITY_FIELD_CONSISTENCY",
                "personName", "=", "", "", true, true, true, null);
        assertEquals("UNDETERMINED", new CheckEngine().evaluate(role, document, a.facts()).status());
    }
}

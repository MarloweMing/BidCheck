package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonCredentialReportTest {
    private static Map<String,Object> coverage(String status) {
        return BidService.fields("status",status,"pagesTotal",50,"pagesScanned",50,"candidatePages",2,"occurrencesCount",8,"comparisonGroupsCount",1);
    }
    @Test void ordinaryExtractionGapsRemainInClosedNotesWithoutBecomingWarnings() {
        java.util.Map<java.lang.String, java.lang.Object> coverage=coverage("PARTIAL");coverage.put("requiresReview",false);
        coverage.put("issues",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "documentName", "商务分册.pdf", "locationType", "PAGE", "page", 50, "code", "CREDENTIAL_NUMBER_MISSING", "message", "该记录未提取到明确号码", "severity", "INFO", "requiresReview", false)));
        coverage.put("missingCandidateLocations",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "documentName", "商务分册.pdf", "page", 50)));
        String html=ReportService.personCredentialCoverageHtml(coverage,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "documentVersion", 3)),cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "bid", "name", "商务分册.pdf", "version", 3)));
        assertTrue(html.contains("已提取可识别的人员与证件字段"));assertTrue(html.contains("商务分册.pdf · V3"));assertTrue(html.contains("第 50 页"));
        assertTrue(html.contains("该记录未提取到明确号码"));assertTrue(html.contains("部分字段未进入比较"));assertTrue(html.contains("不代表所有字段均已提取"));
        assertTrue(html.contains("<details data-person-credential-extraction-notes>"));
        assertFalse(html.contains("<details open"));assertFalse(html.contains("data-person-credential-attention"));
        assertFalse(html.contains("待核对"));assertFalse(html.contains("待补齐"));
        assertEquals("PARTIAL",coverage.get("status"),"Presentation does not rewrite the frozen extraction truth");
    }
    @Test void technicalGapsStayVisibleWhileExtractionNotesStayCollapsed() {
        java.util.Map<java.lang.String, java.lang.Object> coverage=coverage("PARTIAL");coverage.put("requiresReview",true);
        java.util.Map<java.lang.String, java.lang.Object> informational=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("documentName", "商务分册.pdf", "page", 1, "code", "CREDENTIAL_NUMBER_MASKED", "message", "号码含遮挡，仅保留原文", "severity", "INFO", "requiresReview", false);
        java.util.Map<java.lang.String, java.lang.Object> actionable=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("documentName", "商务分册.pdf", "page", 50, "code", "PAGE_NOT_SCANNED", "message", "该页尚未完成扫描", "severity", "WARNING", "requiresReview", true);
        coverage.put("issues",cn.bidcheck.compat.Java8Api.listOf(informational, actionable));coverage.put("actionableIssues",cn.bidcheck.compat.Java8Api.listOf(actionable));coverage.put("informationalIssues",cn.bidcheck.compat.Java8Api.listOf(informational));
        String html=ReportService.personCredentialCoverageHtml(coverage,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(html.contains("人员与证件扫描存在未完成范围"));
        assertTrue(html.contains("<details open data-person-credential-attention>"));
        assertTrue(html.contains("扫描与来源处理提示（1 处）"));
        int notes=html.indexOf("<details data-person-credential-extraction-notes>");
        assertTrue(html.indexOf("该页尚未完成扫描")<notes);
        assertTrue(html.indexOf("号码含遮挡，仅保留原文")>notes);
        assertTrue(html.contains("第 50 页"));assertTrue(html.contains("字段差异 1 组"));
    }
    @Test void legacyOrdinaryIssuesUseTheSameInformationOnlyPolicy() {
        java.util.Map<java.lang.String, java.lang.Object> coverage=coverage("PARTIAL");coverage.put("issues",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 50, "code", "CREDENTIAL_TYPE_UNKNOWN", "message", "未确定证件类型")));
        String html=ReportService.personCredentialCoverageHtml(coverage,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(html.contains("未确定证件类型"));assertTrue(html.contains("原始提取范围记录为部分覆盖"));
        assertFalse(html.contains("data-person-credential-attention"));assertFalse(html.contains("<details open"));
    }
    @Test void scannedAndNoFactsHaveDistinctNonPassLabels() {
        String scanned=ReportService.personCredentialCoverageHtml(coverage("TEXT_SCANNED"),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        String absent=ReportService.personCredentialCoverageHtml(coverage("NO_FACTS"),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(scanned.contains("已扫描可读文本中的明确字段"));assertTrue(absent.contains("尚未提取到可用的人员证件事实"));
        assertFalse(scanned.contains("全部通过"));assertFalse(absent.contains("全文一致性通过"));
    }
    @Test void allSourceNamesAndReasonsAreEscapedAndUnknownPageIsNotInvented() {
        java.util.Map<java.lang.String, java.lang.Object> coverage=coverage("PARTIAL");coverage.put("issues",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentName", "<img src=x onerror=alert(1)>", "page", 0, "reason", "<script>boom</script>")));
        String html=ReportService.personCredentialCoverageHtml(coverage,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentName", "<b>恶意文件</b>", "documentVersion", 1)),cn.bidcheck.compat.Java8Api.listOf());
        assertFalse(html.contains("<script>"));assertFalse(html.contains("<img "));assertTrue(html.contains("&lt;script&gt;"));assertTrue(html.contains("&lt;b&gt;恶意文件&lt;/b&gt;"));
        assertTrue(html.contains("范围提示"));assertFalse(html.contains("第 1 页"));
    }
    @Test void legacyReportsDoNotAcquireAnInventedCoverageSection() {
        assertEquals("",ReportService.personCredentialCoverageHtml(cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf()));
    }
}

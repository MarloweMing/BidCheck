package cn.bidcheck.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerificationReportServiceTest {
    @Test void preparedScopeUsesFrozenEvidenceVersionAndKeepsDeferredSeparate() {
        Map<String,Object> prepared=cn.bidcheck.compat.Java8Api.mapOf("documentId", "new", "name", "证据版本.pdf", "version", 2, "sha256", "newhash");
        Map<String,Object> run=cn.bidcheck.compat.Java8Api.mapOf("evidenceDocumentIds", cn.bidcheck.compat.Java8Api.listOf("new"), "preparedManifest", cn.bidcheck.compat.Java8Api.listOf(prepared, cn.bidcheck.compat.Java8Api.mapOf("documentId", "foreign", "name", "不在证据集合")));
        Map<String,Object> coverage=cn.bidcheck.compat.Java8Api.mapOf("notice", "按检查计划已识别 1 处，1 处待按需补识别", "deferredLocations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "new", "label", "第 2 页", "warning", "[OCR_DEFERRED][OCR未完成] 延期")), "failedLocations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "new", "label", "第 3 页", "warning", "识别超时")));
        Map<String,Object> actual=cn.bidcheck.compat.Java8Api.mapOf("id", "new", "version", 2, "sha256", "newhash", "ocrCoverage", coverage);
        String result=VerificationReportService.preparedScope(run,cn.bidcheck.compat.Java8Api.listOf(actual, cn.bidcheck.compat.Java8Api.mapOf("id", "old", "version", 1, "sha256", "oldhash", "ocrCoverage", cn.bidcheck.compat.Java8Api.mapOf("notice", "旧版本不能混入"))));
        assertTrue(result.contains("证据版本.pdf · V2"));assertTrue(result.contains("待按需补识别 · 第 2 页"));assertTrue(result.contains("识别未完成 · 第 3 页"));
        assertFalse(result.contains("旧版本不能混入"));assertFalse(result.contains("不在证据集合"));
        assertTrue(VerificationReportService.preparedScope(cn.bidcheck.compat.Java8Api.mapOf("manifest", cn.bidcheck.compat.Java8Api.listOf(prepared)),cn.bidcheck.compat.Java8Api.listOf(actual)).isEmpty(),"Legacy reports never acquire a later prepared manifest");
        String mismatch=VerificationReportService.preparedScope(run,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "new", "version", 3, "sha256", "newhash", "ocrCoverage", coverage)));
        assertFalse(mismatch.contains("按检查计划已识别 1 处"));assertTrue(mismatch.contains("未返回识别范围说明"));
    }
    @ParameterizedTest
    @ValueSource(strings={"=1+1\n","=1+1\r\n"," \t=1+1\n", "+SUM(1,2)\n", "-1+2\n", "@SUM(1,2)\n", "\n=1+1", "=HYPERLINK(\"https://example.invalid\",\"文字\")\n"})
    void multilineFormulaCellIsExportedAsText(String value){
        String exported=VerificationReportService.csv(value);
        assertTrue(exported.startsWith("\"'"),"Formula-like cells must be prefixed as text even when they contain newlines");
        assertEquals("'"+value,exported.substring(1,exported.length()-1).replace("\"\"","\""),"Original content and quoting are preserved");
    }
    @Test void ordinaryMultilineTextKeepsItsContent(){assertEquals("\"第一行\n=这只是第二行的原文\"",VerificationReportService.csv("第一行\n=这只是第二行的原文"));}
    @Test void nullCellRemainsEmpty(){assertEquals("\"\"",VerificationReportService.csv(null));}
}

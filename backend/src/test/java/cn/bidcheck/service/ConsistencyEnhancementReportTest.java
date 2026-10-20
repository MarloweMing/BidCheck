package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyEnhancementReportTest {
    @Test void csvScopeKeepsGlobalUnresolvedCountsWithoutExpandingFindingRows() {
        var coverage=Map.<String,Object>of("pagesTotal",581,"pagesScanned",581,"pagesUnparsed",0,
            "contextAssignments",Map.of("EXPLICIT",0,"INHERITED",285,"DOCUMENT_DEFAULT",0,"UNRESOLVED",1522),
            "tableRelations",Map.of("tables",310,"rows",1968,"resolvedRows",837,"unresolvedRows",1131));
        String scope=ReportService.consistencyScope(Map.of("coverage",coverage));
        assertTrue(scope.contains("未明确 1522 条"));assertTrue(scope.contains("沿用章节 285 条"));
        assertTrue(scope.contains("总表 310 个；总行 1968 行；可关联 837 行；待核对 1131 行"));
        assertTrue(scope.contains("扫描覆盖不等于全部信息已核实"));
        assertTrue(scope.contains("CSV按检查结果行提供全局范围摘要及关联组事实"));
        assertTrue(scope.contains("未列入差异组的完整事实和来源请查看JSON导出"));
    }
    @Test void csvScopeDoesNotInventEnhancementResultsForLegacyOrMissingCounts() {
        String legacy=ReportService.consistencyScope(Map.of("coverage",Map.of("pagesTotal",3,"pagesScanned",2,"pagesUnparsed",1)));
        assertTrue(legacy.contains("未完整解析 1"));assertFalse(legacy.contains("事实归属："));assertFalse(legacy.contains("表格关系："));
        String partial=ReportService.consistencyScope(Map.of("coverage",Map.of(
            "contextAssignments",Map.of("EXPLICIT",2),"tableRelations",Map.of("tables",2))));
        assertTrue(partial.contains("未明确 未知 条"));assertTrue(partial.contains("待核对 未知 行"));
        assertFalse(partial.contains("未明确 0 条"));assertFalse(partial.contains("待核对 0 行"));
        assertEquals("",ReportService.consistencyScope(Map.of()));
    }
    @Test void consortiumDeclarationsRemainVisibleOutsideDifferenceGroupsWithBothSources() {
        var assignment=Map.<String,Object>of("status","DECLARED_CONSORTIUM_MEMBER","memberName","海南甲建筑工程有限公司","projectMatched",true,"identityVerified",false,
            "evidences",List.of(Map.of("documentName","商务分册.pdf","page",6,"quote","联合体协议：成员海南甲建筑工程有限公司"),Map.of("documentName","商务分册.pdf","page",1,"quote","综合楼装修工程")));
        var occurrence=Map.<String,Object>of("subject","BIDDER_MEMBER:海南甲建筑工程有限公司","subjectAssignment",assignment);
        String html=ReportService.consortiumAssignmentsHtml(List.of(occurrence,occurrence));
        assertTrue(html.contains("联合体成员 · 海南甲建筑工程有限公司"));assertFalse(html.contains("BIDDER_MEMBER:"));
        assertTrue(html.contains("协议明确声明的联合体成员"));assertTrue(html.contains("未核验签章及法律身份"));
        assertTrue(html.contains("第 6 页"));assertTrue(html.contains("第 1 页"));assertTrue(html.contains("协议与项目封面依据"));
        assertEquals(1,html.split("data-subject-assignment>",-1).length-1);
        assertEquals("BIDDER_MEMBER:海南甲建筑工程有限公司",occurrence.get("subject"));
    }
    @Test void unboundConsortiumDeclarationNeverClaimsMatchedProjectOrVerifiedIdentity() {
        var assignment=Map.<String,Object>of("status","DECLARED_CONSORTIUM_MEMBER","memberName","<img src=x>","projectMatched",false,
            "evidences",List.of(Map.of("documentName","<b>原文</b>","page",0,"quote","<script>不可信</script>")));
        String html=ReportService.consortiumAssignmentsHtml(List.of(Map.of("subjectAssignment",assignment)));
        assertTrue(html.contains("成员声明待核对"));assertFalse(html.contains("协议项目名称与本次项目匹配"));
        assertTrue(html.contains("未核验签章及法律身份"));assertTrue(html.contains("位置未提供"));assertFalse(html.contains("第 1 页"));
        assertTrue(html.contains("&lt;img src=x&gt;"));assertFalse(html.contains("<img "));assertFalse(html.contains("<script>"));
        assertEquals("",ReportService.consortiumAssignmentsHtml(List.of(Map.of("subject","BIDDER"))));
    }
    @Test void interruptedDispatchIsUnknownEvenWhenOldIntentHadFalseRequestedFlag() {
        String text=ReportService.targetedReviewText(Map.of("status","ERROR","requestOutcomeUnknown",true,"modelRequested",false,"reason","上次未保存发送回执"));
        assertTrue(text.contains("模型请求：发送情况未知"));assertFalse(text.contains("模型请求：未请求"));
    }
    @Test void inProgressAndDeduplicationAreSeparateFromSuccessfulReview() {
        String text=ReportService.targetedReviewText(Map.of("status","IN_PROGRESS","deduplicatedEvidenceCount",2));
        assertTrue(text.contains("正在补证与复核"));assertTrue(text.contains("去重 2 条"));assertFalse(text.contains("已取得模型建议"));
    }
    @Test void contextAndTableCoverageExposeUnresolvedRowsWithoutClaimingAccuracy() {
        var coverage=Map.<String,Object>of("contextAssignments",Map.of("EXPLICIT",2,"INHERITED",3,"DOCUMENT_DEFAULT",4,"UNRESOLVED",5),
            "tableRelations",Map.of("tables",2,"rows",10,"resolvedRows",8,"unresolvedRows",2,"linkedContinuations",1,"requiresReview",true,
                "issues",List.of(Map.of("reason","表头不足","source",Map.of("page",42)))));
        String html=ReportService.contextTableCoverageHtml(coverage);
        assertTrue(html.contains("文档暂定 4 条"));assertTrue(html.contains("未明确 5 条"));assertTrue(html.contains("待核对 2 行"));
        assertTrue(html.contains("第 42 页：表头不足"));assertTrue(html.contains("不能作为 OCR 准确率或业务通过率"));
        assertFalse(html.contains("全部关联"));assertEquals("",ReportService.contextTableCoverageHtml(Map.of()));
    }
    @Test void modelAdviceNeverBecomesAnAutomaticOrHumanConclusion() {
        var review=BidService.fields("status","MODEL_REVIEWED","reason","已定位两处报价说明","candidateCount",8,"acceptedEvidenceCount",2,"rejectedEvidenceCount",6,"modelRequested",true,
            "recommendation",Map.of("status","PASS","reason","两处均为含税总额","suggestion","请人工确认税率与标段"));
        var group=BidService.fields("status","SUSPECTED","targetedReview",review);
        String html=ReportService.consistencyEnhancementHtml(group,List.of());
        assertTrue(html.contains("已取得模型建议，待人工核对"));
        assertTrue(html.contains("复核建议（供人工确认）"));
        assertTrue(html.contains("模型建议不自动替代本组结论或人工复核"));
        assertFalse(html.contains("业务结论（自动）：符合"));
        assertEquals("SUSPECTED",group.get("status"));
    }
    @Test void unknownMetricsAndMissingLegacyReceiptAreNeverZeroSuccesses() {
        String partial=ReportService.targetedReviewText(Map.of("status","ERROR","reason","检索未完成"));
        assertTrue(partial.contains("检索候选 未知 条"));assertTrue(partial.contains("模型请求：未记录"));
        assertFalse(partial.contains("来源校验通过 0"));
        String legacy=ReportService.consistencyEnhancementHtml(Map.of("id","old-group"),List.of());
        assertTrue(legacy.contains("未记录组级定向复核"));assertFalse(legacy.contains("已取得模型建议"));
        assertEquals("",ReportService.consistencyEnhancementHtml(Map.of(),List.of()));
    }
    @Test void budgetAndGroupLimitsRemainVisibleAsUnfinishedWork() {
        for(String status:List.of("BUDGET_DEFERRED","GROUP_LIMIT_DEFERRED","MODEL_UNAVAILABLE","ERROR")) {
            String text=ReportService.targetedReviewText(Map.of("status",status,"modelRequested",false,"missingDimensions",List.of("标段","税价口径")));
            assertTrue(text.contains("未请求"));assertTrue(text.contains("待明确：标段、税价口径"));
            assertFalse(text.contains("全部通过"));assertFalse(text.contains("模型请求：已请求"));
        }
    }
    @Test void inheritedContextAndSamePageSpanRetainTheirIndependentSources() {
        var occurrence=BidService.fields("rawValue","陈某","contextAssignment",Map.of("status","INHERITED","crossPage",true,"sectionPath",List.of("历史业绩","项目甲"),"reason","沿用明确历史章节",
            "evidences",List.of(Map.of("documentName","商务分册.pdf","page",40,"quote","第三章 历史业绩"))),
            "tableAssociation",Map.of("status","RESOLVED","headerPath",List.of("项目人员","姓名"),"inherited",true,
                "source",Map.of("documentName","商务分册.pdf","page",41),"headerSources",List.of(Map.of("documentName","商务分册.pdf","page",41))));
        String html=ReportService.consistencyEnhancementHtml(Map.of("id","group"),List.of(occurrence));
        assertTrue(html.contains("沿用章节归属"));assertTrue(html.contains("历史业绩 / 项目甲"));
        assertTrue(html.contains("第 40 页"));assertTrue(html.contains("第 41 页"));assertTrue(html.contains("项目人员 / 姓名"));
        assertTrue(html.contains("沿用同页合并单元格"));assertTrue(html.contains("相邻页不自动认定为同一人员记录"));
    }
    @Test void defaultDocumentScopeIsNotPresentedAsExplicitSectionAssignment() {
        var occurrence=Map.<String,Object>of("contextAssignment",Map.of("status","DOCUMENT_DEFAULT","reason","按投标文件角色暂定"));
        String html=ReportService.consistencyEnhancementHtml(Map.of("id","group"),List.of(occurrence));
        assertTrue(html.contains("尚未找到明确章节依据"));assertFalse(html.contains("明确标记归属"));
    }
    @Test void allSourceTextAndModelOutputAreEscapedAndMissingPageIsNotInvented() {
        var review=Map.<String,Object>of("status","EVIDENCE_AUGMENTED","reason","<script>alert(1)</script>","evidences",List.of(Map.of("documentName","<img src=x>","quote","<b>恶意原文</b>","page",0)));
        String html=ReportService.consistencyEnhancementHtml(Map.of("targetedReview",review),List.of());
        assertFalse(html.contains("<script>"));assertFalse(html.contains("<img "));assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;b&gt;恶意原文&lt;/b&gt;"));assertTrue(html.contains("位置未提供"));assertFalse(html.contains("第 1 页"));
    }
}

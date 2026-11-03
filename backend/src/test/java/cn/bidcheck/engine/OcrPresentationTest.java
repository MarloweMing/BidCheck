package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OcrPresentationTest {
    @Test void documentOnlyLayoutWarningsAreLocatedDeduplicatedAndSeparateFromTextCoverage() {
        List<Page> pages=new ArrayList<>();List<String> warnings=new ArrayList<>();
        for(int number=1;number<=450;number++) {
            String warning="第 "+number+" 页[OCR结构未完成] 表格关系 <须核对>";
            pages.add(new Page(number,"转录保留","PAGE","PDF_OCR",number==1?warning:"[OCR已识别]"));
            warnings.add(warning);warnings.add(warning);
        }
        cn.bidcheck.domain.Domain.Document document=new Document("b","p","BID","投标.pdf",1,"hash","now",pages,warnings);
        java.util.Map<java.lang.String, java.lang.Object> coverage=OcrPresentation.coverage(document);
        assertThat(coverage).containsEntry("structureIncompleteCount",450L).containsEntry("structureCoverageComplete",false)
            .containsEntry("incompleteCount",0L).containsEntry("ocrCoverageComplete",true).containsEntry("unlocatedStructureCount",0L);
        assertThat((List<?>)coverage.get("structureIncompleteLocations")).hasSize(450);
        assertThat(coverage.get("notice").toString()).contains("450 处文字或表格布局待复核");
    }
    @Test void ambiguousOrWrongTypeLayoutLocationsRemainUnlocated() {
        cn.bidcheck.domain.Domain.Document document=new Document("b","p","BID","投标.docx",1,"hash","now",cn.bidcheck.compat.Java8Api.listOf(new Page(2,"文字","BLOCK","POI","")),
            cn.bidcheck.compat.Java8Api.listOf("第 2 页[OCR结构未完成] 不能把块当页", "[OCR结构未完成] 未知位置", "[OCR结构未完成] 未知位置"));
        java.util.Map<java.lang.String, java.lang.Object> coverage=OcrPresentation.coverage(document);
        assertThat(coverage).containsEntry("structureIncompleteCount",2L).containsEntry("unlocatedStructureCount",2L);
        assertThat((List<?>)coverage.get("structureIncompleteLocations")).isEmpty();
    }
    @Test void deferredCoverageIsNotFailureAndMixedBlocksRetainBothRanges() {
        String delayed="第 2 个内容块[OCR_DEFERRED][OCR未完成] 按检查计划延期";
        String mixed="第 3 个内容块[OCR已识别] 一张已返回；[OCR_DEFERRED][OCR未完成] 一张延期；[OCR未完成] 一张读取失败";
        Document document=new Document("b","p","BID","分段.docx",4,"hash","now",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"局部有效文本","BLOCK","WORD_NATIVE_WITH_OCR","[OCR已识别]"), new Page(2,"","BLOCK","WORD_NATIVE",delayed), new Page(3,"部分文本","BLOCK","WORD_NATIVE_WITH_OCR",mixed)),cn.bidcheck.compat.Java8Api.listOf(delayed, mixed));
        Map<String,Object> coverage=OcrPresentation.coverage(document);
        assertThat(coverage).containsEntry("incompleteCount",2L).containsEntry("deferredCount",2L)
            .containsEntry("failedCount",1L).containsEntry("ocrLocations",2L).containsEntry("ocrCoverageComplete",false);
        assertThat((List<?>)coverage.get("deferredLocations")).hasSize(2);
        assertThat((List<?>)coverage.get("failedLocations")).hasSize(1);
        assertThat(coverage.get("notice").toString()).contains("按检查计划已识别 2 处","2 处待按需补识别","另有 1 处识别未完成");
        java.util.Map<java.lang.String, java.lang.Object> view=display(OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(finding("原业务说明","DETERMINISTIC",cn.bidcheck.compat.Java8Api.listOf(evidence("b",2,"BLOCK")))),cn.bidcheck.compat.Java8Api.listOf(document)));
        assertThat(view.get("note").toString()).contains("待按需补识别","不表示识别服务失败").doesNotContain("本项引用位置有未完成的图像识别");
        assertThat(view).containsEntry("reason","原业务说明").containsEntry("legacyClarified",false);
    }
    @Test void unlocatedDeferredCoverageStillPreventsACompleteClaim() {
        Document document=new Document("b","p","BID","附件.pdf",1,"h","now",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"文字","PAGE","PDFBOX_NATIVE","")),
            cn.bidcheck.compat.Java8Api.listOf("[OCR_DEFERRED][OCR未完成] 无法定位到页的延期图像"));
        assertThat(OcrPresentation.coverage(document)).containsEntry("incompleteCount",1L).containsEntry("deferredCount",1L)
            .containsEntry("failedCount",0L).containsEntry("unlocatedIncompleteCount",1L).containsEntry("ocrCoverageComplete",false);
    }
    private static final String PREFIX="部分图像OCR未完成，现有文字不能证明全部内容已经核验。";
    private static final String SUGGESTION="请核对原图中的名称、数字、日期及标识；OCR文字不能证明签字盖章有效。";
    private Document tender() { return new Document("t","p","TENDER","招标.docx",1,"hash","now",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"真实条款","PARAGRAPH","POI_NATIVE",""), new Page(8,"","PARAGRAPH","POI_NATIVE","第8块[OCR未完成]旧式图片")),cn.bidcheck.compat.Java8Api.listOf("第8块[OCR未完成]旧式图片")); }
    private Document bid() { return new Document("b","p","BID","投标.pdf",3,"hash","now",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"人员表","PAGE","PDFBOX_NATIVE",""), new Page(2,"证照文字","PAGE","PDFBOX_OCR","[OCR已识别]")),cn.bidcheck.compat.Java8Api.listOf("[OCR已识别]")); }
    private Evidence evidence(String id,int page,String location) { return new Evidence(id,"原件",page,location,"引用","p"+page,0); }
    private Finding finding(String reason,String method,List<Evidence> references) {
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("c","人员核查","资格","HIGH","SEMANTIC_RESPONSE","staff","RESPONDS","核对任职","",true,true,true,evidence("t",1,"PARAGRAPH"));
        return new Finding("f","r",cp,new Evaluation("UNDETERMINED",reason,SUGGESTION+"原建议",references,0,method));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> display(Map<String,Object> result) { return (Map<String,Object>)((Map<?,?>)result.get("findings")).get("f"); }

    @Test void unrelatedTenderFailureIsNotCalledBidOrPerFindingFailure() {
        Finding original=finding(PREFIX+"未召回社保，需核对检索范围。","DASHSCOPE:test/OCR_REVIEW",cn.bidcheck.compat.Java8Api.listOf(evidence("b",1,"PAGE")));
        Map<String,Object> result=OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(original),cn.bidcheck.compat.Java8Api.listOf(tender(), bid()));
        Map<String,Object> view=display(result);
        assertThat(view.get("reason")).isEqualTo("未召回社保，需核对检索范围。");
        assertThat(view.get("note").toString()).contains("当前引用未定位到 OCR 未完成").doesNotContain("本项引用位置有未完成");
        assertThat(view.get("legacyClarified")).isEqualTo(true);
        assertThat(original.evaluation().reason()).startsWith(PREFIX);
        assertThat(original.evaluation().status()).isEqualTo("UNDETERMINED");
        List<?> documents=(List<?>)result.get("documents");
        assertThat(((Map<?,?>)documents.get(0)).get("incompleteCount")).isEqualTo(1L);
        assertThat(((Map<?,?>)documents.get(1)).get("incompleteCount")).isEqualTo(0L);
        assertThat(result.toString()).doesNotContain("真实条款").doesNotContain("人员表").doesNotContain("证照文字");
    }
    @Test void citedIncompleteLocationRemainsVisibleWithCorrectDocumentAndBlock() {
        java.util.Map<java.lang.String, java.lang.Object> result=OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(finding(PREFIX+"业务原因","DASHSCOPE:test/OCR_REVIEW",cn.bidcheck.compat.Java8Api.listOf(evidence("t",8,"PARAGRAPH")))),cn.bidcheck.compat.Java8Api.listOf(tender(), bid()));
        assertThat(display(result).get("note").toString()).contains("招标文件第 8 段").contains("未完成").doesNotContain("投标文件第 8");
    }
    @Test void completedOcrRequiresAccuracyReviewWithoutClaimingItFailed() {
        java.util.Map<java.lang.String, java.lang.Object> result=OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(finding("业务原因","DASHSCOPE:test/OCR_REVIEW",cn.bidcheck.compat.Java8Api.listOf(evidence("b",2,"PAGE")))),cn.bidcheck.compat.Java8Api.listOf(tender(), bid()));
        assertThat(display(result).get("note").toString()).contains("投标文件第 2 页").contains("没有 OCR 未完成标记").contains("签字盖章未核验");
        assertThat(display(result).get("legacyClarified")).isEqualTo(false);
    }
    @Test void modelAuthoredOcrStatementAndNonLeadingPrefixAreNeverRewritten() {
        for(String method:cn.bidcheck.compat.Java8Api.listOf("DASHSCOPE:test", "DETERMINISTIC")) {
            assertThat(display(OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(finding(PREFIX+"模型正文",method,cn.bidcheck.compat.Java8Api.listOf())),cn.bidcheck.compat.Java8Api.listOf(bid()))).get("reason")).isEqualTo(PREFIX+"模型正文");
        }
        String text="模型引用："+PREFIX;
        assertThat(display(OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(finding(text,"DASHSCOPE:test/OCR_REVIEW",cn.bidcheck.compat.Java8Api.listOf())),cn.bidcheck.compat.Java8Api.listOf(bid()))).get("reason")).isEqualTo(text);
    }
    @Test void foreignReferencesAndWrongLocationTypesDoNotBorrowOcrState() {
        java.util.Map<java.lang.String, java.lang.Object> result=OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(finding("业务原因","DASHSCOPE:test/OCR_REVIEW",cn.bidcheck.compat.Java8Api.listOf(evidence("foreign",8,"PARAGRAPH"), evidence("t",8,"PAGE")))),cn.bidcheck.compat.Java8Api.listOf(tender(), bid()));
        assertThat(display(result).get("note").toString()).doesNotContain("本项引用位置有未完成");
    }
    @Test void newPolicyAndUnpairedModelTextCannotBeMistakenForLegacyBoilerplate() {
        Finding future=finding(PREFIX+"模型分析", "DASHSCOPE:test/OCR_REVIEW_V2",cn.bidcheck.compat.Java8Api.listOf());
        assertThat(display(OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(future),cn.bidcheck.compat.Java8Api.listOf(bid()))).get("reason")).isEqualTo(future.evaluation().reason());
        Finding old=finding(PREFIX+"模型分析", "DASHSCOPE:test/OCR_REVIEW",cn.bidcheck.compat.Java8Api.listOf());
        Finding unpaired=new Finding(old.id(),old.runId(),old.checkpoint(),new Evaluation("WARNING",old.evaluation().reason(),"模型自己的建议",cn.bidcheck.compat.Java8Api.listOf(),0,old.evaluation().method()));
        assertThat(display(OcrPresentation.context(cn.bidcheck.compat.Java8Api.listOf(unpaired),cn.bidcheck.compat.Java8Api.listOf(bid()))).get("reason")).isEqualTo(unpaired.evaluation().reason());
    }
}

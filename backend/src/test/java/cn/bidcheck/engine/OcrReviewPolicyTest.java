package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OcrReviewPolicyTest {
    private Page deferred(int number) { return new Page(number,"","PAGE","PDFBOX_NATIVE","[OCR_DEFERRED][OCR未完成] 待按检查计划补识别"); }
    private Checkpoint scoped(String type,String title,String expected) { return new Checkpoint("scope",title,"资格","HIGH",type,"material","EXISTS",expected,"",true,true,true,null); }
    @Test void deferredPagesPreservePositiveLocalProofButCannotProveMissingMaterial() {
        Document bid=doc(page(1,false,false),deferred(2));
        Checkpoint material=scoped("MATERIAL_EXISTENCE","营业执照","营业执照");
        assertThat(OcrReviewPolicy.apply(result("PASS",1),material,bid,null).status()).isEqualTo("PASS");
        cn.bidcheck.engine.OcrReviewPolicy.Decision missing=OcrReviewPolicy.assess(result("FAIL",1),material,bid,null);
        assertThat(missing.evaluation().status()).isEqualTo("UNDETERMINED");
        assertThat(missing.adjustments()).anyMatch(item->"OCR_COVERAGE_DEFERRED".equals(item.get("code")));
        assertThat(missing.review()).containsEntry("coverageIncomplete",true).containsEntry("required",false);
        assertThat(OcrReviewPolicy.apply(result("PASS",1),rule,bid,null).status()).isEqualTo("PASS");
    }
    @Test void deferredScopeGuardsExhaustiveSuccessAndPersonnelCountsButPreservesProvenContradiction() {
        Document bid=doc(page(1,false,false),deferred(2));
        Checkpoint consistency=scoped("ENTITY_FIELD_CONSISTENCY","全文信息一致性","各处名称一致");
        assertThat(OcrReviewPolicy.apply(result("PASS",1),consistency,bid,null).status()).isEqualTo("UNDETERMINED");
        assertThat(OcrReviewPolicy.apply(result("FAIL",1),consistency,bid,null).status()).isEqualTo("FAIL");
        for(String expected:cn.bidcheck.compat.Java8Api.listOf("全部人员均具有证书", "配备不少于3名人员", "人员人数为3")) {
            Checkpoint count=scoped("SEMANTIC_RESPONSE","人员配置",expected);
            for(String status:cn.bidcheck.compat.Java8Api.listOf("PASS", "FAIL")) assertThat(OcrReviewPolicy.apply(result(status,1),count,bid,null).status()).isEqualTo("UNDETERMINED");
        }
    }
    @Test void deferredCitationHasItsOwnGuardAndMixedRecognizedPageRemainsIncomplete() {
        Document bid=doc(new Page(1,"投标有效期：90天","PAGE","PDFBOX_NATIVE_WITH_OCR","[OCR已识别] 部分文字；[OCR_DEFERRED][OCR未完成] 另一图像延期"));
        cn.bidcheck.engine.OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(result("PASS",1),rule,bid,null);
        assertThat(decision.evaluation().status()).isEqualTo("UNDETERMINED");
        assertThat(decision.review()).containsEntry("deferred",true).containsEntry("failed",false);
        assertThat(decision.adjustments()).anyMatch(item->"OCR_SOURCE_DEFERRED".equals(item.get("code")));
        assertThat(EngineText.incomplete(bid)).isTrue();
        assertThat(EngineText.ocrFailed("[OCR_DEFERRED][OCR未完成] 延期；[OCR未完成] 实际失败")).isTrue();
    }
    private final Checkpoint rule=new Checkpoint("id","有效期","商务","HIGH","VALUE_COMPARE","bidValidity",">=","90","DAY",true,true,true,null);
    private Document doc(Page... pages) { return new Document("b","p","BID","scan.pdf",1,"hash","date",cn.bidcheck.compat.Java8Api.listOf(pages),cn.bidcheck.compat.Java8Api.listOf()); }
    private Page page(int n,boolean ocr,boolean failed) { return new Page(n,"投标有效期：90天","PAGE",ocr?"PDFBOX_OCR":"PDFBOX_NATIVE",failed?"[OCR未完成] 图片失败":""); }
    private Evidence evidence(int n) { return new Evidence("b","scan.pdf",n,"PAGE","投标有效期：90天","p"+n,0); }
    private Evaluation result(String status,int page) { return new Evaluation(status,"比较结果","",cn.bidcheck.compat.Java8Api.listOf(evidence(page)),1,"DETERMINISTIC"); }
    @Test void completedOcrKeepsBusinessDecisionAndSeparatelyRequestsImageReview() {
        for(String status:cn.bidcheck.compat.Java8Api.listOf("PASS", "FAIL", "WARNING", "UNDETERMINED")) {
            cn.bidcheck.engine.OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(result(status,1),rule,doc(page(1,true,false)),null);
            assertThat(decision.evaluation().status()).isEqualTo(status);
            assertThat(decision.review()).containsEntry("required",true).containsEntry("incomplete",false);
            assertThat(decision.adjustments()).isEmpty();
        }
    }
    @Test void unrelatedUnrecognizedPagesNeverDowngradeNativeEvidence() {
        Evaluation original=result("PASS",1);
        cn.bidcheck.engine.OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(original,rule,doc(page(1,false,false),page(2,true,true)),null);
        assertThat(decision.evaluation()).isSameAs(original);
        assertThat(decision.review()).containsEntry("required",false).containsEntry("incomplete",false);
    }
    @Test void numericEngineAndReviewPolicyTogetherIgnoreUnrelatedImagesButGuardReferencedImages() {
        CheckEngine engine=new CheckEngine();
        for(boolean referencedFailure:cn.bidcheck.compat.Java8Api.listOf(false, true)) {
            Document bid=doc(page(1,false,referencedFailure),new Page(2,"历史项目图片","PAGE","PDFBOX_NATIVE","[OCR未完成] 图片失败"));
            Evaluation calculated=engine.evaluate(rule,bid,new FactExtractor().extract(bid));
            assertThat(calculated.status()).isEqualTo("PASS");
            assertThat(OcrReviewPolicy.apply(calculated,rule,bid,null).status()).isEqualTo(referencedFailure?"UNDETERMINED":"PASS");
        }
    }
    @Test void incompleteReferencedImageCannotSupportPassOrFirmFailure() {
        Document failed=doc(page(1,true,true));
        assertThat(OcrReviewPolicy.apply(result("PASS",1),rule,failed,null).status()).isEqualTo("UNDETERMINED");
        cn.bidcheck.engine.OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(result("FAIL",1),rule,failed,null);
        assertThat(decision.evaluation().status()).isEqualTo("WARNING");
        assertThat(decision.adjustments()).containsExactly(cn.bidcheck.compat.Java8Api.mapOf("code", "OCR_SOURCE_INCOMPLETE", "reason", "本项引用的图像位置尚未识别完整。"));
        assertThat(OcrReviewPolicy.apply(decision.evaluation(),rule,failed,null)).isSameAs(decision.evaluation());
    }
    @Test void ruleSourceIsCheckedAgainstItsOwnDocumentVersionAndLocationType() {
        Document tender=new Document("t","p","TENDER","t.docx",2,"h","d",cn.bidcheck.compat.Java8Api.listOf(new Page(8,"要求90天","PARAGRAPH","POI_NATIVE","[OCR未完成] 规则图片")),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("c","有效期","商务","HIGH","VALUE_COMPARE","bidValidity",">=","90","DAY",true,true,true,new Evidence("t","t.docx",8,"PARAGRAPH","要求90天","p8",0));
        assertThat(OcrReviewPolicy.apply(result("PASS",1),cp,doc(page(1,false,false)),tender).status()).isEqualTo("UNDETERMINED");
        cn.bidcheck.domain.Domain.Checkpoint wrongLocation=new Checkpoint("c","有效期","商务","HIGH","VALUE_COMPARE","bidValidity",">=","90","DAY",true,true,true,new Evidence("t","t.docx",8,"PAGE","要求90天","p8",0));
        assertThat(OcrReviewPolicy.assess(result("PASS",1),wrongLocation,doc(page(1,false,false)),tender).review()).containsEntry("incomplete",false);
    }
    @Test void absentBidEvidenceCannotBecomePassAndNativeSignatureStillRequiresReview() {
        Evaluation noProof=new Evaluation("PASS","有内容","",cn.bidcheck.compat.Java8Api.listOf(),1,"MODEL");
        assertThat(OcrReviewPolicy.assess(noProof,rule,doc(page(1,false,false)),null).adjustments()).anyMatch(v->"EVIDENCE_MISSING".equals(v.get("code")));
        Checkpoint seal=new Checkpoint("s","签章","形式","HIGH","SIGNATURE_SEAL","seal","EXISTS","签章","",true,true,true,null);
        cn.bidcheck.engine.OcrReviewPolicy.Decision decision=OcrReviewPolicy.assess(result("PASS",1),seal,doc(page(1,false,false)),null);
        assertThat(decision.evaluation().status()).isEqualTo("UNDETERMINED");
        assertThat(decision.review()).containsEntry("required",true);
        assertThat(decision.adjustments()).anyMatch(v->"VISUAL_SIGNATURE_REQUIRED".equals(v.get("code")));
    }
    @Test void templateDeclaredTenderProofCanSupportPassButMustBeQuotedAndLocatable() {
        Document bid=doc(page(1,false,false));
        Document tender=new Document("t","p","TENDER","tender.docx",1,"th","date",
            cn.bidcheck.compat.Java8Api.listOf(new Page(8,"投标截止时间：2025年8月5日","PARAGRAPH","POI_NATIVE",""), new Page(9,"开标时间同投标截止时间","PARAGRAPH","POI_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());
        Evaluation tenderProof=new Evaluation("PASS","两个日期相同","",cn.bidcheck.compat.Java8Api.listOf(new Evidence("t","tender.docx",8,"PARAGRAPH","投标截止时间：2025年8月5日","p8",0), new Evidence("t","tender.docx",9,"PARAGRAPH","开标时间同投标截止时间","p9",0)),1,"TEMPLATE_FIXED");

        cn.bidcheck.engine.OcrReviewPolicy.Decision accepted=OcrReviewPolicy.assess(tenderProof,rule,bid,tender,cn.bidcheck.compat.Java8Api.setOf("TENDER"));
        assertThat(accepted.evaluation()).isSameAs(tenderProof);
        assertThat(accepted.adjustments()).isEmpty();
        assertThat(accepted.review()).containsEntry("requiredProofDocumentRoles",cn.bidcheck.compat.Java8Api.listOf("TENDER"))
            .containsEntry("locatedProofDocumentRoles",cn.bidcheck.compat.Java8Api.listOf("TENDER"));
        // Without an explicit template contract the long-standing BID-proof guard still applies.
        assertThat(OcrReviewPolicy.assess(tenderProof,rule,bid,tender).evaluation().status()).isEqualTo("UNDETERMINED");

        Evaluation blankQuote=new Evaluation("PASS","两个日期相同","",cn.bidcheck.compat.Java8Api.listOf(new Evidence("t","tender.docx",8,"PARAGRAPH","","p8",0)),1,"TEMPLATE_FIXED");
        Evaluation wrongLocation=new Evaluation("PASS","两个日期相同","",cn.bidcheck.compat.Java8Api.listOf(new Evidence("t","tender.docx",80,"PARAGRAPH","投标截止时间：2025年8月5日","p80",0)),1,"TEMPLATE_FIXED");
        for(Evaluation invalid:cn.bidcheck.compat.Java8Api.listOf(blankQuote, wrongLocation)){
            cn.bidcheck.engine.OcrReviewPolicy.Decision guarded=OcrReviewPolicy.assess(invalid,rule,bid,tender,cn.bidcheck.compat.Java8Api.setOf("TENDER"));
            assertThat(guarded.evaluation().status()).isEqualTo("UNDETERMINED");
            assertThat(guarded.adjustments()).anyMatch(item->"EVIDENCE_MISSING".equals(item.get("code")));
        }
    }
    @Test void disabledRuleAndIncompleteMaterialPresenceKeepCorrectScope() {
        assertThat(OcrReviewPolicy.apply(result("NOT_APPLICABLE",1),rule,doc(page(1,true,true)),null).status()).isEqualTo("NOT_APPLICABLE");
        Document failed=doc(new Page(1,"附件：营业执照","PAGE","PDFBOX_NATIVE","[OCR未完成] 图片失败"));
        Checkpoint material=new Checkpoint("m","营业执照","资格","HIGH","MATERIAL_EXISTENCE","material","EXISTS","营业执照","",true,true,true,null);
        Evaluation original=new CheckEngine().evaluate(material,failed,cn.bidcheck.compat.Java8Api.listOf());
        assertThat(original.status()).isEqualTo("PASS");
        assertThat(OcrReviewPolicy.apply(original,material,failed,null).status()).isEqualTo("UNDETERMINED");
    }
}

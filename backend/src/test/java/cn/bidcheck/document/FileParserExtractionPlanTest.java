package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.ocr.OcrClient;
import cn.bidcheck.ocr.OcrResult;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FileParserExtractionPlanTest {
    @TempDir Path temporary;
    private static final class FakeOcr implements OcrClient {
        final AtomicInteger calls=new AtomicInteger();
        Runnable beforeCall=()->{};
        public boolean enabled(){return true;}
        public String modelName(){return "offline-test";}
        public String identity(){return "offline-test-1";}
        public OcrResult recognize(byte[] bytes,String mime) throws IOException {beforeCall.run();calls.incrementAndGet();return new OcrResult("可追溯识别正文，内容来自选定图片。","test","offline-test",false);}
    }
    @Test void nativeOnlyKeepsAllPdfLocationsAndExplicitlyDefersUnknownImages() throws Exception {
        Path file=pdf("native.pdf",List.of("Native text", ""),Set.of(1,2));FakeOcr ocr=new FakeOcr();
        var result=new FileParser(ocr).parsePlanned(file,"native.pdf",null,ExtractionPlan.nativeOnly());
        assertEquals(0,ocr.calls.get());assertEquals(2,result.document().pages().size());
        assertTrue(result.document().pages().get(0).text().contains("Native text"));
        assertTrue(result.document().pages().stream().allMatch(p->p.warning().contains("[OCR_DEFERRED][OCR未完成]")));
        assertTrue(result.document().pages().stream().noneMatch(p->p.parser().contains("OCR")));
        assertEquals(false,result.report().get("ocrCoverageComplete"));assertEquals(2,result.report().get("deferredImageCount"));
        assertEquals(2,((List<?>)result.report().get("nativeMap")).size());
    }
    @Test void selectedPdfRunsOnlyAfterWholeNativeMapAndPreservesSourcePositions() throws Exception {
        Path file=pdf("selected.pdf",List.of("First", "Second", "Third"),Set.of(1,2,3));FakeOcr ocr=new FakeOcr();
        AtomicInteger mappingComplete=new AtomicInteger();ocr.beforeCall=()->assertEquals(1,mappingComplete.get());
        var plan=ExtractionPlan.selected(hash(file),List.of(OcrSelection.position("PAGE",2,"引用需要补识别")),2,"精确补证");
        var result=new FileParser(ocr).parsePlanned(file,"selected.pdf",(p,s)->{if(s.startsWith("原生文档地图已完成"))mappingComplete.set(1);},plan);
        assertEquals(1,ocr.calls.get());assertEquals(List.of(1,2,3),result.document().pages().stream().map(p->p.number()).toList());
        assertTrue(result.document().pages().get(1).text().contains("Second"));assertTrue(result.document().pages().get(1).parser().contains("OCR"));
        assertTrue(result.document().pages().get(0).warning().contains("[OCR_DEFERRED]"));
        assertEquals(hash(file),result.report().get("actualSourceSha256"));assertEquals(1,result.report().get("selectedImageCount"));
    }
    @Test void sourceHashMismatchIsRejectedBeforeAnyOcr() throws Exception {
        Path file=pdf("stale.pdf",List.of(""),Set.of(1));FakeOcr ocr=new FakeOcr();
        assertThrows(IOException.class,()->new FileParser(ocr).parse(file,"stale.pdf",null,
                ExtractionPlan.selected("0".repeat(64),List.of(OcrSelection.position("PAGE",1,"旧版本")),1,"旧版本")));
        assertEquals(0,ocr.calls.get());
    }
    @Test void explicitInvalidPositionsAreUnresolvedNotSilentlyCompleted() throws Exception {
        Path file=pdf("missing.pdf",List.of("Native only"),Set.of());FakeOcr ocr=new FakeOcr();
        var result=new FileParser(ocr).parsePlanned(file,"missing.pdf",null,ExtractionPlan.selected(hash(file),
                List.of(OcrSelection.position("PAGE",1,"无图页"),OcrSelection.position("PAGE",8,"不存在页")),2,"位置验证"));
        assertEquals(0,ocr.calls.get());assertEquals(2,((List<?>)result.report().get("unresolvedSelections")).size());
        assertEquals(false,result.report().get("ocrCoverageComplete"));
    }
    @Test void wordSelectsImageIndexWithoutChangingBlockNamespaceOrDroppingOtherImages() throws Exception {
        Path file=temporary.resolve("mixed.docx");
        try(var word=new XWPFDocument()){
            word.createParagraph().createRun().setText("前言原生文字");
            var run=word.createParagraph().createRun();run.setText("社保材料");
            for(int i=0;i<2;i++)run.addPicture(new ByteArrayInputStream(png(i)),XWPFDocument.PICTURE_TYPE_PNG,"image"+i,100000,100000);
            word.createParagraph().createRun().setText("末尾原生文字");try(var out=Files.newOutputStream(file)){word.write(out);}
        }
        FakeOcr ocr=new FakeOcr();var plan=ExtractionPlan.selected(hash(file),List.of(new OcrSelection("BLOCK",2,List.of(2),List.of("第二张图"))),1,"Word 图像精确补证");
        var result=new FileParser(ocr).parsePlanned(file,"mixed.docx",null,plan);
        assertEquals(1,ocr.calls.get());assertEquals(3,result.document().pages().size());
        assertEquals(List.of("PARAGRAPH","BLOCK","PARAGRAPH"),result.document().pages().stream().map(p->p.locationType()).toList());
        var block=result.document().pages().get(1);assertEquals(2,block.number());assertTrue(block.text().contains("【图片 2 OCR识别文字】"));
        assertTrue(block.warning().contains("[OCR_DEFERRED]"));assertTrue(block.warning().contains("[OCR已识别]"));
        assertEquals(1,result.report().get("deferredImageCount"));assertEquals(false,result.report().get("ocrCoverageComplete"));
    }
    @Test void standaloneImageHasExplicitNativeAndFullPolicies() throws Exception {
        Path file=temporary.resolve("image.png");Files.write(file,png(0));FakeOcr ocr=new FakeOcr();var parser=new FileParser(ocr);
        var nativeResult=parser.parsePlanned(file,"image.png",null,ExtractionPlan.nativeOnly());
        assertEquals(0,ocr.calls.get());assertTrue(nativeResult.document().pages().get(0).warning().contains("[OCR_DEFERRED]"));
        var full=parser.parse(file,"image.png");assertEquals(1,ocr.calls.get());assertEquals("IMAGE_OCR",full.pages().get(0).parser());
    }
    @Test void budgetDefersWorkDistinctlyFromNoMatchAndDoesNotExceedParserBudget() throws Exception {
        Path file=pdf("budget.pdf",List.of("ClaimAnchor", "", "", "", "VIN document"),Set.of(1,2,3,4,5));FakeOcr ocr=new FakeOcr();
        var result=new FileParser(ocr,1).parsePlanned(file,"budget.pdf",null,ExtractionPlan.fromCheckpoints(List.of(cp("a","ClaimAnchor","","要求",true,true))).withImageBudget(2));
        assertEquals(1,ocr.calls.get());
        List<?> deferred=(List<?>)result.report().get("deferredPositions");assertTrue(deferred.stream().anyMatch(v->((Map<?,?>)v).get("code").equals("PLAN_IMAGE_BUDGET")));
        assertTrue(deferred.stream().anyMatch(v->((Map<?,?>)v).get("code").equals("NO_NATIVE_MATCH")));
        assertFalse((boolean)result.report().get("ocrCoverageComplete"));
    }
    @Test void checkpointPlansIgnoreUnconfirmedOrDisabledRulesAndBindFullRuleSnapshot() {
        var unconfirmed=cp("a","社保","socialMonths","连续 3 月",false,true);
        assertEquals(ExtractionPlan.Mode.NATIVE_ONLY,ExtractionPlan.fromCheckpoints(List.of(unconfirmed)).mode());
        assertEquals(ExtractionPlan.Mode.NATIVE_ONLY,ExtractionPlan.fromCheckpoints(List.of(cp("a","社保","socialMonths","连续 3 月",true,false))).mode());
        var a=cp("a","社保","socialMonths","连续 3 月",true,true);var b=cp("b","业绩","contract","提供合同",true,true);
        assertEquals(ExtractionPlan.fromCheckpoints(List.of(a,b)).hash(),ExtractionPlan.fromCheckpoints(List.of(b,a)).hash());
        assertNotEquals(ExtractionPlan.fromCheckpoints(List.of(a)).hash(),ExtractionPlan.fromCheckpoints(List.of(cp("a","社保","socialMonths","连续 6 月",true,true))).hash());
    }
    @Test void narrowSocialRuleSelectsNineImageContinuationPagesThenStopsAtDifferentHeading() throws Exception {
        var locations=new ArrayList<ExtractionPlan.NativeLocation>();
        locations.add(new ExtractionPlan.NativeLocation(23,"PAGE","证明文件扫描件\n社保",1,1));
        for(int page=24;page<=31;page++)locations.add(new ExtractionPlan.NativeLocation(page,"PAGE",String.valueOf(page-1),1,1));
        locations.add(new ExtractionPlan.NativeLocation(32,"PAGE","营业执照",1,1));
        locations.add(new ExtractionPlan.NativeLocation(33,"PAGE","32",1,1));
        var plan=ExtractionPlan.fromCheckpoints(List.of(cp("s","社保月份核验","socialMonths","连续三个月社会保险",true,true)));
        var decisions=plan.resolve(locations,100).decisions();
        for(int page=23;page<=31;page++)assertTrue(decisions.get("PAGE:"+page+":1").selected(),"page "+page);
        assertEquals("CONTINUATION_CANDIDATE",decisions.get("PAGE:31:1").code());assertTrue(decisions.get("PAGE:31:1").reasons().get(0).contains("PAGE 23"));
        assertFalse(decisions.get("PAGE:32:1").selected());assertFalse(decisions.get("PAGE:33:1").selected());
        var budget=plan.withImageBudget(3).resolve(locations,100).decisions();assertEquals("PLAN_IMAGE_BUDGET",budget.get("PAGE:26:1").code());
    }
    @Test void headingContinuationIsBoundedEvenWhenNoNewHeadingIsReadable() throws Exception {
        var locations=new ArrayList<ExtractionPlan.NativeLocation>();locations.add(new ExtractionPlan.NativeLocation(1,"BLOCK","社保",1,1));
        for(int p=2;p<=16;p++)locations.add(new ExtractionPlan.NativeLocation(p,"BLOCK","",1,1));
        var plan=ExtractionPlan.fromCheckpoints(List.of(cp("s","社保","socialMonths","三个月",true,true)));
        var result=plan.resolve(locations,100);assertTrue(result.decisions().get("BLOCK:13:1").selected());
        assertFalse(result.decisions().get("BLOCK:14:1").selected());
    }
    @Test void plannedNativeMappingStillHonorsCancellationWithoutOcr() throws Exception {
        Path file=pdf("cancel.pdf",List.of("", ""),Set.of(1,2));FakeOcr ocr=new FakeOcr();
        try {assertThrows(InterruptedIOException.class,()->new FileParser(ocr).parse(file,"cancel.pdf",(p,s)->Thread.currentThread().interrupt(),ExtractionPlan.nativeOnly()));}
        finally {Thread.interrupted();}
        assertEquals(0,ocr.calls.get());
    }
    private Path pdf(String name,List<String> texts,Set<Integer> imagePages) throws Exception {
        Path path=temporary.resolve(name);try(PDDocument document=new PDDocument()){
            for(int i=0;i<texts.size();i++) {var page=new PDPage();document.addPage(page);
                try(var stream=new PDPageContentStream(document,page)){
                    if(!texts.get(i).isEmpty()){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,720);stream.showText(texts.get(i));stream.endText();}
                    if(imagePages.contains(i+1)){var image=new BufferedImage(40,40,BufferedImage.TYPE_INT_RGB);image.setRGB(i%40,0,0xffffff);stream.drawImage(LosslessFactory.createFromImage(document,image),30,100,40,40);image.flush();}
                }
            }document.save(path.toFile());
        }return path;
    }
    private static byte[] png(int variant) throws IOException {var image=new BufferedImage(30,30,BufferedImage.TYPE_INT_RGB);image.setRGB(variant,0,0xffffff);var output=new ByteArrayOutputStream();ImageIO.write(image,"PNG",output);image.flush();return output.toByteArray();}
    private static String hash(Path file) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static Checkpoint cp(String id,String title,String field,String expected,boolean confirmed,boolean enabled){return new Checkpoint(id,title,"资格","HIGH","SEMANTIC_RESPONSE",field,"",expected,"",true,confirmed,enabled,null);}
}

package cn.bidcheck.document;

import cn.bidcheck.ocr.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileParserStructuredLayoutTest {
    @TempDir Path data;
    private static byte[] image(int color) throws IOException {
        var bitmap=new BufferedImage(40,60,BufferedImage.TYPE_INT_RGB);bitmap.setRGB(1,1,color);
        try(var out=new ByteArrayOutputStream()){ImageIO.write(bitmap,"png",out);return out.toByteArray();}finally{bitmap.flush();}
    }
    private static final class Client implements OcrClient {
        int calls; boolean sparse, invalid, currentBid; String failureCode;
        public boolean enabled(){return true;}public String modelName(){return "layout-mock";}public String identity(){return "layout-mock-1";}
        public synchronized OcrResult recognize(byte[] bytes,String mime)throws IOException {
            calls++;if(sparse&&calls==1)return new OcrResult("283","mock",modelName(),false);
            var bitmap=ImageIO.read(new ByteArrayInputStream(bytes));
            try {
                String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IOException(e);}
                String text=(currentBid?"投标文件封面\n项目名称：":"")+"合成正文"+calls;
                var layout=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,
                        List.of(new OcrLayout.Frame("image",bitmap.getWidth(),bitmap.getHeight(),hash,"SUBMITTED_IMAGE",0,null,null,null)),
                        List.of(new OcrLayout.TextBox("b1","image",invalid?"虚构正文":text,new OcrLayout.Box(1,1,10,10),null)),List.of(),List.of("b1"),List.of());
                return new OcrResult(text,"mock",modelName(),false,failureCode==null?layout:OcrLayout.unavailable(failureCode+" 结构未完成"));
            }finally{bitmap.flush();}
        }
    }
    @Test void pdfRenderedLayoutUsesActualTransportImageAndSurvivesPageJson() throws Exception {
        Path path=data.resolve("scan.pdf");
        try(var pdf=new PDDocument()){
            var page=new PDPage();pdf.addPage(page);var bitmap=ImageIO.read(new ByteArrayInputStream(image(0xffffff)));
            var picture=LosslessFactory.createFromImage(pdf,bitmap);bitmap.flush();try(var content=new PDPageContentStream(pdf,page)){content.drawImage(picture,20,20,40,60);}pdf.save(path.toFile());
        }
        var page=new FileParser(new Client(),10,1).parse(path,"scan.pdf").pages().get(0);
        assertEquals("PDFBOX_OCR",page.parser());assertNotNull(page.layout());assertEquals("PDF_RENDER",page.layout().frames().get(0).sourceKind());
        assertTrue(page.layout().frames().get(0).width()>1000);assertEquals(1,page.layout().frames().get(0).sourceIndex());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();assertEquals(page,json.readValue(json.writeValueAsBytes(page),cn.bidcheck.domain.Domain.Page.class));
    }
    @Test void wordImagesKeepIndependentFramesAndNeverUseContactSheetCoordinates() throws Exception {
        Path path=data.resolve("two.docx");
        try(var word=new XWPFDocument()){
            var paragraph=word.createParagraph();paragraph.createRun().setText("原生说明");
            for(int color:List.of(0xffffff,0x123456))paragraph.createRun().addPicture(new ByteArrayInputStream(image(color)),XWPFDocument.PICTURE_TYPE_PNG,"source.png",400000,600000);
            try(var output=Files.newOutputStream(path)){word.write(output);}
        }
        var page=new FileParser(new Client(),10,1).parse(path,"two.docx").pages().get(0);
        assertEquals("WORD_NATIVE_WITH_OCR",page.parser());assertEquals(2,page.layout().frames().size());assertEquals("PARTIAL",page.layout().status());
        assertEquals(List.of(1,2),page.layout().frames().stream().map(OcrLayout.Frame::sourceIndex).toList());
        assertTrue(page.layout().frames().stream().allMatch(f->f.width()==40&&f.height()==60&&f.sourceKind().equals("WORD_EMBEDDED")));
        assertNotEquals(page.layout().frames().get(0).imageHash(),page.layout().frames().get(1).imageHash());
    }
    @Test void sparseCropFramesKeepActualOverlapAndDoNotReconstructFullPageTables() throws Exception {
        Path path=data.resolve("sparse.png");Files.write(path,image(0xffffff));var client=new Client();client.sparse=true;
        var page=new FileParser(client,10,1).parse(path,"sparse.png").pages().get(0);
        assertEquals(3,client.calls);assertEquals("PARTIAL",page.layout().status());assertEquals(2,page.layout().frames().size());
        var top=page.layout().frames().get(0); var bottom=page.layout().frames().get(1);
        assertEquals(60,top.parentHeight());assertEquals(0,top.crop().y());assertEquals(27,bottom.crop().y());assertEquals(33,top.height());
        assertEquals(1,page.layout().textBoxes().get(1).bbox().y());assertTrue(page.text().contains("边界有重叠"));
    }
    @Test void invalidGeometryDoesNotDiscardUsefulTranscriptionOrPretendOcrFailed() throws Exception {
        Path path=data.resolve("image.png");Files.write(path,image(0xffffff));var client=new Client();client.invalid=true;
        var page=new FileParser(client,10,1).parse(path,"image.png").pages().get(0);
        assertTrue(page.text().contains("合成正文1"));assertEquals("UNAVAILABLE",page.layout().status());
        assertTrue(page.warning().contains("[OCR结构未完成]"));assertFalse(page.warning().contains("[OCR未完成]"));
    }
    @Test void structureOnlyFailureDoesNotDowngradeLegacyTextCompletenessOrCoverage() throws Exception {
        Path path=data.resolve("compatibility.png");Files.write(path,image(0xffffff));var client=new Client();client.invalid=true;client.currentBid=true;
        var parsed=new FileParser(client,10,1).parse(path,"compatibility.png");var page=parsed.pages().get(0);
        var document=new cn.bidcheck.domain.Domain.Document("b","p","BID","scan.png",1,"hash","now",parsed.pages(),parsed.warnings());
        var rule=new cn.bidcheck.domain.Domain.Checkpoint("c","项目名称","一致性","HIGH","ENTITY_FIELD_CONSISTENCY","projectName","=","合成正文1","",true,true,true,null);
        var facts=new cn.bidcheck.engine.FactExtractor().extract(document);
        var evaluation=new cn.bidcheck.engine.CheckEngine().evaluate(rule,document,facts);
        assertEquals("PASS",evaluation.status(),"Layout unavailability cannot masquerade as unparsed text in EngineText completeness");
        var decision=cn.bidcheck.engine.OcrReviewPolicy.assess(evaluation,rule,document,null);
        assertEquals("PASS",decision.evaluation().status());assertEquals(false,decision.review().get("incomplete"));assertEquals(true,decision.review().get("required"));
        var context=cn.bidcheck.engine.OcrPresentation.context(List.of(new cn.bidcheck.domain.Domain.Finding("f","r",rule,evaluation)),List.of(document));
        var coverage=(Map<?,?>)((List<?>)context.get("documents")).get(0);assertEquals(0L,coverage.get("incompleteCount"));
        String failure=page.warning()+"[OCR未完成] 另一区域正文尚未识别";
        var incompletePage=new cn.bidcheck.domain.Domain.Page(1,page.text(),page.locationType(),page.parser(),failure,page.layout());
        var incomplete=new cn.bidcheck.domain.Domain.Document("b","p","BID","scan.png",1,"hash","now",List.of(incompletePage),List.of(failure));
        assertEquals("UNDETERMINED",new cn.bidcheck.engine.CheckEngine().evaluate(rule,incomplete,facts).status());
        assertEquals(true,cn.bidcheck.engine.OcrReviewPolicy.assess(evaluation,rule,incomplete,null).review().get("incomplete"));
    }
    @Test void safeLayoutFailureCodeReachesPageWarningWithoutMarkingTextIncomplete() throws Exception {
        Path path=data.resolve("diagnostic.png");Files.write(path,image(0xffffff));var client=new Client();client.failureCode="[LAYOUT_TIMEOUT]";
        var page=new FileParser(client,10,1).parse(path,"diagnostic.png").pages().get(0);
        assertTrue(page.warning().contains("[LAYOUT_TIMEOUT]"));assertTrue(page.warning().contains("[OCR结构未完成]"));
        assertTrue(page.warning().contains("[OCR已识别]"));assertFalse(page.warning().contains("[OCR未完成]"));assertFalse(page.text().isBlank());
    }
}

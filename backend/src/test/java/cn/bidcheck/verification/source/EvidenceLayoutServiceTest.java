package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.document.FileParser;
import cn.bidcheck.ocr.OcrLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceLayoutServiceTest {
    @TempDir Path temp;
    static Document document(Path path,List<Page> pages)throws IOException{return new Document("synthetic-doc","project-a","BID",path.getFileName().toString(),1,SourceSafety.digest(Files.readAllBytes(path)),"",pages,List.of());}
    Path pdf(int rotation)throws IOException{Path file=temp.resolve("source.pdf");try(PDDocument d=new PDDocument()){PDPage p=new PDPage(new PDRectangle(300,400));p.setRotation(rotation);d.addPage(p);try(PDPageContentStream out=new PDPageContentStream(d,p)){out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(40,330);out.showText("Actual certificate text");out.endText();}d.save(file.toFile());}return file;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> spans(Map<String,Object> result){return (List<Map<String,Object>>)result.get("spans");}
    @Test void pdfCoordinatesAreNativeAndMatchSafeRenderDimensions()throws Exception{
        Path p=pdf(0);Document doc=document(p,List.of(new Page(1,"Actual certificate text","PAGE","PDFBOX_NATIVE","")));var service=new EvidenceLayoutService();var result=service.layout(doc,p);
        assertEquals("COMPLETE",result.get("status"));var span=spans(result).get(0);assertEquals("Actual certificate text",span.get("text"));List<?> box=(List<?>)span.get("bbox");assertEquals(40,((Number)box.get(0)).doubleValue(),.1);assertTrue(((Number)box.get(1)).doubleValue()<70);
        Map<?,?> page=(Map<?,?>)((List<?>)result.get("pages")).get(0);Map<?,?> transform=(Map<?,?>)page.get("renderMapping");var image=ImageIO.read(new ByteArrayInputStream(new FileParser().renderPdfPage(p,1)));assertEquals(image.getWidth(),transform.get("pixelWidth"));assertEquals(image.getHeight(),transform.get("pixelHeight"));
        assertEquals(result,service.layout(doc,p));
    }
    @Test void rotatedPdfStillUsesDisplayedTopLeftSpace()throws Exception{
        Path p=pdf(90);var result=new EvidenceLayoutService().layout(document(p,List.of()),p);assertEquals("COMPLETE",result.get("status"));Map<?,?> page=(Map<?,?>)((List<?>)result.get("pages")).get(0);assertEquals(400d,page.get("width"));assertEquals(300d,page.get("height"));List<?> b=(List<?>)spans(result).get(0).get("bbox");assertNotNull(b);assertTrue(((Number)b.get(0)).doubleValue()>=0);assertTrue(((Number)b.get(2)).doubleValue()<=400);
    }
    @Test void ocrTextHasNoInventedBox()throws Exception{
        Path p=pdf(0);var result=new EvidenceLayoutService().layout(document(p,List.of(new Page(1,"【原生文字】\nActual certificate text\n【OCR识别文字】\n扫描证件编号甲","PAGE","PDFBOX_NATIVE_WITH_OCR",""))),p);
        Map<String,Object> ocr=spans(result).stream().filter(s->s.get("kind").equals("OCR_TEXT")).findFirst().orElseThrow();assertNull(ocr.get("bbox"));assertEquals("扫描证件编号甲",ocr.get("text"));
    }
    @Test void persistedStructuredPagesEnterLayoutAndChangeItsCacheIdentity()throws Exception{
        Path p=pdf(0);var box=new OcrLayout.TextBox("box","frame","证书",new OcrLayout.Box(2,3,20,10),null);
        var structure=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,List.of(new OcrLayout.Frame("frame",100,100,"a".repeat(64),"PDF_PAGE",1,null,null,null)),List.of(box),List.of(),List.of("box"),List.of());
        var original=document(p,List.of(new Page(1,"证书","PAGE","PDFBOX_OCR","",structure)));
        var json=new ObjectMapper();var restored=json.readValue(json.writeValueAsBytes(original),Document.class);
        var service=new EvidenceLayoutService();var layout=service.layout(restored,p);
        assertEquals("COMPLETE",layout.get("status"));assertTrue(spans(layout).stream().anyMatch(s->"OCR_TEXT_BOX".equals(s.get("kind"))&&"证书".equals(s.get("text"))&&s.get("bbox")!=null));
        assertNotEquals(service.cacheIdentity(restored),service.cacheIdentity(document(p,List.of(new Page(1,"证书","PAGE","PDFBOX_OCR","")))));
    }
    @Test void structureBudgetDoesNotDiscardSuccessfulNativeAndOcrTranscription()throws Exception{
        Path p=temp.resolve("bounded.pdf");try(PDDocument pdf=new PDDocument()){for(int i=0;i<30;i++)pdf.addPage(new PDPage());pdf.save(p.toFile());}
        String text="证".repeat(10000);List<OcrLayout.TextBox> boxes=new ArrayList<>();
        for(int i=0;i<5;i++)boxes.add(new OcrLayout.TextBox("b"+i,"f",text,new OcrLayout.Box(0,i*15,100,10),null));
        var structure=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,List.of(new OcrLayout.Frame("f",100,100,"a".repeat(64),"PDF_PAGE",1,null,null,null)),boxes,List.of(),boxes.stream().map(OcrLayout.TextBox::id).toList(),List.of());
        List<Page> parsed=new ArrayList<>();for(int i=1;i<=30;i++)parsed.add(new Page(i,text.repeat(5),"PAGE","PDFBOX_OCR","",structure));
        var result=new EvidenceLayoutService().layout(document(p,parsed),p);
        assertEquals("COMPLETE",result.get("status"));assertEquals(30,spans(result).stream().filter(s->"OCR_TEXT".equals(s.get("kind"))).count());
        assertEquals("PARTIAL",((Map<?,?>)result.get("structureBudget")).get("status"));assertFalse(((List<?>)((Map<?,?>)result.get("structureBudget")).get("omittedLocations")).isEmpty());
    }
    Path word(String body)throws IOException{Path path=temp.resolve("table.docx");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(path))){out.putNextEntry(new ZipEntry("word/document.xml"));out.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"+body+"</w:body></w:document>").getBytes(StandardCharsets.UTF_8));out.closeEntry();}return path;}
    @Test void wordCellsKeepMergeAndOoxmlPositionsWithoutFakePages()throws Exception{
        Path p=word("<w:p><w:r><w:t>开头段落</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge w:val=\"restart\"/></w:tcPr><w:p><w:r><w:t>公司甲</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge/></w:tcPr><w:p/></w:tc></w:tr></w:tbl><w:p><w:r><w:drawing><a:blip r:embed=\"rId7\"/></w:drawing></w:r></w:p>");
        var r=new EvidenceLayoutService().layout(document(p,List.of(new Page(1,"开头段落","PARAGRAPH","WORD_NATIVE",""))),p);assertEquals("COMPLETE",r.get("status"));var cells=spans(r).stream().filter(s->s.get("kind").equals("TABLE_CELL")).toList();assertEquals(2,cells.size());assertEquals(2,cells.get(0).get("columnSpan"));assertEquals(2,cells.get(0).get("rowSpan"));assertEquals(cells.get(0).get("id"),cells.get(1).get("mergeContinuationOf"));assertNull(cells.get(0).get("physicalPage"));assertNull(cells.get(0).get("bbox"));assertNotNull(cells.get(0).get("ooxml"));assertEquals(1,spans(r).get(0).get("documentBlock"));assertEquals("rId7",spans(r).stream().filter(s->s.get("kind").equals("EMBEDDED_IMAGE")).findFirst().orElseThrow().get("relationshipId"));
    }
    @Test void hashMismatchCannotProduceEvidenceForAnotherSource()throws Exception{Path p=pdf(0);Document d=document(p,List.of());Files.writeString(p,"different source");assertEquals("SOURCE_HASH_MISMATCH",new EvidenceLayoutService().layout(d,p).get("errorCode"));}
    @Test void wordDtdAndExternalEntitiesAreRejected()throws Exception{
        Path p=temp.resolve("unsafe.docx");try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(p))){z.putNextEntry(new ZipEntry("word/document.xml"));z.write("<!DOCTYPE x [<!ENTITY file SYSTEM 'file:///secret'>]><x>&file;</x>".getBytes(StandardCharsets.UTF_8));z.closeEntry();}assertEquals("ERROR",new EvidenceLayoutService().layout(document(p,List.of()),p).get("status"));
    }
    @Test void compressedPdfContentLimitFailsExplicitly()throws Exception{
        Path p=temp.resolve("large.pdf");try(PDDocument d=new PDDocument()){PDPage page=new PDPage();d.addPage(page);PDStream s=new PDStream(d);try(OutputStream out=s.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)){out.write(new byte[4_000_001]);}page.setContents(s);d.save(p.toFile());}
        assertEquals("PDF_EXPANSION_LIMIT",new EvidenceLayoutService().layout(document(p,List.of()),p).get("errorCode"));
    }
    @Test void wordGridBeforeAndRevisionAreNotSilentlyFlattened()throws Exception{
        Path p=word("<w:tbl><w:tr><w:trPr><w:gridBefore w:val=\"2\"/></w:trPr><w:tc><w:p><w:del><w:r><w:delText>历史删除字段</w:delText></w:r></w:del></w:p></w:tc></w:tr></w:tbl>");
        var r=new EvidenceLayoutService().layout(document(p,List.of()),p);var cell=spans(r).stream().filter(s->s.get("kind").equals("TABLE_CELL")).findFirst().orElseThrow();assertEquals(3,cell.get("column"));assertTrue(spans(r).stream().anyMatch(s->"CONTAINS_REVISIONS".equals(s.get("revision"))));
    }
    @Test void xmlNodeExpansionIsStoppedBeforeDomAllocation()throws Exception{
        Path p=word("<w:p/>".repeat(100_001));var r=new EvidenceLayoutService().layout(document(p,List.of()),p);assertEquals("ERROR",r.get("status"));assertTrue(Set.of("WORD_XML_STRUCTURE_LIMIT","WORD_ZIP_LIMIT").contains(r.get("errorCode")));
    }
}

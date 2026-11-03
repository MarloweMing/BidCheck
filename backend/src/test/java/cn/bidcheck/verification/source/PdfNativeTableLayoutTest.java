package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PdfNativeTableLayoutTest {
    @TempDir Path directory;
    Path fixture(boolean grid)throws Exception{Path path=directory.resolve("table.pdf");try(PDDocument pdf=new PDDocument()){PDPage page=new PDPage();pdf.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(pdf,page)){
        if(grid){for(int x:cn.bidcheck.compat.Java8Api.listOf(50, 150, 250)){stream.moveTo(x,550);stream.lineTo(x,650);}for(int y:cn.bidcheck.compat.Java8Api.listOf(550, 600, 650)){stream.moveTo(50,y);stream.lineTo(250,y);}stream.stroke();}
        for(java.lang.Object[] item:cn.bidcheck.compat.Java8Api.listOf(new Object[]{60f,620f,"NAME"}, new Object[]{160f,620f,"ALPHA"}, new Object[]{60f,570f,"ID"}, new Object[]{160f,570f,"123456"})){
            stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);stream.newLineAtOffset((Float)item[0],(Float)item[1]);stream.showText((String)item[2]);stream.endText();}
        }pdf.save(path.toFile());}return path;}
    Document document(Path path)throws Exception{String text;try(PDDocument pdf=Loader.loadPDF(path.toFile())){PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);text=stripper.getText(pdf);}return new Document("test","project","BID","table.pdf",1,SourceSafety.digest(Files.readAllBytes(path)),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDFBOX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());}
    @Test void realClosedLinesProduceSourceBoundCellsAndNoCrossCellText()throws Exception{
        Path path=fixture(true);Document document=document(path);java.util.Map<java.lang.String, java.lang.Object> value=new EvidenceLayoutService().nativePdfLayouts(document,()->path).apply(1);
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> cells=(List<Map<String,Object>>)value.get("spans");assertEquals(4,cells.size(),value.toString());assertTrue(cells.stream().anyMatch(c->"ALPHA".equals(c.get("text"))));assertFalse(cells.stream().anyMatch(c->c.get("text").toString().contains("NAMEALPHA")));
        for(java.util.Map<java.lang.String, java.lang.Object> cell:cells)for(java.util.Map<java.lang.String, java.lang.Object> fragment:(List<Map<String,Object>>)cell.get("sourceFragments"))assertEquals(fragment.get("text"),document.pages().get(0).text().substring(((Number)fragment.get("start")).intValue(),((Number)fragment.get("end")).intValue()));
    }
    @Test void plainAlignedWordsCannotInventTableCells()throws Exception{Path path=fixture(false);cn.bidcheck.domain.Domain.Document document=document(path);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceLayoutService().nativePdfLayouts(document,()->path).apply(1).get("spans"));}
    @Test void changedOriginalInvalidatesPreviouslyCachedPage()throws Exception{Path path=fixture(true);cn.bidcheck.domain.Domain.Document document=document(path);java.util.function.Function<java.lang.Integer, java.util.Map<java.lang.String, java.lang.Object>> lookup=new EvidenceLayoutService().nativePdfLayouts(document,()->path);assertFalse(((List<?>)lookup.apply(1).get("spans")).isEmpty());cn.bidcheck.compat.Java8Api.writeString(path, "changed source");java.util.Map<java.lang.String, java.lang.Object> changed=lookup.apply(1);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),changed.get("spans"));assertTrue(changed.toString().contains("SOURCE_CHANGED"));}
    @Test void changedStoredTranscriptionCannotBorrowNativeValues()throws Exception{Path path=fixture(true);cn.bidcheck.domain.Domain.Document original=document(path);cn.bidcheck.domain.Domain.Document changed=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"Different frozen text","PAGE","PDFBOX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());java.util.Map<java.lang.String, java.lang.Object> result=new EvidenceLayoutService().nativePdfLayouts(changed,()->path).apply(1);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("spans"));assertTrue(result.toString().contains("SOURCE_ALIGNMENT_REQUIRED"));}
}

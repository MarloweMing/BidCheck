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
        if(grid){for(int x:List.of(50,150,250)){stream.moveTo(x,550);stream.lineTo(x,650);}for(int y:List.of(550,600,650)){stream.moveTo(50,y);stream.lineTo(250,y);}stream.stroke();}
        for(var item:List.of(new Object[]{60f,620f,"NAME"},new Object[]{160f,620f,"ALPHA"},new Object[]{60f,570f,"ID"},new Object[]{160f,570f,"123456"})){
            stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);stream.newLineAtOffset((Float)item[0],(Float)item[1]);stream.showText((String)item[2]);stream.endText();}
        }pdf.save(path.toFile());}return path;}
    Document document(Path path)throws Exception{String text;try(PDDocument pdf=Loader.loadPDF(path.toFile())){PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);text=stripper.getText(pdf);}return new Document("test","project","BID","table.pdf",1,SourceSafety.digest(Files.readAllBytes(path)),"",List.of(new Page(1,text,"PAGE","PDFBOX_NATIVE","")),List.of());}
    @Test void realClosedLinesProduceSourceBoundCellsAndNoCrossCellText()throws Exception{
        Path path=fixture(true);Document document=document(path);var value=new EvidenceLayoutService().nativePdfLayouts(document,()->path).apply(1);
        var cells=(List<Map<String,Object>>)value.get("spans");assertEquals(4,cells.size(),value.toString());assertTrue(cells.stream().anyMatch(c->"ALPHA".equals(c.get("text"))));assertFalse(cells.stream().anyMatch(c->c.get("text").toString().contains("NAMEALPHA")));
        for(var cell:cells)for(var fragment:(List<Map<String,Object>>)cell.get("sourceFragments"))assertEquals(fragment.get("text"),document.pages().get(0).text().substring(((Number)fragment.get("start")).intValue(),((Number)fragment.get("end")).intValue()));
    }
    @Test void plainAlignedWordsCannotInventTableCells()throws Exception{Path path=fixture(false);var document=document(path);assertEquals(List.of(),new EvidenceLayoutService().nativePdfLayouts(document,()->path).apply(1).get("spans"));}
    @Test void changedOriginalInvalidatesPreviouslyCachedPage()throws Exception{Path path=fixture(true);var document=document(path);var lookup=new EvidenceLayoutService().nativePdfLayouts(document,()->path);assertFalse(((List<?>)lookup.apply(1).get("spans")).isEmpty());Files.writeString(path,"changed source");var changed=lookup.apply(1);assertEquals(List.of(),changed.get("spans"));assertTrue(changed.toString().contains("SOURCE_CHANGED"));}
    @Test void changedStoredTranscriptionCannotBorrowNativeValues()throws Exception{Path path=fixture(true);var original=document(path);var changed=new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),"",List.of(new Page(1,"Different frozen text","PAGE","PDFBOX_NATIVE","")),List.of());var result=new EvidenceLayoutService().nativePdfLayouts(changed,()->path).apply(1);assertEquals(List.of(),result.get("spans"));assertTrue(result.toString().contains("SOURCE_ALIGNMENT_REQUIRED"));}
}

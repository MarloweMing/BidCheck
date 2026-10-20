package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.OcrLayout;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredLayoutAdapterTest {
    private static final String TEXT="姓名 证号 张三 123";
    private OcrLayout layout(){
        var boxes=List.of(new OcrLayout.TextBox("b1","f","姓名",new OcrLayout.Box(0,0,45,15),null),new OcrLayout.TextBox("b2","f","证号",new OcrLayout.Box(50,0,45,15),null),
            new OcrLayout.TextBox("b3","f","张三",new OcrLayout.Box(0,20,45,15),.9),new OcrLayout.TextBox("b4","f","123",new OcrLayout.Box(50,20,45,15),.9));
        var cells=List.of(new OcrLayout.Cell("c1",0,0,1,1,"姓名",boxes.get(0).bbox(),List.of("b1"),List.of(),true),new OcrLayout.Cell("c2",0,1,1,1,"证号",boxes.get(1).bbox(),List.of("b2"),List.of(),true),
            new OcrLayout.Cell("c3",1,0,1,1,"张三",boxes.get(2).bbox(),List.of("b3"),List.of("c1"),false),new OcrLayout.Cell("c4",1,1,1,1,"123",boxes.get(3).bbox(),List.of("b4"),List.of("c2"),false));
        return new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,List.of(new OcrLayout.Frame("f",100,100,"b".repeat(64),"PDF_PAGE",1,null,null,null)),boxes,List.of(new OcrLayout.Table("t","f",2,2,cells)),List.of("b1","b2","b3","b4"),List.of());
    }
    private Document doc(Page p,int version){return new Document("doc","project","BID","source.pdf",version,"a".repeat(64),"",List.of(p),List.of());}
    @Test void realHeadersAndGeometrySurviveWithoutBecomingNativeEvidence(){
        Page p=new Page(2,TEXT,"PAGE","PDFBOX_OCR","",layout());var spans=StructuredLayoutAdapter.spans(doc(p,1),p,new ArrayList<>());
        assertEquals(8,spans.size());var cells=spans.stream().filter(s->s.get("kind").equals("TABLE_CELL")).toList();var number=cells.get(3);
        assertEquals(2,number.get("row"));assertEquals(2,number.get("column"));assertEquals("证号",number.get("headerText"));assertEquals(List.of(cells.get(1).get("id")),number.get("headerCellIds"));
        assertEquals("OCR_STRUCTURED",number.get("source"));assertEquals("OCR_CANDIDATE",number.get("structureTrust"));assertEquals(List.of(50d,20d,95d,35d),number.get("bbox"));assertEquals(2,number.get("physicalPage"));assertEquals("b".repeat(64),number.get("imageHash"));
    }
    @Test void wordImagesKeepBlockNamespaceAndIndependentFrames(){
        Page p=new Page(8,TEXT,"PARAGRAPH","WORD_WITH_OCR","",layout().source("word_","WORD_IMAGE",2));var span=StructuredLayoutAdapter.spans(doc(p,1),p,new ArrayList<>()).get(0);
        assertNull(span.get("physicalPage"));assertEquals(8,span.get("documentBlock"));assertEquals("PARAGRAPH",span.get("locationType"));assertEquals(2,span.get("imageSourceIndex"));
    }
    @Test void storedGeometryIsRevalidatedAgainstCurrentTranscription(){
        Page p=new Page(2,"另一份原文，没有这些表格字段","PAGE","PDFBOX_OCR","",layout());List<String> notes=new ArrayList<>();assertTrue(StructuredLayoutAdapter.spans(doc(p,1),p,notes).isEmpty());assertFalse(notes.isEmpty());
    }
    @Test void spanIdsCannotCrossPagesOrDocumentVersions(){
        Page p=new Page(2,TEXT,"PAGE","PDFBOX_OCR","",layout()),other=new Page(3,TEXT,"PAGE","PDFBOX_OCR","",layout());Object id=StructuredLayoutAdapter.spans(doc(p,1),p,new ArrayList<>()).get(0).get("id");
        assertNotEquals(id,StructuredLayoutAdapter.spans(doc(other,1),other,new ArrayList<>()).get(0).get("id"));assertNotEquals(id,StructuredLayoutAdapter.spans(doc(p,2),p,new ArrayList<>()).get(0).get("id"));
    }
    @Test void oldTranscriptionDoesNotAcquireInventedGeometry(){Page p=new Page(2,TEXT,"PAGE","PDFBOX_OCR","");assertTrue(StructuredLayoutAdapter.spans(doc(p,1),p,new ArrayList<>()).isEmpty());}
}

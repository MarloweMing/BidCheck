package cn.bidcheck.ocr;

import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OcrLayoutTest {
    private static final String TEXT = "姓名 张三";
    private static OcrLayout.Box box(double x) { return new OcrLayout.Box(x, 0, 10, 10); }
    private static OcrLayout valid() {
        return new OcrLayout(OcrLayout.VERSION, "COMPLETE", OcrLayout.COORDINATES,
                List.of(new OcrLayout.Frame("image", 30, 20, "a".repeat(64), "SUBMITTED_IMAGE", 0, null, null, null)),
                List.of(new OcrLayout.TextBox("b1", "image", "姓名", box(0), null), new OcrLayout.TextBox("b2", "image", "张三", box(10), .8)),
                List.of(new OcrLayout.Table("t1", "image", 1, 2, List.of(
                        new OcrLayout.Cell("c1", 0, 0, 1, 1, "姓名", box(0), List.of("b1"), List.of(), true),
                        new OcrLayout.Cell("c2", 0, 1, 1, 1, "张三", box(10), List.of("b2"), List.of("c1"), false)))), List.of("b1", "b2"), List.of());
    }
    private static OcrLayout blocks(List<OcrLayout.TextBox> boxes, List<String> order) {
        var v=valid(); return new OcrLayout(v.schemaVersion(),v.status(),v.coordinateSystem(),v.frames(),boxes,List.of(),order,List.of());
    }
    @Test void validCellHeaderAndOriginalCharactersSurviveSerialization() throws Exception {
        var layout = valid().validated(TEXT);
        assertEquals("COMPLETE", layout.status()); assertEquals(List.of("c1"), layout.tables().get(0).cells().get(1).headerCellIds());
        assertNull(layout.textBoxes().get(0).confidence()); assertEquals(.8,layout.textBoxes().get(1).confidence());
        var json=new ObjectMapper(); assertEquals(layout,json.readValue(json.writeValueAsBytes(layout),OcrLayout.class).validated(TEXT));
        assertEquals("ocr-layout-1",layout.toMap().get("schemaVersion"));
    }
    @Test void legacyPageAndResultRemainCompatible() throws Exception {
        var old=new ObjectMapper().readValue("{\"number\":1,\"text\":\"正文\",\"locationType\":\"PAGE\",\"parser\":\"PDFBOX_NATIVE\",\"warning\":\"\"}",Page.class);
        assertNull(old.layout()); assertNull(new Page(1,"正文","PAGE","PDFBOX_NATIVE","").layout());
        assertNull(new OcrResult("正文","test","model",false).layout());
    }
    @Test void incompleteCoverageCannotAdvertiseComplete() {
        assertEquals("PARTIAL",valid().validated(TEXT+"其他条款").status());
    }
    @Test void rejectsFakeTextAndDuplicateOccurrencesNotInTranscription() {
        assertThrows(IllegalArgumentException.class,()->valid().validated("姓名 李四"));
        var boxes=List.of(new OcrLayout.TextBox("a","image","张三",box(0),null),new OcrLayout.TextBox("b","image","张三",box(10),null));
        assertThrows(IllegalArgumentException.class,()->blocks(boxes,List.of("a","b")).validated("姓名 张三"));
    }
    @Test void rejectsNonfiniteNegativeAndOutsideImageCoordinates() {
        for(var b:List.of(new OcrLayout.Box(Double.NaN,0,1,1),new OcrLayout.Box(-1,0,1,1),new OcrLayout.Box(29,0,2,1),new OcrLayout.Box(0,0,0,1)))
            assertThrows(IllegalArgumentException.class,()->blocks(List.of(new OcrLayout.TextBox("a","image","姓名",b,null)),List.of("a")).validated(TEXT));
    }
    @Test void rejectsDuplicateIdsUnknownFrameAndBrokenReadingOrder() {
        assertThrows(IllegalArgumentException.class,()->blocks(List.of(valid().textBoxes().get(0),valid().textBoxes().get(0)),List.of("b1")).validated(TEXT));
        assertThrows(IllegalArgumentException.class,()->blocks(List.of(new OcrLayout.TextBox("a","other","姓名",box(0),null)),List.of("a")).validated(TEXT));
        assertThrows(IllegalArgumentException.class,()->blocks(valid().textBoxes(),List.of("b2","b1")).validated(TEXT));
        assertThrows(IllegalArgumentException.class,()->blocks(valid().textBoxes(),List.of("b1")).validated(TEXT));
    }
    @Test void rejectsForgedHeadersSpansAndCellText() {
        var v=valid(); var c=v.tables().get(0).cells().get(0);
        for(var changed:List.of(
                new OcrLayout.Cell("c2",0,1,1,1,"张三",box(10),List.of("b2"),List.of("unknown"),false),
                new OcrLayout.Cell("c2",0,1,1,2,"张三",box(10),List.of("b2"),List.of("c1"),false),
                new OcrLayout.Cell("c2",0,1,1,1,"李四",box(10),List.of("b2"),List.of("c1"),false))) {
            var value=new OcrLayout(v.schemaVersion(),v.status(),v.coordinateSystem(),v.frames(),v.textBoxes(),List.of(new OcrLayout.Table("t1","image",1,2,List.of(c,changed))),v.readingOrder(),List.of());
            assertThrows(IllegalArgumentException.class,()->value.validated(TEXT));
        }
    }
    @Test void sourceIsolationAndCropsRetainLocalCoordinatesWithoutInventedJoin() {
        var first=valid().source("i1_","WORD_EMBEDDED",1); var second=valid().source("i2_","WORD_EMBEDDED",2);
        var merged=OcrLayout.merge(List.of(first,second),true,TEXT+" "+TEXT);
        assertEquals(2,merged.frames().size()); assertEquals("PARTIAL",merged.status()); assertEquals("i2_c1",merged.tables().get(1).cells().get(1).headerCellIds().get(0));
        var cropped=valid().crop(30,40,new OcrLayout.Box(0,10,30,20)).validated(TEXT);
        assertEquals(box(0),cropped.textBoxes().get(0).bbox()); assertEquals(10,cropped.frames().get(0).crop().y()); assertEquals("PARTIAL",cropped.status());
    }
    @Test void excessiveLayoutAndInvalidConfidenceAreRejected() {
        assertThrows(IllegalArgumentException.class,()->blocks(Collections.nCopies(OcrLayout.MAX_BOXES+1,valid().textBoxes().get(0)),List.of()).validated(TEXT));
        assertThrows(IllegalArgumentException.class,()->blocks(List.of(new OcrLayout.TextBox("b1","image","姓名",box(0),1.1)),List.of("b1")).validated(TEXT));
    }
    @Test void logicalRowsCannotMergePhysicallyDifferentRowsOrReverseColumns() {
        var v=valid();
        for(boolean differentRow:List.of(true,false)) {
            var b2=differentRow?new OcrLayout.Box(10,10,10,10):box(10);
            var boxes=List.of(v.textBoxes().get(0),new OcrLayout.TextBox("b2","image","张三",b2,null));
            var cells=List.of(new OcrLayout.Cell("c1",0,differentRow?0:1,1,1,"姓名",box(0),List.of("b1"),List.of(),true),
                    new OcrLayout.Cell("c2",0,differentRow?1:0,1,1,"张三",b2,List.of("b2"),List.of(),false));
            var value=new OcrLayout(v.schemaVersion(),v.status(),v.coordinateSystem(),v.frames(),boxes,List.of(new OcrLayout.Table("t1","image",1,2,cells)),v.readingOrder(),List.of());
            var failure=assertThrows(IllegalArgumentException.class,()->value.validated(TEXT)); assertTrue(failure.getMessage().contains("几何"));
        }
    }
    @Test void unrelatedHeaderInDifferentColumnCannotBindOtherCell() {
        var boxes=List.of(new OcrLayout.TextBox("b1","image","姓名",box(0),null),new OcrLayout.TextBox("b2","image","张三",new OcrLayout.Box(10,10,10,10),null));
        var cells=List.of(new OcrLayout.Cell("c1",0,0,1,1,"姓名",box(0),List.of("b1"),List.of(),true),
                new OcrLayout.Cell("c2",1,1,1,1,"张三",new OcrLayout.Box(10,10,10,10),List.of("b2"),List.of("c1"),false));
        var v=valid();var value=new OcrLayout(v.schemaVersion(),v.status(),v.coordinateSystem(),v.frames(),boxes,List.of(new OcrLayout.Table("t1","image",2,2,cells)),v.readingOrder(),List.of());
        assertThrows(IllegalArgumentException.class,()->value.validated(TEXT));
    }
}

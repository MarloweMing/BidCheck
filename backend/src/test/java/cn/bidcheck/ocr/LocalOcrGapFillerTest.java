package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.ocr.OcrLayout.*;
import static cn.bidcheck.ocr.GridLayoutAssembler.*;

class LocalOcrGapFillerTest {
    private final LocalOcrGapFiller filler=new LocalOcrGapFiller();
    private Frame frame(String id,String hash){return new Frame(id,200,100,hash,"SUBMITTED_IMAGE",0,null,null,null);}
    private TextBox box(String id,String frame,String text,double x,double y,double width,double height,Double confidence,String source){return new TextBox(id,frame,text,new Box(x,y,width,height),confidence,source);}
    private OcrLayout layout(Frame frame,TextBox... boxes){return new OcrLayout(OcrLayout.VERSION,"COMPLETE",COORDINATES,List.of(frame),List.of(boxes),List.of(),Arrays.stream(boxes).map(TextBox::id).toList(),List.of());}
    private OcrResult nativeResult(){return new OcrResult("原生甲","DASHSCOPE","native-model",true,layout(frame("native","a".repeat(64)),box("n1","native","原生甲",10,10,25,10,null,"NATIVE_OCR")));}
    private OcrLayout local(TextBox... boxes){return layout(frame("local","a".repeat(64)),boxes);}
    private TextBox addition(){return box("l1","local","经济类型",120,10,40,10,.95,"RAPIDOCR");}
    private Grid grid(){return new Grid("native",PIXEL_XYXY,1,2,List.of(new Region(0,0,1,1,List.of(0d,0d,100d,50d),true),new Region(0,1,1,1,List.of(100d,0d,200d,50d),true)));}
    private OcrResult crossing(){return new OcrResult("完整原文","DASHSCOPE","native-model",false,layout(frame("native","a".repeat(64)),box("cross","native","完整原文",90,10,30,10,null,"NATIVE_OCR")));}
    private OcrLayout crop(TextBox... boxes){return layout(new Frame("local",200,100,"a".repeat(64),"CELL_CROP_MAPPED",0,null,null,null),boxes);}

    @Test void fillsOnlyEmptyCellPreservesNativeCoverageAndSourceWithoutClaimingIndependentVerification() {
        var source=nativeResult();var filled=filler.fill(source,local(addition()),List.of(grid()));
        assertEquals("原生甲\n经济类型",filled.text());assertEquals("DASHSCOPE+RAPIDOCR",filled.provider());assertEquals("native-model",filled.model());assertFalse(filled.cached());assertEquals("COMPLETE",filled.layout().status());
        assertSame(source.layout().textBoxes().get(0),filled.layout().textBoxes().get(0));var added=filled.layout().textBoxes().get(1);assertTrue(added.id().startsWith("local_"));assertEquals("RAPIDOCR",added.source());assertEquals("native",added.frameId());assertEquals(addition().bbox(),added.bbox());
        assertEquals(List.of("n1",added.id()),filled.layout().readingOrder());assertTrue(filled.layout().warnings().get(0).contains("不是独立核验"));assertEquals(filled.layout(),filled.layout().validated(filled.text()));
        assertSame(filled,filler.fill(filled,local(addition()),List.of(grid())),"The same local source must not be appended twice");
    }
    @Test void differentImageHashDimensionsOrMultipleFramesAreNeverCombined() {
        var source=nativeResult();assertSame(source,filler.fill(source,layout(frame("local","b".repeat(64)),addition()),List.of(grid())));
        var wrongSize=new Frame("local",201,100,"a".repeat(64),"SUBMITTED_IMAGE",0,null,null,null);assertSame(source,filler.fill(source,layout(wrongSize,addition()),List.of(grid())));
        var multi=new OcrLayout(OcrLayout.VERSION,"COMPLETE",COORDINATES,List.of(frame("local","a".repeat(64)),frame("other","a".repeat(64))),List.of(addition()),List.of(),List.of("l1"),List.of());assertSame(source,filler.fill(source,multi,List.of(grid())));
    }
    @Test void rejectsCrossingCellOutsideGridAndPartiallyOccupiedCellEvenWithoutDirectOverlap() {
        var source=nativeResult();for(TextBox b:List.of(box("l","local","跨格",90,10,30,10,.99,null),box("l","local","格外",120,60,30,10,.99,null),box("l","local","同格空隙",50,10,30,10,.99,null),box("l","local","碰原文",20,10,30,10,.99,null)))assertSame(source,filler.fill(source,local(b),List.of(grid())));
    }
    @Test void lowMissingConfidenceAndFalseSourceAreNotEligible() {
        var source=nativeResult();for(Double confidence:Arrays.asList(.799,null))assertSame(source,filler.fill(source,local(box("l","local","补字",120,10,40,10,confidence,"RAPIDOCR")),List.of(grid())));
        assertSame(source,filler.fill(source,local(box("l","local","补字",120,10,40,10,.99,"NATIVE_OCR")),List.of(grid())));
        assertEquals(2,filler.fill(source,local(box("l","local","补字",120,10,40,10,.8,null)),List.of(grid())).layout().textBoxes().size());
    }
    @Test void duplicateOrOverlappingLocalDetectionsAreBothRejected() {
        var source=nativeResult();var second=box("l2","local","另一个结果",120,10,40,10,.99,"RAPIDOCR");assertSame(source,filler.fill(source,local(addition(),second),List.of(grid())));
        var partial=box("l2","local","另一个结果",140,10,40,10,.99,"RAPIDOCR");assertSame(source,filler.fill(source,local(addition(),partial),List.of(grid())));
    }
    @Test void twoDisjointLocalLinesCanFillOneEmptyCellOnceAndKeepReturnedOrder() {
        var source=nativeResult();var a=box("l1","local","经济",120,20,30,10,.91,null);var b=box("l2","local","类型",120,5,30,10,.92,null);
        var result=filler.fill(source,local(a,b),List.of(grid()));assertEquals("原生甲\n经济\n类型",result.text());assertEquals(3,result.layout().textBoxes().size());
    }
    @Test void competingGridInterpretationsDoNotSelectFirstOne() {
        var source=nativeResult();assertSame(source,filler.fill(source,local(addition()),List.of(grid(),grid())));
        var one=new Grid("native",PIXEL_XYXY,1,1,List.of(new Region(0,0,1,1,List.of(90d,0d,200d,50d),true)));assertSame(source,filler.fill(source,local(addition()),List.of(grid(),one)));
    }
    @Test void invalidLogicalGeometricAndFrameGridsAreRejected() {
        var source=nativeResult();var partial=new Grid("native",PIXEL_XYXY,1,2,List.of(new Region(0,1,1,1,List.of(100d,0d,200d,50d),true)));assertSame(source,filler.fill(source,local(addition()),List.of(partial)));
        var reverse=new Grid("native",PIXEL_XYXY,1,2,List.of(new Region(0,0,1,1,List.of(100d,0d,200d,50d),true),new Region(0,1,1,1,List.of(0d,0d,100d,50d),true)));assertSame(source,filler.fill(source,local(addition()),List.of(reverse)));
        var unknown=new Grid("other",PIXEL_XYXY,1,2,grid().cells());assertSame(source,filler.fill(source,local(addition()),List.of(unknown)));
    }
    @Test void normalizedGridUsesExplicitContractAndSameImageMapping() {
        var g=new Grid("native",NORMALIZED_1000_XYXY,1,2,List.of(new Region(0,0,1,1,List.of(0d,0d,500d,500d),true),new Region(0,1,1,1,List.of(500d,0d,1000d,500d),true)));
        assertEquals("原生甲\n经济类型",filler.fill(nativeResult(),local(addition()),List.of(g)).text());
    }
    @Test void malformedLocalReferencesAndNoValidFillLeaveOriginalObjectIntact() {
        var source=nativeResult();assertSame(source,filler.fill(source,null,List.of(grid())));assertSame(source,filler.fill(source,local(addition()),List.of()));
        var invalid=new OcrLayout(OcrLayout.VERSION,"COMPLETE",COORDINATES,List.of(frame("local","a".repeat(64))),List.of(addition()),List.of(),List.of("not-a-box"),List.of());assertSame(source,filler.fill(source,invalid,List.of(grid())));
        assertSame(source,filler.fill(source,local(addition(),addition()),List.of(grid())));
    }
    @Test void sourceProvenanceSurvivesSerializationAndRenamingWhileOldRecordsStillLoad() throws Exception {
        var json=new ObjectMapper();var legacy=new TextBox("b","local","旧记录",new Box(1,1,10,10),.9);assertNull(legacy.source());
        var old=json.readValue("{\"id\":\"b\",\"frameId\":\"local\",\"text\":\"旧记录\",\"bbox\":{\"x\":1,\"y\":1,\"width\":10,\"height\":10},\"confidence\":0.9}",TextBox.class);assertNull(old.source());
        var result=filler.fill(nativeResult(),local(addition()),List.of(grid()));var reloaded=json.readValue(json.writeValueAsBytes(result.layout()),OcrLayout.class).validated(result.text());
        assertEquals("NATIVE_OCR",reloaded.source("p1_","PDF_RENDER",1).textBoxes().get(0).source());assertEquals("RAPIDOCR",reloaded.source("p1_","PDF_RENDER",1).textBoxes().get(1).source());
        assertThrows(IllegalArgumentException.class,()->layout(frame("local","a".repeat(64)),box("b","local","假来源",1,1,10,10,.9,"VERIFIED")).validated("假来源"));
    }
    @Test void cropExactTextCanArbitrateCrossingGeometryWithoutChangingTextIdOrOrder() throws Exception {
        var source=crossing();var observed=box("crop1","local","完整 原文",100,10,20,10,.95,"RAPIDOCR");var result=filler.fill(source,crop(observed),List.of(grid()));
        assertEquals(source.text(),result.text());assertEquals(source.layout().readingOrder(),result.layout().readingOrder());assertEquals(1,result.layout().textBoxes().size());
        var corrected=result.layout().textBoxes().get(0);assertEquals("cross",corrected.id());assertEquals("完整原文",corrected.text());assertEquals(observed.bbox(),corrected.bbox());assertEquals(source.layout().textBoxes().get(0).bbox(),corrected.originalBbox());assertEquals("CONSENSUS_OCR",corrected.source());assertEquals("COMPLETE",result.layout().status());
        var json=new ObjectMapper();var reloaded=json.readValue(json.writeValueAsBytes(result.layout()),OcrLayout.class).validated(result.text()).source("p1_","PDF_RENDER",1);assertEquals(corrected.originalBbox(),reloaded.textBoxes().get(0).originalBbox());
        assertSame(result,filler.fill(result,crop(observed),List.of(grid())));
    }
    @Test void wholePageLocalOcrNeverArbitratesAndNativeWithinCellNeverMoves() {
        var source=crossing();var observed=box("crop1","local","完整原文",100,10,20,10,.95,"RAPIDOCR");assertSame(source,filler.fill(source,local(observed),List.of(grid())));
        var intact=nativeResult();assertSame(intact,filler.fill(intact,crop(box("crop1","local","原生甲",12,10,23,10,.95,"RAPIDOCR")),List.of(grid())));
    }
    @Test void cropPartialMergedOrWeakOverlapObservationsNeverRepairBySplitting() {
        var source=crossing();for(TextBox observed:List.of(box("l","local","完整",100,10,20,10,.99,null),box("l","local","完整原文多了字",100,10,20,10,.99,null),box("l","local","完整原文",100,10,10,10,.99,null),box("l","local","完整原文",112,10,20,10,.99,null),box("l","local","完整原文",95,10,20,10,.99,null)))assertSame(source,filler.fill(source,crop(observed),List.of(grid())));
    }
    @Test void ambiguousCropAndOtherNativeOverlapPreventArbitration() {
        var source=crossing();var a=box("a","local","完整原文",100,10,20,10,.95,null);var b=box("b","local","完整原文",100,10,20,10,.96,null);assertSame(source,filler.fill(source,crop(a,b),List.of(grid())));
        var other=box("other","native","另一原框",105,12,10,5,null,"NATIVE_OCR");var nativeWithConflict=new OcrResult("完整原文\n另一原框","DASHSCOPE","model",false,layout(frame("native","a".repeat(64)),source.layout().textBoxes().get(0),other));assertSame(nativeWithConflict,filler.fill(nativeWithConflict,crop(a),List.of(grid())));
    }
    @Test void equalDatesElsewhereDoNotDefeatUniqueSpatialMatch() {
        var cross=box("cross","native","2025-01-01",90,10,30,10,null,"NATIVE_OCR");var other=box("other","native","2025-01-01",10,30,40,10,null,"NATIVE_OCR");
        var source=new OcrResult("2025-01-01\n2025-01-01","DASHSCOPE","model",false,layout(frame("native","a".repeat(64)),cross,other));
        var corrected=filler.fill(source,crop(box("crop1","local","2025-01-01",100,10,20,10,.95,null),box("crop2","local","2025-01-01",10,30,40,10,.95,null)),List.of(grid()));
        assertEquals("CONSENSUS_OCR",corrected.layout().textBoxes().get(0).source());assertSame(other,corrected.layout().textBoxes().get(1));assertEquals(source.text(),corrected.text());
    }
    @Test void consensusRequiresOriginalInBoundsAndOtherSourcesCannotClaimOriginalGeometry() {
        var f=frame("native","a".repeat(64));var valid=new Box(1,1,10,10);
        assertThrows(IllegalArgumentException.class,()->layout(f,new TextBox("b","native","甲",valid,null,"CONSENSUS_OCR")).validated("甲"));
        assertThrows(IllegalArgumentException.class,()->layout(f,new TextBox("b","native","甲",valid,null,"CONSENSUS_OCR",new Box(-1,1,10,10))).validated("甲"));
        assertThrows(IllegalArgumentException.class,()->layout(f,new TextBox("b","native","甲",valid,null,"NATIVE_OCR",valid)).validated("甲"));
    }
}

package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PixelGridClientTest {
    @TempDir Path temp;
    ObjectMapper json=new ObjectMapper();
    PixelGridClient client;
    OcrLayout.Frame frame=new OcrLayout.Frame("native-image",100,80,"a".repeat(64),"IMAGE",0,null,null,null);
    @BeforeEach void setup(){client=new PixelGridClient(false,"","",temp.toString(),1,2);}
    @AfterEach void close(){client.close();Thread.interrupted();}
    ObjectNode response() throws Exception{return (ObjectNode)json.readTree("""
        {"id":"request-1","status":"OK","schemaVersion":"local-ocr-grid-1","coordinateSystem":"PIXEL_TOP_LEFT",
        "width":100,"height":80,"imageHash":"HASH","gridCandidates":[{"frameId":"f1",
        "coordinateSpace":"PIXEL_XYXY","rows":1,"columns":1,"cells":[{"row":0,"column":0,
        "rowSpan":1,"columnSpan":1,"box":[1,2,90,70],"header":false}]}]}
        """.replace("HASH",frame.imageHash()));}
    @Test void bindsWorkerLocalFrameToActualNativeOcrImage()throws Exception{
        var grids=client.parse(response(),frame,"request-1");assertEquals("native-image",grids.get(0).frameId());assertEquals(90,grids.get(0).cells().get(0).box().get(2));
        assertEquals("PIXEL_XYXY",grids.get(0).coordinateSpace());
    }
    @Test void rejectsDifferentImageDimensionsHashRequestAndCoordinateSpace()throws Exception{
        for(String key:List.of("width","height","imageHash","id","schemaVersion","coordinateSystem")){
            var root=response();if(key.equals("width")||key.equals("height"))root.put(key,999);else root.put(key,"other");
            assertThrows(IOException.class,()->client.parse(root,frame,"request-1"),key);
        }
        var normalized=response();((ObjectNode)normalized.at("/gridCandidates/0")).put("coordinateSpace","NORMALIZED_1000_XYXY");assertThrows(IOException.class,()->client.parse(normalized,frame,"request-1"));
    }
    @Test void rejectsSilentIntegerOrBooleanCoercionAndWorkerErrors()throws Exception{
        var root=response();((ObjectNode)root.at("/gridCandidates/0")).put("rows",1.2);assertThrows(IOException.class,()->client.parse(root,frame,null));
        var second=response();((ObjectNode)second.at("/gridCandidates/0/cells/0")).put("header","false");assertThrows(IOException.class,()->client.parse(second,frame,null));
        var failed=response().put("error","private raw details");var e=assertThrows(IOException.class,()->client.parse(failed,frame,null));assertFalse(e.getMessage().contains("private"));
    }
    @Test void refusesUnknownFrameAndExcessiveGridCount()throws Exception{
        var unknown=response();((ObjectNode)unknown.at("/gridCandidates/0")).put("frameId","f2");assertThrows(IOException.class,()->client.parse(unknown,frame,null));
        var root=response();var g=root.at("/gridCandidates/0").deepCopy();var arr=root.putArray("gridCandidates");for(int i=0;i<41;i++)arr.add(g);assertThrows(IOException.class,()->client.parse(root,frame,null));
    }
    @Test void missingRuntimeIsDisabledAndCancellationPropagates()throws Exception{
        assertFalse(client.enabled());assertThrows(IOException.class,()->client.detect(new byte[]{1},frame));
        Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->client.detect(new byte[]{1},frame));assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void nativePipelineFallsBackWithoutCallingAdvancedWhenGridWorkerMissing()throws Exception{
        var advanced=new OcrClient(){public boolean enabled(){return true;}public String identity(){return "new";}public String modelName(){return "ocr";}public OcrResult recognize(byte[] b,String m){fail("must not pay for layout when worker unavailable");return null;}};
        var base=new OcrClient(){public boolean enabled(){return true;}public String identity(){return "old";}public String modelName(){return "ocr";}public OcrResult recognize(byte[] b,String m){return new OcrResult("原文","mock","ocr",true);}};
        var pipeline=new StructuredOcrClient(base,advanced,client);var r=pipeline.recognize(new byte[]{1},"image/png");assertEquals("原文",r.text());assertEquals("UNAVAILABLE",r.layout().status());assertFalse(pipeline.layoutEnabled());
    }
    ObjectNode cropResponse()throws Exception{
        var root=response();var b=root.putArray("textBoxes").addObject();b.put("text","姓名");b.put("confidence",.99);b.putObject("bbox").put("x",10).put("y",10).put("width",20).put("height",10);
        var region=b.putObject("sourceRegion");region.put("id","c1");region.putArray("box").add(1).add(2).add(90).add(70);region.put("scale",2).put("scaleX",2).put("scaleY",2).put("inputWidth",178).put("inputHeight",136);return root;
    }
    List<Map<String,Object>> cropRegions(){return List.of(Map.of("id","c1","box",List.of(1.0,2.0,90.0,70.0)));}
    @Test void cropOcrRequiresActualRequestedRegionAndMeasuredScale()throws Exception{
        var root=cropResponse();var layout=client.parseLocal(root,frame,cropRegions());assertEquals("CELL_CROP_MAPPED",layout.frames().get(0).sourceKind());assertEquals("RAPIDOCR",layout.textBoxes().get(0).source());
        ((ObjectNode)root.at("/textBoxes/0/sourceRegion")).put("scaleX",3);assertThrows(IOException.class,()->client.parseLocal(root,frame,cropRegions()));
        var unknown=cropResponse();((ObjectNode)unknown.at("/textBoxes/0/sourceRegion")).put("id","unrequested");assertThrows(IOException.class,()->client.parseLocal(unknown,frame,cropRegions()));
        var outside=cropResponse();((ObjectNode)outside.at("/textBoxes/0/bbox")).put("x",89);assertThrows(IOException.class,()->client.parseLocal(outside,frame,cropRegions()));
    }
    @Test void refinementOnlySelectsCrossedAndEmptyCellsWithFixedBudget(){
        var boxes=List.of(new OcrLayout.TextBox("cross",frame.id(),"甲",new OcrLayout.Box(45,15,10,10),.99),new OcrLayout.TextBox("outside",frame.id(),"乙",new OcrLayout.Box(95,75,2,2),.99));
        var source=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,List.of(frame),boxes,List.of(),List.of("cross","outside"),List.of());
        var regions=List.of(new GridLayoutAssembler.Region(0,0,1,1,List.of(1.0,1.0,50.0,60.0),false),new GridLayoutAssembler.Region(0,1,1,1,List.of(50.0,1.0,90.0,60.0),false));
        var selected=PixelGridClient.refinementRegions(source,List.of(new GridLayoutAssembler.Grid(frame.id(),"PIXEL_XYXY",1,2,regions)));assertEquals(2,selected.size());
    }
}

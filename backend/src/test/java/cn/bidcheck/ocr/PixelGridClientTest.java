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
    OcrLayout.Frame frame=new OcrLayout.Frame("native-image",100,80,cn.bidcheck.compat.Java8Api.repeat("a", 64),"IMAGE",0,null,null,null);
    @BeforeEach void setup(){client=new PixelGridClient(false,"",temp.toString(),1,2);}
    @AfterEach void close(){client.close();Thread.interrupted();}
    ObjectNode response() throws Exception{return (ObjectNode)json.readTree("{\"id\":\"request-1\",\"status\":\"OK\",\"schemaVersion\":\"local-ocr-grid-1\",\"coordinateSystem\":\"PIXEL_TOP_LEFT\",\n\"width\":100,\"height\":80,\"imageHash\":\"HASH\",\"gridCandidates\":[{\"frameId\":\"f1\",\n\"coordinateSpace\":\"PIXEL_XYXY\",\"rows\":1,\"columns\":1,\"cells\":[{\"row\":0,\"column\":0,\n\"rowSpan\":1,\"columnSpan\":1,\"box\":[1,2,90,70],\"header\":false}]}]}\n".replace("HASH",frame.imageHash()));}
    @Test void bindsWorkerLocalFrameToActualNativeOcrImage()throws Exception{
        java.util.List<cn.bidcheck.ocr.GridLayoutAssembler.Grid> grids=client.parse(response(),frame,"request-1");assertEquals("native-image",grids.get(0).frameId());assertEquals(90,grids.get(0).cells().get(0).box().get(2));
        assertEquals("PIXEL_XYXY",grids.get(0).coordinateSpace());
    }
    @Test void rejectsDifferentImageDimensionsHashRequestAndCoordinateSpace()throws Exception{
        for(String key:cn.bidcheck.compat.Java8Api.listOf("width", "height", "imageHash", "id", "schemaVersion", "coordinateSystem")){
            com.fasterxml.jackson.databind.node.ObjectNode root=response();if(key.equals("width")||key.equals("height"))root.put(key,999);else root.put(key,"other");
            assertThrows(IOException.class,()->client.parse(root,frame,"request-1"),key);
        }
        com.fasterxml.jackson.databind.node.ObjectNode normalized=response();((ObjectNode)normalized.at("/gridCandidates/0")).put("coordinateSpace","NORMALIZED_1000_XYXY");assertThrows(IOException.class,()->client.parse(normalized,frame,"request-1"));
    }
    @Test void rejectsSilentIntegerOrBooleanCoercionAndWorkerErrors()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=response();((ObjectNode)root.at("/gridCandidates/0")).put("rows",1.2);assertThrows(IOException.class,()->client.parse(root,frame,null));
        com.fasterxml.jackson.databind.node.ObjectNode second=response();((ObjectNode)second.at("/gridCandidates/0/cells/0")).put("header","false");assertThrows(IOException.class,()->client.parse(second,frame,null));
        com.fasterxml.jackson.databind.node.ObjectNode failed=response().put("error","private raw details");java.io.IOException e=assertThrows(IOException.class,()->client.parse(failed,frame,null));assertFalse(e.getMessage().contains("private"));
    }
    @Test void refusesUnknownFrameAndExcessiveGridCount()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode unknown=response();((ObjectNode)unknown.at("/gridCandidates/0")).put("frameId","f2");assertThrows(IOException.class,()->client.parse(unknown,frame,null));
        com.fasterxml.jackson.databind.node.ObjectNode root=response();com.fasterxml.jackson.databind.JsonNode g=root.at("/gridCandidates/0").deepCopy();com.fasterxml.jackson.databind.node.ArrayNode arr=root.putArray("gridCandidates");for(int i=0;i<41;i++)arr.add(g);assertThrows(IOException.class,()->client.parse(root,frame,null));
    }
    @Test void missingRuntimeIsDisabledAndCancellationPropagates()throws Exception{
        assertFalse(client.enabled());assertThrows(IOException.class,()->client.detect(new byte[]{1},frame));
        Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->client.detect(new byte[]{1},frame));assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void nativePipelineFallsBackWithoutCallingAdvancedWhenGridWorkerMissing()throws Exception{
        cn.bidcheck.ocr.OcrClient advanced=new OcrClient(){public boolean enabled(){return true;}public String identity(){return "new";}public String modelName(){return "ocr";}public OcrResult recognize(byte[] b,String m){fail("must not pay for layout when worker unavailable");return null;}};
        cn.bidcheck.ocr.OcrClient base=new OcrClient(){public boolean enabled(){return true;}public String identity(){return "old";}public String modelName(){return "ocr";}public OcrResult recognize(byte[] b,String m){return new OcrResult("原文","mock","ocr",true);}};
        cn.bidcheck.ocr.StructuredOcrClient pipeline=new StructuredOcrClient(base,advanced,client);cn.bidcheck.ocr.OcrResult r=pipeline.recognize(new byte[]{1},"image/png");assertEquals("原文",r.text());assertEquals("UNAVAILABLE",r.layout().status());assertFalse(pipeline.layoutEnabled());
    }
    ObjectNode cropResponse()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=response();com.fasterxml.jackson.databind.node.ObjectNode b=root.putArray("textBoxes").addObject();b.put("text","姓名");b.put("confidence",.99);b.putObject("bbox").put("x",10).put("y",10).put("width",20).put("height",10);
        com.fasterxml.jackson.databind.node.ObjectNode region=b.putObject("sourceRegion");region.put("id","c1");region.putArray("box").add(1).add(2).add(90).add(70);region.put("scale",2).put("scaleX",2).put("scaleY",2).put("inputWidth",178).put("inputHeight",136);return root;
    }
    List<Map<String,Object>> cropRegions(){return cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "c1", "box", cn.bidcheck.compat.Java8Api.listOf(1.0, 2.0, 90.0, 70.0)));}
    @Test void cropOcrRequiresActualRequestedRegionAndMeasuredScale()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=cropResponse();cn.bidcheck.ocr.OcrLayout layout=client.parseLocal(root,frame,cropRegions());assertEquals("CELL_CROP_MAPPED",layout.frames().get(0).sourceKind());assertEquals("RAPIDOCR",layout.textBoxes().get(0).source());
        ((ObjectNode)root.at("/textBoxes/0/sourceRegion")).put("scaleX",3);assertThrows(IOException.class,()->client.parseLocal(root,frame,cropRegions()));
        com.fasterxml.jackson.databind.node.ObjectNode unknown=cropResponse();((ObjectNode)unknown.at("/textBoxes/0/sourceRegion")).put("id","unrequested");assertThrows(IOException.class,()->client.parseLocal(unknown,frame,cropRegions()));
        com.fasterxml.jackson.databind.node.ObjectNode outside=cropResponse();((ObjectNode)outside.at("/textBoxes/0/bbox")).put("x",89);assertThrows(IOException.class,()->client.parseLocal(outside,frame,cropRegions()));
    }
    @Test void refinementOnlySelectsCrossedAndEmptyCellsWithFixedBudget(){
        java.util.List<cn.bidcheck.ocr.OcrLayout.TextBox> boxes=cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.TextBox("cross",frame.id(),"甲",new OcrLayout.Box(45,15,10,10),.99), new OcrLayout.TextBox("outside",frame.id(),"乙",new OcrLayout.Box(95,75,2,2),.99));
        cn.bidcheck.ocr.OcrLayout source=new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,cn.bidcheck.compat.Java8Api.listOf(frame),boxes,cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf("cross", "outside"),cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<cn.bidcheck.ocr.GridLayoutAssembler.Region> regions=cn.bidcheck.compat.Java8Api.listOf(new GridLayoutAssembler.Region(0,0,1,1,cn.bidcheck.compat.Java8Api.listOf(1.0, 1.0, 50.0, 60.0),false), new GridLayoutAssembler.Region(0,1,1,1,cn.bidcheck.compat.Java8Api.listOf(50.0, 1.0, 90.0, 60.0),false));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> selected=PixelGridClient.refinementRegions(source,cn.bidcheck.compat.Java8Api.listOf(new GridLayoutAssembler.Grid(frame.id(),"PIXEL_XYXY",1,2,regions)));assertEquals(2,selected.size());
    }
}

package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StructuredOcrClientTest {
    @TempDir Path data;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicInteger calls=new AtomicInteger();
    private HttpServer server; private String url; private byte[] image; private String reply; private int status=200; private String finish="stop"; private boolean partial; private long delay; private JsonNode request;
    @BeforeEach void setup() throws Exception {
        image=image(0xffffff); reply=validReply();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/layout",e->{calls.incrementAndGet();request=json.readTree(e.getRequestBody());try{if(delay>0)Thread.sleep(delay);byte[] bytes=envelope(reply).getBytes(StandardCharsets.UTF_8);if(partial)e.getResponseHeaders().set("X-DashScope-PartialResponse","true");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}catch(IOException cancelled){}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{e.close();}});
        server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/layout";
    }
    @AfterEach void close() {server.stop(0);Thread.interrupted();}
    private DashScopeLayoutClient adapter(String model) {return new DashScopeLayoutClient(true,url,"test-layout-secret",model,data,1,Duration.ofSeconds(2),true);}
    private OcrClient textClient(String text) {return new OcrClient(){public boolean enabled(){return true;}public String modelName(){return "text-mock";}public String identity(){return "transcription-mock";}public OcrResult recognize(byte[] bytes,String mime){return new OcrResult(text,"mock","text-mock",true);}};}
    private String validReply() throws Exception {return json.writeValueAsString(Map.of("status","COMPLETE","textBoxes",List.of(Map.of("id","b1","frameId","image","text","项目甲","bbox",Map.of("x",1,"y",1,"width",10,"height",10))),"tables",List.of(),"readingOrder",List.of("b1")));}
    private String envelope(String text) throws IOException {return json.writeValueAsString(Map.of("output",Map.of("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",List.of(Map.of("text",text))))))));}
    private byte[] image(int color)throws IOException {var bitmap=new BufferedImage(24,24,BufferedImage.TYPE_INT_RGB);bitmap.setRGB(0,0,color);var out=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",out);bitmap.flush();return out.toByteArray();}

    @Test void sendsIndependentLayoutRequestAndUsesLocallyMeasuredFrame() throws Exception {
        var result=new StructuredOcrClient(textClient("项目甲"),adapter("vl-one")).recognize(image,"image/png");
        assertEquals("项目甲",result.text()); assertEquals("COMPLETE",result.layout().status()); assertTrue(result.cached());
        assertEquals(24,result.layout().frames().get(0).width()); assertEquals(64,result.layout().frames().get(0).imageHash().length());
        assertTrue(request.at("/input/messages/0/content/0/text").asText().contains("UNTRUSTED DATA"));
        assertEquals("项目甲",json.readTree(request.at("/input/messages/1/content/1/text").asText()).path("referenceTranscription").asText());
        assertFalse(request.path("parameters").has("ocr_options"));
        assertEquals("json_object",request.at("/parameters/response_format/type").asText());
        assertFalse(request.at("/parameters/enable_thinking").asBoolean());
        assertEquals("vl-source-layout-compact-3",DashScopeLayoutClient.POLICY);
        assertEquals(8192,request.at("/parameters/max_tokens").asInt());
        String instructions=request.at("/input/messages/0/content/0/text").asText();
        assertTrue(instructions.contains("per whole table cell or coherent text line"));assertTrue(instructions.contains("one box per character"));
        assertTrue(instructions.contains("at most 80 text boxes"));assertTrue(instructions.contains("80 cells TOTAL"));assertTrue(instructions.contains("Never truncate a table"));
    }
    @Test void pdfRecoveryAccessorUsesTranscriptionWhileOrdinaryImagesKeepStructuredLayout() throws Exception {
        var text = textClient("项目甲");
        assertSame(text, text.pdfRecoveryClient(), "Other clients retain their existing behavior by default");
        var structured = new StructuredOcrClient(text, adapter("vl-one"));
        assertSame(text, structured.pdfRecoveryClient());
        assertNull(structured.pdfRecoveryClient().recognize(image, "image/png").layout());
        assertEquals(0, calls.get());
        assertEquals("COMPLETE", structured.recognize(image, "image/png").layout().status());
        assertEquals(1, calls.get(), "The non-PDF image path still requests its source-bound layout");
    }
    @Test void cacheIsBoundToImageTranscriptAndLayoutIdentityAndContainsNoKey() throws Exception {
        var client=adapter("vl-one");client.recognize(image,"image/png","项目甲");client.recognize(image,"image/png","项目甲");assertEquals(1,calls.get());
        client.recognize(image(0x123456),"image/png","项目甲");client.recognize(image,"image/png","项目甲 追加文字");adapter("vl-two").recognize(image,"image/png","项目甲");assertEquals(4,calls.get());
        try(var files=Files.list(data)){for(Path p:files.toList())assertFalse(Files.readString(p).contains("test-layout-secret"));}
    }
    @Test void corruptCachedGeometryIsRevalidatedAndNotReturned() throws Exception {
        var client=adapter("vl-one");client.recognize(image,"image/png","项目甲");Path file;
        try(var files=Files.list(data)){file=files.findFirst().orElseThrow();}
        var cached=json.readTree(Files.readString(file));((ObjectNode)cached.at("/layout/textBoxes/0/bbox")).put("x",999);Files.writeString(file,json.writeValueAsString(cached));
        assertEquals(1,client.recognize(image,"image/png","项目甲").textBoxes().get(0).bbox().x());assertEquals(2,calls.get());
    }
    @Test void invalidOrUnavailableLayoutPreservesTranscriptionAndIsNotCached() throws Exception {
        reply=validReply().replace("项目甲","编造乙");
        var client=new StructuredOcrClient(textClient("项目甲"),adapter("vl-one"));
        assertEquals("UNAVAILABLE",client.recognize(image,"image/png").layout().status());assertEquals("项目甲",client.recognize(image,"image/png").text());assertEquals(2,calls.get());
        try(var files=Files.list(data)){assertEquals(0,files.count());}
        status=503;assertEquals("UNAVAILABLE",client.recognize(image,"image/png").layout().status());
    }
    @Test void unsupportedFieldsScalarCoercionsAndDuplicateJsonAreRejected() throws Exception {
        for(String value:List.of(validReply().replace("\"width\":10","\"width\":\"10\""),validReply().replace("\"text\":\"项目甲\"","\"text\":\"项目甲\",\"source\":\"RAPIDOCR\""),validReply().replace("\"status\":", "\"inventedHeader\":\"企业\",\"status\":"),"{\"status\":\"COMPLETE\",\"status\":\"PARTIAL\"}")) {
            reply=value;assertThrows(IOException.class,()->adapter("vl-one").recognize(image,"image/png","项目甲"));
        }
    }
    @Test void productionPlainHttpIsDisabledAndInterruptIsNotSwallowed() throws Exception {
        var disabled=new DashScopeLayoutClient(true,url,"key","vl-one",data.toString(),1,2);
        assertFalse(disabled.enabled());assertEquals("UNAVAILABLE",disabled.recognize(image,"image/png","项目甲").status());assertEquals(0,calls.get());
        Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->new StructuredOcrClient(textClient("项目甲"),adapter("vl-one")).recognize(image,"image/png"));assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void incompleteAndOversizedRepliesNeverPublishLayoutCache() throws Exception {
        var client=adapter("vl-one");finish="length";assertEquals("MODEL_TRUNCATED",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        finish="stop";partial=true;assertEquals("PARTIAL_RESPONSE",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        partial=false;reply="x".repeat(1024*1024+1);assertEquals("RESPONSE_LIMIT",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        assertFalse(Files.exists(data.resolve("layout-cache")));try(var files=Files.list(data)){assertEquals(0,files.count());}
    }
    @Test void nativeAdapterRejectsOpenAiCompatibleEndpointConfiguration() {
        var invalid=new DashScopeLayoutClient(true,"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions","mock","vl-one",data.toString(),1,2);
        assertFalse(invalid.enabled());
        var valid=new DashScopeLayoutClient(true,"https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation","mock","vl-one",data.toString(),1,2);
        assertTrue(valid.enabled());assertNotEquals(invalid.identity(),valid.identity());
    }
    @Test void safeDiagnosticsSeparateHttpProtocolAndLayoutFailureWithoutProviderData() throws Exception {
        var client=adapter("vl-one");status=503;reply="private source and test-layout-secret";
        var http=assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲"));
        assertEquals("HTTP_STATUS",http.code());assertEquals(503,http.httpStatus());assertNull(http.getCause());
        assertTrue(http.getMessage().contains("[LAYOUT_HTTP_STATUS_503]"));assertFalse(http.getMessage().contains("private"));assertFalse(http.getMessage().contains("test-layout-secret"));
        status=200;reply="{bad json";assertEquals("INVALID_PROTOCOL",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        reply=validReply().replace("项目甲","虚构内容");assertEquals("INVALID_LAYOUT",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        var result=new StructuredOcrClient(textClient("项目甲"),client).recognize(image,"image/png");
        assertEquals("项目甲",result.text());assertTrue(result.layout().warnings().get(0).contains("[LAYOUT_INVALID_LAYOUT]"));
    }
    @Test void deadlineHasStableSafeCodeAndNoFailedCache() throws Exception {
        delay=250;var client=new DashScopeLayoutClient(true,url,"private-credential","vl-one",data,1,Duration.ofMillis(70),true);
        var failure=assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲"));
        assertEquals("TIMEOUT",failure.code());assertNull(failure.getCause());assertNull(failure.httpStatus());
        try(var files=Files.list(data)){assertEquals(0,files.count());}
    }
    @Test void compactPerImageBudgetIsEnforcedBeforePublishingOrCachingRelations() throws Exception {
        var root=(ObjectNode)json.readTree(validReply());var boxes=root.putArray("textBoxes");var order=root.putArray("readingOrder");
        for(int i=0;i<81;i++){boxes.addObject().put("id","b"+i).put("frameId","image").put("text","项目甲").putObject("bbox").put("x",1).put("y",1).put("width",10).put("height",10);order.add("b"+i);}
        reply=json.writeValueAsString(root);
        assertEquals("LAYOUT_BUDGET_EXCEEDED",assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("vl-one").recognize(image,"image/png","项目甲".repeat(81))).code());
        try(var files=Files.list(data)){assertEquals(0,files.count());}
    }
    @Test void drivingLicenseAndPassportKeepStructuredSingleImagePathInsteadOfGenericBatch()throws Exception {
        AtomicInteger single=new AtomicInteger(),batch=new AtomicInteger();
        var transcription=new OcrClient(){
            public boolean enabled(){return true;}public String modelName(){return "route-test";}public String identity(){return "route-test";}
            public OcrResult recognize(byte[] bytes,String mime){single.incrementAndGet();return new OcrResult("项目甲","mock","route-test",false);}
            public void recognizeBatch(List<OcrBatch.Input> inputs,OcrBatch.Listener listener)throws IOException{
                batch.incrementAndGet();for(var input:inputs)listener.accept(OcrBatch.Item.success(input.id(),new OcrResult("项目甲","mock","route-test",false)));
            }
        };
        var structured=new StructuredOcrClient(transcription,adapter("person-layout"));List<OcrBatch.Item> results=new ArrayList<>();
        structured.recognizeBatch(List.of(new OcrBatch.Input("driver",image,"image/png",24,24,"DRIVING_LICENSE","driving_license"),new OcrBatch.Input("passport",image(0x123456),"image/png",24,24,"PASSPORT","passport")),results::add);
        assertEquals(2,single.get());assertEquals(0,batch.get());assertEquals(2,calls.get());assertEquals(2,results.size());
        assertTrue(results.stream().allMatch(item->item.result()!=null&&"COMPLETE".equals(item.result().layout().status())));
        structured.recognizeBatch(List.of(new OcrBatch.Input("ordinary",image,"image/png",24,24,"ORDINARY_TEXT","text")),results::add);
        assertEquals(1,batch.get(),"Ordinary text retains the batch path");assertEquals(2,single.get());
    }
}

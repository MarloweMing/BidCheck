package cn.bidcheck.ocr;
import cn.bidcheck.compat.HexFormat;

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
    private String validReply() throws Exception {return json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETE", "coordinateSystem", "PIXEL", "bboxFormat", "XYWH", "textBoxes", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "b1", "frameId", "image", "text", "项目甲", "bbox", cn.bidcheck.compat.Java8Api.mapOf("x", 1, "y", 1, "width", 10, "height", 10))), "tables", cn.bidcheck.compat.Java8Api.listOf(), "readingOrder", cn.bidcheck.compat.Java8Api.listOf("b1")));}
    private String envelope(String text) throws IOException {return json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", finish, "message", cn.bidcheck.compat.Java8Api.mapOf("content", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("text", text))))))));}
    private byte[] image(int color)throws IOException {java.awt.image.BufferedImage bitmap=new BufferedImage(24,24,BufferedImage.TYPE_INT_RGB);bitmap.setRGB(0,0,color);java.io.ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",out);bitmap.flush();return out.toByteArray();}

    @Test void sendsIndependentLayoutRequestAndUsesLocallyMeasuredFrame() throws Exception {
        cn.bidcheck.ocr.OcrResult result=new StructuredOcrClient(textClient("项目甲"),adapter("vl-one")).recognize(image,"image/png");
        assertEquals("项目甲",result.text()); assertEquals("COMPLETE",result.layout().status()); assertTrue(result.cached());
        assertEquals(24,result.layout().frames().get(0).width()); assertEquals(64,result.layout().frames().get(0).imageHash().length());
        assertTrue(request.at("/input/messages/0/content/0/text").asText().contains("UNTRUSTED DATA"));
        assertEquals("项目甲",json.readTree(request.at("/input/messages/1/content/1/text").asText()).path("referenceTranscription").asText());
        assertFalse(request.path("parameters").has("ocr_options"));
        assertEquals("json_object",request.at("/parameters/response_format/type").asText());
        assertFalse(request.at("/parameters/enable_thinking").asBoolean());
        assertEquals("vl-source-layout-6-compact-cells",DashScopeLayoutClient.POLICY);
        assertEquals(4096,request.at("/parameters/max_tokens").asInt());
        String instructions=request.at("/input/messages/0/content/0/text").asText();
        assertTrue(instructions.contains("per whole table cell or coherent text line"));assertTrue(instructions.contains("one box per character"));
        assertTrue(instructions.contains("at most 32 cells TOTAL"));assertTrue(instructions.contains("compact-cells-1"));assertTrue(instructions.contains("Never truncate a table"));
    }
    @Test void pdfRecoveryAccessorUsesTranscriptionWhileOrdinaryImagesKeepStructuredLayout() throws Exception {
        cn.bidcheck.ocr.OcrClient text = textClient("项目甲");
        assertSame(text, text.pdfRecoveryClient(), "Other clients retain their existing behavior by default");
        cn.bidcheck.ocr.StructuredOcrClient structured = new StructuredOcrClient(text, adapter("vl-one"));
        assertSame(text, structured.pdfRecoveryClient());
        assertNull(structured.pdfRecoveryClient().recognize(image, "image/png").layout());
        assertEquals(0, calls.get());
        assertEquals("COMPLETE", structured.recognize(image, "image/png").layout().status());
        assertEquals(1, calls.get(), "The non-PDF image path still requests its source-bound layout");
    }
    @Test void cacheIsBoundToImageTranscriptAndLayoutIdentityAndContainsNoKey() throws Exception {
        cn.bidcheck.ocr.DashScopeLayoutClient client=adapter("vl-one");client.recognize(image,"image/png","项目甲");client.recognize(image,"image/png","项目甲");assertEquals(1,calls.get());
        client.recognize(image(0x123456),"image/png","项目甲");client.recognize(image,"image/png","项目甲 追加文字");adapter("vl-two").recognize(image,"image/png","项目甲");assertEquals(4,calls.get());
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(data)){for(Path p:cn.bidcheck.compat.Java8Api.toList(files))assertFalse(cn.bidcheck.compat.Java8Api.readString(p).contains("test-layout-secret"));}
    }
    @Test void rawLayoutAuditAndCacheReuseKeepActualRequestResponseAndHashes()throws Exception{
        String previous=System.getProperty("bidcheck.agent.audit-directory");Path audit=data.resolve("audit");System.setProperty("bidcheck.agent.audit-directory",audit.toString());
        try{
            cn.bidcheck.ocr.DashScopeLayoutClient client=adapter("vl-audited");cn.bidcheck.ocr.DashScopeLayoutClient.Recognition first=client.recognizeDetailed(image,"image/png","项目甲");cn.bidcheck.ocr.DashScopeLayoutClient.Recognition cached=client.recognizeDetailed(image,"image/png","项目甲");
            assertFalse(first.cached());assertTrue(cached.cached());assertEquals(1,calls.get());assertEquals(first.audit().get("traceId"),cached.audit().get("traceId"));assertEquals(first.audit().get("inputAudit"),cached.audit().get("inputAudit"));assertEquals(first.audit().get("outputAudit"),cached.audit().get("outputAudit"));
            Map<?,?> input=(Map<?,?>)first.audit().get("inputAudit"),output=(Map<?,?>)first.audit().get("outputAudit");
            assertEquals("SAVED",input.get("status"));assertEquals("SAVED",output.get("status"));
            for(Map<?,?> ref:cn.bidcheck.compat.Java8Api.listOf(input, output)){Path file=audit.resolve("page-layout").resolve(ref.get("file").toString());byte[] bytes=Files.readAllBytes(file);assertEquals(ref.get("sha256"),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("test-layout-secret"));}
            JsonNode savedRequest=json.readTree(cn.bidcheck.compat.Java8Api.readString(audit.resolve("page-layout").resolve(input.get("file").toString())));assertEquals(request,json.readTree(savedRequest.path("bodyUtf8").asText()));
            JsonNode savedResponse=json.readTree(cn.bidcheck.compat.Java8Api.readString(audit.resolve("page-layout").resolve(output.get("file").toString())));assertEquals(json.readTree(envelope(reply)),json.readTree(savedResponse.path("bodyUtf8").asText()));
        }finally{if(previous==null)System.clearProperty("bidcheck.agent.audit-directory");else System.setProperty("bidcheck.agent.audit-directory",previous);}
    }
    @Test void rejectedGeometryAndHttpFailuresRetainRawResponseAndExactFailureCode()throws Exception{
        String previous=System.getProperty("bidcheck.agent.audit-directory");System.setProperty("bidcheck.agent.audit-directory",data.resolve("audit").toString());
        try{
            reply=validReply().replace("项目甲","虚构乙");cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException invalid=assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("vl-audit-invalid").recognizeDetailed(image,"image/png","项目甲"));
            assertEquals("INVALID_LAYOUT",invalid.audit().get("errorCode"));assertEquals("文字框未对齐原始转录",invalid.audit().get("validationReason"));assertEquals("SAVED",((Map<?,?>)invalid.audit().get("outputAudit")).get("status"));
            status=400;reply="provider error must be available to local audit";cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException rejected=assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("vl-audit-http").recognizeDetailed(image,"image/png","项目甲"));
            assertEquals("HTTP_STATUS",rejected.code());List<?> attempts=(List<?>)rejected.audit().get("responseAttempts");assertEquals(1,attempts.size());assertEquals(400,((Map<?,?>)attempts.get(0)).get("httpStatus"));assertEquals("SAVED",((Map<?,?>)rejected.audit().get("outputAudit")).get("status"));
        }finally{if(previous==null)System.clearProperty("bidcheck.agent.audit-directory");else System.setProperty("bidcheck.agent.audit-directory",previous);}
    }
    @Test void corruptCachedGeometryIsRevalidatedAndNotReturned() throws Exception {
        cn.bidcheck.ocr.DashScopeLayoutClient client=adapter("vl-one");client.recognize(image,"image/png","项目甲");Path file;
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(data)){file=files.findFirst().orElseThrow(java.util.NoSuchElementException::new);}
        com.fasterxml.jackson.databind.JsonNode cached=json.readTree(cn.bidcheck.compat.Java8Api.readString(file));((ObjectNode)cached.at("/layout/textBoxes/0/bbox")).put("x",999);cn.bidcheck.compat.Java8Api.writeString(file, json.writeValueAsString(cached));
        assertEquals(1,client.recognize(image,"image/png","项目甲").textBoxes().get(0).bbox().x());assertEquals(2,calls.get());
    }
    @Test void invalidOrUnavailableLayoutPreservesTranscriptionAndIsNotCached() throws Exception {
        reply=validReply().replace("项目甲","编造乙");
        cn.bidcheck.ocr.StructuredOcrClient client=new StructuredOcrClient(textClient("项目甲"),adapter("vl-one"));
        assertEquals("UNAVAILABLE",client.recognize(image,"image/png").layout().status());assertEquals("项目甲",client.recognize(image,"image/png").text());assertEquals(2,calls.get());
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(data)){assertEquals(0,files.count());}
        status=503;assertEquals("UNAVAILABLE",client.recognize(image,"image/png").layout().status());
    }
    @Test void unsupportedFieldsScalarCoercionsAndDuplicateJsonAreRejected() throws Exception {
        for(String value:cn.bidcheck.compat.Java8Api.listOf(validReply().replace("\"width\":10","\"width\":\"10\""), validReply().replace("\"text\":\"项目甲\"","\"text\":\"项目甲\",\"source\":\"RAPIDOCR\""), validReply().replace("\"status\":", "\"inventedHeader\":\"企业\",\"status\":"), "{\"status\":\"COMPLETE\",\"status\":\"PARTIAL\"}")) {
            reply=value;assertThrows(IOException.class,()->adapter("vl-one").recognize(image,"image/png","项目甲"));
        }
    }
    @Test void productionPlainHttpIsDisabledAndInterruptIsNotSwallowed() throws Exception {
        cn.bidcheck.ocr.DashScopeLayoutClient disabled=new DashScopeLayoutClient(true,url,"key","vl-one",data.toString(),1,2);
        assertFalse(disabled.enabled());assertEquals("UNAVAILABLE",disabled.recognize(image,"image/png","项目甲").status());assertEquals(0,calls.get());
        Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->new StructuredOcrClient(textClient("项目甲"),adapter("vl-one")).recognize(image,"image/png"));assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void incompleteAndOversizedRepliesNeverPublishLayoutCache() throws Exception {
        cn.bidcheck.ocr.DashScopeLayoutClient client=adapter("vl-one");finish="length";assertEquals("MODEL_TRUNCATED",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        finish="stop";partial=true;assertEquals("PARTIAL_RESPONSE",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        partial=false;reply=cn.bidcheck.compat.Java8Api.repeat("x", 1024*1024+1);assertEquals("RESPONSE_LIMIT",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        assertFalse(Files.exists(data.resolve("layout-cache")));try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(data)){assertEquals(0,files.count());}
    }
    @Test void nativeAdapterRejectsOpenAiCompatibleEndpointConfiguration() {
        cn.bidcheck.ocr.DashScopeLayoutClient invalid=new DashScopeLayoutClient(true,"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions","mock","vl-one",data.toString(),1,2);
        assertFalse(invalid.enabled());
        cn.bidcheck.ocr.DashScopeLayoutClient valid=new DashScopeLayoutClient(true,"https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation","mock","vl-one",data.toString(),1,2);
        assertTrue(valid.enabled());assertNotEquals(invalid.identity(),valid.identity());
    }
    @Test void safeDiagnosticsSeparateHttpProtocolAndLayoutFailureWithoutProviderData() throws Exception {
        cn.bidcheck.ocr.DashScopeLayoutClient client=adapter("vl-one");status=503;reply="private source and test-layout-secret";
        cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException http=assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲"));
        assertEquals("HTTP_STATUS",http.code());assertEquals(503,http.httpStatus());assertNull(http.getCause());
        assertTrue(http.getMessage().contains("[LAYOUT_HTTP_STATUS_503]"));assertFalse(http.getMessage().contains("private"));assertFalse(http.getMessage().contains("test-layout-secret"));
        status=200;reply="{bad json";assertEquals("INVALID_PROTOCOL",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        reply=validReply().replace("项目甲","虚构内容");assertEquals("INVALID_LAYOUT",assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲")).code());
        cn.bidcheck.ocr.OcrResult result=new StructuredOcrClient(textClient("项目甲"),client).recognize(image,"image/png");
        assertEquals("项目甲",result.text());assertTrue(result.layout().warnings().get(0).contains("[LAYOUT_INVALID_LAYOUT]"));
    }
    @Test void deadlineHasStableSafeCodeAndNoFailedCache() throws Exception {
        delay=250;cn.bidcheck.ocr.DashScopeLayoutClient client=new DashScopeLayoutClient(true,url,"private-credential","vl-one",data,1,Duration.ofMillis(70),true);
        cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException failure=assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognize(image,"image/png","项目甲"));
        assertEquals("TIMEOUT",failure.code());assertNull(failure.getCause());assertNull(failure.httpStatus());
        List<?> attempts=(List<?>)failure.audit().get("transportAttempts");assertFalse(attempts.isEmpty());assertEquals("TIMEOUT",failure.audit().get("firstFailureCode"));
        assertEquals("TRANSPORT",((Map<?,?>)attempts.get(0)).get("phase"));assertEquals(false,((Map<?,?>)attempts.get(0)).get("responseReceived"));assertTrue(((Number)((Map<?,?>)attempts.get(0)).get("durationMs")).longValue()>0);
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(data)){assertEquals(0,files.count());}
    }
    @Test void circuitRejectionRetainsTheTimeoutsWhichActuallyOpenedIt()throws Exception{
        delay=300;cn.bidcheck.ocr.OcrRequestSettings settings=new OcrRequestSettings(4,3,1,1,0,8,2,1000);
        cn.bidcheck.ocr.DashScopeLayoutClient client=new DashScopeLayoutClient(true,url,"private-credential","circuit-audited",data,1,Duration.ofMillis(70),true,settings);
        cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException failure=assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognizeDetailed(image,"image/png","项目甲"));
        assertEquals("CIRCUIT_OPEN",failure.code());assertEquals("TIMEOUT",failure.audit().get("firstFailureCode"));
        List<?> attempts=(List<?>)failure.audit().get("transportAttempts");assertEquals(3,attempts.size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("TIMEOUT", "TIMEOUT", "CIRCUIT_OPEN"),cn.bidcheck.compat.Java8Api.toList(attempts.stream().map(v->((Map<?,?>)v).get("code"))));
        assertEquals("ADMISSION",((Map<?,?>)attempts.get(2)).get("phase"));assertTrue(((List<?>)failure.audit().get("responseAttempts")).isEmpty());
        assertFalse(failure.audit().toString().contains("private-credential"));
    }
    @Test void compactPerImageBudgetIsEnforcedBeforePublishingOrCachingRelations() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root=(ObjectNode)json.readTree(validReply());com.fasterxml.jackson.databind.node.ArrayNode boxes=root.putArray("textBoxes");com.fasterxml.jackson.databind.node.ArrayNode order=root.putArray("readingOrder");
        for(int i=0;i<81;i++){boxes.addObject().put("id","b"+i).put("frameId","image").put("text","项目甲").putObject("bbox").put("x",1).put("y",1).put("width",10).put("height",10);order.add("b"+i);}
        reply=json.writeValueAsString(root);
        assertEquals("LAYOUT_BUDGET_EXCEEDED",assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("vl-one").recognize(image,"image/png",cn.bidcheck.compat.Java8Api.repeat("项目甲", 81))).code());
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(data)){assertEquals(0,files.count());}
    }
    @Test void declaredNormalizedCornersConvertToActualImagePixelsWithOriginalAudit()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=(ObjectNode)json.readTree(validReply());root.put("coordinateSystem","NORMALIZED_1000");root.put("bboxFormat","XYXY");
        ((ObjectNode)root.at("/textBoxes/0")).set("bbox",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf(100, 200, 600, 700)));reply=json.writeValueAsString(root);
        cn.bidcheck.ocr.DashScopeLayoutClient.Recognition result=adapter("normalized-corners").recognizeDetailed(image,"image/png","项目甲");cn.bidcheck.ocr.OcrLayout.Box box=result.layout().textBoxes().get(0).bbox();
        assertEquals(2.4,box.x(),1e-9);assertEquals(4.8,box.y(),1e-9);assertEquals(12,box.width(),1e-9);assertEquals(12,box.height(),1e-9);
        Map<?,?> mapping=(Map<?,?>)result.audit().get("coordinateTransform");assertEquals("NORMALIZED_1000",mapping.get("declaredCoordinateSystem"));assertEquals("XYXY",mapping.get("declaredBboxFormat"));assertEquals(false,mapping.get("clamped"));assertEquals(false,mapping.get("inferred"));
    }
    @Test void normalizedTableUsesTheSameDeclaredMappingForCellAndText()throws Exception{
        Map<String,Object> header=cn.bidcheck.compat.Java8Api.mapOf("id", "b1", "frameId", "image", "text", "姓名", "bbox", cn.bidcheck.compat.Java8Api.listOf(100, 100, 900, 400));
        Map<String,Object> person=cn.bidcheck.compat.Java8Api.mapOf("id", "b2", "frameId", "image", "text", "合成甲", "bbox", cn.bidcheck.compat.Java8Api.listOf(100, 500, 900, 900));
        Map<String,Object> h=cn.bidcheck.compat.Java8Api.mapOf("id", "c1", "row", 0, "column", 0, "rowSpan", 1, "columnSpan", 1, "text", "姓名", "bbox", cn.bidcheck.compat.Java8Api.listOf(100, 100, 900, 400), "blockIds", cn.bidcheck.compat.Java8Api.listOf("b1"), "headerCellIds", cn.bidcheck.compat.Java8Api.listOf(), "header", true);
        Map<String,Object> p=cn.bidcheck.compat.Java8Api.mapOf("id", "c2", "row", 1, "column", 0, "rowSpan", 1, "columnSpan", 1, "text", "合成甲", "bbox", cn.bidcheck.compat.Java8Api.listOf(100, 500, 900, 900), "blockIds", cn.bidcheck.compat.Java8Api.listOf("b2"), "headerCellIds", cn.bidcheck.compat.Java8Api.listOf("c1"), "header", false);
        reply=json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETE", "coordinateSystem", "NORMALIZED_1000", "bboxFormat", "XYXY", "textBoxes", cn.bidcheck.compat.Java8Api.listOf(header, person), "tables", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "t1", "frameId", "image", "rows", 2, "columns", 1, "cells", cn.bidcheck.compat.Java8Api.listOf(h, p))), "readingOrder", cn.bidcheck.compat.Java8Api.listOf("b1", "b2")));
        cn.bidcheck.ocr.OcrLayout result=adapter("normalized-table").recognize(image,"image/png","姓名 合成甲");assertEquals("COMPLETE",result.status());assertEquals(2,result.tables().get(0).cells().size());assertEquals(result.textBoxes().get(1).bbox(),result.tables().get(0).cells().get(1).bbox());
    }
    @Test void undeclaredOrContradictoryCoordinateSystemsAreNeverGuessedOrClamped()throws Exception{
        for(String variant:cn.bidcheck.compat.Java8Api.listOf("missing", "unknown", "normalized-overflow", "xyxy-reversed", "xyxy-object", "pixel-array", "string-number")){
            com.fasterxml.jackson.databind.node.ObjectNode root=(ObjectNode)json.readTree(validReply());com.fasterxml.jackson.databind.node.ObjectNode box=(ObjectNode)root.at("/textBoxes/0");
            switch (variant) {case "missing":root.remove("coordinateSystem"); break;case "unknown":root.put("coordinateSystem","AUTO"); break;case "normalized-overflow":{root.put("coordinateSystem","NORMALIZED_1000");box.set("bbox",json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("x", 570, "y", 691, "width", 802, "height", 24)));} break;case "xyxy-reversed":{root.put("bboxFormat","XYXY");box.set("bbox",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf(10, 10, 5, 20)));} break;case "xyxy-object":root.put("bboxFormat","XYXY"); break;case "pixel-array":box.set("bbox",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf(1, 1, 10, 10))); break;case "string-number":((ObjectNode)box.path("bbox")).put("width","10"); break;}
            reply=json.writeValueAsString(root);cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException failure=assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("coordinates-"+variant).recognize(image,"image/png","项目甲"));assertEquals("INVALID_LAYOUT",failure.code(),variant);
        }
    }
    private ObjectNode compactReply()throws Exception{
        return (ObjectNode)json.readTree("{\"schemaVersion\":\"compact-cells-1\",\"status\":\"PARTIAL\",\"coordinateSystem\":\"NORMALIZED_1000\",\"bboxFormat\":\"XYXY\",\n \"tables\":[{\"id\":\"t1\",\"rows\":3,\"columns\":2,\"cells\":[\n   {\"id\":\"h\",\"row\":0,\"column\":0,\"rowSpan\":1,\"columnSpan\":1,\"text\":\"姓名\",\"bbox\":[100,100,400,200],\"header\":true,\"headerCellIds\":[]},\n   {\"id\":\"v\",\"row\":2,\"column\":0,\"rowSpan\":1,\"columnSpan\":1,\"text\":\"合成甲\",\"bbox\":[100,500,400,600],\"header\":false,\"headerCellIds\":[\"h\"]}]}],\n \"fields\":[],\"readingOrder\":[\"h\",\"v\"]}\n");
    }
    @Test void compactCellsGenerateOnlyOneBoxEachAndKeepSparseRowsPartial()throws Exception{
        reply=json.writeValueAsString(compactReply());cn.bidcheck.ocr.DashScopeLayoutClient.Recognition result=adapter("compact-sparse").recognizeDetailed(image,"image/png","姓名 合成甲 其他未定位内容");
        assertEquals("PARTIAL",result.layout().status());assertEquals(2,result.layout().textBoxes().size());assertEquals(cn.bidcheck.compat.Java8Api.listOf("box_h", "box_v"),result.layout().readingOrder());
        cn.bidcheck.ocr.OcrLayout.Table table=result.layout().tables().get(0);assertEquals(3,table.rows());assertEquals(2,table.columns());assertEquals(2,table.cells().size(),"omitted row/column must not become invented empty cells");
        assertEquals(2,table.cells().get(1).row());assertEquals(cn.bidcheck.compat.Java8Api.listOf("box_v"),table.cells().get(1).blockIds());assertEquals(cn.bidcheck.compat.Java8Api.listOf("h"),table.cells().get(1).headerCellIds());
        assertEquals(result.layout().textBoxes().get(1).bbox(),table.cells().get(1).bbox());assertEquals("compact-cells-1",((Map<?,?>)result.audit().get("responseLayoutContract")).get("schemaVersion"));
        assertEquals(4096,request.at("/parameters/max_tokens").asInt());assertEquals("compact-cells-1",((Map<?,?>)result.audit().get("requestLayoutContract")).get("schemaVersion"));
    }
    @Test void compactReadingOrderFollowsSuppliedOriginalOccurrencesNotVisualOrder()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=compactReply();root.set("readingOrder",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf("v", "h")));reply=json.writeValueAsString(root);
        cn.bidcheck.ocr.OcrLayout result=adapter("compact-source-order").recognize(image,"image/png","合成甲 姓名");assertEquals(cn.bidcheck.compat.Java8Api.listOf("box_v", "box_h"),result.readingOrder());
        assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("compact-bad-source-order").recognize(image,"image/png","姓名 合成甲"),"server must not silently reorder source occurrences to force acceptance");
    }
    @Test void compactFreeFieldsNeverInventTableOrSplitIdentifierDigits()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=compactReply();root.set("tables",json.createArrayNode());root.set("fields",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "id1", "text", "身份证号码 11010519491231002X", "bbox", cn.bidcheck.compat.Java8Api.listOf(100, 200, 900, 300)))));root.set("readingOrder",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf("id1")));reply=json.writeValueAsString(root);
        cn.bidcheck.ocr.OcrLayout result=adapter("compact-field").recognize(image,"image/png","身份证号码 11010519491231\n002X");assertTrue(result.tables().isEmpty());assertEquals(1,result.textBoxes().size());assertEquals("身份证号码 11010519491231002X",result.textBoxes().get(0).text());
        assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("compact-fabricated-digits").recognize(image,"image/png","身份证号码 11010519491231\n9999"));
    }
    @Test void compactMissingHeadersDuplicatesAndFullCoverageClaimsAreRejected()throws Exception{
        for(String variant:cn.bidcheck.compat.Java8Api.listOf("missing-header", "unknown-header", "missing-row", "unknown-schema", "complete", "duplicate-box", "source-rewrite", "duplicate-order", "unknown-property")){
            com.fasterxml.jackson.databind.node.ObjectNode root=compactReply();com.fasterxml.jackson.databind.node.ObjectNode cell=(ObjectNode)root.at("/tables/0/cells/1");
            switch (variant) {case "missing-header":cell.set("headerCellIds",json.createArrayNode()); break;case "unknown-header":cell.set("headerCellIds",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf("absent"))); break;case "missing-row":cell.remove("row"); break;case "unknown-schema":root.put("schemaVersion","compact-auto"); break;case "complete":root.put("status","COMPLETE"); break;case "duplicate-box":cell.set("bbox",root.at("/tables/0/cells/0/bbox").deepCopy()); break;case "source-rewrite":cell.put("text","不同人员"); break;case "duplicate-order":root.set("readingOrder",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf("h", "h"))); break;case "unknown-property":cell.put("verified",true); break;}
            reply=json.writeValueAsString(root);assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("compact-reject-"+variant).recognize(image,"image/png","姓名 合成甲"),variant);
        }
    }
    @Test void compactCellAndFieldLimitsAreCheckedBeforeExpanding()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode root=compactReply();com.fasterxml.jackson.databind.node.ArrayNode cells=(com.fasterxml.jackson.databind.node.ArrayNode)root.at("/tables/0/cells");JsonNode original=cells.get(0).deepCopy();for(int i=2;i<33;i++)cells.add(original.deepCopy());reply=json.writeValueAsString(root);
        assertEquals("LAYOUT_BUDGET_EXCEEDED",assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("compact-cell-limit").recognize(image,"image/png","姓名 合成甲")).code());
        root=compactReply();com.fasterxml.jackson.databind.node.ArrayNode fields=root.putArray("fields");for(int i=0;i<9;i++)fields.addObject().put("id","f"+i).put("text","姓名").set("bbox",json.valueToTree(cn.bidcheck.compat.Java8Api.listOf(10, 10, 20, 20)));reply=json.writeValueAsString(root);
        assertEquals("LAYOUT_BUDGET_EXCEEDED",assertThrows(DashScopeLayoutClient.LayoutException.class,()->adapter("compact-field-limit").recognize(image,"image/png","姓名 合成甲")).code());
    }
    @Test void oneAttemptLayoutSettingsDoNotRepeatTimedOutImage()throws Exception{
        delay=300;cn.bidcheck.ocr.OcrRequestSettings settings=new OcrRequestSettings(4,1,1,1);cn.bidcheck.ocr.DashScopeLayoutClient client=new DashScopeLayoutClient(true,url,"private-credential","compact-once",data,1,Duration.ofMillis(70),true,settings);
        cn.bidcheck.ocr.DashScopeLayoutClient.LayoutException failure=assertThrows(DashScopeLayoutClient.LayoutException.class,()->client.recognizeDetailed(image,"image/png","项目甲"));assertEquals("TIMEOUT",failure.code());assertEquals(1,((List<?>)failure.audit().get("transportAttempts")).size());assertTrue(calls.get()<=1);
        assertEquals(1,((Map<?,?>)failure.audit().get("requestLayoutContract")).get("maxAttempts"));
    }
    @Test void drivingLicenseAndPassportKeepStructuredSingleImagePathInsteadOfGenericBatch()throws Exception {
        AtomicInteger single=new AtomicInteger(),batch=new AtomicInteger();
        cn.bidcheck.ocr.OcrClient transcription=new OcrClient(){
            public boolean enabled(){return true;}public String modelName(){return "route-test";}public String identity(){return "route-test";}
            public OcrResult recognize(byte[] bytes,String mime){single.incrementAndGet();return new OcrResult("项目甲","mock","route-test",false);}
            public void recognizeBatch(List<OcrBatch.Input> inputs,OcrBatch.Listener listener)throws IOException{
                batch.incrementAndGet();for(cn.bidcheck.ocr.OcrBatch.Input input:inputs)listener.accept(OcrBatch.Item.success(input.id(),new OcrResult("项目甲","mock","route-test",false)));
            }
        };
        cn.bidcheck.ocr.StructuredOcrClient structured=new StructuredOcrClient(transcription,adapter("person-layout"));List<OcrBatch.Item> results=new ArrayList<>();
        structured.recognizeBatch(cn.bidcheck.compat.Java8Api.listOf(new OcrBatch.Input("driver",image,"image/png",24,24,"DRIVING_LICENSE","driving_license"), new OcrBatch.Input("passport",image(0x123456),"image/png",24,24,"PASSPORT","passport")),results::add);
        assertEquals(2,single.get());assertEquals(0,batch.get());assertEquals(2,calls.get());assertEquals(2,results.size());
        assertTrue(results.stream().allMatch(item->item.result()!=null&&"COMPLETE".equals(item.result().layout().status())));
        structured.recognizeBatch(cn.bidcheck.compat.Java8Api.listOf(new OcrBatch.Input("ordinary",image,"image/png",24,24,"ORDINARY_TEXT","text")),results::add);
        assertEquals(1,batch.get(),"Ordinary text retains the batch path");assertEquals(2,single.get());
    }
}

package cn.bidcheck.ocr;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol fixtures use synthetic text only. Model quality and regional availability require a separate live probe. */
class Qwen35MigrationProtocolTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    HttpServer server;ExecutorService workers;String url;
    interface Responder {Map<String,Object> content(JsonNode request)throws Exception;}
    volatile Responder responder;
    final OcrRequestSettings settings=new OcrRequestSettings(16,1,1,2,0,8,100,100);
    @BeforeEach void start()throws Exception {
        responder=request->Map.of("text","合成字段：BC-001");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);workers=Executors.newFixedThreadPool(2);server.setExecutor(workers);
        server.createContext("/native",exchange->{try{
            JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);
            byte[] body=json.writeValueAsBytes(Map.of("output",Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("role","assistant","content",List.of(responder.content(request))))))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception failure){exchange.close();}finally{exchange.close();}});server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/native";
    }
    @AfterEach void close(){server.stop(0);workers.shutdownNow();}
    DashScopeOcrClient textClient(String model){return new DashScopeOcrClient(true,url,"synthetic-test-key",model,temp.resolve("text"),Duration.ofSeconds(3),true,2,settings,OcrBatchPolicy.defaults());}
    DashScopeAdvancedOcrClient advanced(){return new DashScopeAdvancedOcrClient(true,url,"synthetic-test-key","qwen3.5-ocr",temp.resolve("advanced"),Duration.ofSeconds(3),true,2,settings);}
    byte[] image(int marker)throws IOException {BufferedImage bitmap=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);bitmap.setRGB(0,0,marker);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",out);bitmap.flush();return out.toByteArray();}
    List<OcrBatch.Input> inputs(int count)throws IOException {List<OcrBatch.Input> out=new ArrayList<>();for(int i=1;i<=count;i++)out.add(new OcrBatch.Input("page-"+i,image(i),"image/png",100,100,"SCAN_PAGE","TEXT"));return out;}
    @Test void qwen35TextRecognitionKeepsDocumentedTextFieldAndSeparatesOldModelCache()throws Exception {
        byte[] image=image(1);assertEquals("合成字段：BC-001",textClient("qwen-vl-ocr-2025-11-20").recognize(image,"image/png").text());
        responder=request->Map.of("text","新模型合成字段：BC-002","ocr_result",Map.of("metadata","not transcription"));
        var current=textClient("qwen3.5-ocr");OcrResult result=current.recognize(image,"image/png");
        assertEquals("qwen3.5-ocr",result.model());assertEquals("新模型合成字段：BC-002",result.text());assertFalse(result.cached());assertEquals(2,requests.size());
        JsonNode sent=requests.get(1);assertEquals("qwen3.5-ocr",sent.path("model").asText());assertEquals("text_recognition",sent.at("/parameters/ocr_options/task").asText());
        assertEquals(1,sent.at("/input/messages/0/content").size());assertFalse(sent.at("/input/messages/0/content/0").has("text"));
        assertTrue(textClient("qwen3.5-ocr").recognize(image,"image/png").cached());assertEquals(2,requests.size());
    }
    @Test void qwen35TextTaskDoesNotInventTextFromUndocumentedResultMetadata()throws Exception {
        responder=request->Map.of("ocr_result",Map.of("metadata","not transcription"));
        assertThrows(IOException.class,()->textClient("qwen3.5-ocr").recognize(image(1),"image/png"));assertFalse(Files.exists(temp.resolve("text")));
    }
    @Test void qwen35AdvancedReadsNativeWordsAndIgnoresCoordinatePreview()throws Exception {
        responder=request->Map.of("text","```json\n[{\"pos_list\":[1,2,3,4]}]\n```","ocr_result",Map.of("words_info",List.of(
            Map.of("text","合成编号：BC-001","rotate_rect",List.of(50,20,80,10,0)),Map.of("text","合成日期：2026-01-01","rotate_rect",List.of(50,50,90,10,0)))));
        var result=advanced().recognize(image(1),"image/png");assertEquals("合成编号：BC-001\n合成日期：2026-01-01",result.text());assertEquals("qwen3.5-ocr",result.model());
        assertEquals("advanced_recognition",requests.get(0).at("/parameters/ocr_options/task").asText());assertEquals("COMPLETE",result.layout().status());
        assertEquals(10,result.layout().textBoxes().get(0).bbox().x());assertEquals(100,result.layout().frames().get(0).width());assertTrue(advanced().recognize(image(1),"image/png").cached());assertEquals(1,requests.size());
    }
    @Test void qwen35AdvancedStillRejectsTextOnlyAndOutOfImageGeometry()throws Exception {
        responder=request->Map.of("text","看似完整的合成转录");assertThrows(IOException.class,()->advanced().recognize(image(1),"image/png"));
        responder=request->Map.of("ocr_result",Map.of("words_info",List.of(Map.of("text","合成文本","rotate_rect",List.of(99,50,20,10,0)))));
        assertThrows(IOException.class,()->advanced().recognize(image(1),"image/png"));assertFalse(Files.exists(temp.resolve("advanced")));assertEquals(2,requests.size());
    }
    @Test void qwen35CustomBatchBindsEightReorderedItemsByIdInOneHttp()throws Exception {
        responder=request->{List<ObjectNode> items=new ArrayList<>();for(JsonNode part:request.at("/input/messages/0/content")){
            String text=part.path("text").asText();if(text.startsWith("IMAGE_META ")){ObjectNode item=(ObjectNode)json.readTree(text.substring(11));item.put("text","合成记录："+item.path("id").asText());items.add(item);}}
            Collections.reverse(items);return Map.of("text",json.writeValueAsString(Map.of("items",items)));};
        List<OcrBatch.Item> results=new ArrayList<>();var input=inputs(8);textClient("qwen3.5-ocr").recognizeBatch(input,results::add);
        assertEquals(1,requests.size());assertEquals(8,results.size());assertTrue(results.stream().allMatch(item->item.result()!=null&&item.result().text().equals("合成记录："+item.id())&&item.result().model().equals("qwen3.5-ocr")));
        JsonNode request=requests.get(0);assertEquals("qwen3.5-ocr",request.path("model").asText());assertFalse(request.path("parameters").has("ocr_options"));
        int images=0;for(JsonNode part:request.at("/input/messages/0/content"))if(part.has("image"))images++;assertEquals(8,images);
        results.clear();textClient("qwen3.5-ocr").recognizeBatch(input,results::add);assertEquals(1,requests.size());assertTrue(results.stream().allMatch(item->item.result().cached()));
    }
    @Test void qwen35BatchCannotGuessMissingOrUnexpectedImageIds()throws Exception {
        responder=request->Map.of("text","{\"items\":[{\"id\":\"not-an-input\",\"text\":\"合成文本\"}]}");
        List<OcrBatch.Item> results=new ArrayList<>();textClient("qwen3.5-ocr").recognizeBatch(inputs(1),results::add);
        assertEquals(1,requests.size());assertEquals(1,results.size());assertNull(results.get(0).result());assertEquals("page-1",results.get(0).id());
    }
}

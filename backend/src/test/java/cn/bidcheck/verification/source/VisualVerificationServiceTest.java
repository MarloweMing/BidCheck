package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VisualVerificationServiceTest {
    @TempDir Path temp;HttpServer server;ObjectMapper json=new ObjectMapper();AtomicInteger calls=new AtomicInteger();AtomicReference<JsonNode> request=new AtomicReference<>();volatile String answer;volatile int delay;Path image;Document document;
    @BeforeEach void setup()throws Exception{image=temp.resolve("synthetic.png");ImageIO.write(new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB),"png",image.toFile());document=EvidenceLayoutServiceTest.document(image,cn.bidcheck.compat.Java8Api.listOf(new Page(1,"","IMAGE","IMAGE_OCR","")));
        server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/vision",exchange->{try{calls.incrementAndGet();request.set(json.readTree(exchange.getRequestBody()));if(delay>0)Thread.sleep(delay);byte[] bytes=json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", answer)))));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}});server.start();}
    @AfterEach void stop(){server.stop(0);}
    VisualVerificationService client(Duration timeout){return new VisualVerificationService(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/vision","test-key","qwen3-vl-plus",timeout,true);}
    String reply(int w,int h,Object bbox,String value)throws Exception{return json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("coordinateSpace", "IMAGE_PIXELS", "imageWidth", w, "imageHeight", h, "observations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("type", "FIELD", "label", "证书编号", "value", value, "readability", "CLEAR", "bbox", bbox)), "limitations", cn.bidcheck.compat.Java8Api.listOf()));}
    @Test void croppedObservationMapsToOriginalAndUsesOnlyImageProtocol()throws Exception{
        answer=reply(100,50,cn.bidcheck.compat.Java8Api.listOf(10, 5, 90, 45),"合成编号甲");java.util.Map<java.lang.String, java.lang.Object> result=client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf("task", "FIELD", "fields", cn.bidcheck.compat.Java8Api.listOf("证书编号"), "region", cn.bidcheck.compat.Java8Api.listOf(.25, .2, .75, .7)));
        assertEquals("OBSERVED",result.get("status"));assertEquals(false,result.get("authenticityVerified"));Map<?,?> observation=(Map<?,?>)((List<?>)result.get("observations")).get(0);assertEquals(cn.bidcheck.compat.Java8Api.listOf(60d, 25d, 140d, 65d),observation.get("sourceBbox"));Map<?,?> source=(Map<?,?>)result.get("source");assertNotNull(source.get("renderedSha256"));assertNotNull(source.get("submittedSha256"));assertEquals(document.sha256(),source.get("documentHash"));assertTrue(request.get().path("messages").get(1).path("content").get(1).path("image_url").path("url").asText().startsWith("data:image/jpeg;base64,"));assertFalse(request.get().toString().contains(image.toString()));
    }
    @Test void invalidCoordinatesCannotProduceGroundedObservations()throws Exception{answer=reply(200,100,cn.bidcheck.compat.Java8Api.listOf(0, 0, 201, 10),"合成字段");java.util.Map<java.lang.String, java.lang.Object> r=client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf());assertEquals("VISUAL_BBOX_INVALID",r.get("errorCode"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),r.get("observations"));}
    @Test void unlocatedPositiveObservationIsRejected()throws Exception{answer="{\"coordinateSpace\":\"IMAGE_PIXELS\",\"imageWidth\":200,\"imageHeight\":100,\"observations\":[{\"type\":\"SEAL\",\"label\":\"印章\",\"value\":\"合成公司\",\"readability\":\"CLEAR\",\"bbox\":null}],\"limitations\":[]}";assertEquals("VISUAL_LOCATION_UNGROUNDED",client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf("task", "SEAL_VISIBILITY")).get("errorCode"));}
    @Test void authenticityOrComplianceFieldsAreNotAccepted()throws Exception{answer="{\"coordinateSpace\":\"IMAGE_PIXELS\",\"imageWidth\":200,\"imageHeight\":100,\"observations\":[],\"limitations\":[],\"authentic\":true}";java.util.Map<java.lang.String, java.lang.Object> r=client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf());assertEquals("VISUAL_SCHEMA_INVALID",r.get("errorCode"));assertEquals(false,r.get("authenticityVerified"));}
    @Test void disabledAndProductionHttpNeverSendRequests(){assertEquals("DISABLED",new VisualVerificationService().verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf()).get("status"));cn.bidcheck.verification.source.VisualVerificationService production=new VisualVerificationService(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/vision","test-key","qwen3-vl-plus");assertFalse(production.enabled());assertEquals(0,calls.get());}
    @Test void hashMismatchAndInvalidRegionDoNotUpload()throws Exception{cn.bidcheck.domain.Domain.Document bad=new Document(document.id(),document.projectId(),document.role(),document.name(),1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",document.pages(),cn.bidcheck.compat.Java8Api.listOf());assertEquals("SOURCE_HASH_MISMATCH",client(Duration.ofSeconds(3)).verify(bad,image,1,cn.bidcheck.compat.Java8Api.mapOf()).get("errorCode"));assertEquals("VISUAL_REGION_INVALID",client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf("region", cn.bidcheck.compat.Java8Api.listOf(-.1, 0, 1, 1))).get("errorCode"));assertEquals(0,calls.get());}
    @Test void boundedTimeoutAndCancellationRemainFailures()throws Exception{delay=600;answer=reply(200,100,cn.bidcheck.compat.Java8Api.listOf(1, 1, 20, 20),"合成");assertEquals("VISUAL_TIMEOUT",client(Duration.ofMillis(100)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf()).get("errorCode"));Thread.currentThread().interrupt();try{assertThrows(CancellationException.class,()->client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf()));}finally{Thread.interrupted();}}
    @Test void invalidJsonDoesNotExposeProviderContent(){answer="secret-provider-document-text";java.util.Map<java.lang.String, java.lang.Object> r=client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf());assertEquals("UNDETERMINED",r.get("status"));assertFalse(r.toString().contains(answer));assertFalse(r.toString().contains("test-key"));}
    @Test void modelLimitationsAreSeparateFromInvariantAuthenticityBoundary()throws Exception{ObjectNode node=(ObjectNode)json.readTree(reply(200,100,cn.bidcheck.compat.Java8Api.listOf(1, 1, 20, 20),"合成字段"));node.putArray("limitations").add("右侧文字被裁切，未核对全部字段");answer=json.writeValueAsString(node);java.util.Map<java.lang.String, java.lang.Object> r=client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf());assertEquals(cn.bidcheck.compat.Java8Api.listOf("右侧文字被裁切，未核对全部字段"),r.get("modelLimitations"));assertEquals(3,((List<?>)r.get("limitations")).size());}
    @Test void ordinaryFieldAndLayoutStillRequireCoordinateMetadata()throws Exception{
        ObjectNode node=(ObjectNode)json.readTree(reply(200,100,cn.bidcheck.compat.Java8Api.listOf(1, 1, 20, 20),"合成字段"));node.remove(cn.bidcheck.compat.Java8Api.listOf("coordinateSpace", "imageWidth", "imageHeight"));answer=json.writeValueAsString(node);
        for(String task:cn.bidcheck.compat.Java8Api.listOf("FIELD", "LAYOUT"))assertEquals("VISUAL_COORDINATE_SPACE_INVALID",client(Duration.ofSeconds(3)).verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf("task", task)).get("errorCode"));
    }
    @Test void structuredRequestsHaveTheirOwnFiniteDeadlineWithoutExtendingOrdinaryRequests(){
        cn.bidcheck.verification.source.VisualVerificationService service=new VisualVerificationService(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/vision","test-key","qwen3-vl-plus",Duration.ofMillis(100),Duration.ofSeconds(2),true,6,4,16,8000000,8388608);
        delay=300;answer="{\"pages\":[{\"page\":1,\"observations\":[],\"limitations\":[]}],\"limitations\":[]}";
        assertEquals("VISUAL_TIMEOUT",service.verify(document,image,1,cn.bidcheck.compat.Java8Api.mapOf("task", "FIELD")).get("errorCode"));
        assertEquals("VISUAL_NO_GROUNDED_OBSERVATIONS",service.extractContext(document,image,cn.bidcheck.compat.Java8Api.listOf(1),()->true,"run").get("errorCode"));assertEquals(2,calls.get());
        assertEquals(Duration.ofMillis(100),service.requestTimeout(cn.bidcheck.compat.Java8Api.mapOf("task", "FIELD")));assertEquals(Duration.ofSeconds(2),service.requestTimeout(cn.bidcheck.compat.Java8Api.mapOf("task", "STRUCTURED_FIELDS")));
    }
    @Test void productionStructuredTimeoutDefaultsAndConfigurationRemainBounded(){
        String url="https://example.invalid/vision";Map<String,Object> structured=cn.bidcheck.compat.Java8Api.mapOf("task", "STRUCTURED_FIELDS");
        cn.bidcheck.verification.source.VisualVerificationService normal=new VisualVerificationService(true,url,"test","test",6,4,16,8000000,8388608);
        assertEquals(Duration.ofSeconds(120),normal.requestTimeout(structured));assertEquals(Duration.ofSeconds(60),normal.requestTimeout(cn.bidcheck.compat.Java8Api.mapOf("task", "LAYOUT")));
        cn.bidcheck.verification.source.VisualVerificationService fallback=new VisualVerificationService(true,url,"test","test",6,4,16,8000000,8388608,0);
        cn.bidcheck.verification.source.VisualVerificationService capped=new VisualVerificationService(true,url,"test","test",6,4,16,8000000,8388608,Integer.MAX_VALUE);
        assertEquals(Duration.ofSeconds(120),fallback.requestTimeout(structured));assertEquals(Duration.ofSeconds(300),capped.requestTimeout(structured));assertNotEquals(normal.identity(),capped.identity());
    }
    @Test void independentRequestModeSurvivesEveryEarlyEmptyFailureBeforeFactConversion(){
        Document pdf=new Document("d","p","BID","source.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"合同编号：TEST-1","PAGE","OCR",""), new Page(3,"合同编号：TEST-3","PAGE","OCR","")),cn.bidcheck.compat.Java8Api.listOf());
        for(String code:cn.bidcheck.compat.Java8Api.listOf("VISUAL_CAPACITY_TIMEOUT", "VISUAL_TIMEOUT", "VISUAL_NOT_CONFIGURED", "VISUAL_NO_GROUNDED_OBSERVATIONS")){
            VisualVerificationService service=new VisualVerificationService(){
                public Map<String,Object> verifyWindow(Document d,Path path,List<Integer> pages,Map<String,Object> req,java.util.function.BooleanSupplier permit,String scope){
                    assertEquals(true,req.get("independentPages"));return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", code, "observations", cn.bidcheck.compat.Java8Api.listOf()));
                }
            };
            java.util.Map<java.lang.String, java.lang.Object> response=service.extractIndependentPages(pdf,image,cn.bidcheck.compat.Java8Api.listOf(1, 3),()->{fail("Early failure must not reserve a model attempt");return false;},"test");
            assertEquals(true,response.get("independentPages"));assertEquals(code,response.get("errorCode"));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(),((Map<?,?>)response.get("factBatch")).get("facts"));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(),((Map<?,?>)response.get("factBatch")).get("contextRelations"));
        }
    }
    @Test void coordinatorFitsConfiguredWindowCapacityWithoutGeneratingAdmissionTimeouts(){
        cn.bidcheck.verification.source.VisualVerificationService service=new VisualVerificationService(true,"https://example.invalid/vision","test","test",6,4,16,8000000,8388608);
        assertEquals(4,service.parallelism());VisualRequestGate gate=new VisualRequestGate();
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),timeouts=new AtomicInteger(),completed=new AtomicInteger();List<VisualWorkCoordinator.Job> jobs=new ArrayList<>();
        for(int i=0;i<12;i++)jobs.add(new VisualWorkCoordinator.Job("window-"+i,permit->{
            try(cn.bidcheck.verification.source.VisualRequestGate.Permit slot=gate.acquire(6,4,true,16,25)){
                if(slot==null){timeouts.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("errorCode", "VISUAL_CAPACITY_TIMEOUT");}
                int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);
                try{assertTrue(permit.getAsBoolean());Thread.sleep(100);return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED");}finally{active.decrementAndGet();}
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
        }));
        VisualWorkCoordinator.run(jobs,service.parallelism(),()->true,()->true,(job,response)->{completed.incrementAndGet();return cn.bidcheck.compat.Java8Api.listOf();});
        assertEquals(12,completed.get());assertEquals(4,peak.get());assertEquals(0,timeouts.get());
    }
}

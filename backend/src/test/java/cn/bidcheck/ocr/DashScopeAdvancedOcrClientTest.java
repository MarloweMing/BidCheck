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
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeAdvancedOcrClientTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger(), redirects = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server; private ExecutorService handlers;
    private String url; private byte[] image;
    private volatile String reply; private volatile int status = 200;
    private volatile boolean partial; private volatile long delay;
    private volatile CountDownLatch entered, release;

    @BeforeEach void setup() throws Exception {
        image = image(0xffffff); reply = envelope(cn.bidcheck.compat.Java8Api.listOf(word("姓名", 40, 20, 40, 10, 0), word("合成人员甲", 50, 50, 70, 12, 0)), "stop");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); handlers = Executors.newFixedThreadPool(8); server.setExecutor(handlers);
        server.createContext("/native", exchange -> {
            calls.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); request.set(json.readTree(exchange.getRequestBody()));
            try {
                if (entered != null) entered.countDown(); if (release != null) release.await(5, TimeUnit.SECONDS); if (delay > 0) Thread.sleep(delay);
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8); if (partial) exchange.getResponseHeaders().set("x-dashscope-partialresponse", "true");
                if (status == 302) exchange.getResponseHeaders().set("Location", "/redirect");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); } catch (IOException closed) { }
            finally { exchange.close(); }
        });
        server.createContext("/redirect", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort() + "/native";
    }
    @AfterEach void stop() { Thread.interrupted(); if (release != null) release.countDown(); server.stop(0); handlers.shutdownNow(); }
    private DashScopeAdvancedOcrClient client() { return client("qwen-vl-ocr-2025-11-20", Duration.ofSeconds(3), 2); }
    private DashScopeAdvancedOcrClient client(String model, Duration timeout, int capacity) { return new DashScopeAdvancedOcrClient(true, url, "advanced-test-secret", model, data.resolve("cache"), timeout, true, capacity); }
    private Map<String,Object> word(String text, double x, double y, double w, double h, double angle) { return cn.bidcheck.compat.Java8Api.mapOf("text", text, "rotate_rect", cn.bidcheck.compat.Java8Api.listOf(x, y, w, h, angle)); }
    private String envelope(Object words, String finish) throws IOException { return json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", finish, "message", cn.bidcheck.compat.Java8Api.mapOf("content", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("text", "NEVER USE THIS COORDINATE PREVIEW", "ocr_result", cn.bidcheck.compat.Java8Api.mapOf("words_info", words))))))))); }
    private byte[] image(int color) throws IOException { BufferedImage b = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB); b.setRGB(0, 0, color); java.io.ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(b, "png", out); b.flush(); return out.toByteArray(); }
    private long cacheCount() throws IOException { if (!Files.exists(data.resolve("cache"))) return 0; try (java.util.stream.Stream<java.nio.file.Path> paths=Files.list(data.resolve("cache"))) { return paths.count(); } }
    private Path cacheFile() throws IOException { try(java.util.stream.Stream<java.nio.file.Path> paths=Files.list(data.resolve("cache"))) { return paths.findFirst().orElseThrow(java.util.NoSuchElementException::new); } }

    @Test void nativeProtocolUsesWordsInfoFromSameResponseAndNoCustomPrompt() throws Exception {
        OcrResult result=client().recognize(image,"image/png"); assertEquals("姓名\n合成人员甲",result.text()); assertEquals("DASHSCOPE",result.provider()); assertFalse(result.cached());
        assertEquals("Bearer advanced-test-secret",authorization.get()); JsonNode body=request.get();
        assertEquals("advanced_recognition",body.at("/parameters/ocr_options/task").asText()); assertEquals(8192,body.at("/parameters/max_tokens").asInt());
        assertEquals(1,body.at("/input/messages").size()); assertEquals(1,body.at("/input/messages/0/content").size());
        assertFalse(body.at("/input/messages/0/content/0").has("text")); assertFalse(body.at("/input/messages/0/content/0/enable_rotate").asBoolean());
        assertEquals("COMPLETE",result.layout().status()); assertTrue(result.layout().tables().isEmpty());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("w1", "w2"),result.layout().readingOrder()); assertEquals(320,result.layout().frames().get(0).width());
        assertEquals(64,result.layout().frames().get(0).imageHash().length()); assertNull(result.layout().textBoxes().get(0).confidence());
    }
    @Test void rotationUsesDegreeEnvelopeAndNeverAssumesNormalizedCoordinates() throws Exception {
        reply=envelope(cn.bidcheck.compat.Java8Api.listOf(word("直角",100,80,20,60,-90), word("斜角",180,100,20,20,45), word("小框",.5,.5,.2,.2,0)),"stop");
        java.util.List<cn.bidcheck.ocr.OcrLayout.TextBox> boxes=client().recognize(image,"image/png").layout().textBoxes();
        assertEquals(70,boxes.get(0).bbox().x(),1e-8);assertEquals(70,boxes.get(0).bbox().y(),1e-8);assertEquals(60,boxes.get(0).bbox().width(),1e-8);assertEquals(20,boxes.get(0).bbox().height(),1e-8);
        assertEquals(20*Math.sqrt(2),boxes.get(1).bbox().width(),1e-8);assertEquals(.4,boxes.get(2).bbox().x(),1e-8);assertEquals(.2,boxes.get(2).bbox().width(),1e-8);
    }
    @Test void preservesProviderOrderAndRepeatedTextAsDistinctOccurrences() throws Exception {
        reply=envelope(cn.bidcheck.compat.Java8Api.listOf(word("同文",70,100,30,10,0), word("同文",70,20,30,10,0)),"stop");
        cn.bidcheck.ocr.OcrResult result=client().recognize(image,"image/png");assertEquals("同文\n同文",result.text());assertEquals(95,result.layout().textBoxes().get(0).bbox().y());
    }
    @Test void validatesPersistentCacheAndSeparatesImageModelEndpointAndTranscriptionPolicies() throws Exception {
        cn.bidcheck.ocr.DashScopeAdvancedOcrClient c=client();assertFalse(c.recognize(image,"image/png").cached());assertTrue(client().recognize(image,"image/png").cached());assertEquals(1,calls.get());
        String stored=cn.bidcheck.compat.Java8Api.readString(cacheFile());assertFalse(stored.contains("advanced-test-secret"));assertFalse(stored.contains("NEVER USE"));
        com.fasterxml.jackson.databind.node.ObjectNode corrupt=(ObjectNode)json.readTree(stored);((ArrayNode)corrupt.path("words")).removeAll();cn.bidcheck.compat.Java8Api.writeString(cacheFile(), json.writeValueAsString(corrupt));
        assertFalse(c.recognize(image,"image/png").cached());assertEquals(2,calls.get());
        c.recognize(image(0x123456),"image/png");client("other-model",Duration.ofSeconds(3),2).recognize(image,"image/png");assertEquals(4,calls.get());
        cn.bidcheck.ocr.DashScopeAdvancedOcrClient otherEndpoint=new DashScopeAdvancedOcrClient(true,url+"/other","key","qwen-vl-ocr-2025-11-20",data.resolve("cache"),Duration.ofSeconds(3),true,2);
        assertNotEquals(c.identity(),otherEndpoint.identity());
        assertNotEquals(c.identity(),new DashScopeOcrClient(true,url,"key","qwen-vl-ocr-2025-11-20",data.resolve("cache"),Duration.ofSeconds(3),true).identity());
    }
    @Test void rejectsMissingWordsEvenWhenTextPreviewContainsPlausibleProse() throws Exception {
        reply="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"text\":\"真实似的正文\"}]}}]}}";
        assertEquals("WORDS_LIMIT_OR_MISSING",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());assertEquals(0,cacheCount());
    }
    @Test void rejectsInvalidGeometryRatherThanClampingOrScalingIt() throws Exception {
        for(Object rect:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(-20, 20, 10, 10, 0), cn.bidcheck.compat.Java8Api.listOf(318, 20, 20, 10, 0), cn.bidcheck.compat.Java8Api.listOf(40, 20, 0, 10, 0), cn.bidcheck.compat.Java8Api.listOf(40, 20, 10, 10, 91), cn.bidcheck.compat.Java8Api.listOf("40", 20, 10, 10, 0), cn.bidcheck.compat.Java8Api.listOf(40, 20, 10, 10), cn.bidcheck.compat.Java8Api.listOf(40, 20, 1e300, 10, 0))) {
            reply=envelope(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("text", "测试", "rotate_rect", rect)),"stop");assertEquals("INVALID_GEOMETRY",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());
        }assertEquals(0,cacheCount());
    }
    @Test void rejectsIncompleteDuplicateOversizedAndNonTextRepliesWithoutLeakingBody() throws Exception {
        reply=envelope(cn.bidcheck.compat.Java8Api.listOf(word("测试",40,20,20,10,0)),"length");assertEquals("MODEL_TRUNCATED",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());
        reply=envelope(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("text", true, "rotate_rect", cn.bidcheck.compat.Java8Api.listOf(40, 20, 20, 10, 0))),"stop");assertThrows(IOException.class,()->client().recognize(image,"image/png"));
        reply="{\"private\":\"secret-document\",\"private\":\"advanced-test-secret\"}";java.io.IOException invalid=assertThrows(IOException.class,()->client().recognize(image,"image/png"));assertFalse(invalid.getMessage().contains("secret"));assertNull(invalid.getCause());
        reply=cn.bidcheck.compat.Java8Api.repeat("x", 1024*1024+1);assertEquals("RESPONSE_LIMIT",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());assertEquals(0,cacheCount());
    }
    @Test void wordAndTextBudgetsAreBounded() throws Exception {
        reply=envelope(Collections.nCopies(OcrLayout.MAX_BOXES+1,word("甲",40,20,10,10,0)),"stop");assertThrows(IOException.class,()->client().recognize(image,"image/png"));
        reply=envelope(Collections.nCopies(6,word(cn.bidcheck.compat.Java8Api.repeat("甲", 9000),40,20,10,10,0)),"stop");assertEquals("TEXT_LIMIT",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());assertEquals(0,cacheCount());
    }
    @Test void partialAndRedirectResponsesAreRejectedWithoutFollowingRedirect() throws Exception {
        partial=true;assertEquals("PARTIAL_RESPONSE",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());
        partial=false;status=302;assertEquals("HTTP_302",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client().recognize(image,"image/png")).code());assertEquals(0,redirects.get());assertEquals(0,cacheCount());
    }
    @Test void invalidImagesAndProductionHttpNeverReachServer() throws Exception {
        cn.bidcheck.ocr.DashScopeAdvancedOcrClient c=client();assertThrows(IOException.class,()->c.recognize(image,"image/jpeg"));assertThrows(IOException.class,()->c.recognize(image,null));assertThrows(IOException.class,()->c.recognize(new byte[6*1024*1024+1],"image/png"));
        byte[] huge=image.clone();huge[16]=0x7f;huge[17]=(byte)0xff;huge[18]=(byte)0xff;huge[19]=(byte)0xff;assertThrows(IOException.class,()->c.recognize(huge,"image/png"));
        cn.bidcheck.ocr.DashScopeAdvancedOcrClient http=new DashScopeAdvancedOcrClient(true,url,"key","model",data.toString());assertFalse(http.enabled());assertThrows(IOException.class,()->http.recognize(image,"image/png"));assertEquals(0,calls.get());
        assertThrows(IllegalArgumentException.class,()->client("model",Duration.ofSeconds(61),2));assertThrows(IllegalArgumentException.class,()->client("model",Duration.ofSeconds(1),9));
    }
    @Test void sameImageConcurrencyUsesOneNetworkRequestAndReleasesLock() throws Exception {
        entered=new CountDownLatch(1);release=new CountDownLatch(1);cn.bidcheck.ocr.DashScopeAdvancedOcrClient c=client();ExecutorService workers=Executors.newFixedThreadPool(2);
        try {Future<OcrResult> first=workers.submit(()->c.recognize(image,"image/png"));assertTrue(entered.await(2,TimeUnit.SECONDS));Future<OcrResult> second=workers.submit(()->c.recognize(image,"image/png"));release.countDown();assertFalse(first.get(3,TimeUnit.SECONDS).cached());assertTrue(second.get(3,TimeUnit.SECONDS).cached());assertEquals(1,calls.get());}
        finally {release.countDown();workers.shutdownNow();}
    }
    @Test void queuedRequestReceivesFreshNetworkBudget() throws Exception {
        delay=260;cn.bidcheck.ocr.DashScopeAdvancedOcrClient c=client("queue-model",Duration.ofMillis(480),1);entered=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        try {Future<OcrResult> first=workers.submit(()->c.recognize(image,"image/png"));assertTrue(entered.await(2,TimeUnit.SECONDS));Future<OcrResult> second=workers.submit(()->c.recognize(image(0xabcdef),"image/png"));assertNotNull(first.get(2,TimeUnit.SECONDS));assertNotNull(second.get(2,TimeUnit.SECONDS));assertEquals(2,calls.get());}
        finally {workers.shutdownNow();}
    }
    @Test void timeoutAndInterruptAreBoundedAndDoNotPoisonNextRequest() throws Exception {
        delay=600;assertEquals("TIMEOUT",assertThrows(DashScopeAdvancedOcrClient.AdvancedOcrException.class,()->client("timeout",Duration.ofMillis(120),1).recognize(image,"image/png")).code());
        delay=0;Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->client().recognize(image,"image/png"));assertTrue(Thread.interrupted());
        assertNotNull(client().recognize(image,"image/png"));
    }
    @Test void interruptionDuringRequestStopsAndReleasesCapacity() throws Exception {
        entered=new CountDownLatch(1);release=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();cn.bidcheck.ocr.DashScopeAdvancedOcrClient c=client("interruption",Duration.ofSeconds(2),1);
        Thread worker=new Thread(()->{try{c.recognize(image,"image/png");}catch(Throwable e){failure.set(e);flag.set(Thread.currentThread().isInterrupted());}});worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS));worker.interrupt();worker.join(2000);assertFalse(worker.isAlive());assertInstanceOf(InterruptedIOException.class,failure.get());assertTrue(flag.get());release.countDown();assertNotNull(c.recognize(image,"image/png"));
    }
}

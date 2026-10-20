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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeBatchOcrClientTest {
    @TempDir Path data;
    private final ObjectMapper json=new ObjectMapper();
    private final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    private final AtomicInteger calls=new AtomicInteger();
    private HttpServer server;private ExecutorService serverThreads;private String url;
    private volatile Responder responder;
    private volatile CountDownLatch release;
    @FunctionalInterface interface Responder { Reply respond(List<ObjectNode> items,int call)throws Exception; }
    record Reply(int status,String body) { }
    @BeforeEach void setup()throws Exception {
        responder=(items,call)->reply(items);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);serverThreads=Executors.newFixedThreadPool(8);server.setExecutor(serverThreads);
        server.createContext("/ocr",exchange->{try{
            JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);
            Reply response=responder.respond(items(request),calls.incrementAndGet());byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(),bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception stopped){if(stopped instanceof InterruptedException)Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/ocr";
    }
    @AfterEach void close(){if(release!=null)release.countDown();server.stop(0);serverThreads.shutdownNow();}
    private DashScopeOcrClient client(){return client(Duration.ofSeconds(3),3,OcrBatchPolicy.defaults());}
    private DashScopeOcrClient client(Duration timeout,int attempts,OcrBatchPolicy policy){return new DashScopeOcrClient(true,url,"stub-secret","qwen-vl-ocr-2025-11-20",data.resolve("cache"),timeout,true,1,new OcrRequestSettings(16,attempts,1,2,0,8,100,100),policy);}
    private List<OcrBatch.Input> inputs(int count)throws IOException {
        List<OcrBatch.Input> values=new ArrayList<>();for(int i=1;i<=count;i++){
            BufferedImage bitmap=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);bitmap.setRGB(0,0,0xffffff-i);
            ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",output);
            values.add(new OcrBatch.Input("page-"+i,output.toByteArray(),"image/png",64,64,"SCAN_PAGE","TEXT"));}
        return values;
    }
    private List<ObjectNode> items(JsonNode request)throws IOException {
        List<ObjectNode> items=new ArrayList<>();for(JsonNode part:request.path("input").path("messages").get(0).path("content")){
            String text=part.path("text").asText();if(text.startsWith("IMAGE_META ")){
                ObjectNode item=(ObjectNode)json.readTree(text.substring(11));item.put("text","transcribed "+item.path("id").asText());items.add(item);}}
        return items;
    }
    private Reply reply(List<ObjectNode> items)throws IOException {return new Reply(200,envelope(json.writeValueAsString(Map.of("items",items)),"stop"));}
    private String envelope(String text,String finish)throws IOException {return json.writeValueAsString(Map.of("output",Map.of("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("role","assistant","content",List.of(Map.of("text",text))))))));}
    private List<OcrBatch.Item> run(DashScopeOcrClient client,List<OcrBatch.Input> inputs)throws IOException {List<OcrBatch.Item> result=new ArrayList<>();client.recognizeBatch(inputs,result::add);return result;}
    private List<Integer> sizes()throws IOException {List<Integer> sizes=new ArrayList<>();for(var request:requests)sizes.add(items(request).size());return sizes;}

    @Test void eightImagesUseOneHttpAndIndependentPersistentCache()throws Exception {
        var input=inputs(8);List<OcrBatch.Event> events=new ArrayList<>();List<OcrBatch.Item> results=new ArrayList<>();
        client().recognizeBatch(input,new OcrBatch.Listener(){public void accept(OcrBatch.Item item){results.add(item);}public void onBatch(OcrBatch.Event event){events.add(event);}});
        assertEquals(1,calls.get());assertEquals(8,results.size());assertTrue(results.stream().allMatch(i->i.result()!=null&&!i.result().cached()));
        var request=requests.get(0);var messages=request.path("input").path("messages");assertEquals(1,messages.size());assertEquals("user",messages.get(0).path("role").asText());
        int images=0;for(var part:messages.get(0).path("content"))if(part.has("image"))images++;
        assertEquals(8,images);assertFalse(request.path("parameters").has("ocr_options"));assertFalse(request.path("parameters").has("response_format"));
        assertEquals("qwen-vl-ocr-2025-11-20",request.path("model").asText());
        assertTrue(events.stream().anyMatch(e->e.status().equals("SUCCEEDED")&&e.inputIds().size()==8));
        assertTrue(events.stream().allMatch(e->e.inputHash().matches("[a-f0-9]{64}")&&!e.toString().contains("stub-secret")&&!e.toString().contains("transcribed")));
        var cached=run(client(),input);assertEquals(1,calls.get());assertTrue(cached.stream().allMatch(i->i.result().cached()));
        try(var files=Files.walk(data)){for(var file:files.filter(Files::isRegularFile).toList()){String content=Files.readString(file);assertFalse(content.contains("stub-secret"));assertFalse(content.contains("base64"));}}
    }

    @Test void missingPageRetriesOnlyMissingAndPublishesSuccessBeforeRecovery()throws Exception {
        List<OcrBatch.Item> results=new ArrayList<>();List<OcrBatch.Event> events=new ArrayList<>();responder=(items,call)->{if(call==1)return reply(items.subList(0,2));assertEquals(2,results.size());assertEquals("page-3",items.get(0).path("id").asText());return reply(items);};
        client().recognizeBatch(inputs(3),new OcrBatch.Listener(){public void accept(OcrBatch.Item item){results.add(item);}public void onBatch(OcrBatch.Event event){events.add(event);}});
        assertEquals(List.of(3,1),sizes());assertEquals(3,results.size());assertTrue(results.stream().allMatch(i->i.result()!=null));
        assertTrue(events.stream().anyMatch(e->e.status().equals("ITEM_REJECTED")&&e.errorCode().equals("BATCH_MISSING_ITEM")&&e.inputIds().equals(List.of("page-3"))));
        assertEquals("RECOVERED",events.get(events.size()-1).status());
    }

    @Test void invalidBatchBisectsEightToFourToTwoToOneWithoutRepeatingSuccess()throws Exception {
        responder=(items,call)->items.size()>1?new Reply(200,envelope("malformed","stop")):reply(items);
        List<OcrBatch.Item> results=new ArrayList<>();List<OcrBatch.Event> events=new ArrayList<>();
        client().recognizeBatch(inputs(8),new OcrBatch.Listener(){public void accept(OcrBatch.Item item){results.add(item);}public void onBatch(OcrBatch.Event event){events.add(event);}});
        assertEquals(List.of(8,4,2,1,1,2,1,1,4,2,1,1,2,1,1),sizes());assertEquals(8,results.size());assertTrue(results.stream().allMatch(i->i.result()!=null));
        for(var event:events)if(event.status().equals("SPLITTING"))assertTrue(events.stream().anyMatch(e->e.id().equals(event.id())&&e.status().equals("SPLIT_COMPLETED")));
        run(client(),inputs(8));assertEquals(15,calls.get());
    }

    @Test void duplicateOrUnknownIdsInvalidateAllItemsBeforeCaching()throws Exception {
        responder=(items,call)->{if(items.size()>1){items.get(0).put("text","poison");items.get(1).put("id",items.get(0).path("id").asText());}return reply(items);};
        var result=run(client(),inputs(2));assertEquals(List.of(2,1,1),sizes());assertTrue(result.stream().noneMatch(i->i.result().text().contains("poison")));
    }

    @Test void mismatchedImageHashOrDimensionRejectsOnlyThatItem()throws Exception {
        responder=(items,call)->{if(call==1)items.get(1).put("imageHash","0".repeat(64));return reply(items);};
        var results=run(client(),inputs(3));assertEquals(List.of(3,1),sizes());assertEquals(3,results.size());assertTrue(results.stream().allMatch(i->i.result()!=null));
    }

    @Test void authAndExhaustedRateLimitNeverSplit()throws Exception {
        responder=(items,call)->new Reply(401,"{}");var denied=run(client(),inputs(8));assertEquals(1,calls.get());assertTrue(denied.stream().allMatch(i->i.result()==null&&!i.retryable()&&i.errorCode().equals("HTTP_401")));
        responder=(items,call)->new Reply(429,"{}");var limited=run(client(),inputs(8));assertEquals(4,calls.get());assertTrue(limited.stream().allMatch(i->i.result()==null&&i.retryable()&&i.errorCode().equals("HTTP_429")));
        responder=(items,call)->new Reply(503,"{}");var unavailable=run(client(),inputs(8));assertEquals(7,calls.get());assertTrue(unavailable.stream().allMatch(i->i.result()==null&&i.retryable()&&i.errorCode().equals("HTTP_503")));
    }

    @Test void authEncounteredInsideSplitStopsUnsentSiblingRequests()throws Exception {
        responder=(items,call)->call==1?new Reply(200,envelope("bad","stop")):new Reply(403,"{}");
        var result=run(client(),inputs(8));assertEquals(2,calls.get());assertEquals(8,result.size());assertTrue(result.stream().allMatch(i->i.errorCode().equals("HTTP_403")));
    }

    @Test void oneSlowBatchCanRecoverBySplittingAfterBoundedTimeout()throws Exception {
        responder=(items,call)->{if(items.size()>1)Thread.sleep(900);return reply(items);};
        var result=run(client(Duration.ofMillis(250),1,OcrBatchPolicy.defaults()),inputs(2));assertEquals(List.of(2,1,1),sizes());assertTrue(result.stream().allMatch(i->i.result()!=null));
    }

    @Test void sourceBoundBoxesPreserveOnePageWhileBlankPageIsNotCached()throws Exception {
        responder=(items,call)->{
            for(var item:items)if(item.path("id").asText().equals("page-1")){
                item.put("text","hello");item.set("boxes",json.valueToTree(List.of(Map.of("text","hello","x",1,"y",2,"width",20,"height",10,"confidence",0.8))));
            }else item.put("text","");return reply(items);
        };
        var input=inputs(2);var result=run(client(),input);assertEquals("PARTIAL",result.get(0).result().layout().status());
        var frame=result.get(0).result().layout().frames().get(0);assertEquals(64,frame.width());assertEquals(64,frame.height());assertNull(result.get(1).result());
        assertTrue(run(client(),input).get(0).result().cached());assertEquals(3,calls.get());
    }

    @Test void invalidOptionalBoxPreservesTextWithoutAnyRepeatedRequest()throws Exception {
        responder=(items,call)->{for(var item:items)if(item.path("id").asText().equals("page-2"))item.set("boxes",json.valueToTree(List.of(Map.of("text",item.path("text").asText(),"x",63,"y",2,"width",20,"height",10))));return reply(items);};
        var result=run(client(),inputs(2));assertTrue(result.stream().allMatch(i->i.result()!=null));assertEquals(List.of(2),sizes());
        assertEquals("UNAVAILABLE",result.get(1).result().layout().status());assertTrue(result.get(1).result().layout().warnings().get(0).contains("BATCH_INVALID_GEOMETRY"));
        var cached=run(client(),inputs(2));assertTrue(cached.stream().allMatch(i->i.result().cached()));assertEquals(1,calls.get());assertTrue(cached.get(1).result().layout().warnings().get(0).contains("BATCH_INVALID_GEOMETRY"));
    }

    @Test void callbackLeaseFailurePropagatesAndDoesNotTriggerSplitOrReplay()throws Exception {
        IOException leaseLost=new IOException("lease lost");var input=inputs(2);
        IOException actual=assertThrows(IOException.class,()->client().recognizeBatch(input,item->{throw leaseLost;}));assertSame(leaseLost,actual);assertEquals(1,calls.get());
        var recovered=run(client(),input);assertEquals(2,calls.get());assertEquals(1,items(requests.get(1)).size());assertTrue(recovered.stream().allMatch(i->i.result()!=null));
    }

    @Test void rejectedItemsAreNeverCachedAndDiagnosticLeaseFailureStopsRecovery()throws Exception {
        responder=(items,call)->{for(var item:items)if(item.path("id").asText().equals("page-2"))item.put("imageHash","0".repeat(64));return reply(items);};
        IOException lease=new IOException("diagnostic lease lost");List<OcrBatch.Item> accepted=new ArrayList<>();
        assertSame(lease,assertThrows(IOException.class,()->client().recognizeBatch(inputs(2),new OcrBatch.Listener(){
            public void accept(OcrBatch.Item item){accepted.add(item);}
            public void onBatch(OcrBatch.Event event)throws IOException{if(event.status().equals("ITEM_REJECTED")){assertEquals("BATCH_HASH_MISMATCH",event.errorCode());throw lease;}}
        })));
        assertEquals(1,calls.get());assertEquals(1,accepted.size());assertNotNull(accepted.get(0).result());
        var again=run(client(),inputs(2));assertEquals(2,calls.get());assertTrue(again.get(0).result().cached());assertNull(again.get(1).result());assertEquals("BATCH_HASH_MISMATCH",again.get(1).errorCode());
        run(client(),inputs(2));assertEquals(3,calls.get(),"A rejected image must remain retryable by the caller, never an erroneous cache hit");
    }

    @Test void cancellationStopsWholeTreeAndAllowsLaterRecovery()throws Exception {
        CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);responder=(items,call)->{entered.countDown();release.await(5,TimeUnit.SECONDS);return reply(items);};
        AtomicReference<Throwable> failure=new AtomicReference<>();Thread worker=new Thread(()->{try{run(client(),uncheckedInputs(8));}catch(Throwable stopped){failure.set(stopped);}});
        worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));worker.interrupt();worker.join(2000);assertFalse(worker.isAlive());assertInstanceOf(InterruptedIOException.class,failure.get());assertEquals(1,calls.get());
        release.countDown();responder=(items,call)->reply(items);assertEquals(8,run(client(),inputs(8)).size());assertEquals(2,calls.get());
    }

    @Test void overlappingImageWaitsWithoutTakingAnotherHttpPermit()throws Exception {
        CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);responder=(items,call)->{if(call==1){entered.countDown();release.await(5,TimeUnit.SECONDS);}return reply(items);};
        var input=inputs(2);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<List<OcrBatch.Item>> first=workers.submit(()->run(client(),input));assertTrue(entered.await(3,TimeUnit.SECONDS));
            Future<List<OcrBatch.Item>> duplicate=workers.submit(()->run(client(),List.of(input.get(0))));
            Thread.sleep(80);assertEquals(1,calls.get());release.countDown();assertEquals(2,first.get(3,TimeUnit.SECONDS).size());assertTrue(duplicate.get(3,TimeUnit.SECONDS).get(0).result().cached());assertEquals(1,calls.get());
        }finally{workers.shutdownNow();}
    }

    @Test void invalidCallerDimensionsMakeNoRequestAndCacheIsBoundToSchema()throws Exception {
        var input=inputs(1).get(0);assertThrows(IOException.class,()->run(client(),List.of(new OcrBatch.Input(input.id(),input.image(),input.mimeType(),63,64,"SCAN_PAGE","TEXT"))));assertEquals(0,calls.get());
        run(client(),List.of(input));run(client(),List.of(new OcrBatch.Input("other-id",input.image(),input.mimeType(),64,64,"SCAN_PAGE","TEXT")));assertEquals(1,calls.get());
        run(client(),List.of(new OcrBatch.Input("other-id",input.image(),input.mimeType(),64,64,"SCAN_PAGE","OTHER_SCHEMA")));assertEquals(2,calls.get());
    }

    @Test void structuredNativeModeKeepsTrueBatchWithoutCallingAdvancedAgain()throws Exception {
        AtomicInteger advancedCalls=new AtomicInteger();
        OcrClient advanced=new OcrClient(){public boolean enabled(){return true;}public String modelName(){return "native";}public String identity(){return "native";}public OcrResult recognize(byte[] image,String mime){advancedCalls.incrementAndGet();throw new AssertionError("Batch must not recognize the same image again");}};
        Path runtime=Files.writeString(data.resolve("unused-runtime"),""),worker=Files.writeString(data.resolve("unused-worker"),"");
        try(var grids=new PixelGridClient(true,runtime.toString(),worker.toString(),data.toString(),1,1)){
            var structured=new StructuredOcrClient(client(),advanced,grids);assertTrue(structured.layoutEnabled());assertTrue(structured.batchPolicy().enabled());
            List<OcrBatch.Item> results=new ArrayList<>();structured.recognizeBatch(inputs(8),results::add);
            assertEquals(1,calls.get());assertEquals(0,advancedCalls.get());assertEquals(8,results.size());assertTrue(results.stream().allMatch(i->i.result().layout().status().equals("UNAVAILABLE")));
        }
    }

    @Test void disabledStructuredLayoutStillForwardsBatchAndItsEvents()throws Exception {
        var legacy=new DashScopeLayoutClient(false,"","","",data.toString(),1,2);var structured=new StructuredOcrClient(client(),legacy);
        List<OcrBatch.Item> results=new ArrayList<>();List<OcrBatch.Event> events=new ArrayList<>();
        structured.recognizeBatch(inputs(3),new OcrBatch.Listener(){public void accept(OcrBatch.Item item){results.add(item);}public void onBatch(OcrBatch.Event event){events.add(event);}});
        assertEquals(1,calls.get());assertEquals(3,results.size());assertTrue(events.stream().anyMatch(e->e.status().equals("RUNNING")));
    }

    @Test void exhaustedWholeTreeDeadlineStopsFurtherHttpRequests()throws Exception {
        responder=(items,call)->{Thread.sleep(400);return reply(items);};long started=System.nanoTime();
        var result=run(client(Duration.ofMillis(70),3,OcrBatchPolicy.defaults()),inputs(8));
        assertEquals(8,result.size());assertTrue(result.stream().allMatch(i->i.result()==null));assertTrue(calls.get()<45);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3500,"One tree shares a deadline, including provider retries and splits");
        assertTrue(result.stream().anyMatch(i->Set.of("BATCH_BUDGET_EXHAUSTED","QUEUE_TIMEOUT").contains(i.errorCode())));
    }
    private List<OcrBatch.Input> uncheckedInputs(int count){try{return inputs(count);}catch(IOException failed){throw new UncheckedIOException(failed);}}
}

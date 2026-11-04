package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.VerificationService;
import cn.bidcheck.verification.engine.VerificationEngine;
import cn.bidcheck.verification.external.ExternalVerificationAdapter;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.ocr.OcrClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VisualWindowTest {
    void publishCached(CollectionHarness h,TaskRow submitted,List<Map<String,Object>> observations)throws Exception{
        java.lang.reflect.Method method=VerificationService.class.getDeclaredMethod("persistCheckVisualObservations",TaskRow.class,String.class,String.class,List.class);
        try{method.invoke(h.service,submitted,"check-run","new-check",observations);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException)throw (RuntimeException)failure.getCause();throw failure;}
    }
    @Test void cachedObservationPublicationRetainsRunAttemptAndLeaseFence()throws Exception{
        CollectionHarness h=new CollectionHarness(12,2);h.store.save("run","check-run","project",document.id(),cn.bidcheck.service.BidService.fields("projectId","project","taskId",h.task.id,"status","RUNNING"));
        List<Map<String,Object>> observations=Collections.singletonList(cn.bidcheck.compat.Java8Api.mapOf("id","cached","documentId",document.id()));int before=h.records.size();
        h.task.status="CANCELLED";assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,()->publishCached(h,h.task,observations));assertEquals(before,h.records.size());
        h.task.status="RUNNING";h.lease.set(false);assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,()->publishCached(h,h.task,observations));assertEquals(before,h.records.size());
        h.lease.set(true);h.task.leaseOwner="owner";h.task.leaseUntil=0L;assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,()->publishCached(h,h.task,observations));assertEquals(before,h.records.size());
        h.task.leaseUntil=System.currentTimeMillis()+60000;TaskRow old=new TaskRow();old.id=h.task.id;old.projectId=h.task.projectId;old.leaseOwner=h.task.leaseOwner;old.attempt=h.task.attempt+1;
        assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,()->publishCached(h,old,observations));assertEquals(before,h.records.size());
        publishCached(h,h.task,observations);assertEquals(before+1,h.records.size());
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> cached(CollectionHarness h,List<Document> docs,List<Map<String,Object>> requests)throws Exception{
        java.lang.reflect.Method method=VerificationService.class.getDeclaredMethod("collectCachedCheckVisual",TaskRow.class,String.class,Checkpoint.class,Map.class,List.class,List.class);
        Checkpoint cp=new Checkpoint("new-check","缓存观察","形式","HIGH","MATERIAL","","","","",true,true,true,null);
        return (List<Map<String,Object>>)method.invoke(h.service,h.task,"new-run",cp,cn.bidcheck.compat.Java8Api.mapOf("visualRequests",requests),docs,h.diagnostics);
    }
    @Test void firstRoundCacheOnlyRebindsCurrentFieldsAndNeverSchedulesMissingPages()throws Exception{
        CollectionHarness h=new CollectionHarness(12,2);Map<String,Object> warm=requested(document,6);
        h.collect(Collections.singletonList(document),Collections.singletonList(warm));assertEquals(1,calls.get());int stored=h.records.size();
        Map<String,Object> same=new LinkedHashMap<>(warm);same.put("obligationId","new-obligation");
        List<Map<String,Object>> result=cached(h,Collections.singletonList(document),Arrays.asList(same,requested(document,2)));
        assertEquals(1,result.size());assertEquals("new-obligation",result.get(0).get("obligationId"));assertEquals("new-check",result.get(0).get("checkpointId"));
        assertEquals(1,calls.get());assertEquals(stored,h.records.size(),"cache-only must not create jobs, leases, calls or page states");
        Map<String,Object> otherField=new LinkedHashMap<>(same);otherField.put("fields",Collections.singletonList("合同金额"));
        assertTrue(cached(h,Collections.singletonList(document),Collections.singletonList(otherField)).isEmpty());
        assertTrue(cached(h,Collections.singletonList(version(document.id(),2)),Collections.singletonList(same)).isEmpty());
        Document another=version("other-doc",1);assertTrue(cached(h,Collections.singletonList(another),Collections.singletonList(requested(another,6))).isEmpty());
        assertEquals(1,calls.get());assertEquals(stored,h.records.size());
        for(Stored row:h.records.values())if(row.body!=null)row.body=row.body.replace(document.sha256(),"invalid-source-hash");
        assertTrue(cached(h,Collections.singletonList(document),Collections.singletonList(same)).isEmpty(),"rejected original-image provenance must never be rebound");
        assertEquals(1,calls.get());assertEquals(stored,h.records.size());
    }
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger(),windowActive=new AtomicInteger(),windowPeak=new AtomicInteger();
    final List<JsonNode> payloads=new CopyOnWriteArrayList<>();
    HttpServer server;ExecutorService httpThreads;Path pdf;Document document;
    volatile String mode="",retryAfter="";volatile int delay;
    @BeforeEach void setup()throws Exception {
        pdf=temp.resolve("context.pdf");try(PDDocument file=new PDDocument()){for(int i=0;i<6;i++)file.addPage(new PDPage(new PDRectangle(400,300)));file.save(pdf.toFile());}
        List<Page> pages=new ArrayList<>();for(int p=1;p<=6;p++)pages.add(new Page(p,"合同编号："+(p<6?"TEST-2026-001":"OTHER-2026-002")+"\n合同正文合成字段","PAGE","PDF_TEXT",""));
        document=new Document("doc","project","BID","context.pdf",1,SourceSafety.digest(Files.readAllBytes(pdf)),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);httpThreads=Executors.newCachedThreadPool();server.setExecutor(httpThreads);
        server.createContext("/vision",exchange->{int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);calls.incrementAndGet();boolean window=false;
            try {
                JsonNode payload=json.readTree(exchange.getRequestBody());payloads.add(payload);JsonNode instruction=json.readTree(payload.path("messages").get(1).path("content").get(0).path("text").asText());
                window=instruction.has("pages");if(window)windowPeak.accumulateAndGet(windowActive.incrementAndGet(),Math::max);
                if(delay>0)Thread.sleep(delay);if(cn.bidcheck.compat.Java8Api.setOf("http", "rate-limit").contains(mode)||mode.equals("partial-single-http")&&!instruction.has("pages")){
                    if(!cn.bidcheck.compat.Java8Api.isBlank(retryAfter))exchange.getResponseHeaders().add("Retry-After",retryAfter);exchange.sendResponseHeaders(mode.equals("rate-limit")?429:503,-1);return;
                }
                ObjectNode answer;
                if(instruction.has("pages")){
                    answer=json.createObjectNode();ArrayNode output=answer.putArray("pages");for(JsonNode p:instruction.path("pages"))output.add(pageAnswer(p.path("imageWidth").asInt(),p.path("imageHeight").asInt()).put("page",p.path("page").asInt()));answer.putArray("limitations");
                    if(mode.equals("extra-page")||mode.equals("window-invalid"))((ObjectNode)output.get(0)).put("page",999);
                    if(mode.equals("duplicate-page"))((ObjectNode)output.get(1)).put("page",output.get(0).path("page").asInt());
                    if(mode.equals("missing-page")||mode.equals("partial-single-http"))output.remove(0);
                    if(mode.equals("dimensions"))((ObjectNode)output.get(0)).put("imageWidth",1);
                }else answer=pageAnswer(instruction.path("imageWidth").asInt(),instruction.path("imageHeight").asInt());
                byte[] bytes=json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", json.writeValueAsString(answer))))));
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception ignored){}finally{if(window)windowActive.decrementAndGet();active.decrementAndGet();exchange.close();}
        });server.start();
    }
    ObjectNode pageAnswer(int width,int height){
        ObjectNode answer=json.createObjectNode().put("coordinateSpace","IMAGE_PIXELS").put("imageWidth",width).put("imageHeight",height);answer.putArray("limitations");
        ObjectNode o=answer.putArray("observations").addObject().put("type","FIELD").put("label",mode.equals("field")?"未请求字段":"合同编号").put("value","TEST-2026-001").put("readability","CLEAR");
        o.putArray("bbox").add(1).add(1).add(mode.equals("bbox")?width+1:20).add(20);if(mode.equals("verdict"))o.put("authentic",true);return answer;
    }
    @AfterEach void stop(){server.stop(0);httpThreads.shutdownNow();}
    VisualVerificationService client(int capacity){return client(capacity,8_000_000,8*1024*1024);}
    VisualVerificationService client(int capacity,int pixels,int bytes){return new VisualVerificationService(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/vision","test","qwen3-vl-plus",Duration.ofSeconds(5),true,capacity,8,pixels,bytes);}
    Map<String,Object> request(){return cn.bidcheck.compat.Java8Api.mapOf("task", "FIELD", "fields", cn.bidcheck.compat.Java8Api.listOf("合同编号"));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> observations(Map<String,Object> value){return (List<Map<String,Object>>)value.get("observations");}
    Document version(String id,int version){return new Document(id,document.projectId(),document.role(),document.name(),version,document.sha256(),"",document.pages(),cn.bidcheck.compat.Java8Api.listOf());}

    final class CollectionHarness {
        final Map<String,Stored> records=new HashMap<>();final TaskRow task=new TaskRow();final Store store;final org.springframework.transaction.PlatformTransactionManager tx;final BidService bids;final ExternalVerificationAdapter external;VerificationService service;
        final AtomicReference<Thread> caller=new AtomicReference<>(Thread.currentThread());final AtomicBoolean lease=new AtomicBoolean(true);
        final List<Map<String,Object>> diagnostics=new ArrayList<>();
        CollectionHarness(int budget,int capacity){this(budget,capacity,new ExternalVerificationAdapter());}
        CollectionHarness(int budget,int capacity,ExternalVerificationAdapter external){
            this.external=external;
            task.id="task";task.projectId="project";task.status="RUNNING";
            RecordMapper mapper=(RecordMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RecordMapper.class},(proxy,method,args)->{
                assertSame(caller.get(),Thread.currentThread(),"Workers must not access persistence");
                switch (method.getName()) {case "get":case "getForUpdate":return records.get((String)args[0]);case "save":{Stored row=(Stored)args[0];records.put(row.id,row);return null;}case "insertRecord":{Stored row=(Stored)args[0];return records.putIfAbsent(row.id,row)==null?1:0;}case "tryInsertEvidenceOperationLease":case "renewEvidenceOperationLease":case "releaseEvidenceOperationLease":return 1;case "takeOverEvidenceOperationLease":return 0;case "lockTask":case "task":return lease.get()?task:null;default:return null;}
            });
            store=new Store(mapper,json);store.save("verification_run","run","project","",cn.bidcheck.compat.Java8Api.mapOf("visualCallCount", 0));
            tx=new org.springframework.transaction.PlatformTransactionManager(){
                public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){assertSame(caller.get(),Thread.currentThread());return new org.springframework.transaction.support.SimpleTransactionStatus();}
                public void commit(org.springframework.transaction.TransactionStatus status){assertSame(caller.get(),Thread.currentThread());}public void rollback(org.springframework.transaction.TransactionStatus status){}
            };
            bids=new BidService(store,tx,null,null,OcrClient.disabled()){@Override public Path path(String id){assertSame(caller.get(),Thread.currentThread());return pdf;}};
            restart(budget,capacity);
        }
        void restart(int budget,int capacity){service=new VerificationService(bids,new VerificationEngine(),new EvidenceLayoutService(),client(capacity),external,null,new FactLedgerService(store,FactRepository.memoryForTests()),tx,"","",true,"qwen3-vl-plus",budget,100);}
        @SuppressWarnings("unchecked") List<Map<String,Object>> collect(List<Document> docs,List<Map<String,Object>> requests)throws Exception {
            caller.set(Thread.currentThread());java.lang.reflect.Method method=VerificationService.class.getDeclaredMethod("collectVisual",TaskRow.class,String.class,Checkpoint.class,Map.class,List.class,List.class);method.setAccessible(true);
            Checkpoint cp=new Checkpoint("cp","合同编号","响应检查","HIGH","MATERIAL","","","","",true,true,true,null);
            try{return (List<Map<String,Object>>)method.invoke(service,task,"run",cp,cn.bidcheck.compat.Java8Api.mapOf("visualRequests", requests),docs,diagnostics);}
            catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof Error)throw ((Error) (e.getCause()));if(e.getCause() instanceof RuntimeException)throw ((RuntimeException) (e.getCause()));throw e;}
        }
        long pageRecords(){return records.values().stream().filter(r->"verification_visual_page".equals(r.kind)).count();}
        long pendingPages(){return records.values().stream().filter(r->"verification_visual_page_status".equals(r.kind)&&"RETRY_PENDING".equals(store.map(r.body).get("status"))).count();}
        @SuppressWarnings("unchecked") List<Map<String,Object>> collectExternal(List<Map<String,Object>> requests)throws Exception{
            caller.set(Thread.currentThread());Map<String,Object> run=store.getMap("verification_run","run");run.put("referenceDate","2025-06-01");run.put("evaluationStage","EVALUATION");store.save("verification_run","run","project","",run);
            java.lang.reflect.Method method=VerificationService.class.getDeclaredMethod("collectExternal",TaskRow.class,String.class,Map.class,Map.class,List.class);method.setAccessible(true);
            try{return (List<Map<String,Object>>)method.invoke(service,task,"run",cn.bidcheck.compat.Java8Api.mapOf("externalRequests", requests),run,diagnostics);}
            catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof Error)throw ((Error) (e.getCause()));if(e.getCause() instanceof RuntimeException)throw ((RuntimeException) (e.getCause()));throw e;}
        }
        List<Map<String,Object>> collectCheckExternal(String runId,List<Map<String,Object>> requests){
            caller.set(Thread.currentThread());Map<String,Object> run=cn.bidcheck.compat.Java8Api.mapOf("id", runId, "projectId", "project", "documentId", "doc", "taskId", task.id, "status", "RUNNING", "evaluationStage", "EVALUATION", "referenceDate", "2025-06-01", "externalCallCount", 0);
            store.save("run",runId,"project","doc",run);
            return service.collectCheckExternal(task,runId,cn.bidcheck.compat.Java8Api.mapOf("externalRequests", requests),run,diagnostics,()->true);
        }
    }
    Map<String,Object> requested(Document doc,int page){Map<String,Object> out=new HashMap<>(request());out.put("documentId",doc.id());out.put("page",page);out.put("obligationId","rule");out.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("source-"+page));return out;}

    @Test void contextIsSourceBoundThreePagesWithOnePageOverlap(){
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3, 4, 5)),VisualPageWindows.forPage(document,3,request()));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(6)),VisualPageWindows.forPage(document,6,request()));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(3)),VisualPageWindows.forPage(document,3,cn.bidcheck.compat.Java8Api.mapOf("region", cn.bidcheck.compat.Java8Api.listOf(0, 0, 1, 1))));
        List<Page> unrelated=cn.bidcheck.compat.Java8Api.listOf(new Page(1,"合同内容甲","PAGE","PDF_TEXT",""), new Page(2,"合同内容乙","PAGE","PDF_TEXT",""));
        Document other=new Document("other","project","BID","context.pdf",1,document.sha256(),"",unrelated,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1)),VisualPageWindows.forPage(other,1,request()));
        assertEquals("VISUAL_WINDOW_INVALID",client(2).verifyWindow(other,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2),request(),()->true).get("errorCode"));assertEquals(0,calls.get());
    }
    @Test void realWindowRequestMapsEveryObservationToItsOwnPageAndImage(){
        AtomicInteger reservations=new AtomicInteger();Map<String,Object> out=client(2).verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{reservations.incrementAndGet();return true;});
        assertEquals("OBSERVED",out.get("status"));assertEquals(1,reservations.get());assertEquals(1,calls.get());assertEquals(4,payloads.get(0).path("messages").get(1).path("content").size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),cn.bidcheck.compat.Java8Api.toList(observations(out).stream().map(o->o.get("page"))));
        for(Map<String,Object> item:observations(out)){Map<?,?> source=(Map<?,?>)item.get("source");assertEquals(item.get("page"),source.get("physicalPage"));assertEquals(document.sha256(),source.get("documentHash"));assertEquals(1,source.get("documentVersion"));assertNotNull(source.get("submittedSha256"));assertEquals("MODEL_PROPOSED_BOUNDS_CHECKED",item.get("localization"));}
        assertEquals(false,out.get("authenticityVerified"));assertEquals("VISUAL_OBSERVATION_ONLY",out.get("proofLevel"));
    }
    @Test void unrequestedDuplicateMissingPagesDimensionsFieldsAndBoxesFailClosed(){
        VisualVerificationService service=client(2);
        for(String invalid:cn.bidcheck.compat.Java8Api.listOf("extra-page", "duplicate-page", "missing-page", "dimensions", "field", "bbox", "verdict")){
            mode=invalid;Map<String,Object> out=service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true);
            assertEquals("PARTIAL",out.get("status"),invalid);
            int good=cn.bidcheck.compat.Java8Api.setOf("extra-page", "missing-page", "dimensions").contains(invalid)?2:invalid.equals("duplicate-page")?1:0;
            assertEquals(good,observations(out).size(),invalid);assertFalse(VisualVerificationService.cacheable(out),invalid);
        }
        assertEquals(7,calls.get());mode="";assertEquals("OBSERVED",service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true).get("status"));assertEquals(8,calls.get());
    }
    @Test void failedProviderResponseIsRetryableAndBudgetIsOnlyReservedForHttp(){
        VisualVerificationService service=client(2);AtomicInteger attempts=new AtomicInteger();
        assertEquals("VISUAL_BUDGET_EXHAUSTED",service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->false).get("errorCode"));assertEquals(0,calls.get());
        mode="http";assertEquals("VISUAL_HTTP_503",service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{attempts.incrementAndGet();return true;}).get("errorCode"));
        mode="";assertEquals("OBSERVED",service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{attempts.incrementAndGet();return true;}).get("status"));
        assertEquals(true,service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->false).get("cacheHit"));assertEquals(2,attempts.get());assertEquals(2,calls.get());
    }
    @Test void successfulCacheIsVersionScopedAndIndependentOfCheckpointText(){
        VisualVerificationService service=client(2);service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true);
        Map<String,Object> differentlyBound=new HashMap<>(request());differentlyBound.put("obligationId","another-rule");differentlyBound.put("question","another question");
        assertEquals(true,service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),differentlyBound,()->false).get("cacheHit"));
        assertEquals("OBSERVED",service.verifyWindow(version("doc",2),pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true).get("status"));assertEquals(2,calls.get());
    }
    @Test void overlapDedupRetainsEachDifferentPageOccurrence(){
        VisualVerificationService service=client(2);Map<String,Object> first=service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true),second=service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(3, 4, 5),request(),()->true);
        List<Map<String,Object>> all=new ArrayList<>(observations(first));all.addAll(observations(second));assertEquals(6,all.size());
        assertEquals(5,all.stream().map(VisualVerificationService::observationKey).distinct().count());
        assertEquals(observations(first).get(2).get("observationKey"),observations(second).get(0).get("observationKey"));
    }
    @Test void duplicateConcurrentRequestsMakeOneCallAndOneBudgetReservation()throws Exception {
        delay=150;VisualVerificationService service=client(2);AtomicInteger attempts=new AtomicInteger();ExecutorService threads=Executors.newFixedThreadPool(6);
        try{List<Future<Map<String,Object>>> work=new ArrayList<>();for(int i=0;i<6;i++)work.add(threads.submit(()->service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{attempts.incrementAndGet();return true;})));
            for(Future<Map<String,Object>> response:work)assertEquals("OBSERVED",response.get(10,TimeUnit.SECONDS).get("status"));
            assertEquals(1,calls.get());assertEquals(1,attempts.get());
        }finally{threads.shutdownNow();}
    }
    @Test void singlePagesAndWindowsAcrossInstancesShareGlobalConcurrency()throws Exception {
        delay=180;VisualVerificationService a=client(2),b=client(2);ExecutorService threads=Executors.newFixedThreadPool(4);
        try {List<Future<Map<String,Object>>> work=new ArrayList<>();
            work.add(threads.submit(()->a.verifyWindow(version("a",1),pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true)));
            work.add(threads.submit(()->b.verify(version("b",1),pdf,1,request())));
            work.add(threads.submit(()->a.verify(version("c",1),pdf,1,request())));
            work.add(threads.submit(()->b.verifyWindow(version("d",1),pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true)));
            for(Future<Map<String,Object>> response:work)assertEquals("OBSERVED",response.get(15,TimeUnit.SECONDS).get("status"));
            assertEquals(4,calls.get());assertEquals(2,peak.get());
        }finally{threads.shutdownNow();}
    }
    @Test void windowTotalPixelsAndBytesAreBoundedBeforeAnyUpload(){
        Map<String,Object> out=client(2,120_000,8*1024*1024).verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true);
        assertEquals("OBSERVED",out.get("status"));assertTrue(((Number)out.get("imagePixelCount")).longValue()<=120_000);assertEquals(1,calls.get());
        Map<String,Object> blocked=client(2,120_000,1024).verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true);
        assertEquals("VISUAL_WINDOW_BUDGET_EXCEEDED",blocked.get("errorCode"));assertEquals(1,calls.get());
    }
    @Test void anotherRunsExhaustedBudgetCannotSuppressThisRunsRequest()throws Exception {
        VisualVerificationService service=client(2);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService threads=Executors.newSingleThreadExecutor();
        try {
            Future<Map<String,Object>> exhausted=threads.submit(()->service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{
                entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return false;
            },"exhausted-run"));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("OBSERVED",service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true,"available-run").get("status"));
            release.countDown();assertEquals("VISUAL_BUDGET_EXHAUSTED",exhausted.get(5,TimeUnit.SECONDS).get("errorCode"));assertEquals(1,calls.get());
        }finally{release.countDown();threads.shutdownNow();}
    }
    @Test void leaseOrPersistenceFailureStopsBeforeHttpAndIsNotCached(){
        VisualVerificationService service=client(2);
        assertThrows(CancellationException.class,()->service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{throw new cn.bidcheck.infrastructure.queue.TaskLeaseLostException("test-task");}));
        assertThrows(CancellationException.class,()->service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{throw new IllegalStateException("database unavailable");}));
        assertThrows(AssertionError.class,()->service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->{throw new AssertionError("owner failed");}));
        assertEquals(0,calls.get());assertEquals("OBSERVED",service.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true).get("status"));assertEquals(1,calls.get());
    }
    @Test @SuppressWarnings("unchecked") void automatedCollectionUsesWindowsDeduplicatesAndPersistsOnlySuccessfulResponses()throws Exception {
        Map<String,Stored> records=new HashMap<>();TaskRow task=new TaskRow();task.id="task";task.projectId="project";task.status="RUNNING";
        RecordMapper mapper=(RecordMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RecordMapper.class},(proxy,method,args)->{ switch (method.getName()) {case "get":return records.get((String)args[0]);case "save":{Stored row=(Stored)args[0];records.put(row.id,row);return null;}case "insertRecord":{Stored row=(Stored)args[0];return records.putIfAbsent(row.id,row)==null?1:0;}case "tryInsertEvidenceOperationLease":case "renewEvidenceOperationLease":case "releaseEvidenceOperationLease":return 1;case "takeOverEvidenceOperationLease":return 0;case "lockTask":return task;default:return null;} });
        Store store=new Store(mapper,json);store.save("verification_run","run","project","",cn.bidcheck.compat.Java8Api.mapOf("visualCallCount", 0));
        org.springframework.transaction.PlatformTransactionManager tx=new org.springframework.transaction.PlatformTransactionManager(){
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){return new org.springframework.transaction.support.SimpleTransactionStatus();}
            public void commit(org.springframework.transaction.TransactionStatus status){}public void rollback(org.springframework.transaction.TransactionStatus status){}
        };
        BidService bids=new BidService(store,tx,null,null,OcrClient.disabled()){@Override public Path path(String id){return pdf;}};
        VerificationService service=new VerificationService(bids,new VerificationEngine(),new EvidenceLayoutService(),client(2),new ExternalVerificationAdapter(),null,
            new FactLedgerService(store,FactRepository.memoryForTests()),tx,"","",true,"qwen3-vl-plus",12,100);
        java.lang.reflect.Method collect=VerificationService.class.getDeclaredMethod("collectVisual",TaskRow.class,String.class,Checkpoint.class,Map.class,List.class,List.class);collect.setAccessible(true);
        Checkpoint cp=new Checkpoint("cp","合同编号","响应检查","HIGH","MATERIAL","","","","",true,true,true,null);
        Map<String,Object> requested=new HashMap<>(request());requested.put("documentId",document.id());requested.put("page",3);requested.put("obligationId","obligation");requested.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf("source-3"));
        Map<String,Object> result=cn.bidcheck.compat.Java8Api.mapOf("visualRequests", cn.bidcheck.compat.Java8Api.listOf(requested));List<Map<String,Object>> diagnostics=new ArrayList<>();
        List<Map<String,Object>> observed=(List<Map<String,Object>>)collect.invoke(service,task,"run",cp,result,cn.bidcheck.compat.Java8Api.listOf(document),diagnostics);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3, 4, 5),cn.bidcheck.compat.Java8Api.toList(observed.stream().map(o->o.get("page"))));assertEquals(2,calls.get());assertEquals(2,store.getMap("verification_run","run").get("visualCallCount"));
        assertEquals(2,records.values().stream().filter(r->"verification_visual".equals(r.kind)).count());assertEquals(cn.bidcheck.compat.Java8Api.listOf("source-3"),observed.get(2).get("requestedSourceIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),observed.get(0).get("requestedSourceIds"));
        collect.invoke(service,task,"run",cp,result,cn.bidcheck.compat.Java8Api.listOf(document),diagnostics);assertEquals(2,calls.get());
        mode="http";collect.invoke(service,task,"run",cp,result,cn.bidcheck.compat.Java8Api.listOf(version("doc",2)),diagnostics);int afterFailure=calls.get();assertEquals(4,afterFailure);
        assertEquals(2,records.values().stream().filter(r->"verification_visual".equals(r.kind)).count());
        mode="";Thread.sleep(600);collect.invoke(service,task,"run",cp,result,cn.bidcheck.compat.Java8Api.listOf(version("doc",2)),diagnostics);assertEquals(afterFailure+2,calls.get());assertEquals(calls.get(),store.getMap("verification_run","run").get("visualCallCount"));
    }
    @Test void collectionActuallyOverlapsIndependentRequestsAndAllDatabaseWorkStaysOnCaller()throws Exception {
        delay=180;CollectionHarness h=new CollectionHarness(12,6);List<Document> docs=new ArrayList<>();List<Map<String,Object>> requests=new ArrayList<>();
        for(int i=0;i<6;i++){Document d=version("independent-"+i,1);docs.add(d);requests.add(requested(d,6));}
        List<Map<String,Object>> result=h.collect(docs,requests);assertEquals(6,result.size());assertEquals(6,calls.get());assertTrue(peak.get()>=2,"requests should overlap, not merely acquire a larger semaphore");assertTrue(peak.get()<=6);assertEquals(6,h.store.getMap("verification_run","run").get("visualCallCount"));
    }
    @Test void missingWindowPageSplitsOnlyThatPageAndCachesCompletedWindow()throws Exception {
        mode="missing-page";CollectionHarness h=new CollectionHarness(12,6);
        List<Map<String,Object>> result=h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2)));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),cn.bidcheck.compat.Java8Api.toList(result.stream().map(o->o.get("page"))));assertEquals(2,calls.get());assertEquals(3,h.pageRecords());
        List<Map<String,Object>> traces=cn.bidcheck.compat.Java8Api.toList(h.records.values().stream().filter(r->"verification_visual_call".equals(r.kind)).map(r->h.store.map(r.body)));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1)),cn.bidcheck.compat.Java8Api.toList(traces.stream().filter(r->Boolean.TRUE.equals(r.get("split"))).map(r->r.get("inputPages"))));
        h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2)));assertEquals(2,calls.get());
    }
    @Test void durableVisualObservationIsReadAfterServiceRestartAndAcrossCheckpointBinding()throws Exception {
        CollectionHarness h=new CollectionHarness(12,4);Map<String,Object> first=requested(document,2);
        assertEquals(3,h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(first)).size());assertEquals(1,calls.get());
        assertEquals(1,h.records.values().stream().filter(row->"evidence_operation".equals(row.kind)).count());
        h.records.entrySet().removeIf(entry->cn.bidcheck.compat.Java8Api.setOf("verification_visual", "verification_visual_page", "verification_visual_page_status", "verification_visual_call").contains(entry.getValue().kind));
        h.restart(12,4);Map<String,Object> rebound=new HashMap<>(first);rebound.put("obligationId","another-checkpoint-rule");rebound.put("question","different business wording");
        assertEquals(3,h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(rebound)).size());assertEquals(1,calls.get(),"durable observation must suppress a second model request");
    }
    @Test void durableTrustedExternalRecordIsReadAfterServiceRestart()throws Exception {
        AtomicInteger queries=new AtomicInteger();ExternalVerificationAdapter adapter=new ExternalVerificationAdapter(){
            @Override public List<String> enabledTypes(){return cn.bidcheck.compat.Java8Api.listOf("CREDIT_STATUS");}
            @Override public String identity(){return "trusted-fixture-v1";}
            @Override public Map<String,Object> query(Map<String,Object> request){queries.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "record", cn.bidcheck.compat.Java8Api.mapOf("id", "record-1", "type", "CREDIT_STATUS", "subject", request.get("subject"), "effectiveAt", request.get("referenceDate"), "evaluationStage", request.get("evaluationStage"), "provenanceStatus", "TRUSTED_CONNECTOR", "result", cn.bidcheck.compat.Java8Api.mapOf("status", "VERIFIED", "facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "creditStatus", "value", "ACTIVE", "rawValue", "ACTIVE", "sourceQuote", "creditStatus: ACTIVE")))), "identity", identity());}
        };
        CollectionHarness h=new CollectionHarness(12,4,adapter);Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_STATUS", "subject", "合成企业甲");
        assertEquals(1,h.collectExternal(cn.bidcheck.compat.Java8Api.listOf(request)).size());assertEquals(1,queries.get());
        h.records.entrySet().removeIf(entry->"verification_query".equals(entry.getValue().kind));h.restart(12,4);
        assertEquals(1,h.collectExternal(cn.bidcheck.compat.Java8Api.listOf(request)).size());assertEquals(1,queries.get(),"durable trusted record must suppress a second connector call");
    }
    @Test void ordinaryCheckExternalQueryUsesTheSameDurableOperationAcrossRuns(){
        AtomicInteger queries=new AtomicInteger();ExternalVerificationAdapter adapter=new ExternalVerificationAdapter(){
            @Override public List<String> enabledTypes(){return cn.bidcheck.compat.Java8Api.listOf("CREDIT_STATUS");}
            @Override public String identity(){return "trusted-check-fixture-v1";}
            @Override public Map<String,Object> query(Map<String,Object> request){queries.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "record", BidService.fields(
                "id","record-check","type","CREDIT_STATUS","subject",request.get("subject"),"source","固定信用来源","issuer","合成权威机构",
                "queriedAt","2026-10-19T08:00:00Z","effectiveAt",request.get("referenceDate"),"evaluationStage",request.get("evaluationStage"),
                "coverage","指定主体信用状态","coverageComplete",true,"rawSnapshotHash","snapshot-check","provenanceStatus","TRUSTED_CONNECTOR",
                "result",cn.bidcheck.compat.Java8Api.mapOf("status", "VERIFIED", "facts", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("field", "creditStatus", "value", "ACTIVE", "rawValue", "ACTIVE", "sourceQuote", "creditStatus: ACTIVE")))), "identity", identity());}
        };
        CollectionHarness h=new CollectionHarness(12,4,adapter);Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("type", "CREDIT_STATUS", "subject", "合成企业甲", "evaluationStage", "EVALUATION", "referenceDate", "2025-06-01");
        assertEquals(1,h.collectCheckExternal("check-run-a",cn.bidcheck.compat.Java8Api.listOf(request)).size());assertEquals(1,queries.get());
        h.records.entrySet().removeIf(entry->"verification_query".equals(entry.getValue().kind));h.restart(12,4);
        assertEquals(1,h.collectCheckExternal("check-run-b",cn.bidcheck.compat.Java8Api.listOf(request)).size());
        assertEquals(1,queries.get(),"ordinary 109-item recovery must reuse the durable connector record");
    }
    @Test void completedPagesSurviveFailedSplitAndOnlyMissingPageIsResumed()throws Exception {
        mode="partial-single-http";CollectionHarness h=new CollectionHarness(12,6);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(2, 3),cn.bidcheck.compat.Java8Api.toList(h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).stream().map(o->o.get("page"))));assertEquals(2,calls.get());assertEquals(2,h.pageRecords());
        assertEquals(1,h.pendingPages());mode="";assertEquals(cn.bidcheck.compat.Java8Api.listOf(2, 3),cn.bidcheck.compat.Java8Api.toList(h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).stream().map(o->o.get("page"))));assertEquals(2,calls.get());
        Thread.sleep(300);assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),cn.bidcheck.compat.Java8Api.toList(h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).stream().map(o->o.get("page"))));assertEquals(3,calls.get());assertEquals(3,h.pageRecords());assertEquals(0,h.pendingPages());
    }
    @Test void wronglyNumberedWindowPageRepairsOnlyItsMissingSourceWithoutReissuingGoodPages()throws Exception {
        mode="window-invalid";CollectionHarness h=new CollectionHarness(12,6);assertEquals(3,h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).size());
        assertEquals(2,calls.get());assertEquals(1,payloads.stream().filter(p->p.path("messages").get(1).path("content").size()==4).count());assertEquals(2,h.store.getMap("verification_run","run").get("visualCallCount"));
    }
    @Test void unavailableProviderDefersWindowWithoutSplittingAndRemembersDeadline()throws Exception {
        mode="http";retryAfter="120";CollectionHarness h=new CollectionHarness(12,6);
        assertTrue(h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).isEmpty());assertEquals(1,calls.get());assertEquals(3,h.pendingPages());
        mode="";assertTrue(h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).isEmpty());assertEquals(1,calls.get());
        assertEquals(1,h.store.getMap("verification_run","run").get("visualCallCount"));assertTrue(h.diagnostics.stream().anyMatch(d->"VISUAL_RETRY_PENDING".equals(d.get("code"))));
    }
    @Test void rateLimitDefersWindowWithoutSplittingOrSpendingFallbackBudget()throws Exception {
        mode="rate-limit";retryAfter="120";CollectionHarness h=new CollectionHarness(12,6);
        assertTrue(h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).isEmpty());assertEquals(1,calls.get());assertEquals(3,h.pendingPages());
        assertEquals(1,h.store.getMap("verification_run","run").get("visualCallCount"));
    }
    @Test void retryAfterAcrossServiceInstancesBlocksNewHttpWithoutSpendingBudget(){
        mode="http";retryAfter="120";VisualVerificationService first=client(6),second=client(6);AtomicInteger reservations=new AtomicInteger();
        Map<String,Object> failed=first.verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true);assertEquals("RETRY_PENDING",failed.get("status"));assertFalse(VisualVerificationService.splitRetryable(failed));
        mode="";long started=System.nanoTime();Map<String,Object> deferred=second.verifyWindow(version("new-doc",1),pdf,cn.bidcheck.compat.Java8Api.listOf(6),request(),()->{reservations.incrementAndGet();return true;});
        assertEquals("VISUAL_RETRY_PENDING",deferred.get("errorCode"));assertTrue(((Number)deferred.get("retryAfterMillis")).longValue()>115_000);
        assertEquals(1,calls.get());assertEquals(0,reservations.get());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000);
    }
    @Test void shortSharedCooldownWaitsThenMakesOneRequestAndHttpDatesAreSupported(){
        mode="rate-limit";retryAfter="1";client(6).verifyWindow(document,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),request(),()->true);mode="";
        long started=System.nanoTime();assertEquals("OBSERVED",client(6).verifyWindow(version("new-doc",1),pdf,cn.bidcheck.compat.Java8Api.listOf(6),request(),()->true).get("status"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>=850);assertEquals(2,calls.get());
        assertEquals(2000,VisualVerificationService.retryAfterMillis("Thu, 01 Jan 1970 00:00:02 GMT",0));
        assertEquals(0,VisualVerificationService.retryAfterMillis("invalid",0));assertEquals(Long.MAX_VALUE,VisualVerificationService.retryAfterMillis("9999999999999999999999999",0));
    }
    @Test void repeatedCapacityFailuresOpenSharedCircuitWithoutShorteningRetryAfter()throws Exception {
        VisualRequestGate gate=new VisualRequestGate();for(int i=0;i<5;i++)gate.failed("service",0);
        assertTrue(gate.retryAfterMillis("service")>14_000);assertNull(gate.acquire(6,4,false,8,100,"service"));
        gate.failed("service",120_000);gate.succeeded("service");assertTrue(gate.retryAfterMillis("service")>119_000);
        assertNull(gate.acquire(6,4,true,8,100,"service"));try(cn.bidcheck.verification.source.VisualRequestGate.Permit other=gate.acquire(6,4,false,8,100,"other")){assertNotNull(other);}
    }
    @Test void budgetDenialRetainsCompletedPagesAndSendsNoFallbackRequest()throws Exception {
        mode="missing-page";CollectionHarness h=new CollectionHarness(1,6);assertEquals(2,h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,2))).size());assertEquals(1,calls.get());assertEquals(2,h.pageRecords());
        assertTrue(h.diagnostics.stream().anyMatch(d->"VISUAL_BUDGET_EXHAUSTED".equals(d.get("code"))));
    }
    @Test void leaseFailureAtCallerHandshakeCancelsWorkersBeforeAnyUpload()throws Exception {
        CollectionHarness h=new CollectionHarness(12,6);h.lease.set(false);
        assertThrows(cn.bidcheck.infrastructure.queue.TaskLeaseLostException.class,()->h.collect(cn.bidcheck.compat.Java8Api.listOf(document),cn.bidcheck.compat.Java8Api.listOf(requested(document,3))));assertEquals(0,calls.get());
    }
    @Test void cancellingCollectionStopsQueuedWorkAndUnblocksRunningWorkers()throws Exception {
        delay=1500;CollectionHarness h=new CollectionHarness(12,2);List<Document> docs=new ArrayList<>();List<Map<String,Object>> requests=new ArrayList<>();
        for(int i=0;i<6;i++){Document d=version("cancel-"+i,1);docs.add(d);requests.add(requested(d,6));}
        CountDownLatch finished=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread caller=new Thread(()->{try{h.collect(docs,requests);}catch(Throwable e){failure.set(e);}finally{finished.countDown();}},"visual-cancel-test");caller.start();
        // Both allowed workers may already have crossed the HTTP boundary. Wait until
        // they are observable so a late arrival from the second in-flight request is
        // not mistaken for newly scheduled work after cancellation.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(calls.get()<2&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals(2,calls.get());caller.interrupt();assertTrue(finished.await(4,TimeUnit.SECONDS));assertInstanceOf(CancellationException.class,failure.get());
        int stopped=calls.get();Thread.sleep(150);assertEquals(stopped,calls.get());assertTrue(stopped<=2);
    }
    @Test void validatedFullPageGeometryActuallyReducesSubmittedRegion()throws Exception {
        cn.bidcheck.ocr.OcrLayout.Frame frame=new cn.bidcheck.ocr.OcrLayout.Frame("frame",400,300,cn.bidcheck.compat.Java8Api.repeat("a", 64),"PDF_RENDER",2,null,null,null);
        cn.bidcheck.ocr.OcrLayout.TextBox box=new cn.bidcheck.ocr.OcrLayout.TextBox("text","frame","合同编号：TEST-2026-001",new cn.bidcheck.ocr.OcrLayout.Box(100,80,100,20),null);
        cn.bidcheck.ocr.OcrLayout layout=new cn.bidcheck.ocr.OcrLayout(cn.bidcheck.ocr.OcrLayout.VERSION,"COMPLETE",cn.bidcheck.ocr.OcrLayout.COORDINATES,cn.bidcheck.compat.Java8Api.listOf(frame),cn.bidcheck.compat.Java8Api.listOf(box),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf("text"),cn.bidcheck.compat.Java8Api.listOf());
        List<Page> pages=new ArrayList<>(document.pages());pages.set(1,new Page(2,box.text(),"PAGE","PDF_TEXT","",layout));
        Document located=new Document(document.id(),document.projectId(),document.role(),document.name(),1,document.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf());CollectionHarness h=new CollectionHarness(12,6);
        List<Map<String,Object>> observations=h.collect(cn.bidcheck.compat.Java8Api.listOf(located),cn.bidcheck.compat.Java8Api.listOf(requested(located,2)));assertEquals(1,observations.size());Map<?,?> source=(Map<?,?>)observations.get(0).get("source");
        assertEquals(true,source.get("roiApplied"));assertTrue(((Number)source.get("submittedWidth")).intValue()<((Number)source.get("renderedWidth")).intValue());assertEquals(2,source.get("physicalPage"));assertEquals(2,payloads.get(0).path("messages").get(1).path("content").size());
    }
    @Test void explicitNamedCardFacesShareContextButDifferentHoldersDoNot(){
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(new Page(1,"张三身份证（正面）","PAGE","PDF_TEXT",""), new Page(2,"张三身份证（反面）","PAGE","PDF_TEXT",""), new Page(3,"李四身份证（正面）","PAGE","PDF_TEXT",""));
        Document cards=new Document("cards","project","BID","cards.pdf",1,document.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf());assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2)),VisualPageWindows.forPage(cards,1,request()));assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(3)),VisualPageWindows.forPage(cards,3,request()));
    }
    @Test void continuationWithoutRepeatedRecordIdentityActuallyUsesWindowHttp()throws Exception {
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(VisualContinuationTest.table(1,"拟投入车辆一览表","车牌号"), VisualContinuationTest.table(2,"拟投入车辆一览表（续表）","车牌号"), VisualContinuationTest.table(3,"拟投入车辆一览表（续表）","车牌号"));
        Document table=new Document("table",document.projectId(),"BID","context.pdf",1,document.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf());CollectionHarness h=new CollectionHarness(12,6);
        assertEquals(3,h.collect(cn.bidcheck.compat.Java8Api.listOf(table),cn.bidcheck.compat.Java8Api.listOf(requested(table,2))).size());assertEquals(1,calls.get());assertEquals(4,payloads.get(0).path("messages").get(1).path("content").size());
        List<Page> contractPages=cn.bidcheck.compat.Java8Api.listOf(VisualContinuationTest.text(1,"设备采购合同\n合同编号：TEST-2026-001\n第一条 范围"), VisualContinuationTest.text(2,"第二条 价格"), VisualContinuationTest.text(3,"第三条 交货"));
        Document contract=new Document("contract",document.projectId(),"BID","context.pdf",1,document.sha256(),"",contractPages,cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(3,h.collect(cn.bidcheck.compat.Java8Api.listOf(contract),cn.bidcheck.compat.Java8Api.listOf(requested(contract,2))).size());assertEquals(2,calls.get());assertEquals(4,payloads.get(1).path("messages").get(1).path("content").size());
    }
    @Test void sixTotalAndFourWindowPermitsNeverAddIntoTenRequests()throws Exception {
        delay=160;VisualVerificationService service=client(6);ExecutorService threads=Executors.newFixedThreadPool(10);
        try {
            List<Future<Map<String,Object>>> futures=new ArrayList<>();
            for(int i=0;i<10;i++){int index=i;futures.add(threads.submit(()->service.verifyWindow(version("mixed-"+index,1),pdf,index<6?cn.bidcheck.compat.Java8Api.listOf(1, 2, 3):cn.bidcheck.compat.Java8Api.listOf(6),request(),()->true)));}
            for(Future<Map<String,Object>> f:futures)assertEquals("OBSERVED",f.get(15,TimeUnit.SECONDS).get("status"));
            assertEquals(10,calls.get());assertTrue(peak.get()>=2);assertTrue(peak.get()<=6);assertTrue(windowPeak.get()<=4);
        }finally{threads.shutdownNow();}
    }
}

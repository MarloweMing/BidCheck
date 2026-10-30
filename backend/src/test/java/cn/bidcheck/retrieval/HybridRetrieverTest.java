package cn.bidcheck.retrieval;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridRetrieverTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper=new ObjectMapper();
    private HttpServer server;
    private ExecutorService workers;
    private String base;
    private final AtomicInteger embeddings=new AtomicInteger(), reranks=new AtomicInteger();
    private final AtomicInteger activeEmbeddings=new AtomicInteger(), peakEmbeddings=new AtomicInteger();
    private final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    private volatile String embeddingFault="", rerankFault="";
    private volatile long delayMillis;
    private volatile int failEmbeddingCall;
    private volatile int failEmbeddingStatus=503;
    private volatile String failingText="";
    private volatile CountDownLatch embeddingBarrier;
    private final CountDownLatch entered=new CountDownLatch(1);
    private final AtomicReference<Throwable> serverFailure=new AtomicReference<>();

    @BeforeEach void server() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/embedding",exchange->{
            boolean active=false;
            try {
                assertEquals("POST",exchange.getRequestMethod());assertEquals("Bearer test-secret",exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request=mapper.readTree(exchange.getRequestBody());requests.add(request);int call=embeddings.incrementAndGet();entered.countDown();
                active=true;peakEmbeddings.accumulateAndGet(activeEmbeddings.incrementAndGet(),Math::max);
                CountDownLatch barrier=embeddingBarrier;
                if(barrier!=null){barrier.countDown();assertTrue(barrier.await(2,TimeUnit.SECONDS));}
                if(delayMillis>0)Thread.sleep(delayMillis);
                assertEquals(64,request.path("parameters").path("dimension").intValue());assertEquals("dense",request.path("parameters").path("output_type").asText());
                var texts=request.path("input").path("texts");assertTrue(texts.isArray());assertTrue(texts.size()<=10);
                if("http".equals(embeddingFault)||call==failEmbeddingCall||(!failingText.isEmpty()&&texts.toString().contains(failingText))){respond(exchange,failEmbeddingStatus,"secret input must never escape");return;}
                if("large".equals(embeddingFault)){respond(exchange,200," ".repeat(2*1024*1024+1));return;}
                List<Map<String,Object>> values=new ArrayList<>();
                for(int i=texts.size()-1;i>=0;i--) {
                    List<Object> vector=new ArrayList<>();
                    boolean denseMatch="query".equals(request.path("parameters").path("text_type").asText())||texts.get(i).asText().contains("紫藤登记");
                    for(int j=0;j<("dimension".equals(embeddingFault)?63:64);j++)vector.add("zero".equals(embeddingFault)?0d:(j==(denseMatch?0:1)?1d:0d));
                    if("nonnumeric".equals(embeddingFault))vector.set(0,"NaN");
                    values.add(Map.of("text_index","index".equals(embeddingFault)?0:i,"embedding",vector));
                }
                respond(exchange,200,mapper.writeValueAsString(Map.of("output",Map.of("embeddings",values))));
            } catch(InterruptedException cancelled) {Thread.currentThread().interrupt();exchange.close();}
            catch(IOException disconnected) {exchange.close();}
            catch(Throwable failure) {serverFailure.set(failure);try{respond(exchange,500,"mock validation failed");}catch(IOException ignored){exchange.close();}}
            finally {if(active)activeEmbeddings.decrementAndGet();}
        });
        server.createContext("/rerank",exchange->{
            try {
                JsonNode request=mapper.readTree(exchange.getRequestBody());requests.add(request);reranks.incrementAndGet();
                assertEquals("qwen3-rerank",request.path("model").asText());assertFalse(request.has("input"));
                assertTrue(request.path("query").isTextual());assertTrue(request.path("documents").isArray());
                int top=request.path("top_n").intValue();assertTrue(top<=16);assertTrue(request.path("documents").size()<=60);
                if("http".equals(rerankFault)){respond(exchange,500,"never echo credentials or documents");return;}
                List<Integer> order=new ArrayList<>();for(int i=0;i<request.path("documents").size();i++)order.add(i);
                order.sort(Comparator.comparingInt(i->request.path("documents").get(i).asText().contains("紫藤登记")?0:1));
                List<Map<String,Object>> result=new ArrayList<>();
                for(int i=0;i<top;i++)result.add(Map.of("index","index".equals(rerankFault)?999:order.get(i),"relevance_score",1d-i*.01));
                respond(exchange,200,mapper.writeValueAsString(Map.of("model","qwen3-rerank","results",result)));
            }catch(IOException disconnected){exchange.close();}
            catch(Throwable failure){serverFailure.set(failure);try{respond(exchange,500,"mock validation failed");}catch(IOException ignored){exchange.close();}}
        });
        server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);workers.shutdownNow();if(serverFailure.get()!=null)throw new AssertionError(serverFailure.get());}
    private void respond(com.sun.net.httpserver.HttpExchange exchange,int status,String body)throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    private HybridRetriever retriever(){return retriever(temporary,Duration.ofSeconds(10),Duration.ofSeconds(3));}
    private HybridRetriever retriever(Path cache,Duration total,Duration request){return new HybridRetriever(true,base+"/embedding",base+"/rerank","test-secret","text-embedding-v4","qwen3-rerank",cache.toString(),64,total,request);}
    private HybridRetriever retriever(Path cache,Duration search,Duration request,Duration preparation,int concurrency){return new HybridRetriever(true,base+"/embedding",base+"/rerank","test-secret","text-embedding-v4","qwen3-rerank",cache.toString(),64,search,request,preparation,concurrency);}
    private Document manyPages(int count){List<Page> pages=new ArrayList<>();for(int i=1;i<=count;i++)pages.add(new Page(i,"合成档案编号 "+i,"PAGE","PDFBOX_NATIVE",""));return document("p","d",1,"s",pages);}
    private Document document(String text){return document("project-a","document-a",1,"sha-a",List.of(new Page(1,text,"PAGE","PDFBOX_NATIVE","")));}
    private Document document(String project,String id,int version,String sha,List<Page> pages){return new Document(id,project,"BID","合成材料",version,sha,"2025-01-01",pages,List.of());}

    @Test void disabledIsRealBm25WithLengthNormalizationAndNoNetworkOrCacheWrites()throws Exception {
        Document doc=document("p","d",1,"s",List.of(new Page(1,"安全生产许可证\n证书编号：A-001","PAGE","PDFBOX_NATIVE",""),
                new Page(2,"安全生产许可证 \n"+"泛泛介绍经营情况。".repeat(100),"PAGE","PDFBOX_NATIVE","")));
        HybridRetriever disabled=new HybridRetriever(false,"","","","text-embedding-v4","qwen3-rerank",temporary.resolve("disabled").toString(),64);
        var result=disabled.search(doc,"安全生产许可证 A-001",16);
        assertEquals("BM25_DISABLED",result.mode());assertEquals(1,result.hits().get(0).page());
        assertEquals(false,result.diagnostics().get("vectorSearchExecuted"));assertEquals(0,result.diagnostics().get("denseCandidateCount"));
        assertEquals(0,embeddings.get());assertEquals(0,reranks.get());assertFalse(Files.exists(temporary.resolve("disabled")));
        assertEquals(1d,result.hits().get(0).confidence());
    }

    @Test void batchesVectorsReordersEmbeddingIndicesFusesAndReallyReranksSixteenCandidates()throws Exception {
        List<Page> pages=new ArrayList<>();
        for(int i=1;i<=36;i++)pages.add(new Page(i,i==35?"紫藤登记\n完全不含查询词的合成证明。":"安全资格证明\n普通合成条目 "+i,"PAGE",i==35?"PDFBOX_OCR":"PDFBOX_NATIVE",""));
        var result=retriever().search(document("p","d",1,"s",pages),"安全资格",16);
        assertEquals("HYBRID_RERANKED",result.mode());assertEquals(16,result.hits().size());assertEquals(35,result.hits().get(0).page());
        assertEquals(true,result.diagnostics().get("vectorSearchExecuted"));assertEquals(30,result.diagnostics().get("denseCandidateCount"));
        assertEquals(0d,result.hits().get(0).confidence());assertTrue(result.hits().get(0).quote().startsWith("紫藤登记"));
        assertEquals(5,embeddings.get());assertEquals(1,reranks.get());assertEquals(true,result.diagnostics().get("denseCoverageComplete"));
        assertTrue(((Number)result.diagnostics().get("fusedCandidates")).intValue()>30);
        assertEquals("LOCAL_EXACT_COSINE",result.diagnostics().get("indexBackend"));
    }

    @Test void persistedVectorsAndQueryCacheSurviveANewRetrieverInstance()throws Exception {
        Document doc=document("营业执照\n合成单位登记证明");HybridRetriever first=retriever();
        var warmup=first.warmup(doc);assertEquals("INDEX_READY",warmup.mode());assertEquals(false,warmup.diagnostics().get("vectorSearchExecuted"));assertEquals(1,embeddings.get());assertEquals(0,reranks.get());
        assertEquals("HYBRID_RERANKED",first.search(doc,"营业执照",16).mode());assertEquals(2,embeddings.get());
        var cached=retriever().search(doc,"营业执照",16);
        assertEquals("HYBRID_RERANKED",cached.mode());assertEquals(2,embeddings.get());assertEquals(2,((Number)cached.diagnostics().get("cacheHits")).intValue());
        try(var files=Files.walk(temporary)){for(Path file:files.filter(p->p.toString().endsWith(".vec")).toList()){
            String binary=new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1);assertFalse(binary.contains("test-secret"));
            assertFalse(binary.contains("document-a"));assertFalse(binary.contains("project-a"));
        }}
    }

    @Test void scopeBindsProjectDocumentVersionSourceHashAndActualChunkText()throws Exception {
        HybridRetriever service=retriever();Document original=document("原始文字");
        for(Document doc:List.of(original,document("project-b",original.id(),1,original.sha256(),original.pages()),
                document(original.projectId(),"document-b",1,original.sha256(),original.pages()),
                document(original.projectId(),original.id(),2,original.sha256(),original.pages()),
                document(original.projectId(),original.id(),1,"changed-sha",original.pages()),document("不同解析文字"))) {
            assertEquals("INDEX_READY",service.warmup(doc).mode());
        }
        assertEquals(6,embeddings.get());assertEquals(0,reranks.get());
    }

    @Test void corruptCacheIsRejectedAndRebuiltRatherThanReused()throws Exception {
        Document doc=document("登记证书");assertEquals("INDEX_READY",retriever().warmup(doc).mode());
        try(var files=Files.walk(temporary)){Path cached=files.filter(p->p.toString().endsWith(".vec")).findFirst().orElseThrow();Files.write(cached,new byte[]{1,2,3});}
        var repaired=retriever().warmup(doc);assertEquals("INDEX_READY",repaired.mode());assertEquals(2,embeddings.get());assertEquals(1,repaired.diagnostics().get("cacheInvalid"));
    }

    @Test void interruptedBatchWarmupResumesOnlyMissingVectorsAndNeverPublishesPartialCoverage()throws Exception {
        List<Page> pages=new ArrayList<>();for(int i=1;i<=25;i++)pages.add(new Page(i,"合成档案编号 "+i,"PAGE","PDFBOX_NATIVE",""));
        Document doc=document("p","d",1,"s",pages);failEmbeddingCall=2;
        var incomplete=retriever(temporary,Duration.ofSeconds(10),Duration.ofSeconds(3),Duration.ofSeconds(10),1).warmup(doc);
        assertEquals("BM25_DEGRADED",incomplete.mode());assertEquals(false,incomplete.diagnostics().get("denseCoverageComplete"));
        assertEquals(10,incomplete.diagnostics().get("newlyEmbeddedTexts"));assertEquals(2,embeddings.get());
        failEmbeddingCall=0;var resumed=retriever().warmup(doc);
        assertEquals("INDEX_READY",resumed.mode());assertEquals(10,resumed.diagnostics().get("cacheHits"));
        assertEquals(15,resumed.diagnostics().get("newlyEmbeddedTexts"));assertEquals(4,embeddings.get());assertEquals(0,reranks.get());
    }

    @Test void embeddingModelRevisionAndEndpointArePartOfCacheIdentity()throws Exception {
        Document doc=document("登记证明");HybridRetriever original=retriever();assertEquals("INDEX_READY",original.warmup(doc).mode());
        HybridRetriever revised=new HybridRetriever(true,base+"/embedding",base+"/rerank","test-secret","text-embedding-v4-revision-test","qwen3-rerank",temporary.toString(),64);
        assertNotEquals(original.identity(),revised.identity());assertEquals("INDEX_READY",revised.warmup(doc).mode());assertEquals(2,embeddings.get());
        HybridRetriever otherEndpoint=new HybridRetriever(true,"https://example.invalid/embedding",base+"/rerank","test-secret","text-embedding-v4","qwen3-rerank",temporary.toString(),64);
        assertNotEquals(original.identity(),otherEndpoint.identity()); // No network call is made to this endpoint.
    }

    @Test void boundedConcurrencyReportsMonotoneProgressOnTheCallerThread()throws Exception {
        embeddingBarrier=new CountDownLatch(3);delayMillis=60;
        List<Map<String,Object>> progress=new ArrayList<>();Thread caller=Thread.currentThread();
        var service=retriever(temporary,Duration.ofSeconds(5),Duration.ofSeconds(3),Duration.ofSeconds(10),3);
        var ready=service.warmup(manyPages(80),snapshot->{assertSame(caller,Thread.currentThread());progress.add(snapshot);});
        assertEquals("INDEX_READY",ready.mode());assertEquals(8,embeddings.get());assertEquals(3,peakEmbeddings.get());
        assertEquals(3,ready.diagnostics().get("peakInFlightRequests"));
        assertEquals(8,ready.diagnostics().get("embeddingBatchesCompleted"));
        assertEquals(80,ready.diagnostics().get("completedChunks"));assertEquals(0,ready.diagnostics().get("remainingChunks"));
        assertEquals(true,progress.get(progress.size()-1).get("denseCoverageComplete"));
        int previous=0;for(var snapshot:progress){int count=((Number)snapshot.get("completedChunks")).intValue();assertTrue(count>=previous);previous=count;}
        assertThrows(UnsupportedOperationException.class,()->progress.get(0).put("changed",true));
        assertFalse(mapper.writeValueAsString(progress).contains("合成档案"));assertEquals(0,reranks.get());
    }

    @Test void failedConcurrentBatchPreservesOtherInFlightSuccessesAndOnlyMissingTextsAreRetried()throws Exception {
        embeddingBarrier=new CountDownLatch(4);failingText="档案编号 11";
        Document doc=manyPages(40);
        var incomplete=retriever().warmup(doc);
        assertEquals("BM25_DEGRADED",incomplete.mode());assertEquals(false,incomplete.diagnostics().get("denseCoverageComplete"));
        assertEquals(30,incomplete.diagnostics().get("newlyEmbeddedTexts"));assertEquals(10,incomplete.diagnostics().get("remainingChunks"));
        assertEquals(1,incomplete.diagnostics().get("embeddingBatchesFailed"));assertEquals(true,incomplete.diagnostics().get("retryableFailure"));
        assertEquals(4,embeddings.get());
        failingText="";embeddingBarrier=null;
        var resumed=retriever().warmup(doc);
        assertEquals("INDEX_READY",resumed.mode());assertEquals(30,resumed.diagnostics().get("cachedDocumentChunks"));
        assertEquals(10,resumed.diagnostics().get("newlyEmbeddedTexts"));assertEquals(1,resumed.diagnostics().get("embeddingBatchesTotal"));
        assertEquals(5,embeddings.get());assertEquals(0,reranks.get());
    }

    @Test void preparationHasAnIndependentDeadlineAndSearchRemainsBounded()throws Exception {
        delayMillis=90;
        var service=retriever(temporary,Duration.ofMillis(50),Duration.ofSeconds(2),Duration.ofSeconds(5),1);
        Document doc=manyPages(30);
        var ready=service.warmup(doc);
        assertEquals("INDEX_READY",ready.mode());assertEquals(5000L,ready.diagnostics().get("operationBudgetMs"));
        assertTrue(((Number)ready.diagnostics().get("elapsedMs")).longValue()>=270);
        var search=service.search(doc,"合成档案",16);
        assertEquals("RETRIEVAL_TIMEOUT",search.diagnostics().get("failureCode"));
        assertEquals(50L,search.diagnostics().get("operationBudgetMs"));assertEquals(true,search.diagnostics().get("operationDeadlineExceeded"));
    }

    @Test void transientHttp408ResumesCachedBatchesWhilePermanent4xxRemainNonRetryable()throws Exception {
        failEmbeddingCall=2;failEmbeddingStatus=408;Document doc=manyPages(25);
        var incomplete=retriever(temporary,Duration.ofSeconds(5),Duration.ofSeconds(3),Duration.ofSeconds(5),1).warmup(doc);
        assertEquals("EMBEDDING_HTTP_408",incomplete.diagnostics().get("failureCode"));
        assertEquals(true,incomplete.diagnostics().get("retryableFailure"));assertEquals(10,incomplete.diagnostics().get("completedChunks"));
        assertEquals(false,incomplete.diagnostics().get("denseCoverageComplete"));assertEquals(2,embeddings.get());
        failEmbeddingCall=0;
        var resumed=retriever().warmup(doc);
        assertEquals("INDEX_READY",resumed.mode());assertEquals(10,resumed.diagnostics().get("cachedDocumentChunks"));
        assertEquals(15,resumed.diagnostics().get("newlyEmbeddedTexts"));assertEquals(4,embeddings.get());
        for(int status:List.of(425,400,401,403,404,422)){
            failEmbeddingStatus=status;failEmbeddingCall=embeddings.get()+1;
            var failed=retriever(temporary.resolve("http-"+status),Duration.ofSeconds(5),Duration.ofSeconds(2)).warmup(document("HTTP测试"));
            assertEquals("EMBEDDING_HTTP_"+status,failed.diagnostics().get("failureCode"));
            assertEquals(status==425,failed.diagnostics().get("retryableFailure"));
            assertEquals(false,failed.diagnostics().get("denseCoverageComplete"));
        }
        assertEquals(0,reranks.get());
    }

    @Test void requestTimeoutIsDistinguishedFromOperationDeadlineAndNeitherPublishesAnIncompleteIndex()throws Exception {
        delayMillis=500;
        var requestLimited=retriever(temporary.resolve("request-limit"),Duration.ofSeconds(5),Duration.ofMillis(80),Duration.ofSeconds(5),2)
                .warmup(document("合成超时材料"));
        assertEquals("EMBEDDING_REQUEST_TIMEOUT",requestLimited.diagnostics().get("failureCode"));
        assertEquals(false,requestLimited.diagnostics().get("operationDeadlineExceeded"));
        assertEquals(false,requestLimited.diagnostics().get("denseCoverageComplete"));
        long started=System.nanoTime();
        var deadlineLimited=retriever(temporary.resolve("operation-limit"),Duration.ofSeconds(5),Duration.ofSeconds(2),Duration.ofMillis(150),2)
                .warmup(manyPages(30));
        assertEquals("RETRIEVAL_TIMEOUT",deadlineLimited.diagnostics().get("failureCode"));
        assertEquals(true,deadlineLimited.diagnostics().get("operationDeadlineExceeded"));
        assertEquals(false,deadlineLimited.diagnostics().get("denseCoverageComplete"));
        assertTrue(((Number)deadlineLimited.diagnostics().get("remainingChunks")).intValue()>0);
        assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<2000);
        assertEquals(0,reranks.get());
    }

    @Test void progressLeaseFailureCancelsOutstandingBatchesAndPropagatesTheExactException()throws Exception {
        delayMillis=3000;
        var service=retriever(temporary,Duration.ofSeconds(5),Duration.ofSeconds(5),Duration.ofSeconds(10),2);
        IllegalStateException leaseLost=new IllegalStateException("synthetic lease fencing");
        long started=System.nanoTime();
        var failure=assertThrows(IllegalStateException.class,()->service.warmup(manyPages(80),snapshot->{
            if(((Number)snapshot.get("activeRequests")).intValue()>0)throw leaseLost;
        }));
        assertSame(leaseLost,failure);assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<2500);
        assertEquals(2,embeddings.get());assertEquals(0,reranks.get());
    }

    @Test void malformedEmbeddingShapesNeverCreateAPartialSuccessfulDenseIndex()throws Exception {
        Document doc=document("p","d",1,"s",List.of(new Page(1,"安全资格证书","PAGE","PDFBOX_NATIVE",""),new Page(2,"许可证明","PAGE","PDFBOX_NATIVE","")));
        for(String fault:List.of("dimension","index","zero","nonnumeric")) {
            embeddingFault=fault;var result=retriever(temporary.resolve(fault),Duration.ofSeconds(5),Duration.ofSeconds(2)).search(doc,"安全资格",16);
            assertEquals("BM25_DEGRADED",result.mode());assertEquals(false,result.diagnostics().get("denseCoverageComplete"));
            assertTrue(result.diagnostics().get("failureCode").toString().startsWith("EMBEDDING_"));assertFalse(result.hits().isEmpty());
        }
        assertEquals(0,reranks.get());
    }

    @Test void rerankFailureRetainsRrfAndNeverClaimsSuccessfulHybridReranking()throws Exception {
        for(String fault:List.of("http","index")) {
            rerankFault=fault;var result=retriever().search(document("安全资格材料"),"安全资格",16);
            assertEquals("HYBRID_RRF_DEGRADED",result.mode());assertEquals(true,result.diagnostics().get("denseCoverageComplete"));
            assertTrue(result.diagnostics().get("failureCode").toString().startsWith("RERANK_"));assertEquals(1,result.hits().size());
            assertFalse(mapper.writeValueAsString(result.diagnostics()).contains("test-secret"));
        }
    }

    @Test void insecureRemoteEndpointsAndUnscopedDocumentsNeverContactServices()throws Exception {
        HybridRetriever invalid=new HybridRetriever(true,"http://192.0.2.1/embedding",base+"/rerank","test-secret","text-embedding-v4","qwen3-rerank",temporary.toString(),64);
        assertTrue(invalid.enabled());var result=invalid.search(document("安全资格"),"安全资格",16);
        assertEquals("BM25_DEGRADED",result.mode());assertEquals("CONFIGURATION_INVALID",result.diagnostics().get("failureCode"));
        var unscoped=retriever().search(document("","doc",1,"sha",List.of(new Page(1,"正文","PAGE","PDFBOX_NATIVE",""))),"查询",16);
        assertEquals("DOCUMENT_SCOPE_MISSING",unscoped.diagnostics().get("failureCode"));assertTrue(unscoped.hits().isEmpty());assertEquals(0,embeddings.get());
    }

    @Test void timeoutAndResponseSizeAreBoundedWithoutExposingResponseText()throws Exception {
        delayMillis=500;var service=retriever(temporary.resolve("timeout"),Duration.ofMillis(300),Duration.ofMillis(100));
        long started=System.nanoTime();var timed=service.search(document("安全资格"),"安全资格",16);
        assertEquals("BM25_DEGRADED",timed.mode());assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<2000);
        delayMillis=0;embeddingFault="large";var large=retriever().search(document("安全资格"),"安全资格",16);
        assertEquals("BM25_DEGRADED",large.mode());assertFalse(large.diagnostics().toString().contains("secret"));
    }

    @Test void interruptionCancelsTheCurrentCallAndDoesNotContinueToRerank()throws Exception {
        delayMillis=3000;AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread caller=new Thread(()->{try{retriever().search(document("安全资格"),"安全资格",16);}catch(Throwable error){failure.set(error);}});
        caller.start();assertTrue(entered.await(2,TimeUnit.SECONDS));caller.interrupt();caller.join(2000);
        assertFalse(caller.isAlive());assertInstanceOf(InterruptedIOException.class,failure.get());assertEquals(0,reranks.get());
    }

    @Test void chunkQuotesRemainExactAndTableHeaderContextStaysALabelledHint()throws Exception {
        String header="姓名\t岗位\t证书编号";String text="合成人员\t设计负责人\tC-123\n"+"相邻正文片段。".repeat(200);
        Document doc=document("p","d",1,"s",List.of(new Page(1,header,"BLOCK","POI_NATIVE",""),new Page(2,text,"BLOCK","POI_NATIVE","")));
        LexicalIndex index=new LexicalIndex(doc);
        for(var chunk:index.chunks){String source=doc.pages().get(chunk.evidence().page()-1).text();assertTrue(source.contains(chunk.evidence().quote()));}
        assertTrue(index.chunks.stream().filter(c->c.evidence().page()==2).allMatch(c->c.searchText().contains("前一内容块线索：\n"+header)));
        assertTrue(index.chunks.stream().filter(c->c.evidence().page()==2).noneMatch(c->c.evidence().quote().contains(header)));
        assertThrows(IllegalArgumentException.class,()->retriever().search(doc,"query",17));
    }

    @Test void rrfUsesRanksNotIncommensurateRawModelScores() {
        var fused=HybridRetriever.fuse(List.of(new LexicalIndex.Scored(0,10000),new LexicalIndex.Scored(1,500)),
                List.of(new LexicalIndex.Scored(1,.9),new LexicalIndex.Scored(2,.8)));
        assertEquals(1,fused.get(0).index());assertEquals(1d/61+1d/62,fused.get(0).score(),1e-12);
    }
}

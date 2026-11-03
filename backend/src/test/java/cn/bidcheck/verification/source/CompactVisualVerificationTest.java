package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Loopback fixtures only: never calls an external model. */
class CompactVisualVerificationTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<JsonNode> payload=new AtomicReference<>();final AtomicInteger bytesReturned=new AtomicInteger();
    HttpServer server;Path pdf;Document doc;String mode="";Object usage=123;
    @BeforeEach void setup()throws Exception{
        pdf=temp.resolve("compact.pdf");try(PDDocument file=new PDDocument()){for(int i=0;i<3;i++)file.addPage(new PDPage(new PDRectangle(400,300)));file.save(pdf.toFile());}
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(VisualContinuationTest.markdown(1,"拟投入车辆一览表","车牌号","渝A12341"), VisualContinuationTest.markdown(2,"续表","车牌号","渝A12342"), VisualContinuationTest.markdown(3,"续表","车牌号","渝A12343"));
        doc=new Document("compact-doc","project","BID","compact.pdf",1,SourceSafety.digest(Files.readAllBytes(pdf)),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/vision",exchange->{try{
            calls.incrementAndGet();JsonNode request=json.readTree(exchange.getRequestBody());payload.set(request);
            JsonNode instruction=instruction(request);boolean compact=instruction.has("wire");
            if(mode.equals("timeout")){Thread.sleep(500);return;}
            ObjectNode answer=json.createObjectNode();
            if("FIELD".equals(instruction.path("task").asText())){
                answer.put("coordinateSpace","IMAGE_PIXELS").put("imageWidth",instruction.path("imageWidth").asInt()).put("imageHeight",instruction.path("imageHeight").asInt());
                answer.putArray("observations").addObject().put("type","FIELD").put("label","车牌号").put("value","渝A12341").put("readability","CLEAR").putArray("bbox").add(0).add(0).add(10).add(10);
            }else{
                if(compact)answer.put("wire",StructuredCompactResponse.VERSION);
                ArrayNode pagesOut=answer.putArray("pages");
                for(JsonNode descriptor:instruction.path("pages")){
                    int number=descriptor.path("page").asInt();if(mode.equals("missing")&&number==2)continue;
                    ObjectNode page=compactPage(number);
                    if(mode.equals("bad-index")&&number==2)((ArrayNode)page.path("f").get(0)).set(3,IntNode.valueOf(3));
                    if(mode.equals("moved")&&number==2){((ArrayNode)page.get("q")).set(0,TextNode.valueOf(quote(1)));((ArrayNode)page.path("f").get(0)).set(1,TextNode.valueOf("渝A12341"));}
                    if(mode.equals("owner")&&number==2)((ArrayNode)page.path("r").get(0)).set(0,TextNode.valueOf("LSVAB123456789011"));
                    if(mode.equals("empty")||mode.equals("mixed-empty")&&number==2){page.putArray("q");page.putArray("r");page.putArray("f");}
                    if(mode.equals("unreadable"))((ArrayNode)page.path("f").get(0)).set(2,TextNode.valueOf("UNREADABLE"));
                    pagesOut.add(compact?page:StructuredCompactResponse.page(page));
                    if(mode.equals("duplicate-extra")&&number==2)pagesOut.add(page.deepCopy());
                }
                if(mode.equals("extra")||mode.equals("duplicate-extra"))pagesOut.add(compactPage(1).put("page",999));
            }
            answer.putArray("limitations");String content=json.writeValueAsString(answer);if(mode.equals("malformed"))content="{not-json";
            ObjectNode outer=json.createObjectNode();outer.putArray("choices").addObject().put("finish_reason",mode.equals("truncated")?"length":"stop").putObject("message").put("content",content);
            if(usage!=null)outer.putObject("usage").set("completion_tokens",json.valueToTree(usage));
            outer.put("provider_debug","DO_NOT_COPY_RESPONSE_BODY");byte[] bytes=json.writeValueAsBytes(outer);bytesReturned.set(bytes.length);
            exchange.sendResponseHeaders(mode.equals("http-error")?400:200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/vision";}
    VisualVerificationService client(boolean compact){return client(compact,Duration.ofSeconds(3));}
    VisualVerificationService client(boolean compact,Duration timeout){return new VisualVerificationService(true,endpoint(),"fixture-secret","test-model",timeout,timeout,true,6,4,16,8_000_000,8*1024*1024,compact);}
    String quote(int page){return cn.bidcheck.compat.Java8Extras.lines(doc.pages().get(page-1).text()).filter(l->l.startsWith("| "+page+" |")).findFirst().orElseThrow(java.util.NoSuchElementException::new);}
    ObjectNode compactPage(int page){
        ObjectNode result=json.createObjectNode().put("page",page);result.putArray("q").add(quote(page));
        result.putArray("r").addArray().add("LSVAB12345678901"+page).add("VEHICLE").add("LSVAB12345678901"+page);
        result.putArray("f").addArray().add("plateNumber").add("渝A1234"+page).add("CLEAR").add(0).add(0);result.putArray("limitations");return result;
    }
    JsonNode instruction(JsonNode request)throws Exception{return json.readTree(request.path("messages").get(1).path("content").get(0).path("text").asText());}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    Map<String,Object> extract(VisualVerificationService service,List<Integer> pages){return service.extractIndependentPages(doc,pdf,pages,()->true,"run");}
    Set<Integer> factPages(Map<String,Object> out){Set<Integer> pages=new HashSet<>();for(Map<String,Object> fact:list(map(out.get("factBatch")).get("facts")))pages.add((Integer)map(fact.get("attributes")).get("sourcePage"));return pages;}

    @Test void compactPayloadExpandsToEquivalentGroundedPageFactsAndKeepsExistingTokenAndSourceLimits()throws Exception{
        Map<String,Object> out=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals("OBSERVED",out.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 2, 3),factPages(out));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),map(out.get("factBatch")).get("contextRelations"));assertEquals(8192,payload.get().path("max_tokens").asInt());
        JsonNode instruction=instruction(payload.get());assertEquals(StructuredCompactResponse.VERSION,instruction.path("wire").asText());assertEquals(StructuredCompactResponse.VERSION,instruction.path("schema").asText());
        assertEquals(VisualVerificationService.FACT_SCHEMA,map(out.get("factBatch")).get("schemaVersion"));
        for(Map<String,Object> observed:list(out.get("observations"))){assertNull(observed.get("bbox"));assertEquals("FIELD",observed.get("type"));assertEquals(quote((Integer)observed.get("page")),observed.get("sourceQuote"));}
        Map<String,Object> legacy=extract(client(false),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals(map(legacy.get("factBatch")).get("sources"),map(out.get("factBatch")).get("sources"));
        assertEquals(cn.bidcheck.compat.Java8Api.toList(list(map(legacy.get("factBatch")).get("facts")).stream().map(f->f.get("id"))),cn.bidcheck.compat.Java8Api.toList(list(map(out.get("factBatch")).get("facts")).stream().map(f->f.get("id"))));
        Page original=doc.pages().get(0);List<Page> longPages=new ArrayList<>(doc.pages());longPages.set(0,new Page(1,original.text()+cn.bidcheck.compat.Java8Api.repeat("字", 17000),"PAGE","PDF_OCR",""));
        Document longer=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),"",longPages,cn.bidcheck.compat.Java8Api.listOf());
        client(true).extractIndependentPages(longer,pdf,cn.bidcheck.compat.Java8Api.listOf(1),()->true,"long");assertEquals(16000,instruction(payload.get()).path("pages").get(0).path("sourceText").asText().length());assertEquals(6144,payload.get().path("max_tokens").asInt());
    }
    @Test void flagOffPreservesStructuredProtocolButAdmissionPolicyInvalidatesOldIdentity()throws Exception{
        VisualVerificationService legacy=new VisualVerificationService(true,endpoint(),"fixture-secret","test-model",Duration.ofSeconds(3),Duration.ofSeconds(3),true,6,4,16,8_000_000,8*1024*1024);
        String original=SourceSafety.digest(("visual-source-observation-5-structured-envelope\n"+VisualPageWindows.VERSION+"\n"+endpoint()+"\ntest-model\n8000000\n8388608\nPT3S").getBytes(StandardCharsets.UTF_8));
        String revised=SourceSafety.digest(("visual-source-observation-6-field-admission\n"+VisualObservationPolicy.VERSION+"\n"+VisualPageWindows.VERSION+"\n"+endpoint()+"\ntest-model\n8000000\n8388608\nPT3S").getBytes(StandardCharsets.UTF_8));
        assertNotEquals(original,legacy.identity());assertEquals(revised,legacy.identity());assertEquals(revised,client(false).identity());assertNotEquals(revised,client(true).identity());
        assertFalse(legacy.compactStructuredOutputEnabled());assertEquals(VisualVerificationService.FACT_SCHEMA,legacy.structuredWireVersion());assertEquals(StructuredCompactResponse.VERSION,client(true).structuredWireVersion());
        assertEquals("CANONICAL_ONLY",legacy.structuredRoutingPolicy());assertEquals("COMPACT_INDEPENDENT_BATCHES_ONLY_V1",client(true).structuredRoutingPolicy());
        Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("task", "STRUCTURED_FIELDS", "schema", VisualVerificationService.FACT_SCHEMA);
        assertEquals(legacy.requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request),client(false).requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request));assertNotEquals(legacy.requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request),client(true).requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request));
        assertEquals("OBSERVED",extract(legacy,cn.bidcheck.compat.Java8Api.listOf(1)).get("status"));assertFalse(instruction(payload.get()).has("wire"));assertFalse(payload.get().path("messages").get(0).path("content").asText().contains(StructuredCompactResponse.VERSION));
        assertEquals(VisualVerificationService.FACT_SCHEMA,instruction(payload.get()).path("schema").asText());
    }
    @Test void revisedCompactPromptHasOneWireContractAndItsOwnCacheIdentity()throws Exception{
        VisualVerificationService compact=client(true);Map<String,Object> out=extract(compact,cn.bidcheck.compat.Java8Api.listOf(1, 2));
        String prompt=payload.get().path("messages").get(0).path("content").asText();
        assertFalse(prompt.contains("bbox"));assertFalse(prompt.contains("coordinateSpace"));assertFalse(prompt.contains("observations"));
        assertTrue(prompt.contains("先选择本页最多40个字段"));assertTrue(prompt.contains("q[q索引]必须包含该条完整value"));
        assertTrue(prompt.contains("每项恰好3个JSON字符串"));assertTrue(prompt.contains("label字符串、value字符串"));
        assertTrue(prompt.contains("q/r索引均在本页范围内"));assertTrue(prompt.contains("未能提取的内容留待复核"));
        String base="visual-source-observation-6-field-admission\n"+VisualObservationPolicy.VERSION+"\n"+VisualPageWindows.VERSION+"\n"+endpoint()+"\ntest-model\n8000000\n8388608\nPT3S\n"+StructuredCompactResponse.VERSION;
        String oldCompact=SourceSafety.digest(base.getBytes(StandardCharsets.UTF_8));
        String promptOnly=base+"\n"+SourceSafety.digest(prompt.getBytes(StandardCharsets.UTF_8));
        String revised=SourceSafety.digest((promptOnly+"\n"+compact.structuredRoutingPolicy()).getBytes(StandardCharsets.UTF_8));
        assertNotEquals(oldCompact,compact.identity());assertNotEquals(SourceSafety.digest(promptOnly.getBytes(StandardCharsets.UTF_8)),compact.identity());assertEquals(revised,compact.identity());
        assertEquals(StructuredCompactResponse.VERSION,instruction(payload.get()).path("schema").asText());
        assertEquals(StructuredCompactResponse.VERSION,instruction(payload.get()).path("wire").asText());
        assertEquals(VisualVerificationService.FACT_SCHEMA,map(out.get("factBatch")).get("schemaVersion"));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 2),factPages(out));
    }
    @Test void singleAndRelatedWindowsUseCanonicalWhileIndependentBatchesUseCompactWithoutCacheMixing()throws Exception{
        VisualVerificationService service=client(true);
        Map<String,Object> single=extract(service,cn.bidcheck.compat.Java8Api.listOf(1));assertCanonical(single);assertEquals(cn.bidcheck.compat.Java8Api.setOf(1),factPages(single));
        assertFalse(VisualPageWindows.relations(doc,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3)).isEmpty());
        Map<String,Object> related=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"related");assertCanonical(related);
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 2, 3),factPages(related));assertFalse(list(map(related.get("factBatch")).get("contextRelations")).isEmpty());
        Map<String,Object> independent=extract(service,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals(StructuredCompactResponse.VERSION,independent.get("wireVersion"));
        assertEquals(StructuredCompactResponse.VERSION,instruction(payload.get()).path("schema").asText());assertTrue(instruction(payload.get()).path("independentPages").asBoolean());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),map(independent.get("factBatch")).get("contextRelations"));assertEquals(3,calls.get());
        Map<String,Object> cached=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->fail("canonical cache must remain distinct from independent mode"),"related-again");
        assertEquals(true,cached.get("cacheHit"));assertEquals(VisualVerificationService.FACT_SCHEMA,cached.get("wireVersion"));assertEquals(false,cached.get("requestAttempted"));assertEquals(3,calls.get());
    }
    void assertCanonical(Map<String,Object> out)throws Exception{
        assertEquals("OBSERVED",out.get("status"));assertEquals(VisualVerificationService.FACT_SCHEMA,out.get("wireVersion"));
        assertEquals(VisualVerificationService.FACT_SCHEMA,instruction(payload.get()).path("schema").asText());assertFalse(instruction(payload.get()).has("wire"));
        String system=payload.get().path("messages").get(0).path("content").asText();assertTrue(system.contains("observations"));assertFalse(system.contains(StructuredCompactResponse.VERSION));
    }
    @Test void ordinaryFieldProtocolDoesNotChangeWhenCompactFeatureIsEnabled()throws Exception{
        Map<String,Object> out=client(true).verify(doc,pdf,1,cn.bidcheck.compat.Java8Api.mapOf("task", "FIELD", "fields", cn.bidcheck.compat.Java8Api.listOf("车牌号")));assertEquals("OBSERVED",out.get("status"));
        assertEquals("VISUAL_OBSERVATION_V1",out.get("wireVersion"));assertFalse(instruction(payload.get()).has("wire"));assertEquals(4096,payload.get().path("max_tokens").asInt());
        assertTrue(payload.get().path("messages").get(0).path("content").asText().contains("coordinateSpace"));assertFalse(payload.get().path("messages").get(0).path("content").asText().contains(StructuredCompactResponse.VERSION));
    }
    @Test void aMalformedPageIsIsolatedAndUnknownOrDuplicatePageRecordsNeverProduceFacts(){
        for(String value:cn.bidcheck.compat.Java8Api.listOf("bad-index", "missing", "duplicate-extra")){mode=value;Map<String,Object> out=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals("PARTIAL",out.get("status"),value);assertEquals(cn.bidcheck.compat.Java8Api.listOf(2),out.get("failedPages"));assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 3),factPages(out));assertEquals(2,((Number)out.get("fieldCount")).intValue());}
        mode="extra";Map<String,Object> out=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 2, 3),factPages(out));assertEquals(1,out.get("rejectedPageRecords"));
        assertEquals("VISUAL_PAGE_MAPPING_INVALID",extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1)).get("errorCode"));
    }
    @Test void compactReferencesStillRejectWrongPageQuotesAndCannotEstablishUnrelatedOwners(){
        mode="moved";Map<String,Object> out=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 3),factPages(out));assertEquals(3,list(out.get("observations")).size());
        assertEquals("CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE",list(map(out.get("factBatch")).get("rejected")).get(0).get("code"));
        mode="owner";out=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));Map<String,Object> fact=list(map(out.get("factBatch")).get("facts")).get(1);
        assertEquals("",fact.get("subject"));assertEquals("UNRESOLVED",map(fact.get("attributes")).get("subjectBinding"));
    }
    @Test void onlyValidExplicitEmptyPagesCanReachNoFactsAndInvalidResponsesRemainRetryableFailures(){
        mode="empty";Map<String,Object> empty=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2));assertEquals("VISUAL_NO_GROUNDED_OBSERVATIONS",empty.get("errorCode"));assertEquals("COMPLETED",list(empty.get("pageResults")).get(0).get("status"));
        mode="mixed-empty";Map<String,Object> mixed=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2, 3));assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 3),factPages(mixed));assertTrue(list(list(mixed.get("pageResults")).get(1).get("observations")).isEmpty());
        mode="bad-index";Map<String,Object> bad=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2));assertEquals("VISUAL_WINDOW_PARTIAL",bad.get("errorCode"));assertEquals("VISUAL_COMPACT_SCHEMA_INVALID",list(bad.get("pageResults")).get(1).get("errorCode"));assertTrue(VisualVerificationService.splitRetryable(bad));assertFalse(VisualVerificationService.cacheable(bad));assertEquals(cn.bidcheck.compat.Java8Api.setOf(1),factPages(bad));
        mode="unreadable";assertEquals("VISUAL_SCHEMA_INVALID",extract(client(true),cn.bidcheck.compat.Java8Api.listOf(2)).get("errorCode"));
    }
    @Test void safeAttemptTelemetryIncludesFailuresAndSeparatesCacheReuseFromAnActualCall()throws Exception{
        VisualVerificationService service=client(true);Map<String,Object> success=extract(service,cn.bidcheck.compat.Java8Api.listOf(1, 2));assertTelemetry(success,true);assertEquals(123L,((Number)map(success.get("usage")).get("completion_tokens")).longValue());assertEquals(2,((Number)success.get("fieldCount")).intValue());
        Map<String,Object> cached=service.extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2),()->fail("cache hit must not reserve another attempt"),"next");assertEquals(1,calls.get());assertEquals(true,cached.get("cacheHit"));assertEquals(false,cached.get("requestAttempted"));assertEquals(true,cached.get("requestTelemetryReused"));assertEquals(success.get("requestStartedAt"),cached.get("requestStartedAt"));
        for(String value:cn.bidcheck.compat.Java8Api.listOf("malformed", "truncated", "http-error")){mode=value;Map<String,Object> failure=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2));assertTelemetry(failure,true);assertEquals("UNDETERMINED",failure.get("status"));assertTrue(factPages(failure).isEmpty());assertNull(failure.get("fieldCount"));}
        mode="timeout";Map<String,Object> timeout=extract(client(true,Duration.ofMillis(50)),cn.bidcheck.compat.Java8Api.listOf(1, 2));assertEquals("VISUAL_TIMEOUT",timeout.get("errorCode"));assertTelemetry(timeout,false);
    }
    void assertTelemetry(Map<String,Object> out,boolean hasBody)throws Exception{
        assertEquals(true,out.get("requestAttempted"));assertEquals(false,out.get("requestTelemetryReused"));assertEquals(StructuredCompactResponse.VERSION,out.get("wireVersion"));
        assertTrue(((Number)out.get("requestStartedAt")).longValue()>0);assertTrue(((Number)out.get("requestFinishedAt")).longValue()>=((Number)out.get("requestStartedAt")).longValue());
        if(hasBody)assertEquals(bytesReturned.get(),out.get("responseBytes"));else assertTrue(((Number)out.get("responseBytes")).intValue()>=0);
        assertFalse(json.writeValueAsString(out).contains("fixture-secret"));assertFalse(json.writeValueAsString(out).contains("DO_NOT_COPY_RESPONSE_BODY"));
    }
    @Test void invalidUsageIsNotCoercedAndDeniedBudgetDoesNotInventHttpTiming(){
        for(Object invalid:cn.bidcheck.compat.Java8Api.listOf(-1, 1.5, "123", true)){usage=invalid;Map<String,Object> out=extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2));assertEquals(cn.bidcheck.compat.Java8Api.mapOf(),out.get("usage"));}
        usage=null;assertEquals(cn.bidcheck.compat.Java8Api.mapOf(),extract(client(true),cn.bidcheck.compat.Java8Api.listOf(1, 2)).get("usage"));int before=calls.get();
        Map<String,Object> denied=client(true).extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2),()->false,"denied");assertEquals(before,calls.get());assertEquals("VISUAL_BUDGET_EXHAUSTED",denied.get("errorCode"));
        assertEquals(false,denied.get("requestAttempted"));assertNull(denied.get("requestStartedAt"));assertNull(denied.get("requestFinishedAt"));assertNull(denied.get("responseBytes"));assertNull(denied.get("fieldCount"));
    }
}

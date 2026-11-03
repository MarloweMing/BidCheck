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

/** Production adapter exercised only against a local fixture server. */
class SourceSpanVisualVerificationTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<JsonNode> payload=new AtomicReference<>();
    HttpServer server;Path pdf;Document doc;String mode="";

    @BeforeEach void setup()throws Exception{
        pdf=temp.resolve("source-span.pdf");try(PDDocument file=new PDDocument()){for(int i=0;i<3;i++)file.addPage(new PDPage(new PDRectangle(400,300)));file.save(pdf.toFile());}
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(VisualContinuationTest.markdown(1,"拟投入车辆一览表","车牌号","渝A12341"), VisualContinuationTest.markdown(2,"续表","车牌号","渝A12342"), VisualContinuationTest.markdown(3,"续表","车牌号","渝A12343"));
        doc=new Document("span-doc","project","BID","source-span.pdf",1,SourceSafety.digest(Files.readAllBytes(pdf)),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/vision",exchange->{try{
            calls.incrementAndGet();JsonNode request=json.readTree(exchange.getRequestBody());payload.set(request);
            JsonNode instruction=instruction(request);String wire=instruction.path("wire").asText();ObjectNode answer=json.createObjectNode();
            if(mode.equals("timeout")){Thread.sleep(500);return;}
            if("FIELD".equals(instruction.path("task").asText())){
                answer.put("coordinateSpace","IMAGE_PIXELS").put("imageWidth",instruction.path("imageWidth").asInt()).put("imageHeight",instruction.path("imageHeight").asInt());
                answer.putArray("observations").addObject().put("type","FIELD").put("label","车牌号").put("value","渝A12341").put("readability","CLEAR").putArray("bbox").add(0).add(0).add(10).add(10);
            }else{
                if(!wire.isEmpty())answer.put("wire",wire);ArrayNode pagesOut=answer.putArray("pages");
                for(JsonNode descriptor:instruction.path("pages")){
                    int page=descriptor.path("page").asInt();
                    if(StructuredSourceSpans.VERSION.equals(wire))pagesOut.add(spanPage(descriptor,page));
                    else{ObjectNode compact=compactPage(page);JsonNode decoded=StructuredCompactResponse.VERSION.equals(wire)?compact:StructuredCompactResponse.page(compact);
                        if(mode.equals("canonical-over-cap")&&decoded.path("observations").isArray()){ArrayNode observations=(ArrayNode)decoded.get("observations");for(int i=1;i<41;i++)observations.add(observations.get(0).deepCopy());}
                        pagesOut.add(decoded);
                    }
                }
            }
            answer.putArray("limitations");ObjectNode outer=json.createObjectNode();outer.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("content",json.writeValueAsString(answer));
            outer.putObject("usage").put("completion_tokens",123);byte[] bytes=json.writeValueAsBytes(outer);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/vision";}
    VisualVerificationService client(boolean compact,boolean span){return client(compact,span,Duration.ofSeconds(3));}
    VisualVerificationService client(boolean compact,boolean span,Duration duration){return new VisualVerificationService(true,endpoint(),"fixture-secret","test-model",duration,duration,true,6,4,16,8_000_000,8*1024*1024,compact,span);}
    String quote(int page){return cn.bidcheck.compat.Java8Extras.lines(doc.pages().get(page-1).text()).filter(l->l.startsWith("| "+page+" |")).findFirst().orElseThrow(java.util.NoSuchElementException::new);}
    ObjectNode compactPage(int page){
        ObjectNode result=json.createObjectNode().put("page",page);result.putArray("q").add(quote(page));
        result.putArray("r").addArray().add("LSVAB12345678901"+page).add("VEHICLE").add("LSVAB12345678901"+page);
        result.putArray("f").addArray().add("plateNumber").add("渝A1234"+page).add("CLEAR").add(0).add(0);result.putArray("limitations");return result;
    }
    ObjectNode spanPage(JsonNode descriptor,int page){
        String id="";for(JsonNode span:descriptor.path("sourceSpans"))if(span.path("text").asText().contains("渝A1234"+page)){id=span.path("id").asText();break;}
        ObjectNode result=json.createObjectNode().put("page",page).put("hasMore",mode.equals("has-more"));
        ArrayNode fields=result.putArray("f");int count=mode.equals("at-cap")?40:mode.equals("over-cap")?121:mode.equals("at-hard-cap")?120:mode.equals("partial-53")?53:mode.equals("empty")||mode.equals("empty-more")?0:1;
        for(int i=0;i<count;i++)fields.addArray().add(mode.equals("partial-53")||mode.equals("at-hard-cap")?"field"+i:"plateNumber").add("渝A1234"+page).add("CLEAR").add(id).add("LSVAB12345678901"+page).add("VEHICLE").add("LSVAB12345678901"+page);
        if(mode.equals("empty-more"))result.put("hasMore",true);
        if(mode.equals("invalid-id"))((ArrayNode)fields.get(0)).set(3,TextNode.valueOf("UNRECOGNIZED_SOURCE_SPAN"));
        if(mode.equals("wrong-page"))result.put("page",99);
        if(mode.equals("invented-value"))((ArrayNode)fields.get(0)).set(1,TextNode.valueOf("NO_SOURCE_VALUE"));
        if(mode.equals("tuple-extra"))((ArrayNode)fields.get(0)).add(123);
        if(mode.equals("model-offset"))result.put("sourceSpan",12);
        if(mode.equals("has-more-string"))result.put("hasMore","false");
        if(mode.equals("has-more-missing"))result.remove("hasMore");
        if(mode.equals("partial-53")){
            for(int i:new int[]{1,24})((ArrayNode)fields.get(i)).set(5,TextNode.valueOf("INVALID_ENTITY"));
            for(int i:new int[]{5,31})((ArrayNode)fields.get(i)).set(1,TextNode.valueOf("VALUE_NOT_ON_SOURCE"));
        }
        if(mode.equals("invalid-enum"))((ArrayNode)fields.get(0)).set(5,TextNode.valueOf("INVALID_ENTITY"));
        if(mode.equals("unreadable-value"))((ArrayNode)fields.get(0)).set(2,TextNode.valueOf("UNREADABLE"));
        ArrayNode notes=result.putArray("limitations");if(mode.equals("unreadable-note"))notes.add("该页无法确认清晰程度");return result;
    }
    JsonNode instruction(JsonNode request)throws Exception{return json.readTree(request.path("messages").get(1).path("content").get(0).path("text").asText());}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    Map<String,Object> single(VisualVerificationService service){return service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1),()->true,"run");}
    List<Map<String,Object>> facts(Map<String,Object> value){return list(map(value.get("factBatch")).get("facts"));}

    @Test void singlePageUsesTrustedExactSourceSpansAndCanonicalFactValidation()throws Exception{
        Map<String,Object> out=single(client(true,true));assertEquals("OBSERVED",out.get("status"));assertEquals(1,facts(out).size());
        assertEquals(StructuredSourceSpans.VERSION,out.get("wireVersion"));JsonNode instruction=instruction(payload.get());
        assertEquals(StructuredSourceSpans.VERSION,instruction.path("schema").asText());assertEquals(StructuredSourceSpans.VERSION,instruction.path("wire").asText());
        JsonNode descriptor=instruction.path("pages").get(0);assertFalse(descriptor.has("sourceText"));assertTrue(descriptor.path("sourceSpans").isArray());
        for(JsonNode span:descriptor.path("sourceSpans")){assertEquals(cn.bidcheck.compat.Java8Api.setOf("id", "text"),keys(span));assertTrue(doc.pages().get(0).text().contains(span.path("text").asText()));}
        Map<String,Object> observation=list(out.get("observations")).get(0),binding=map(observation.get("sourceSpan"));
        int start=((Number)binding.get("start")).intValue(),end=((Number)binding.get("end")).intValue();
        assertEquals(doc.pages().get(0).text().substring(start,end),observation.get("sourceQuote"));assertEquals(1,binding.get("page"));
        assertEquals(StructuredSourceSpans.VERSION,binding.get("version"));assertEquals(VisualVerificationService.FACT_SCHEMA,map(out.get("factBatch")).get("schemaVersion"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),out.get("coverageLimitedPages"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),out.get("coverageLimitations"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),observation.get("modelLimitations"));assertEquals(6144,payload.get().path("max_tokens").asInt());
        assertTrue(payload.get().path("messages").get(0).path("content").asText().contains("每条f恰好7个JSON字符串"));
    }
    static Set<String> keys(JsonNode node){Set<String> out=new HashSet<>();node.fieldNames().forEachRemaining(out::add);return out;}

    @Test void routingSelectsSpanOnlyForStructuredSinglePagesIncludingIndependentSingle()throws Exception{
        VisualVerificationService service=client(true,true);
        assertEquals(StructuredSourceSpans.VERSION,single(service).get("wireVersion"));
        Map<String,Object> independent=service.extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1),()->true,"independent");
        assertEquals(StructuredSourceSpans.VERSION,independent.get("wireVersion"));assertEquals(true,independent.get("independentPages"));assertEquals(1,facts(independent).size());
        Map<String,Object> batch=service.extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"batch");assertEquals(StructuredCompactResponse.VERSION,batch.get("wireVersion"));assertEquals(3,facts(batch).size());
        assertFalse(instruction(payload.get()).path("pages").get(0).has("sourceSpans"));
        Map<String,Object> related=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"related");assertEquals(VisualVerificationService.FACT_SCHEMA,related.get("wireVersion"));assertEquals(3,facts(related).size());
        assertFalse(instruction(payload.get()).has("wire"));
        Map<String,Object> ordinary=service.verify(doc,pdf,1,cn.bidcheck.compat.Java8Api.mapOf("task", "FIELD", "fields", cn.bidcheck.compat.Java8Api.listOf("车牌号")));assertEquals("VISUAL_OBSERVATION_V1",ordinary.get("wireVersion"));assertEquals("OBSERVED",ordinary.get("status"));
        assertFalse(instruction(payload.get()).has("wire"));assertEquals(4096,payload.get().path("max_tokens").asInt());
    }

    @Test void disabledSpanPreservesAllExistingConstructorIdentitiesAndMixedRouting(){
        VisualVerificationService prior=new VisualVerificationService(true,endpoint(),"fixture-secret","test-model",Duration.ofSeconds(3),Duration.ofSeconds(3),true,6,4,16,8_000_000,8*1024*1024,true);
        assertFalse(prior.sourceSpanOutputEnabled());assertEquals(prior.identity(),client(true,false).identity());
        assertEquals("COMPACT_INDEPENDENT_BATCHES_ONLY_V1",prior.structuredRoutingPolicy());assertEquals(StructuredCompactResponse.VERSION,prior.structuredWireVersion());
        assertEquals(VisualVerificationService.FACT_SCHEMA,single(prior).get("wireVersion"));
        VisualVerificationService span=client(true,true);assertNotEquals(prior.identity(),span.identity());assertTrue(span.sourceSpanOutputEnabled());
        assertEquals("SPAN_SINGLE_COMPACT_INDEPENDENT_BATCHES_V1",span.structuredRoutingPolicy());assertEquals(StructuredSourceSpans.VERSION,span.structuredWireVersion());
        assertEquals("SPAN_SINGLE_CANONICAL_MULTI_V1",client(false,true).structuredRoutingPolicy());assertFalse(client(false,true).compactStructuredOutputEnabled());
        assertEquals(VisualVerificationService.FACT_SCHEMA,client(false,true).extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2),()->true,"no-compact").get("wireVersion"));
        Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("task", "STRUCTURED_FIELDS", "schema", VisualVerificationService.FACT_SCHEMA);
        assertEquals(prior.requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request),client(true,false).requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request));assertNotEquals(prior.requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request),span.requestIdentity(doc,cn.bidcheck.compat.Java8Api.listOf(1),request));
    }

    @Test void spanPromptAndRoutingIdentityIsolatedWithoutChangingLegacyMixedIdentity()throws Exception{
        VisualVerificationService service=client(true,true);single(service);String prompt=payload.get().path("messages").get(0).path("content").asText();
        String compactPrompt=privateString("COMPACT_STRUCTURED_SYSTEM");
        String base="visual-source-observation-6-field-admission\n"+VisualObservationPolicy.VERSION+"\n"+VisualPageWindows.VERSION+"\n"+endpoint()+"\ntest-model\n8000000\n8388608\nPT3S\n"+StructuredCompactResponse.VERSION+"\n"+SourceSafety.digest(compactPrompt.getBytes(StandardCharsets.UTF_8))+"\nCOMPACT_INDEPENDENT_BATCHES_ONLY_V1";
        assertEquals(SourceSafety.digest(base.getBytes(StandardCharsets.UTF_8)),client(true,false).identity());
        assertEquals(SourceSafety.digest((base+"\n"+StructuredSourceSpans.VERSION+"\n"+StructuredSourceSpans.POLICY+"\n"+SourceSafety.digest(prompt.getBytes(StandardCharsets.UTF_8))+"\n"+service.structuredRoutingPolicy()).getBytes(StandardCharsets.UTF_8)),service.identity());
    }
    static String privateString(String name)throws Exception{java.lang.reflect.Field field=VisualVerificationService.class.getDeclaredField(name);field.setAccessible(true);return (String)field.get(null);}

    @Test void moreFieldsAndCapRemainReviewMetadataWhileValidFieldsStillEnterFacts(){
        for(String state:cn.bidcheck.compat.Java8Api.listOf("has-more", "at-cap")){mode=state;Map<String,Object> out=single(client(true,true));
            assertEquals("OBSERVED",out.get("status"));assertEquals(1,facts(out).size());assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),out.get("coverageLimitedPages"));
            assertFalse(((List<?>)out.get("coverageLimitations")).isEmpty());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),out.get("modelLimitations"));
            for(Map<String,Object> observation:list(out.get("observations")))assertEquals(cn.bidcheck.compat.Java8Api.listOf(),observation.get("modelLimitations"));
            assertEquals(out.get("coverageLimitations"),list(out.get("pageResults")).get(0).get("coverageLimitations"));
            assertTrue(list(map(out.get("factBatch")).get("rejected")).isEmpty());assertEquals(state.equals("at-cap")?40:1,((Number)out.get("fieldCount")).intValue());
        }
    }

    @Test void partial53FieldResponseRetains49GroundedFactsWithPerFieldAuditAndCacheReview(){
        mode="partial-53";VisualVerificationService service=client(true,true);Map<String,Object> out=single(service);
        assertEquals("OBSERVED",out.get("status"));assertEquals("",out.get("errorCode"));assertTrue(VisualVerificationService.cacheable(out));
        assertEquals(53,out.get("submittedFieldCount"));assertEquals(51,((Number)out.get("fieldCount")).intValue());assertEquals(49,facts(out).size());
        assertEquals(2,list(out.get("rejectedFields")).size());assertEquals(4,list(map(out.get("factBatch")).get("rejected")).size());
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 24),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(list(out.get("rejectedFields")).stream().map(f->f.get("inputIndex")))));
        for(Map<String,Object> rejected:list(out.get("rejectedFields")))assertEquals(cn.bidcheck.compat.Java8Api.setOf("page", "inputIndex", "code"),rejected.keySet());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),out.get("coverageLimitedPages"));assertTrue(((List<?>)out.get("coverageLimitations")).contains("STRUCTURED_FIELDS_REJECTED"));
        assertTrue(((List<?>)out.get("coverageLimitations")).contains("STRUCTURED_OUTPUT_TARGET_EXCEEDED"));
        Map<String,Object> page=list(out.get("pageResults")).get(0);assertEquals("COMPLETED",page.get("status"));assertEquals(53,page.get("submittedFieldCount"));assertEquals(out.get("rejectedFields"),page.get("rejectedFields"));
        for(Map<String,Object> item:list(out.get("observations"))){assertEquals(cn.bidcheck.compat.Java8Api.listOf(),item.get("modelLimitations"));assertNotNull(StructuredSourceSpans.verifiedBinding(1,doc.pages().get(0).text(),(String)item.get("sourceQuote"),item.get("sourceSpan")));}
        Map<String,Object> cached=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1),()->fail("reviewable valid fields must retain cache"),"cached-partial");
        assertEquals(1,calls.get());assertEquals(false,cached.get("requestAttempted"));assertEquals(49,facts(cached).size());assertEquals(4,list(map(cached.get("factBatch")).get("rejected")).size());assertEquals(out.get("coverageLimitations"),cached.get("coverageLimitations"));
    }

    @Test void onlySpanMayReach120FieldsWhileLegacyCanonicalStillRejectsOver40(){
        mode="at-hard-cap";Map<String,Object> out=single(client(true,true));assertEquals(120,facts(out).size());assertEquals(120,out.get("submittedFieldCount"));
        assertEquals(120,((Number)out.get("fieldCount")).intValue());assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),out.get("coverageLimitedPages"));assertTrue(((List<?>)out.get("coverageLimitations")).contains("STRUCTURED_OUTPUT_TARGET_EXCEEDED"));
        mode="over-cap";Map<String,Object> over=single(client(true,true));assertEquals("VISUAL_SOURCE_SPAN_SCHEMA_INVALID",over.get("errorCode"));assertTrue(facts(over).isEmpty());
        mode="canonical-over-cap";Map<String,Object> legacy=single(client(true,false));assertEquals("VISUAL_SCHEMA_INVALID",legacy.get("errorCode"));assertTrue(facts(legacy).isEmpty());
    }

    @Test void allRejectedFieldsAreAuditedFailuresNotEmptySuccessAndAreNotAutomaticallyRetried(){
        for(String state:cn.bidcheck.compat.Java8Api.listOf("invalid-enum", "invalid-id", "unreadable-value", "tuple-extra")){
            mode=state;Map<String,Object> out=single(client(true,true));assertEquals("VISUAL_SOURCE_SPAN_FIELDS_REJECTED",out.get("errorCode"),state);
            assertEquals("UNDETERMINED",out.get("status"));assertEquals(1,out.get("submittedFieldCount"));assertEquals(1,list(out.get("rejectedFields")).size());assertEquals(1,list(map(out.get("factBatch")).get("rejected")).size());
            assertTrue(facts(out).isEmpty());assertFalse(VisualVerificationService.cacheable(out));assertFalse(VisualVerificationService.singleRetryable(out));assertFalse(VisualVerificationService.splitRetryable(out));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),out.get("failedPages"));assertEquals("FAILED",list(out.get("pageResults")).get(0).get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),out.get("coverageLimitedPages"));
        }
    }

    @Test void boundedInputRetainsAcceptedFactsAndReportsUnsubmittedSourceCoverage()throws Exception{
        List<Page> pages=new ArrayList<>(doc.pages());Page page=pages.get(0);pages.set(0,new Page(1,page.text()+"\n"+cn.bidcheck.compat.Java8Api.repeat("未送检文字", 4000),"PAGE","PDF_OCR",""));
        doc=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> out=single(client(true,true));assertEquals(1,facts(out).size());assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),out.get("coverageLimitedPages"));
        assertFalse(((List<?>)out.get("coverageLimitations")).isEmpty());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),list(out.get("observations")).get(0).get("modelLimitations"));
        assertFalse(instruction(payload.get()).path("pages").get(0).has("sourceText"));
    }

    @Test void invalidSpanMappingTuplesAndLimitsCannotBecomeNoFactsOrLeakTrustedBindings(){
        for(String state:cn.bidcheck.compat.Java8Api.listOf("invalid-id", "wrong-page", "tuple-extra", "model-offset", "has-more-string", "has-more-missing", "over-cap")){
            mode=state;Map<String,Object> out=single(client(true,true));assertEquals("UNDETERMINED",out.get("status"),state);assertFalse("VISUAL_NO_GROUNDED_OBSERVATIONS".equals(out.get("errorCode")),state);
            assertFalse(cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(out.get("errorCode"),"")),state);assertTrue(facts(out).isEmpty(),state);assertFalse(VisualVerificationService.cacheable(out),state);
            assertEquals(StructuredSourceSpans.VERSION,out.get("wireVersion"));assertEquals(true,out.get("requestAttempted"));assertTrue(((Number)out.get("responseBytes")).intValue()>0);
            assertTrue(list(out.get("observations")).isEmpty());
        }
        mode="invented-value";Map<String,Object> invented=single(client(true,true));assertEquals("OBSERVED",invented.get("status"));assertTrue(facts(invented).isEmpty());
        assertEquals("CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE",list(map(invented.get("factBatch")).get("rejected")).get(0).get("code"));
    }

    @Test void emptyValidResponseStillNeedsReviewAndModelLimitationsDoNotBecomeCoverageClaims(){
        mode="empty";Map<String,Object> empty=single(client(true,true));assertEquals("VISUAL_NO_GROUNDED_OBSERVATIONS",empty.get("errorCode"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),empty.get("coverageLimitedPages"));
        assertEquals("COMPLETED",list(empty.get("pageResults")).get(0).get("status"));assertTrue(facts(empty).isEmpty());
        mode="empty-more";Map<String,Object> more=single(client(true,true));assertEquals("VISUAL_NO_GROUNDED_OBSERVATIONS",more.get("errorCode"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),more.get("coverageLimitedPages"));assertTrue(facts(more).isEmpty());
        mode="unreadable-note";Map<String,Object> uncertain=single(client(true,true));assertTrue(facts(uncertain).isEmpty());assertEquals("CONTEXT_FIELD_NEEDS_REVIEW",list(map(uncertain.get("factBatch")).get("rejected")).get(0).get("code"));
    }

    @Test void cacheAndTelemetryDoNotResendOrReuseBindingsForChangedSourceText(){
        VisualVerificationService service=client(true,true);Map<String,Object> first=single(service);
        Map<String,Object> cached=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1),()->fail("cache must not reserve"),"next");assertEquals(1,calls.get());
        assertEquals(false,cached.get("requestAttempted"));assertEquals(true,cached.get("cacheHit"));assertEquals(true,cached.get("requestTelemetryReused"));assertEquals(StructuredSourceSpans.VERSION,cached.get("wireVersion"));
        List<Page> pages=new ArrayList<>(doc.pages());Page old=pages.get(0);pages.set(0,new Page(1,"新前缀\n"+old.text(),"PAGE",old.parser(),old.warning()));
        doc=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf());Map<String,Object> changed=single(service);
        assertEquals(2,calls.get());assertEquals(1,facts(changed).size());assertNotEquals(first.get("requestFingerprint"),changed.get("requestFingerprint"));
        assertNotEquals(map(list(first.get("observations")).get(0).get("sourceSpan")).get("sourceTextHash"),map(list(changed.get("observations")).get(0).get("sourceSpan")).get("sourceTextHash"));
    }

    @Test void deniedBudgetAndRealTimeoutKeepActualSpanWireAndHonestAttemptTiming(){
        Map<String,Object> denied=client(true,true).extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1),()->false,"denied");assertEquals(0,calls.get());assertEquals(false,denied.get("requestAttempted"));assertEquals(StructuredSourceSpans.VERSION,denied.get("wireVersion"));assertNull(denied.get("requestStartedAt"));
        mode="timeout";Map<String,Object> timeout=single(client(true,true,Duration.ofMillis(50)));assertEquals("VISUAL_TIMEOUT",timeout.get("errorCode"));assertEquals(StructuredSourceSpans.VERSION,timeout.get("wireVersion"));assertEquals(true,timeout.get("requestAttempted"));
        assertTrue(((Number)timeout.get("requestFinishedAt")).longValue()>=((Number)timeout.get("requestStartedAt")).longValue());assertTrue(facts(timeout).isEmpty());
    }
}

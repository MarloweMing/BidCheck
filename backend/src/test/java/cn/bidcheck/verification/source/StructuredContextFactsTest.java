package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StructuredContextFactsTest {
    @TempDir Path temp;final ObjectMapper json=new ObjectMapper();final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<JsonNode> payload=new AtomicReference<>();HttpServer server;Path pdf;Document doc;String mode="";
    @BeforeEach void setup()throws Exception{
        pdf=temp.resolve("table.pdf");try(PDDocument file=new PDDocument()){for(int i=0;i<3;i++)file.addPage(new PDPage(new PDRectangle(400,300)));file.save(pdf.toFile());}
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(VisualContinuationTest.markdown(1,"拟投入车辆一览表","车牌号","渝A12341"), VisualContinuationTest.markdown(2,"续表","车牌号","渝A12342"), VisualContinuationTest.markdown(3,"续表","车牌号","渝A12343"));
        doc=new Document("doc","project","BID","table.pdf",1,SourceSafety.digest(Files.readAllBytes(pdf)),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/vision",exchange->{try{
            calls.incrementAndGet();JsonNode request=json.readTree(exchange.getRequestBody());payload.set(request);
            JsonNode instruction=json.readTree(request.path("messages").get(1).path("content").get(0).path("text").asText());
            boolean multi=instruction.path("pages").size()>1;ObjectNode answer=json.createObjectNode();
            if(multi||mode.contains("envelope")){ArrayNode pagesOut=answer.putArray("pages");for(JsonNode page:instruction.path("pages"))if(!mode.equals("missing")||page.path("page").asInt()!=2){
                ObjectNode result=page(page).put("page",page.path("page").asInt());
                if(mode.contains("wrong-page"))result.put("page",99);if(mode.contains("missing-page"))result.remove("page");
                pagesOut.add(result);if(mode.contains("duplicate"))pagesOut.add(result.deepCopy());
            }answer.putArray("limitations");}
            else {answer=page(instruction.path("pages").get(0));if(mode.contains("with-page"))answer.put("page",instruction.path("pages").get(0).path("page").asInt());if(mode.contains("wrong-page"))answer.put("page",99);}
            if(mode.equals("fenced")){answer.remove("limitations");for(JsonNode p:answer.path("pages"))((ObjectNode)p).remove("limitations");}
            String content=json.writeValueAsString(answer);if(mode.equals("fenced"))content="```json\n"+content+"\n```";
            byte[] bytes=json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", content)))));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
    }
    ObjectNode page(JsonNode descriptor){
        int number=descriptor.path("page").asInt();ObjectNode answer=json.createObjectNode().put("coordinateSpace","IMAGE_PIXELS")
            .put("imageWidth",descriptor.path("imageWidth").asInt()).put("imageHeight",descriptor.path("imageHeight").asInt());answer.putArray("limitations");
        String quote=cn.bidcheck.compat.Java8Extras.lines(doc.pages().get(number-1).text()).filter(l->l.startsWith("| "+number+" |")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        ObjectNode item=answer.putArray("observations").addObject().put("type","FIELD").put("label","plateNumber").put("value","渝A1234"+number)
            .put("readability","CLEAR").put("subject","LSVAB12345678901"+number).put("entityType","VEHICLE").put("recordKey","LSVAB12345678901"+number).put("sourceQuote",quote);
        item.putNull("bbox");if(mode.equals("moved")&&number==2){item.put("value","渝A12341");item.put("sourceQuote",cn.bidcheck.compat.Java8Extras.lines(doc.pages().get(0).text()).filter(l->l.startsWith("| 1 |")).findFirst().orElseThrow(java.util.NoSuchElementException::new));}
        if(mode.equals("unknown-owner")&&number==2)item.put("subject","LSVAB123456789011");
        if(mode.equals("bbox"))item.putArray("bbox").add(0).add(0).add(99999).add(5);
        if(mode.contains("valid-bbox"))item.putArray("bbox").add(0).add(0).add(5).add(5);
        if(mode.contains("no-coordinates"))answer.remove(cn.bidcheck.compat.Java8Api.listOf("coordinateSpace", "imageWidth", "imageHeight"));
        if(mode.contains("wrong-coordinates"))answer.put("coordinateSpace","NORMALIZED").put("imageWidth",0).put("imageHeight",1);
        return answer;
    }
    @AfterEach void stop(){server.stop(0);}
    VisualVerificationService client(){return new VisualVerificationService(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/vision","test","test-model",Duration.ofSeconds(4),true);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @Test void contextRequestTranscribesIndependentRowsAndReturnsSourceBoundFactBatchWithoutInventingBoxes(){
        Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");assertEquals("OBSERVED",result.get("status"));assertEquals(1,calls.get());
        Map<String,Object> batch=map(result.get("factBatch"));assertEquals(3,list(batch.get("facts")).size());assertEquals(3,list(batch.get("sources")).size());assertEquals(2,list(batch.get("contextRelations")).size());
        for(int i=0;i<3;i++){Map<String,Object> fact=list(batch.get("facts")).get(i),attrs=map(fact.get("attributes"));assertEquals(i+1,attrs.get("sourcePage"));assertEquals("UNAVAILABLE",attrs.get("localization"));assertNull(attrs.get("sourceBbox"));assertEquals("渝A1234"+(i+1),fact.get("value"));}
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),batch.get("layoutCells"));assertEquals(4,payload.get().path("messages").get(1).path("content").size());
        assertTrue(payload.get().path("messages").get(0).path("content").asText().contains("跨页无归属依据时subject留空"));
    }
    @Test void nonAdjacentIndependentPagesShareTransportWithoutInventingContextOrMovingFacts()throws Exception{
        Map<String,Object> out=client().extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 3),()->true,"run");
        assertEquals("OBSERVED",out.get("status"));assertEquals(true,out.get("independentPages"));
        Map<String,Object> batch=map(out.get("factBatch"));assertEquals(2,list(batch.get("facts")).size());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),batch.get("contextRelations"));
        for(Map<String,Object> fact:list(batch.get("facts"))){Map<String,Object> attrs=map(fact.get("attributes"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(attrs.get("sourcePage")),attrs.get("contextWindowPages"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),attrs.get("contextRelationIds"));}
        JsonNode instruction=json.readTree(payload.get().path("messages").get(1).path("content").get(0).path("text").asText());
        assertTrue(instruction.path("independentPages").asBoolean());assertTrue(instruction.path("contextRelations").isEmpty());
        assertEquals("json_object",payload.get().path("response_format").path("type").asText());
        assertEquals(1,calls.get());
    }
    @Test void independentBatchRetainsSuccessfulPagesWhenOnePageIsMissing(){
        mode="missing";Map<String,Object> out=client().extractIndependentPages(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");
        assertEquals("PARTIAL",out.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(2),out.get("failedPages"));
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(1, 3),list(map(out.get("factBatch")).get("facts")).stream().map(f->map(f.get("attributes")).get("sourcePage")).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void exactJsonFenceAndOmittedOptionalLimitationsDoNotDiscardOtherwiseValidatedFacts(){
        mode="fenced";Map<String,Object> out=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");
        assertEquals("OBSERVED",out.get("status"));assertEquals(3,list(map(out.get("factBatch")).get("facts")).size());
        assertEquals("prefix```json\n{}\n```",VisualVerificationService.jsonContent("prefix```json\n{}\n```"));
    }
    @Test void aValueMovedFromNeighbourPageIsRejectedFromFactsButKeptAsReviewableObservation(){
        mode="moved";Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run"),batch=map(result.get("factBatch"));
        assertEquals(3,list(result.get("observations")).size());assertEquals(2,list(batch.get("facts")).size());assertEquals(2,list(batch.get("rejected")).get(0).get("page"));
        assertEquals("CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE",list(batch.get("rejected")).get(0).get("code"));
    }
    @Test void unrelatedOwnerClaimDoesNotMoveIdentityAcrossPages(){
        mode="unknown-owner";Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");Map<String,Object> fact=list(map(result.get("factBatch")).get("facts")).get(1);
        assertEquals("",fact.get("subject"));assertEquals("UNRESOLVED",map(fact.get("attributes")).get("subjectBinding"));assertEquals("UNKNOWN",map(fact.get("attributes")).get("entityType"));
    }
    @Test void partialWindowKeepsGoodPagesAndSinglePageRecoveryUsesSameStructuredSchema(){
        mode="missing";VisualVerificationService service=client();Map<String,Object> first=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");
        assertEquals("PARTIAL",first.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(2),first.get("failedPages"));assertEquals(2,list(map(first.get("factBatch")).get("facts")).size());
        mode="";Map<String,Object> recovered=service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run");assertEquals("OBSERVED",recovered.get("status"));assertEquals(1,list(map(recovered.get("factBatch")).get("facts")).size());assertEquals(2,calls.get());
    }
    @Test void cacheIsInvalidatedByReparsedContentEvenWhenDocumentBytesAndVersionAreUnchanged(){
        VisualVerificationService service=client();service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");
        assertEquals(true,service.extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->false,"new-run").get("cacheHit"));assertEquals(1,calls.get());
        List<Page> changed=new ArrayList<>(doc.pages());Page p=changed.get(0);changed.set(0,new Page(p.number(),p.text()+"\n补充原文","PAGE","QWEN_PDF_OCR_NEW",""));
        Document reparsed=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),"",changed,cn.bidcheck.compat.Java8Api.listOf());
        service.extractContext(reparsed,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");assertEquals(2,calls.get());
    }
    @Test void structuredFieldsStillRejectInvalidBoxesAndNeverClaimGeometryProof(){
        mode="bbox";Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");assertEquals("PARTIAL",result.get("status"));assertTrue(list(map(result.get("factBatch")).get("facts")).isEmpty());
    }
    @Test void unifiedSinglePageEnvelopeNeedsNoCoordinateMetadataAndRetainsActualSourcePage(){
        mode="envelope-no-coordinates";Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run");
        assertEquals("OBSERVED",result.get("status"));Map<String,Object> batch=map(result.get("factBatch"));assertEquals(1,list(batch.get("facts")).size());
        Map<String,Object> attrs=map(list(batch.get("facts")).get(0).get("attributes"));assertEquals(2,attrs.get("sourcePage"));assertEquals("UNAVAILABLE",attrs.get("localization"));assertNull(attrs.get("sourceBbox"));
        assertEquals(2,list(result.get("pageResults")).get(0).get("page"));assertEquals(6144,payload.get().path("max_tokens").asInt());
        assertTrue(payload.get().path("messages").get(0).path("content").asText().contains("无论单页还是多页，统一返回"));
    }
    @Test void legacySinglePageObjectsRemainCompatibleOnlyWithTheRequestedPage(){
        for(String shape:cn.bidcheck.compat.Java8Api.listOf("no-coordinates", "with-page-no-coordinates", "with-page-wrong-coordinates")){
            mode=shape;Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run");assertEquals("OBSERVED",result.get("status"),shape);
            assertEquals(2,map(list(map(result.get("factBatch")).get("facts")).get(0).get("attributes")).get("sourcePage"));
        }
        mode="wrong-page-no-coordinates";assertEquals("VISUAL_PAGE_MAPPING_INVALID",client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run").get("errorCode"));
    }
    @Test void structuredEnvelopeCannotInventOmitOrDuplicateOriginalPageIdentity(){
        for(String shape:cn.bidcheck.compat.Java8Api.listOf("envelope-wrong-page-no-coordinates", "envelope-missing-page-no-coordinates", "envelope-duplicate-no-coordinates")){
            mode=shape;Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run");assertEquals("VISUAL_PAGE_MAPPING_INVALID",result.get("errorCode"),shape);
            assertTrue(list(map(result.get("factBatch")).get("facts")).isEmpty());
        }
    }
    @Test void anyStructuredBoxStillRequiresExactPixelCoordinates(){
        for(String shape:cn.bidcheck.compat.Java8Api.listOf("envelope-valid-bbox-no-coordinates", "envelope-valid-bbox-wrong-coordinates")){
            mode=shape;Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run");assertEquals("VISUAL_COORDINATE_SPACE_INVALID",result.get("errorCode"),shape);
            assertTrue(list(map(result.get("factBatch")).get("facts")).isEmpty());
        }
        mode="envelope-valid-bbox";Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(2),()->true,"run");assertEquals("OBSERVED",result.get("status"));
        assertEquals("MODEL_PROPOSED_BOUNDS_CHECKED",list(result.get("observations")).get(0).get("localization"));
    }
    @Test void multipleUnlocatedPagesKeepTheirOwnFactsWithoutGeometryMetadata(){
        mode="no-coordinates";Map<String,Object> result=client().extractContext(doc,pdf,cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),()->true,"run");assertEquals("OBSERVED",result.get("status"));
        assertEquals(3,list(map(result.get("factBatch")).get("facts")).size());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("failedPages"));
    }
    @Test void singleLineHtmlWithSeveralRowsCannotBindAnotherRowsOwner(){
        String html="<table><tr><td>LSVAB123456789011</td><td>渝A12341</td></tr><tr><td>LSVAB123456789012</td><td>渝A12342</td></tr></table>";
        Page page=new Page(1,html,"PAGE","PDF_OCR","");Document table=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),"",cn.bidcheck.compat.Java8Api.listOf(page),cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> item=new LinkedHashMap<>();item.put("type","FIELD");item.put("readability","CLEAR");item.put("value","渝A12342");item.put("label","plateNumber");item.put("sourceQuote",html);
        item.put("subject","LSVAB123456789011");item.put("recordKey","LSVAB123456789011");item.put("entityType","VEHICLE");item.put("sourcePage",1);
        item.put("source",cn.bidcheck.compat.Java8Api.mapOf("projectId", doc.projectId(), "documentId", doc.id(), "documentHash", doc.sha256(), "documentVersion", 1, "page", 1));
        Map<String,Object> batch=StructuredContextFacts.batch(table,cn.bidcheck.compat.Java8Api.listOf(1),cn.bidcheck.compat.Java8Api.mapOf("observations", cn.bidcheck.compat.Java8Api.listOf(item)),"model");Map<String,Object> fact=list(batch.get("facts")).get(0);
        assertEquals("",fact.get("subject"));assertEquals(false,map(fact.get("attributes")).get("recordIdentityEstablished"));
    }
    @Test void changingAnInheritedHeaderOutsideThisWindowInvalidatesItsRequestIdentity(){
        Page first=VisualContinuationTest.markdown(1,"拟投入车辆一览表","车牌号","渝A12341");
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(first, new Page(2,"续表\n| 2 | 渝A12342 | LSVAB123456789012 |","PAGE","PDF_OCR",""), new Page(3,"续表\n| 3 | 渝A12343 | LSVAB123456789013 |","PAGE","PDF_OCR",""));
        Document before=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),1,doc.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf());
        List<Page> modified=new ArrayList<>(pages);modified.set(0,VisualContinuationTest.markdown(1,"拟投入车辆一览表","所有人","某公司"));
        Document after=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),1,doc.sha256(),"",modified,cn.bidcheck.compat.Java8Api.listOf());
        VisualVerificationService service=client();Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("task", "STRUCTURED_FIELDS", "schema", VisualVerificationService.FACT_SCHEMA);
        assertNotEquals(service.requestIdentity(before,cn.bidcheck.compat.Java8Api.listOf(2, 3),request),service.requestIdentity(after,cn.bidcheck.compat.Java8Api.listOf(2, 3),request));assertEquals(0,calls.get());
    }
}

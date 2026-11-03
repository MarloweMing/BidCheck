package cn.bidcheck.verification.engine;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import cn.bidcheck.consistency.ConsistencyModelScanner;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class VerificationModelClientTest {
    @Test void nativeProtocolReturnsStructuredObject()throws Exception{withServer(exchange->{byte[] data="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"facts\\\":[]}\"}}]}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();},url->{assertTrue(new VerificationModelClient(url,"secret-test","model",Duration.ofSeconds(2)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()).path("facts").isArray());});}
    @Test void truncatedModelReplyNeverBecomesFacts()throws Exception{withServer(exchange->{byte[] data="{\"output\":{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();},url->{IOException ex=assertThrows(IOException.class,()->new VerificationModelClient(url,"never-print-secret","model",Duration.ofSeconds(2)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertFalse(ex.getMessage().contains("never-print-secret"));});}
    @Test void slowBodyIsBoundedByOverallDeadline()throws Exception{withServer(exchange->{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();},url->{long start=System.nanoTime();assertThrows(IOException.class,()->new VerificationModelClient(url,"key","model",Duration.ofMillis(200)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertTrue((System.nanoTime()-start)/1_000_000<1200);});}
    @Test void oversizedBodyFailsBoundedly()throws Exception{withServer(exchange->{byte[] data=new byte[270_000];Arrays.fill(data,(byte)'x');exchange.sendResponseHeaders(200,data.length);try{exchange.getResponseBody().write(data);}catch(IOException ignored){}exchange.close();},url->assertThrows(IOException.class,()->new VerificationModelClient(url,"key","model",Duration.ofSeconds(2)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf())));}
    @Test void interruptStopsBeforeHttp()throws Exception{Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->new VerificationModelClient("http://127.0.0.1:9","key","model",Duration.ofSeconds(1)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
    @Test void fullTextProtocolIsPresentInActualHttpSystemMessageAndCustomFactRoundTrips() throws Exception {
        ObjectMapper json=new ObjectMapper(); AtomicReference<JsonNode> captured=new AtomicReference<>();
        String quote="本项目业务数据库备份副本数：3份。";
        withServer(exchange->{
            JsonNode body=json.readTree(exchange.getRequestBody());captured.set(body);
            JsonNode input=json.readTree(body.path("input").path("messages").get(1).path("content").asText()).path("input");
            String sourceId=input.path("sources").get(0).path("id").asText();
            Map<String,Object> fact=new LinkedHashMap<>();
            fact.put("kind","FIELD");fact.put("field","custom");fact.put("fieldLabel","备份副本数");fact.put("categoryId","solution");
            fact.put("subject","业务数据库");fact.put("scope","CURRENT_PROJECT");fact.put("rawValue","3份");fact.put("value","3份");
            fact.put("sourceIds",cn.bidcheck.compat.Java8Api.listOf(sourceId));fact.put("attributes",cn.bidcheck.compat.Java8Api.mapOf("quote", quote, "contextQuote", quote, "dimensions", cn.bidcheck.compat.Java8Api.mapOf("object", "业务数据库")));
            String response=json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact)));
            byte[] data=json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", response))))));
            exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        },url->{
            cn.bidcheck.consistency.ConsistencyModelScanner scanner=new ConsistencyModelScanner(new VerificationModelClient(url,"local-test-key","qwen3-max","qwen3.7-plus-2026-05-26",Duration.ofSeconds(3)));
            cn.bidcheck.domain.Domain.Document doc=new Document("test-document","project","BID","投标文件",1,"test-hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,quote,"PAGE","PDFBOX_NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());
            cn.bidcheck.consistency.ConsistencyModelScanner.Result result=scanner.scan(doc);
            assertEquals(1,result.facts().size());assertEquals("custom",result.facts().get(0).get("field"));
            assertEquals("备份副本数",result.facts().get(0).get("fieldLabel"));assertEquals("solution",result.facts().get(0).get("categoryId"));
            assertEquals(cn.bidcheck.compat.Java8Api.mapOf("object", "业务数据库"),result.facts().get(0).get("dimensions"));
        });
        JsonNode messages=captured.get().path("input").path("messages");
        assertEquals("qwen3.7-plus-2026-05-26",captured.get().path("model").asText());
        assertTrue(captured.get().path("parameters").has("enable_thinking"));assertFalse(captured.get().path("parameters").path("enable_thinking").asBoolean());
        assertEquals("system",messages.get(0).path("role").asText());
        String system=messages.get(0).path("content").asText();
        for(String expected:cn.bidcheck.compat.Java8Api.listOf("FULL_TEXT_CONSISTENCY", "仅输出kind=FIELD", "fieldLabel", "categoryId", "attributes.dimensions", "input.requirementHints", "不能作为投标事实"))assertTrue(system.contains(expected),expected);
        for(String forbidden:cn.bidcheck.compat.Java8Api.listOf("COMPILE", "REPAIR", "规则编译协议", "QUALITY subject为ruleId"))assertFalse(system.contains(forbidden),forbidden);
        JsonNode payload=json.readTree(messages.get(1).path("content").asText());
        assertEquals("EXTRACT",payload.path("operation").asText());assertEquals("FULL_TEXT_CONSISTENCY",payload.path("input").path("task").asText());
        assertEquals(18,payload.path("input").path("categories").size());
    }

    @Test void nonExtractionOperationsAreRejectedBeforeHttp()throws Exception {
        AtomicInteger requests=new AtomicInteger();
        withServer(exchange->{requests.incrementAndGet();writeResponse(exchange,500,"{}");},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url,"stub-key","qwen3-max",Duration.ofSeconds(2));
            for(String operation:cn.bidcheck.compat.Java8Api.listOf("COMPILE", "REPAIR", "REPAIR_COMPILED_REQUIREMENT", "")){
                cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request(operation,cn.bidcheck.compat.Java8Api.mapOf()));
                assertEquals("OPERATION_NOT_ALLOWED",failure.code());assertFalse(failure.retryable());assertFalse(failure.splittable());
            }
        });assertEquals(0,requests.get());
    }

    @Test void complexOrUnknownExtractionStaysOnPrimaryModel()throws Exception {
        ObjectMapper json=new ObjectMapper();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{requests.add(json.readTree(exchange.getRequestBody()));writeFacts(exchange,json);},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url,"stub-key","qwen3-max","qwen3.7-plus-2026-05-26",Duration.ofSeconds(2));
            for(String predicate:cn.bidcheck.compat.Java8Api.listOf("QUALITY", "CONTRACT", "RELATION", "MATERIAL"))
                client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("rules", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("predicate", predicate)), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "FULL_TEXT_CONSISTENCY"))));
            client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("task", "FULL_TEXT_CONSISTENCY"));
            Map<String,Object> mixed=new LinkedHashMap<>(fieldInput());mixed.put("rules",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("predicate", "QUALITY")));client.request("EXTRACT",mixed);
            Map<String,Object> unknown=new LinkedHashMap<>(fieldInput());unknown.put("task","UNKNOWN_EXTRACTION");client.request("EXTRACT",unknown);
        });
        assertEquals(7,requests.size());
        for(JsonNode request:requests){assertEquals("qwen3-max",request.path("model").asText());assertTrue(request.path("parameters").has("enable_thinking"));assertFalse(request.path("parameters").path("enable_thinking").asBoolean());}
    }

    @Test void extractionModelDefaultsToPrimaryAndConfigurationIdentityBindsBothModels()throws Exception {
        cn.bidcheck.verification.engine.VerificationModelClient original=new VerificationModelClient("https://example.invalid/generation","stub-key","qwen3-max",Duration.ofSeconds(2));
        cn.bidcheck.verification.engine.VerificationModelClient explicit=new VerificationModelClient("https://example.invalid/generation","stub-key","qwen3-max","qwen3-max",Duration.ofSeconds(2));
        cn.bidcheck.verification.engine.VerificationModelClient empty=new VerificationModelClient("https://example.invalid/generation","stub-key","qwen3-max","  ",Duration.ofSeconds(2));
        cn.bidcheck.verification.engine.VerificationModelClient routed=new VerificationModelClient("https://example.invalid/generation","stub-key","qwen3-max","qwen3.7-plus-2026-05-26",Duration.ofSeconds(2));
        assertEquals(original.identity(),explicit.identity());assertEquals(original.identity(),empty.identity());assertNotEquals(original.identity(),routed.identity());
        assertEquals("qwen3-max",routed.primaryModelName());assertEquals("qwen3.7-plus-2026-05-26",routed.extractionModelName());
        assertEquals("verification-model-routing-1-consistency-fields-only",routed.routingPolicy());assertFalse(routed.identity().contains("stub-key"));
        ObjectMapper json=new ObjectMapper();AtomicReference<String> selected=new AtomicReference<>();
        withServer(exchange->{selected.set(json.readTree(exchange.getRequestBody()).path("model").asText());writeFacts(exchange,json);},url->{
            new VerificationModelClient(url,"stub-key","qwen3-max",Duration.ofSeconds(2)).request("EXTRACT",fieldInput());
        });assertEquals("qwen3-max",selected.get());
    }

    @Test void dedicatedExtractionFailureNeverMakesAnUnbudgetedPrimaryModelRetry()throws Exception {
        ObjectMapper json=new ObjectMapper();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{requests.add(json.readTree(exchange.getRequestBody()));exchange.sendResponseHeaders(503,2);exchange.getResponseBody().write("{}".getBytes());exchange.close();},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url,"never-print-key","qwen3-max","qwen3.7-plus-2026-05-26",Duration.ofSeconds(2));
            IOException failure=assertThrows(IOException.class,()->client.request("EXTRACT",fieldInput()));assertFalse(failure.getMessage().contains("never-print-key"));
        });assertEquals(1,requests.size());assertEquals("qwen3.7-plus-2026-05-26",requests.get(0).path("model").asText());
    }

    @Test void compatibleExtractionUsesIndependentEndpointAndPrimaryKeepsNativeProtocol()throws Exception {
        ObjectMapper json=new ObjectMapper();List<String> paths=new CopyOnWriteArrayList<>();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{
            paths.add(exchange.getRequestURI().getPath());requests.add(json.readTree(exchange.getRequestBody()));
            if(exchange.getRequestURI().getPath().endsWith("/chat/completions"))writeCompatibleFacts(exchange,json);else writeFacts(exchange,json);
        },url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"native-generation","stub-key","qwen3-max","qwen3.7-plus-2026-05-26",url+"compatible-mode/v1/chat/completions",Duration.ofSeconds(2));
            assertTrue(client.request("EXTRACT",fieldInput()).path("facts").isArray());
            client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("rules", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("predicate", "QUALITY"))));
            client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("task", "FULL_TEXT_CONSISTENCY"));
        });
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("/compatible-mode/v1/chat/completions", "/native-generation", "/native-generation"),paths);
        JsonNode extraction=requests.get(0);assertEquals("qwen3.7-plus-2026-05-26",extraction.path("model").asText());
        assertFalse(extraction.has("input"));assertFalse(extraction.has("parameters"));assertEquals(2,extraction.path("messages").size());
        assertEquals("system",extraction.path("messages").path(0).path("role").asText());
        assertTrue(extraction.has("enable_thinking"));assertFalse(extraction.path("enable_thinking").asBoolean());
        assertTrue(extraction.has("preserve_thinking"));assertFalse(extraction.path("preserve_thinking").asBoolean());
        assertEquals(0,extraction.path("temperature").asInt());assertEquals(8192,extraction.path("max_tokens").asInt());
        assertEquals("json_object",extraction.path("response_format").path("type").asText());
        assertEquals("FULL_TEXT_CONSISTENCY",json.readTree(extraction.path("messages").path(1).path("content").asText()).path("input").path("task").asText());
        for(JsonNode request:requests.subList(1,requests.size())){
            assertEquals("qwen3-max",request.path("model").asText());assertTrue(request.path("input").path("messages").isArray());
            assertFalse(request.has("messages"));assertTrue(request.path("parameters").has("enable_thinking"));assertFalse(request.path("parameters").path("enable_thinking").asBoolean());
            assertEquals("json_object",request.path("parameters").path("response_format").path("type").asText());
        }
    }

    @Test void compatibleExtractionRejectsTruncationWrongEnvelopeAndInvalidJsonWithoutRetry()throws Exception {
        ObjectMapper json=new ObjectMapper();AtomicReference<String> reply=new AtomicReference<>();List<String> paths=new CopyOnWriteArrayList<>();
        withServer(exchange->{paths.add(exchange.getRequestURI().getPath());writeResponse(exchange,200,reply.get());},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"native-generation","never-print-key","qwen3-max","plus",url+"chat/completions",Duration.ofSeconds(2));
            List<String> replies=cn.bidcheck.compat.Java8Api.listOf(json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", "{}")))))), compatibleReply(json,"length","{\"facts\":[]}"), compatibleReply(json,"stop",""), compatibleReply(json,"stop",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("type", "text", "text", "{}"))), compatibleReply(json,"stop","[]"), compatibleReply(json,"stop","{\"facts\":[],\"facts\":[]}"), compatibleReply(json,"stop","never-print-provider-content"), "{invalid-never-print-provider-content}", json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(), "error", cn.bidcheck.compat.Java8Api.mapOf("message", "never-print-provider-content"))));
            for(String response:replies){reply.set(response);int before=paths.size();IOException failure=assertThrows(IOException.class,()->client.request("EXTRACT",fieldInput()));
                assertEquals(before+1,paths.size());assertFalse(failure.getMessage().contains("never-print"));assertNull(failure.getCause());}
        });assertTrue(paths.stream().allMatch("/chat/completions"::equals));
    }

    @Test void compatiblePrimaryHandlesOrdinaryExtractionWithoutChangingModelRoute()throws Exception {
        ObjectMapper json=new ObjectMapper();List<String> paths=new CopyOnWriteArrayList<>();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{paths.add(exchange.getRequestURI().getPath());requests.add(json.readTree(exchange.getRequestBody()));writeCompatibleFacts(exchange,json);},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"primary/chat/completions","stub-key","qwen3.8-max-0902","qwen3.7-plus-2026-05-26",url+"extraction/chat/completions",Duration.ofSeconds(2));
            for(String predicate:cn.bidcheck.compat.Java8Api.listOf("QUALITY", "CONTRACT", "RELATION", "MATERIAL"))client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("rules", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("predicate", predicate))));
            client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("task", "FULL_TEXT_CONSISTENCY"));
            client.request("EXTRACT",fieldInput());
        });
        assertEquals(6,requests.size());
        for(int i=0;i<requests.size();i++){
            JsonNode request=requests.get(i);boolean simple=i==5;
            assertEquals(simple?"/extraction/chat/completions":"/primary/chat/completions",paths.get(i));
            assertEquals(simple?"qwen3.7-plus-2026-05-26":"qwen3.8-max-0902",request.path("model").asText());
            assertFalse(request.has("input"));assertFalse(request.has("parameters"));assertEquals(2,request.path("messages").size());
            assertTrue(request.has("enable_thinking"));assertFalse(request.path("enable_thinking").asBoolean());
            assertTrue(request.has("preserve_thinking"));assertFalse(request.path("preserve_thinking").asBoolean());
            assertEquals(0,request.path("temperature").asInt());assertEquals(8192,request.path("max_tokens").asInt());
            assertEquals("json_object",request.path("response_format").path("type").asText());
        }
        assertTrue(requests.stream().allMatch(request->{try{return "EXTRACT".equals(json.readTree(request.path("messages").path(1).path("content").asText()).path("operation").asText());}catch(IOException invalid){return false;}}));
    }

    @Test void compatiblePrimaryAndNativeExtractionSelectEachConfiguredProtocol()throws Exception {
        ObjectMapper json=new ObjectMapper();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{requests.add(json.readTree(exchange.getRequestBody()));
            if(exchange.getRequestURI().getPath().endsWith("/chat/completions"))writeCompatibleFacts(exchange,json);else writeFacts(exchange,json);
        },url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"chat/completions","key","max","plus",url+"native-generation",Duration.ofSeconds(2));
            client.request("EXTRACT",fieldInput());
        });assertEquals(1,requests.size());assertEquals("plus",requests.get(0).path("model").asText());assertTrue(requests.get(0).path("input").path("messages").isArray());
        assertTrue(requests.get(0).path("parameters").has("enable_thinking"));assertFalse(requests.get(0).path("parameters").path("enable_thinking").asBoolean());
        assertEquals("json_object",requests.get(0).path("parameters").path("response_format").path("type").asText());
    }

    @Test void extractionBoundaryRejectsModelAuthoredDecisionsAndUnknownFactFields()throws Exception {
        ObjectMapper json=new ObjectMapper();AtomicReference<String> content=new AtomicReference<>();
        withServer(exchange->writeResponse(exchange,200,compatibleReply(json,"stop",content.get())),url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2));
            for(String invalid:cn.bidcheck.compat.Java8Api.listOf("{\"status\":\"PASS\",\"facts\":[]}", "{\"facts\":[{\"kind\":\"FIELD\",\"field\":\"name\",\"sourceIds\":[\"S1\"],\"conclusion\":\"PASS\"}]}", "{\"facts\":[{\"kind\":\"FIELD\",\"field\":\"name\",\"sourceIds\":\"S1\"}]}")){
                content.set(invalid);cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request("EXTRACT",fieldInput()));
                assertEquals("OUTPUT_SCHEMA_INVALID",failure.code());assertFalse(failure.splittable());assertFalse(cn.bidcheck.compat.Java8Api.isBlank(failure.schemaIssue()));
            }
        });
    }

    @Test void extractionBoundaryAcceptsTheCompleteOrdinaryFactSchema()throws Exception {
        ObjectMapper json=new ObjectMapper();String content=json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(strictFact())));
        withServer(exchange->writeResponse(exchange,200,compatibleReply(json,"stop",content)),url->{
            JsonNode result=new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf());
            assertEquals("NUMBER",result.path("facts").path(0).path("kind").asText());
        });
    }

    @Test void fullTextFieldAttributesAndDimensionKeysAreBoundToTheRequestSchema()throws Exception {
        ObjectMapper json=new ObjectMapper();AtomicReference<String> content=new AtomicReference<>();
        withServer(exchange->writeResponse(exchange,200,compatibleReply(json,"stop",content.get())),url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2));
            ObjectNode valid=json.valueToTree(dedicatedFact());content.set(json.writeValueAsString(envelope(json,valid)));
            assertEquals("projectName",client.request("EXTRACT",fieldInput()).path("facts").path(0).path("field").asText());
            List<ObjectNode> invalid=new ArrayList<>();
            ObjectNode unknownAttribute=valid.deepCopy();unknownAttribute.withObject("attributes").put("riskLevel","HIGH");invalid.add(unknownAttribute);
            ObjectNode unknownDimension=valid.deepCopy();unknownDimension.withObject("attributes").withObject("dimensions").put("version","v1");invalid.add(unknownDimension);
            ObjectNode wrongDimensionType=valid.deepCopy();wrongDimensionType.withObject("attributes").withObject("dimensions").put("object",3);invalid.add(wrongDimensionType);
            ObjectNode wrongField=valid.deepCopy();wrongField.put("field","inventedField");invalid.add(wrongField);
            ObjectNode wrongCategory=valid.deepCopy();wrongCategory.put("categoryId","inventedCategory");invalid.add(wrongCategory);
            ObjectNode twoSources=valid.deepCopy();twoSources.putArray("sourceIds").add("S1").add("S2");invalid.add(twoSources);
            ObjectNode computedValue=valid.deepCopy();computedValue.put("value","标准化后的项目名称");invalid.add(computedValue);
            for(ObjectNode fact:invalid){content.set(json.writeValueAsString(envelope(json,fact)));
                cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request("EXTRACT",fieldInput()));
                assertEquals("OUTPUT_SCHEMA_INVALID",failure.code(),fact.toString());}
        });
    }

    @Test void extractionBoundaryRejectsMalformedFactsNestedVerdictsAndResourceAbuse()throws Exception {
        ObjectMapper json=new ObjectMapper();AtomicReference<String> content=new AtomicReference<>();
        List<JsonNode> invalid=new ArrayList<>();
        for(String missing:cn.bidcheck.compat.Java8Api.listOf("kind", "subject", "field", "rawValue", "value", "scope", "sourceIds", "attributes")){
            ObjectNode fact=json.valueToTree(strictFact());fact.remove(missing);invalid.add(envelope(json,fact));
        }
        ObjectNode wrongText=json.valueToTree(strictFact());wrongText.put("subject",7);invalid.add(envelope(json,wrongText));
        ObjectNode wrongKind=json.valueToTree(strictFact());wrongKind.put("kind","DECISION");invalid.add(envelope(json,wrongKind));
        ObjectNode modelQuality=json.valueToTree(strictFact());modelQuality.put("kind","QUALITY");modelQuality.withObject("attributes").put("score",95);invalid.add(envelope(json,modelQuality));
        ObjectNode wrongScope=json.valueToTree(strictFact());wrongScope.put("scope","ALL_PROJECTS");invalid.add(envelope(json,wrongScope));
        ObjectNode blankRaw=json.valueToTree(strictFact());blankRaw.put("rawValue","  ");invalid.add(envelope(json,blankRaw));
        ObjectNode emptySources=json.valueToTree(strictFact());emptySources.putArray("sourceIds");invalid.add(envelope(json,emptySources));
        ObjectNode duplicateSources=json.valueToTree(strictFact());duplicateSources.putArray("sourceIds").add("S1").add(" S1 ");invalid.add(envelope(json,duplicateSources));
        ObjectNode manySources=json.valueToTree(strictFact());ArrayNode ids=manySources.putArray("sourceIds");for(int i=0;i<13;i++)ids.add("S"+i);invalid.add(envelope(json,manySources));
        ObjectNode nonTextSource=json.valueToTree(strictFact());nonTextSource.putArray("sourceIds").add(1);invalid.add(envelope(json,nonTextSource));
        ObjectNode attributesArray=json.valueToTree(strictFact());attributesArray.putArray("attributes");invalid.add(envelope(json,attributesArray));
        ObjectNode unknownAttribute=json.valueToTree(strictFact());unknownAttribute.withObject("attributes").put("riskLevel","HIGH");invalid.add(envelope(json,unknownAttribute));
        ObjectNode wrongAttributeType=json.valueToTree(strictFact());wrongAttributeType.withObject("attributes").put("currency",7);invalid.add(envelope(json,wrongAttributeType));
        ObjectNode countAsText=json.valueToTree(strictFact());countAsText.withObject("attributes").put("setSize","3");invalid.add(envelope(json,countAsText));
        ObjectNode monthsAsText=json.valueToTree(strictFact());monthsAsText.put("kind","MONTHS");monthsAsText.withObject("attributes").removeAll();monthsAsText.withObject("attributes").put("months","2026-01");invalid.add(envelope(json,monthsAsText));
        ObjectNode nestedDecision=json.valueToTree(strictFact());nestedDecision.withObject("attributes").withObject("review").put("Business_Decision","PASS");invalid.add(envelope(json,nestedDecision));
        ObjectNode commonVariant=json.valueToTree(strictFact());commonVariant.withObject("attributes").withObject("audit").put("compliance-conclusion","符合");invalid.add(envelope(json,commonVariant));
        ObjectNode statusVerdict=json.valueToTree(strictFact());statusVerdict.withObject("attributes").put("review_status","FAIL");invalid.add(envelope(json,statusVerdict));
        ObjectNode longString=json.valueToTree(strictFact());longString.withObject("attributes").put("quote",cn.bidcheck.compat.Java8Api.repeat("x", 32_769));invalid.add(envelope(json,longString));
        ObjectNode largeCollection=json.valueToTree(strictFact());ArrayNode values=largeCollection.withObject("attributes").putArray("values");for(int i=0;i<513;i++)values.add(i);invalid.add(envelope(json,largeCollection));
        ObjectNode deep=json.valueToTree(strictFact()),cursor=deep.withObject("attributes");for(int i=0;i<20;i++)cursor=cursor.withObject("level"+i);invalid.add(envelope(json,deep));
        ObjectNode tooManyFacts=json.createObjectNode();ArrayNode facts=tooManyFacts.putArray("facts");for(int i=0;i<121;i++)facts.add(json.valueToTree(strictFact()));invalid.add(tooManyFacts);
        withServer(exchange->writeResponse(exchange,200,compatibleReply(json,"stop",content.get())),url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2));
            for(JsonNode payload:invalid){
                content.set(json.writeValueAsString(payload));
                cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));
                assertEquals("OUTPUT_SCHEMA_INVALID",failure.code(),payload.toString());assertFalse(failure.splittable());
            }
        });
    }

    @Test void schemaMismatchGetsOneGroundedFormatRetryInsteadOfSplittingEverySource()throws Exception {
        ObjectMapper json=new ObjectMapper();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));ObjectNode fact=json.valueToTree(strictFact());
            if(requests.size()==1)fact.put("value",80); // A valid JSON number is not this schema's original-text string.
            writeResponse(exchange,200,compatibleReply(json,"stop",json.writeValueAsString(envelope(json,fact))));
        },url->{
            cn.bidcheck.verification.engine.RunSession session=new RunSession(new VerificationModelClient(url+"chat/completions","private-key","model",Duration.ofSeconds(2)),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"sensitive-project-name");
            java.util.List<java.util.Map<java.lang.String, java.lang.String>> sources=cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "报价80元"), cn.bidcheck.compat.Java8Api.mapOf("id", "S2", "quote", "另页信息"));
            JsonNode response=session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("sources", sources));assertEquals("80",response.path("facts").path(0).path("value").textValue());
            assertEquals(2,session.modelCalls);java.util.List<?> failures=(List<?>)session.diagnostics().get("failures");assertEquals(1,failures.size());java.util.Map<?, ?> failure=(Map<?,?>)failures.get(0);
            assertEquals("STRING_REQUIRED",failure.get("schemaIssue"));assertEquals("/facts/0/value",failure.get("schemaPath"));assertEquals(false,failure.get("splitPlanned"));assertEquals(true,failure.get("retryPlanned"));assertEquals(true,failure.get("recoveredByRetry"));
            assertEquals(cn.bidcheck.compat.Java8Api.mapOf("OUTPUT_SCHEMA_INVALID", 1L),session.diagnostics().get("failuresByCode"));
            assertEquals(cn.bidcheck.compat.Java8Api.mapOf("STRING_REQUIRED", 1L),session.diagnostics().get("failuresBySchemaIssue"));
        });
        assertEquals(2,requests.size());assertEquals(requests.get(0).path("messages").path(1),requests.get(1).path("messages").path(1),"Retry must retain identical sources/rules");
        String initial=requests.get(0).path("messages").path(0).path("content").asText(),retry=requests.get(1).path("messages").path(0).path("content").asText();
        assertTrue(initial.contains("NUMBER的value也必须是原文数字字符串"));assertTrue(initial.contains("attributes必须为{}"));assertTrue(initial.contains("\"additionalProperties\":false"));
        assertTrue(retry.contains("STRING_REQUIRED"));assertTrue(retry.contains("/facts/0/value"));assertFalse(retry.contains("private-key"));assertFalse(retry.contains("sensitive-project-name"));
    }

    @Test void repeatedSchemaMismatchStopsAfterOneFeedbackRetryAndDoesNotCreateReadCompletion()throws Exception {
        ObjectMapper json=new ObjectMapper();AtomicInteger calls=new AtomicInteger();
        withServer(exchange->{calls.incrementAndGet();ObjectNode fact=json.valueToTree(strictFact());fact.remove("attributes");writeResponse(exchange,200,compatibleReply(json,"stop",json.writeValueAsString(envelope(json,fact))));},url->{
            cn.bidcheck.verification.engine.RunSession session=new RunSession(new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2)),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"");
            cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1"), cn.bidcheck.compat.Java8Api.mapOf("id", "S2")))));
            assertEquals("REQUIRED_FIELD_MISSING",failure.schemaIssue());assertEquals("/facts/0/attributes",failure.schemaPath());assertFalse(failure.splittable());
            assertEquals(2,calls.get());assertTrue(session.steps.stream().noneMatch(step->"COMPLETED".equals(step.get("status"))));
        });
    }

    @Test void unknownProviderKeyNeverLeaksThroughDiagnosticPathOrTriggersFeedbackForVerdicts()throws Exception {
        ObjectMapper json=new ObjectMapper();AtomicInteger calls=new AtomicInteger();String secret="private-document-name-and-key";
        withServer(exchange->{calls.incrementAndGet();ObjectNode fact=json.valueToTree(strictFact());fact.withObject("attributes").put(secret,"private-value");writeResponse(exchange,200,compatibleReply(json,"stop",json.writeValueAsString(envelope(json,fact))));},url->{
            cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2)).request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));
            assertEquals("UNKNOWN_FIELD",failure.schemaIssue());assertEquals("/facts/0/attributes/@unknown",failure.schemaPath());
            assertFalse(json.valueToTree(RunSession.failure(failure)).toString().contains(secret));assertFalse(failure.toString().contains("private-value"));
        });
        withServer(exchange->{calls.incrementAndGet();writeResponse(exchange,200,compatibleReply(json,"stop","{\"facts\":[],\"decision\":\"PASS\"}"));},url->{
            int before=calls.get();cn.bidcheck.verification.engine.RunSession session=new RunSession(new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2)),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"");
            cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));
            assertEquals("DECISION_CONTENT_FORBIDDEN",failure.schemaIssue());assertFalse(failure.retryable());assertEquals(before+1,calls.get());
        });
    }

    @Test void generatedSchemaUsesExactAttributeAllowListsAndExplicitEmptyObjects(){
        ObjectMapper json=new ObjectMapper();JsonNode schema=json.valueToTree(VerificationModelClient.responseSchema(false,cn.bidcheck.compat.Java8Api.mapOf()));
        JsonNode fact=schema.path("properties").path("facts").path("items");assertFalse(fact.path("additionalProperties").asBoolean());
        assertEquals("string",fact.path("properties").path("value").path("type").asText());assertTrue(fact.path("required").toString().contains("attributes"));
        for(JsonNode variant:fact.path("allOf"))if("MATERIAL".equals(variant.path("if").path("properties").path("kind").path("const").asText())){
            JsonNode attrs=variant.path("then").path("properties").path("attributes");assertEquals("object",attrs.path("type").asText());assertEquals(0,attrs.path("properties").size());assertFalse(attrs.path("additionalProperties").asBoolean());
        }
    }

    @Test void operationalLogCorrelatesRealRunCheckpointAndAttemptWithoutDocumentPayload()throws Exception {
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VerificationModelClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();logger.addAppender(capture);
        ObjectMapper json=new ObjectMapper();String runId="75ef8785-5aba-46ff-8d7c-612fbb41f377",checkpointId="d4d64e8d-a80d-3677-ae2a-3f6eb6124fac";
        try{
            withServer(exchange->writeResponse(exchange,200,compatibleReply(json,"stop","{\"facts\":[]}")),url->{
                cn.bidcheck.verification.engine.RunSession session=new RunSession(new VerificationModelClient(url+"chat/completions","never-log-key","model",Duration.ofSeconds(2)),cn.bidcheck.compat.Java8Api.mapOf("sourceRunId", runId, "checkpointId", checkpointId),"never-log-project-name");
                session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "never-log-document-content"))));
                String log=capture.list.get(capture.list.size()-1).getFormattedMessage();
                assertTrue(log.contains("sourceRunId="+runId));assertTrue(log.contains("checkpointId="+checkpointId));assertTrue(log.contains("requestId="+session.activeAttempt));
                assertTrue(log.contains("stepId="+session.steps.get(0).get("id")));assertTrue(log.contains("systemChars="));assertTrue(log.contains("requestChars="));
                for(String forbidden:cn.bidcheck.compat.Java8Api.listOf("never-log-key", "never-log-project-name", "never-log-document-content"))assertFalse(log.contains(forbidden));
                assertNull(org.slf4j.MDC.get("verification.requestId"));
            });
        }finally{logger.detachAppender(capture);capture.stop();}
    }

    private static Map<String,Object> strictFact(){return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("kind", "NUMBER", "subject", "", "field", "amount", "rawValue", "80", "value", "80", "unit", "元", "basis", "投标报价", "scope", "CURRENT_PROJECT", "sourceIds", cn.bidcheck.compat.Java8Api.listOf("S1"), "attributes", cn.bidcheck.compat.Java8Api.mapOf("currency", "人民币")));
    }
    private static Map<String,Object> dedicatedFact(){return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("kind", "FIELD", "subject", "本项目", "field", "projectName", "fieldLabel", "项目名称", "categoryId", "project", "rawValue", "示例项目", "value", "示例项目", "scope", "CURRENT_PROJECT", "sourceIds", cn.bidcheck.compat.Java8Api.listOf("S1"), "attributes", cn.bidcheck.compat.Java8Api.mapOf("quote", "本项目名称：示例项目", "contextQuote", "本项目名称：示例项目", "lot", "", "basis", "", "time", "", "dimensions", cn.bidcheck.compat.Java8Api.mapOf("object", "项目名称")))) ;
    }
    private static ObjectNode envelope(ObjectMapper json,JsonNode fact){ObjectNode result=json.createObjectNode();result.putArray("facts").add(fact);return result;}

    @Test void missingExtractionUrlCanReuseCompatiblePrimaryAndIdentityBindsPrimaryProtocol()throws Exception {
        String nativeUrl="https://example.invalid/generation",compatibleUrl="https://example.invalid/chat/completions";
        cn.bidcheck.verification.engine.VerificationModelClient routed=new VerificationModelClient(compatibleUrl,"key","max","plus",Duration.ofSeconds(2));
        assertEquals(routed.identity(),new VerificationModelClient(compatibleUrl,"key","max","plus",compatibleUrl,Duration.ofSeconds(2)).identity());
        assertNotEquals(routed.identity(),new VerificationModelClient(nativeUrl,"key","max","plus",compatibleUrl,Duration.ofSeconds(2)).identity());
        ObjectMapper json=new ObjectMapper();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{requests.add(json.readTree(exchange.getRequestBody()));writeCompatibleFacts(exchange,json);},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"chat/completions","key","max","plus",Duration.ofSeconds(2));
            client.request("EXTRACT",fieldInput());
        });assertEquals(1,requests.size());assertEquals("plus",requests.get(0).path("model").asText());
        assertTrue(requests.stream().allMatch(request->request.path("messages").isArray()&&!request.has("input")));
    }

    @Test void compatiblePrimaryNeverReceivesACompilationRequest()throws Exception {
        ObjectMapper json=new ObjectMapper();List<String> paths=new CopyOnWriteArrayList<>();
        withServer(exchange->{paths.add(exchange.getRequestURI().getPath());writeFacts(exchange,json);},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"primary/chat/completions","key","max","plus",url+"extraction/chat/completions",Duration.ofSeconds(2));
            cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request("COMPILE",cn.bidcheck.compat.Java8Api.mapOf("requirement", "示例要求")));
            assertEquals("OPERATION_NOT_ALLOWED",failure.code());
        });assertTrue(paths.isEmpty());
    }

    @Test void compatibleFailureDoesNotRetryOnEitherEndpoint()throws Exception {
        List<String> paths=new CopyOnWriteArrayList<>();
        withServer(exchange->{paths.add(exchange.getRequestURI().getPath());writeResponse(exchange,503,"{\"error\":\"never-print-body\"}");},url->{
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"native-generation","never-print-key","max","plus",url+"chat/completions",Duration.ofSeconds(2));
            IOException failure=assertThrows(IOException.class,()->client.request("EXTRACT",fieldInput()));assertFalse(failure.getMessage().contains("never-print"));
        });assertEquals(cn.bidcheck.compat.Java8Api.listOf("/chat/completions"),paths);
    }

    @Test void compatibleRedirectNeverForwardsCredentialToAnotherOrigin()throws Exception {
        List<String> destinationRequests=new CopyOnWriteArrayList<>();List<String> sourceRequests=new CopyOnWriteArrayList<>();
        withServer(exchange->{destinationRequests.add(exchange.getRequestURI().getPath());writeResponse(exchange,200,"{}");},destination->{
            withServer(exchange->{sourceRequests.add(exchange.getRequestURI().getPath());exchange.getResponseHeaders().add("Location",destination+"chat/completions");exchange.sendResponseHeaders(307,-1);exchange.close();},url->{
                cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"native-generation","never-forward-key","max","plus",url+"chat/completions",Duration.ofSeconds(2));
                assertThrows(IOException.class,()->client.request("EXTRACT",fieldInput()));
            });
        });assertEquals(cn.bidcheck.compat.Java8Api.listOf("/chat/completions"),sourceRequests);assertTrue(destinationRequests.isEmpty());
    }

    @Test void missingExtractionUrlFallsBackToNativeAndIdentityBindsBothEndpoints()throws Exception {
        String primary="https://example.invalid/generation",compatible="https://example.invalid/compatible-mode/v1/chat/completions";
        cn.bidcheck.verification.engine.VerificationModelClient original=new VerificationModelClient(primary,"key","max","plus",Duration.ofSeconds(2));
        cn.bidcheck.verification.engine.VerificationModelClient blank=new VerificationModelClient(primary,"key","max","plus","  ",Duration.ofSeconds(2));
        cn.bidcheck.verification.engine.VerificationModelClient explicit=new VerificationModelClient(primary,"key","max","plus",primary,Duration.ofSeconds(2));
        cn.bidcheck.verification.engine.VerificationModelClient routed=new VerificationModelClient(primary,"key","max","plus",compatible,Duration.ofSeconds(2));
        assertEquals(original.identity(),blank.identity());assertEquals(original.identity(),explicit.identity());assertNotEquals(original.identity(),routed.identity());
        assertNotEquals(routed.identity(),new VerificationModelClient(primary+"2","key","max","plus",compatible,Duration.ofSeconds(2)).identity());
        assertNotEquals(routed.identity(),new VerificationModelClient(primary,"key","max","plus",compatible+"/other",Duration.ofSeconds(2)).identity());
        ObjectMapper json=new ObjectMapper();List<JsonNode> requests=new CopyOnWriteArrayList<>();
        withServer(exchange->{requests.add(json.readTree(exchange.getRequestBody()));writeFacts(exchange,json);},url->{
            new VerificationModelClient(url,"key","max","plus",null,Duration.ofSeconds(2)).request("EXTRACT",fieldInput());
            new VerificationModelClient(url,"key","max","plus"," ",Duration.ofSeconds(2)).request("EXTRACT",fieldInput());
        });assertEquals(2,requests.size());for(JsonNode request:requests){assertEquals("plus",request.path("model").asText());assertTrue(request.path("input").path("messages").isArray());}
    }

    @Test void invalidExtractionUrlCannotSilentlyUseThePrimaryEndpoint()throws Exception {
        ObjectMapper json=new ObjectMapper();List<String> paths=new CopyOnWriteArrayList<>();
        withServer(exchange->{paths.add(exchange.getRequestURI().getPath());writeFacts(exchange,json);},url->{
            for(String invalid:cn.bidcheck.compat.Java8Api.listOf("http://example.invalid/chat/completions", "https://user@example.invalid/chat/completions", "https://example.invalid/chat/completions#fragment", "not a URL")){
                cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url,"key","max","plus",invalid,Duration.ofSeconds(2));
                assertTrue(client.enabled());assertThrows(IOException.class,()->client.request("EXTRACT",fieldInput()));assertTrue(paths.isEmpty());
            }
            cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->new VerificationModelClient(url,"key","max","plus","not a URL",Duration.ofSeconds(2)).request("COMPILE",cn.bidcheck.compat.Java8Api.mapOf()));
            assertEquals("OPERATION_NOT_ALLOWED",failure.code());
        });assertTrue(paths.isEmpty());
    }

    private static Map<String,Object> fieldInput(){return cn.bidcheck.compat.Java8Api.mapOf("task", "FULL_TEXT_CONSISTENCY", "instructions", "Only extract grounded fields", "fields", cn.bidcheck.compat.Java8Api.listOf("projectName"), "catalog", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "projectName")), "categories", cn.bidcheck.compat.Java8Api.listOf("project"), "dimensionKeys", cn.bidcheck.compat.Java8Api.listOf("object"), "requirementHints", cn.bidcheck.compat.Java8Api.listOf(), "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "S1", "quote", "本项目名称：示例项目")));}
    private static void writeFacts(HttpExchange exchange,ObjectMapper json)throws IOException {
        byte[] bytes=json.writeValueAsBytes(cn.bidcheck.compat.Java8Api.mapOf("output", cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", "stop", "message", cn.bidcheck.compat.Java8Api.mapOf("content", "{\"facts\":[]}"))))));
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @Test void typedHttpFailuresExposeStatusWithoutProviderBodyOrHiddenRetry() throws Exception {
        for(int status:cn.bidcheck.compat.Java8Api.listOf(401, 413, 429, 503)) {
            AtomicInteger calls=new AtomicInteger();
            withServer(exchange -> { calls.incrementAndGet(); writeResponse(exchange,status,"provider-secret-token-and-url"); },url -> {
                cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url,"private-key","model",Duration.ofSeconds(2));
                cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,() -> client.request("EXTRACT",fieldInput()));
                assertEquals("HTTP_"+status,failure.code()); assertEquals(status,failure.httpStatus());
                assertEquals(status==429||status==503,failure.retryable()); assertEquals(status==413,failure.splittable());
                assertNull(failure.getCause()); assertFalse(failure.toString().contains("provider-secret")); assertFalse(failure.toString().contains("private-key"));
            });
            assertEquals(1,calls.get());
        }
    }

    @Test void fullTextHasIndependentDeadlineWhileOrdinaryFactExtractionKeepsItsOwnLimit() throws Exception {
        ObjectMapper json=new ObjectMapper();
        withServer(exchange -> {
            try { Thread.sleep(250); writeFacts(exchange,json); }
            catch(InterruptedException stopped) { Thread.currentThread().interrupt(); exchange.close(); }
            catch(IOException cancelled) { exchange.close(); }
        },url -> {
            cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url,"key","primary","extract",url,Duration.ofMillis(100),Duration.ofSeconds(2));
            cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,() -> client.request("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));
            assertEquals("REQUEST_TIMEOUT",failure.code()); assertTrue(failure.retryable()); assertTrue(failure.splittable());
            assertTrue(client.request("EXTRACT",fieldInput()).path("facts").isArray());
            cn.bidcheck.verification.engine.VerificationModelClient different=new VerificationModelClient(url,"key","primary","extract",url,Duration.ofMillis(100),Duration.ofSeconds(1));
            assertEquals(client.identity(),different.identity(),"A deadline change does not invalidate successfully extracted fields");
        });
    }

    @Test void truncatedAndInvalidJsonResponsesCarryDistinctRecoveryReasons() throws Exception {
        ObjectMapper json=new ObjectMapper();
        for(String reason:cn.bidcheck.compat.Java8Api.listOf("length", "invalid-json")) {
            String response=reason.equals("length")?compatibleReply(json,"length","{}"):compatibleReply(json,"stop","{not-json");
            withServer(exchange -> writeResponse(exchange,200,response),url -> {
                cn.bidcheck.verification.engine.VerificationModelClient client=new VerificationModelClient(url+"chat/completions","key","model",Duration.ofSeconds(2));
                cn.bidcheck.verification.engine.VerificationModelClient.ModelRequestException failure=assertThrows(VerificationModelClient.ModelRequestException.class,() -> client.request("EXTRACT",fieldInput()));
                assertEquals(reason.equals("length")?"OUTPUT_INCOMPLETE":"OUTPUT_JSON_INVALID",failure.code());
                assertEquals(200,failure.httpStatus()); assertTrue(failure.retryable()); assertEquals(reason.equals("length"),failure.splittable()); assertNull(failure.getCause());
            });
        }
    }

    private static String compatibleReply(ObjectMapper json,String reason,Object content)throws IOException {
        return json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("choices", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("finish_reason", reason, "message", cn.bidcheck.compat.Java8Api.mapOf("content", content)))));
    }
    private static void writeCompatibleFacts(HttpExchange exchange,ObjectMapper json)throws IOException {writeResponse(exchange,200,compatibleReply(json,"stop","{\"facts\":[]}"));}
    private static void writeResponse(HttpExchange exchange,int status,String content)throws IOException {
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    interface Action{void run(String url)throws Exception;}
    static void withServer(HttpHandler handler,Action action)throws Exception{HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.createContext("/",handler);server.start();try{action.run("http://127.0.0.1:"+server.getAddress().getPort()+"/");}finally{server.stop(0);executor.shutdownNow();}}
}

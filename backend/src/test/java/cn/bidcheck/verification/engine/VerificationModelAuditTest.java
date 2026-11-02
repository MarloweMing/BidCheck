package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;

class VerificationModelAuditTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();
    private static final String KEY="never-persist-provider-key-8429";
    private static final String OK="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"facts\\\":[]}\"}}]}}";

    @Test void actualRequestAndRawReplyHaveStableCorrelationAndByteHashes()throws Exception{
        AtomicReference<String> sent=new AtomicReference<>();
        withServer(exchange->{sent.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));reply(exchange,200,OK);},url->{
            var client=client(url,directory);
            try(var run=MDC.putCloseable("verification.sourceRunId","run-test");var checkpoint=MDC.putCloseable("verification.checkpointId","cp-test");var request=MDC.putCloseable("verification.requestId","request-test");var step=MDC.putCloseable("verification.stepId","step-test")){
                assertTrue(client.requestWithFeedback("EXTRACT",Map.of("rules",List.of(Map.of("predicate","PERSON_ROLE","subject","项目负责人")),"sources",List.of(Map.of("id","S22","quote","拟在本合同任职 项目负责人 姓名 张某"))),Map.of("schemaIssue","STRING_REQUIRED","schemaPath","/facts/0/value")).path("facts").isArray());
            }
        });
        JsonNode input=record("input"),output=record("output"),receipt=record("receipt");
        assertEquals(sent.get(),input.path("payload").asText());assertEquals(OK,output.path("payload").asText());
        assertEquals("run-test",input.path("runId").asText());assertEquals("cp-test",input.path("checkpointId").asText());assertEquals("request-test",input.path("requestId").asText());assertEquals("step-test",input.path("stepId").asText());
        assertEquals("EXTRACT",input.path("stage").asText());assertEquals(hash(sent.get().getBytes(StandardCharsets.UTF_8)),input.path("rawBodySha256").asText());
        assertEquals(sent.get().getBytes(StandardCharsets.UTF_8).length,input.path("requestBytes").asInt());assertTrue(output.path("responseComplete").asBoolean());
        assertTrue(receipt.path("payload").path("requestDispatched").asBoolean());assertEquals("COMPLETED",receipt.path("state").asText());
        assertTrue(sent.get().contains("STRING_REQUIRED"));assertTrue(sent.get().contains("S22"));assertTrue(sent.get().contains("PERSON_ROLE"));
        JsonNode reference=receipt.path("payload").path("inputAudit");Path file=directory.resolve(reference.path("file").asText());assertEquals(hash(Files.readAllBytes(file)),reference.path("sha256").asText());assertEquals(Files.size(file),reference.path("bytes").asLong());
        for(Path filePath:files())assertFalse(Files.readString(filePath).contains(KEY));
    }

    @Test void credentialFieldsInNestedChatJsonAndProviderErrorsAreRedacted()throws Exception{
        String error="{\"error\":{\"message\":\"Bearer arbitrary-provider-token "+KEY+"\",\"password\":\"provider-password\"}}";
        withServer(exchange->reply(exchange,429,error),url->{var client=client(url,directory);var failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request("EXTRACT",Map.of("api_key","separate-app-secret","sources",List.of(Map.of("id","S1","quote","原文 姓名 保留")))));assertEquals("HTTP_429",failure.code());});
        String captured=Files.readString(file("input"))+Files.readString(file("output"));
        for(String secret:List.of(KEY,"arbitrary-provider-token","provider-password","separate-app-secret"))assertFalse(captured.contains(secret),secret);
        assertTrue(captured.contains("原文 姓名 保留"));assertTrue(captured.contains("[REDACTED]"));assertTrue(record("output").path("responseComplete").asBoolean());assertEquals(429,record("receipt").path("httpStatus").asInt());assertEquals("HTTP_429",record("receipt").path("errorCode").asText());
    }

    @Test void invalidProviderJsonIsPreservedWithActualFailureCode()throws Exception{
        withServer(exchange->reply(exchange,200,"{not-json"),url->{var failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client(url,directory).request("EXTRACT",Map.of()));assertEquals("OUTPUT_JSON_INVALID",failure.code());});
        assertEquals("{not-json",record("output").path("payload").asText());assertEquals("OUTPUT_JSON_INVALID",record("receipt").path("errorCode").asText());assertEquals("FAILED",record("receipt").path("state").asText());
    }

    @Test void outputLimitRetainsOnlyMarkedPartialResponseAndDoesNotTurnIntoSuccess()throws Exception{
        withServer(exchange->{byte[] bytes="x".repeat(270_000).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try{exchange.getResponseBody().write(bytes);}catch(IOException ignored){}exchange.close();},url->{var failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client(url,directory).request("EXTRACT",Map.of()));assertEquals("OUTPUT_LIMIT",failure.code());});
        JsonNode output=record("output");assertFalse(output.path("responseComplete").asBoolean());assertTrue(output.path("observedBytes").asLong()>256_000);assertEquals(256_000,output.path("capturedBytes").asInt());assertEquals("OUTPUT_LIMIT",record("receipt").path("errorCode").asText());
    }

    @Test void oversizedAuditIsExplicitlyOmittedAndFileHashStillVerifies()throws Exception{
        var call=new VerificationModelAudit.Call(directory.toString(),KEY,"EXTRACT",1024);call.requestPrepared("原文".repeat(3000));call.result("COMPLETED","",200,"","");Map<String,Object> ref=call.finish(Map.of(),Map.of());
        JsonNode input=record("input");assertTrue(input.path("payloadOmitted").asBoolean());assertFalse(input.has("payload"));assertEquals("AUDIT_PAYLOAD_TOO_LARGE",input.path("reason").asText());assertTrue(input.path("payloadBytes").asInt()>1024);
        Map<?,?> inputRef=(Map<?,?>)ref.get("inputAudit");assertEquals("PAYLOAD_OMITTED",inputRef.get("status"));assertEquals(hash(Files.readAllBytes(directory.resolve(inputRef.get("file").toString()))),inputRef.get("sha256"));
    }

    @Test void disabledAndUnwritableAuditsNeverBlockSuccessfulModelCalls()throws Exception{
        Path blocked=directory.resolve("regular-file");Files.writeString(blocked,"not a directory");
        withServer(exchange->reply(exchange,200,OK),url->{assertTrue(new VerificationModelClient(url,KEY,"model",Duration.ofSeconds(5)).request("EXTRACT",Map.of()).path("facts").isArray());assertTrue(client(url,blocked).request("EXTRACT",Map.of()).path("facts").isArray());});
        assertFalse(Files.exists(directory.resolve("java-model")));try(var paths=Files.list(directory)){assertEquals(1,paths.count());}
        var disabled=VerificationModelAudit.begin("",KEY,"EXTRACT");assertEquals(Map.of("status","DISABLED"),disabled.finish(Map.of(),Map.of()));
        var broken=VerificationModelAudit.begin(blocked.toString(),KEY,"EXTRACT");broken.requestPrepared("{}");Map<String,Object> receipt=broken.finish(Map.of(),Map.of());assertEquals("WRITE_FAILED",receipt.get("status"));
    }

    @Test void locallyRejectedInputIsMarkedUnsentAndDoesNotInventAResponse()throws Exception{
        var client=client("http://127.0.0.1:9/",directory);var failure=assertThrows(VerificationModelClient.ModelRequestException.class,()->client.request("COMPILE",Map.of("source","原文")));assertEquals("OPERATION_NOT_ALLOWED",failure.code());
        JsonNode receipt=record("receipt");assertFalse(receipt.path("requestDispatched").asBoolean());assertEquals("OPERATION_NOT_ALLOWED",receipt.path("errorCode").asText());assertEquals("UNSENT_APPLICATION_INPUT",record("error").path("payloadKind").asText());assertEquals("NO_RESPONSE_CAPTURED",receipt.path("payload").path("outputAudit").path("status").asText());assertEquals(2,files().size());
    }

    private VerificationModelClient client(String url,Path path)throws Exception{var client=new VerificationModelClient(url,KEY,"model",Duration.ofSeconds(5));var field=VerificationModelClient.class.getDeclaredField("auditDirectory");field.setAccessible(true);field.set(client,path.toString());return client;}
    private List<Path> files()throws IOException{try(var files=Files.list(directory.resolve("java-model"))){return files.toList();}}
    private Path file(String direction)throws IOException{return files().stream().filter(path->path.getFileName().toString().endsWith("-"+direction+".json")).findFirst().orElseThrow();}
    private JsonNode record(String direction)throws IOException{return json.readTree(file(direction).toFile());}
    private static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static void reply(HttpExchange exchange,int status,String text)throws IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    @FunctionalInterface private interface Check {void apply(String url)throws Exception;}
    private static void withServer(HttpHandler handler,Check check)throws Exception{HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",handler);server.start();try{check.apply("http://127.0.0.1:"+server.getAddress().getPort()+"/");}finally{server.stop(0);}}
}

package cn.bidcheck.verification.external;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalVerificationAdapterTest {
    HttpServer server;ObjectMapper json=new ObjectMapper();AtomicInteger calls=new AtomicInteger();AtomicReference<JsonNode> sent=new AtomicReference<>();volatile int code=200,delay;volatile String response;
    @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/authority",e->{try{calls.incrementAndGet();sent.set(json.readTree(e.getRequestBody()));if(delay>0)Thread.sleep(delay);if(code==302)e.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/authority");byte[] body=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(code,body.length);e.getResponseBody().write(body);}catch(Exception ignored){}finally{e.close();}});server.start();response=json.writeValueAsString(snapshot("VERIFIED",true));}
    @AfterEach void stop(){server.stop(0);}
    String configuration()throws Exception{return json.writeValueAsString(List.of(Map.of("type","CREDIT_STATUS","url","http://127.0.0.1:"+server.getAddress().getPort()+"/authority","issuer","合成权威机构","source","合成授信记录系统","key","fixture-secret")));}
    ExternalVerificationAdapter adapter()throws Exception{return new ExternalVerificationAdapter(configuration(),Duration.ofSeconds(3),true);}
    Map<String,Object> request(){return Map.of("type","CREDIT_STATUS","subject","合成企业甲","evaluationStage","EVALUATION","referenceDate","2025-06-01");}
    Map<String,Object> snapshot(String status,boolean complete){var m=new LinkedHashMap<String,Object>();m.put("type","CREDIT_STATUS");m.put("subject","合成企业甲");m.put("evaluationStage","EVALUATION");m.put("effectiveAt","2025-06-01");m.put("coverage","合成来源中指定主体的信用状态");m.put("coverageComplete",complete);m.put("result",Map.of("status",status,"facts",status.equals("VERIFIED")?List.of(Map.of("field","creditStatus","value","ACTIVE","rawValue","ACTIVE","sourceQuote","creditStatus: ACTIVE")):List.of(),"summary","合成记录"));return m;}
    @Test void disabledIsNotReportedAsConnected(){var adapter=new ExternalVerificationAdapter();assertEquals(List.of(),adapter.enabledTypes());assertEquals("DISABLED",adapter.identity());assertEquals("UNAVAILABLE",adapter.query(request()).get("status"));assertEquals(0,calls.get());}
    @Test void executionReceiptDistinguishesMissingConfigurationFromActualFailedQuery()throws Exception{
        var missing=new ExternalVerificationAdapter().query(request());assertEquals(false,missing.get("queryExecuted"));assertEquals("EXTERNAL_CONNECTOR_NOT_CONFIGURED",missing.get("errorCode"));
        code=503;var failed=adapter().query(request());assertEquals(true,failed.get("queryExecuted"));assertEquals("QUERY_FAILED",failed.get("connectionStatus"));assertNull(failed.get("record"));assertEquals(1,calls.get());
    }
    @Test void fixedEndpointProtocolProducesBoundRecordWithoutBusinessPass()throws Exception{var a=adapter();var out=a.query(request());assertEquals("COMPLETED",out.get("status"));Map<?,?> r=(Map<?,?>)out.get("record");assertEquals("TRUSTED_CONNECTOR",r.get("provenanceStatus"));assertEquals("2025-06-01",r.get("effectiveAt"));assertEquals("EVALUATION",r.get("evaluationStage"));assertEquals("合成权威机构",r.get("issuer"));assertNotNull(r.get("rawSnapshotHash"));assertEquals(response,r.get("rawSnapshot"));assertEquals("VERIFIED",((Map<?,?>)r.get("result")).get("status"));assertFalse(out.toString().contains("fixture-secret"));assertEquals("2025-06-01",sent.get().get("referenceDate").asText());assertFalse(sent.get().has("url"));assertEquals(List.of("CREDIT_STATUS"),a.enabledTypes());assertEquals(Map.of("合成授信记录系统","CREDIT_STATUS"),a.enabledSourceTypes());}
    @Test void requestCannotChooseEndpointOrUnconfiguredType()throws Exception{var a=adapter();var req=new HashMap<>(request());req.put("url","https://elsewhere.invalid/");assertEquals("EXTERNAL_REQUEST_INVALID",a.query(req).get("errorCode"));req=new HashMap<>(request());req.put("type","OTHER_TYPE");assertEquals("UNAVAILABLE",a.query(req).get("status"));assertEquals(0,calls.get());}
    @Test void productionRejectsHttpAndRedirectIsNotFollowed()throws Exception{assertEquals(List.of(),new ExternalVerificationAdapter(configuration()).enabledTypes());code=302;var out=adapter().query(request());assertEquals("EXTERNAL_HTTP_302",out.get("errorCode"));assertNull(out.get("record"));assertEquals(1,calls.get());}
    @Test void subjectStageAndHistoricalDateMustMatchExactly()throws Exception{for(String key:List.of("subject","evaluationStage","effectiveAt")){var value=snapshot("VERIFIED",true);value.put(key,key.equals("effectiveAt")?"2026-06-01":"DIFFERENT");response=json.writeValueAsString(value);var out=adapter().query(request());assertEquals("ERROR",out.get("status"));assertNull(out.get("record"));}}
    @Test void notFoundRequiresCompleteCoverageAndIsNotFailureOrPass()throws Exception{response=json.writeValueAsString(snapshot("NOT_FOUND",false));assertEquals("EXTERNAL_NEGATIVE_COVERAGE_INCOMPLETE",adapter().query(request()).get("errorCode"));response=json.writeValueAsString(snapshot("NOT_FOUND",true));var out=adapter().query(request());assertEquals("COMPLETED",out.get("status"));assertEquals("NOT_FOUND",((Map<?,?>)((Map<?,?>)out.get("record")).get("result")).get("status"));}
    @Test void verifiedFactsNeedRawValuesAndCannotBeCallerVerdicts()throws Exception{var value=snapshot("VERIFIED",true);value.put("result",Map.of("status","VERIFIED","facts",List.of(Map.of("field","creditStatus","value","ACTIVE","rawValue","UNKNOWN")),"summary","bad"));response=json.writeValueAsString(value);assertEquals("EXTERNAL_FACT_VALUE_UNGROUNDED",adapter().query(request()).get("errorCode"));value.put("result",Map.of("status","PASS","facts",List.of(),"summary","forbidden"));response=json.writeValueAsString(value);assertEquals("EXTERNAL_RESULT_INVALID",adapter().query(request()).get("errorCode"));}
    @Test void verifiedScalarWithoutTraceableSourceQuoteCannotBecomeARecord()throws Exception{for(String quote:List.of("MISSING","   ","creditStatus: UNKNOWN","creditStatus: INACTIVE")){var fact=new LinkedHashMap<String,Object>(Map.of("field","creditStatus","value","ACTIVE","rawValue","ACTIVE"));if(!quote.equals("MISSING"))fact.put("sourceQuote",quote);var value=snapshot("VERIFIED",true);value.put("result",Map.of("status","VERIFIED","facts",List.of(fact),"summary","合成记录"));response=json.writeValueAsString(value);var out=adapter().query(request());assertEquals("ERROR",out.get("status"));assertNull(out.get("record"));assertEquals(quote.startsWith("creditStatus")?"EXTERNAL_FACT_VALUE_UNGROUNDED":"EXTERNAL_FACT_SOURCE_REQUIRED",out.get("errorCode"));}}
    @Test void positiveNumericFactCannotBeGroundedByNegativeOrExponentSubstring()throws Exception{for(String quote:List.of("amount: -10","amount: 10e3")){var value=snapshot("VERIFIED",true);value.put("result",Map.of("status","VERIFIED","facts",List.of(Map.of("field","amount","value",10,"rawValue","10","sourceQuote",quote)),"summary","合成记录"));response=json.writeValueAsString(value);assertEquals("EXTERNAL_FACT_VALUE_UNGROUNDED",adapter().query(request()).get("errorCode"));}}
    @Test void serverFailureAndOversizedBodyNeverBecomeNegativeFinding()throws Exception{code=503;assertEquals("EXTERNAL_HTTP_503",adapter().query(request()).get("errorCode"));code=200;response="private-provider-body".repeat(10000);var out=adapter().query(request());assertEquals("ERROR",out.get("status"));assertNull(out.get("record"));assertFalse(out.toString().contains("private-provider-body"));}
    @Test void timeoutAndCancellationStayExplicit()throws Exception{delay=500;var a=new ExternalVerificationAdapter(configuration(),Duration.ofMillis(100),true);assertEquals("EXTERNAL_TIMEOUT",a.query(request()).get("errorCode"));Thread.currentThread().interrupt();try{assertThrows(CancellationException.class,()->a.query(request()));}finally{Thread.interrupted();}}
    @Test void identityIsConfigurationBoundButNeverIncludesKey()throws Exception{String config=configuration();var first=new ExternalVerificationAdapter(config,Duration.ofSeconds(3),true);var second=new ExternalVerificationAdapter(config.replace("fixture-secret","another-secret"),Duration.ofSeconds(3),true);assertEquals(first.identity(),second.identity());assertNotEquals(first.identity(),new ExternalVerificationAdapter(config.replace("合成权威机构","另一来源"),Duration.ofSeconds(3),true).identity());assertFalse(first.identity().contains("secret"));}
    Map<String,Object> scopedRequest(){var request=new LinkedHashMap<>(request());request.put("requiredProvider","合成授信记录系统");request.put("requirementScopeFingerprint","a".repeat(64));request.put("requiredConditionFields",List.of("creditStatus"));return request;}
    @Test void sourceScopeIsTransmittedAndProviderConfirmedFieldsArePreserved()throws Exception{
        var snapshot=snapshot("VERIFIED",true);snapshot.put("requirementScopeFingerprint","a".repeat(64));snapshot.put("coveredConditionFields",List.of("creditStatus"));response=json.writeValueAsString(snapshot);
        var result=adapter().query(scopedRequest());assertEquals("COMPLETED",result.get("status"),result.toString());var record=(Map<?,?>)result.get("record");
        assertEquals("a".repeat(64),sent.get().path("requirementScopeFingerprint").asText());assertEquals("creditStatus",sent.get().path("requiredConditionFields").get(0).asText());
        assertEquals("a".repeat(64),record.get("requirementScopeFingerprint"));assertEquals(List.of("creditStatus"),record.get("coveredConditionFields"));assertEquals(true,record.get("scopeVerified"));
    }
    @Test void requestScopeAloneOrEchoedFieldsWithoutActualFactsNeverBecomeProof()throws Exception{
        assertNull(adapter().query(scopedRequest()).get("record"),"old unscoped response must not acquire requested scope by copying");
        var snapshot=snapshot("VERIFIED",true);snapshot.put("requirementScopeFingerprint","b".repeat(64));snapshot.put("coveredConditionFields",List.of("creditStatus"));response=json.writeValueAsString(snapshot);
        assertEquals("EXTERNAL_SCOPE_RESPONSE_MISMATCH",adapter().query(scopedRequest()).get("errorCode"));
        snapshot.put("requirementScopeFingerprint","a".repeat(64));snapshot.put("coveredConditionFields",List.of("creditStatus","otherStatus"));response=json.writeValueAsString(snapshot);
        var request=scopedRequest();request.put("requiredConditionFields",List.of("creditStatus","otherStatus"));
        assertEquals("EXTERNAL_SCOPE_FIELDS_UNPROVEN",adapter().query(request).get("errorCode"));
    }
    @Test void scopeCannotRedirectTheFixedProvider()throws Exception{
        var request=scopedRequest();request.put("requiredProvider","OTHER_PROVIDER");
        assertEquals("EXTERNAL_PROVIDER_OR_FIELDS_MISMATCH",adapter().query(request).get("errorCode"));assertEquals(0,calls.get());
    }
}

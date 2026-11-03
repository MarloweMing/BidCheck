package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelRecoveryRegressionTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    interface Request { JsonNode call(String operation,Map<String,Object> input)throws IOException; }
    private ModelGateway gateway(Request request){return new ModelGateway(){
        public boolean enabled(){return true;}public String identity(){return "local-recovery-fixture";}
        public JsonNode request(String op,Map<String,Object> input)throws IOException{return request.call(op,input);}
    };}
    private VerificationModelClient.ModelRequestException timeout(){return new VerificationModelClient.ModelRequestException("REQUEST_TIMEOUT",0,true,true);}

    @Test void transientFailureRetriesOnlySameRequestAndPreservesBothAttempts()throws Exception{
        AtomicInteger calls=new AtomicInteger();List<Map<String,Object>> inputs=new ArrayList<>();
        RunSession session=new RunSession(gateway((op,input)->{inputs.add(input);if(calls.incrementAndGet()==1)throw timeout();return JSON.createObjectNode().put("ok",true);}),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"doc");
        JsonNode response=session.model("COMPILE",cn.bidcheck.compat.Java8Api.mapOf("requirement", "测试要求"));assertTrue(response.get("ok").asBoolean());
        assertEquals(2,calls.get());assertEquals(inputs.get(0),inputs.get(1));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("STARTED", "FAILED", "STARTED", "RECEIVED"),cn.bidcheck.compat.Java8Api.toList(session.steps.stream().map(s->s.get("status"))));
        assertNotEquals(session.steps.get(0).get("attemptId"),session.steps.get(2).get("attemptId"));
        assertEquals(1L,session.diagnostics().get("recoveredAttempts"));assertEquals(0L,session.diagnostics().get("unresolvedFailures"));
        session.completeCompilation(cn.bidcheck.compat.Java8Api.mapOf("requirement", "测试要求"),response,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("reasonCode", "PLAN_VALIDATION_FAILED", "need", "ok并非业务规则")));
        assertEquals("VALIDATION_FAILED",session.steps.get(session.steps.size()-1).get("status"));
        RunSession resumed=new RunSession(session.model,cn.bidcheck.compat.Java8Api.mapOf("priorSteps", session.steps),"doc");
        assertNull(resumed.prior("COMPILE",resumed.fingerprint("COMPILE",cn.bidcheck.compat.Java8Api.mapOf("requirement", "测试要求"))),"Transport recovery must not create a valid-plan cache hit");
    }
    @Test void retryIsBoundedEvenWhenTotalCallsAreUnlimited(){
        AtomicInteger calls=new AtomicInteger();RunSession session=new RunSession(gateway((op,input)->{calls.incrementAndGet();throw timeout();}),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"doc");
        assertThrows(IOException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertEquals(2,calls.get());assertEquals(2L,session.diagnostics().get("unresolvedFailures"));
    }
    @Test void permanentFailureIsNotRepeatedAndKeepsSafeErrorCode(){
        AtomicInteger calls=new AtomicInteger();RunSession session=new RunSession(gateway((op,input)->{calls.incrementAndGet();throw new VerificationModelClient.ModelRequestException("HTTP_401",401,false,false);}),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"doc");
        assertThrows(IOException.class,()->session.model("COMPILE",cn.bidcheck.compat.Java8Api.mapOf()));assertEquals(1,calls.get());
        java.util.Map<?, ?> failure=(Map<?,?>)((List<?>)session.diagnostics().get("failures")).get(0);assertEquals("HTTP_401",failure.get("errorCode"));assertEquals(401,failure.get("httpStatus"));
    }
    @Test void inputLimitHasSplittingHintWithoutBlindRetry(){
        RunSession session=new RunSession(gateway((op,input)->{throw new VerificationModelClient.ModelRequestException("INPUT_LIMIT",0,false,true);}),cn.bidcheck.compat.Java8Api.mapOf(),"doc");
        assertThrows(IOException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertEquals(1,session.modelCalls);
        java.util.Map<?, ?> failure=(Map<?,?>)((List<?>)session.diagnostics().get("failures")).get(0);assertEquals(true,failure.get("splittable"));assertEquals("INPUT_LIMIT",failure.get("errorCode"));
    }
    @Test void arbitraryProviderTextNeverEntersAudit(){
        String secret="provider-body-with-secret-and-document-text";RunSession session=new RunSession(gateway((op,input)->{throw new IOException(secret);}),cn.bidcheck.compat.Java8Api.mapOf(),"doc");
        assertThrows(IOException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertFalse(JSON.valueToTree(session.steps).toString().contains(secret));
        assertEquals("MODEL_UNAVAILABLE",RunSession.failure(new VerificationModelClient.ModelRequestException(secret,0,true,true)).get("errorCode"));
        assertEquals("MODEL_UNAVAILABLE",RunSession.failure(new VerificationModelClient.ModelRequestException(null,0,true,true)).get("errorCode"));
    }
    @Test void laterDifferentEvidenceSuccessDoesNotEraseEarlierFailure()throws Exception{
        RunSession session=new RunSession(gateway((op,input)->{if(input.containsKey("failed"))throw timeout();return JSON.createObjectNode();}),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"doc");
        assertThrows(IOException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("failed", true)));session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("different", true));
        assertEquals(0L,session.diagnostics().get("recoveredAttempts"));assertEquals(2L,session.diagnostics().get("unresolvedFailures"));
    }
    @Test void successfulRetryIsReusableWithoutAnotherPaidCall()throws Exception{
        AtomicInteger calls=new AtomicInteger();ModelGateway model=gateway((op,input)->{if(calls.incrementAndGet()==1)throw timeout();return JSON.createObjectNode();});
        RunSession first=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"doc");first.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("a", "b"));
        RunSession next=new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true, "priorSteps", first.steps),"doc");next.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("a", "b"));assertEquals(2,calls.get());assertEquals(1,next.resumedSteps);
    }
    @Test void separateVerifyBudgetStillLimitsRetries(){
        AtomicInteger calls=new AtomicInteger();RunSession session=new RunSession(gateway((op,input)->{calls.incrementAndGet();throw timeout();}),cn.bidcheck.compat.Java8Api.mapOf("maxModelCalls", 1),"doc");
        assertThrows(IOException.class,()->session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf()));assertEquals(1,calls.get());
    }
    @Test void unlimitedNormalCheckDoesNotReintroduceTotalCallCap()throws Exception{
        AtomicInteger calls=new AtomicInteger();RunSession session=new RunSession(gateway((op,input)->{if(calls.incrementAndGet()%2==1)throw timeout();return JSON.createObjectNode();}),cn.bidcheck.compat.Java8Api.mapOf("unlimitedModelCalls", true),"doc");
        for(int i=0;i<3;i++)session.model("EXTRACT",cn.bidcheck.compat.Java8Api.mapOf("part", i));assertEquals(6,calls.get());
    }
    @Test void unsupportedRulesRemainVisibleWithoutRetrievalOrExtraction(){
        String requirement="投标人应接受现场核验";AtomicInteger calls=new AtomicInteger();ModelGateway model=gateway((op,input)->{
            calls.incrementAndGet();assertEquals("COMPILE",op);return JSON.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "r", "predicate", "UNSUPPORTED", "description", requirement, "subject", "投标人", "sourceQuote", requirement))));
        });
        Document bid=new Document("bid","project","BID","投标文件",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标人资料","PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());
        Checkpoint cp=new Checkpoint("c",requirement,"资格","高风险","SEMANTIC_RESPONSE","","",requirement,"",true,true,true,null);
        Map<String,Object> result=new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid),null,cn.bidcheck.compat.Java8Api.mapOf("focusedCheck", true, "atomicDomains", cn.bidcheck.compat.Java8Api.listOf("PERSONNEL_CONSORTIUM"), "unlimitedModelCalls", true));
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals("NEEDS_REVIEW",result.get("workflowState"));assertEquals(0,calls.get(),"生产路径不得调用模型编写规则");
        assertEquals(0,((Map<?,?>)result.get("metrics")).get("rounds"));assertFalse(((List<?>)result.get("obligations")).isEmpty());
        assertFalse(JSON.valueToTree(result.get("gaps")).toString().contains("SUBJECT_RELATION_UNPROVEN"));
    }
}

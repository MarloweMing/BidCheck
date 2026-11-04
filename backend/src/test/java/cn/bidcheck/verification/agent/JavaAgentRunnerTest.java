package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaAgentRunnerTest {
    @Test void threeFruitlessRoundsCloseOnlyActiveInvestigationAndContinueOtherTarget()throws Exception{
        Map<String,Object> context=typedContext();context.put("targetObligationIds",Arrays.asList("A","B"));context.put("targetEvidenceRevisions",map("A","v1","B","v1"));
        AtomicInteger turns=new AtomicInteger();List<String> visited=new ArrayList<>();
        SdkAgentInvestigator agent=bridge((r,t)->{int turn=turns.incrementAndGet();String target=turn<=3?"A":"B";return response(call("search"+turn,"search_evidence",map("query","query"+turn,"targetObligationId",target)));});
        Map<String,Object> result=agent.investigate(start(context,tool("search_evidence",map("query",schema(),"targetObligationId",schema()),"query","targetObligationId")),(n,a)->{String target=String.valueOf(a.get("targetObligationId"));visited.add(target);return map("sources",Collections.singletonList(map("id","same-page","documentId","synthetic-doc","page",7,"locationType","PAGE","text","合成原文")));},e->{});
        assertEquals(Arrays.asList("A","A","A","B","B","B"),visited,"One repeated target must not stop the unvisited target");
        assertEquals(6,turns.get());assertEquals(Collections.emptyList(),result.get("completedTargetObligationIds"));
    }
    @Test void samePageChangedQueryIsDuplicateButNewPageIsNot()throws Exception{
        AtomicInteger turns=new AtomicInteger(),calls=new AtomicInteger();
        Map<String,Object> result=bridge((r,t)->turns.incrementAndGet()<=3?response(call("q"+turns.get(),"search_evidence",map("query","word"+turns.get()))):response()).investigate(
            start(map(),tool("search_evidence",map("query",schema()),"query")),(n,a)->{int call=calls.incrementAndGet();return map("sources",Collections.singletonList(map("id","chunk"+call,"documentId","synthetic-doc","locationType","PAGE","page",call<3?7:8,"text","synthetic body")));},e->{});
        assertEquals(3,calls.get());assertEquals(1L,result.get("duplicateCallsAvoided"));
    }
    @Test void callerCancellationRetainsTheAlreadyStartedModelCount()throws Exception{
        CountDownLatch requested=new CountDownLatch(1);AtomicReference<Map<String,Object>> receipt=new AtomicReference<>();
        SdkAgentInvestigator agent=bridge((request,timeout)->{requested.countDown();Thread.sleep(5000);return response();});
        Thread caller=new Thread(()->receipt.set(agent.investigate(start(map(),tool("search_evidence",map())),(name,args)->map(),event->{})));
        try{caller.start();assertTrue(requested.await(2,TimeUnit.SECONDS));caller.interrupt();caller.join(2000);assertFalse(caller.isAlive());
            assertNotNull(receipt.get());assertEquals("HOST_CANCELLED",receipt.get().get("errorCode"));assertEquals(1L,receipt.get().get("modelCalls"));assertEquals(0L,receipt.get().get("toolCalls"));assertEquals("OBSERVED_STARTED_EVENTS",receipt.get().get("countBasis"));
        }finally{caller.interrupt();caller.join(2000);}
    }
    @TempDir Path temp;
    private static final String BASE="https://dashscope.aliyuncs.com/compatible-mode/v1",KEY="native-agent-synthetic-key";
    private final ObjectMapper json=new ObjectMapper();
    private SdkAgentInvestigator bridge(JavaAgentRunner.ModelTransport model){return new SdkAgentInvestigator(true,BASE,KEY,"qwen-plus",10,1,null,model);}
    private Map<String,Object> tool(String name,Map<String,Object> properties,String...required){return map("name",name,"description","服务器控制工具","parameters",map("type","object","properties",properties,"required",Arrays.asList(required),"additionalProperties",false));}
    private Map<String,Object> start(Map<String,Object> context,Map<String,Object>...tools){return map("type","start","protocol","bidcheck-agent-1","requestId","native-test","context",context,"tools",Arrays.asList(tools));}
    private Map<String,Object> call(String id,String name,Map<String,Object> args)throws Exception{return map("id",id,"type","function","function",map("name",name,"arguments",json.writeValueAsString(args)));}
    private Map<String,Object> response(Map<String,Object>...calls){return map("choices",Collections.singletonList(map("message",map("role","assistant","content","模型自称全部通过，不可信","tool_calls",Arrays.asList(calls)))),"usage",map("prompt_tokens",12,"completion_tokens",3));}
    private Map<String,Object> schema(){return map("type","string");}
    @Test void hostDeadlineKeepsStartedModelAndToolCounts() {
        AtomicInteger calls=new AtomicInteger();
        JavaAgentRunner.ModelTransport model=(request,timeout)->{
            if(calls.incrementAndGet()==1)return response(call("first","search_evidence",map("query","模拟证据")));
            Thread.sleep(4000);
            return response();
        };
        SdkAgentInvestigator agent=new SdkAgentInvestigator(true,BASE,KEY,"synthetic",1,1,null,model);
        Map<String,Object> result=agent.investigate(start(map(),tool("search_evidence",map("query",schema()),"query")),
            (name,args)->map("sources",Collections.emptyList()),event->{});
        assertEquals("INVESTIGATION_TIMEOUT",result.get("errorCode"),result.toString());
        assertEquals(2L,((Number)result.get("modelCalls")).longValue());
        assertEquals(1L,((Number)result.get("toolCalls")).longValue());
    }
    @Test void javaLoopUsesOnlyHostFactsAndCompletion()throws Exception{
        AtomicInteger modelCalls=new AtomicInteger();List<String> tools=new ArrayList<>();List<Map<String,Object>> events=new ArrayList<>();
        SdkAgentInvestigator agent=bridge((request,timeout)->modelCalls.getAndIncrement()==0?response(call("one","submit_candidate_fact",map("quote","原文"))):response(call("two","recompute_check",map())));
        Map<String,Object> result=agent.investigate(start(map(),tool("submit_candidate_fact",map("quote",schema()),"quote"),tool("recompute_check",map())),(name,args)->{tools.add(name);return name.equals("submit_candidate_fact")?map("acceptedFactIds",Collections.singletonList("host-fact")):map("businessCheckCompleted",true);},events::add);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(Arrays.asList("submit_candidate_fact","recompute_check"),tools);assertEquals(Collections.singletonList("host-fact"),result.get("acceptedFactIds"));assertEquals("bidcheck-java",result.get("sdk"));assertEquals(2L,result.get("modelCalls"));assertFalse(result.containsKey("summary"));
        assertFalse(events.toString().contains("模型自称"));assertFalse(events.toString().contains(KEY));assertFalse(events.toString().contains("原文"));
    }
    @Test void modelProseCannotCompleteBusinessOrAcceptFacts(){
        Map<String,Object> result=bridge((r,t)->response()).investigate(start(map(),tool("search_evidence",map())),(n,a)->{fail("No tool call expected");return map();},e->{});
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(false,result.get("businessCheckCompleted"));assertEquals(Collections.emptyList(),result.get("acceptedFactIds"));
    }
    @Test void identicalVisibleSourceBodyAppearsOnceAndRetainsNavigationMetadata()throws Exception{
        AtomicInteger turns=new AtomicInteger();List<Map<String,Object>> requests=new ArrayList<>();SdkAgentInvestigator agent=bridge((r,t)->{requests.add(json.convertValue(r,LinkedHashMap.class));int turn=turns.getAndIncrement();return turn<2?response(call("search-"+turn,"search_evidence",map("query","wording-"+turn))):response();});
        Map<String,Object> result=agent.investigate(start(map(),tool("search_evidence",map("query",schema()),"query")),(n,a)->map("sources",Collections.singletonList(map("sourceId","S1","text","ONLY_SOURCE_BODY","sectionTitle","原文标题","startOffset",42))),e->{});
        String last=json.writeValueAsString(requests.get(2));assertEquals(1,(last.length()-last.replace("ONLY_SOURCE_BODY","").length())/"ONLY_SOURCE_BODY".length());assertTrue(last.contains("bodyAlreadyReturned"));assertTrue(last.contains("sectionTitle"));assertTrue(last.contains("startOffset"));assertNotEquals("FAILED",result.get("status"));
    }
    @Test void rewordedSearchesWithSameEvidenceStopWithoutExhaustingBusinessTarget()throws Exception{
        AtomicInteger turns=new AtomicInteger();Map<String,Object> context=typedContext();Map<String,Object> result=bridge((r,t)->response(call("q"+turns.get(),"search_evidence",map("query","different wording "+turns.incrementAndGet(),"targetObligationId","A")))).investigate(start(context,tool("search_evidence",map("query",schema(),"targetObligationId",schema()),"query","targetObligationId")),(n,a)->map("sources",Collections.singletonList(map("sourceId","S1","text","same frozen source"))),e->{});
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(3,turns.get());assertEquals(Collections.singletonList("A"),result.get("remainingTargetObligationIds"));assertEquals(Collections.emptyList(),result.get("completedTargetObligationIds"));
    }
    @Test void revokedModelPermissionPreventsProviderRequest(){
        AtomicInteger requests=new AtomicInteger();Map<String,Object> result=bridge((r,t)->{requests.incrementAndGet();return response();}).investigate(start(map(),tool("search_evidence",map())),(n,a)->map(),e->{if("model_started".equals(e.get("event")))throw new CancellationException("lease lost");});
        assertEquals("MODEL_REQUEST_DENIED",result.get("errorCode"));assertEquals(0,requests.get());
    }
    @Test void repeatedMalformedArgumentsNeverReachHostAndStop(){
        AtomicInteger hostCalls=new AtomicInteger(),modelCalls=new AtomicInteger();Map<String,Object> result=bridge((r,t)->{modelCalls.incrementAndGet();return response(call("q","search_evidence",map("unexpected","private-value")));}).investigate(start(map(),tool("search_evidence",map("query",schema()),"query")),(n,a)->{hostCalls.incrementAndGet();return map();},e->{});
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(0,hostCalls.get());assertEquals(2,modelCalls.get());assertEquals(2L,result.get("correctableToolRejectionCount"));assertFalse(result.toString().contains("private-value"));
    }
    @Test void schemaCorrectionCanContinueAndComplete()throws Exception{
        AtomicInteger turns=new AtomicInteger(),hostCalls=new AtomicInteger();SdkAgentInvestigator agent=bridge((r,t)->turns.getAndIncrement()==0?response(call("bad","search_evidence",map())):response(call("good","search_evidence",map("query","项目经理")),call("done","recompute_check",map())));
        Map<String,Object> result=agent.investigate(start(map(),tool("search_evidence",map("query",schema()),"query"),tool("recompute_check",map())),(n,a)->{hostCalls.incrementAndGet();return n.equals("recompute_check")?map("businessCheckCompleted",true):map("sources",Collections.singletonList(map("id","s1","text","项目经理：甲")));},e->{});
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,hostCalls.get());assertEquals(1L,result.get("correctableToolRejectionCount"));
    }
    @Test void duplicateJsonKeysAndUnadvertisedToolsFailClosed()throws Exception{
        Map<String,Object> duplicate=map("id","one","type","function","function",map("name","search_evidence","arguments","{\"query\":\"a\",\"query\":\"b\"}"));
        Map<String,Object> bad=bridge((r,t)->response(duplicate)).investigate(start(map(),tool("search_evidence",map("query",schema()))),(n,a)->{fail("duplicate arguments");return map();},e->{});assertEquals("MODEL_PROTOCOL_ERROR",bad.get("errorCode"));
        Map<String,Object> unknown=bridge((r,t)->response(call("one","execute_code",map()))).investigate(start(map(),tool("search_evidence",map())),(n,a)->{fail("unknown tool");return map();},e->{});assertEquals("TOOL_NOT_ALLOWED",unknown.get("errorCode"));
    }
    @Test void laterFactInSameBatchInvalidatesEarlierCompletion()throws Exception{
        AtomicInteger turns=new AtomicInteger();SdkAgentInvestigator agent=bridge((r,t)->turns.getAndIncrement()==0?response(call("done","recompute_check",map()),call("new","submit_candidate_fact",map())):response());
        Map<String,Object> result=agent.investigate(start(map(),tool("recompute_check",map()),tool("submit_candidate_fact",map())),(n,a)->n.equals("recompute_check")?map("businessCheckCompleted",true):map("acceptedFactIds",Collections.singletonList("later-fact")),e->{});
        assertEquals("EVIDENCE_UPDATED",result.get("status"),result.toString());assertEquals(false,result.get("businessCheckCompleted"));assertEquals(2,turns.get());
    }
    @Test void failedOperationOnlyRecoversAfterSameOperationSuccess()throws Exception{
        AtomicInteger turns=new AtomicInteger(),attempts=new AtomicInteger();SdkAgentInvestigator agent=bridge((r,t)->turns.getAndIncrement()<2?response(call("read","read_section",map("sourceId","s1"))):response());
        Map<String,Object> result=agent.investigate(start(map(),tool("read_section",map("sourceId",schema()),"sourceId")),(n,a)->attempts.getAndIncrement()==0?map("status","UNAVAILABLE","error","OCR_TIMEOUT"):map("status","COMPLETED","sources",Collections.singletonList(map("id","s1","text","可靠原文"))),e->{});
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("recoveredToolCount"));assertEquals(2,attempts.get());
    }
    @Test void initialCanonicalProofCanCompleteWithoutPaidModelCall(){
        Map<String,Object> context=typedContext();context.putAll(receipt(true));AtomicInteger models=new AtomicInteger();Map<String,Object> result=bridge((r,t)->{models.incrementAndGet();return response();}).investigate(start(context,tool("recompute_check",map())),(n,a)->map(),e->{});
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(0,models.get());
    }
    @Test void hostTerminalPathDoesNotExhaustAnotherTarget()throws Exception{
        Map<String,Object> context=typedContext();context.put("targetObligationIds",Arrays.asList("A","B"));context.put("targetEvidenceRevisions",map("A","v1","B","vb1"));AtomicInteger turns=new AtomicInteger();List<String> visited=new ArrayList<>();
        SdkAgentInvestigator agent=bridge((r,t)->turns.getAndIncrement()==0?response(call("a","submit_candidate_fact",map("targetObligationId","A"))):response(call("b","submit_candidate_fact",map("targetObligationId","B"))));
        Map<String,Object> result=agent.investigate(start(context,tool("submit_candidate_fact",map("targetObligationId",schema()),"targetObligationId")),(n,a)->{String target=(String)a.get("targetObligationId");visited.add(target);if(target.equals("A"))return map("status","REJECTED","semanticWorkItems",Collections.singletonList(map("workKey","work-a","targetObligationId","A","evidenceRevision","v1","terminalForRevision",true,"targetExhaustedForRevision",true)));return map("acceptedFactIds",Collections.singletonList("fact-b"),"computationReceipt",map("kind","HOST_RULE_RECOMPUTE","status","COMPLETED","businessCheckCompleted",false,"targetCheckCompleted",true,"targetEvidenceRevision","global1"),"targetEvidenceRevision","global1");},e->{});
        assertEquals(Arrays.asList("A","B"),visited);assertEquals("COMPLETED",result.get("status"),result.toString());
    }
    @Test void malformedConfirmationCannotSuppressRemainingBusinessWork()throws Exception{
        Map<String,Object> context=typedContext();AtomicInteger turns=new AtomicInteger();SdkAgentInvestigator agent=bridge((r,t)->{turns.incrementAndGet();return response(call("done","recompute_check",map()));});
        Map<String,Object> invalid=receipt(false);invalid.put("pendingConfirmationTargetObligationIds",Collections.singletonList("A"));invalid.put("confirmationTargetProofs",map("A",map("authority","MODEL_SELF_REPORT")));
        Map<String,Object> result=agent.investigate(start(context,tool("recompute_check",map())),(n,a)->invalid,e->{});assertEquals("HOST_CONFIRMATION_RECEIPT_INVALID",result.get("errorCode"));assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void validatedPendingConfirmationEndsWithoutClaimingBusinessPass()throws Exception{
        Map<String,Object> context=typedContext(),pending=receipt(false);pending.put("pendingConfirmationTargetObligationIds",Collections.singletonList("A"));pending.put("confirmationTargetProofs",map("A",map("authority","SOURCE_VALIDATED_PENDING_CONFIRMATION","targetObligationId","A","conditionStatus","NEEDS_CONFIRMATION","gapCodes",Collections.singletonList("INFERENCE_CONFIRMATION_REQUIRED"),"businessProof",false,"targetFingerprint","fingerprint","sourceValidation","CURRENT_DOCUMENT_HASH_VERSION_PAGE_AND_EXACT_QUOTE","claimIds",Collections.singletonList("claim"),"sourceIds",Collections.singletonList("source"))));
        Map<String,Object> result=bridge((r,t)->response(call("done","recompute_check",map()))).investigate(start(context,tool("recompute_check",map())),(n,a)->pending,e->{});
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals("WAIT_CONFIRMATION",result.get("code"));assertEquals(false,result.get("businessCheckCompleted"));assertEquals(Collections.singletonList("A"),result.get("pendingConfirmationTargetObligationIds"));
    }
    @Test void timeoutRevokesHostMutationFenceAndInterruptsRuntime()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1);AtomicBoolean fenceAfterCancel=new AtomicBoolean(true);SdkAgentInvestigator agent=new SdkAgentInvestigator(true,BASE,KEY,"qwen-plus",1,1,null,(r,t)->response(call("read","search_evidence",map())));
        Map<String,Object> result=agent.investigate(start(map(),tool("search_evidence",map())),(n,a)->{entered.countDown();try{Thread.sleep(5000);}catch(InterruptedException stop){fenceAfterCancel.set(SdkAgentInvestigator.currentCallActive());cancelled.countDown();Thread.currentThread().interrupt();}return map();},e->{});
        assertEquals("INVESTIGATION_TIMEOUT",result.get("errorCode"));assertEquals(0,entered.getCount());assertTrue(cancelled.await(1,TimeUnit.SECONDS));assertFalse(fenceAfterCancel.get());
    }
    @Test void modelAndToolPayloadAuditsAreLocalAndRedactCredentials()throws Exception{
        SdkAgentInvestigator agent=bridge((r,t)->response(call("one","search_evidence",map())));java.lang.reflect.Field field=SdkAgentInvestigator.class.getDeclaredField("auditDirectory");field.setAccessible(true);field.set(agent,temp.toString());
        Map<String,Object> result=agent.investigate(start(map("privateText","exact source "+KEY),tool("search_evidence",map())),(n,a)->map("sources",Collections.singletonList(map("id","s1","text","source body")),"authorization",KEY),e->{});
        assertNotEquals("FAILED",result.get("status"),result.toString());boolean input=false,output=false;try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(temp,"*.json")){for(Path path:files){String content=new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);assertFalse(content.contains(KEY));input|=path.toString().endsWith("-input.json");output|=path.toString().endsWith("-output.json");}}assertTrue(input);assertTrue(output);
    }
    private Map<String,Object> typedContext(){return map("workflowPolicy","TARGET_FACTS_V1","targetObligationIds",Collections.singletonList("A"),"serverPlanRevision","plan1","serverPlanFingerprint","fp1","targetEvidenceRevision","global1","targetEvidenceRevisions",map("A","v1"));}
    private Map<String,Object> receipt(boolean complete){return map("targetReceiptAuthority","CURRENT_SERVER_PROOF","serverPlanRevision","plan1","serverPlanFingerprint","fp1","targetEvidenceRevision","global1","targetEvidenceRevisions",map("A","v1"),"completedTargetObligationIds",complete?Collections.singletonList("A"):Collections.emptyList(),"remainingTargetObligationIds",complete?Collections.emptyList():Collections.singletonList("A"),"businessCheckCompleted",complete,"targetCheckCompleted",complete);}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
}

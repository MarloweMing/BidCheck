package cn.bidcheck.verification.agent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SdkAgentInvestigatorTest {
    @TempDir Path temp;
    private static final String BASE="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",KEY="bridge-unit-test-not-a-real-key";
    private static final String HEADER="""
        import json,sys,os,time
        sys.stdin.reconfigure(encoding='utf-8')
        sys.stdout.reconfigure(encoding='utf-8',line_buffering=True)
        start=json.loads(sys.stdin.readline())
        rid=start['requestId']
        def emit(kind,**extra):
            print(json.dumps(dict(type=kind,protocol='bidcheck-agent-1',requestId=rid,**extra),ensure_ascii=False),flush=True)
        def done(status='NO_PROGRESS',**extra):
            emit('result',status=status,code='',sdk='openai-agents',sdkVersion='0.24.0',tracingExportEnabled=False,**extra)
        """;
    private Path python(){
        String configured=System.getProperty("bidcheck.agent.test-python","");if(!configured.isBlank())return Path.of(configured);
        Path here=Path.of("").toAbsolutePath();for(Path root:List.of(here,here.getParent())){
            Path candidate=root.resolve(".local/agent-venv/Scripts/python.exe");if(Files.isRegularFile(candidate))return candidate;
            candidate=root.resolve(".local/agent-venv/bin/python");if(Files.isRegularFile(candidate))return candidate;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(false,"configured test Python required for real process protocol tests");return null;
    }
    private Path script(String body)throws Exception{Path script=temp.resolve("worker-"+UUID.randomUUID()+".py");Files.writeString(script,HEADER+body);return script;}
    private SdkAgentInvestigator bridge(Path script,int timeout,int concurrency){return new SdkAgentInvestigator(true,python().toString(),script.toString(),BASE,KEY,"qwen-plus",timeout,concurrency);}
    private Map<String,Object> start(String id,String... names){List<Map<String,Object>> tools=new ArrayList<>();for(String name:names)tools.add(Map.of("name",name,"description","测试可信工具","parameters",Map.of("type","object","properties",Map.of(),"additionalProperties",false)));
        return Map.of("type","start","protocol","bidcheck-agent-1","requestId",id,"context",Map.of("domain","PERSONNEL_OWNERSHIP","requirement","只读取本次人员材料"),"tools",tools);}
    private Map<String,Object> run(String body,AgentInvestigator.ToolHandler handler)throws Exception{return bridge(script(body),5,1).investigate(start("test-1","search_evidence","submit_candidate_fact","recompute_check"),handler,null);}
    private void code(String expected,Map<String,Object> result){assertEquals("FAILED",result.get("status"),result.toString());assertEquals(expected,result.get("errorCode"),result.toString());}

    @Test void onlyApprovedBailianCompatibleEndpointsCanReceiveCredentials()throws Exception{
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",SdkAgentInvestigator.normalizedEndpoint(BASE));
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1",SdkAgentInvestigator.normalizedEndpoint("https://dashscope-intl.aliyuncs.com:443/compatible-mode/v1/"));
        for(String url:List.of("","http://dashscope.aliyuncs.com/compatible-mode/v1","https://dashscope.aliyuncs.com.evil.test/compatible-mode/v1","https://secret@dashscope.aliyuncs.com/compatible-mode/v1","https://dashscope.aliyuncs.com/compatible-mode/v1?key=secret","https://dashscope.aliyuncs.com:444/compatible-mode/v1","https://dashscope.aliyuncs.com/compatible-mode/%76%31","https://api.openai.com/v1","https://127.0.0.1/compatible-mode/v1"))assertThrows(SdkAgentInvestigator.BridgeFailure.class,()->SdkAgentInvestigator.normalizedEndpoint(url),url);
    }
    @Test void missingConfigAndDisabledStateAreSafeFailures(){
        var disabled=new SdkAgentInvestigator(false,"","",BASE,"","",1,1);assertFalse(disabled.enabled());code("AGENT_DISABLED",disabled.investigate(start("disabled","search_evidence"),(n,a)->Map.of(),null));
        var noKey=new SdkAgentInvestigator(true,"","",BASE,"","qwen-plus",1,1);assertTrue(noKey.enabled());code("MODEL_CONFIGURATION_MISSING",noKey.investigate(start("missing","search_evidence"),(n,a)->Map.of(),null));
    }
    @Test void realJsonlToolRoundTripAcceptsOnlyServerReceiptsAndDropsProse()throws Exception{
        List<String> names=new ArrayList<>();List<Map<String,Object>> events=new ArrayList<>();Path script=script("""
            assert os.environ['BIDCHECK_AGENT_API_KEY']=='bridge-unit-test-not-a-real-key'
            assert os.environ['BIDCHECK_AGENT_BASE_URL']=='https://dashscope.aliyuncs.com/compatible-mode/v1'
            assert os.environ['OPENAI_AGENTS_DISABLE_TRACING']=='1'
            assert os.environ['BIDCHECK_AGENT_TOOL_TIMEOUT_SECONDS']==os.environ['BIDCHECK_AGENT_RUN_TIMEOUT_SECONDS']
            assert 'BIDCHECK_AGENT_ALLOW_TEST_LOCALHOST' not in os.environ
            assert not any('bridge-unit-test-not-a-real-key' in arg for arg in sys.argv)
            emit('event',event='model_started',id='permit-1',modelIndex=1,prompt='secret original text',apiKey='never forward')
            permit=json.loads(sys.stdin.readline())
            assert permit['type']=='event_ack' and permit['id']=='permit-1' and permit['allowed'] is True
            emit('tool_call',id='tool-1',name='search_evidence',arguments={'query':'项目负责人任职表'})
            answer=json.loads(sys.stdin.readline())
            assert answer['id']=='tool-1' and answer['requestId']==rid
            assert answer['result']['source']=='拟在本合同任职'
            emit('tool_call',id='tool-2',name='submit_candidate_fact',arguments={'sourceId':'S1'})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='tool-3',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED',summary='PASS 全部满足',businessDecision='PASS',acceptedFactIds=['invented'],businessCheckCompleted=True,modelCalls=1,inputTokens=20,outputTokens=10)
            """);
        Map<String,Object> result=bridge(script,5,1).investigate(start("roundtrip","search_evidence","submit_candidate_fact","recompute_check"),(name,args)->{
            names.add(name);return switch(name){case "search_evidence"->{assertEquals("项目负责人任职表",args.get("query"));yield Map.of("source","拟在本合同任职");}case "submit_candidate_fact"->Map.of("acceptedFactIds",List.of("verified-fact"));default->Map.of("businessCheckCompleted",true);};
        },events::add);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(List.of("verified-fact"),result.get("acceptedFactIds"));assertEquals(3L,result.get("toolCalls"));
        assertEquals(List.of("search_evidence","submit_candidate_fact","recompute_check"),names);assertEquals(1,events.size());
        assertFalse(result.containsKey("summary"));assertFalse(result.containsKey("businessDecision"));assertFalse(events.get(0).containsKey("prompt"));assertFalse(events.get(0).containsKey("apiKey"));
    }
    @Test void workerCannotInventCompletedBusinessCheck()throws Exception{code("WORKER_RESULT_STATE_MISMATCH",run("done('COMPLETED',businessCheckCompleted=True,acceptedFactIds=['invented'])\n",(n,a)->{fail("no tool was requested");return Map.of();}));}
    @Test void workerCannotInventAcceptedFacts()throws Exception{code("WORKER_RESULT_STATE_MISMATCH",run("done('EVIDENCE_UPDATED',acceptedFactIds=['invented'])\n",(n,a)->Map.of()));}
    @Test void trustedAutomaticRecomputeCrossesRealProcessBoundary()throws Exception{
        var receipt=Map.of("kind","HOST_RULE_RECOMPUTE","status","COMPLETED","targetEvidenceRevision","revision-1","targetCheckCompleted",true,"businessCheckCompleted",false);
        var result=run("""
            emit('tool_call',id='1',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            emit('event',event='host_recomputed',targetCheckCompleted=True,businessCheckCompleted=False)
            done('COMPLETED',targetCheckCompleted=True,businessCheckCompleted=False)
            """,(n,a)->Map.of("status","COMPLETED","acceptedFactIds",List.of("trusted"),"targetEvidenceRevision","revision-1","computationReceipt",receipt));
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(true,result.get("targetCheckCompleted"));assertEquals(false,result.get("businessCheckCompleted"));assertEquals(1,result.get("automaticRecomputations"));
    }
    @Test void layoutRevisionInvalidatesEarlierAutomaticCompletion()throws Exception{
        var receipt=Map.of("kind","HOST_RULE_RECOMPUTE","status","COMPLETED","targetEvidenceRevision","revision-1","targetCheckCompleted",true,"businessCheckCompleted",false);
        Path script=script("""
            emit('tool_call',id='1',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='read_table_record',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED')
            """);
        var result=bridge(script,5,1).investigate(start("revision","search_evidence","read_table_record"),(n,a)->n.equals("search_evidence")?Map.of("status","COMPLETED","targetEvidenceRevision","revision-1","computationReceipt",receipt):Map.of("status","COMPLETED","targetEvidenceRevision","revision-2"),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);
    }
    @Test void stalledInvestigationPreservesOnlyKnownUnfinishedTargets()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("stalled","search_evidence"));request.put("context",Map.of("targetObligationIds",List.of("A","B")));
        var result=bridge(script("""
            emit('event',event='semantic_path_exhausted',path='p1',evidenceRevision='v1',affectedTargetObligationIds=['A','forged'],failureFamily='TYPED_SOURCE_EXTRACTION',remainingRepairActions=['READ_COMPLETE_RECORD','private document body'])
            emit('result',status='FAILED',code='AGENT_STALLED_WITH_REMAINING_TARGETS',tracingExportEnabled=False,blockedTargetObligationIds=['A','forged'],remainingTargetObligationIds=['B','forged'],completedTargetObligationIds=['forged'])
            """),5,1).investigate(request,(n,a)->Map.of(),null);
        code("AGENT_STALLED_WITH_REMAINING_TARGETS",result);assertEquals(List.of("A"),result.get("blockedTargetObligationIds"));assertEquals(List.of("B"),result.get("remainingTargetObligationIds"));
        assertEquals(List.of(),result.get("completedTargetObligationIds"));assertEquals(true,result.get("investigationIncomplete"));
    }
    @Test void boundedCorrectableToolErrorsRemainEvidencePendingRatherThanSystemFailure()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("pending-schema","search_evidence"));request.put("context",Map.of("targetObligationIds",List.of("A")));
        var result=bridge(script("emit('result',status='NO_PROGRESS',code='EVIDENCE_PENDING',tracingExportEnabled=False,correctableToolRejectionCount=2,remainingTargetObligationIds=['A'])\n"),5,1).investigate(request,(n,a)->Map.of(),null);
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals("EVIDENCE_PENDING",result.get("code"));assertEquals("",result.get("errorCode"));assertEquals(List.of("A"),result.get("remainingTargetObligationIds"));
    }
    @Test void compactContextMetricsAreAllowedWithoutExportingDocumentBodies()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        var result=bridge(script("""
            emit('event',event='context_compacted',inputChars=1200,removedChars=42000,retainedToolBatches=2,disclosedSourceCount=8,sourceText='private document',contracts={'person':'private name'})
            done(contextCompactions=3,contextInputChars=3600,contextRemovedChars=80000)
            """),5,1).investigate(start("compact","read_section"),(n,a)->Map.of(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(3L,result.get("contextCompactions"));
        assertEquals(80000L,result.get("contextRemovedChars"));assertEquals(1,events.size());
        assertEquals(42000L,events.get(0).get("removedChars"));assertFalse(events.get(0).containsKey("sourceText"));assertFalse(events.get(0).containsKey("contracts"));
    }
    @Test void semanticStopMetadataKeepsOnlyBoundedCodesAndNoSourceText()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        var result=bridge(script("""
            emit('event',event='semantic_path_exhausted',path='work-key',evidenceRevision='revision',failureFamily='TYPED_SOURCE_EXTRACTION',remainingRepairActions=['READ_COMPLETE_RECORD','private document body'],sourceText='private document body')
            done()
            """),5,1).investigate(start("semantic-log","read_section"),(n,a)->Map.of(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(1,events.size());
        assertEquals("TYPED_SOURCE_EXTRACTION",events.get(0).get("failureFamily"));assertEquals(List.of("READ_COMPLETE_RECORD"),events.get(0).get("remainingRepairActions"));
        assertFalse(events.get(0).containsKey("sourceText"));
    }
    @Test void schemaCorrectionEventKeepsOnlyActionableValidationCoordinates()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        var result=bridge(script("""
            emit('event',event='tool_arguments_rejected',tool='search_evidence',validatorKeyword='required',fieldPath='/query',correctable=True,attemptForTargetGap=1,maxAttemptsForTargetGap=2,rejectedValue='private document')
            done(correctableToolRejectionCount=1)
            """),5,1).investigate(start("schema-event","search_evidence"),(n,a)->Map.of(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(1L,result.get("correctableToolRejectionCount"));assertEquals(1,events.size());
        assertEquals("search_evidence",events.get(0).get("tool"));assertEquals("required",events.get(0).get("validatorKeyword"));assertEquals("/query",events.get(0).get("fieldPath"));assertEquals(true,events.get(0).get("correctable"));assertFalse(events.get(0).containsKey("rejectedValue"));
    }
    @Test void targetWorkflowProtocolFailuresRemainSystemFailures()throws Exception{
        for(String error:List.of("TARGET_CONTRACT_MISSING","TARGET_WORK_RECEIPT_MISSING","TARGET_WORK_RECEIPT_INVALID","CANDIDATE_TARGET_REQUIRED")){
            var result=run("emit('result',status='FAILED',code='"+error+"',tracingExportEnabled=False)\n",(n,a)->Map.of());
            code(error,result);assertEquals(false,result.get("targetCheckCompleted"));
        }
    }
    @Test void newFactsInvalidateEarlierCompletionReceipt()throws Exception{
        Map<String,Object> result=run("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='submit_candidate_fact',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED')
            """,(name,args)->name.equals("recompute_check")?Map.of("businessCheckCompleted",true):Map.of("acceptedFactIds",List.of("new")));
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(List.of("new"),result.get("acceptedFactIds"));
    }
    @Test void trustedNativeSearchAndSectionReceiptsCountAsEvidenceProgress()throws Exception{
        Path worker=script("""
            emit('tool_call',id='1',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='read_section',arguments={})
            json.loads(sys.stdin.readline())
            done('EVIDENCE_UPDATED')
            """);
        Map<String,Object> result=bridge(worker,5,1).investigate(start("native-progress","search_evidence","read_section"),(name,args)->Map.of("acceptedFactIds",List.of(name+"-fact")),null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(List.of("search_evidence-fact","read_section-fact"),result.get("acceptedFactIds"));
    }
    @Test void newlyAdoptedNativeFactsInvalidateThePreviousRecomputeReceipt()throws Exception{
        Map<String,Object> result=run("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED')
            """,(name,args)->name.equals("recompute_check")?Map.of("businessCheckCompleted",true):Map.of("acceptedFactIds",List.of("native-new")));
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(List.of("native-new"),result.get("acceptedFactIds"));
    }
    @Test void otherReadToolsCannotClaimAcceptedFacts()throws Exception{
        Map<String,Object> result=bridge(script("emit('tool_call',id='1',name='read_table_record',arguments={})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED')\n"),5,1)
            .investigate(start("not-adopted","read_table_record"),(n,a)->Map.of("acceptedFactIds",List.of("not-accepted")),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(List.of(),result.get("acceptedFactIds"));
    }
    @Test void failedNativeReadCannotClaimAcceptedFacts()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone()\n",
            (n,a)->Map.of("status","FAILED","acceptedFactIds",List.of("not-accepted")));
        code("TOOL_OPERATION_FAILED",result);assertEquals(List.of(),result.get("acceptedFactIds"));
    }
    @Test void validationRejectionsDoNotPoisonPartialEvidence()throws Exception{
        for(String error:List.of("SOURCE_NOT_DISCLOSED","ROW_OFFSET_INVALID","FACT_OUTSIDE_TARGET")){
            AtomicInteger count=new AtomicInteger();
            Map<String,Object> result=run("""
                emit('tool_call',id='1',name='search_evidence',arguments={'query':'initial'})
                json.loads(sys.stdin.readline())
                emit('tool_call',id='2',name='submit_candidate_fact',arguments={})
                json.loads(sys.stdin.readline())
                done('EVIDENCE_UPDATED',failedToolCount=999)
                """,(name,args)->count.incrementAndGet()==1?Map.of("status","FAILED","error",error):Map.of("acceptedFactIds",List.of("real-fact")));
            assertEquals("EVIDENCE_UPDATED",result.get("status"),result.toString());assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("validationRejectionCount"));
        }
    }
    @Test void successfulRetryClearsOnlyMatchingTechnicalFailure()throws Exception{
        AtomicInteger count=new AtomicInteger();
        Map<String,Object> result=run("""
            emit('tool_call',id='1',name='search_evidence',arguments={'query':' current role '})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='search_evidence',arguments={'query':'current  role'})
            json.loads(sys.stdin.readline())
            emit('result',status='FAILED',code='TOOL_OPERATION_FAILED',tracingExportEnabled=False)
            """,(name,args)->count.incrementAndGet()==1?Map.of("status","FAILED","error","DATABASE_UNAVAILABLE"):Map.of("status","COMPLETED"));
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals("",result.get("errorCode"));assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("recoveredToolCount"));
    }
    @Test void differentOperationOrPendingReceiptCannotHideTechnicalFailure()throws Exception{
        for(boolean pending:List.of(false,true)){
            AtomicInteger count=new AtomicInteger();
            String nextQuery=pending?"first":"other";
            Map<String,Object> result=run("""
                emit('tool_call',id='1',name='search_evidence',arguments={'query':'first'})
                json.loads(sys.stdin.readline())
                emit('tool_call',id='2',name='search_evidence',arguments={'query':'NEXT_QUERY'})
                json.loads(sys.stdin.readline())
                done(failedToolCount=0)
                """.replace("NEXT_QUERY",nextQuery),(name,args)->count.incrementAndGet()==1?Map.of("status","REJECTED","error","DATABASE_UNAVAILABLE","recoverable",true):Map.of("status",pending?"PENDING":"COMPLETED"));
            code("TOOL_OPERATION_FAILED",result);assertEquals(1,result.get("failedToolCount"));assertEquals(0,result.get("recoveredToolCount"));
        }
    }
    @Test void targetCompletionCanStopInvestigationWithoutClaimingWholeBusinessCompletion()throws Exception{
        Map<String,Object> result=run("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED',businessCheckCompleted=True,targetCheckCompleted=True)
            """,(name,args)->Map.of("targetCheckCompleted",true,"businessCheckCompleted",false));
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(true,result.get("targetCheckCompleted"));assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void workerAndNonRecomputeToolsCannotInventTargetCompletion()throws Exception{
        code("WORKER_RESULT_STATE_MISMATCH",run("done('COMPLETED',targetCheckCompleted=True)\n",(n,a)->Map.of()));
        code("WORKER_RESULT_STATE_MISMATCH",run("""
            emit('tool_call',id='1',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED',targetCheckCompleted=True)
            """,(n,a)->Map.of("targetCheckCompleted",true)));
    }
    @Test void newlyAcceptedFactsInvalidateTargetCompletion()throws Exception{
        Map<String,Object> result=run("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            done('COMPLETED')
            """,(name,args)->name.equals("recompute_check")?Map.of("targetCheckCompleted",true,"businessCheckCompleted",false):Map.of("acceptedFactIds",List.of("new")));
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(List.of("new"),result.get("acceptedFactIds"));
    }
    @Test void workerFailurePreservesAlreadyAcceptedFacts()throws Exception{
        Map<String,Object> result=run("""
            emit('tool_call',id='1',name='submit_candidate_fact',arguments={})
            json.loads(sys.stdin.readline())
            emit('result',status='FAILED',code='MODEL_OR_SDK_ERROR',summary='sensitive message',tracingExportEnabled=False)
            """,(n,a)->Map.of("acceptedFactIds",List.of("kept")));
        code("MODEL_OR_SDK_ERROR",result);assertEquals(List.of("kept"),result.get("acceptedFactIds"));assertFalse(result.containsKey("summary"));
    }
    @Test void unknownWorkerErrorCodeCannotExfiltrateText()throws Exception{
        Map<String,Object> result=run("emit('result',status='FAILED',code='api key and raw document text',tracingExportEnabled=False)\n",(n,a)->Map.of());code("WORKER_REPORTED_FAILURE",result);assertFalse(result.toString().contains("raw document"));
    }
    @Test void crossRequestMessagesAreRejectedBeforeToolExecution()throws Exception{
        AtomicBoolean called=new AtomicBoolean();code("PROTOCOL_REQUEST_ID_MISMATCH",run("rid='other-request'\nemit('tool_call',id='1',name='search_evidence',arguments={})\n",(n,a)->{called.set(true);return Map.of();}));assertFalse(called.get());
    }
    @Test void unofferedToolsAndDuplicateCallIdsCannotReachHandler()throws Exception{
        AtomicInteger calls=new AtomicInteger();Map<String,Object> unoffered=bridge(script("emit('tool_call',id='1',name='query_external',arguments={})\n"),5,1).investigate(start("restricted","search_evidence"),(n,a)->{calls.incrementAndGet();return Map.of();},null);code("TOOL_NOT_ALLOWED",unoffered);assertEquals(0,calls.get());
        Map<String,Object> duplicated=run("""
            emit('tool_call',id='same',name='search_evidence',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='same',name='search_evidence',arguments={})
            """,(n,a)->{calls.incrementAndGet();return Map.of();});code("TOOL_CALL_ID_INVALID",duplicated);assertEquals(1,calls.get());
    }
    @Test void largeArgumentsAndDuplicateJsonKeysAreRejected()throws Exception{
        code("TOOL_ARGUMENTS_TOO_LARGE",run("emit('tool_call',id='1',name='search_evidence',arguments={'query':'x'*70000})\n",(n,a)->{fail("oversized arguments");return Map.of();}));
        code("PROTOCOL_READ_FAILED",run("print('{\"type\":\"event\",\"type\":\"tool_call\"}',flush=True)\n",(n,a)->Map.of()));
    }
    @Test void malformedUtf8AndOversizedFramesAreSafeFailures()throws Exception{
        code("PROTOCOL_READ_FAILED",run("sys.stdout.buffer.write(b'\\xff\\n');sys.stdout.buffer.flush()\n",(n,a)->Map.of()));
        code("PROTOCOL_MESSAGE_TOO_LARGE",run("print('x'*(4*1024*1024+1),flush=True)\n",(n,a)->Map.of()));
    }
    @Test void stderrNeverBecomesProtocolOrReturnedFailureText()throws Exception{
        Map<String,Object> result=run("print('SECRET credential traceback',file=sys.stderr,flush=True)\nsys.exit(3)\n",(n,a)->Map.of());code("WORKER_DISCONNECTED",result);assertFalse(result.toString().contains("SECRET"));
    }
    @Test void toolExceptionsRemainSystemFailuresWithoutExceptionText()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={})\ntime.sleep(10)\n",(n,a)->{throw new IllegalArgumentException("secret original materials");});code("TOOL_HANDLER_FAILED",result);assertFalse(result.toString().contains("secret"));
    }
    @Test void aWorkerTimeoutAndBlockedHandlerAreTerminated()throws Exception{
        Path pidFile=temp.resolve("pid");String quoted=pidFile.toString().replace("\\","\\\\").replace("'","\\'");
        Path script=script("open('"+quoted+"','w').write(str(os.getpid()))\ntime.sleep(30)\n");
        long start=System.nanoTime();Map<String,Object> result=bridge(script,1,1).investigate(start("timeout","search_evidence"),(n,a)->Map.of(),null);code("INVESTIGATION_TIMEOUT",result);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<4000);
        long pid=Long.parseLong(Files.readString(pidFile));for(int i=0;i<20&&ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);i++)Thread.sleep(25);assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        AtomicBoolean interrupted=new AtomicBoolean();Map<String,Object> handler=bridge(script("emit('tool_call',id='1',name='search_evidence',arguments={})\ntime.sleep(30)\n"),1,1).investigate(start("handler-timeout","search_evidence"),(n,a)->{try{Thread.sleep(30000);}catch(InterruptedException e){interrupted.set(true);Thread.currentThread().interrupt();}return Map.of();},null);code("INVESTIGATION_TIMEOUT",handler);for(int i=0;i<20&&!interrupted.get();i++)Thread.sleep(25);assertTrue(interrupted.get());
    }
    @Test void callerCancellationStopsInvestigationAndRestoresInterruptFlag()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicReference<Map<String,Object>> result=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();SdkAgentInvestigator bridge=bridge(script("emit('event',event='model_started',id='permit-1',modelIndex=1)\ntime.sleep(30)\n"),20,1);
        Thread caller=new Thread(()->{result.set(bridge.investigate(start("cancel","search_evidence"),(n,a)->Map.of(),e->entered.countDown()));flag.set(Thread.currentThread().isInterrupted());});caller.start();assertTrue(entered.await(5,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);assertFalse(caller.isAlive());code("HOST_CANCELLED",result.get());assertTrue(flag.get());
    }
    @Test void expiredCallbackFenceStaysInvalidEvenIfAToolClearsItsInterrupt()throws Exception{
        CountDownLatch returned=new CountDownLatch(1);AtomicBoolean afterTimeout=new AtomicBoolean(true);
        Path marker=temp.resolve("late-tool-result");String escaped=marker.toString().replace("\\","\\\\").replace("'","\\'");
        Map<String,Object> result=bridge(script("emit('tool_call',id='1',name='submit_candidate_fact',arguments={})\nresponse=json.loads(sys.stdin.readline())\nopen('"+escaped+"','w').write('late result')\ndone('EVIDENCE_UPDATED')\n"),1,1).investigate(start("stale-tool","submit_candidate_fact"),(n,a)->{
            assertTrue(SdkAgentInvestigator.currentCallActive());
            try{Thread.sleep(30000);}catch(InterruptedException ignored){Thread.interrupted();}
            afterTimeout.set(SdkAgentInvestigator.currentCallActive());returned.countDown();return Map.of("acceptedFactIds",List.of("late-fact"));
        },null);
        code("INVESTIGATION_TIMEOUT",result);assertTrue(returned.await(3,TimeUnit.SECONDS));assertFalse(afterTimeout.get(),"a canceled tool must not publish after clearing its thread interruption");
        assertEquals(List.of(),result.get("acceptedFactIds"));assertFalse(Files.exists(marker),"an expired callback result must not reach the worker");
    }
    @Test void modelRequestWaitsForSuccessfulHostPermitAcknowledgement()throws Exception{
        Path marker=temp.resolve("authorized");String escaped=marker.toString().replace("\\","\\\\").replace("'","\\'");
        Path worker=script("emit('event',event='model_started',id='permit-1',modelIndex=1)\nack=json.loads(sys.stdin.readline())\nassert ack['allowed'] is True and ack['id']=='permit-1'\nopen('"+escaped+"','w').write('allowed')\nemit('event',event='model_request_authorized',id='permit-1',modelIndex=1)\ndone()\n");
        CountDownLatch entered=new CountDownLatch(1),allow=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();
        try{Future<Map<String,Object>> result=executor.submit(()->bridge(worker,5,1).investigate(start("permit","search_evidence"),(n,a)->Map.of(),event->{if(event.get("event").equals("model_started")){entered.countDown();try{allow.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("cancel");}}}));
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertFalse(Files.exists(marker));allow.countDown();assertEquals("NO_PROGRESS",result.get(5,TimeUnit.SECONDS).get("status"));assertEquals("allowed",Files.readString(marker));
        }finally{allow.countDown();executor.shutdownNow();}
    }
    @Test void deniedPermitCannotReachProviderRequest()throws Exception{
        Path marker=temp.resolve("must-not-authorize");String escaped=marker.toString().replace("\\","\\\\").replace("'","\\'");
        Path worker=script("emit('event',event='model_started',id='permit-1',modelIndex=1)\nack=json.loads(sys.stdin.readline())\nif ack.get('allowed') is True: open('"+escaped+"','w').write('bad')\ndone()\n");
        Map<String,Object> result=bridge(worker,5,1).investigate(start("deny","search_evidence"),(n,a)->Map.of(),event->{throw new IllegalStateException("lease not owned; sensitive detail");});
        code("MODEL_REQUEST_DENIED",result);assertFalse(Files.exists(marker));assertFalse(result.toString().contains("sensitive"));
    }
    @Test void malformedPermitEventCannotCallHostOrAuthorizeModel()throws Exception{
        AtomicBoolean callback=new AtomicBoolean();Map<String,Object> result=bridge(script("emit('event',event='model_started',modelIndex=1)\ntime.sleep(30)\n"),5,1).investigate(start("bad-permit","search_evidence"),(n,a)->Map.of(),event->callback.set(true));
        code("MODEL_PERMISSION_PROTOCOL_ERROR",result);assertFalse(callback.get());
    }
    @Test void missingHostPermitCallbackFailsClosed()throws Exception{
        code("MODEL_REQUEST_DENIED",run("emit('event',event='model_started',id='permit-1',modelIndex=1)\ntime.sleep(30)\n",(n,a)->Map.of()));
    }
    @Test void configuredConcurrencySerializesWorkerLaunchesAndReleasesSlots()throws Exception{
        SdkAgentInvestigator bridge=bridge(script("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone()\n"),10,1);
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();ExecutorService executor=Executors.newFixedThreadPool(3);
        try{List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<3;i++){String id="parallel-"+i;futures.add(executor.submit(()->bridge.investigate(start(id,"search_evidence"),(n,a)->{int current=active.incrementAndGet();max.accumulateAndGet(current,Math::max);try{Thread.sleep(80);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{active.decrementAndGet();}return Map.of();},null)));}for(var future:futures)assertEquals("NO_PROGRESS",future.get(10,TimeUnit.SECONDS).get("status"));assertEquals(1,max.get());}finally{executor.shutdownNow();}
    }
    @Test void identityChangesWithWorkerOrModelButNeverWithApiKey()throws Exception{
        Path script=script("done()\n");SdkAgentInvestigator first=bridge(script,5,1),otherKey=new SdkAgentInvestigator(true,python().toString(),script.toString(),BASE,"a-different-secret","qwen-plus",5,1);
        assertEquals(first.identity(),otherKey.identity());assertFalse(first.identity().contains(KEY));
        String beforeLock=first.identity();Files.writeString(script.getParent().resolve("requirements.lock"),"openai-agents==0.24.0\n");assertNotEquals(beforeLock,first.identity());
        String previous=first.identity();Files.writeString(script,"\n# changed worker\n",StandardOpenOption.APPEND);assertNotEquals(previous,first.identity());
        assertNotEquals(first.identity(),new SdkAgentInvestigator(true,python().toString(),script.toString(),BASE,KEY,"different-model",5,1).identity());
    }

    private Map<String,Object> reviewStart(String id,String... tools){
        Map<String,Object> request=new LinkedHashMap<>(start(id,tools));
        request.put("context",Map.of("workflowPolicy","AGENT_LED_REVIEW_V1","targetObligationIds",List.of(),"originalRequirement","完整原始条款"));return request;
    }
    private Map<String,Object> approvedPlan(){return Map.of("status","COMPLETED","planReceipt",Map.of("approved",true,"planRevision","plan-1","targetObligationIds",List.of("O1")),"targetEvidenceRevisions",Map.of("O1","o1-v1"));}
    private Map<String,Object> reviewCompletion(String revision){return Map.of("status","COMPLETED","targetEvidenceRevision","global-v2","targetEvidenceRevisions",Map.of("O1","o1-v2"),
        "computationReceipt",Map.of("kind","HOST_RULE_RECOMPUTE","status","COMPLETED","planRevision",revision,"targetEvidenceRevision","global-v2","targetCheckCompleted",true,"businessCheckCompleted",true));}
    @Test void reviewPlanAndJudgmentUseDynamicTargetsAndOnlyTrustedFinalReceipts()throws Exception{
        AtomicInteger calls=new AtomicInteger();List<Map<String,Object>> events=new ArrayList<>();
        var result=bridge(script("""
            emit('tool_call',id='1',name='submit_review_plan',arguments={'conditions':[{'obligationId':'O1'}]})
            json.loads(sys.stdin.readline())
            emit('event',event='review_plan_approved',planRevision='plan-1',targetCount=1)
            emit('tool_call',id='2',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1','status':'PASS'})
            json.loads(sys.stdin.readline())
            done('COMPLETED',businessDecision='PASS',completedTargetObligationIds=['invented'])
            """),5,1).investigate(reviewStart("review-roundtrip","submit_review_plan","submit_review_judgment"),(name,args)->{calls.incrementAndGet();return name.equals("submit_review_plan")?approvedPlan():reviewCompletion("plan-1");},events::add);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,calls.get());assertEquals(List.of("O1"),result.get("completedTargetObligationIds"));
        assertEquals("plan-1",result.get("approvedPlanRevision"));assertEquals("AGENT_LED_REVIEW_V1",result.get("workflowPolicy"));assertFalse(result.containsKey("businessDecision"));
        assertEquals(1L,events.get(0).get("targetCount"));
    }
    @Test void reviewToolsAreRejectedInLegacyMode()throws Exception{
        AtomicBoolean called=new AtomicBoolean();var result=bridge(script("done()\n"),5,1).investigate(start("legacy-review","submit_review_plan"),(n,a)->{called.set(true);return approvedPlan();},null);
        code("TOOL_NOT_ALLOWED",result);assertFalse(called.get());
    }
    @Test void approvingAReviewPlanDoesNotApproveTheBid()throws Exception{
        Map<String,Object> approval=new LinkedHashMap<>(approvedPlan());approval.putAll(reviewCompletion("plan-1"));approval.put("acceptedFactIds",List.of("invented"));
        var result=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n"),5,1)
            .investigate(reviewStart("plan-not-verdict","submit_review_plan"),(n,a)->approval,null);
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(List.of(),result.get("acceptedFactIds"));
    }
    @Test void stalePlanReceiptCannotCompleteCurrentReview()throws Exception{
        var result=bridge(script("""
            emit('tool_call',id='1',name='submit_review_plan',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1','status':'PASS'})
            json.loads(sys.stdin.readline())
            done('COMPLETED')
            """),5,1).investigate(reviewStart("stale-review","submit_review_plan","submit_review_judgment"),(n,a)->n.equals("submit_review_plan")?approvedPlan():reviewCompletion("old-plan"),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);
    }
    @Test void reviewRequiresPlanAndMatchingTargetBeforeHostMutation()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        var noPlan=bridge(script("emit('tool_call',id='1',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1'})\n"),5,1)
            .investigate(reviewStart("no-plan","submit_review_judgment"),(n,a)->{calls.incrementAndGet();return Map.of();},null);
        code("REVIEW_PLAN_REQUIRED",noPlan);assertEquals(0,calls.get());
        for(String target:List.of("O1","foreign")){
            String revision=target.equals("O1")?"wrong-plan":"plan-1";calls.set(0);
            var invalid=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_review_judgment',arguments={'targetObligationId':'"+target+"','planRevision':'"+revision+"'})\n"),5,1)
                .investigate(reviewStart("invalid-target","submit_review_plan","submit_review_judgment"),(n,a)->{calls.incrementAndGet();return approvedPlan();},null);
            code(target.equals("O1")?"REVIEW_PLAN_REVISION_MISMATCH":"CANDIDATE_TARGET_REQUIRED",invalid);assertEquals(1,calls.get());
        }
    }
    @Test void unrelatedTargetRevisionAndFactDoNotDiscardClosedTargetReceipt()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("scoped-close","recompute_check","read_section"));
        request.put("context",Map.of("workflowPolicy","TARGET_FACTS_V1","targetObligationIds",List.of("A","B")));
        var result=bridge(script("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='read_section',arguments={'sourceId':'B-page'})
            json.loads(sys.stdin.readline())
            done('EVIDENCE_UPDATED')
            """),5,1).investigate(request,(name,args)->name.equals("recompute_check")?
                Map.of("status","COMPLETED","targetEvidenceRevisions",Map.of("A","a1","B","b1"),"obligations",List.of(Map.of("id","A","status","PASS","gaps",List.of()))):
                Map.of("status","COMPLETED","acceptedFactIds",List.of("B-fact"),"targetEvidenceRevision","global-new","targetEvidenceRevisions",Map.of("A","a1","B","b2")),null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(List.of("A"),result.get("completedTargetObligationIds"));assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void relatedTargetRevisionReopensItsOwnClosedReceipt()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("scoped-reopen","recompute_check","read_section","submit_candidate_fact"));
        request.put("context",Map.of("workflowPolicy","TARGET_FACTS_V1","targetObligationIds",List.of("A","B")));
        AtomicInteger calls=new AtomicInteger();var result=bridge(script("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='read_section',arguments={'sourceId':'changed-A'})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='3',name='submit_candidate_fact',arguments={'targetObligationId':'A'})
            json.loads(sys.stdin.readline())
            done('EVIDENCE_UPDATED')
            """),5,1).investigate(request,(name,args)->{
                calls.incrementAndGet();return switch(name){
                    case "recompute_check"->Map.of("status","COMPLETED","targetEvidenceRevisions",Map.of("A","a1","B","b1"),"obligations",List.of(Map.of("id","A","status","PASS","gaps",List.of())));
                    case "read_section"->Map.of("status","COMPLETED","targetEvidenceRevisions",Map.of("A","a2","B","b1"));
                    default->Map.of("status","COMPLETED","acceptedFactIds",List.of("new-A"));};},null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(3,calls.get());assertEquals(List.of(),result.get("completedTargetObligationIds"));
    }
    @Test void blockedTargetResubmissionCannotReachHostAgain()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("blocked-gate","submit_candidate_fact"));request.put("context",Map.of("workflowPolicy","TARGET_FACTS_V1","targetObligationIds",List.of("A","B")));
        AtomicInteger calls=new AtomicInteger();var result=bridge(script("""
            emit('tool_call',id='1',name='submit_candidate_fact',arguments={'targetObligationId':'A'})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='submit_candidate_fact',arguments={'targetObligationId':'A','field':'different-alias'})
            """),5,1).investigate(request,(n,a)->{calls.incrementAndGet();return Map.of("status","REJECTED","targetEvidenceRevisions",Map.of("A","a1","B","b1"),
                "semanticWorkItems",List.of(Map.of("targetObligationId","A","evidenceRevision","a1","terminalForRevision",true,"targetExhaustedForRevision",true)));},null);
        code("TARGET_STATE_UNCHANGED",result);assertEquals(1,calls.get());
    }
    @Test void correctedReviewPlanRejectionDoesNotRemainATechnicalFailure()throws Exception{
        AtomicInteger calls=new AtomicInteger();var result=bridge(script("""
            emit('tool_call',id='1',name='submit_review_plan',arguments={'coverageExplanation':'first'})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='submit_review_plan',arguments={'coverageExplanation':'corrected'})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='3',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1'})
            json.loads(sys.stdin.readline())
            done('COMPLETED')
            """),5,1).investigate(reviewStart("rejected-plan-repair","submit_review_plan","submit_review_judgment"),(n,a)->{
                int index=calls.incrementAndGet();return index==1?Map.of("status","REJECTED","error","PLAN_COVERAGE_REJECTED"):index==2?approvedPlan():reviewCompletion("plan-1");},null);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("validationRejectionCount"));
    }
    @Test void reviewReadToolsRequireApprovedCurrentTargetsBeforeDispatch()throws Exception{
        for(String tool:List.of("search_evidence","read_section","read_table_record")){
            AtomicInteger calls=new AtomicInteger();
            var noPlan=bridge(script("emit('tool_call',id='1',name='"+tool+"',arguments={'targetObligationId':'O1','sourceId':'S1'})\n"),5,1)
                .investigate(reviewStart("read-without-plan",tool),(n,a)->{calls.incrementAndGet();return Map.of();},null);
            code("REVIEW_PLAN_REQUIRED",noPlan);assertEquals(0,calls.get());
            var wrongTarget=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='"+tool+"',arguments={'targetObligationId':'foreign','sourceId':'S1'})\n"),5,1)
                .investigate(reviewStart("read-foreign-target","submit_review_plan",tool),(n,a)->{calls.incrementAndGet();return approvedPlan();},null);
            code("CANDIDATE_TARGET_REQUIRED",wrongTarget);assertEquals(1,calls.get());
        }
    }

    private Map<String,Object> targetStart(String id,String... tools){
        Map<String,Object> request=new LinkedHashMap<>(start(id,tools));
        request.put("context",Map.of("workflowPolicy","TARGET_FACTS_V1","targetObligationIds",List.of("A","B","C"),
            "serverPlanRevision","rule-1","serverPlanFingerprint","frozen-plan","targetEvidenceRevision","global-1",
            "targetEvidenceRevisions",Map.of("A","A-1","B","B-1","C","C-1")));return request;
    }
    private Map<String,Object> canonicalReceipt(List<String> done,List<String> remaining){
        Map<String,Object> receipt=new LinkedHashMap<>();
        receipt.putAll(Map.of("status","COMPLETED","targetReceiptAuthority","CURRENT_SERVER_PROOF","completedTargetObligationIds",done,
            "remainingTargetObligationIds",remaining,"serverPlanRevision","rule-1","serverPlanFingerprint","frozen-plan"));
        receipt.putAll(Map.of("targetEvidenceRevision","global-1","targetEvidenceRevisions",Map.of("A","A-1","B","B-1","C","C-1"),
            "businessCheckCompleted",false,"targetCheckCompleted",remaining.isEmpty(),"obligations",remaining.stream().map(id->Map.of("id",id,"status","UNDETERMINED","gaps",List.of(Map.of("code","MISSING")))).toList()));
        return receipt;
    }
    @SuppressWarnings("unchecked")
    @Test void targetProgressEventsAndLedgerCrossBridgeWithoutGrantingWorkerProofAuthority()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        var result=bridge(script("""
            emit('event',event='no_evidence_progress',eventId=rid+':1',targetObligationId='A',operationKey='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='A-1',attemptForTargetGap=1,reason='NO_NEW_EVIDENCE',rawValue='private original text')
            emit('event',event='target_navigation_progress',eventId=rid+':2',targetId='B',newSourceCount=2,newTableRecord=True,acceptedFactDelta=0,closedTargetDelta=0,noEvidenceProgressRounds=1)
            emit('event',event='target_proof_progress',eventId=rid+':3',targetObligationId='B',stateAfter='PROVEN',firstFailureEventId=rid+':1',acceptedFactDelta=99,closedTargetDelta=1)
            emit('event',event='target_tool_failed',eventId=rid+':4',targetId='forged',rejectionCode='HOST_TOOL_FAILED',operationKey='a'*64)
            emit('event',event='investigation_failed',eventId=rid+':5',rejectionCode='HOST_TARGET_RECEIPT_INVALID',stage='WORKER_EXECUTION')
            row=dict(targetObligationId='A',progressScope='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='A-1',state='NO_MORE_EVIDENCE',visited=True,noEvidenceProgressRounds=2,maxNoEvidenceProgressRounds=2,acceptedFactCount=0,closedTargetCount=0,navigationCount=1,firstFailureEventId=rid+':1',rejectionCode='NO_NEW_EVIDENCE',resumeTrigger='RELEVANT_RULE_OR_EVIDENCE_CHANGED',rawValue='private original text')
            forged=dict(row,targetObligationId='forged',firstFailureEventId='private original text')
            fake_proof=dict(row,targetObligationId='B',relevantEvidenceRevision='B-1',state='PROVEN',closedTargetCount=9)
            done(firstFailureEventId=rid+':1',targetProgressLedger=[row,forged,fake_proof],exhaustedTargetObligationIds=['A','forged'],remainingTargetObligationIds=['C','forged'],completedTargetObligationIds=['B'],businessUnclosedTargetObligationIds=[])
            """),5,1).investigate(targetStart("ledger-roundtrip","read_section"),(n,a)->Map.of(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(5,events.size());
        assertEquals("ledger-roundtrip:4",result.get("firstFailureEventId")); // ordinary no-evidence progress is not a failure
        assertEquals(List.of("A"),result.get("exhaustedTargetObligationIds"));assertEquals(List.of(),result.get("completedTargetObligationIds"));
        assertEquals(List.of("A","B","C"),result.get("businessUnclosedTargetObligationIds"));
        List<Map<String,Object>> ledger=(List<Map<String,Object>>)result.get("targetProgressLedger");assertEquals(2,ledger.size());
        assertEquals("NO_MORE_EVIDENCE",ledger.get(0).get("state"));assertEquals(false,ledger.get(0).get("businessProof"));
        assertEquals("RUNNING",ledger.get(1).get("state"));assertEquals(0L,ledger.get(1).get("closedTargetCount"));
        assertEquals("RUNNING",events.get(2).get("stateAfter"));assertFalse(events.get(3).containsKey("targetId"));
        assertFalse(result.toString().contains("private original text"));assertFalse(events.toString().contains("private original text"));
        assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void eventReferencesMustBeObservedOrExplicitlyInheritedAndNeverContainProse()throws Exception{
        var result=bridge(script("done(firstFailureEventId=rid+':999',targetProgressLedger=[])\n"),5,1)
            .investigate(targetStart("unobserved-event","search_evidence"),(n,a)->Map.of(),null);
        assertFalse(result.containsKey("firstFailureEventId"));
        List<Map<String,Object>> events=new ArrayList<>();
        var rejected=bridge(script("emit('event',event='target_tool_failed',eventId='private original text',firstFailureEventId='other-request:1',targetId='forged',rejectionCode='private original text')\ndone(firstFailureEventId='private original text')\n"),5,1)
            .investigate(targetStart("event-redaction","search_evidence"),(n,a)->Map.of(),events::add);
        assertFalse(rejected.containsKey("firstFailureEventId"));assertFalse(events.get(0).containsKey("eventId"));assertFalse(events.get(0).containsKey("rejectionCode"));
        Map<String,Object> request=targetStart("inherited-event","search_evidence");
        Map<String,Object> context=new LinkedHashMap<>((Map<String,Object>)request.get("context"));
        context.put("targetProgressLedger",List.of(Map.of("targetObligationId","A","firstFailureEventId","prior-request:12")));request.put("context",context);
        var inherited=bridge(script("done(firstFailureEventId='prior-request:12')\n"),5,1).investigate(request,(n,a)->Map.of(),null);
        assertEquals("prior-request:12",inherited.get("firstFailureEventId"));
    }
    @Test void diagnosticLedgerHasFiniteBoundsWithoutLimitingBusinessTargetSet()throws Exception{
        Map<String,Object> request=targetStart("ledger-cap","search_evidence");Map<String,Object> context=new LinkedHashMap<>((Map<String,Object>)request.get("context"));
        List<String> targets=new ArrayList<>();Map<String,String> versions=new LinkedHashMap<>();for(int i=0;i<300;i++){targets.add("T"+i);versions.put("T"+i,"v1");}
        context.put("targetObligationIds",targets);context.put("targetEvidenceRevisions",versions);request.put("context",context);
        var result=bridge(script("rows=[dict(targetObligationId=t,progressScope='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='v1',state='UNVISITED',visited=False) for t in start['context']['targetObligationIds']]\ndone(targetProgressLedger=rows)\n"),5,1).investigate(request,(n,a)->Map.of(),null);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(256,((List<?>)result.get("targetProgressLedger")).size());assertEquals(true,result.get("targetProgressLedgerTruncated"));
        assertEquals(300,((List<?>)result.get("businessUnclosedTargetObligationIds")).size());
    }
    @Test void coveredAnyHostReceiptClosesOnlyItsTargetsAndUnfinishedTargetStillExecutes()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        var result=bridge(script("""
            emit('tool_call',id='1',name='recompute_check',arguments={})
            json.loads(sys.stdin.readline())
            emit('tool_call',id='2',name='read_section',arguments={'targetObligationId':'C','sourceId':'C-page'})
            json.loads(sys.stdin.readline())
            done('COMPLETED',completedTargetObligationIds=['forged'])
            """),5,1).investigate(targetStart("canonical-any","recompute_check","read_section"),(name,args)->{
                calls.incrementAndGet();if(name.equals("recompute_check"))return canonicalReceipt(List.of("A","B"),List.of("C"));
                Map<String,Object> receipt=canonicalReceipt(List.of("A","B","C"),List.of());receipt.put("kind","HOST_RULE_RECOMPUTE");
                return Map.of("status","COMPLETED","acceptedFactIds",List.of("host-C"),"targetEvidenceRevision","global-1","targetEvidenceRevisions",Map.of("A","A-1","B","B-1","C","C-1"),"computationReceipt",receipt);
            },null);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,calls.get());assertEquals(List.of("A","B","C"),result.get("completedTargetObligationIds"));
        assertEquals(false,result.get("businessCheckCompleted"));assertEquals(List.of(),result.get("businessUnclosedTargetObligationIds"));
    }
    @Test void coveredAnyTargetOrForgedTargetCannotReachHostAgain()throws Exception{
        for(String target:List.of("A","B","forged")){
            AtomicInteger calls=new AtomicInteger();
            var result=bridge(script("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_section',arguments={'targetObligationId':'"+target+"','sourceId':'page'})\n"),5,1)
                .investigate(targetStart("canonical-gate","recompute_check","read_section"),(name,args)->{calls.incrementAndGet();return canonicalReceipt(List.of("A","B"),List.of("C"));},null);
            code(target.equals("forged")?"CANDIDATE_TARGET_REQUIRED":"TARGET_STATE_UNCHANGED",result);assertEquals(1,calls.get());
        }
    }
    @Test void malformedOrStaleCanonicalPartitionCannotCertifyAnyTarget()throws Exception{
        List<Map<String,Object>> mutations=List.of(Map.of("targetReceiptAuthority","WORKER_ASSERTION"),Map.of("completedTargetObligationIds",List.of("A","B","C")),
            Map.of("remainingTargetObligationIds",List.of()),Map.of("completedTargetObligationIds",List.of("A","A")),Map.of("completedTargetObligationIds",List.of("A","forged")),
            Map.of("serverPlanRevision","old-rule"),Map.of("serverPlanFingerprint","other-plan"),Map.of("targetEvidenceRevisions",Map.of("A","old","B","B-1","C","C-1")),Map.of("targetCheckCompleted",true));
        for(Map<String,Object> mutation:mutations){
            Map<String,Object> receipt=canonicalReceipt(List.of("A","B"),List.of("C"));receipt.putAll(mutation);receipt.put("kind","HOST_RULE_RECOMPUTE");
            var result=bridge(script("emit('tool_call',id='1',name='read_section',arguments={'targetObligationId':'A'})\njson.loads(sys.stdin.readline())\ndone()\n"),5,1)
                .investigate(targetStart("bad-canonical","read_section"),(name,args)->Map.of("status","COMPLETED","targetEvidenceRevision","global-1","targetEvidenceRevisions",Map.of("A","A-1","B","B-1","C","C-1"),"computationReceipt",receipt),null);
            code("HOST_TARGET_RECEIPT_INVALID",result);assertEquals(false,result.get("businessCheckCompleted"));assertEquals(List.of(),result.get("completedTargetObligationIds"));
        }
    }
    @Test void topLevelNavigationCanonicalIdsDoNotReplaceEvaluatedProof()throws Exception{
        var result=bridge(script("emit('tool_call',id='1',name='read_table_record',arguments={'targetObligationId':'C'})\njson.loads(sys.stdin.readline())\ndone(completedTargetObligationIds=['A','B'])\n"),5,1)
            .investigate(targetStart("navigation-canonical","read_table_record"),(n,a)->canonicalReceipt(List.of("A","B"),List.of("C")),null);
        assertEquals(List.of(),result.get("completedTargetObligationIds"));assertEquals(List.of("A","B","C"),result.get("businessUnclosedTargetObligationIds"));
    }

    @SuppressWarnings("unchecked") private Map<String,Object> startWithCanonical(String id,String... tools){
        Map<String,Object> start=targetStart(id,tools);Map<String,Object> context=new LinkedHashMap<>((Map<String,Object>)start.get("context"));
        context.putAll(canonicalReceipt(List.of("A","B"),List.of("C")));start.put("context",context);return start;
    }
    @Test void canonicalStartClosesCoveredTargetsBeforeAnyToolCall()throws Exception{
        for(String target:List.of("A","B","forged")){
            AtomicInteger calls=new AtomicInteger();var result=bridge(script("emit('tool_call',id='1',name='read_section',arguments={'targetObligationId':'"+target+"','sourceId':'page'})\n"),5,1)
                .investigate(startWithCanonical("initial-canonical-gate","read_section"),(n,a)->{calls.incrementAndGet();return Map.of();},null);
            code(target.equals("forged")?"CANDIDATE_TARGET_REQUIRED":"TARGET_STATE_UNCHANGED",result);assertEquals(0,calls.get());assertEquals(List.of("A","B"),result.get("completedTargetObligationIds"));
        }
    }
    @Test void canonicalStartAllowsOnlyItsRemainingTargetAndPreservesBusinessUnclosed()throws Exception{
        AtomicInteger calls=new AtomicInteger();var result=bridge(script("emit('tool_call',id='1',name='read_section',arguments={'targetObligationId':'C','sourceId':'page'})\njson.loads(sys.stdin.readline())\ndone()\n"),5,1)
            .investigate(startWithCanonical("initial-canonical-valid","read_section"),(n,a)->{calls.incrementAndGet();return Map.of("status","COMPLETED");},null);
        assertEquals(1,calls.get());assertEquals("NO_PROGRESS",result.get("status"));assertEquals(List.of("A","B"),result.get("completedTargetObligationIds"));assertEquals(List.of("C"),result.get("businessUnclosedTargetObligationIds"));
    }
    @SuppressWarnings("unchecked")
    @Test void malformedCanonicalStartNeverLaunchesWorkerOrReachesHost()throws Exception{
        for(Map<String,Object> mutation:List.<Map<String,Object>>of(Map.of("targetReceiptAuthority","WORKER_ASSERTION"),Map.of("completedTargetObligationIds",List.of("A","A")),Map.of("remainingTargetObligationIds",List.of("B","C")),Map.of("remainingTargetObligationIds",List.of()),Map.of("completedTargetObligationIds",List.of("forged")),Map.of("targetCheckCompleted",true))){
            var request=startWithCanonical("initial-canonical-invalid","read_section");var context=new LinkedHashMap<>((Map<String,Object>)request.get("context"));context.putAll(mutation);request.put("context",context);
            var result=bridge(script("raise RuntimeError('must not start')\n"),5,1).investigate(request,(n,a)->{fail("invalid start reached Host");return Map.of();},null);
            code("HOST_TARGET_RECEIPT_INVALID",result);assertEquals(List.of(),result.get("completedTargetObligationIds"));
        }
    }
    @Test void ordinaryNoEvidenceProgressNeverBecomesFirstFailure()throws Exception{
        var result=bridge(script("emit('event',event='no_evidence_progress',eventId=rid+':1',targetObligationId='A')\ndone(firstFailureEventId=rid+':1')\n"),5,1)
            .investigate(targetStart("no-proof-not-error","search_evidence"),(n,a)->Map.of(),null);
        assertEquals("NO_PROGRESS",result.get("status"));assertFalse(result.containsKey("firstFailureEventId"));
    }
    @Test void trueFailureAfterOrdinaryNoProgressIsTheFirstFailure()throws Exception{
        var result=bridge(script("emit('event',event='no_evidence_progress',eventId=rid+':1',targetObligationId='A')\nemit('event',event='target_tool_failed',eventId=rid+':2',targetObligationId='C',rejectionCode='SOURCE_NOT_CURRENT')\ndone(firstFailureEventId=rid+':1')\n"),5,1)
            .investigate(targetStart("proof-before-failure","search_evidence"),(n,a)->Map.of(),null);
        assertEquals("proof-before-failure:2",result.get("firstFailureEventId"));
    }

}

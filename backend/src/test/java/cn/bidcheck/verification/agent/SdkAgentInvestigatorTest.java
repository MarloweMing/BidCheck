package cn.bidcheck.verification.agent;
import cn.bidcheck.compat.HexFormat;

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
    private static final String HEADER="import json,sys,os,time\nsys.stdin.reconfigure(encoding='utf-8')\nsys.stdout.reconfigure(encoding='utf-8',line_buffering=True)\nstart=json.loads(sys.stdin.readline())\nrid=start['requestId']\ndef emit(kind,**extra):\n    print(json.dumps(dict(type=kind,protocol='bidcheck-agent-1',requestId=rid,**extra),ensure_ascii=False),flush=True)\ndef done(status='NO_PROGRESS',**extra):\n    emit('result',status=status,code='',sdk='openai-agents',sdkVersion='0.24.0',tracingExportEnabled=False,**extra)\n";
    private Path python(){
        String configured=System.getProperty("bidcheck.agent.test-python","");if(!cn.bidcheck.compat.Java8Api.isBlank(configured))return java.nio.file.Paths.get(configured);
        Path here=java.nio.file.Paths.get("").toAbsolutePath();for(Path root:cn.bidcheck.compat.Java8Api.listOf(here, here.getParent())){
            Path candidate=root.resolve(".local/agent-venv/Scripts/python.exe");if(Files.isRegularFile(candidate))return candidate;
            candidate=root.resolve(".local/agent-venv/bin/python");if(Files.isRegularFile(candidate))return candidate;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(false,"configured test Python required for real process protocol tests");return null;
    }
    private Path script(String body)throws Exception{Path script=temp.resolve("worker-"+UUID.randomUUID()+".py");cn.bidcheck.compat.Java8Api.writeString(script, HEADER+body);return script;}
    private SdkAgentInvestigator bridge(Path script,int timeout,int concurrency){return new SdkAgentInvestigator(true,BASE,KEY,"qwen-plus",timeout,concurrency,new TestAgentProtocol(python(),script));}
    private Map<String,Object> start(String id,String... names){List<Map<String,Object>> tools=new ArrayList<>();for(String name:names)tools.add(cn.bidcheck.compat.Java8Api.mapOf("name", name, "description", "测试可信工具", "parameters", cn.bidcheck.compat.Java8Api.mapOf("type", "object", "properties", cn.bidcheck.compat.Java8Api.mapOf(), "additionalProperties", false)));
        return cn.bidcheck.compat.Java8Api.mapOf("type", "start", "protocol", "bidcheck-agent-1", "requestId", id, "context", cn.bidcheck.compat.Java8Api.mapOf("domain", "PERSONNEL_OWNERSHIP", "requirement", "只读取本次人员材料"), "tools", tools);}
    private Map<String,Object> run(String body,AgentInvestigator.ToolHandler handler)throws Exception{return bridge(script(body),5,1).investigate(start("test-1","search_evidence","submit_candidate_fact","recompute_check"),handler,null);}
    private void code(String expected,Map<String,Object> result){assertEquals("FAILED",result.get("status"),result.toString());assertEquals(expected,result.get("errorCode"),result.toString());}

    @Test void onlyApprovedBailianCompatibleEndpointsCanReceiveCredentials()throws Exception{
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",SdkAgentInvestigator.normalizedEndpoint(BASE));
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1",SdkAgentInvestigator.normalizedEndpoint("https://dashscope-intl.aliyuncs.com:443/compatible-mode/v1/"));
        for(String url:cn.bidcheck.compat.Java8Api.listOf("", "http://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com.evil.test/compatible-mode/v1", "https://secret@dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1?key=secret", "https://dashscope.aliyuncs.com:444/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/%76%31", "https://api.openai.com/v1", "https://127.0.0.1/compatible-mode/v1"))assertThrows(SdkAgentInvestigator.BridgeFailure.class,()->SdkAgentInvestigator.normalizedEndpoint(url),url);
    }
    @Test void missingConfigAndDisabledStateAreSafeFailures(){
        cn.bidcheck.verification.agent.SdkAgentInvestigator disabled=new SdkAgentInvestigator(false,BASE,"","",1,1);assertFalse(disabled.enabled());code("AGENT_DISABLED",disabled.investigate(start("disabled","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null));
        cn.bidcheck.verification.agent.SdkAgentInvestigator noKey=new SdkAgentInvestigator(true,BASE,"","qwen-plus",1,1);assertTrue(noKey.enabled());code("MODEL_CONFIGURATION_MISSING",noKey.investigate(start("missing","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null));
    }
    @Test void realJsonlToolRoundTripAcceptsOnlyServerReceiptsAndDropsProse()throws Exception{
        List<String> names=new ArrayList<>();List<Map<String,Object>> events=new ArrayList<>();Path script=script("assert os.environ['BIDCHECK_AGENT_API_KEY']=='bridge-unit-test-not-a-real-key'\nassert os.environ['BIDCHECK_AGENT_BASE_URL']=='https://dashscope.aliyuncs.com/compatible-mode/v1'\nassert os.environ['OPENAI_AGENTS_DISABLE_TRACING']=='1'\nassert os.environ['BIDCHECK_AGENT_TOOL_TIMEOUT_SECONDS']==os.environ['BIDCHECK_AGENT_RUN_TIMEOUT_SECONDS']\nassert 'BIDCHECK_AGENT_ALLOW_TEST_LOCALHOST' not in os.environ\nassert not any('bridge-unit-test-not-a-real-key' in arg for arg in sys.argv)\nemit('event',event='model_started',id='permit-1',modelIndex=1,prompt='secret original text',apiKey='never forward')\npermit=json.loads(sys.stdin.readline())\nassert permit['type']=='event_ack' and permit['id']=='permit-1' and permit['allowed'] is True\nemit('tool_call',id='tool-1',name='search_evidence',arguments={'query':'项目负责人任职表'})\nanswer=json.loads(sys.stdin.readline())\nassert answer['id']=='tool-1' and answer['requestId']==rid\nassert answer['result']['source']=='拟在本合同任职'\nemit('tool_call',id='tool-2',name='submit_candidate_fact',arguments={'sourceId':'S1'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='tool-3',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED',summary='PASS 全部满足',businessDecision='PASS',acceptedFactIds=['invented'],businessCheckCompleted=True,modelCalls=1,inputTokens=20,outputTokens=10)\n");
        Map<String,Object> result=bridge(script,5,1).investigate(start("roundtrip","search_evidence","submit_candidate_fact","recompute_check"),(name,args)->{
            names.add(name);switch (name) {case "search_evidence":{assertEquals("项目负责人任职表",args.get("query"));return cn.bidcheck.compat.Java8Api.mapOf("source", "拟在本合同任职");}case "submit_candidate_fact":return cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("verified-fact"));default:return cn.bidcheck.compat.Java8Api.mapOf("businessCheckCompleted", true);}
        },events::add);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(cn.bidcheck.compat.Java8Api.listOf("verified-fact"),result.get("acceptedFactIds"));assertEquals(3L,result.get("toolCalls"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("search_evidence", "submit_candidate_fact", "recompute_check"),names);assertEquals(1,events.size());
        assertFalse(result.containsKey("summary"));assertFalse(result.containsKey("businessDecision"));assertFalse(events.get(0).containsKey("prompt"));assertFalse(events.get(0).containsKey("apiKey"));
    }
    @Test void workerCannotInventCompletedBusinessCheck()throws Exception{code("WORKER_RESULT_STATE_MISMATCH",run("done('COMPLETED',businessCheckCompleted=True,acceptedFactIds=['invented'])\n",(n,a)->{fail("no tool was requested");return cn.bidcheck.compat.Java8Api.mapOf();}));}
    @Test void workerCannotInventAcceptedFacts()throws Exception{code("WORKER_RESULT_STATE_MISMATCH",run("done('EVIDENCE_UPDATED',acceptedFactIds=['invented'])\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf()));}
    @Test void trustedAutomaticRecomputeCrossesRealProcessBoundary()throws Exception{
        java.util.Map<java.lang.String, java.io.Serializable> receipt=cn.bidcheck.compat.Java8Api.mapOf("kind", "HOST_RULE_RECOMPUTE", "status", "COMPLETED", "targetEvidenceRevision", "revision-1", "targetCheckCompleted", true, "businessCheckCompleted", false);
        java.util.Map<java.lang.String, java.lang.Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\nemit('event',event='host_recomputed',targetCheckCompleted=True,businessCheckCompleted=False)\ndone('COMPLETED',targetCheckCompleted=True,businessCheckCompleted=False)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("trusted"), "targetEvidenceRevision", "revision-1", "computationReceipt", receipt));
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(true,result.get("targetCheckCompleted"));assertEquals(false,result.get("businessCheckCompleted"));assertEquals(1,result.get("automaticRecomputations"));
    }
    @Test void tableLayoutRecomputeReceiptAndRawSourceAuditCrossRealProcessBoundary()throws Exception{
        java.util.Map<java.lang.String, java.io.Serializable> receipt=cn.bidcheck.compat.Java8Api.mapOf("kind", "HOST_RULE_RECOMPUTE", "status", "COMPLETED", "targetEvidenceRevision", "table-v2", "targetCheckCompleted", true, "businessCheckCompleted", false);
        Map<String,Object> layoutAudit=cn.bidcheck.compat.Java8Api.mapOf("traceId", "layout-call", "cacheHit", true, "inputAudit", cn.bidcheck.compat.Java8Api.mapOf("file", "layout-input.json", "sha256", cn.bidcheck.compat.Java8Api.repeat("a", 64)), "outputAudit", cn.bidcheck.compat.Java8Api.mapOf("file", "layout-output.json", "sha256", cn.bidcheck.compat.Java8Api.repeat("b", 64)));
        Map<String,Object> response=cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "layoutChanged", true, "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("table-derived-fact"), "targetEvidenceRevision", "table-v2", "computationReceipt", receipt, "pageLayoutAudit", layoutAudit, "sources", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "source-tail", "quote", "末行证书编号：TAIL-99")));
        Path script=script("emit('tool_call',id='table-1',name='read_table_record',arguments={'sourceId':'source-tail'})\nresult=json.loads(sys.stdin.readline())['result']\nassert result['sources'][0]['quote']=='末行证书编号：TAIL-99'\nassert result['pageLayoutAudit']['inputAudit']['sha256']=='a'*64\nassert result['pageLayoutAudit']['outputAudit']['sha256']=='b'*64\ndone('COMPLETED',targetCheckCompleted=True,businessCheckCompleted=False,acceptedFactIds=['table-derived-fact'])\n");
        cn.bidcheck.verification.agent.SdkAgentInvestigator investigator=bridge(script,5,1);Path auditDirectory=temp.resolve("table-audit");org.springframework.test.util.ReflectionTestUtils.setField(investigator,"auditDirectory",auditDirectory.toString());
        List<Map<String,Object>> events=new ArrayList<>();Map<String,Object> result=investigator.investigate(start("table-auto","read_table_record"),(name,args)->response,events::add);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(true,result.get("targetCheckCompleted"));assertEquals(false,result.get("businessCheckCompleted"));
        assertEquals(1,result.get("automaticRecomputations"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("table-derived-fact"),result.get("acceptedFactIds"));assertEquals(2,events.size());
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(Map<String,Object> event:events){
            assertEquals("tool_payload_audit",event.get("event"));Map<?,?> audit=(Map<?,?>)event.get("toolAudit");assertEquals("SAVED",audit.get("status"));
            byte[] bytes=Files.readAllBytes(auditDirectory.resolve(audit.get("file").toString()));
            assertEquals(audit.get("sha256"),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            com.fasterxml.jackson.databind.JsonNode payload=json.readTree(bytes).path("payload");
            if("input".equals(event.get("direction")))assertEquals("source-tail",payload.path("sourceId").asText());
            else assertEquals(json.valueToTree(response),payload,"full source bodies and original layout audit references must be retained before worker compaction");
        }
    }
    @Test void tableReceiptWithStaleEvidenceRevisionCannotCompleteWorkerInvestigation()throws Exception{
        java.util.Map<java.lang.String, java.io.Serializable> stale=cn.bidcheck.compat.Java8Api.mapOf("kind", "HOST_RULE_RECOMPUTE", "status", "COMPLETED", "targetEvidenceRevision", "table-v1", "targetCheckCompleted", true, "businessCheckCompleted", true);
        Map<String,Object> result=bridge(script("emit('tool_call',id='table-1',name='read_table_record',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED',targetCheckCompleted=True,businessCheckCompleted=True)\n"),5,1)
            .investigate(start("table-stale","read_table_record"),(name,args)->cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "layoutChanged", true, "targetEvidenceRevision", "table-v2", "computationReceipt", stale),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);
    }
    @Test void layoutRevisionInvalidatesEarlierAutomaticCompletion()throws Exception{
        java.util.Map<java.lang.String, java.io.Serializable> receipt=cn.bidcheck.compat.Java8Api.mapOf("kind", "HOST_RULE_RECOMPUTE", "status", "COMPLETED", "targetEvidenceRevision", "revision-1", "targetCheckCompleted", true, "businessCheckCompleted", false);
        Path script=script("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_table_record',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n");
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script,5,1).investigate(start("revision","search_evidence","read_table_record"),(n,a)->n.equals("search_evidence")?cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevision", "revision-1", "computationReceipt", receipt):cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevision", "revision-2"),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);
    }
    @Test void stalledInvestigationPreservesOnlyKnownUnfinishedTargets()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("stalled","search_evidence"));request.put("context",cn.bidcheck.compat.Java8Api.mapOf("targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "B")));
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='semantic_path_exhausted',path='p1',evidenceRevision='v1',affectedTargetObligationIds=['A','forged'],failureFamily='TYPED_SOURCE_EXTRACTION',remainingRepairActions=['READ_COMPLETE_RECORD','private document body'])\nemit('result',status='FAILED',code='AGENT_STALLED_WITH_REMAINING_TARGETS',tracingExportEnabled=False,blockedTargetObligationIds=['A','forged'],remainingTargetObligationIds=['B','forged'],completedTargetObligationIds=['forged'])\n"),5,1).investigate(request,(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        code("AGENT_STALLED_WITH_REMAINING_TARGETS",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf("A"),result.get("blockedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("B"),result.get("remainingTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));assertEquals(true,result.get("investigationIncomplete"));
    }
    @Test void boundedCorrectableToolErrorsRemainEvidencePendingRatherThanSystemFailure()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("pending-schema","search_evidence"));request.put("context",cn.bidcheck.compat.Java8Api.mapOf("targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A")));
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('result',status='NO_PROGRESS',code='EVIDENCE_PENDING',tracingExportEnabled=False,correctableToolRejectionCount=2,remainingTargetObligationIds=['A'])\n"),5,1).investigate(request,(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals("EVIDENCE_PENDING",result.get("code"));assertEquals("",result.get("errorCode"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("A"),result.get("remainingTargetObligationIds"));
    }
    @Test void compactContextMetricsAreAllowedWithoutExportingDocumentBodies()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='context_compacted',inputChars=1200,removedChars=42000,retainedToolBatches=2,disclosedSourceCount=8,sourceText='private document',contracts={'person':'private name'})\ndone(contextCompactions=3,contextInputChars=3600,contextRemovedChars=80000)\n"),5,1).investigate(start("compact","read_section"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(3L,result.get("contextCompactions"));
        assertEquals(80000L,result.get("contextRemovedChars"));assertEquals(1,events.size());
        assertEquals(42000L,events.get(0).get("removedChars"));assertFalse(events.get(0).containsKey("sourceText"));assertFalse(events.get(0).containsKey("contracts"));
    }
    @Test void semanticStopMetadataKeepsOnlyBoundedCodesAndNoSourceText()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='semantic_path_exhausted',path='work-key',evidenceRevision='revision',failureFamily='TYPED_SOURCE_EXTRACTION',remainingRepairActions=['READ_COMPLETE_RECORD','private document body'],sourceText='private document body')\ndone()\n"),5,1).investigate(start("semantic-log","read_section"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(1,events.size());
        assertEquals("TYPED_SOURCE_EXTRACTION",events.get(0).get("failureFamily"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("READ_COMPLETE_RECORD"),events.get(0).get("remainingRepairActions"));
        assertFalse(events.get(0).containsKey("sourceText"));
    }
    @Test void schemaCorrectionEventKeepsOnlyActionableValidationCoordinates()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='tool_arguments_rejected',tool='search_evidence',validatorKeyword='required',fieldPath='/query',correctable=True,attemptForTargetGap=1,maxAttemptsForTargetGap=2,rejectedValue='private document')\ndone(correctableToolRejectionCount=1)\n"),5,1).investigate(start("schema-event","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(1L,result.get("correctableToolRejectionCount"));assertEquals(1,events.size());
        assertEquals("search_evidence",events.get(0).get("tool"));assertEquals("required",events.get(0).get("validatorKeyword"));assertEquals("/query",events.get(0).get("fieldPath"));assertEquals(true,events.get(0).get("correctable"));assertFalse(events.get(0).containsKey("rejectedValue"));
    }
    @Test void targetWorkflowProtocolFailuresRemainSystemFailures()throws Exception{
        for(String error:cn.bidcheck.compat.Java8Api.listOf("TARGET_CONTRACT_MISSING", "TARGET_WORK_RECEIPT_MISSING", "TARGET_WORK_RECEIPT_INVALID", "CANDIDATE_TARGET_REQUIRED")){
            java.util.Map<java.lang.String, java.lang.Object> result=run("emit('result',status='FAILED',code='"+error+"',tracingExportEnabled=False)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf());
            code(error,result);assertEquals(false,result.get("targetCheckCompleted"));
        }
    }
    @Test void newFactsInvalidateEarlierCompletionReceipt()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_candidate_fact',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n",(name,args)->name.equals("recompute_check")?cn.bidcheck.compat.Java8Api.mapOf("businessCheckCompleted", true):cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("new")));
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf("new"),result.get("acceptedFactIds"));
    }
    @Test void trustedNativeSearchAndSectionReceiptsCountAsEvidenceProgress()throws Exception{
        Path worker=script("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_section',arguments={})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED')\n");
        Map<String,Object> result=bridge(worker,5,1).investigate(start("native-progress","search_evidence","read_section"),(name,args)->cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf(name+"-fact")),null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("search_evidence-fact", "read_section-fact"),result.get("acceptedFactIds"));
    }
    @Test void newlyAdoptedNativeFactsInvalidateThePreviousRecomputeReceipt()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n",(name,args)->name.equals("recompute_check")?cn.bidcheck.compat.Java8Api.mapOf("businessCheckCompleted", true):cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("native-new")));
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf("native-new"),result.get("acceptedFactIds"));
    }
    @Test void trustedTableReadCanAdoptNativeFacts()throws Exception{
        Map<String,Object> result=bridge(script("emit('tool_call',id='1',name='read_table_record',arguments={})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED')\n"),5,1)
            .investigate(start("table-adopted","read_table_record"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("host-verified-native-fact")),null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("host-verified-native-fact"),result.get("acceptedFactIds"));
    }
    @Test void externalQueryCannotClaimAcceptedFacts()throws Exception{
        Map<String,Object> result=bridge(script("emit('tool_call',id='1',name='query_external',arguments={})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED')\n"),5,1)
            .investigate(start("external-not-adopted","query_external"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("not-accepted")),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("acceptedFactIds"));
    }
    @Test void failedTableReadCannotClaimAcceptedFacts()throws Exception{
        Map<String,Object> result=bridge(script("emit('tool_call',id='1',name='read_table_record',arguments={})\njson.loads(sys.stdin.readline())\ndone()\n"),5,1)
            .investigate(start("failed-table","read_table_record"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("not-accepted")),null);
        code("TOOL_OPERATION_FAILED",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("acceptedFactIds"));
    }
    @Test void failedNativeReadCannotClaimAcceptedFacts()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone()\n",
            (n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("not-accepted")));
        code("TOOL_OPERATION_FAILED",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("acceptedFactIds"));
    }
    @Test void validationRejectionsDoNotPoisonPartialEvidence()throws Exception{
        for(String error:cn.bidcheck.compat.Java8Api.listOf("SOURCE_NOT_DISCLOSED", "ROW_OFFSET_INVALID", "FACT_OUTSIDE_TARGET")){
            AtomicInteger count=new AtomicInteger();
            Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={'query':'initial'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_candidate_fact',arguments={})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED',failedToolCount=999)\n",(name,args)->count.incrementAndGet()==1?cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "error", error):cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("real-fact")));
            assertEquals("EVIDENCE_UPDATED",result.get("status"),result.toString());assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("validationRejectionCount"));
        }
    }
    @Test void successfulRetryClearsOnlyMatchingTechnicalFailure()throws Exception{
        AtomicInteger count=new AtomicInteger();
        Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={'query':' current role '})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='search_evidence',arguments={'query':'current  role'})\njson.loads(sys.stdin.readline())\nemit('result',status='FAILED',code='TOOL_OPERATION_FAILED',tracingExportEnabled=False)\n",(name,args)->count.incrementAndGet()==1?cn.bidcheck.compat.Java8Api.mapOf("status", "FAILED", "error", "DATABASE_UNAVAILABLE"):cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED"));
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals("",result.get("errorCode"));assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("recoveredToolCount"));
    }
    @Test void differentOperationOrPendingReceiptCannotHideTechnicalFailure()throws Exception{
        for(boolean pending:cn.bidcheck.compat.Java8Api.listOf(false, true)){
            AtomicInteger count=new AtomicInteger();
            String nextQuery=pending?"first":"other";
            Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={'query':'first'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='search_evidence',arguments={'query':'NEXT_QUERY'})\njson.loads(sys.stdin.readline())\ndone(failedToolCount=0)\n".replace("NEXT_QUERY",nextQuery),(name,args)->count.incrementAndGet()==1?cn.bidcheck.compat.Java8Api.mapOf("status", "REJECTED", "error", "DATABASE_UNAVAILABLE", "recoverable", true):cn.bidcheck.compat.Java8Api.mapOf("status", pending?"PENDING":"COMPLETED"));
            code("TOOL_OPERATION_FAILED",result);assertEquals(1,result.get("failedToolCount"));assertEquals(0,result.get("recoveredToolCount"));
        }
    }
    @Test void targetCompletionCanStopInvestigationWithoutClaimingWholeBusinessCompletion()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED',businessCheckCompleted=True,targetCheckCompleted=True)\n",(name,args)->cn.bidcheck.compat.Java8Api.mapOf("targetCheckCompleted", true, "businessCheckCompleted", false));
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(true,result.get("targetCheckCompleted"));assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void workerAndNonRecomputeToolsCannotInventTargetCompletion()throws Exception{
        code("WORKER_RESULT_STATE_MISMATCH",run("done('COMPLETED',targetCheckCompleted=True)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf()));
        code("WORKER_RESULT_STATE_MISMATCH",run("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED',targetCheckCompleted=True)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf("targetCheckCompleted", true)));
    }
    @Test void newlyAcceptedFactsInvalidateTargetCompletion()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n",(name,args)->name.equals("recompute_check")?cn.bidcheck.compat.Java8Api.mapOf("targetCheckCompleted", true, "businessCheckCompleted", false):cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("new")));
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf("new"),result.get("acceptedFactIds"));
    }
    @Test void workerFailurePreservesAlreadyAcceptedFacts()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='submit_candidate_fact',arguments={})\njson.loads(sys.stdin.readline())\nemit('result',status='FAILED',code='MODEL_OR_SDK_ERROR',summary='sensitive message',tracingExportEnabled=False)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("kept")));
        code("MODEL_OR_SDK_ERROR",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf("kept"),result.get("acceptedFactIds"));assertFalse(result.containsKey("summary"));
    }
    @Test void unknownWorkerErrorCodeCannotExfiltrateText()throws Exception{
        Map<String,Object> result=run("emit('result',status='FAILED',code='api key and raw document text',tracingExportEnabled=False)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf());code("WORKER_REPORTED_FAILURE",result);assertFalse(result.toString().contains("raw document"));
    }
    @Test void crossRequestMessagesAreRejectedBeforeToolExecution()throws Exception{
        AtomicBoolean called=new AtomicBoolean();code("PROTOCOL_REQUEST_ID_MISMATCH",run("rid='other-request'\nemit('tool_call',id='1',name='search_evidence',arguments={})\n",(n,a)->{called.set(true);return cn.bidcheck.compat.Java8Api.mapOf();}));assertFalse(called.get());
    }
    @Test void unofferedToolsAndDuplicateCallIdsCannotReachHandler()throws Exception{
        AtomicInteger calls=new AtomicInteger();Map<String,Object> unoffered=bridge(script("emit('tool_call',id='1',name='query_external',arguments={})\n"),5,1).investigate(start("restricted","search_evidence"),(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf();},null);code("TOOL_NOT_ALLOWED",unoffered);assertEquals(0,calls.get());
        Map<String,Object> duplicated=run("emit('tool_call',id='same',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='same',name='search_evidence',arguments={})\n",(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf();});code("TOOL_CALL_ID_INVALID",duplicated);assertEquals(1,calls.get());
    }
    @Test void largeArgumentsAndDuplicateJsonKeysAreRejected()throws Exception{
        code("TOOL_ARGUMENTS_TOO_LARGE",run("emit('tool_call',id='1',name='search_evidence',arguments={'query':'x'*70000})\n",(n,a)->{fail("oversized arguments");return cn.bidcheck.compat.Java8Api.mapOf();}));
        code("PROTOCOL_READ_FAILED",run("print('{\"type\":\"event\",\"type\":\"tool_call\"}',flush=True)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf()));
    }
    @Test void malformedUtf8AndOversizedFramesAreSafeFailures()throws Exception{
        code("PROTOCOL_READ_FAILED",run("sys.stdout.buffer.write(b'\\xff\\n');sys.stdout.buffer.flush()\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf()));
        code("PROTOCOL_MESSAGE_TOO_LARGE",run("print('x'*(4*1024*1024+1),flush=True)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf()));
    }
    @Test void stderrNeverBecomesProtocolOrReturnedFailureText()throws Exception{
        Map<String,Object> result=run("print('SECRET credential traceback',file=sys.stderr,flush=True)\nsys.exit(3)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf());code("WORKER_DISCONNECTED",result);assertFalse(result.toString().contains("SECRET"));
    }
    @Test void toolExceptionsRemainSystemFailuresWithoutExceptionText()throws Exception{
        Map<String,Object> result=run("emit('tool_call',id='1',name='search_evidence',arguments={})\ntime.sleep(10)\n",(n,a)->{throw new IllegalArgumentException("secret original materials");});code("TOOL_HANDLER_FAILED",result);assertFalse(result.toString().contains("secret"));
    }
    @Test void aWorkerTimeoutAndBlockedHandlerAreTerminated()throws Exception{
        Path pidFile=temp.resolve("pid");String quoted=pidFile.toString().replace("\\","\\\\").replace("'","\\'");
        Path script=script("open('"+quoted+"','w').write(str(os.getpid()))\ntime.sleep(30)\n");
        long start=System.nanoTime();Map<String,Object> result=bridge(script,1,1).investigate(start("timeout","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);code("INVESTIGATION_TIMEOUT",result);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<4000);
        long pid=Long.parseLong(cn.bidcheck.compat.Java8Api.readString(pidFile));for(int i=0;i<20&&cn.bidcheck.compat.Java8Processes.isAlive(pid);i++)Thread.sleep(25);assertFalse(cn.bidcheck.compat.Java8Processes.isAlive(pid));
        AtomicBoolean entered=new AtomicBoolean(),interrupted=new AtomicBoolean();Map<String,Object> handler=bridge(script("emit('tool_call',id='1',name='search_evidence',arguments={})\ntime.sleep(30)\n"),1,1).investigate(start("handler-timeout","search_evidence"),(n,a)->{entered.set(true);try{Thread.sleep(30000);}catch(InterruptedException e){interrupted.set(true);Thread.currentThread().interrupt();}return cn.bidcheck.compat.Java8Api.mapOf();},null);
        code("INVESTIGATION_TIMEOUT",handler);assertTrue(entered.get(),"fixture worker did not reach handler before the unchanged one-second deadline; inspect process startup separately from callback cancellation");
        for(int i=0;i<20&&!interrupted.get();i++)Thread.sleep(25);assertTrue(interrupted.get(),"an entered blocked handler must observe cancellation");
    }
    @Test void callerCancellationStopsInvestigationAndRestoresInterruptFlag()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicReference<Map<String,Object>> result=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();SdkAgentInvestigator bridge=bridge(script("emit('event',event='model_started',id='permit-1',modelIndex=1)\ntime.sleep(30)\n"),20,1);
        Thread caller=new Thread(()->{result.set(bridge.investigate(start("cancel","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),e->entered.countDown()));flag.set(Thread.currentThread().isInterrupted());});caller.start();assertTrue(entered.await(5,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);assertFalse(caller.isAlive());code("HOST_CANCELLED",result.get());assertTrue(flag.get());
    }
    @Test void expiredCallbackFenceStaysInvalidEvenIfAToolClearsItsInterrupt()throws Exception{
        CountDownLatch returned=new CountDownLatch(1);AtomicBoolean afterTimeout=new AtomicBoolean(true);
        Path marker=temp.resolve("late-tool-result");String escaped=marker.toString().replace("\\","\\\\").replace("'","\\'");
        Map<String,Object> result=bridge(script("emit('tool_call',id='1',name='submit_candidate_fact',arguments={})\nresponse=json.loads(sys.stdin.readline())\nopen('"+escaped+"','w').write('late result')\ndone('EVIDENCE_UPDATED')\n"),1,1).investigate(start("stale-tool","submit_candidate_fact"),(n,a)->{
            assertTrue(SdkAgentInvestigator.currentCallActive());
            try{Thread.sleep(30000);}catch(InterruptedException ignored){Thread.interrupted();}
            afterTimeout.set(SdkAgentInvestigator.currentCallActive());returned.countDown();return cn.bidcheck.compat.Java8Api.mapOf("acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("late-fact"));
        },null);
        code("INVESTIGATION_TIMEOUT",result);assertTrue(returned.await(3,TimeUnit.SECONDS));assertFalse(afterTimeout.get(),"a canceled tool must not publish after clearing its thread interruption");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("acceptedFactIds"));assertFalse(Files.exists(marker),"an expired callback result must not reach the worker");
    }
    @Test void modelRequestWaitsForSuccessfulHostPermitAcknowledgement()throws Exception{
        Path marker=temp.resolve("authorized");String escaped=marker.toString().replace("\\","\\\\").replace("'","\\'");
        Path worker=script("emit('event',event='model_started',id='permit-1',modelIndex=1)\nack=json.loads(sys.stdin.readline())\nassert ack['allowed'] is True and ack['id']=='permit-1'\nopen('"+escaped+"','w').write('allowed')\nemit('event',event='model_request_authorized',id='permit-1',modelIndex=1)\ndone()\n");
        CountDownLatch entered=new CountDownLatch(1),allow=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();
        try{Future<Map<String,Object>> result=executor.submit(()->bridge(worker,5,1).investigate(start("permit","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),event->{if(event.get("event").equals("model_started")){entered.countDown();try{allow.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("cancel");}}}));
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertFalse(Files.exists(marker));allow.countDown();assertEquals("NO_PROGRESS",result.get(5,TimeUnit.SECONDS).get("status"));assertEquals("allowed",cn.bidcheck.compat.Java8Api.readString(marker));
        }finally{allow.countDown();executor.shutdownNow();}
    }
    @Test void deniedPermitCannotReachProviderRequest()throws Exception{
        Path marker=temp.resolve("must-not-authorize");String escaped=marker.toString().replace("\\","\\\\").replace("'","\\'");
        Path worker=script("emit('event',event='model_started',id='permit-1',modelIndex=1)\nack=json.loads(sys.stdin.readline())\nif ack.get('allowed') is True: open('"+escaped+"','w').write('bad')\ndone()\n");
        Map<String,Object> result=bridge(worker,5,1).investigate(start("deny","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),event->{throw new IllegalStateException("lease not owned; sensitive detail");});
        code("MODEL_REQUEST_DENIED",result);assertFalse(Files.exists(marker));assertFalse(result.toString().contains("sensitive"));
    }
    @Test void malformedPermitEventCannotCallHostOrAuthorizeModel()throws Exception{
        AtomicBoolean callback=new AtomicBoolean();Map<String,Object> result=bridge(script("emit('event',event='model_started',modelIndex=1)\ntime.sleep(30)\n"),5,1).investigate(start("bad-permit","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),event->callback.set(true));
        code("MODEL_PERMISSION_PROTOCOL_ERROR",result);assertFalse(callback.get());
    }
    @Test void missingHostPermitCallbackFailsClosed()throws Exception{
        code("MODEL_REQUEST_DENIED",run("emit('event',event='model_started',id='permit-1',modelIndex=1)\ntime.sleep(30)\n",(n,a)->cn.bidcheck.compat.Java8Api.mapOf()));
    }
    @Test void configuredConcurrencySerializesWorkerLaunchesAndReleasesSlots()throws Exception{
        SdkAgentInvestigator bridge=bridge(script("emit('tool_call',id='1',name='search_evidence',arguments={})\njson.loads(sys.stdin.readline())\ndone()\n"),10,1);
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();ExecutorService executor=Executors.newFixedThreadPool(3);
        try{List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<3;i++){String id="parallel-"+i;futures.add(executor.submit(()->bridge.investigate(start(id,"search_evidence"),(n,a)->{int current=active.incrementAndGet();max.accumulateAndGet(current,Math::max);try{Thread.sleep(80);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{active.decrementAndGet();}return cn.bidcheck.compat.Java8Api.mapOf();},null)));}for(java.util.concurrent.Future<java.util.Map<java.lang.String, java.lang.Object>> future:futures)assertEquals("NO_PROGRESS",future.get(10,TimeUnit.SECONDS).get("status"));assertEquals(1,max.get());}finally{executor.shutdownNow();}
    }
    @Test void identityChangesWithWorkerOrModelButNeverWithApiKey()throws Exception{
        Path script=script("done()\n");SdkAgentInvestigator first=bridge(script,5,1),otherKey=new SdkAgentInvestigator(true,BASE,"a-different-secret","qwen-plus",5,1,new TestAgentProtocol(python(),script));
        assertEquals(first.identity(),otherKey.identity());assertFalse(first.identity().contains(KEY));
        String beforeLock=first.identity();cn.bidcheck.compat.Java8Api.writeString(script.getParent().resolve("requirements.lock"), "openai-agents==0.24.0\n");assertNotEquals(beforeLock,first.identity());
        String beforeAudit=first.identity();cn.bidcheck.compat.Java8Api.writeString(script.getParent().resolve("model_audit.py"), "# changed local auditing\n");assertNotEquals(beforeAudit,first.identity());
        String previous=first.identity();cn.bidcheck.compat.Java8Api.writeString(script, "\n# changed worker\n", StandardOpenOption.APPEND);assertNotEquals(previous,first.identity());
        assertNotEquals(first.identity(),new SdkAgentInvestigator(true,BASE,KEY,"different-model",5,1,new TestAgentProtocol(python(),script)).identity());
    }

    @Test void localAuditEmitsOnlySafeReferencesNeverPayloadOrArbitraryPaths()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();Path script=script("assert os.environ['BIDCHECK_AGENT_AUDIT_DIRECTORY']\nemit('event',event='model_finished',modelAudit={'status':'SAVED','file':'a'*32+'-00001-output.json','sha256':'b'*64,'bytes':17,'payload':'private model text','apiKey':'sensitive'})\nemit('event',event='model_finished',modelAudit={'status':'SAVED','file':'../private.json','sha256':'malformed','bytes':-1})\ndone()\n");
        cn.bidcheck.verification.agent.SdkAgentInvestigator investigator=bridge(script,5,1);org.springframework.test.util.ReflectionTestUtils.setField(investigator,"auditDirectory",temp.resolve("audit").toString());
        java.util.Map<java.lang.String, java.lang.Object> result=investigator.investigate(start("audit","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(2,events.size());
        assertEquals(cn.bidcheck.compat.Java8Api.repeat("a", 32)+"-00001-output.json",((Map<?,?>)events.get(0).get("modelAudit")).get("file"));
        assertFalse(events.toString().contains("private model text"));assertFalse(events.toString().contains("sensitive"));assertFalse(events.toString().contains("../private.json"));
    }

    private Map<String,Object> reviewStart(String id,String... tools){
        Map<String,Object> request=new LinkedHashMap<>(start(id,tools));
        request.put("context",cn.bidcheck.compat.Java8Api.mapOf("workflowPolicy", "AGENT_LED_REVIEW_V1", "targetObligationIds", cn.bidcheck.compat.Java8Api.listOf(), "originalRequirement", "完整原始条款"));return request;
    }
    private Map<String,Object> approvedPlan(){return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "planReceipt", cn.bidcheck.compat.Java8Api.mapOf("approved", true, "planRevision", "plan-1", "targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("O1")), "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("O1", "o1-v1"));}
    private Map<String,Object> reviewCompletion(String revision){return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevision", "global-v2", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("O1", "o1-v2"), "computationReceipt", cn.bidcheck.compat.Java8Api.mapOf("kind", "HOST_RULE_RECOMPUTE", "status", "COMPLETED", "planRevision", revision, "targetEvidenceRevision", "global-v2", "targetCheckCompleted", true, "businessCheckCompleted", true));}
    @Test void reviewPlanAndJudgmentUseDynamicTargetsAndOnlyTrustedFinalReceipts()throws Exception{
        AtomicInteger calls=new AtomicInteger();List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={'conditions':[{'obligationId':'O1'}]})\njson.loads(sys.stdin.readline())\nemit('event',event='review_plan_approved',planRevision='plan-1',targetCount=1)\nemit('tool_call',id='2',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1','status':'PASS'})\njson.loads(sys.stdin.readline())\ndone('COMPLETED',businessDecision='PASS',completedTargetObligationIds=['invented'])\n"),5,1).investigate(reviewStart("review-roundtrip","submit_review_plan","submit_review_judgment"),(name,args)->{calls.incrementAndGet();return name.equals("submit_review_plan")?approvedPlan():reviewCompletion("plan-1");},events::add);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,calls.get());assertEquals(cn.bidcheck.compat.Java8Api.listOf("O1"),result.get("completedTargetObligationIds"));
        assertEquals("plan-1",result.get("approvedPlanRevision"));assertEquals("AGENT_LED_REVIEW_V1",result.get("workflowPolicy"));assertFalse(result.containsKey("businessDecision"));
        assertEquals(1L,events.get(0).get("targetCount"));
    }
    @Test void reviewToolsAreRejectedInLegacyMode()throws Exception{
        AtomicBoolean called=new AtomicBoolean();java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("done()\n"),5,1).investigate(start("legacy-review","submit_review_plan"),(n,a)->{called.set(true);return approvedPlan();},null);
        code("TOOL_NOT_ALLOWED",result);assertFalse(called.get());
    }
    @Test void approvingAReviewPlanDoesNotApproveTheBid()throws Exception{
        Map<String,Object> approval=new LinkedHashMap<>(approvedPlan());approval.putAll(reviewCompletion("plan-1"));approval.put("acceptedFactIds",cn.bidcheck.compat.Java8Api.listOf("invented"));
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n"),5,1)
            .investigate(reviewStart("plan-not-verdict","submit_review_plan"),(n,a)->approval,null);
        code("WORKER_RESULT_STATE_MISMATCH",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("acceptedFactIds"));
    }
    @Test void stalePlanReceiptCannotCompleteCurrentReview()throws Exception{
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1','status':'PASS'})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n"),5,1).investigate(reviewStart("stale-review","submit_review_plan","submit_review_judgment"),(n,a)->n.equals("submit_review_plan")?approvedPlan():reviewCompletion("old-plan"),null);
        code("WORKER_RESULT_STATE_MISMATCH",result);
    }
    @Test void reviewRequiresPlanAndMatchingTargetBeforeHostMutation()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        java.util.Map<java.lang.String, java.lang.Object> noPlan=bridge(script("emit('tool_call',id='1',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1'})\n"),5,1)
            .investigate(reviewStart("no-plan","submit_review_judgment"),(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf();},null);
        code("REVIEW_PLAN_REQUIRED",noPlan);assertEquals(0,calls.get());
        for(String target:cn.bidcheck.compat.Java8Api.listOf("O1", "foreign")){
            String revision=target.equals("O1")?"wrong-plan":"plan-1";calls.set(0);
            java.util.Map<java.lang.String, java.lang.Object> invalid=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_review_judgment',arguments={'targetObligationId':'"+target+"','planRevision':'"+revision+"'})\n"),5,1)
                .investigate(reviewStart("invalid-target","submit_review_plan","submit_review_judgment"),(n,a)->{calls.incrementAndGet();return approvedPlan();},null);
            code(target.equals("O1")?"REVIEW_PLAN_REVISION_MISMATCH":"CANDIDATE_TARGET_REQUIRED",invalid);assertEquals(1,calls.get());
        }
    }
    @Test void unrelatedTargetRevisionAndFactDoNotDiscardClosedTargetReceipt()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("scoped-close","recompute_check","read_section"));
        request.put("context",cn.bidcheck.compat.Java8Api.mapOf("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "B")));
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_section',arguments={'sourceId':'B-page'})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED')\n"),5,1).investigate(request,(name,args)->name.equals("recompute_check")?
                cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "a1", "B", "b1"), "obligations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "A", "status", "PASS", "gaps", cn.bidcheck.compat.Java8Api.listOf()))):
                cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("B-fact"), "targetEvidenceRevision", "global-new", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "a1", "B", "b2")),null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("A"),result.get("completedTargetObligationIds"));assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void relatedTargetRevisionReopensItsOwnClosedReceipt()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("scoped-reopen","recompute_check","read_section","submit_candidate_fact"));
        request.put("context",cn.bidcheck.compat.Java8Api.mapOf("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "B")));
        AtomicInteger calls=new AtomicInteger();java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_section',arguments={'sourceId':'changed-A'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='3',name='submit_candidate_fact',arguments={'targetObligationId':'A'})\njson.loads(sys.stdin.readline())\ndone('EVIDENCE_UPDATED')\n"),5,1).investigate(request,(name,args)->{
                calls.incrementAndGet();switch (name) {case "recompute_check":return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "a1", "B", "b1"), "obligations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "A", "status", "PASS", "gaps", cn.bidcheck.compat.Java8Api.listOf())));case "read_section":return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "a2", "B", "b1"));default:return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("new-A"));}},null);
        assertEquals("EVIDENCE_UPDATED",result.get("status"));assertEquals(3,calls.get());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));
    }
    @Test void blockedTargetResubmissionCannotReachHostAgain()throws Exception{
        Map<String,Object> request=new LinkedHashMap<>(start("blocked-gate","submit_candidate_fact"));request.put("context",cn.bidcheck.compat.Java8Api.mapOf("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "B")));
        AtomicInteger calls=new AtomicInteger();java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_candidate_fact',arguments={'targetObligationId':'A'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_candidate_fact',arguments={'targetObligationId':'A','field':'different-alias'})\n"),5,1).investigate(request,(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "REJECTED", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "a1", "B", "b1"), "semanticWorkItems", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "A", "evidenceRevision", "a1", "terminalForRevision", true, "targetExhaustedForRevision", true)));},null);
        code("TARGET_STATE_UNCHANGED",result);assertEquals(1,calls.get());
    }
    @Test void correctedReviewPlanRejectionDoesNotRemainATechnicalFailure()throws Exception{
        AtomicInteger calls=new AtomicInteger();java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={'coverageExplanation':'first'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='submit_review_plan',arguments={'coverageExplanation':'corrected'})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='3',name='submit_review_judgment',arguments={'targetObligationId':'O1','planRevision':'plan-1'})\njson.loads(sys.stdin.readline())\ndone('COMPLETED')\n"),5,1).investigate(reviewStart("rejected-plan-repair","submit_review_plan","submit_review_judgment"),(n,a)->{
                int index=calls.incrementAndGet();return index==1?cn.bidcheck.compat.Java8Api.mapOf("status", "REJECTED", "error", "PLAN_COVERAGE_REJECTED"):index==2?approvedPlan():reviewCompletion("plan-1");},null);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(0,result.get("failedToolCount"));assertEquals(1,result.get("validationRejectionCount"));
    }
    @Test void reviewReadToolsRequireApprovedCurrentTargetsBeforeDispatch()throws Exception{
        for(String tool:cn.bidcheck.compat.Java8Api.listOf("search_evidence", "read_section", "read_table_record")){
            AtomicInteger calls=new AtomicInteger();
            java.util.Map<java.lang.String, java.lang.Object> noPlan=bridge(script("emit('tool_call',id='1',name='"+tool+"',arguments={'targetObligationId':'O1','sourceId':'S1'})\n"),5,1)
                .investigate(reviewStart("read-without-plan",tool),(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf();},null);
            code("REVIEW_PLAN_REQUIRED",noPlan);assertEquals(0,calls.get());
            java.util.Map<java.lang.String, java.lang.Object> wrongTarget=bridge(script("emit('tool_call',id='1',name='submit_review_plan',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='"+tool+"',arguments={'targetObligationId':'foreign','sourceId':'S1'})\n"),5,1)
                .investigate(reviewStart("read-foreign-target","submit_review_plan",tool),(n,a)->{calls.incrementAndGet();return approvedPlan();},null);
            code("CANDIDATE_TARGET_REQUIRED",wrongTarget);assertEquals(1,calls.get());
        }
    }

    private Map<String,Object> targetStart(String id,String... tools){
        Map<String,Object> request=new LinkedHashMap<>(start(id,tools));
        request.put("context",cn.bidcheck.compat.Java8Api.mapOf("workflowPolicy", "TARGET_FACTS_V1", "targetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"), "serverPlanRevision", "rule-1", "serverPlanFingerprint", "frozen-plan", "targetEvidenceRevision", "global-1", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "A-1", "B", "B-1", "C", "C-1")));return request;
    }
    private Map<String,Object> canonicalReceipt(List<String> done,List<String> remaining){
        Map<String,Object> receipt=new LinkedHashMap<>();
        receipt.putAll(cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetReceiptAuthority", "CURRENT_SERVER_PROOF", "completedTargetObligationIds", done, "remainingTargetObligationIds", remaining, "serverPlanRevision", "rule-1", "serverPlanFingerprint", "frozen-plan"));
        receipt.putAll(cn.bidcheck.compat.Java8Api.mapOf("targetEvidenceRevision", "global-1", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "A-1", "B", "B-1", "C", "C-1"), "businessCheckCompleted", false, "targetCheckCompleted", remaining.isEmpty(), "obligations", cn.bidcheck.compat.Java8Api.toList(remaining.stream().map(id->cn.bidcheck.compat.Java8Api.mapOf("id", id, "status", "UNDETERMINED", "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "MISSING")))))));
        return receipt;
    }
    @SuppressWarnings("unchecked")
    @Test void targetProgressEventsAndLedgerCrossBridgeWithoutGrantingWorkerProofAuthority()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='no_evidence_progress',eventId=rid+':1',targetObligationId='A',operationKey='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='A-1',attemptForTargetGap=1,reason='NO_NEW_EVIDENCE',rawValue='private original text')\nemit('event',event='target_navigation_progress',eventId=rid+':2',targetId='B',newSourceCount=2,newTableRecord=True,acceptedFactDelta=0,closedTargetDelta=0,noEvidenceProgressRounds=1)\nemit('event',event='target_proof_progress',eventId=rid+':3',targetObligationId='B',stateAfter='PROVEN',firstFailureEventId=rid+':1',acceptedFactDelta=99,closedTargetDelta=1)\nemit('event',event='target_tool_failed',eventId=rid+':4',targetId='forged',rejectionCode='HOST_TOOL_FAILED',operationKey='a'*64)\nemit('event',event='investigation_failed',eventId=rid+':5',rejectionCode='HOST_TARGET_RECEIPT_INVALID',stage='WORKER_EXECUTION')\nrow=dict(targetObligationId='A',progressScope='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='A-1',state='NO_MORE_EVIDENCE',visited=True,noEvidenceProgressRounds=2,maxNoEvidenceProgressRounds=2,acceptedFactCount=0,closedTargetCount=0,navigationCount=1,firstFailureEventId=rid+':1',rejectionCode='NO_NEW_EVIDENCE',resumeTrigger='RELEVANT_RULE_OR_EVIDENCE_CHANGED',rawValue='private original text')\nforged=dict(row,targetObligationId='forged',firstFailureEventId='private original text')\nfake_proof=dict(row,targetObligationId='B',relevantEvidenceRevision='B-1',state='PROVEN',closedTargetCount=9)\ndone(firstFailureEventId=rid+':1',targetProgressLedger=[row,forged,fake_proof],exhaustedTargetObligationIds=['A','forged'],remainingTargetObligationIds=['C','forged'],completedTargetObligationIds=['B'],businessUnclosedTargetObligationIds=[])\n"),5,1).investigate(targetStart("ledger-roundtrip","read_section"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),events::add);
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(5,events.size());
        assertEquals("ledger-roundtrip:4",result.get("firstFailureEventId")); // ordinary no-evidence progress is not a failure
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("A"),result.get("exhaustedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"),result.get("businessUnclosedTargetObligationIds"));
        List<Map<String,Object>> ledger=(List<Map<String,Object>>)result.get("targetProgressLedger");assertEquals(2,ledger.size());
        assertEquals("NO_MORE_EVIDENCE",ledger.get(0).get("state"));assertEquals(false,ledger.get(0).get("businessProof"));
        assertEquals("RUNNING",ledger.get(1).get("state"));assertEquals(0L,ledger.get(1).get("closedTargetCount"));
        assertEquals("RUNNING",events.get(2).get("stateAfter"));assertFalse(events.get(3).containsKey("targetId"));
        assertFalse(result.toString().contains("private original text"));assertFalse(events.toString().contains("private original text"));
        assertEquals(false,result.get("businessCheckCompleted"));
    }
    @Test void eventReferencesMustBeObservedOrExplicitlyInheritedAndNeverContainProse()throws Exception{
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("done(firstFailureEventId=rid+':999',targetProgressLedger=[])\n"),5,1)
            .investigate(targetStart("unobserved-event","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        assertFalse(result.containsKey("firstFailureEventId"));
        List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> rejected=bridge(script("emit('event',event='target_tool_failed',eventId='private original text',firstFailureEventId='other-request:1',targetId='forged',rejectionCode='private original text')\ndone(firstFailureEventId='private original text')\n"),5,1)
            .investigate(targetStart("event-redaction","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),events::add);
        assertFalse(rejected.containsKey("firstFailureEventId"));assertFalse(events.get(0).containsKey("eventId"));assertFalse(events.get(0).containsKey("rejectionCode"));
        Map<String,Object> request=targetStart("inherited-event","search_evidence");
        Map<String,Object> context=new LinkedHashMap<>((Map<String,Object>)request.get("context"));
        context.put("targetProgressLedger",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "A", "firstFailureEventId", "prior-request:12")));request.put("context",context);
        java.util.Map<java.lang.String, java.lang.Object> inherited=bridge(script("done(firstFailureEventId='prior-request:12')\n"),5,1).investigate(request,(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        assertEquals("prior-request:12",inherited.get("firstFailureEventId"));
    }
    @Test void diagnosticLedgerHasFiniteBoundsWithoutLimitingBusinessTargetSet()throws Exception{
        Map<String,Object> request=targetStart("ledger-cap","search_evidence");Map<String,Object> context=new LinkedHashMap<>((Map<String,Object>)request.get("context"));
        List<String> targets=new ArrayList<>();Map<String,String> versions=new LinkedHashMap<>();for(int i=0;i<300;i++){targets.add("T"+i);versions.put("T"+i,"v1");}
        context.put("targetObligationIds",targets);context.put("targetEvidenceRevisions",versions);request.put("context",context);
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("rows=[dict(targetObligationId=t,progressScope='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='v1',state='UNVISITED',visited=False) for t in start['context']['targetObligationIds']]\ndone(targetProgressLedger=rows)\n"),5,1).investigate(request,(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        assertEquals("NO_PROGRESS",result.get("status"));assertEquals(256,((List<?>)result.get("targetProgressLedger")).size());assertEquals(true,result.get("targetProgressLedgerTruncated"));
        assertEquals(300,((List<?>)result.get("businessUnclosedTargetObligationIds")).size());
    }
    @Test void coveredAnyHostReceiptClosesOnlyItsTargetsAndUnfinishedTargetStillExecutes()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_section',arguments={'targetObligationId':'C','sourceId':'C-page'})\njson.loads(sys.stdin.readline())\ndone('COMPLETED',completedTargetObligationIds=['forged'])\n"),5,1).investigate(targetStart("canonical-any","recompute_check","read_section"),(name,args)->{
                calls.incrementAndGet();if(name.equals("recompute_check"))return canonicalReceipt(cn.bidcheck.compat.Java8Api.listOf("A", "B"),cn.bidcheck.compat.Java8Api.listOf("C"));
                Map<String,Object> receipt=canonicalReceipt(cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"),cn.bidcheck.compat.Java8Api.listOf());receipt.put("kind","HOST_RULE_RECOMPUTE");
                return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf("host-C"), "targetEvidenceRevision", "global-1", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "A-1", "B", "B-1", "C", "C-1"), "computationReceipt", receipt);
            },null);
        assertEquals("COMPLETED",result.get("status"),result.toString());assertEquals(2,calls.get());assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"),result.get("completedTargetObligationIds"));
        assertEquals(false,result.get("businessCheckCompleted"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("businessUnclosedTargetObligationIds"));
    }
    @Test void coveredAnyTargetOrForgedTargetCannotReachHostAgain()throws Exception{
        for(String target:cn.bidcheck.compat.Java8Api.listOf("A", "B", "forged")){
            AtomicInteger calls=new AtomicInteger();
            java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='recompute_check',arguments={})\njson.loads(sys.stdin.readline())\nemit('tool_call',id='2',name='read_section',arguments={'targetObligationId':'"+target+"','sourceId':'page'})\n"),5,1)
                .investigate(targetStart("canonical-gate","recompute_check","read_section"),(name,args)->{calls.incrementAndGet();return canonicalReceipt(cn.bidcheck.compat.Java8Api.listOf("A", "B"),cn.bidcheck.compat.Java8Api.listOf("C"));},null);
            code(target.equals("forged")?"CANDIDATE_TARGET_REQUIRED":"TARGET_STATE_UNCHANGED",result);assertEquals(1,calls.get());
        }
    }
    @Test void malformedOrStaleCanonicalPartitionCannotCertifyAnyTarget()throws Exception{
        List<Map<String,Object>> mutations=cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("targetReceiptAuthority", "WORKER_ASSERTION"), cn.bidcheck.compat.Java8Api.mapOf("completedTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "B", "C")), cn.bidcheck.compat.Java8Api.mapOf("remainingTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf()), cn.bidcheck.compat.Java8Api.mapOf("completedTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "A")), cn.bidcheck.compat.Java8Api.mapOf("completedTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "forged")), cn.bidcheck.compat.Java8Api.mapOf("serverPlanRevision", "old-rule"), cn.bidcheck.compat.Java8Api.mapOf("serverPlanFingerprint", "other-plan"), cn.bidcheck.compat.Java8Api.mapOf("targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "old", "B", "B-1", "C", "C-1")), cn.bidcheck.compat.Java8Api.mapOf("targetCheckCompleted", true));
        for(Map<String,Object> mutation:mutations){
            Map<String,Object> receipt=canonicalReceipt(cn.bidcheck.compat.Java8Api.listOf("A", "B"),cn.bidcheck.compat.Java8Api.listOf("C"));receipt.putAll(mutation);receipt.put("kind","HOST_RULE_RECOMPUTE");
            java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='read_section',arguments={'targetObligationId':'A'})\njson.loads(sys.stdin.readline())\ndone()\n"),5,1)
                .investigate(targetStart("bad-canonical","read_section"),(name,args)->cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "targetEvidenceRevision", "global-1", "targetEvidenceRevisions", cn.bidcheck.compat.Java8Api.mapOf("A", "A-1", "B", "B-1", "C", "C-1"), "computationReceipt", receipt),null);
            code("HOST_TARGET_RECEIPT_INVALID",result);assertEquals(false,result.get("businessCheckCompleted"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));
        }
    }
    @Test void topLevelNavigationCanonicalIdsDoNotReplaceEvaluatedProof()throws Exception{
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='read_table_record',arguments={'targetObligationId':'C'})\njson.loads(sys.stdin.readline())\ndone(completedTargetObligationIds=['A','B'])\n"),5,1)
            .investigate(targetStart("navigation-canonical","read_table_record"),(n,a)->canonicalReceipt(cn.bidcheck.compat.Java8Api.listOf("A", "B"),cn.bidcheck.compat.Java8Api.listOf("C")),null);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B", "C"),result.get("businessUnclosedTargetObligationIds"));
    }

    @SuppressWarnings("unchecked") private Map<String,Object> startWithCanonical(String id,String... tools){
        Map<String,Object> start=targetStart(id,tools);Map<String,Object> context=new LinkedHashMap<>((Map<String,Object>)start.get("context"));
        context.putAll(canonicalReceipt(cn.bidcheck.compat.Java8Api.listOf("A", "B"),cn.bidcheck.compat.Java8Api.listOf("C")));start.put("context",context);return start;
    }
    @Test void canonicalStartClosesCoveredTargetsBeforeAnyToolCall()throws Exception{
        for(String target:cn.bidcheck.compat.Java8Api.listOf("A", "B", "forged")){
            AtomicInteger calls=new AtomicInteger();java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='read_section',arguments={'targetObligationId':'"+target+"','sourceId':'page'})\n"),5,1)
                .investigate(startWithCanonical("initial-canonical-gate","read_section"),(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf();},null);
            code(target.equals("forged")?"CANDIDATE_TARGET_REQUIRED":"TARGET_STATE_UNCHANGED",result);assertEquals(0,calls.get());assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B"),result.get("completedTargetObligationIds"));
        }
    }
    @Test void canonicalStartAllowsOnlyItsRemainingTargetAndPreservesBusinessUnclosed()throws Exception{
        AtomicInteger calls=new AtomicInteger();java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='read_section',arguments={'targetObligationId':'C','sourceId':'page'})\njson.loads(sys.stdin.readline())\ndone()\n"),5,1)
            .investigate(startWithCanonical("initial-canonical-valid","read_section"),(n,a)->{calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED");},null);
        assertEquals(1,calls.get());assertEquals("NO_PROGRESS",result.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("A", "B"),result.get("completedTargetObligationIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("C"),result.get("businessUnclosedTargetObligationIds"));
    }
    @SuppressWarnings("unchecked")
    @Test void malformedCanonicalStartNeverLaunchesWorkerOrReachesHost()throws Exception{
        for(Map<String,Object> mutation:cn.bidcheck.compat.Java8Api.<Map<String,Object>>listOf(cn.bidcheck.compat.Java8Api.mapOf("targetReceiptAuthority", "WORKER_ASSERTION"), cn.bidcheck.compat.Java8Api.mapOf("completedTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf("A", "A")), cn.bidcheck.compat.Java8Api.mapOf("remainingTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf("B", "C")), cn.bidcheck.compat.Java8Api.mapOf("remainingTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf()), cn.bidcheck.compat.Java8Api.mapOf("completedTargetObligationIds", cn.bidcheck.compat.Java8Api.listOf("forged")), cn.bidcheck.compat.Java8Api.mapOf("targetCheckCompleted", true))){
            java.util.Map<java.lang.String, java.lang.Object> request=startWithCanonical("initial-canonical-invalid","read_section");java.util.LinkedHashMap<java.lang.String, java.lang.Object> context=new LinkedHashMap<>((Map<String,Object>)request.get("context"));context.putAll(mutation);request.put("context",context);
            java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("raise RuntimeError('must not start')\n"),5,1).investigate(request,(n,a)->{fail("invalid start reached Host");return cn.bidcheck.compat.Java8Api.mapOf();},null);
            code("HOST_TARGET_RECEIPT_INVALID",result);assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));
        }
    }
    @Test void ordinaryNoEvidenceProgressNeverBecomesFirstFailure()throws Exception{
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='no_evidence_progress',eventId=rid+':1',targetObligationId='A')\ndone(firstFailureEventId=rid+':1')\n"),5,1)
            .investigate(targetStart("no-proof-not-error","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        assertEquals("NO_PROGRESS",result.get("status"));assertFalse(result.containsKey("firstFailureEventId"));
    }
    @Test void trueFailureAfterOrdinaryNoProgressIsTheFirstFailure()throws Exception{
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('event',event='no_evidence_progress',eventId=rid+':1',targetObligationId='A')\nemit('event',event='target_tool_failed',eventId=rid+':2',targetObligationId='C',rejectionCode='SOURCE_NOT_CURRENT')\ndone(firstFailureEventId=rid+':1')\n"),5,1)
            .investigate(targetStart("proof-before-failure","search_evidence"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf(),null);
        assertEquals("proof-before-failure:2",result.get("firstFailureEventId"));
    }

    @Test void inferenceValidationRejectionIsNotAnUnresolvedServiceFailure()throws Exception{
        for(String error:cn.bidcheck.compat.Java8Api.listOf("INFERENCE_PROTOCOL_INVALID", "INFERENCE_CITATION_UNGROUNDED", "INFERENCE_TYPED_FACT_REQUIRED", "INFERENCE_PREVIOUSLY_REJECTED", "INFERENCE_CURRENT_SCOPE_CONFLICT")){
            java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_candidate_fact',arguments={'targetObligationId':'A','fact':{}})\njson.loads(sys.stdin.readline())\ndone()\n"),5,1)
                .investigate(targetStart("inference-rejection","submit_candidate_fact"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "REJECTED", "error", error, "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf()),null);
            assertEquals("NO_PROGRESS",result.get("status"),result.toString());
            assertEquals(0,((Number)result.get("failedToolCount")).intValue());assertEquals(1,((Number)result.get("validationRejectionCount")).intValue());
            assertEquals(false,result.get("businessCheckCompleted"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void pendingConfirmationSurvivesDiagnosticsButNeverCertifiesCompletion()throws Exception{
        List<Map<String,Object>> events=new ArrayList<>();
        java.util.Map<java.lang.String, java.lang.Object> result=bridge(script("emit('tool_call',id='1',name='submit_candidate_fact',arguments={'targetObligationId':'A','fact':{}})\njson.loads(sys.stdin.readline())\nemit('event',event='evidence_pending',targetObligationId='A',stateAfter='NEEDS_CONFIRMATION')\nrow=dict(targetObligationId='A',progressScope='a'*64,ruleVersion='rule-1',relevantEvidenceRevision='A-1',state='NEEDS_CONFIRMATION',visited=True)\ndone(pendingConfirmationTargetObligationIds=['A','forged'],targetProgressLedger=[row])\n"),5,1).investigate(targetStart("pending-confirmation","submit_candidate_fact"),(n,a)->cn.bidcheck.compat.Java8Api.mapOf("status", "NEEDS_CONFIRMATION", "acceptedFactIds", cn.bidcheck.compat.Java8Api.listOf(), "confirmationId", "claim"),events::add);
        assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(cn.bidcheck.compat.Java8Api.listOf("A"),result.get("pendingConfirmationTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),result.get("completedTargetObligationIds"));assertEquals(false,result.get("businessCheckCompleted"));
        assertEquals("NEEDS_CONFIRMATION",((List<Map<String,Object>>)result.get("targetProgressLedger")).get(0).get("state"));
        assertTrue(events.stream().anyMatch(e->"NEEDS_CONFIRMATION".equals(e.get("stateAfter"))));
    }

}

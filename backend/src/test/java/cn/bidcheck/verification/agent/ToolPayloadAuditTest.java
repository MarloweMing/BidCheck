package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ToolPayloadAuditTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private static final String KEY="tool-audit-test-credential";
    private static final String RUN="11111111-1111-1111-1111-111111111111",CP="22222222-2222-2222-2222-222222222222";
    private Map<String,Object> start(){return Map.of("type","start","protocol","bidcheck-agent-1","requestId","tool-audit-run","auditContext",Map.of("runId",RUN,"checkpointId",CP),"context",Map.of("domain","SOURCE_GROUNDED_EVIDENCE"),"tools",List.of(Map.of("name","search_evidence","description","读取原文","parameters",Map.of("type","object","properties",Map.of()))));}
    @Test void disabledHasNoFilesystemSideEffect(){var metadata=new ToolPayloadAudit("",KEY).record(start(),"request",1,"call","search_evidence","input",Map.of());assertEquals("DISABLED",metadata.get("status"));assertEquals(0,temp.toFile().list().length);}
    @Test void actualPayloadIsPreservedWithCorrelationAndCredentialsRedacted()throws Exception{
        Path folder=temp.resolve("payloads");var audit=new ToolPayloadAudit(folder.toString(),KEY);String quote="姓名 项目负责人 身份证号\n".repeat(3000);
        var payload=Map.of("arguments",Map.of("query","项目负责人"),"result",Map.of("quote",quote,"headers",Map.of("Authorization","secret-header"),"password","secret-password","note",KEY));
        var meta=audit.record(start(),"tool-audit-run",2,"call-2","search_evidence","output",payload);assertEquals("SAVED",meta.get("status"));Path file=folder.resolve(meta.get("file").toString());byte[] raw=Files.readAllBytes(file);var saved=json.readTree(raw);
        assertEquals(RUN,saved.path("runId").asText());assertEquals(CP,saved.path("checkpointId").asText());assertEquals(quote,saved.path("payload").path("result").path("quote").asText());assertEquals("项目负责人",saved.path("payload").path("arguments").path("query").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)),meta.get("sha256"));assertEquals(raw.length,meta.get("bytes"));String text=new String(raw,java.nio.charset.StandardCharsets.UTF_8);assertFalse(text.contains(KEY));assertFalse(text.contains("secret-header"));assertFalse(text.contains("secret-password"));
    }
    @Test void hashedNamesCannotEscapeAndDuplicateDoesNotOverwrite()throws Exception{
        var audit=new ToolPayloadAudit(temp.toString(),KEY);var first=audit.record(start(),"../../outside",1,"call","read_section","input",Map.of("sourceId","S1"));assertEquals("SAVED",first.get("status"));assertTrue(first.get("file").toString().matches("[a-f0-9]{32}-tool-00001-input\\.json"));byte[] before=Files.readAllBytes(temp.resolve(first.get("file").toString()));
        assertEquals("WRITE_FAILED",audit.record(start(),"../../outside",1,"call","read_section","input",Map.of("sourceId","changed")).get("status"));assertArrayEquals(before,Files.readAllBytes(temp.resolve(first.get("file").toString())));
    }
    @Test void tooLargeIsExplicitlyOmittedAndHashRetained()throws Exception{
        var meta=new ToolPayloadAudit(temp.toString(),KEY,1024).record(start(),"request",1,"call","read_section","output",Map.of("quote","证据".repeat(2000)));assertEquals("PAYLOAD_OMITTED",meta.get("status"));var body=json.readTree(temp.resolve(meta.get("file").toString()).toFile());assertTrue(body.path("payloadOmitted").asBoolean());assertFalse(body.has("payload"));assertTrue(body.path("payloadBytes").asInt()>1024);assertTrue(body.path("payloadSha256").asText().matches("[a-f0-9]{64}"));
    }
    @Test void failureIsMetadataInsteadOfThrowing()throws Exception{Path file=temp.resolve("not-directory");Files.writeString(file,"preserved");assertEquals("WRITE_FAILED",new ToolPayloadAudit(file.toString(),KEY).record(start(),"request",1,"call","read_section","input",Map.of()).get("status"));assertEquals("preserved",Files.readString(file));}
    private Path python(){Path here=Path.of("").toAbsolutePath();for(Path root:List.of(here,here.getParent()))for(String suffix:List.of(".local/agent-venv/Scripts/python.exe",".local/agent-venv/bin/python")){Path candidate=root.resolve(suffix);if(Files.isRegularFile(candidate))return candidate;}org.junit.jupiter.api.Assumptions.assumeTrue(false,"local test Python required");return null;}
    private SdkAgentInvestigator bridge(Path auditFolder)throws Exception{
        Path worker=temp.resolve("worker.py");Files.writeString(worker,"""
            import sys,json
            sys.stdin.reconfigure(encoding='utf-8');sys.stdout.reconfigure(encoding='utf-8',line_buffering=True)
            start=json.loads(sys.stdin.readline());rid=start['requestId']
            print(json.dumps(dict(type='tool_call',protocol='bidcheck-agent-1',requestId=rid,id='call-1',name='search_evidence',arguments={'query':'项目负责人'}),ensure_ascii=False),flush=True)
            answer=sys.stdin.readline()
            if answer:
              result=json.loads(answer)['result']
              assert result['quote']=='最后一次工具返回原文'
              print(json.dumps(dict(type='result',protocol='bidcheck-agent-1',requestId=rid,status='NO_PROGRESS',code='',sdk='openai-agents',sdkVersion='0.24.0',tracingExportEnabled=False,modelCalls=0)),flush=True)
            """,java.nio.charset.StandardCharsets.UTF_8);
        var bridge=new SdkAgentInvestigator(true,python().toString(),worker.toString(),"https://dashscope.aliyuncs.com/compatible-mode/v1",KEY,"qwen-plus",15,1);var field=SdkAgentInvestigator.class.getDeclaredField("auditDirectory");field.setAccessible(true);field.set(bridge,auditFolder.toString());return bridge;
    }
    @Test void bridgePreservesLastToolResponseWithoutAnotherModelCall()throws Exception{
        Path folder=temp.resolve("roundtrip");List<Map<String,Object>> events=new ArrayList<>();var result=bridge(folder).investigate(start(),(name,args)->Map.of("quote","最后一次工具返回原文"),events::add);assertEquals("NO_PROGRESS",result.get("status"),result.toString());assertEquals(2,events.size());assertTrue(events.stream().allMatch(e->"tool_payload_audit".equals(e.get("event"))));var output=events.get(1);assertEquals("output",output.get("direction"));var meta=(Map<?,?>)output.get("toolAudit");assertEquals("SAVED",meta.get("status"));assertEquals("最后一次工具返回原文",json.readTree(folder.resolve(meta.get("file").toString()).toFile()).path("payload").path("quote").asText());
    }
    @Test void auditWriteFailureDoesNotFailSuccessfulTool()throws Exception{
        Path file=temp.resolve("not-folder");Files.writeString(file,"preserved");List<Map<String,Object>> events=new ArrayList<>();var result=bridge(file).investigate(start(),(name,args)->Map.of("quote","最后一次工具返回原文"),events::add);assertEquals("NO_PROGRESS",result.get("status"));assertEquals(2,events.size());assertTrue(events.stream().allMatch(e->"WRITE_FAILED".equals(((Map<?,?>)e.get("toolAudit")).get("status"))));
    }
    @Test void handlerExceptionGetsSafeErrorReceipt()throws Exception{
        Path folder=temp.resolve("failure");List<Map<String,Object>> events=new ArrayList<>();var result=bridge(folder).investigate(start(),(name,args)->{throw new IllegalStateException(KEY+" should not be logged");},events::add);assertEquals("TOOL_HANDLER_FAILED",result.get("errorCode"));var error=events.stream().filter(e->"error".equals(e.get("direction"))).findFirst().orElseThrow();var meta=(Map<?,?>)error.get("toolAudit");var text=Files.readString(folder.resolve(meta.get("file").toString()));assertFalse(text.contains(KEY));assertEquals("TOOL_HANDLER_FAILED",json.readTree(text).path("payload").path("errorCode").asText());
    }
}

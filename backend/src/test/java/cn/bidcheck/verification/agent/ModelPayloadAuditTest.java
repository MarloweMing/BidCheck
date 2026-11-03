package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelPayloadAuditTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private static Map<String,Object> m(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    @Test void recordsOriginalPayloadWhileRedactingCredentialFieldsAndText()throws Exception{
        ModelPayloadAudit audit=new ModelPayloadAudit(temp.toString(),"the-key");Map<String,Object> meta=audit.record("request-1",2,"input",m("messages",Arrays.asList(m("role","user","content","原文 the-key")),"api_key","another-secret","nested",m("headers",m("Authorization","secret"))));
        assertEquals("SAVED",meta.get("status"));assertTrue(meta.get("file").toString().matches("[a-f0-9]{32}-00002-input\\.json"));byte[] bytes=Files.readAllBytes(temp.resolve(meta.get("file").toString()));String body=new String(bytes,StandardCharsets.UTF_8);assertFalse(body.contains("the-key"));assertFalse(body.contains("another-secret"));assertFalse(body.contains("Authorization"));JsonNode node=json.readTree(bytes);assertEquals("原文 [REDACTED]",node.path("payload").path("messages").get(0).path("content").asText());assertEquals(bytes.length,meta.get("bytes"));assertTrue(meta.get("sha256").toString().matches("[a-f0-9]{64}"));
    }
    @Test void exclusiveHashedFilesPreservePreviousAudit()throws Exception{
        ModelPayloadAudit audit=new ModelPayloadAudit(temp.toString(),"");Map<String,Object> first=audit.record("../../escape",1,"output",m("text","original"));byte[] before=Files.readAllBytes(temp.resolve(first.get("file").toString()));assertEquals("WRITE_FAILED",audit.record("../../escape",1,"output",m("text","changed")).get("status"));assertArrayEquals(before,Files.readAllBytes(temp.resolve(first.get("file").toString())));
    }
    @Test void disabledInvalidAndOversizedPayloadsHaveExplicitMetadata()throws Exception{
        assertEquals("DISABLED",new ModelPayloadAudit("","").record("r",1,"input",m()).get("status"));assertEquals("INVALID_METADATA",new ModelPayloadAudit(temp.toString(),"").record("r",0,"input",m()).get("status"));
        char[] chars=new char[3000];Arrays.fill(chars,'x');Map<String,Object> large=new ModelPayloadAudit(temp.toString(),"",1024).record("r",1,"input",m("text",new String(chars)));assertEquals("PAYLOAD_OMITTED",large.get("status"));JsonNode body=json.readTree(temp.resolve(large.get("file").toString()).toFile());assertFalse(body.has("payload"));assertTrue(body.path("payloadBytes").asInt()>1024);assertTrue(body.path("payloadSha256").asText().matches("[a-f0-9]{64}"));
        assertEquals("WRITE_FAILED",new ModelPayloadAudit(temp.toString(),"").record("nonfinite",1,"output",m("value",Double.NaN)).get("status"));
    }
}

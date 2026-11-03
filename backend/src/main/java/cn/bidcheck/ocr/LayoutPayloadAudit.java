package cn.bidcheck.ocr;
import cn.bidcheck.compat.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Local audit of layout transport bodies. HTTP credentials/headers are never recorded. */
final class LayoutPayloadAudit {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int LIMIT=10*1024*1024;
    static final class Call {
        final String traceId=UUID.randomUUID().toString();
        private final Path root;private final String secret;private final Map<String,Object> context;
        private final long started=System.nanoTime();private Map<String,Object> input=cn.bidcheck.compat.Java8Api.mapOf(),output=cn.bidcheck.compat.Java8Api.mapOf();private final List<Map<String,Object>> attempts=new ArrayList<>(),transportAttempts=new ArrayList<>();private Map<String,Object> finished;
        Call(String model,String imageHash,String textHash,String secret){
            String configured=System.getProperty("bidcheck.agent.audit-directory",Objects.toString(System.getenv("BIDCHECK_AGENT_AUDIT_DIRECTORY"),""));
            root=cn.bidcheck.compat.Java8Api.isBlank(configured)?null:java.nio.file.Paths.get(configured).resolve("page-layout");this.secret=secret;
            context=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("traceId", traceId, "model", model, "imageHash", imageHash, "transcriptionHash", textHash, "boundary", "ACTUAL_HTTP_JSON_BODY_NO_HEADERS", "createdAt", Instant.now().toString()));
        }
        void validationFailure(String reason){if(reason!=null&&!cn.bidcheck.compat.Java8Api.isBlank(reason))context.put("validationReason",reason);}
        void coordinates(String space,String format,int width,int height){context.put("coordinateTransform",cn.bidcheck.compat.Java8Api.mapOf("declaredCoordinateSystem", space, "declaredBboxFormat", format, "actualImageWidth", width, "actualImageHeight", height, "outputCoordinateSystem", "PIXEL_TOP_LEFT", "inferred", false, "clamped", false));}
        void wireRequest(String schema,int tokens,int cells,long timeoutMillis,int attempts){context.put("requestLayoutContract",cn.bidcheck.compat.Java8Api.mapOf("schemaVersion", schema, "maxOutputTokens", tokens, "maxCells", cells, "attemptTimeoutMs", timeoutMillis, "maxAttempts", attempts));}
        void wireResponse(String schema,int boxes,int cells){context.put("responseLayoutContract",cn.bidcheck.compat.Java8Api.mapOf("schemaVersion", schema, "returnedBoxes", boxes, "returnedCells", cells, "completeTableCoverageClaimed", false));}
        void request(byte[] body){input=write("input",body,cn.bidcheck.compat.Java8Api.mapOf("payloadKind", "HTTP_REQUEST_BODY"));}
        void response(int attempt,int status,byte[] body){output=write("output-"+attempt,body,cn.bidcheck.compat.Java8Api.mapOf("payloadKind", "HTTP_RESPONSE_BODY", "httpStatus", status, "attempt", attempt));attempts.add(cn.bidcheck.compat.Java8Api.mapOf("attempt", attempt, "httpStatus", status, "outputAudit", output));}
        void failure(int attempt,String phase,String code,Integer status,long durationMillis){
            Map<String,Object> failure=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("attempt", attempt, "phase", phase, "code", code, "durationMs", durationMillis, "responseReceived", false));
            if(status!=null)failure.put("httpStatus",status);transportAttempts.add(cn.bidcheck.compat.Java8Api.mapCopyOf(failure));
            LoggerFactory.getLogger(LayoutPayloadAudit.class).warn("bidcheck.page_layout_attempt traceId={} attempt={} phase={} code={} durationMs={} responseReceived=false",traceId,attempt,phase,code,durationMillis);
        }
        Map<String,Object> finish(String state,String code){
            if(finished!=null)return finished;Map<String,Object> outcome=new LinkedHashMap<>(context);
            outcome.put("status",state);outcome.put("errorCode",code);outcome.put("durationMs",(System.nanoTime()-started)/1_000_000);outcome.put("inputAudit",input);outcome.put("outputAudit",output);outcome.put("responseAttempts",cn.bidcheck.compat.Java8Api.listCopyOf(attempts));
            outcome.put("transportAttempts",cn.bidcheck.compat.Java8Api.listCopyOf(transportAttempts));
            if(!transportAttempts.isEmpty())outcome.put("firstFailureCode",transportAttempts.get(0).get("code"));
            LoggerFactory.getLogger(LayoutPayloadAudit.class).info("bidcheck.page_layout_transport traceId={} model={} imageHash={} transcriptionHash={} status={} errorCode={} validationReason={} durationMs={} inputAudit={} outputAudit={}",traceId,context.get("model"),context.get("imageHash"),context.get("transcriptionHash"),state,code,context.get("validationReason"),outcome.get("durationMs"),input,output);
            try{outcome.put("receiptAudit",write("receipt",JSON.writeValueAsBytes(outcome),cn.bidcheck.compat.Java8Api.mapOf("payloadKind", "LAYOUT_CALL_RECEIPT")));}catch(Exception impossible){outcome.put("receiptAudit",cn.bidcheck.compat.Java8Api.mapOf("status", "WRITE_FAILED"));}
            finished=cn.bidcheck.compat.Java8Api.mapCopyOf(outcome);return finished;
        }
        private Map<String,Object> write(String direction,byte[] bytes,Map<String,Object> extra){
            if(root==null)return cn.bidcheck.compat.Java8Api.mapOf("status", "DISABLED");
            try{
                String text=new String(bytes,StandardCharsets.UTF_8);if(secret!=null&&!cn.bidcheck.compat.Java8Api.isBlank(secret))text=text.replace(secret,"[REDACTED]");
                text=text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [REDACTED]");
                Map<String,Object> payload=new LinkedHashMap<>(context);payload.putAll(extra);payload.put("direction",direction);payload.put("rawBytes",bytes.length);
                boolean omitted=bytes.length>LIMIT;payload.put("status",omitted?"PAYLOAD_OMITTED":"SAVED");
                if(omitted)payload.put("reason","AUDIT_PAYLOAD_LIMIT");else payload.put("bodyUtf8",text);
                byte[] encoded=JSON.writeValueAsBytes(payload);Files.createDirectories(root);Path file=root.resolve(traceId+"-"+direction+".json");Files.write(file,encoded,StandardOpenOption.CREATE_NEW);
                return cn.bidcheck.compat.Java8Api.mapOf("status", omitted?"PAYLOAD_OMITTED":"SAVED", "file", file.getFileName().toString(), "directory", "page-layout", "bytes", encoded.length, "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
            }catch(Exception failure){LoggerFactory.getLogger(LayoutPayloadAudit.class).warn("bidcheck.page_layout_audit traceId={} direction={} status=WRITE_FAILED errorType={}",traceId,direction,failure.getClass().getSimpleName());return cn.bidcheck.compat.Java8Api.mapOf("status", "WRITE_FAILED", "errorType", failure.getClass().getSimpleName());}
        }
    }
}

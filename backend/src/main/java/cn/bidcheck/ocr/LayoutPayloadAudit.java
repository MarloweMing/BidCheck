package cn.bidcheck.ocr;

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
        private final long started=System.nanoTime();private Map<String,Object> input=Map.of(),output=Map.of();private final List<Map<String,Object>> attempts=new ArrayList<>(),transportAttempts=new ArrayList<>();private Map<String,Object> finished;
        Call(String model,String imageHash,String textHash,String secret){
            String configured=System.getProperty("bidcheck.agent.audit-directory",Objects.toString(System.getenv("BIDCHECK_AGENT_AUDIT_DIRECTORY"),""));
            root=configured.isBlank()?null:Path.of(configured).resolve("page-layout");this.secret=secret;
            context=new LinkedHashMap<>(Map.of("traceId",traceId,"model",model,"imageHash",imageHash,"transcriptionHash",textHash,"boundary","ACTUAL_HTTP_JSON_BODY_NO_HEADERS","createdAt",Instant.now().toString()));
        }
        void validationFailure(String reason){if(reason!=null&&!reason.isBlank())context.put("validationReason",reason);}
        void coordinates(String space,String format,int width,int height){context.put("coordinateTransform",Map.of("declaredCoordinateSystem",space,"declaredBboxFormat",format,"actualImageWidth",width,"actualImageHeight",height,"outputCoordinateSystem","PIXEL_TOP_LEFT","inferred",false,"clamped",false));}
        void wireRequest(String schema,int tokens,int cells,long timeoutMillis,int attempts){context.put("requestLayoutContract",Map.of("schemaVersion",schema,"maxOutputTokens",tokens,"maxCells",cells,"attemptTimeoutMs",timeoutMillis,"maxAttempts",attempts));}
        void wireResponse(String schema,int boxes,int cells){context.put("responseLayoutContract",Map.of("schemaVersion",schema,"returnedBoxes",boxes,"returnedCells",cells,"completeTableCoverageClaimed",false));}
        void request(byte[] body){input=write("input",body,Map.of("payloadKind","HTTP_REQUEST_BODY"));}
        void response(int attempt,int status,byte[] body){output=write("output-"+attempt,body,Map.of("payloadKind","HTTP_RESPONSE_BODY","httpStatus",status,"attempt",attempt));attempts.add(Map.of("attempt",attempt,"httpStatus",status,"outputAudit",output));}
        void failure(int attempt,String phase,String code,Integer status,long durationMillis){
            Map<String,Object> failure=new LinkedHashMap<>(Map.of("attempt",attempt,"phase",phase,"code",code,"durationMs",durationMillis,"responseReceived",false));
            if(status!=null)failure.put("httpStatus",status);transportAttempts.add(Map.copyOf(failure));
            LoggerFactory.getLogger(LayoutPayloadAudit.class).warn("bidcheck.page_layout_attempt traceId={} attempt={} phase={} code={} durationMs={} responseReceived=false",traceId,attempt,phase,code,durationMillis);
        }
        Map<String,Object> finish(String state,String code){
            if(finished!=null)return finished;Map<String,Object> outcome=new LinkedHashMap<>(context);
            outcome.put("status",state);outcome.put("errorCode",code);outcome.put("durationMs",(System.nanoTime()-started)/1_000_000);outcome.put("inputAudit",input);outcome.put("outputAudit",output);outcome.put("responseAttempts",List.copyOf(attempts));
            outcome.put("transportAttempts",List.copyOf(transportAttempts));
            if(!transportAttempts.isEmpty())outcome.put("firstFailureCode",transportAttempts.get(0).get("code"));
            LoggerFactory.getLogger(LayoutPayloadAudit.class).info("bidcheck.page_layout_transport traceId={} model={} imageHash={} transcriptionHash={} status={} errorCode={} validationReason={} durationMs={} inputAudit={} outputAudit={}",traceId,context.get("model"),context.get("imageHash"),context.get("transcriptionHash"),state,code,context.get("validationReason"),outcome.get("durationMs"),input,output);
            try{outcome.put("receiptAudit",write("receipt",JSON.writeValueAsBytes(outcome),Map.of("payloadKind","LAYOUT_CALL_RECEIPT")));}catch(Exception impossible){outcome.put("receiptAudit",Map.of("status","WRITE_FAILED"));}
            finished=Map.copyOf(outcome);return finished;
        }
        private Map<String,Object> write(String direction,byte[] bytes,Map<String,Object> extra){
            if(root==null)return Map.of("status","DISABLED");
            try{
                String text=new String(bytes,StandardCharsets.UTF_8);if(secret!=null&&!secret.isBlank())text=text.replace(secret,"[REDACTED]");
                text=text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [REDACTED]");
                Map<String,Object> payload=new LinkedHashMap<>(context);payload.putAll(extra);payload.put("direction",direction);payload.put("rawBytes",bytes.length);
                boolean omitted=bytes.length>LIMIT;payload.put("status",omitted?"PAYLOAD_OMITTED":"SAVED");
                if(omitted)payload.put("reason","AUDIT_PAYLOAD_LIMIT");else payload.put("bodyUtf8",text);
                byte[] encoded=JSON.writeValueAsBytes(payload);Files.createDirectories(root);Path file=root.resolve(traceId+"-"+direction+".json");Files.write(file,encoded,StandardOpenOption.CREATE_NEW);
                return Map.of("status",omitted?"PAYLOAD_OMITTED":"SAVED","file",file.getFileName().toString(),"directory","page-layout","bytes",encoded.length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
            }catch(Exception failure){LoggerFactory.getLogger(LayoutPayloadAudit.class).warn("bidcheck.page_layout_audit traceId={} direction={} status=WRITE_FAILED errorType={}",traceId,direction,failure.getClass().getSimpleName());return Map.of("status","WRITE_FAILED","errorType",failure.getClass().getSimpleName());}
        }
    }
}

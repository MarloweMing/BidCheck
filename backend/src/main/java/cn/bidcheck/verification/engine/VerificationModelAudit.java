package cn.bidcheck.verification.engine;
import cn.bidcheck.compat.HexFormat;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Optional local evidence of actual model transport bodies. Never captures HTTP headers. */
public final class VerificationModelAudit {
    static final String VERSION="java-model-audit-1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int DEFAULT_LIMIT=8*1024*1024;
    private static final Set<String> SECRET_FIELDS=cn.bidcheck.compat.Java8Api.setOf("apikey", "authorization", "password", "passwd", "secret", "clientsecret", "accesstoken", "refreshtoken", "cookie", "headers", "credentials");
    private static final Pattern BEARER=Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private VerificationModelAudit(){}
    public static Call begin(String directory,String secret,String operation){return new Call(directory,secret,operation,DEFAULT_LIMIT);}

    public static final class Call {
        final String traceId=UUID.randomUUID().toString();
        private final String directory,secret;private final int limit;private final long started=System.nanoTime();
        private final Map<String,Object> context=new LinkedHashMap<>();
        private Map<String,Object> requestReceipt=cn.bidcheck.compat.Java8Api.mapOf(),responseReceipt=cn.bidcheck.compat.Java8Api.mapOf();
        private boolean dispatched;private int httpStatus;private String state="FAILED",errorCode="UNCLASSIFIED_FAILURE",schemaIssue="",schemaPath="";
        Call(String directory,String secret,String operation,int limit){
            this.directory=cn.bidcheck.compat.Java8Api.strip(Objects.toString(directory,""));this.secret=Objects.toString(secret,"");this.limit=Math.max(1024,limit);
            String run=Objects.toString(MDC.get("verification.sourceRunId"),"");
            context.put("runId",run);context.put("sourceRunId",run);context.put("checkpointId",Objects.toString(MDC.get("verification.checkpointId"),""));
            context.put("requestId",Objects.toString(MDC.get("verification.requestId"),""));context.put("stepId",Objects.toString(MDC.get("verification.stepId"),""));
            context.put("traceId",traceId);context.put("stage",operation);context.put("correlationStatus",cn.bidcheck.compat.Java8Api.isBlank(run)?"RUN_CONTEXT_UNAVAILABLE":"RUN_CONTEXT_AVAILABLE");
        }
        boolean enabled(){return !cn.bidcheck.compat.Java8Api.isBlank(directory);}
        public void correlate(String runId,String checkpointId){context.put("runId",Objects.toString(runId,""));context.put("sourceRunId",Objects.toString(runId,""));context.put("checkpointId",Objects.toString(checkpointId,""));context.put("correlationStatus",cn.bidcheck.compat.Java8Api.isBlank(Objects.toString(runId,""))?"RUN_CONTEXT_UNAVAILABLE":"RUN_CONTEXT_AVAILABLE");}
        public void route(String model,String protocol,String route){context.put("model",model);context.put("protocol",protocol);context.put("route",route);}
        public void requestPrepared(String body){if(enabled()){byte[] raw=body.getBytes(StandardCharsets.UTF_8);requestReceipt=write("input",body,cn.bidcheck.compat.Java8Api.mapOf("payloadKind", "HTTP_REQUEST_JSON_BODY", "dispatchStateAtCapture", "PREPARED", "requestBytes", raw.length, "rawBodySha256", sha(raw)));}}
        public void dispatched(){dispatched=true;}
        public void received(byte[] body,int status){httpStatus=status;if(enabled())responseReceipt=body(body,true,body.length);}
        void partial(byte[] bytes,long observed,int status){if(httpStatus==0)httpStatus=status;if(enabled()&&responseReceipt.isEmpty()&&(observed>0||status>0))responseReceipt=body(bytes,false,observed);}
        private Map<String,Object> body(byte[] bytes,boolean complete,long observed){
            Map<String,Object> meta=new LinkedHashMap<>();meta.put("payloadKind","HTTP_RESPONSE_BODY");meta.put("httpStatus",httpStatus);meta.put("responseComplete",complete);meta.put("observedBytes",observed);meta.put("capturedBytes",bytes.length);meta.put("capturedRawSha256",sha(bytes));
            try{return write("output",StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(),meta);}
            catch(CharacterCodingException invalid){meta.put("payloadOmitted",true);meta.put("reason","INVALID_UTF8_RESPONSE");return write("output",null,meta);}
        }
        public void result(String state,String code,int status,String issue,String path){this.state=state;this.errorCode=code;if(status>0)httpStatus=status;schemaIssue=issue;schemaPath=path;}
        public Map<String,Object> finish(Map<String,Object> input,Map<String,Object> feedback){
            if(!enabled())return cn.bidcheck.compat.Java8Api.mapOf("status", "DISABLED");
            // Rejected local input was never sent. Preserve it explicitly without inventing an HTTP request.
            if(requestReceipt.isEmpty())requestReceipt=write("error",cn.bidcheck.compat.Java8Api.mapOf("applicationInput", input==null?cn.bidcheck.compat.Java8Api.mapOf():input, "schemaFeedback", feedback==null?cn.bidcheck.compat.Java8Api.mapOf():feedback),cn.bidcheck.compat.Java8Api.mapOf("payloadKind", "UNSENT_APPLICATION_INPUT", "errorCode", errorCode));
            Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("state",state);outcome.put("errorCode",errorCode);outcome.put("schemaIssue",schemaIssue);outcome.put("schemaPath",schemaPath);outcome.put("httpStatus",httpStatus);outcome.put("requestDispatched",dispatched);outcome.put("durationMs",(System.nanoTime()-started)/1_000_000);
            outcome.put("inputAudit",requestReceipt);outcome.put("outputAudit",responseReceipt.isEmpty()?cn.bidcheck.compat.Java8Api.mapOf("status", "NO_RESPONSE_CAPTURED"):responseReceipt);
            outcome.put("boundary","HTTP JSON body and received body only; credentials redacted. Dispatch is not proof of server receipt or billing. Partial responses are marked explicitly.");
            Map<String,Object> receipt=write("receipt",outcome,cn.bidcheck.compat.Java8Api.mapOf("state", state, "errorCode", errorCode, "httpStatus", httpStatus, "requestDispatched", dispatched));
            Map<String,Object> result=new LinkedHashMap<>(context);result.put("status",receipt.getOrDefault("status","WRITE_FAILED"));result.put("inputAudit",requestReceipt);result.put("outputAudit",responseReceipt.isEmpty()?cn.bidcheck.compat.Java8Api.mapOf("status", "NO_RESPONSE_CAPTURED"):responseReceipt);result.put("receiptAudit",receipt);return result;
        }
        private Map<String,Object> write(String direction,Object payload,Map<String,Object> metadata){
            if(!enabled())return cn.bidcheck.compat.Java8Api.mapOf("status", "DISABLED");
            try{
                Path folder=java.nio.file.Paths.get(directory).toAbsolutePath().normalize().resolve("java-model");Files.createDirectories(folder);
                JsonNode raw=JSON.valueToTree(payload),sanitized=sanitize(raw,secret,0);byte[] payloadBytes=JSON.writeValueAsBytes(sanitized);
                Map<String,Object> document=new LinkedHashMap<>(context);document.put("version",VERSION);document.put("direction",direction);document.put("savedAt",Instant.now().toString());document.putAll(metadata);
                document.put("credentialsRedacted",!raw.equals(sanitized));document.put("payloadBytes",payloadBytes.length);document.put("payloadSha256",sha(payloadBytes));document.put("payload",sanitized);
                byte[] encoded=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);boolean omitted=encoded.length>limit;
                if(omitted){document.remove("payload");document.put("payloadOmitted",true);document.put("reason","AUDIT_PAYLOAD_TOO_LARGE");document.put("fullRecordBytes",encoded.length);document.put("fullRecordSha256",sha(encoded));encoded=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);}
                String filename=traceId+"-"+direction+".json";Path target=folder.resolve(filename);
                java.nio.file.attribute.FileAttribute<?>[] attributes=Files.getFileStore(folder).supportsFileAttributeView("posix")?new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(cn.bidcheck.compat.Java8Api.setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))}:new java.nio.file.attribute.FileAttribute<?>[0];
                try(java.nio.channels.SeekableByteChannel channel=Files.newByteChannel(target,cn.bidcheck.compat.Java8Api.setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),attributes)){ByteBuffer buffer=ByteBuffer.wrap(encoded);while(buffer.hasRemaining())channel.write(buffer);}
                return cn.bidcheck.compat.Java8Api.mapOf("status", omitted||Boolean.TRUE.equals(metadata.get("payloadOmitted"))?"PAYLOAD_OMITTED":"SAVED", "file", "java-model/"+filename, "sha256", sha(encoded), "bytes", encoded.length);
            }catch(Exception failure){return cn.bidcheck.compat.Java8Api.mapOf("status", "WRITE_FAILED", "errorType", failure.getClass().getSimpleName());}
        }
    }
    private static JsonNode sanitize(JsonNode value,String secret,int depth){
        if(depth>64)return TextNode.valueOf("[AUDIT_DEPTH_LIMIT]");
        if(value.isObject()){ObjectNode result=JSON.createObjectNode();value.fields().forEachRemaining(field->{String name=field.getKey(),key=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","");result.set(redact(name,secret),SECRET_FIELDS.contains(key)?TextNode.valueOf("[REDACTED]"):sanitize(field.getValue(),secret,depth+1));});return result;}
        if(value.isArray()){ArrayNode result=JSON.createArrayNode();value.forEach(child->result.add(sanitize(child,secret,depth+1)));return result;}
        if(value.isTextual()){
            String text=value.textValue(),cleaned=redact(text,secret),trimmed=cn.bidcheck.compat.Java8Api.stripLeading(cleaned);
            // The business input is JSON embedded in a chat message string. Redact its credential keys too.
            if(trimmed.startsWith("{")||trimmed.startsWith("["))try{JsonNode nested=JSON.readTree(cleaned),safe=sanitize(nested,secret,depth+1);if(!nested.equals(safe))cleaned=JSON.writeValueAsString(safe);}catch(Exception ignored){}
            return TextNode.valueOf(cleaned);
        }
        return value.deepCopy();
    }
    private static String redact(String value,String secret){String result=cn.bidcheck.compat.Java8Api.isBlank(secret)?value:value.replace(secret,"[REDACTED]");return BEARER.matcher(result).replaceAll("Bearer [REDACTED]");}
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}

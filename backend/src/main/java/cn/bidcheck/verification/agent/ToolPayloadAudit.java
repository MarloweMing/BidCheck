package cn.bidcheck.verification.agent;
import cn.bidcheck.compat.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Optional local payloads, independent of subsequent model context compaction. */
final class ToolPayloadAudit {
    static final String VERSION="agent-tool-audit-1";
    private final String directory,credential;private final int maximumBytes;private final ObjectMapper json=new ObjectMapper();
    ToolPayloadAudit(String directory,String credential){this(directory,credential,8*1024*1024);}
    ToolPayloadAudit(String directory,String credential,int maximumBytes){this.directory=cn.bidcheck.compat.Java8Api.strip(Objects.toString(directory,""));this.credential=Objects.toString(credential,"");this.maximumBytes=Math.max(1024,Math.min(maximumBytes,32*1024*1024));}
    boolean enabled(){return !cn.bidcheck.compat.Java8Api.isBlank(directory);}
    Map<String,Object> record(Map<String,Object> start,String requestId,long index,String callId,String tool,String direction,Object payload){
        if(!enabled())return cn.bidcheck.compat.Java8Api.mapOf("status", "DISABLED");
        if(index<1||!cn.bidcheck.compat.Java8Api.setOf("input", "output", "error").contains(direction))return cn.bidcheck.compat.Java8Api.mapOf("status", "INVALID_METADATA");
        try{
            Path folder=java.nio.file.Paths.get(directory).toAbsolutePath().normalize();Files.createDirectories(folder);
            if(Files.isSymbolicLink(folder)||!folder.toRealPath().equals(folder))throw new java.io.IOException("AUDIT_DIRECTORY_ALIAS");
            String filename=sha(requestId.getBytes(StandardCharsets.UTF_8)).substring(0,32)+"-tool-"+String.format(Locale.ROOT,"%05d",index)+"-"+direction+".json";
            Map<String,Object> body=new LinkedHashMap<>();body.put("version",VERSION);body.put("requestId",requestId);body.put("toolIndex",index);body.put("callId",callId);body.put("tool",tool);body.put("direction",direction);body.put("savedAt",Instant.now().toString());
            Object rawContext=start==null?null:start.get("auditContext");Map<?,?> context=rawContext instanceof Map<?,?>?((Map<?,?>) (rawContext)):cn.bidcheck.compat.Java8Api.mapOf();
            for(String key:cn.bidcheck.compat.Java8Api.listOf("runId", "checkpointId")){String id=Objects.toString(context.get(key),"");body.put(key,id.matches("[a-fA-F0-9-]{36}")?id:"");}
            body.put("payload",sanitize(payload,"",0));body.put("boundary","Actual Java SDK tool boundary payload, credentials redacted; independent of model input/context compaction.");
            byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);boolean omitted=bytes.length>maximumBytes;
            if(omitted){body.remove("payload");body.put("payloadOmitted",true);body.put("payloadBytes",bytes.length);body.put("payloadSha256",sha(bytes));body.put("reason","AUDIT_PAYLOAD_TOO_LARGE");bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);}
            Path target=folder.resolve(filename);Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return cn.bidcheck.compat.Java8Api.mapOf("status", omitted?"PAYLOAD_OMITTED":"SAVED", "file", filename, "sha256", sha(bytes), "bytes", bytes.length);
        }catch(Exception failure){return cn.bidcheck.compat.Java8Api.mapOf("status", "WRITE_FAILED", "errorType", failure.getClass().getSimpleName());}
    }
    private Object sanitize(Object value,String key,int depth){
        if(cn.bidcheck.compat.Java8Api.setOf("apikey", "authorization", "password", "secret", "accesstoken", "refreshtoken", "cookie", "headers", "credential", "credentials").contains(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","")))return "[REDACTED]";
        if(depth>64)throw new IllegalArgumentException("AUDIT_PAYLOAD_TOO_DEEP");
        if(value instanceof Map<?,?>){Map<String,Object> out=new LinkedHashMap<>();((Map<?,?>) (value)).forEach((k,v)->out.put(Objects.toString(k),sanitize(v,Objects.toString(k),depth+1)));return out;}
        if(value instanceof Collection<?>){List<Object> out=new ArrayList<>();for(Object row:((Collection<?>) (value)))out.add(sanitize(row,"",depth+1));return out;}
        if(value instanceof String)return cn.bidcheck.compat.Java8Api.isBlank(credential)?((String) (value)):((String) (value)).replace(credential,"[REDACTED]");
        if(value==null||value instanceof Number||value instanceof Boolean)return value;
        return sanitize(json.convertValue(value,Object.class),"",depth+1);
    }
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

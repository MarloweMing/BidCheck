package cn.bidcheck.verification.agent;

import cn.bidcheck.compat.HexFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Optional local Java model wire payloads; never writes to normal logs or tracing. */
final class ModelPayloadAudit {
    private static final Set<String> SECRETS=new HashSet<>(Arrays.asList("apikey","authorization","password","secret","accesstoken","refreshtoken","cookie","headers","credential","credentials"));
    private final String directory,secret;
    private final int maximumBytes;
    private final ObjectMapper json=new ObjectMapper();
    ModelPayloadAudit(String directory,String secret){this(directory,secret,8*1024*1024);}
    ModelPayloadAudit(String directory,String secret,int maximumBytes){this.directory=Objects.toString(directory,"").trim();this.secret=Objects.toString(secret,"");this.maximumBytes=Math.max(1024,Math.min(maximumBytes,32*1024*1024));}
    Map<String,Object> record(String requestId,long modelIndex,String direction,Object payload){
        if(directory.isEmpty())return metadata("DISABLED");
        if(modelIndex<1||!Arrays.asList("input","output","error").contains(direction))return metadata("INVALID_METADATA");
        try{
            Path folder=Paths.get(directory).toAbsolutePath().normalize();Files.createDirectories(folder);
            if(Files.isSymbolicLink(folder)||!folder.toRealPath().equals(folder))throw new java.io.IOException("AUDIT_DIRECTORY_ALIAS");
            String filename=sha(Objects.toString(requestId,"").getBytes(StandardCharsets.UTF_8)).substring(0,32)+"-"+String.format(Locale.ROOT,"%05d",modelIndex)+"-"+direction+".json";
            Map<String,Object> body=new LinkedHashMap<>();body.put("version","agent-model-audit-1");body.put("requestId",requestId);body.put("modelIndex",modelIndex);body.put("direction",direction);body.put("savedAt",Instant.now().toString());
            body.put("payload",sanitize(payload,"",0));body.put("boundary","Java model request/response payload, credentials redacted; not HTTP headers or provider billing record.");
            byte[] encoded=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);boolean omitted=encoded.length>maximumBytes;
            if(omitted){body.remove("payload");body.put("payloadOmitted",true);body.put("payloadBytes",encoded.length);body.put("payloadSha256",sha(encoded));body.put("reason","AUDIT_PAYLOAD_TOO_LARGE");encoded=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);}
            Files.write(folder.resolve(filename),encoded,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Map<String,Object> meta=metadata(omitted?"PAYLOAD_OMITTED":"SAVED");meta.put("file",filename);meta.put("sha256",sha(encoded));meta.put("bytes",encoded.length);return meta;
        }catch(Exception failed){Map<String,Object> meta=metadata("WRITE_FAILED");meta.put("errorType",failed.getClass().getSimpleName());return meta;}
    }
    private Object sanitize(Object value,String field,int depth){
        if(SECRETS.contains(field.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","")))return "[REDACTED]";
        if(depth>64)throw new IllegalArgumentException("AUDIT_PAYLOAD_TOO_DEEP");
        if(value instanceof Map<?,?>){Map<String,Object> copy=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){String key=Objects.toString(entry.getKey());copy.put(key,sanitize(entry.getValue(),key,depth+1));}return copy;}
        if(value instanceof Collection<?>){List<Object> copy=new ArrayList<>();for(Object item:(Collection<?>)value)copy.add(sanitize(item,"",depth+1));return copy;}
        if(value instanceof String)return secret.isEmpty()?value:((String)value).replace(secret,"[REDACTED]");
        if(value instanceof Double&&!Double.isFinite((Double)value)||value instanceof Float&&!Float.isFinite((Float)value))throw new IllegalArgumentException("INVALID_JSON_NUMBER");
        if(value==null||value instanceof Number||value instanceof Boolean)return value;
        return Collections.singletonMap("unserializedType",value.getClass().getSimpleName());
    }
    private static Map<String,Object> metadata(String status){Map<String,Object> result=new LinkedHashMap<>();result.put("status",status);return result;}
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

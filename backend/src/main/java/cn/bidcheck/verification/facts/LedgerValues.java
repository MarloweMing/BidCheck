package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;

final class LedgerValues {
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static String text(Map<?,?> m,String key){return Objects.toString(m.get(key),"");}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** Database identifiers are bounded independently from full integrity digests. */
    static String dbKey(String prefix,String value){if(prefix.length()>20)throw new IllegalArgumentException("FACT_KEY_PREFIX_INVALID");return prefix+":"+hash(value).substring(0,40);}
    static String canonical(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("FACT_VALUE_INVALID");}}
    static Map<String,Object> map(Object value){if(value instanceof Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put(String.valueOf(k),v));return out;}return Map.of();}
    static List<Map<String,Object>> maps(Object value){return value instanceof List<?> l?l.stream().filter(Map.class::isInstance).map(LedgerValues::map).toList():List.of();}
    static List<String> strings(Object value){if(!(value instanceof List<?> l)||l.stream().anyMatch(v->!(v instanceof String)))return List.of();return l.stream().map(String.class::cast).toList();}
    static Map<String,Object> of(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static Map<String,Object> copy(Map<String,Object> value){return map(JSON.convertValue(value,Map.class));}
    static Map<String,Object> evidence(Object raw){if(raw instanceof Evidence e)return of("documentId",e.documentId(),"documentName",e.documentName(),"page",e.page(),"locationType",e.locationType(),"quote",e.quote(),"anchor",e.anchor(),"confidence",e.confidence());return map(raw);}
    static int integer(Object value,int fallback){try{if(value instanceof Number n&&n.doubleValue()!=n.intValue())return fallback;return Integer.parseInt(Objects.toString(value,""));}catch(Exception e){return fallback;}}
    static String clean(String value){return value==null?"":value.replaceAll("\\s+","");}
    static void interrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("事实账本已中断");}
}

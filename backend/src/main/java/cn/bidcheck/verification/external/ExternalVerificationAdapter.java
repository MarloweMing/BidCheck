package cn.bidcheck.verification.external;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Fixed administrator-configured endpoints only. No request URL, redirects or implicit business verdicts. */
@Service
public class ExternalVerificationAdapter {
    private static final String VERSION="external-authority-contract-2";
    private static final int MAX_BODY=128*1024;
    private static final Semaphore CAPACITY=new Semaphore(2,true);
    private final SortedMap<String,Connector> connectors=new TreeMap<>();
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_BODY).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client;private final Duration timeout;private final boolean configInvalid;
    private record Connector(String type,URI url,String issuer,String source,String key){}
    public ExternalVerificationAdapter(){this("");}
    @Autowired public ExternalVerificationAdapter(@Value("${bidcheck.external.connectors:${BIDCHECK_EXTERNAL_CONNECTORS:}}") String configuration){this(configuration,Duration.ofSeconds(30),false);}
    /** Loopback HTTP is available only through this package-private contract-test constructor. */
    ExternalVerificationAdapter(String configuration,Duration timeout,boolean loopbackTest){
        this.timeout=timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0?Duration.ofSeconds(30):timeout;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();boolean invalid=false;
        if(configuration!=null&&!configuration.isBlank())try{
            if(configuration.length()>32_000)throw new IOException();JsonNode entries=json.readTree(configuration);if(!entries.isArray()||entries.size()>20)throw new IOException();
            for(JsonNode item:entries){String type=text(item,"type",64),url=text(item,"url",2048),issuer=text(item,"issuer",200),source=text(item,"source",500);String key=item.has("key")?text(item,"key",4096):"";
                if(!type.matches("[A-Z][A-Z0-9_]{0,63}")||issuer.isBlank()||source.isBlank()||key.contains("\r")||key.contains("\n")||connectors.containsKey(type))throw new IOException();
                URI endpoint=URI.create(url);boolean secure="https".equalsIgnoreCase(endpoint.getScheme()),local=loopbackTest&&"http".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost());
                if((!secure&&!local)||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null)throw new IOException();
                connectors.put(type,new Connector(type,endpoint,issuer,source,key));
            }
        }catch(Exception e){connectors.clear();invalid=true;}
        configInvalid=invalid;
    }
    public List<String> enabledTypes(){return List.copyOf(connectors.keySet());}
    /** Exact administrator-owned source names that have one unambiguous enabled adapter type. */
    public Map<String,String> enabledSourceTypes(){
        Map<String,List<String>> grouped=new LinkedHashMap<>();
        for(Connector connector:connectors.values())grouped.computeIfAbsent(connector.source(),ignored->new ArrayList<>()).add(connector.type());
        Map<String,String> result=new LinkedHashMap<>();
        grouped.forEach((source,types)->{if(types.size()==1)result.put(source,types.get(0));});
        return Map.copyOf(result);
    }
    public String identity(){if(connectors.isEmpty())return configInvalid?"CONFIGURATION_INVALID":"DISABLED";StringBuilder b=new StringBuilder(VERSION);for(Connector c:connectors.values())b.append('\n').append(c.type).append('\n').append(c.url).append('\n').append(c.issuer).append('\n').append(c.source);return hash(b.toString().getBytes(StandardCharsets.UTF_8));}
    public Map<String,Object> query(Map<String,Object> request){
        interrupted();boolean acquired=false;CompletableFuture<HttpResponse<byte[]>> pending=null;LimitedBody body=new LimitedBody();
        try{
            if(request==null||!Set.of("type","subject","evaluationStage","referenceDate").containsAll(request.keySet()))return failure("ERROR","EXTERNAL_REQUEST_INVALID");
            String type=requestText(request,"type",64),subject=requestText(request,"subject",200),stage=requestText(request,"evaluationStage",64),date=requestText(request,"referenceDate",10);
            if(!type.matches("[A-Z][A-Z0-9_]{0,63}")||!stage.matches("[A-Z][A-Z0-9_]{0,63}")||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return failure("ERROR","EXTERNAL_REQUEST_INVALID");LocalDate.parse(date);
            Connector connector=connectors.get(type);if(connector==null)return failure("UNAVAILABLE",configInvalid?"EXTERNAL_CONFIGURATION_INVALID":"EXTERNAL_CONNECTOR_NOT_CONFIGURED");
            if(!CAPACITY.tryAcquire(Math.min(10_000,timeout.toMillis()),TimeUnit.MILLISECONDS))return failure("ERROR","EXTERNAL_CAPACITY_TIMEOUT");acquired=true;interrupted();
            Map<String,Object> payload=map("type",type,"subject",subject,"evaluationStage",stage,"referenceDate",date);
            HttpRequest.Builder builder=HttpRequest.newBuilder(connector.url).timeout(timeout).header("Content-Type","application/json");if(!connector.key.isBlank())builder.header("Authorization","Bearer "+connector.key);
            pending=client.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build(),ignored->body);
            HttpResponse<byte[]> response=pending.get(timeout.toNanos(),TimeUnit.NANOSECONDS);interrupted();
            if(response.statusCode()!=200)return failure("ERROR","EXTERNAL_HTTP_"+response.statusCode());
            JsonNode snapshot=json.readTree(response.body());Map<String,Object> result=validate(snapshot,type,subject,stage,date);
            Map<String,Object> record=map("id",UUID.randomUUID().toString(),"type",type,"subject",subject,"source",connector.source,"issuer",connector.issuer,"queriedAt",Instant.now().toString(),"effectiveAt",date,"evaluationStage",stage,
                "coverage",snapshot.get("coverage").textValue(),"coverageComplete",snapshot.get("coverageComplete").booleanValue(),"rawSnapshotHash",hash(response.body()),"result",result,"provenanceStatus","TRUSTED_CONNECTOR","rawSnapshot",new String(response.body(),StandardCharsets.UTF_8));
            return map("status","COMPLETED","record",record,"identity",identity(),"connectionStatus","CONFIGURED_ENDPOINT_RESPONDED","limitations",List.of("TRUSTED_CONNECTOR表示来自服务端配置的固定连接器并通过主体、阶段、时点和结构校验，不代表全部外部事实或业务义务均已满足。","NOT_FOUND仅表示所声明来源与覆盖范围未命中，不自动等于投标符合；历史时点不得被当前查询替代。"));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("外部核验已中断");}
        catch(CancellationException e){throw e;}
        catch(TimeoutException e){return failure("ERROR","EXTERNAL_TIMEOUT");}
        catch(ExecutionException e){return failure("ERROR",e.getCause() instanceof HttpTimeoutException?"EXTERNAL_TIMEOUT":"EXTERNAL_TRANSPORT_OR_SIZE_FAILURE");}
        catch(Exception e){String message=e instanceof IOException?e.getMessage():null;return failure("ERROR",message!=null&&message.matches("EXTERNAL_[A-Z0-9_]{1,70}")?message:"EXTERNAL_RESPONSE_OR_REQUEST_INVALID");}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);body.cancel();if(acquired)CAPACITY.release();}
    }
    private Map<String,Object> validate(JsonNode s,String type,String subject,String stage,String date)throws IOException{
        keys(s,Set.of("type","subject","evaluationStage","effectiveAt","coverage","coverageComplete","result"));
        if(!s.isObject()||!type.equals(text(s,"type",64))||!subject.equals(text(s,"subject",200)))throw new IOException("EXTERNAL_SUBJECT_OR_TYPE_MISMATCH");
        if(!stage.equals(text(s,"evaluationStage",64)))throw new IOException("EXTERNAL_STAGE_MISMATCH");
        if(!date.equals(text(s,"effectiveAt",10)))throw new IOException("EXTERNAL_EFFECTIVE_DATE_MISMATCH");
        if(text(s,"coverage",1000).isBlank()||!s.path("coverageComplete").isBoolean())throw new IOException("EXTERNAL_COVERAGE_INVALID");
        JsonNode r=s.path("result");keys(r,Set.of("status","facts","summary"));String status=text(r,"status",30),summary=text(r,"summary",2000);if(!Set.of("VERIFIED","NOT_FOUND","INCONCLUSIVE").contains(status))throw new IOException("EXTERNAL_RESULT_INVALID");
        JsonNode f=r.path("facts");if(!f.isArray()||f.size()>100)throw new IOException("EXTERNAL_FACTS_INVALID");List<Map<String,Object>> facts=new ArrayList<>();Set<String> fields=new HashSet<>();
        for(JsonNode fact:f){keys(fact,Set.of("field","value","rawValue","sourceQuote"));String field=text(fact,"field",100),raw=text(fact,"rawValue",2000);JsonNode value=fact.get("value");
            if(field.isBlank()||!fields.add(field)||raw.isBlank()||value==null||!(value.isTextual()||value.isNumber()||value.isBoolean()))throw new IOException("EXTERNAL_FACTS_INVALID");
            String canonical=value.asText();boolean same=raw.strip().equals(canonical.strip());
            if(value.isNumber())try{same=new BigDecimal(raw.strip()).compareTo(value.decimalValue())==0;}catch(NumberFormatException e){same=false;}
            if(!same)throw new IOException("EXTERNAL_FACT_VALUE_UNGROUNDED");
            String quote=fact.has("sourceQuote")?text(fact,"sourceQuote",4000):"";
            if(quote.isBlank())throw new IOException("EXTERNAL_FACT_SOURCE_REQUIRED");
            if(!quoteContainsValue(quote,raw,value))throw new IOException("EXTERNAL_FACT_VALUE_UNGROUNDED");
            facts.add(map("field",field,"value",json.convertValue(value,Object.class),"rawValue",raw,"sourceQuote",quote));
        }
        if(status.equals("VERIFIED")&&facts.isEmpty())throw new IOException("EXTERNAL_VERIFIED_WITHOUT_FACTS");
        if(status.equals("NOT_FOUND")&&(!s.get("coverageComplete").booleanValue()||!facts.isEmpty()))throw new IOException("EXTERNAL_NEGATIVE_COVERAGE_INCOMPLETE");
        return map("status",status,"facts",facts,"summary",summary);
    }
    private Map<String,Object> failure(String status,String code){return map("status",status,"errorCode",code,"record",null,"identity",identity(),"connectionStatus",connectors.isEmpty()?"NOT_CONFIGURED":"NOT_VERIFIED_BY_THIS_CALL","limitations",List.of("外部核验未完成，不得把服务失败、缺配置或时点不匹配解释为未命中或业务符合。"));}
    private static String text(JsonNode n,String k,int max)throws IOException{JsonNode v=n.get(k);if(v==null||!v.isTextual()||v.textValue().length()>max)throw new IOException("EXTERNAL_SCHEMA_INVALID");return v.textValue();}
    private static void keys(JsonNode node,Set<String> allowed)throws IOException{if(!node.isObject())throw new IOException("EXTERNAL_SCHEMA_INVALID");for(Iterator<String> it=node.fieldNames();it.hasNext();)if(!allowed.contains(it.next()))throw new IOException("EXTERNAL_SCHEMA_INVALID");}
    private static boolean quoteContainsValue(String quote,String raw,JsonNode value){
        if(!quote.contains(raw))return false;
        // ACTIVE is not grounded by INACTIVE, nor is numeric 1 grounded by 10.
        String token=raw.strip(),boundary=value.isNumber()?"[A-Za-z0-9_.+-]":"[A-Za-z0-9_-]";
        if(value.isNumber()||token.matches("[A-Za-z0-9_]+"))return java.util.regex.Pattern.compile("(?<!"+boundary+")"+java.util.regex.Pattern.quote(token)+"(?!"+boundary+")").matcher(quote).find();
        return true;
    }
    private static String requestText(Map<String,Object> r,String k,int max)throws IOException{Object v=r.get(k);if(!(v instanceof String s)||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32))throw new IOException("EXTERNAL_REQUEST_INVALID");return s;}
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("外部核验已中断");}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]>{
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}public synchronized void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public synchronized void onNext(List<ByteBuffer> buffers){try{for(ByteBuffer buffer:buffers){if(bytes.size()+buffer.remaining()>MAX_BODY)throw new IOException("EXTERNAL_RESPONSE_LIMIT");byte[] b=new byte[buffer.remaining()];buffer.get(b);bytes.write(b);}subscription.request(1);}catch(Exception e){result.completeExceptionally(e);cancel();}}
        public synchronized void onError(Throwable e){result.completeExceptionally(e);}public synchronized void onComplete(){result.complete(bytes.toByteArray());}synchronized void cancel(){if(subscription!=null)subscription.cancel();}
    }
}

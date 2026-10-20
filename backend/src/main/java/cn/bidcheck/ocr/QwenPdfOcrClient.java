package cn.bidcheck.ocr;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.*;

/** Private, same-region PDF transcription. No provider resources/credentials are persisted. */
@Component
public final class QwenPdfOcrClient implements PdfOcrClient {
    public static final String POLICY = "qwen-pdf-private-layouts-figure-text-2";
    private static final String PROVIDER = "DASHSCOPE_PDF";
    private static final int MAX_PDF_BYTES=100_000_000, MAX_RESPONSE_BYTES=16*1024*1024, MAX_PAGE_TEXT=200_000;
    private static final int MAX_QUEUE=32;
    private static final Semaphore GLOBAL = new Semaphore(5,true), WAITERS = new Semaphore(MAX_QUEUE,true);
    private static final Map<String,CompletableFuture<Parsed>> FLIGHTS = new HashMap<>();
    private static final Map<String,LongAdder> TOTALS = new ConcurrentHashMap<>();
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\[\\]]+",Pattern.CASE_INSENSITIVE);
    private static final String RESOURCE_MARKER="[SIGNED_RESOURCE_URL_REDACTED]";
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48).maxStringLength(MAX_RESPONSE_BYTES).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint,policyEndpoint;
    private final String key,model,identity;
    private final boolean active,testLoopback;
    private final Path cacheDirectory;
    private final Duration timeout;
    private final int batchPages,concurrency;
    private final Semaphore local;
    private final HttpClient http;

    @Autowired
    public QwenPdfOcrClient(@Value("${bidcheck.ocr.pdf.enabled:false}") boolean enabled,
            @Value("${bidcheck.ocr.pdf.url:https://dashscope.aliyuncs.com/compatible-mode/v1/responses}") String url,
            @Value("${bidcheck.ocr.pdf.policy-url:https://dashscope.aliyuncs.com/api/v1/uploads}") String policyUrl,
            @Value("${bidcheck.ocr.pdf.key:${bidcheck.ocr.key:${bidcheck.semantic.key:}}}") String key,
            @Value("${bidcheck.ocr.pdf.model:qwen3.5-ocr}") String model,
            @Value("${bidcheck.data:./data}") String data,
            @Value("${bidcheck.ocr.pdf.timeout-seconds:300}") int timeoutSeconds,
            @Value("${bidcheck.ocr.pdf.batch-pages:10}") int batchPages,
            @Value("${bidcheck.ocr.pdf.concurrency:5}") int concurrency) {
        this(enabled,url,policyUrl,key,model,Path.of(data).resolve("pdf-ocr-cache"),Duration.ofSeconds(timeoutSeconds),batchPages,concurrency,false);
    }

    /** Package-private loopback seam: ordinary production construction requires Beijing HTTPS. */
    QwenPdfOcrClient(boolean enabled,String url,String policyUrl,String key,String model,Path cacheDirectory,
                     Duration timeout,int batchPages,int concurrency,boolean testLoopback) {
        if(batchPages<1||batchPages>10||concurrency<1||concurrency>5||timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(600))>0)
            throw new IllegalArgumentException("PDF OCR 批大小、并发或超时配置无效");
        this.key=key==null?"":key.strip();this.model=model==null?"":model.strip();this.timeout=timeout;
        this.batchPages=batchPages;this.concurrency=concurrency;this.testLoopback=testLoopback;this.local=new Semaphore(concurrency,true);
        this.cacheDirectory=cacheDirectory.toAbsolutePath().normalize();
        this.endpoint=validateEndpoint(url,"/compatible-mode/v1/responses",testLoopback);
        this.policyEndpoint=validateEndpoint(policyUrl,"/api/v1/uploads",testLoopback);
        this.active=enabled&&endpoint!=null&&policyEndpoint!=null&&!this.key.isBlank()&&this.key.length()<=4096
            &&!this.key.contains("\r")&&!this.key.contains("\n")&&this.model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}");
        this.identity=active?PROVIDER+":"+hash((POLICY+"\n"+endpoint+"\n"+policyEndpoint+"\n"+this.model).getBytes(StandardCharsets.UTF_8)):"DISABLED";
        this.http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout.compareTo(Duration.ofSeconds(10))<0?timeout:Duration.ofSeconds(10)).build();
    }
    @Override public boolean enabled(){return active;}
    @Override public String identity(){return identity;}
    @Override public String modelName(){return model;}
    @Override public int batchPages(){return batchPages;}
    @Override public int concurrency(){return concurrency;}

    /** Process-cumulative metadata only; contains no document text, URLs or credentials. */
    public static Map<String,Long> summary(){
        Map<String,Long> result=new TreeMap<>();
        for(String name:List.of("httpRequests","policyRequests","uploadRequests","ocrRequests","retries","http2xx","http3xx","http4xx","http5xx","timeouts","cancellations","transportErrors","policyMillis","uploadMillis","ocrMillis"))
            result.put(name,TOTALS.getOrDefault(name,new LongAdder()).sum());
        result.put("activeBatches",5L-GLOBAL.availablePermits());result.put("waitingRequests",(long)MAX_QUEUE-WAITERS.availablePermits());
        return Map.copyOf(result);
    }
    private static void total(String name,long value){TOTALS.computeIfAbsent(name,ignored->new LongAdder()).add(value);}

    private static URI validateEndpoint(String value,String path,boolean test) {
        try {
            URI uri=URI.create(value);
            boolean local=test&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost());
            boolean beijing="https".equals(uri.getScheme())&&("dashscope.aliyuncs.com".equals(uri.getHost())
                ||uri.getHost()!=null&&uri.getHost().endsWith(".cn-beijing.maas.aliyuncs.com"))&&(uri.getPort()==-1||uri.getPort()==443);
            return (local||beijing)&&path.equals(uri.getPath())&&uri.getUserInfo()==null&&uri.getRawQuery()==null&&uri.getFragment()==null?uri:null;
        }catch(Exception invalid){return null;}
    }

    private URI uploadEndpoint(String value)throws IOException {
        try {
            URI uri=URI.create(value);
            boolean local=testLoopback&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost())&&Objects.equals(uri.getAuthority(),endpoint.getAuthority());
            boolean secure="https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getHost().matches("dashscope-file-[a-z0-9-]+\\.oss-cn-beijing\\.aliyuncs\\.com")
                &&(uri.getPort()==-1||uri.getPort()==443)&&(uri.getPath().isEmpty()||"/".equals(uri.getPath()));
            if((local||secure)&&uri.getUserInfo()==null&&uri.getRawQuery()==null&&uri.getFragment()==null)return uri;
        }catch(Exception ignored){}
        throw failure("UPLOAD_HOST_REJECTED",false);
    }

    @Override public BatchResult recognizePdf(byte[] pdf,List<Integer> sourcePages)throws IOException {
        interrupted();if(!active)throw failure("DISABLED",false);
        long started=System.nanoTime(),deadline=started+timeout.toNanos();
        List<Integer> sources=validatePdf(pdf,sourcePages);
        String inputHash=hash(pdf),cacheKey=hash((identity+"\n"+inputHash).getBytes(StandardCharsets.UTF_8));
        Path cache=cacheDirectory.resolve(cacheKey+".json");
        Parsed cached=readCache(cache,inputHash,sources.size());
        if(cached!=null){interrupted();return bind(cached,sources,true,new Stats(),started);}
        String flightKey=cacheKey+":"+hash(key.getBytes(StandardCharsets.UTF_8));
        CompletableFuture<Parsed> flight;boolean leader;
        synchronized(FLIGHTS){
            flight=FLIGHTS.get(flightKey);leader=flight==null;
            if(leader){if(FLIGHTS.size()>=MAX_QUEUE+5)throw failure("QUEUE_FULL",true);flight=new CompletableFuture<>();FLIGHTS.put(flightKey,flight);}
        }
        if(!leader){
            if(!WAITERS.tryAcquire())throw failure("QUEUE_FULL",true);
            try {return bind(flight.get(remaining(deadline),TimeUnit.NANOSECONDS),sources,true,new Stats(),started);}
            catch(InterruptedException stopped){throw interruptedFailure();}
            catch(TimeoutException expired){throw failure("TIMEOUT",true);}
            catch(ExecutionException failed){if(failed.getCause() instanceof InterruptedIOException)throw failure("SHARED_CANCELLED",true);if(failed.getCause() instanceof IOException safe)throw safe;throw failure("INVALID_RESPONSE",false);}
            finally{WAITERS.release();}
        }
        boolean heldGlobal=false,heldLocal=false;
        Stats stats=new Stats();
        try {
            acquire(local,deadline);heldLocal=true;acquire(GLOBAL,deadline);heldGlobal=true;
            cached=readCache(cache,inputHash,sources.size());
            if(cached!=null){flight.complete(cached);return bind(cached,sources,true,stats,started);}
            Parsed result=execute(pdf,sources.size(),deadline,stats);
            interrupted();if(result.errors().isEmpty())writeCache(cache,inputHash,result);
            interrupted();flight.complete(result);return bind(result,sources,false,stats,started);
        }catch(IOException|RuntimeException|Error failed){
            IOException safe=failed instanceof IOException io?io:failure("INVALID_RESPONSE",false);
            flight.completeExceptionally(safe);
            if(failed instanceof Error fatal)throw fatal;
            throw safe;
        }finally{
            if(heldGlobal)GLOBAL.release();if(heldLocal)local.release();
            synchronized(FLIGHTS){FLIGHTS.remove(flightKey,flight);}
        }
    }

    private List<Integer> validatePdf(byte[] pdf,List<Integer> sources)throws IOException {
        if(pdf==null||pdf.length<5||pdf.length>MAX_PDF_BYTES||!new String(pdf,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw failure("INVALID_PDF",false);
        if(sources==null||sources.isEmpty()||sources.size()>batchPages||sources.stream().anyMatch(p->p==null||p<1)||new HashSet<>(sources).size()!=sources.size())throw failure("INVALID_PAGE_MANIFEST",false);
        try(var document=Loader.loadPDF(pdf)){
            if(document.isEncrypted()||document.getNumberOfPages()!=sources.size())throw failure("PDF_PAGE_COUNT_MISMATCH",false);
        }catch(OcrFailure safe){throw safe;}catch(Exception invalid){throw failure("INVALID_PDF",false);}
        interrupted();return List.copyOf(sources);
    }

    private static void acquire(Semaphore semaphore,long deadline)throws IOException {
        interrupted();if(semaphore.tryAcquire())return;
        if(!WAITERS.tryAcquire())throw failure("QUEUE_FULL",true);
        try {if(!semaphore.tryAcquire(remaining(deadline),TimeUnit.NANOSECONDS))throw failure("QUEUE_TIMEOUT",true);}
        catch(InterruptedException stopped){throw interruptedFailure();}
        finally{WAITERS.release();}
    }

    private Parsed execute(byte[] pdf,int pages,long deadline,Stats stats)throws IOException {
        URI policyRequest=URI.create(policyEndpoint+"?action=getPolicy&model="+URLEncoder.encode(model,StandardCharsets.UTF_8));
        JsonNode policy;
        try {
            HttpResponse<byte[]> policyResponse=send("GET",policyRequest,null,Map.of("Authorization","Bearer "+key,"Content-Type","application/json"),1_048_576,deadline,true,"policy",stats);
            policy=tree(policyResponse.body()).path("data");
            if(!policy.isObject()||!"private".equals(policy.path("x_oss_object_acl").asText())||!"true".equals(policy.path("x_oss_forbid_overwrite").asText()))throw failure("PRIVATE_UPLOAD_POLICY_REQUIRED",false);
            long maxMb=policy.path("max_file_size_mb").asLong(-1);
            if(maxMb<1||maxMb>1024||pdf.length>maxMb*1024*1024L)throw failure("UPLOAD_POLICY_SIZE_LIMIT",false);
            URI upload=uploadEndpoint(requiredText(policy,"upload_host"));
            String folder=requiredText(policy,"upload_dir").replaceFirst("/+$","");
            if(!folder.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))throw failure("INVALID_UPLOAD_POLICY",false);
            String filename="pdf-ocr-"+UUID.randomUUID()+".pdf",objectKey=folder+"/"+filename;
            String boundary="pdf-ocr-"+UUID.randomUUID();
            byte[] multipart=multipart(policy,pdf,objectKey,filename,boundary);
            // An upload with an uncertain response must not be repeated or overwrite a private object.
            send("POST",upload,multipart,Map.of("Content-Type","multipart/form-data; boundary="+boundary),1_048_576,deadline,false,"upload",stats);
            Map<String,Object> body=Map.of("model",model,"input",List.of(Map.of("role","user","content",List.of(Map.of("type","input_file","file_url","oss://"+objectKey)))),
                "ocr_options",Map.of("task","document_parsing"),"stream",false);
            HttpResponse<byte[]> response=send("POST",endpoint,json.writeValueAsBytes(body),Map.of("Authorization","Bearer "+key,"Content-Type","application/json","X-DashScope-OssResourceResolve","enable"),MAX_RESPONSE_BYTES,deadline,true,"ocr",stats);
            if(response.headers().firstValue("x-dashscope-partialresponse").map("true"::equalsIgnoreCase).orElse(false))throw failure("INCOMPLETE_RESPONSE",false);
            JsonNode envelope=tree(response.body());stats.usage=numericObject(envelope.path("usage"));
            return parse(envelope,pages);
        }catch(InterruptedIOException stopped){throw stopped;}
        catch(OcrFailure safe){throw safe;}
        catch(Exception invalid){throw failure("INVALID_RESPONSE",false);}
    }

    private byte[] multipart(JsonNode policy,byte[] pdf,String objectKey,String filename,String boundary)throws IOException {
        Map<String,String> fields=new LinkedHashMap<>();fields.put("OSSAccessKeyId",requiredText(policy,"oss_access_key_id"));
        fields.put("Signature",requiredText(policy,"signature"));fields.put("policy",requiredText(policy,"policy"));
        fields.put("x-oss-object-acl","private");fields.put("x-oss-forbid-overwrite","true");fields.put("key",objectKey);fields.put("success_action_status","200");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(pdf.length+8192);
        for(var entry:fields.entrySet()){
            if(entry.getValue().indexOf('\r')>=0||entry.getValue().indexOf('\n')>=0)throw failure("INVALID_UPLOAD_POLICY",false);
            bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n"+entry.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
        }
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(pdf);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return bytes.toByteArray();
    }

    private HttpResponse<byte[]> send(String method,URI uri,byte[] payload,Map<String,String> headers,int limit,long deadline,
                                     boolean retryable,String stage,Stats stats)throws IOException {
        OcrFailure last=null;
        for(int attempt=0;attempt<(retryable?2:1);attempt++){
            interrupted();long started=System.nanoTime();
            // Preserve time for one bounded retry instead of letting the first timeout consume
            // the entire batch deadline. Uploads remain non-retryable because acceptance is uncertain.
            long left=remaining(deadline);
            Duration budget=Duration.ofNanos(retryable&&attempt==0?Math.max(1,left/2):left);
            var subscriber=new DashScopeOcrClient.LimitedBodySubscriber(limit);
            CompletableFuture<HttpResponse<byte[]>> pending=null;long retryDelay=250;
            try {
                HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(budget);headers.forEach(builder::header);
                builder.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(payload));
                HttpRequest request=builder.build();stats.request(stage);if(attempt>0){stats.retries++;total("retries",1);}
                pending=http.sendAsync(request,ignored->subscriber);
                HttpResponse<byte[]> response=pending.get(remaining(deadline),TimeUnit.NANOSECONDS);
                stats.lastHttp=response.statusCode();total("http"+(response.statusCode()/100)+"xx",1);
                if(response.statusCode()==200)return response;
                last=OcrFailure.http(response.statusCode());retryDelay=retryAfter(response);
            }catch(InterruptedException stopped){total("cancellations",1);throw interruptedFailure();}
            catch(TimeoutException expired){total("timeouts",1);last=failure("TIMEOUT",true);}
            catch(ExecutionException failed){
                if(subscriber.limitExceeded())throw failure("RESPONSE_LIMIT",false);
                Throwable cause=failed.getCause();total(cause instanceof HttpTimeoutException?"timeouts":"transportErrors",1);
                last=failure(cause instanceof HttpTimeoutException?"TIMEOUT":"TRANSPORT_ERROR",transientTransport(cause));
            }catch(IllegalArgumentException invalid){throw failure("INVALID_REQUEST",false);}
            finally{if(pending!=null&&!pending.isDone())pending.cancel(true);subscriber.cancel();stats.time(stage,System.nanoTime()-started);}
            if(last==null||!last.temporary()||!retryable||attempt==1)throw last==null?failure("TRANSPORT_ERROR",false):last;
            if(TimeUnit.MILLISECONDS.toNanos(retryDelay)>=remaining(deadline))throw last;
            try{TimeUnit.MILLISECONDS.sleep(retryDelay);}catch(InterruptedException stopped){throw interruptedFailure();}
        }
        throw last;
    }
    static boolean transientTransport(Throwable cause){
        // Certificate/protocol/configuration failures need correction, not repeated paid calls.
        if(cause instanceof javax.net.ssl.SSLException||cause instanceof ProtocolException)return false;
        return cause instanceof IOException||cause instanceof TimeoutException;
    }
    private static long retryAfter(HttpResponse<?> response){
        String value=response.headers().firstValue("Retry-After").orElse("");
        try{long seconds=Long.parseLong(value);return seconds<0?250:seconds>Long.MAX_VALUE/1000?Long.MAX_VALUE:Math.max(250,seconds*1000);}
        catch(Exception ignored){}
        try{return Math.max(250,Duration.between(Instant.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());}
        catch(Exception ignored){return 250;}
    }

    private record Block(int index,int encounter,String markdown,String text,String type){}
    private record Parsed(Map<Integer,String> texts,Map<Integer,String> errors){}
    private Parsed parse(JsonNode envelope,int pageCount)throws IOException {
        if(!envelope.isObject()||!"completed".equals(envelope.path("status").asText())||envelope.hasNonNull("error")||envelope.hasNonNull("incomplete_details"))throw failure("INCOMPLETE_RESPONSE",false);
        JsonNode output=envelope.path("output");if(!output.isArray()||output.isEmpty())throw failure("UNKNOWN_RESPONSE_SCHEMA",false);
        Map<Integer,List<Block>> grouped=new HashMap<>();boolean found=false;int encounter=0;
        for(JsonNode item:output){
            if(!"message".equals(item.path("type").asText())||!"completed".equals(item.path("status").asText("completed")))throw failure("UNKNOWN_RESPONSE_SCHEMA",false);
            JsonNode content=item.path("content");if(!content.isArray()||content.isEmpty())throw failure("UNKNOWN_RESPONSE_SCHEMA",false);
            for(JsonNode part:content){
                JsonNode layouts=part.path("ocr_result").path("layouts");if(!layouts.isArray())throw failure("UNKNOWN_RESPONSE_SCHEMA",false);found=true;
                for(JsonNode block:layouts){
                    JsonNode page=block.path("pageNum");
                    if(!page.isIntegralNumber()||!page.canConvertToInt()||page.intValue()<0||page.intValue()>=pageCount)throw failure("INVALID_PROVIDER_PAGE",false);
                    if(!block.path("markdownContent").isTextual()||(block.has("text")&&!block.path("text").isTextual()))throw failure("UNKNOWN_LAYOUT_SCHEMA",false);
                    String md=sanitize(block.path("markdownContent").textValue()).replace(key,"[REDACTED_CREDENTIAL]"),text=sanitize(block.path("text").asText("")).replace(key,"[REDACTED_CREDENTIAL]");
                    if(md.length()>MAX_PAGE_TEXT||text.length()>MAX_PAGE_TEXT)throw failure("PAGE_TEXT_LIMIT",false);
                    int index=block.path("index").isIntegralNumber()&&block.path("index").canConvertToInt()?block.path("index").intValue():-1;
                    grouped.computeIfAbsent(page.intValue(),ignored->new ArrayList<>()).add(new Block(index,encounter++,md,text,block.path("type").asText("")));
                }
            }
        }
        if(!found)throw failure("UNKNOWN_RESPONSE_SCHEMA",false);
        Map<Integer,String> texts=new TreeMap<>(),errors=new TreeMap<>();
        for(int page=0;page<pageCount;page++){
            List<Block> blocks=grouped.get(page);if(blocks==null){errors.put(page,"PDF_OCR_MISSING_PAGE");continue;}
            if(blocks.stream().allMatch(b->b.index()>=0)&&blocks.stream().map(Block::index).distinct().count()==blocks.size())blocks.sort(Comparator.comparingInt(Block::index));
            StringBuilder text=new StringBuilder();
            for(Block block:blocks){String chosen=blockText(block);
                if(!chosen.isBlank()){if(!text.isEmpty())text.append("\n\n");text.append(chosen);}
                if(text.length()>MAX_PAGE_TEXT)throw failure("PAGE_TEXT_LIMIT",false);
            }
            String value=text.toString().strip();
            if(value.isBlank()||usefulMarkdown(value).isBlank()){errors.put(page,"PDF_OCR_EMPTY_PAGE");continue;}
            if(value.indexOf('\0')>=0)throw failure("INVALID_TEXT",false);texts.put(page,value);
        }
        return new Parsed(Map.copyOf(texts),Map.copyOf(errors));
    }

    static String sanitize(String input){
        Matcher matcher=URL.matcher(input);StringBuffer out=new StringBuffer();
        while(matcher.find()){
            String original=matcher.group(),candidate=original.replaceFirst("[).,;!}]+$","");String replacement=original;
            try{
                String query=URI.create(candidate.replace("&amp;","&")).getRawQuery();
                if(query!=null)for(String pair:query.split("&")){
                    String name=URLDecoder.decode(pair.split("=",2)[0],StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if(Set.of("expires","ossaccesskeyid","signature","security-token","x-oss-security-token","x-oss-signature","x-amz-signature").contains(name)){
                        replacement=RESOURCE_MARKER+original.substring(candidate.length());break;
                    }
                }
            }catch(Exception ignored){
                if(candidate.matches("(?is).*[?&](expires|ossaccesskeyid|signature|security-token|x-oss-security-token|x-oss-signature)=.*"))replacement=RESOURCE_MARKER+original.substring(candidate.length());
            }
            matcher.appendReplacement(out,Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(out).toString();
    }
    private static String usefulMarkdown(String value){return value.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)","").replaceAll("(?is)<img\\b[^>]*>","").replace(RESOURCE_MARKER,"").strip();}

    private static String blockText(Block block){
        String markdown=usefulMarkdown(block.markdown()),text=block.text().strip();
        if(Set.of("figure","image","picture").contains(block.type().toLowerCase(Locale.ROOT))){
            // A caption and the OCR inside its image are separate evidence in this same block.
            if(markdown.isBlank())return text;
            if(text.isBlank()||markdown.contains(text))return markdown;
            if(text.contains(markdown))return text;
            return markdown+"\n\n"+text;
        }
        return markdown.isBlank()?text:block.markdown();
    }

    private BatchResult bind(Parsed parsed,List<Integer> sources,boolean cached,Stats stats,long started)throws IOException {
        interrupted();Map<Integer,OcrResult> pages=new LinkedHashMap<>();Map<Integer,String> errors=new LinkedHashMap<>();
        for(var item:parsed.texts().entrySet())pages.put(sources.get(item.getKey()),new OcrResult(item.getValue(),PROVIDER,model,cached,null));
        for(var item:parsed.errors().entrySet())errors.put(sources.get(item.getKey()),item.getValue());
        Map<String,Object> metrics=stats.metrics();metrics.put("model",model);metrics.put("policy",POLICY);metrics.put("cached",cached);
        metrics.put("resultPages",pages.size());metrics.put("errorPages",errors.size());metrics.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return new BatchResult(pages,errors,metrics);
    }

    private Parsed readCache(Path path,String inputHash,int pages){
        try{
            if(!Files.isRegularFile(path)||Files.size(path)>MAX_RESPONSE_BYTES)return null;JsonNode root=tree(Files.readAllBytes(path));
            if(!identity.equals(root.path("identity").asText())||!inputHash.equals(root.path("inputHash").asText())||root.path("pageCount").asInt()!=pages)return null;
            JsonNode values=root.path("pages");if(!values.isArray()||values.size()!=pages)return null;Map<Integer,String> texts=new TreeMap<>();
            for(int i=0;i<pages;i++){JsonNode page=values.get(i);String text=page.path("text").asText("");
                if(!page.path("text").isTextual()||text.isBlank()||text.length()>MAX_PAGE_TEXT||text.indexOf('\0')>=0||!sanitize(text).equals(text)
                    ||!hash(text.getBytes(StandardCharsets.UTF_8)).equals(page.path("textHash").asText()))return null;texts.put(i,text);}
            return new Parsed(Map.copyOf(texts),Map.of());
        }catch(Exception invalid){return null;}
    }
    private void writeCache(Path path,String inputHash,Parsed result){
        Path temporary=null;
        try{
            Files.createDirectories(cacheDirectory);List<Object> pages=new ArrayList<>();
            for(int i=0;i<result.texts().size();i++){String text=result.texts().get(i);pages.add(Map.of("text",text,"textHash",hash(text.getBytes(StandardCharsets.UTF_8))));}
            byte[] bytes=json.writeValueAsBytes(Map.of("identity",identity,"inputHash",inputHash,"pageCount",pages.size(),"pages",pages));
            if(bytes.length>MAX_RESPONSE_BYTES)return;temporary=Files.createTempFile(cacheDirectory,"pdf-ocr-",".tmp");Files.write(temporary,bytes);
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){/* Disk cache failure does not discard successful OCR. */}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }

    private JsonNode tree(byte[] bytes)throws IOException{try{JsonNode value=json.readTree(bytes);if(value==null)throw failure("INVALID_RESPONSE",false);return value;}catch(OcrFailure safe){throw safe;}catch(Exception invalid){throw failure("INVALID_RESPONSE",false);}}
    private static String requiredText(JsonNode node,String key)throws IOException{JsonNode value=node.path(key);if(!value.isTextual()||value.textValue().isBlank()||value.textValue().length()>256_000)throw failure("INVALID_UPLOAD_POLICY",false);return value.textValue();}
    private static Map<String,Object> numericObject(JsonNode node){Map<String,Object> result=new LinkedHashMap<>();if(node.isObject())node.fields().forEachRemaining(e->{if(e.getKey().matches("[A-Za-z0-9_]{1,80}")){if(e.getValue().isIntegralNumber())result.put(e.getKey(),e.getValue().longValue());else if(e.getValue().isObject())result.put(e.getKey(),numericObject(e.getValue()));}});return result;}
    private static long remaining(long deadline)throws IOException{long left=deadline-System.nanoTime();if(left<=0)throw failure("TIMEOUT",true);return left;}
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw interruptedFailure();}
    private static InterruptedIOException interruptedFailure(){Thread.currentThread().interrupt();return new InterruptedIOException("PDF OCR 已取消");}
    private static OcrFailure failure(String code,boolean temporary){return OcrFailure.of("PDF_"+code,temporary);}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception impossible){throw new IllegalStateException("SHA-256 unavailable");}}
    private static final class Stats {
        long policyRequests,uploadRequests,ocrRequests,policyMillis,uploadMillis,ocrMillis,retries;int lastHttp;Map<String,Object> usage=Map.of();
        void request(String stage){total("httpRequests",1);total(stage+"Requests",1);switch(stage){case "policy"->policyRequests++;case "upload"->uploadRequests++;default->ocrRequests++;}}
        void time(String stage,long nanos){long millis=TimeUnit.NANOSECONDS.toMillis(nanos);total(stage+"Millis",millis);switch(stage){case "policy"->policyMillis+=millis;case "upload"->uploadMillis+=millis;default->ocrMillis+=millis;}}
        Map<String,Object> metrics(){Map<String,Object> value=new LinkedHashMap<>();value.put("httpRequests",policyRequests+uploadRequests+ocrRequests);value.put("policyRequests",policyRequests);value.put("uploadRequests",uploadRequests);value.put("ocrRequests",ocrRequests);value.put("retries",retries);value.put("policyMillis",policyMillis);value.put("uploadMillis",uploadMillis);value.put("ocrMillis",ocrMillis);value.put("httpStatus",lastHttp);value.put("usage",usage);return value;}
    }
}

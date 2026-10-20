package cn.bidcheck.document.word;

import cn.bidcheck.document.DocumentLimits;
import cn.bidcheck.document.FileParser;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.aspose.words.BuildVersionInfo;
import com.aspose.words.License;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static cn.bidcheck.document.word.WordRenderSupport.*;

/** Licensed, bounded Word pagination sidecar. The original POI block identities are never replaced. */
@Service
public final class WordRenderingService {
    private static final Semaphore CAPACITY=new Semaphore(1,true);
    private final Path cache;
    private final String licensePath,fontFolder,fontProfile;
    private final boolean enabled;
    private final int maxPages,timeoutSeconds;
    private final WorkerExecutor executor;
    private volatile LicenseState lastLicense;
    @Autowired public WordRenderingService(@Value("${bidcheck.data:./data}")String data,
            @Value("${bidcheck.word.license-path:}")String licensePath,
            @Value("${bidcheck.word.enabled:true}")boolean enabled,
            @Value("${bidcheck.word.max-pages:500}")int maxPages,
            @Value("${bidcheck.word.timeout-seconds:120}")int timeoutSeconds,
            @Value("${bidcheck.word.font-folder:}")String fontFolder){this(Path.of(data),licensePath,enabled,maxPages,timeoutSeconds,fontFolder,new ProcessWorker());}
    WordRenderingService(Path data,String licensePath,boolean enabled,int maxPages,int timeoutSeconds,String fontFolder,WorkerExecutor executor){
        if(maxPages<1||maxPages>1000||timeoutSeconds<5||timeoutSeconds>300)throw new IllegalArgumentException("WORD_RENDER_CONFIGURATION_INVALID");
        this.cache=data.toAbsolutePath().normalize().resolve("word-renderings");this.licensePath=Objects.toString(licensePath,"").strip();this.fontFolder=Objects.toString(fontFolder,"").strip();this.enabled=enabled;this.maxPages=maxPages;this.timeoutSeconds=timeoutSeconds;this.executor=executor;this.fontProfile=fontProfile(this.fontFolder);
    }
    public String identity(){LicenseState state=license();return hash(POLICY+"|"+WordDocxStagingNormalizer.POLICY+"|"+BuildVersionInfo.getVersion()+"|"+WordsCompatibility.identity()+"|"+enabled+"|"+state.status+"|"+state.hash+"|"+fontProfile+"|"+maxPages+"|"+timeoutSeconds);}
    public Map<String,Object> configuration(){LicenseState state=license();return map("enabled",enabled,"licensed",state.licensed,"status",enabled?state.status:"DISABLED","renderer","Aspose.Words","rendererVersion",BuildVersionInfo.getVersion(),"compatibility",WordsCompatibility.configuration(),"maxPages",maxPages,"timeoutSeconds",timeoutSeconds,"concurrency",1,"fontProfile",fontProfile,"identity",identity(),"licenseConfigured",!licensePath.isBlank());}
    public Map<String,Object> layout(Document document,Path original){
        try{return layoutChecked(document,original);}catch(InterruptedIOException e){Thread.currentThread().interrupt();throw new CancellationException("Word 渲染已中断");}catch(IOException e){String code=e.getMessage();return decorated(failure(code!=null&&code.matches("[A-Z_]{1,70}")?code:"ERROR"),document);}
    }
    public Path renderedPdf(Document document,Path original)throws IOException{
        Map<String,Object> result=layout(document,original);if(!Boolean.TRUE.equals(result.get("usableForEvidence")))throw new IOException("WORD_RENDER_UNAVAILABLE:"+str(result,"status"));Path pdf=cache.resolve(str(result,"cacheKey")).resolve("rendered.pdf");
        if(!str(result,"derivedSha256").equals(hash(pdf,MAX_PDF_BYTES)))throw new IOException("WORD_DERIVED_HASH_MISMATCH");return pdf;
    }
    public byte[] renderPage(Document document,Path original,int page)throws IOException{
        if(page<1||page>maxPages)throw new IOException("WORD_RENDER_PAGE_INVALID");Path pdf=renderedPdf(document,original);return new FileParser().renderPdfPage(pdf,page);
    }
    private Map<String,Object> layoutChecked(Document doc,Path original)throws IOException{
        interrupted();if(!enabled)return decorated(failure("DISABLED"),doc);LicenseState state=license();if(!state.licensed)return decorated(failure(state.status),doc);WordsCompatibility.requireCompatible();
        validateSource(doc,original);String identity=identity(),key=cacheKey(doc,identity);Files.createDirectories(cache);Path destination=cache.resolve(key);Map<String,Object> cached=cached(destination,doc,identity,key);if(cached!=null)return cached;
        boolean acquired=false;Path staging=null;
        try{
            try{acquired=CAPACITY.tryAcquire(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("WORD_RENDER_INTERRUPTED");}
            if(!acquired)return decorated(failure("BUSY"),doc);cached=cached(destination,doc,identity,key);if(cached!=null)return cached;
            staging=Files.createTempDirectory(cache,".pending-");Map<String,Object> request=map("original",original.toAbsolutePath().toString(),"sourceSha256",doc.sha256().toLowerCase(Locale.ROOT),"rendererIdentity",WordsCompatibility.identity(),"licensePath",licensePath,"licenseHash",state.hash,"fontFolder",fontFolder,"outputDirectory",staging.toString(),"timeoutSeconds",timeoutSeconds,"maxPages",maxPages);
            String status=executor.execute(request,staging,timeoutSeconds);if(!status.equals("COMPLETE"))return decorated(failure(status),doc);
            Path resultFile=staging.resolve("result.json");if(!Files.isRegularFile(resultFile)||Files.size(resultFile)>MAX_RESULT_BYTES)throw new IOException("WORD_RESULT_INVALID");
            Map<String,Object> result=JSON.readValue(Files.readAllBytes(resultFile),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
            if(!Boolean.TRUE.equals(result.get("usableForEvidence"))||!"COMPLETE".equals(result.get("status")))return decorated(failure(safeStatus(str(result,"status"))),doc);
            validateResult(result,doc,staging.resolve("rendered.pdf"));bindBlocks(result,doc);result.put("configurationIdentity",identity);result.put("fontProfile",fontProfile);result.put("cacheKey",key);result=decorated(result,doc);
            // Publish only the two checked derived artifacts. The worker's source copy and temporary files are discarded.
            Path ready=Files.createTempDirectory(cache,".ready-");try{
                Files.move(staging.resolve("rendered.pdf"),ready.resolve("rendered.pdf"));try(OutputStream out=new LimitedOutput(Files.newOutputStream(ready.resolve("result.json")),MAX_RESULT_BYTES)){JSON.writeValue(out,result);}
                if(Files.exists(destination))clear(destination,cache);try{Files.move(ready,destination,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(ready,destination);}
            }finally{if(Files.exists(ready))clear(ready,cache);}
            return result;
        }finally{try{if(staging!=null)clear(staging,cache);}catch(IOException cleanupFailure){/* Unpublished remnants are never cache entries. */}finally{if(acquired)CAPACITY.release();}}
    }
    private void validateSource(Document doc,Path original)throws IOException{
        if(doc==null||doc.projectId()==null||doc.id()==null||doc.version()<1||doc.pages()==null||doc.name()==null||!doc.name().toLowerCase(Locale.ROOT).endsWith(".docx"))throw new IOException("WORD_DOCUMENT_INVALID");
        if(doc.sha256()==null||!doc.sha256().matches("[a-fA-F0-9]{64}")||original==null||!Files.isRegularFile(original)||Files.size(original)<1||Files.size(original)>DocumentLimits.MAX_FILE_BYTES)throw new IOException("WORD_FILE_LIMIT");
        if(!doc.sha256().equalsIgnoreCase(hash(original,DocumentLimits.MAX_FILE_BYTES)))throw new IOException("SOURCE_HASH_MISMATCH");
    }
    private void validateResult(Map<String,Object> result,Document doc,Path pdf)throws IOException{
        int pages=result.get("pageCount") instanceof Number n?n.intValue():0;if(pages<1||pages>maxPages||!doc.sha256().equalsIgnoreCase(str(result,"sourceSha256"))||!BuildVersionInfo.getVersion().equals(str(result,"rendererVersion")))throw new IOException("WORD_RESULT_INVALID");
        if(!WordsCompatibility.identity().equals(str(result,"rendererIdentity")))throw new IOException("WORD_SDK_IDENTITY_MISMATCH");
        if(!(result.get("pages") instanceof List<?> list)||list.size()!=pages||!(result.get("spans") instanceof List<?> spans)||spans.size()>MAX_SPANS||!(result.get("renderedSpans") instanceof List<?> rendered)||rendered.size()>MAX_SPANS)throw new IOException("WORD_RESULT_INVALID");
        if(!Files.isRegularFile(pdf)||Files.size(pdf)<1||Files.size(pdf)>MAX_PDF_BYTES||!hash(pdf,MAX_PDF_BYTES).equals(str(result,"derivedSha256")))throw new IOException("WORD_DERIVED_HASH_MISMATCH");
    }
    private Map<String,Object> cached(Path folder,Document doc,String identity,String key)throws IOException{
        Path metadata=folder.resolve("result.json");if(!Files.isRegularFile(metadata))return null;
        try{if(Files.size(metadata)>MAX_RESULT_BYTES)return null;Map<String,Object> result=JSON.readValue(Files.readAllBytes(metadata),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
            if(!identity.equals(str(result,"configurationIdentity"))||!key.equals(str(result,"cacheKey"))||!"COMPLETE".equals(str(result,"status"))||!Boolean.TRUE.equals(result.get("usableForEvidence")))return null;validateResult(result,doc,folder.resolve("rendered.pdf"));result.put("cached",true);return result;
        }catch(InterruptedIOException e){throw e;}catch(IOException|RuntimeException invalid){return null;}
    }
    static String cacheKey(Document doc,String identity){StringBuilder parsed=new StringBuilder();for(Page p:doc.pages())parsed.append(hash(p.parser()+"\0"+p.locationType()+"\0"+p.number()+"\0"+p.text()));return hash(identity+"|"+doc.projectId()+"|"+doc.id()+"|"+doc.version()+"|"+doc.sha256()+"|"+parsed);}
    @SuppressWarnings("unchecked") static void bindBlocks(Map<String,Object> result,Document doc){
        Map<String,List<Integer>> blocks=new HashMap<>();for(Page page:doc.pages()){String text=Objects.toString(page.text(),"").split("【OCR识别文字】|【图片 \\d+ OCR识别文字】",2)[0].replace("【原生文字】","");blocks.computeIfAbsent(normalize(text),k->new ArrayList<>()).add(page.number());}
        for(Object raw:(List<?>)result.get("spans"))if(raw instanceof Map<?,?> entry){Map<String,Object> span=(Map<String,Object>)entry;String text=normalize(str(span,"text"));List<Integer> ids=blocks.getOrDefault(text,List.of());span.put("documentBlock",!text.isBlank()&&ids.size()==1?ids.get(0):null);span.put("documentId",doc.id());span.put("documentHash",doc.sha256());span.put("documentVersion",doc.version());span.put("sourceDocumentRole",doc.role());}
    }
    private static String normalize(String text){return text.replaceAll("\\s+","").strip();}
    private Map<String,Object> decorated(Map<String,Object> result,Document doc){result.put("renderer","Aspose.Words");result.put("rendererVersion",BuildVersionInfo.getVersion());result.put("cached",false);if(doc!=null){result.put("documentId",doc.id());result.put("documentVersion",doc.version());result.put("sourceSha256",doc.sha256());}return result;}
    private static String safeStatus(String code){return Set.of("LICENSE_REQUIRED","LICENSE_INVALID","FONT_SUBSTITUTION","RESOURCE_BLOCKED","REVISIONS_PRESENT","LAYOUT_WARNING","TIMEOUT","MEMORY_LIMIT","WORD_PAGE_LIMIT","WORD_IMAGE_LIMIT","WORD_PACKAGE_LIMIT","WORD_XML_LIMIT","WORD_XML_UNSAFE","WORD_MACROS_UNSUPPORTED","WORD_SDK_UNSUPPORTED","WORD_SDK_CONFIGURATION_ERROR","WORD_SDK_IDENTITY_MISMATCH","WORD_LEGACY_PACKAGE_UNSUPPORTED","WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED","WORD_LEGACY_EMBEDDED_CONTENT_UNSUPPORTED","WORD_LEGACY_FORM_UNSUPPORTED","WORD_STAGING_PATH_INVALID","WORD_MAIN_PART_MISSING","SOURCE_HASH_MISMATCH","ERROR").contains(code)?code:"ERROR";}
    private LicenseState license(){
        if(licensePath.isBlank())return new LicenseState("LICENSE_REQUIRED",false,"");try{Path path=Path.of(licensePath);if(!Files.isRegularFile(path)||Files.size(path)<1||Files.size(path)>2_000_000)return new LicenseState("LICENSE_INVALID",false,"");byte[] bytes=Files.readAllBytes(path);String fingerprint=hash(bytes);LicenseState previous=lastLicense;if(previous!=null&&previous.hash.equals(fingerprint))return previous;
            try{validateXml(bytes,false);new License().setLicense(new ByteArrayInputStream(bytes));return lastLicense=new LicenseState("LICENSED",true,fingerprint);}catch(Exception invalid){return lastLicense=new LicenseState("LICENSE_INVALID",false,fingerprint);}
        }catch(IOException|RuntimeException invalid){return new LicenseState("LICENSE_INVALID",false,"");}
    }
    private static String fontProfile(String custom){
        List<String> inventory=new ArrayList<>();inventory.add(System.getProperty("os.name","")+"|"+System.getProperty("java.version",""));
        List<Path> folders=new ArrayList<>();if(!custom.isBlank())folders.add(Path.of(custom));else if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))folders.add(Path.of(Objects.toString(System.getenv("WINDIR"),"C:/Windows"),"Fonts"));else {folders.add(Path.of("/usr/share/fonts"));folders.add(Path.of("/usr/local/share/fonts"));}
        for(Path folder:folders)try(var paths=Files.walk(folder,8)){for(Path file:paths.filter(Files::isRegularFile).limit(10000).sorted().toList())inventory.add(file.toAbsolutePath()+":"+Files.size(file)+":"+Files.getLastModifiedTime(file).toMillis());}catch(IOException e){inventory.add("UNAVAILABLE");}
        return hash(String.join("\n",inventory));
    }
    record LicenseState(String status,boolean licensed,String hash){}
    interface WorkerExecutor{String execute(Map<String,Object> request,Path output,int timeout)throws IOException;}
    static final class ProcessWorker implements WorkerExecutor{
        public String execute(Map<String,Object> request,Path output,int timeout)throws IOException{
            return launch(command(),request,output,timeout);
        }
        static String launch(List<String> args,Map<String,Object> request,Path output,int timeout)throws IOException{
            if(timeout<1||timeout>300)throw new IOException("WORD_RENDER_CONFIGURATION_INVALID");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);byte[] payload=JSON.writeValueAsBytes(request);if(payload.length>65536)throw new IOException("WORD_REQUEST_LIMIT");
            Process process=null;FutureTask<Void> writer=null;try{
                Path temp=Files.createDirectories(output.resolve("temp"));var workerArgs=new ArrayList<>(args.subList(1,args.size()));workerArgs.add(0,"-Djava.io.tmpdir="+temp.toAbsolutePath());
                Path argumentFile=output.resolve("worker.args");Files.writeString(argumentFile,String.join("\n",workerArgs.stream().map(a->"\""+a.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList()),java.nio.charset.StandardCharsets.UTF_8);
                ProcessBuilder builder=new ProcessBuilder(args.get(0),"@"+argumentFile);builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                // Do not pass service/model/storage secrets to the renderer process.
                builder.environment().keySet().removeIf(k->k.startsWith("BIDCHECK_")||k.equals("JAVA_TOOL_OPTIONS")||k.equals("JDK_JAVA_OPTIONS")||k.equals("_JAVA_OPTIONS"));
                process=builder.start();final Process child=process;
                writer=new FutureTask<>(()->{try(OutputStream in=child.getOutputStream()){in.write(payload);}return null;});
                Thread inputThread=new Thread(writer,"word-render-input");inputThread.setDaemon(true);inputThread.start();
                long remaining=deadline-System.nanoTime();if(remaining<=0||!process.waitFor(remaining,TimeUnit.NANOSECONDS)){terminate(process);return "TIMEOUT";}
                remaining=deadline-System.nanoTime();if(remaining<=0)return "TIMEOUT";
                try{writer.get(remaining,TimeUnit.NANOSECONDS);}catch(TimeoutException e){return "TIMEOUT";}catch(ExecutionException e){return "ERROR";}
                return process.exitValue()==0?"COMPLETE":"ERROR";
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("WORD_RENDER_INTERRUPTED");}finally{if(process!=null&&process.isAlive())terminate(process);if(writer!=null)writer.cancel(true);}
        }
        static List<String> command()throws IOException{
            String cp=System.getProperty("java.class.path");String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"javaw.exe":"java").toString();
            List<String> command=new ArrayList<>(List.of(java,"-Xmx512m","-XX:MaxMetaspaceSize=192m","-Djava.awt.headless=true","-Dfile.encoding=UTF-8"));
            boolean boot=false;if(!cp.contains(File.pathSeparator)&&cp.endsWith(".jar")){Path app=Path.of(cp);if(Files.isRegularFile(app))try(JarFile jar=new JarFile(app.toFile())){boot=jar.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class")!=null;}}
            if(boot){command.add("-Dloader.main="+WordRenderWorker.class.getName());command.addAll(List.of("-cp",cp,"org.springframework.boot.loader.launch.PropertiesLauncher"));}
            else command.addAll(List.of("-cp",cp,WordRenderWorker.class.getName()));return command;
        }
        private static void terminate(Process process){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
}

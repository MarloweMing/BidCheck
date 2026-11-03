package cn.bidcheck.ocr;
import cn.bidcheck.compat.HexFormat;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded JVM image-only grid detector with optional local ONNX cell recognition. */
public final class PixelGridClient implements AutoCloseable {
    public static final String POLICY="java-pixel-grid-client-2";
    private static final int LIMIT=1024*1024;
    private final Path cache;
    private final Engine engine;
    private final boolean enabled;
    private final String identity;
    private final Duration timeout;
    private final Semaphore capacity;
    private final ExecutorService io;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(LIMIT).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    @FunctionalInterface public interface Engine extends AutoCloseable {
        JsonNode process(byte[] image,OcrLayout.Frame frame,boolean withText,List<Map<String,Object>> regions,long deadlineNanos)throws IOException;
        default String identity(){return getClass().getName();}
        @Override default void close(){}
    }
    public PixelGridClient(boolean requested,String modelDirectory,String data,int concurrency,int timeoutSeconds) {
        this(requested,new JavaLocalOcrEngine(modelDirectory),data,concurrency,timeoutSeconds);
    }
    /** Injectable engine for deterministic cancellation and source-validation tests. */
    public PixelGridClient(boolean requested,Engine engine,String data,int concurrency,int timeoutSeconds) {
        if(concurrency<1||concurrency>4||timeoutSeconds<1||timeoutSeconds>60)throw new IllegalArgumentException("本地表格解析资源配置无效。");
        this.engine=Objects.requireNonNull(engine);this.cache=java.nio.file.Paths.get(data).toAbsolutePath().normalize().resolve("pixel-grid-cache");
        this.enabled=requested;
        this.timeout=Duration.ofSeconds(timeoutSeconds);this.capacity=new Semaphore(concurrency,true);
        this.io=Executors.newFixedThreadPool(concurrency,r->{Thread t=new Thread(r,"pixel-grid-java");t.setDaemon(true);return t;});
        this.identity=hash((POLICY+"|"+engine.identity()+"|"+enabled).getBytes(StandardCharsets.UTF_8));
    }
    public boolean enabled(){return enabled;}
    public String identity(){return identity;}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Detection{
        private final List<GridLayoutAssembler.Grid> grids;
        private final List<String> warnings;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Detection(@com.fasterxml.jackson.annotation.JsonProperty("grids") List<GridLayoutAssembler.Grid> grids, @com.fasterxml.jackson.annotation.JsonProperty("warnings") List<String> warnings) { this.grids = grids; this.warnings = warnings; }
        public List<GridLayoutAssembler.Grid> grids() { return grids; }
        public List<String> warnings() { return warnings; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Detection that = (Detection) other; return java.util.Objects.equals(this.grids, that.grids) && java.util.Objects.equals(this.warnings, that.warnings); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.grids); hash = 31 * hash + java.util.Objects.hashCode(this.warnings); return hash; }
        @Override public String toString() { return "Detection[" + "grids=" + grids + ", warnings=" + warnings + "]"; }
}
    public Detection detect(byte[] image,OcrLayout.Frame frame)throws IOException {
        JsonNode response=response(image,frame,false,cn.bidcheck.compat.Java8Api.listOf());List<String> warnings=new ArrayList<>();
        for(JsonNode w:response.path("warnings"))if(cn.bidcheck.compat.Java8Api.setOf("GRID_GEOMETRY_AMBIGUOUS", "GRID_BUDGET_EXCEEDED", "GRID_SIZE_LIMIT", "GRID_COUNT_LIMIT").contains(w.asText()))warnings.add(w.asText());
        return new Detection(parse(response,frame,null),cn.bidcheck.compat.Java8Api.listCopyOf(warnings));
    }
    /** Secondary OCR is local and called only when an actual detected cell has no native OCR text. */
    public OcrLayout localText(byte[] image,OcrLayout text,List<GridLayoutAssembler.Grid> grids)throws IOException{
        cn.bidcheck.ocr.OcrLayout.Frame frame=text.frames().get(0);List<Map<String,Object>> regions=refinementRegions(text,grids);
        if(regions.isEmpty())throw failure("NO_REFINEMENT_REGIONS");
        return parseLocal(response(image,frame,true,regions),frame,regions);
    }
    /** Prioritize all cells touched by a crossing box, then empty cells. Never slice text by character count. */
    static List<Map<String,Object>> refinementRegions(OcrLayout text,List<GridLayoutAssembler.Grid> grids){
        List<GridLayoutAssembler.Region> cells=cn.bidcheck.compat.Java8Api.toList(grids.stream().flatMap(g->g.cells().stream()).filter(c->c.box().size()==4));
        LinkedHashMap<List<Double>,Map<String,Object>> selected=new LinkedHashMap<>();
        for(cn.bidcheck.ocr.OcrLayout.TextBox box:text.textBoxes()){
            cn.bidcheck.ocr.OcrLayout.Box b=box.bbox();double x=b.x(),y=b.y(),right=x+b.width(),bottom=y+b.height();
            boolean contained=cells.stream().anyMatch(c->c.box().get(0)<=x&&c.box().get(1)<=y&&c.box().get(2)>=right&&c.box().get(3)>=bottom);
            if(contained)continue;
            for(cn.bidcheck.ocr.GridLayoutAssembler.Region c:cells){java.util.List<java.lang.Double> v=c.box();if(selected.size()<64&&v.get(0)<right&&v.get(1)<bottom&&v.get(2)>x&&v.get(3)>y)selected.computeIfAbsent(v,k->cn.bidcheck.compat.Java8Api.mapOf("id", "cell_"+selected.size(), "box", k));}
        }
        for(cn.bidcheck.ocr.GridLayoutAssembler.Region c:cells){java.util.List<java.lang.Double> v=c.box();boolean occupied=text.textBoxes().stream().anyMatch(t->{cn.bidcheck.ocr.OcrLayout.Box b=t.bbox();return v.get(0)<b.x()+b.width()&&v.get(1)<b.y()+b.height()&&v.get(2)>b.x()&&v.get(3)>b.y();});if(!occupied&&selected.size()<64)selected.computeIfAbsent(v,k->cn.bidcheck.compat.Java8Api.mapOf("id", "cell_"+selected.size(), "box", k));}
        return cn.bidcheck.compat.Java8Api.listCopyOf(selected.values());
    }
    private JsonNode response(byte[] image,OcrLayout.Frame frame,boolean withText,List<Map<String,Object>> regions)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("本地表格解析已中断。");
        if(!enabled)throw failure("NOT_CONFIGURED");
        if(image==null||image.length==0||image.length>6*1024*1024||frame==null||!hash(image).equals(frame.imageHash()))throw failure("INVALID_INPUT");
        String key=hash((identity+frame.imageHash()+withText+json.writeValueAsString(regions)).getBytes(StandardCharsets.UTF_8));Path entry=cache.resolve(key+".json");
        JsonNode cached=readCache(entry);if(cached!=null)try{validate(cached,frame,null,withText,regions);return cached;}catch(IOException|RuntimeException ignored){}
        boolean acquired=false,submitted=false;Future<JsonNode> output=null;long deadline=System.nanoTime()+timeout.toNanos();
        try{
            acquired=capacity.tryAcquire(timeout.toMillis(),TimeUnit.MILLISECONDS);if(!acquired)throw failure("CAPACITY_TIMEOUT");
            cached=readCache(entry);if(cached!=null)try{validate(cached,frame,null,withText,regions);return cached;}catch(IOException|RuntimeException ignored){}
            java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean(),released=new java.util.concurrent.atomic.AtomicBoolean();
            FutureTask<JsonNode> task=new FutureTask<JsonNode>(()->engine.process(image,frame,withText,regions,deadline)){
                @Override public void run(){started.set(true);try{super.run();}finally{if(released.compareAndSet(false,true))capacity.release();}}
                @Override protected void done(){if(!started.get()&&released.compareAndSet(false,true))capacity.release();}
            };
            io.execute(task);output=task;submitted=true;
            JsonNode response=output.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            byte[] bytes=json.writeValueAsBytes(response);if(bytes.length>LIMIT)throw failure("RESPONSE_LIMIT");validate(response,frame,null,withText,regions);
            writeCache(entry,bytes);return response;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("本地表格解析已中断。");}
        catch(TimeoutException invalid){throw failure("TIMEOUT");}
        catch(ExecutionException invalid){Throwable cause=invalid.getCause();if(cause instanceof InterruptedIOException){if(Thread.currentThread().isInterrupted())throw (InterruptedIOException)cause;throw failure("TIMEOUT");}if(cause instanceof GridException)throw (GridException)cause;String code=cause instanceof IOException?cause.getMessage():"ENGINE_FAILED";throw failure(code!=null&&code.matches("[A-Z][A-Z0-9_]{0,63}")?code:"ENGINE_FAILED");}
        catch(IOException invalid){if(invalid instanceof GridException)throw invalid;throw failure("UNAVAILABLE");}
        catch(RuntimeException invalid){throw failure("INVALID_RESPONSE");}
        finally{if(output!=null&&!output.isDone())output.cancel(true);if(acquired&&!submitted)capacity.release();}
    }
    private void validate(JsonNode root,OcrLayout.Frame frame,String id,boolean withText,List<Map<String,Object>> regions)throws IOException {parse(root,frame,id);if(withText)parseLocal(root,frame,regions);}
    OcrLayout parseLocal(JsonNode root,OcrLayout.Frame frame,List<Map<String,Object>> regions)throws IOException {
        parse(root,frame,null);JsonNode boxes=root.get("textBoxes");
        if(boxes==null||!boxes.isArray()||boxes.isEmpty()||boxes.size()>OcrLayout.MAX_BOXES)throw failure("INVALID_LOCAL_OCR");
        List<OcrLayout.TextBox> text=new ArrayList<>();List<String> order=new ArrayList<>();StringBuilder transcript=new StringBuilder();
        for(JsonNode b:boxes){JsonNode value=b.get("text"),confidence=b.get("confidence");if(value==null||!value.isTextual()||cn.bidcheck.compat.Java8Api.isBlank(value.asText())||value.asText().length()>8000||confidence==null||!confidence.isNumber())throw failure("INVALID_LOCAL_OCR");
            String id="local_"+text.size();JsonNode box=b.path("bbox");List<Double> values=new ArrayList<>();for(String k:cn.bidcheck.compat.Java8Api.listOf("x", "y", "width", "height")){JsonNode n=box.get(k);if(n==null||!n.isNumber()||!Double.isFinite(n.doubleValue()))throw failure("INVALID_LOCAL_OCR");values.add(n.doubleValue());}
            JsonNode region=b.get("sourceRegion");if(region==null||!region.isObject())throw failure("INVALID_CROP_SOURCE");
            Map<String,Object> expected=regions.stream().filter(r->r.get("id").equals(region.path("id").asText())).findFirst().orElseThrow(()->failure("INVALID_CROP_SOURCE"));
            Object xy=expected.get("box");if(!(xy instanceof List<?>)||((List<?>) (xy)).size()!=4)throw failure("INVALID_CROP_SOURCE");
            JsonNode actual=region.path("box");if(!actual.isArray()||actual.size()!=4)throw failure("INVALID_CROP_SOURCE");double[] bounds=new double[4];
            for(int i=0;i<4;i++){double v=((Number)((List<?>) (xy)).get(i)).doubleValue();bounds[i]=i<2?Math.ceil(v):Math.floor(v);if(!actual.get(i).isNumber()||actual.get(i).doubleValue()!=bounds[i])throw failure("INVALID_CROP_SOURCE");}
            int inputWidth=integer(region,"inputWidth",1,2400),inputHeight=integer(region,"inputHeight",1,2400);
            if((long)inputWidth*inputHeight>4_000_000)throw failure("INVALID_CROP_SOURCE");
            for(String axis:cn.bidcheck.compat.Java8Api.listOf("scale", "scaleX", "scaleY")){com.fasterxml.jackson.databind.JsonNode n=region.get(axis);if(n==null||!n.isNumber()||!Double.isFinite(n.doubleValue())||n.doubleValue()<=0||n.doubleValue()>4)throw failure("INVALID_CROP_SOURCE");}
            if(Math.abs(region.get("scaleX").doubleValue()-inputWidth/(bounds[2]-bounds[0]))>1e-9||Math.abs(region.get("scaleY").doubleValue()-inputHeight/(bounds[3]-bounds[1]))>1e-9)throw failure("INVALID_CROP_SOURCE");
            if(values.get(0)<bounds[0]||values.get(1)<bounds[1]||values.get(0)+values.get(2)>bounds[2]||values.get(1)+values.get(3)>bounds[3])throw failure("INVALID_CROP_SOURCE");
            text.add(new OcrLayout.TextBox(id,frame.id(),value.asText(),new OcrLayout.Box(values.get(0),values.get(1),values.get(2),values.get(3)),confidence.doubleValue(),"RAPIDOCR"));order.add(id);if(!(transcript.length() == 0))transcript.append('\n');transcript.append(value.asText());if(transcript.length()>50000)throw failure("INVALID_LOCAL_OCR");
        }
        cn.bidcheck.ocr.OcrLayout.Frame cropFrame=new OcrLayout.Frame(frame.id(),frame.width(),frame.height(),frame.imageHash(),"CELL_CROP_MAPPED",frame.sourceIndex(),frame.parentWidth(),frame.parentHeight(),frame.crop());
        try{return new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,cn.bidcheck.compat.Java8Api.listOf(cropFrame),text,cn.bidcheck.compat.Java8Api.listOf(),order,cn.bidcheck.compat.Java8Api.listOf()).validated(transcript.toString());}catch(IllegalArgumentException invalid){throw failure("INVALID_LOCAL_OCR");}
    }
    /** Source identity and numeric types must be explicit; no normalized-coordinate heuristics. */
    List<GridLayoutAssembler.Grid> parse(JsonNode root,OcrLayout.Frame frame,String requestId)throws IOException {
        if(root==null||!root.isObject()||!"OK".equals(root.path("status").asText())||!root.path("schemaVersion").asText().equals("local-ocr-grid-1")||!root.path("coordinateSystem").asText().equals(OcrLayout.COORDINATES)
            ||requestId!=null&&!requestId.equals(root.path("id").asText())||!frame.imageHash().equals(root.path("imageHash").asText())
            ||integer(root,"width",1,32768)!=frame.width()||integer(root,"height",1,32768)!=frame.height()||root.has("error")||root.has("errorCode"))throw failure("SOURCE_MISMATCH");
        JsonNode candidates=root.get("gridCandidates");if(candidates==null||!candidates.isArray()||candidates.size()>40)throw failure("INVALID_GRID");
        List<GridLayoutAssembler.Grid> grids=new ArrayList<>();int total=0;
        for(JsonNode g:candidates){
            if(!g.isObject()||!"PIXEL_XYXY".equals(g.path("coordinateSpace").asText())||!"f1".equals(g.path("frameId").asText()))throw failure("INVALID_GRID");
            int rows=integer(g,"rows",1,500),columns=integer(g,"columns",1,100);JsonNode cells=g.get("cells");
            if(cells==null||!cells.isArray()||(total+=cells.size())>3000||cells.isEmpty())throw failure("INVALID_GRID");
            List<GridLayoutAssembler.Region> regions=new ArrayList<>();
            for(JsonNode c:cells){JsonNode b=c.get("box");if(b==null||!b.isArray()||b.size()!=4||!c.path("header").isBoolean())throw failure("INVALID_GRID");
                List<Double> xy=new ArrayList<>();for(JsonNode n:b){if(!n.isNumber()||!Double.isFinite(n.doubleValue()))throw failure("INVALID_GRID");xy.add(n.doubleValue());}
                regions.add(new GridLayoutAssembler.Region(integer(c,"row",0,499),integer(c,"column",0,99),integer(c,"rowSpan",1,500),integer(c,"columnSpan",1,100),xy,c.get("header").booleanValue()));
            }
            grids.add(new GridLayoutAssembler.Grid(frame.id(),"PIXEL_XYXY",rows,columns,regions));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(grids);
    }
    private static int integer(JsonNode n,String name,int min,int max)throws IOException {JsonNode v=n.get(name);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<min||v.intValue()>max)throw failure("INVALID_GRID");return v.intValue();}
    private JsonNode readCache(Path entry){try{if(Files.isRegularFile(entry)&&Files.size(entry)<=LIMIT)return json.readTree(Files.readAllBytes(entry));}catch(IOException|RuntimeException ignored){}return null;}
    private void writeCache(Path entry,byte[] bytes){Path temp=null;try{Files.createDirectories(cache);temp=Files.createTempFile(cache,"grid-",".tmp");Files.write(temp,bytes);try{Files.move(temp,entry,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,entry,StandardCopyOption.REPLACE_EXISTING);}}catch(IOException ignored){}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static GridException failure(String code){return new GridException(code);}
    public static final class GridException extends IOException {private final String code;GridException(String code){super("[PIXEL_GRID_"+code+"] 图像表格定位未完成，文字和已校验位置保留。");this.code=code;}public String code(){return code;}}
    @Override public void close(){io.shutdownNow();try{if(io.awaitTermination(2,TimeUnit.SECONDS))engine.close();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}

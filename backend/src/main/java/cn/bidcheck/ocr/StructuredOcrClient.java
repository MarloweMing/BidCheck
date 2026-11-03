package cn.bidcheck.ocr;

import java.io.IOException;
import java.io.InterruptedIOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Native OCR owns text and boxes; local pixels own grids; the server owns references. */
@Component
@Primary
public final class StructuredOcrClient implements OcrClient {
    private final OcrClient transcription;
    private final DashScopeLayoutClient layout;
    private final OcrClient advanced;
    private final PixelGridClient grids;
    public static final String POLICY="native-ocr-pixel-grid-2-person-credentials";

    public StructuredOcrClient(DashScopeOcrClient transcription, boolean enabled, String url, String key, String model,
            String data, int concurrency, int timeoutSeconds, String engine, String ocrUrl, String ocrKey,
            String ocrModel, String modelDirectory) {
        this(transcription, enabled, url, key, model, data, concurrency, timeoutSeconds, engine, ocrUrl, ocrKey, ocrModel, modelDirectory, OcrRequestSettings.defaults());
    }

    @Autowired
    public StructuredOcrClient(DashScopeOcrClient transcription,
            @Value("${bidcheck.ocr.layout.enabled:false}") boolean enabled,
            @Value("${bidcheck.ocr.layout.url:}") String url,
            @Value("${bidcheck.ocr.layout.key:}") String key,
            @Value("${bidcheck.ocr.layout.model:}") String model,
            @Value("${bidcheck.data:./data}") String data,
            @Value("${bidcheck.ocr.layout.concurrency:4}") int concurrency,
            @Value("${bidcheck.ocr.layout.timeout-seconds:45}") int timeoutSeconds,
            @Value("${bidcheck.ocr.layout.engine:native-grid}") String engine,
            @Value("${bidcheck.ocr.url:}") String ocrUrl,
            @Value("${bidcheck.ocr.key:}") String ocrKey,
            @Value("${bidcheck.ocr.model:}") String ocrModel,
            @Value("${bidcheck.ocr.layout.model-directory:.local/table-models}") String modelDirectory, OcrRequestSettings settings) {
        this.transcription=java.util.Objects.requireNonNull(transcription);
        if(!cn.bidcheck.compat.Java8Api.setOf("native-grid", "legacy-vl").contains(engine))throw new IllegalArgumentException("不支持的结构化识别方式。");
        this.layout="legacy-vl".equals(engine)?new DashScopeLayoutClient(enabled,url,key,model,data,concurrency,timeoutSeconds,settings):null;
        this.advanced=layout==null?new DashScopeAdvancedOcrClient(enabled,ocrUrl,ocrKey,ocrModel,data,concurrency,settings):null;
        this.grids=layout==null?new PixelGridClient(enabled,modelDirectory,data,concurrency,timeoutSeconds):null;
    }
    public StructuredOcrClient(OcrClient transcription, DashScopeLayoutClient layout) {
        this.transcription = java.util.Objects.requireNonNull(transcription); this.layout = java.util.Objects.requireNonNull(layout);this.advanced=null;this.grids=null;
    }
    public StructuredOcrClient(OcrClient transcription,OcrClient advanced,PixelGridClient grids){this.transcription=java.util.Objects.requireNonNull(transcription);this.advanced=java.util.Objects.requireNonNull(advanced);this.grids=java.util.Objects.requireNonNull(grids);this.layout=null;}
    @Override public boolean enabled() { return transcription.enabled(); }
    @Override public String modelName() { return transcription.modelName(); }
    @Override public OcrClient pdfRecoveryClient() { return transcription; }
    @Override public String identity() { return transcription.identity() + ":" + (layout!=null?DashScopeLayoutClient.POLICY+":"+layout.identity():POLICY+":"+advanced.identity()+":"+grids.identity()+":"+GridLayoutAssembler.VERSION+":"+LocalOcrGapFiller.VERSION); }
    public boolean layoutEnabled() { return layout!=null?layout.enabled():advanced.enabled()&&grids.enabled(); }
    public String layoutModelName() { return layout!=null?layout.modelName():advanced.modelName()+" / native-grid"; }
    @javax.annotation.PreDestroy public void close(){if(grids!=null)grids.close();}
    @Override public OcrBatchPolicy batchPolicy() {
        return transcription.batchPolicy();
    }
    @Override public void recognizeBatch(java.util.List<OcrBatch.Input> inputs, OcrBatch.Listener listener) throws IOException {
        // Tables and independent evidence retain the precise native single-image path.
        if (inputs.stream().anyMatch(i -> i.pageType().toUpperCase(java.util.Locale.ROOT).matches(".*(TABLE|CERTIFICATE|IDENTITY|INDEPENDENT|VEHICLE_LICENSE|DRIVING_LICENSE|PASSPORT|ID_CARD|SIGNATURE_SEAL).*"))) {
            OcrClient.super.recognizeBatch(inputs, listener); return;
        }
        java.util.HashMap<java.lang.String, cn.bidcheck.ocr.OcrBatch.Input> byId=new java.util.HashMap<String,OcrBatch.Input>();for(cn.bidcheck.ocr.OcrBatch.Input input:inputs)byId.put(input.id(),input);
        transcription.recognizeBatch(inputs, new OcrBatch.Listener() {
            public void onBatch(OcrBatch.Event event)throws IOException { listener.onBatch(event); }
            public void accept(OcrBatch.Item item)throws IOException {
                OcrResult result=item.result();
                if(result!=null&&layout==null&&layoutEnabled()&&result.layout()!=null&&result.layout().frames().size()==1&&!result.layout().textBoxes().isEmpty()) {
                    cn.bidcheck.ocr.OcrBatch.Input input=byId.get(item.id());
                    // Reuse batch text and verified boxes locally; never make a second cloud request.
                    result=assembleGrid(input.image(),result);
                    item=OcrBatch.Item.success(item.id(),result);
                }
                listener.accept(item);
            }
        });
    }
    @Override public OcrResult recognize(byte[] image, String mime) throws IOException {
        if(layout==null)return nativeGrid(image,mime);
        OcrResult result = transcription.recognize(image, mime);
        if (result == null) return null;
        OcrLayout structure;
        try { structure = layout.recognize(image, mime, result.text()); }
        catch (InterruptedIOException interrupted) { throw interrupted; }
        catch (DashScopeLayoutClient.LayoutException controlled) { structure = OcrLayout.unavailable(controlled.getMessage()); }
        catch (IOException | RuntimeException invalid) {
            structure = OcrLayout.unavailable("[LAYOUT_UNAVAILABLE] 结构化识别未完成或来源校验未通过；转录文字保留，请对照原图。");
        }
        return new OcrResult(result.text(), result.provider(), result.model(), result.cached(), structure);
    }
    private OcrResult nativeGrid(byte[] image,String mime)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("结构化识别已中断。");
        OcrResult source=null;String failureCode="NOT_CONFIGURED";
        if(layoutEnabled())try{source=advanced.recognize(image,mime);if(source==null||source.layout()==null||source.layout().frames().size()!=1)throw new IOException();source.layout().validated(source.text());}
        catch(InterruptedIOException interrupted){throw interrupted;}
        catch(IOException|RuntimeException invalid){
            // Exhausted temporary provider failures must reach the page retry state. Calling the same
            // service immediately with text_recognition would bypass the shared retry/rate budget.
            if(invalid instanceof OcrFailure && ((OcrFailure) (invalid)).temporary())throw ((OcrFailure) (invalid));
            source=null;failureCode=invalid instanceof DashScopeAdvancedOcrClient.AdvancedOcrException?((DashScopeAdvancedOcrClient.AdvancedOcrException) (invalid)).code():"INVALID_RESPONSE";
        }
        if(source==null){OcrResult fallback=transcription.recognize(image,mime);if(fallback==null)return null;
            return new OcrResult(fallback.text(),fallback.provider(),fallback.model(),fallback.cached(),OcrLayout.unavailable(layoutEnabled()?"[LAYOUT_NATIVE_OCR_"+failureCode+"] 文字位置识别未完成，保留普通文字转录。":"[LAYOUT_NOT_CONFIGURED] 结构化识别未启用或本地解析程序未配置。"));}
        return assembleGrid(image,source);
    }
    private OcrResult assembleGrid(byte[] image,OcrResult source)throws IOException {
        OcrLayout structure;
        try{
            cn.bidcheck.ocr.PixelGridClient.Detection detected=grids.detect(image,source.layout().frames().get(0));cn.bidcheck.ocr.GridLayoutAssembler assembler=new GridLayoutAssembler();
            cn.bidcheck.ocr.GridLayoutAssembler.Assembly assembly=assembler.assemble(source.layout(),source.text(),detected.grids());
            boolean gaps=!assembly.diagnostics().isEmpty()||assembly.layout().tables().stream().flatMap(t->t.cells().stream()).anyMatch(c->cn.bidcheck.compat.Java8Api.isBlank(c.text()));
            String localFailure="";
            if(gaps&&!detected.grids().isEmpty())try{source=new LocalOcrGapFiller().fill(source,grids.localText(image,source.layout(),detected.grids()),detected.grids());assembly=assembler.assemble(source.layout(),source.text(),detected.grids());}
            catch(InterruptedIOException interrupted){throw interrupted;}
            catch(IOException|RuntimeException invalid){localFailure="[LAYOUT_LOCAL_"+(invalid instanceof PixelGridClient.GridException?((PixelGridClient.GridException) (invalid)).code():"INVALID_RESPONSE")+"] 局部文字补充未完成，保留原始文字位置。";}
            structure=assembly.layout();
            if(!detected.warnings().isEmpty()||!cn.bidcheck.compat.Java8Api.isBlank(localFailure)){
                java.util.ArrayList<java.lang.String> notes=new java.util.ArrayList<>(structure.warnings());for(String warning:detected.warnings())if(notes.size()<31)notes.add("["+warning+"] 部分表格边界未能确定，已校验文字框保留。");if(!cn.bidcheck.compat.Java8Api.isBlank(localFailure)&&notes.size()<32)notes.add(localFailure);
                structure=new OcrLayout(structure.schemaVersion(),"PARTIAL",structure.coordinateSystem(),structure.frames(),structure.textBoxes(),structure.tables(),structure.readingOrder(),notes).validated(source.text());
            }
        }
        catch(InterruptedIOException interrupted){throw interrupted;}
        catch(IOException|RuntimeException invalid){cn.bidcheck.ocr.OcrLayout b=source.layout();String code=invalid instanceof PixelGridClient.GridException?((PixelGridClient.GridException) (invalid)).code():"INVALID_RESPONSE";structure=new OcrLayout(b.schemaVersion(),"PARTIAL",b.coordinateSystem(),b.frames(),b.textBoxes(),cn.bidcheck.compat.Java8Api.listOf(),b.readingOrder(),cn.bidcheck.compat.Java8Api.listOf("[LAYOUT_GRID_"+code+"] 表格单元格定位未完成，文字与已校验文字框保留。")).validated(source.text());}
        return new OcrResult(source.text(),source.provider(),source.model(),source.cached(),structure);
    }
}

package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.document.FileParser;
import cn.bidcheck.ocr.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static cn.bidcheck.verification.source.SourceSafety.map;

/** Requested-page geometry only. The immutable stored transcript is never replaced or re-OCRed. */
@Service
public final class ScanPageLayoutService {
    public static final String VERSION="requested-page-layout-2-compact-once";
    @FunctionalInterface interface Recognition { DashScopeLayoutClient.Recognition locate(byte[] image,String text)throws IOException; }
    private final Recognition recognition;private final boolean enabled;private final String identity;private final int timeoutSeconds;
    @Autowired public ScanPageLayoutService(
            @Value("${bidcheck.verification.scan-layout-enabled:true}") boolean enabled,
            @Value("${bidcheck.ocr.layout.url:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}") String url,
            @Value("${bidcheck.ocr.layout.key:${bidcheck.visual.key:${bidcheck.model.key:}}}") String key,
            @Value("${bidcheck.ocr.key:}") String ocrKey,
            @Value("${bidcheck.ocr.layout.model:qwen3-vl-plus}") String model,
            @Value("${bidcheck.data:./data}") String data,
            @Value("${bidcheck.ocr.layout.concurrency:4}") int concurrency,
            @Value("${bidcheck.verification.scan-layout-timeout-seconds:60}") int timeout,OcrRequestSettings settings){
        String credential=key==null||cn.bidcheck.compat.Java8Api.isBlank(key)?ocrKey:key;
        cn.bidcheck.ocr.DashScopeLayoutClient client=new DashScopeLayoutClient(enabled,url,credential,model,data,concurrency,timeout,oneAttempt(settings));
        this.enabled=client.enabled();this.identity=VERSION+":"+client.identity();this.timeoutSeconds=timeout;this.recognition=(image,text)->client.recognizeDetailed(image,"image/png",text);
        LoggerFactory.getLogger(ScanPageLayoutService.class).info("bidcheck.page_layout_configuration requested={} enabled={} model={} credentialConfigured={} credentialSource={} protocol=DASHSCOPE_NATIVE_MULTIMODAL schema={} timeoutSeconds={} maxAttempts=1",enabled,this.enabled,model,credential!=null&&!cn.bidcheck.compat.Java8Api.isBlank(credential),key!=null&&!cn.bidcheck.compat.Java8Api.isBlank(key)?"OCR_LAYOUT_OR_VISUAL_OR_MODEL":"OCR_FALLBACK",DashScopeLayoutClient.COMPACT_SCHEMA,timeout);
    }
    static OcrRequestSettings oneAttempt(OcrRequestSettings settings){return new OcrRequestSettings(settings.queueCapacity(),1,settings.retryBaseMs(),settings.retryMaxDelayMs(),settings.requestsPerMinute(),settings.burst(),settings.circuitFailures(),settings.circuitOpenMillis());}
    ScanPageLayoutService(Recognition recognition){this.enabled=true;this.identity=VERSION+":local-test";this.timeoutSeconds=60;this.recognition=recognition;}
    int requestTimeoutSeconds(){return timeoutSeconds;}
    public String identity(){return identity;}
    public boolean enabled(){return enabled;}

    /** Caller must have verified the original's SHA256 and stable file attributes. */
    Map<String,Object> extractVerified(Document document,Page page,Path verifiedOriginal,String nativeDiagnostic){
        long started=System.nanoTime();Map<String,Object> audit=new LinkedHashMap<>();
        audit.putAll(map("version",VERSION,"documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),"physicalPage",page.number(),"parser",page.parser(),"textSource","IMMUTABLE_STORED_TRANSCRIPTION","imageSource","VERIFIED_PDF_PHYSICAL_PAGE","nativeDiagnostic",nativeDiagnostic));
        List<String> limitations=new ArrayList<>();List<Map<String,Object>> spans=cn.bidcheck.compat.Java8Api.listOf();String code="";String status="UNAVAILABLE";
        try{
            if(!enabled)code="SCAN_LAYOUT_NOT_CONFIGURED";
            else if(page.text()==null||cn.bidcheck.compat.Java8Api.isBlank(page.text()))code="SCAN_LAYOUT_TRANSCRIPTION_REQUIRED";
            else{
                SourceSafety.interrupted();byte[] image=new FileParser().renderPdfPage(verifiedOriginal,page.number());
                audit.put("imageHash",SourceSafety.digest(image));audit.put("transcriptionHash",SourceSafety.digest(page.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                cn.bidcheck.ocr.DashScopeLayoutClient.Recognition result=recognition.locate(image,page.text());audit.put("cacheHit",result.cached());audit.put("modelCall",result.audit());
                OcrLayout layout=result.layout().source("","PDF_PAGE",page.number()).validated(page.text());status=layout.status();
                if(!"UNAVAILABLE".equals(status)){
                    Page structured=new Page(page.number(),page.text(),page.locationType(),page.parser(),page.warning(),layout);
                    spans=StructuredLayoutAdapter.spans(document,structured,limitations);
                    audit.put("tableCount",layout.tables().size());audit.put("cellCount",layout.tables().stream().mapToInt(t->t.cells().size()).sum());
                    audit.put("mergedCellCount",layout.tables().stream().flatMap(t->t.cells().stream()).filter(c->c.rowSpan()>1||c.columnSpan()>1).count());
                    audit.put("cells",cn.bidcheck.compat.Java8Api.toList(spans.stream().filter(s->"TABLE_CELL".equals(s.get("kind"))).map(s->map("cellId",s.get("cellId"),"tableId",s.get("tableId"),"row",s.get("row"),"column",s.get("column"),"rowSpan",s.get("rowSpan"),"columnSpan",s.get("columnSpan"),"bbox",s.get("bbox"),"headerCellIds",s.get("headerCellIds"),"sourceFragments",s.get("sourceFragments"),"inheritanceBasis","VALIDATED_HEADER_AND_EXPLICIT_CELL_SPAN"))));
                    if(spans.stream().noneMatch(s->"TABLE_CELL".equals(s.get("kind"))))code="SCAN_LAYOUT_NO_SOURCE_BOUND_TABLE";
                }else code="SCAN_LAYOUT_UNAVAILABLE";
            }
        }catch(InterruptedIOException interrupted){Thread.currentThread().interrupt();throw new CancellationException("扫描页结构取证已中断");}
        catch(CancellationException interrupted){throw interrupted;}
        catch(DashScopeLayoutClient.LayoutException failure){code="SCAN_LAYOUT_"+failure.code();audit.put("modelCall",failure.audit());}
        catch(IOException|RuntimeException failure){code="SCAN_LAYOUT_SOURCE_OR_GEOMETRY_REJECTED";audit.put("errorType",failure.getClass().getSimpleName());}
        audit.put("status",status);audit.put("diagnosticCode",code);audit.put("durationMs",(System.nanoTime()-started)/1_000_000);
        if(!cn.bidcheck.compat.Java8Api.isBlank(code))limitations.add(code+"：本页结构未闭合，不能据此判定原件缺少材料。");
        LoggerFactory.getLogger(ScanPageLayoutService.class).info("bidcheck.page_layout documentId={} page={} parser={} imageHash={} status={} diagnosticCode={} cached={} cellCount={} mergedCellCount={} durationMs={} modelCall={}",document.id(),page.number(),page.parser(),audit.get("imageHash"),status,code,audit.get("cacheHit"),audit.get("cellCount"),audit.get("mergedCellCount"),audit.get("durationMs"),audit.get("modelCall"));
        return map("status","COMPLETE","version",VERSION,"documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),"physicalPage",page.number(),"spans",spans,"limitations",limitations,"structureStatus",status,"diagnosticCode",code,"pageLayoutAudit",audit);
    }
}

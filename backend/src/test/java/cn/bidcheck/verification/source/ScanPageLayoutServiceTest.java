package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.*;
import cn.bidcheck.verification.facts.TableStructure;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ScanPageLayoutServiceTest {
    @TempDir Path temporary;
    private static final String TEXT="姓名 陈林\n身份证号码 11010519491231\n002X\n缴费月份 2025年07月";
    @Test void requestedPageHasOneAttemptWithoutMutatingOrdinaryOcrAdmission(){
        var ordinary=new OcrRequestSettings(16,3,100,2000,60,4,5,15000);var scan=ScanPageLayoutService.oneAttempt(ordinary);
        assertEquals(1,scan.retryAttempts());assertEquals(3,ordinary.retryAttempts());assertEquals(ordinary.queueCapacity(),scan.queueCapacity());assertEquals(ordinary.requestsPerMinute(),scan.requestsPerMinute());assertEquals(ordinary.circuitFailures(),scan.circuitFailures());assertEquals(ordinary.circuitOpenMillis(),scan.circuitOpenMillis());
    }
    @Test void scanTimeoutDoesNotInheritOrdinaryLayoutFortyFiveSecondDefault(){
        new ApplicationContextRunner().withBean(OcrRequestSettings.class,OcrRequestSettings::defaults).withBean(ScanPageLayoutService.class)
                .withPropertyValues("bidcheck.ocr.layout.timeout-seconds=45","bidcheck.ocr.layout.key=","bidcheck.ocr.key=")
                .run(context->{assertNull(context.getStartupFailure());assertEquals(60,context.getBean(ScanPageLayoutService.class).requestTimeoutSeconds());});
        new ApplicationContextRunner().withBean(OcrRequestSettings.class,OcrRequestSettings::defaults).withBean(ScanPageLayoutService.class)
                .withPropertyValues("bidcheck.ocr.layout.timeout-seconds=45","bidcheck.verification.scan-layout-timeout-seconds=50","bidcheck.ocr.layout.key=","bidcheck.ocr.key=")
                .run(context->{assertNull(context.getStartupFailure());assertEquals(50,context.getBean(ScanPageLayoutService.class).requestTimeoutSeconds());});
    }
    private OcrLayout structure(byte[] image)throws IOException{
        var bitmap=ImageIO.read(new ByteArrayInputStream(image));String[] values={"姓名","陈林","身份证号码","11010519491231002X","缴费月份","2025年07月"};
        List<OcrLayout.TextBox> boxes=new ArrayList<>();List<OcrLayout.Cell> cells=new ArrayList<>();
        for(int i=0;i<values.length;i++){
            var box=new OcrLayout.Box(i%2*130,i/2*35,120,30);boxes.add(new OcrLayout.TextBox("b"+i,"image",values[i],box,null));
            cells.add(new OcrLayout.Cell("c"+i,i/2,i%2,1,1,values[i],box,List.of("b"+i),i%2==0?List.of():List.of("c"+(i-1)),i%2==0));
        }
        return new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,List.of(new OcrLayout.Frame("image",bitmap.getWidth(),bitmap.getHeight(),SourceSafety.digest(image),"SUBMITTED_IMAGE",0,null,null,null)),boxes,List.of(new OcrLayout.Table("t","image",3,2,cells)),boxes.stream().map(OcrLayout.TextBox::id).toList(),List.of());
    }
    private Path original()throws IOException{Path file=temporary.resolve("scan.pdf");try(PDDocument d=new PDDocument()){d.addPage(new PDPage());d.save(file.toFile());}return file;}
    private Document document(Path file,OcrLayout layout)throws IOException{return new Document("d","p","BID","scan.pdf",1,SourceSafety.digest(Files.readAllBytes(file)),"",List.of(new Page(1,TEXT,"PAGE","PDFBOX_OCR","",layout)),List.of());}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> spans(Map<String,Object> value){return (List<Map<String,Object>>)value.get("spans");}
    private List<Map<String,Object>> source(Document d){return List.of(Map.of("id","s","documentHash",d.sha256(),"documentVersion",1,"evidence",Map.of("documentId","d","documentName","scan.pdf","page",1,"locationType","PAGE","quote",TEXT,"anchor","chars:0-"+TEXT.length())));}
    @Test void requestedScannedPageGetsSourceBoundCellsAndPreservesSplitIdentifier()throws Exception{
        Path file=original();Document d=document(file,null);AtomicInteger calls=new AtomicInteger();
        var scans=new ScanPageLayoutService((image,text)->{calls.incrementAndGet();assertEquals(TEXT,text);return new DashScopeLayoutClient.Recognition(structure(image),false,Map.of("traceId","trace-one","inputAudit",Map.of("status","SAVED","sha256","a".repeat(64))));});
        var lazy=new EvidenceLayoutService(null,scans).documentPageLayouts(d,()->file);
        assertEquals(0,calls.get());var layout=lazy.apply(1);assertEquals(1,calls.get());assertEquals("COMPLETE",layout.get("structureStatus"));
        var cells=TableStructure.validatedCells(spans(layout),source(d));assertEquals(6,cells.size());
        var idCell=cells.stream().filter(c->"11010519491231002X".equals(c.get("text"))).findFirst().orElseThrow();
        assertEquals("KEY_VALUE",idCell.get("tableType"));assertTrue(idCell.get("sourceFragments").toString().contains("11010519491231\n002X"));
        var row=TableStructure.analyze("陈林",List.of(),spans(layout),source(d));assertTrue(row.valid(),row.issue());assertEquals(List.of("2025-07"),row.rows().get(0).months());
        assertEquals(TEXT,d.pages().get(0).text());assertSame(layout,lazy.apply(1));assertEquals(1,calls.get());
        Map<?,?> audit=(Map<?,?>)layout.get("pageLayoutAudit");assertEquals(6,audit.get("cellCount"));assertEquals("trace-one",((Map<?,?>)audit.get("modelCall")).get("traceId"));
    }
    @Test void existingStructuredPageAndWrongOriginalNeverInvokeCloud()throws Exception{
        Path file=original();byte[] image=new cn.bidcheck.document.FileParser().renderPdfPage(file,1);AtomicInteger calls=new AtomicInteger();
        var scans=new ScanPageLayoutService((bytes,text)->{calls.incrementAndGet();throw new AssertionError("unexpected cloud request");});
        Document d=document(file,structure(image));var service=new EvidenceLayoutService(null,scans);assertFalse(spans(service.documentPageLayouts(d,()->file).apply(1)).isEmpty());
        var lazy=service.documentPageLayouts(d,()->file);Files.writeString(file,"changed source");assertTrue(spans(lazy.apply(1)).isEmpty());assertEquals(0,calls.get());
    }
    @Test void nativePreloadNeverFallsBackToCloudWithoutExplicitToolPermission()throws Exception{
        Path file=original();Document d=document(file,null);AtomicInteger calls=new AtomicInteger();
        var scans=new ScanPageLayoutService((image,text)->{calls.incrementAndGet();return new DashScopeLayoutClient.Recognition(structure(image),false,Map.of());});
        var service=new EvidenceLayoutService(null,scans);assertTrue(spans(service.nativePdfLayouts(d,()->file).apply(1)).isEmpty());assertEquals(0,calls.get());
        assertFalse(spans(service.documentPageLayouts(d,()->file,true).apply(1)).isEmpty());assertEquals(1,calls.get());
    }
    @Test void repeatedConcurrentRequestSharesOneLayoutCall()throws Exception{
        Path file=original();Document d=document(file,null);AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var scans=new ScanPageLayoutService((bytes,text)->{calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException();}return new DashScopeLayoutClient.Recognition(structure(bytes),false,Map.of());});
        var lazy=new EvidenceLayoutService(null,scans).documentPageLayouts(d,()->file);
        var pool=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>> a=pool.submit(()->lazy.apply(1));assertTrue(entered.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> b=pool.submit(()->lazy.apply(1));release.countDown();assertSame(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertEquals(1,calls.get());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void changedSourceOffsetCannotAuthorizeAnOtherwiseMatchingCell()throws Exception{
        Path file=original();Document d=document(file,null);byte[] image=new cn.bidcheck.document.FileParser().renderPdfPage(file,1);var page=new Page(1,TEXT,"PAGE","PDFBOX_OCR","",structure(image));
        var spans=new ArrayList<>(StructuredLayoutAdapter.spans(d,page,new ArrayList<>()));var forged=new LinkedHashMap<>(spans.stream().filter(s->"TABLE_CELL".equals(s.get("kind"))&&"陈林".equals(s.get("text"))).findFirst().orElseThrow());
        forged.put("sourceFragments",List.of(Map.of("start",0,"end",2,"text","陈林")));assertTrue(TableStructure.validatedCells(List.of(forged),source(d)).isEmpty());
    }
}

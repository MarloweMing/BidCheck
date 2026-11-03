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
        cn.bidcheck.ocr.OcrRequestSettings ordinary=new OcrRequestSettings(16,3,100,2000,60,4,5,15000);cn.bidcheck.ocr.OcrRequestSettings scan=ScanPageLayoutService.oneAttempt(ordinary);
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
        java.awt.image.BufferedImage bitmap=ImageIO.read(new ByteArrayInputStream(image));String[] values={"姓名","陈林","身份证号码","11010519491231002X","缴费月份","2025年07月"};
        List<OcrLayout.TextBox> boxes=new ArrayList<>();List<OcrLayout.Cell> cells=new ArrayList<>();
        for(int i=0;i<values.length;i++){
            cn.bidcheck.ocr.OcrLayout.Box box=new OcrLayout.Box(i%2*130,i/2*35,120,30);boxes.add(new OcrLayout.TextBox("b"+i,"image",values[i],box,null));
            cells.add(new OcrLayout.Cell("c"+i,i/2,i%2,1,1,values[i],box,cn.bidcheck.compat.Java8Api.listOf("b"+i),i%2==0?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf("c"+(i-1)),i%2==0));
        }
        return new OcrLayout(OcrLayout.VERSION,"COMPLETE",OcrLayout.COORDINATES,cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.Frame("image",bitmap.getWidth(),bitmap.getHeight(),SourceSafety.digest(image),"SUBMITTED_IMAGE",0,null,null,null)),boxes,cn.bidcheck.compat.Java8Api.listOf(new OcrLayout.Table("t","image",3,2,cells)),cn.bidcheck.compat.Java8Api.toList(boxes.stream().map(OcrLayout.TextBox::id)),cn.bidcheck.compat.Java8Api.listOf());
    }
    private Path original()throws IOException{Path file=temporary.resolve("scan.pdf");try(PDDocument d=new PDDocument()){d.addPage(new PDPage());d.save(file.toFile());}return file;}
    private Document document(Path file,OcrLayout layout)throws IOException{return new Document("d","p","BID","scan.pdf",1,SourceSafety.digest(Files.readAllBytes(file)),"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,TEXT,"PAGE","PDFBOX_OCR","",layout)),cn.bidcheck.compat.Java8Api.listOf());}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> spans(Map<String,Object> value){return (List<Map<String,Object>>)value.get("spans");}
    private List<Map<String,Object>> source(Document d){return cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "s", "documentHash", d.sha256(), "documentVersion", 1, "evidence", cn.bidcheck.compat.Java8Api.mapOf("documentId", "d", "documentName", "scan.pdf", "page", 1, "locationType", "PAGE", "quote", TEXT, "anchor", "chars:0-"+TEXT.length())));}
    @Test void requestedScannedPageGetsSourceBoundCellsAndPreservesSplitIdentifier()throws Exception{
        Path file=original();Document d=document(file,null);AtomicInteger calls=new AtomicInteger();
        cn.bidcheck.verification.source.ScanPageLayoutService scans=new ScanPageLayoutService((image,text)->{calls.incrementAndGet();assertEquals(TEXT,text);return new DashScopeLayoutClient.Recognition(structure(image),false,cn.bidcheck.compat.Java8Api.mapOf("traceId", "trace-one", "inputAudit", cn.bidcheck.compat.Java8Api.mapOf("status", "SAVED", "sha256", cn.bidcheck.compat.Java8Api.repeat("a", 64))));});
        java.util.function.Function<java.lang.Integer, java.util.Map<java.lang.String, java.lang.Object>> lazy=new EvidenceLayoutService(null,scans).documentPageLayouts(d,()->file);
        assertEquals(0,calls.get());java.util.Map<java.lang.String, java.lang.Object> layout=lazy.apply(1);assertEquals(1,calls.get());assertEquals("COMPLETE",layout.get("structureStatus"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> cells=TableStructure.validatedCells(spans(layout),source(d));assertEquals(6,cells.size());
        java.util.Map<java.lang.String, java.lang.Object> idCell=cells.stream().filter(c->"11010519491231002X".equals(c.get("text"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("KEY_VALUE",idCell.get("tableType"));assertTrue(idCell.get("sourceFragments").toString().contains("11010519491231\n002X"));
        cn.bidcheck.verification.facts.TableStructure.Result row=TableStructure.analyze("陈林",cn.bidcheck.compat.Java8Api.listOf(),spans(layout),source(d));assertTrue(row.valid(),row.issue());assertEquals(cn.bidcheck.compat.Java8Api.listOf("2025-07"),row.rows().get(0).months());
        assertEquals(TEXT,d.pages().get(0).text());assertSame(layout,lazy.apply(1));assertEquals(1,calls.get());
        Map<?,?> audit=(Map<?,?>)layout.get("pageLayoutAudit");assertEquals(6,audit.get("cellCount"));assertEquals("trace-one",((Map<?,?>)audit.get("modelCall")).get("traceId"));
    }
    @Test void existingStructuredPageAndWrongOriginalNeverInvokeCloud()throws Exception{
        Path file=original();byte[] image=new cn.bidcheck.document.FileParser().renderPdfPage(file,1);AtomicInteger calls=new AtomicInteger();
        cn.bidcheck.verification.source.ScanPageLayoutService scans=new ScanPageLayoutService((bytes,text)->{calls.incrementAndGet();throw new AssertionError("unexpected cloud request");});
        Document d=document(file,structure(image));cn.bidcheck.verification.source.EvidenceLayoutService service=new EvidenceLayoutService(null,scans);assertFalse(spans(service.documentPageLayouts(d,()->file).apply(1)).isEmpty());
        java.util.function.Function<java.lang.Integer, java.util.Map<java.lang.String, java.lang.Object>> lazy=service.documentPageLayouts(d,()->file);cn.bidcheck.compat.Java8Api.writeString(file, "changed source");assertTrue(spans(lazy.apply(1)).isEmpty());assertEquals(0,calls.get());
    }
    @Test void nativePreloadNeverFallsBackToCloudWithoutExplicitToolPermission()throws Exception{
        Path file=original();Document d=document(file,null);AtomicInteger calls=new AtomicInteger();
        cn.bidcheck.verification.source.ScanPageLayoutService scans=new ScanPageLayoutService((image,text)->{calls.incrementAndGet();return new DashScopeLayoutClient.Recognition(structure(image),false,cn.bidcheck.compat.Java8Api.mapOf());});
        cn.bidcheck.verification.source.EvidenceLayoutService service=new EvidenceLayoutService(null,scans);assertTrue(spans(service.nativePdfLayouts(d,()->file).apply(1)).isEmpty());assertEquals(0,calls.get());
        assertFalse(spans(service.documentPageLayouts(d,()->file,true).apply(1)).isEmpty());assertEquals(1,calls.get());
    }
    @Test void repeatedConcurrentRequestSharesOneLayoutCall()throws Exception{
        Path file=original();Document d=document(file,null);AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        cn.bidcheck.verification.source.ScanPageLayoutService scans=new ScanPageLayoutService((bytes,text)->{calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException();}return new DashScopeLayoutClient.Recognition(structure(bytes),false,cn.bidcheck.compat.Java8Api.mapOf());});
        java.util.function.Function<java.lang.Integer, java.util.Map<java.lang.String, java.lang.Object>> lazy=new EvidenceLayoutService(null,scans).documentPageLayouts(d,()->file);
        java.util.concurrent.ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>> a=pool.submit(()->lazy.apply(1));assertTrue(entered.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> b=pool.submit(()->lazy.apply(1));release.countDown();assertSame(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertEquals(1,calls.get());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void changedSourceOffsetCannotAuthorizeAnOtherwiseMatchingCell()throws Exception{
        Path file=original();Document d=document(file,null);byte[] image=new cn.bidcheck.document.FileParser().renderPdfPage(file,1);cn.bidcheck.domain.Domain.Page page=new Page(1,TEXT,"PAGE","PDFBOX_OCR","",structure(image));
        java.util.ArrayList<java.util.Map<java.lang.String, java.lang.Object>> spans=new ArrayList<>(StructuredLayoutAdapter.spans(d,page,new ArrayList<>()));java.util.LinkedHashMap<java.lang.String, java.lang.Object> forged=new LinkedHashMap<>(spans.stream().filter(s->"TABLE_CELL".equals(s.get("kind"))&&"陈林".equals(s.get("text"))).findFirst().orElseThrow(java.util.NoSuchElementException::new));
        forged.put("sourceFragments",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("start", 0, "end", 2, "text", "陈林")));assertTrue(TableStructure.validatedCells(cn.bidcheck.compat.Java8Api.listOf(forged),source(d)).isEmpty());
    }
}

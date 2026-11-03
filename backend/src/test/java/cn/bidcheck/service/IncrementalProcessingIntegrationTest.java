package cn.bidcheck.service;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.document.*;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.VisualVerificationService;
import cn.bidcheck.verification.source.StructuredContextFacts;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:incremental-processing;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000","bidcheck.visual.enabled=false"})
class IncrementalProcessingIntegrationTest {
    @Autowired Store store;
    @Autowired PlatformTransactionManager manager;
    @Autowired FactLedgerService ledger;
    @Autowired BidService service;
    @TempDir Path temporary;
    Document metadata(String id,String project,String hash){return new Document(id,project,"BID","source.pdf",1,hash,"2026-10-14T00:00:00Z",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());}
    Checkpoint check(String id,String type,String field,String expected){return new Checkpoint(id,"投标有效期","响应检查","HIGH",type,field,">=",expected,"天",true,true,true,null);}
    DocumentProcessingSession session(Document d,List<Checkpoint> checks,Runnable fence){
        store.save("project",d.projectId(),d.projectId(),"",cn.bidcheck.compat.Java8Api.mapOf("id", d.projectId()));
        return new DocumentProcessingSession(store,new TransactionTemplate(manager),fence,OcrUnitObserver.noop(),ledger,d,Store.id(),"processing-test",checks);
    }
    @Test void durableFactsAndRiskExistBeforeTheSecondOcrResponseAndBeforeDocumentPublication()throws Exception {
        Path path=temporary.resolve("two.pdf");
        try(PDDocument pdf=new PDDocument()){
            for(int i=0;i<2;i++){
                PDPage p=new PDPage();pdf.addPage(p);BufferedImage image=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,100,100);g.setColor(java.awt.Color.BLACK);g.drawString("scan "+i,10,40);g.dispose();
                try(PDPageContentStream c=new PDPageContentStream(pdf,p)){c.drawImage(LosslessFactory.createFromImage(pdf,image),40,300,450,450);}image.flush();
            }pdf.save(path.toFile());
        }
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        Document d=metadata(Store.id(),Store.id(),hash);Checkpoint cp=check(Store.id(),"VALUE_COMPARE","bidValidity","90");
        CountDownLatch firstDurable=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();
        OcrClient ocr=new OcrClient(){
            public boolean enabled(){return true;}
            public String modelName(){return "incremental-test";}
            public String identity(){return "incremental-test";}
            public OcrResult recognize(byte[] bytes,String mime)throws IOException{
                int call=calls.incrementAndGet();if(call==2){try{
                    assertTrue(firstDurable.await(10,TimeUnit.SECONDS),"First-page progress must commit before the last OCR call returns");
                    assertNull(store.mapper().get(d.id()),"The complete document must not be published early");
                    java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));assertEquals(false,summary.get("parsingComplete"));
                    assertTrue(((Number)summary.get("factCount")).intValue()>0);
                    assertEquals("REVIEW_REQUIRED",store.maps("incremental_check",d.projectId(),d.id()).get(0).get("state"));
                }catch(Throwable failure){error.set(failure);throw new IOException(failure);}}
                return new OcrResult(call==1?"投标有效期：30天":"项目编号：A001","TEST","incremental-test",false);
            }
        };
        cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(cp),()->{});
        OcrUnitObserver observer=new OcrUnitObserver(){public void onPage(Page p,PageClassifier.Decision r)throws IOException{session.onPage(p,r);firstDurable.countDown();}};
        cn.bidcheck.document.ExtractionResult parsed=new FileParser(ocr,10,1).parsePlanned(path,"two.pdf",null,ExtractionPlan.full(),observer);
        if(error.get()!=null)throw new AssertionError(error.get());
        assertEquals(2,parsed.document().pages().size());assertEquals(2,calls.get());
        assertEquals(2,store.maps("processing_page",d.projectId(),d.id()).size());
        assertFalse(ledger.documentView(session.current(),"processing-test").isEmpty());
    }
    @Test void retryIsIdempotentAndChangedPageRetractsEarlierWarningWithoutCertifyingPass()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("a", 64));Checkpoint cp=check(Store.id(),"VALUE_COMPARE","bidValidity","90");
        cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(cp),()->{});Page bad=new Page(1,"投标有效期：30天","PAGE","NATIVE",""),good=new Page(1,"投标有效期：120天","PAGE","NATIVE","");
        session.onPage(bad,PageClassifier.nativeOnly(bad.text(),0));session.onPage(bad,PageClassifier.nativeOnly(bad.text(),0));
        assertEquals(1,store.maps("processing_page",d.projectId(),d.id()).size());
        assertEquals("REVIEW_REQUIRED",store.maps("incremental_check",d.projectId(),d.id()).get(0).get("state"));
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(cp),()->{});resumed.onPage(good,PageClassifier.nativeOnly(good.text(),0));
        java.util.Map<java.lang.String, java.lang.Object> result=store.maps("incremental_check",d.projectId(),d.id()).get(0);
        assertEquals("EVIDENCE_FOUND",result.get("state"));assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals(true,result.get("provisional"));
        assertFalse(store.encode(ledger.documentView(resumed.current(),"processing-test")).contains("30天"));
    }
    @Test void leaseLossRollsBackPageAndFactsTogether()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("b", 64));cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{throw new IllegalStateException("LEASE_LOST");});
        Page page=new Page(1,"项目编号：A001","PAGE","NATIVE","");
        assertThrows(IllegalStateException.class,()->session.onPage(page,PageClassifier.nativeOnly(page.text(),0)));
        assertTrue(store.maps("processing_page",d.projectId(),d.id()).isEmpty());
        assertTrue(store.maps("incremental_check",d.projectId(),d.id()).isEmpty());
    }
    @Test void missingMaterialAndGlobalAgreementRemainWaitingOnPartialInput(){
        cn.bidcheck.service.IncrementalCheckEngine engine=new IncrementalCheckEngine();Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("c", 64));
        Checkpoint cp=check(Store.id(),"MATERIAL_EXISTENCE","material","营业执照");
        java.util.Map<java.lang.String, java.lang.Object> result=engine.evaluate(cp,d,cn.bidcheck.compat.Java8Api.listOf(),false);
        assertEquals("WAITING_EVIDENCE",result.get("state"));assertEquals("UNDETERMINED",result.get("businessDecision"));
    }
    @Test void partialVisualWindowSurvivesRestartAndRetriesOnlyTheFailedPage()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("d", 64));List<Page> pages=new ArrayList<>();
        for(int i=1;i<=3;i++)pages.add(new Page(i,"合同编号：ABCD-001\n合同协议\n第"+i+"条 服务内容","PAGE","QWEN_PDF_OCR",""));
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf());
        List<List<Integer>> requested=new CopyOnWriteArrayList<>();AtomicBoolean failSingle=new AtomicBoolean(true);
        VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "context-restart-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());requested.add(input);
                if(input.size()==3)return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_WINDOW_PARTIAL", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "status", "COMPLETED"), cn.bidcheck.compat.Java8Api.mapOf("page", 2, "status", "COMPLETED")));
                if(failSingle.get())return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_TIMEOUT", "pageResults", cn.bidcheck.compat.Java8Api.listOf());
                return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 3, "status", "COMPLETED")));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:pages)first.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        first.finish(full,temporary.resolve("unused.pdf"),visual,8);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3)),requested);
        assertEquals("PARTIAL_FAILED",store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("status"));
        failSingle.set(false);cn.bidcheck.service.DocumentProcessingSession second=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});second.finish(full,temporary.resolve("unused.pdf"),visual,8);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(3), cn.bidcheck.compat.Java8Api.listOf(3)),requested);
        assertEquals("REVIEW_REQUIRED",store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("status"),"A completed call with no usable facts remains reviewable");
        session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,8);assertEquals(3,requested.size());
    }
    @Test void repeatedTranscriptionOfOneVehiclePageIsNotASecondIndependentProof(){
        Evidence wide=new Evidence("doc","bid.pdf",1,"PAGE","车架号：LSVA1234567890123 车牌号：渝A12345","line:1",1);
        Evidence narrow=new Evidence("doc","bid.pdf",1,"PAGE","车牌号：渝A12345","chars:25-38",0);
        List<Fact> values=cn.bidcheck.compat.Java8Api.listOf(new Fact("VEHICLE","VIN:LSVA1234567890123","plateNumber","渝A12345","渝A12345",wide), new Fact("VEHICLE","VIN:LSVA1234567890123","plateNumber","渝A12345","渝A12345",narrow));
        assertEquals(1,IncrementalCheckEngine.distinctEvidence(values).size());
    }
    @Test void fullPlanBatchesIndependentMaterialsDerivesBudgetAndDoesNotCallSuccessfulPagesAgain()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("8", 64));List<Page> pages=new ArrayList<>();
        for(int i=1;i<=9;i++)pages.add(new Page(i,"合同编号：INDEPENDENT-"+i+"\n合同协议\n签订日期：2025年1月1日","PAGE","QWEN_PDF_OCR",""));
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf());
        List<List<Integer>> requests=new CopyOnWriteArrayList<>();
        VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "independent-plan-test";}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());requests.add(input);
                return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_NO_GROUNDED_OBSERVATIONS", "pageResults", cn.bidcheck.compat.Java8Api.toList(input.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED"))));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page page:pages)processing.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        processing.finish(full,temporary.resolve("unused.pdf"),visual,1,true,32);
        assertEquals(3,requests.size());assertEquals(9,requests.stream().flatMap(List::stream).distinct().count());assertTrue(requests.stream().allMatch(p->p.size()==3));
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals(3,((Number)summary.get("visualCalls")).intValue());assertEquals(0,((Number)summary.get("contextPending")).intValue());
        assertEquals(0,((Number)summary.get("contextCompleted")).intValue());assertEquals(3,((Number)summary.get("contextNoFacts")).intValue());assertEquals("REVIEW_REQUIRED",summary.get("status"));
        assertEquals(false,object(summary.get("visualBudget")).get("limited"));
        session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,1,true,32);assertEquals(3,requests.size());
    }
    @Test void fullPlanRepairsOnlyFailedSingletonOnceAndRetainsHardBudgetGaps()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("9", 64));Page page=new Page(1,"合同编号：RECOVER-001\n合同协议","PAGE","QWEN_PDF_OCR","");
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),cn.bidcheck.compat.Java8Api.listOf(page),cn.bidcheck.compat.Java8Api.listOf());AtomicInteger requests=new AtomicInteger();
        VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "single-repair-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                if(!permit.getAsBoolean())return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_BUDGET_EXHAUSTED");
                requests.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_TIMEOUT");
            }
        };
        cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});processing.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        processing.finish(full,temporary.resolve("unused.pdf"),visual,1,true,2);
        assertEquals(2,requests.get());java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals("PARTIAL_FAILED",summary.get("status"));assertEquals(1,((Number)summary.get("contextPending")).intValue());
        cn.bidcheck.service.DocumentProcessingSession next=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});next.finish(full,temporary.resolve("unused.pdf"),visual,1,true,1);
        assertEquals(3,requests.get());assertEquals(true,object(store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("visualBudget")).get("limited"));
    }
    @Test void fullPlanDefersEveryNewWindowForOneTextGapThenResumesWithoutChangingSourceScope()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("a3", 32));List<Page> pages=new ArrayList<>(guardPages(6,""));
        pages.add(new Page(7,"","PAGE","OCR_DEFERRED","[OCR未完成][OCR_DEFERRED] 原页待识别"));
        List<List<Integer>> requests=new CopyOnWriteArrayList<>();cn.bidcheck.verification.source.VisualVerificationService visual=guardVisual(requests,false);
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:pages)first.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        first.finish(first.current(),temporary.resolve("unused.pdf"),visual,1,true,32);
        java.util.Map<java.lang.String, java.lang.Object> before=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertTrue(requests.isEmpty());assertEquals(true,before.get("visualDeferredForOcr"));assertEquals("DOCUMENT_OCR_INCOMPLETE",before.get("visualDeferralReason"));
        assertEquals(1,((Number)before.get("incompletePages")).intValue());assertEquals(2,((Number)before.get("contextPending")).intValue());
        assertEquals(0,((Number)object(before.get("visualBudget")).get("plannedJobs")).intValue());assertEquals("PARTIAL_FAILED",before.get("status"));
        assertTrue(store.maps("processing_window",d.projectId(),d.id()).stream().allMatch(w->"WAITING_OCR".equals(w.get("status"))&&"DOCUMENT_OCR_INCOMPLETE".equals(w.get("deferralReason"))));
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});Page fixed=new Page(7,"一般说明内容","PAGE","NATIVE","");resumed.onPage(fixed,PageClassifier.nativeOnly(fixed.text(),0));
        resumed.finish(resumed.current(),temporary.resolve("unused.pdf"),visual,1,true,32);
        java.util.Map<java.lang.String, java.lang.Object> after=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals(before.get("scope"),after.get("scope"));assertEquals("document-processing-4",after.get("version"));
        assertEquals(2,requests.size());assertEquals(6,requests.stream().flatMap(List::stream).distinct().count());
        assertEquals(false,after.get("visualDeferredForOcr"));assertEquals("",after.get("visualDeferralReason"));assertEquals(0,((Number)after.get("contextPending")).intValue());
    }
    @Test void fullPlanLayoutOnlyWarningsDoNotBlockVisualScheduling()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("b3", 32));List<List<Integer>> requests=new CopyOnWriteArrayList<>();
        cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:guardPages(3,"[OCR已识别][OCR结构未完成] 缺少可靠像素坐标"))processing.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        processing.finish(processing.current(),temporary.resolve("unused.pdf"),guardVisual(requests,false),1,true,16);
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3)),requests);assertEquals(false,summary.get("visualDeferredForOcr"));
        assertEquals(0,((Number)summary.get("incompletePages")).intValue());assertEquals(0,((Number)summary.get("contextPending")).intValue());
        assertTrue(processing.current().pages().stream().allMatch(p->p.warning().contains("[OCR结构未完成]")),"Visual facts cannot clear unverified OCR layout warnings");
    }
    @Test void fullPlanTextGapPreservesSuccessfulWindowsAndTheirFactReceipts()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("c3", 32));List<List<Integer>> requests=new CopyOnWriteArrayList<>();cn.bidcheck.verification.source.VisualVerificationService visual=guardVisual(requests,false);
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:guardPages(3,""))first.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        first.finish(first.current(),temporary.resolve("unused.pdf"),visual,1,true,16);
        java.util.Map<java.lang.String, java.lang.Object> success=store.maps("processing_window",d.projectId(),d.id()).get(0);assertEquals("SUCCEEDED",success.get("status"));
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:guardPages(6,""))resumed.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        Page missing=new Page(7,"","PAGE","OCR_DEFERRED","[OCR未完成] 原页待识别");resumed.onPage(missing,PageClassifier.nativeOnly(missing.text(),1));
        resumed.finish(resumed.current(),temporary.resolve("unused.pdf"),visual,1,true,32);
        assertEquals(1,requests.size());java.util.List<java.util.Map<java.lang.String, java.lang.Object>> windows=store.maps("processing_window",d.projectId(),d.id());
        assertEquals(success,windows.stream().filter(w->success.get("windowKey").equals(w.get("windowKey"))).findFirst().orElseThrow(java.util.NoSuchElementException::new));
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals(1,((Number)summary.get("contextCompleted")).intValue());assertEquals(1,((Number)summary.get("contextPending")).intValue());
        assertEquals(true,summary.get("visualDeferredForOcr"));
    }
    @Test void fullPlanTextGapKeepsPartialWindowResponseAndAcceptedFactsWithoutNewCalls()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("d3", 32));List<List<Integer>> requests=new CopyOnWriteArrayList<>();cn.bidcheck.verification.source.VisualVerificationService visual=guardVisual(requests,true);
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:guardPages(3,""))first.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        first.finish(first.current(),temporary.resolve("unused.pdf"),visual,1,true,1);
        java.util.Map<java.lang.String, java.lang.Object> partial=store.maps("processing_window",d.projectId(),d.id()).stream().filter(w->cn.bidcheck.compat.Java8Api.listOf(1, 2, 3).equals(w.get("pages"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),object(partial.get("factResult")).get("acceptedSourcePages"));assertEquals(1,requests.size());
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});Page missing=new Page(4,"","PAGE","OCR_DEFERRED","[OCR未完成] 原页待识别");resumed.onPage(missing,PageClassifier.nativeOnly(missing.text(),1));
        resumed.finish(resumed.current(),temporary.resolve("unused.pdf"),visual,1,true,16);
        java.util.Map<java.lang.String, java.lang.Object> waiting=store.maps("processing_window",d.projectId(),d.id()).stream().filter(w->partial.get("windowKey").equals(w.get("windowKey"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals(1,requests.size());assertEquals("WAITING_OCR",waiting.get("status"));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("response", "factResult", "noFactPages", "noFactPageCount", "reviewRequired"))assertEquals(partial.get(key),waiting.get(key),key);
        java.util.List<?> occurrences=(List<?>)ledger.documentView(resumed.current(),"processing-test").get("occurrences");
        assertTrue(occurrences.stream().map(IncrementalProcessingIntegrationTest::object).anyMatch(o->"CONTEXT_FACT".equals(o.get("sourceDomain"))));
    }
    @Test void independentNoncontiguousEmptyResponsesUsePlanModeAndRemainHonestAboutCompletion()throws Exception{
        for(String code:cn.bidcheck.compat.Java8Api.listOf("VISUAL_CAPACITY_TIMEOUT", "VISUAL_NO_GROUNDED_OBSERVATIONS")){
            Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("e3", 32));cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
            for(Page p:cn.bidcheck.compat.Java8Api.toList(guardPages(5,"").stream().filter(p->p.number()%2==1)))processing.onPage(p,PageClassifier.nativeOnly(p.text(),1));
            boolean noFacts=code.equals("VISUAL_NO_GROUNDED_OBSERVATIONS");
            cn.bidcheck.verification.source.VisualVerificationService visual=emptyIndependentVisual(code,noFacts);
            assertDoesNotThrow(()->processing.finish(processing.current(),temporary.resolve("unused.pdf"),visual,1,true,16));
            java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
            java.util.Map<java.lang.String, java.lang.Object> window=store.maps("processing_window",d.projectId(),d.id()).get(0);
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 3, 5),window.get("pages"));assertEquals(true,window.get("independentPages"));
            assertEquals(noFacts?"NO_FACTS":"VISUAL_CAPACITY_TIMEOUT",window.get("status"));
            assertEquals(noFacts?0:1,((Number)summary.get("contextPending")).intValue());
            assertEquals(noFacts?3:0,((Number)summary.get("contextNoFactPageCount")).intValue());
            assertTrue(store.maps("page_fact_window",d.projectId(),null).isEmpty(),"Empty/failure metadata must not replace ledger indexes");
        }
    }
    @Test void independentPlanModeDoesNotDependOnAnAdapterResponseFlag()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("f3", 32));cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});List<List<Integer>> requests=new ArrayList<>();
        for(Page p:cn.bidcheck.compat.Java8Api.toList(guardPages(5,"").stream().filter(p->p.number()%2==1)))processing.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        cn.bidcheck.verification.source.VisualVerificationService delegate=guardVisual(requests,false);
        cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return delegate.identity();}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                java.util.Map<java.lang.String, java.lang.Object> response=delegate.extractIndependentPages(doc,path,input,permit,scope);response.remove("independentPages");return response;
            }
        };
        processing.finish(processing.current(),temporary.resolve("unused.pdf"),visual,1,true,16);
        java.util.Map<java.lang.String, java.lang.Object> window=store.maps("processing_window",d.projectId(),d.id()).get(0);
        assertEquals("SUCCEEDED",window.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 3, 5),object(window.get("factResult")).get("acceptedSourcePages"));
        assertEquals(3,store.maps("page_fact_window",d.projectId(),null).size());
    }
    @Test void replayedEmptyOrFailedIndependentResponseKeepsExistingLedgerFactsAndReviewState()throws Exception{
        for(String code:cn.bidcheck.compat.Java8Api.listOf("VISUAL_CAPACITY_TIMEOUT", "VISUAL_NO_GROUNDED_OBSERVATIONS")){
            Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("a4", 32));cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
            for(Page p:cn.bidcheck.compat.Java8Api.toList(guardPages(5,"").stream().filter(p->p.number()%2==1)))first.onPage(p,PageClassifier.nativeOnly(p.text(),1));
            first.finish(first.current(),temporary.resolve("unused.pdf"),guardVisual(new ArrayList<>(),false),1,true,16);
            java.util.Map<java.lang.String, java.lang.Object> saved=store.maps("processing_window",d.projectId(),d.id()).get(0);java.util.List<java.util.Map<java.lang.String, java.lang.Object>> indexes=store.maps("page_fact_window",d.projectId(),null);
            // A durable partial receipt may outlive its response/completion envelope after an interrupted older attempt.
            Map<String,Object> partial=new LinkedHashMap<>(saved);partial.put("status","VISUAL_WINDOW_PARTIAL");partial.put("response",cn.bidcheck.compat.Java8Api.mapOf());
            store.save("processing_window",BidService.hash("processing-window:"+saved.get("windowKey")),d.projectId(),d.id(),partial);
            boolean noFacts=code.equals("VISUAL_NO_GROUNDED_OBSERVATIONS");cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
            resumed.finish(resumed.current(),temporary.resolve("unused.pdf"),emptyIndependentVisual(code,noFacts),1,true,16);
            java.util.Map<java.lang.String, java.lang.Object> after=store.maps("processing_window",d.projectId(),d.id()).get(0);
            assertEquals(noFacts?"REVIEW_REQUIRED":"VISUAL_CAPACITY_TIMEOUT",after.get("status"));
            assertEquals(saved.get("factResult"),after.get("factResult"));assertEquals(indexes,store.maps("page_fact_window",d.projectId(),null));
            java.util.List<?> occurrences=(List<?>)ledger.documentView(resumed.current(),"processing-test").get("occurrences");
            assertEquals(3,occurrences.stream().map(IncrementalProcessingIntegrationTest::object).filter(o->"CONTEXT_FACT".equals(o.get("sourceDomain"))).count());
        }
    }
    @Test void mixedIndependentBatchPreservesEmptyPagesAsReviewableWithoutRetryingThem()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("c2", 32));List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(new Page(1,"合同编号：MIXED-001\n合同协议\n合同金额：100元","PAGE","QWEN_PDF_OCR",""), new Page(2,"合同编号：MIXED-002\n合同协议","PAGE","QWEN_PDF_OCR",""), new Page(3,"合同编号：MIXED-003\n合同协议","PAGE","QWEN_PDF_OCR",""));
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf());AtomicInteger requests=new AtomicInteger();
        VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "mixed-independent-test";}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3),input);requests.incrementAndGet();
                Map<String,Object> field=cn.bidcheck.compat.Java8Api.mapOf("sourcePage", 1, "type", "FIELD", "label", "合同金额", "value", "100元", "sourceQuote", "合同金额：100元", "readability", "CLEAR", "source", cn.bidcheck.compat.Java8Api.mapOf("documentId", doc.id(), "projectId", doc.projectId(), "documentHash", doc.sha256(), "documentVersion", doc.version(), "page", 1));
                Map<String,Object> response=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "independentPages", true, "observations", cn.bidcheck.compat.Java8Api.listOf(field), "pageResults", cn.bidcheck.compat.Java8Api.toList(input.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED", "observations", p==1?cn.bidcheck.compat.Java8Api.listOf(field):cn.bidcheck.compat.Java8Api.listOf())))));
                response.put("factBatch",StructuredContextFacts.batch(doc,input,response,identity()));return response;
            }
        };
        cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page page:pages)processing.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        processing.finish(full,temporary.resolve("unused.pdf"),visual,1,true,16);
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals(1,requests.get(),"Legal empty page observations must not trigger repairs");assertEquals("REVIEW_REQUIRED",summary.get("status"));
        assertEquals(0,((Number)summary.get("contextCompleted")).intValue());assertEquals(0,((Number)summary.get("contextPending")).intValue());
        assertEquals(1,((Number)summary.get("contextNoFacts")).intValue());assertEquals(2,((Number)summary.get("contextNoFactPageCount")).intValue());assertEquals(cn.bidcheck.compat.Java8Api.listOf(2, 3),summary.get("contextNoFactPages"));
        Map<String,Object> window=store.maps("processing_window",d.projectId(),d.id()).get(0);
        assertEquals("REVIEW_REQUIRED",window.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(2, 3),window.get("noFactPages"));
        assertFalse(((List<?>)object(window.get("factResult")).get("occurrenceIds")).isEmpty(),"The first page's grounded fact remains saved even if it is not eligible for deterministic comparison");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1),object(window.get("factResult")).get("acceptedSourcePages"));
        session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,1,true,16);assertEquals(1,requests.get());
    }
    @Test void noncontiguousIndependentPagesArePersistedSeparatelyWithoutInventingRelations()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("d2", 32));List<Page> pages=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Api.listOf(1, 3, 5).stream()
            .map(p->new Page(p,"合同编号：SEPARATE-"+p+"\n合同协议","PAGE","QWEN_PDF_OCR","")));
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf());AtomicInteger requests=new AtomicInteger();
        VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "noncontiguous-independent-test";}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 3, 5),input);requests.incrementAndGet();
                List<Map<String,Object>> fields=cn.bidcheck.compat.Java8Api.toList(input.stream().map(p->cn.bidcheck.compat.Java8Api.<String, Object>mapOf("sourcePage", p, "type", "FIELD", "label", "合同编号", "value", "SEPARATE-"+p, "sourceQuote", "合同编号：SEPARATE-"+p, "readability", "CLEAR", "source", cn.bidcheck.compat.Java8Api.mapOf("documentId", doc.id(), "projectId", doc.projectId(), "documentHash", doc.sha256(), "documentVersion", doc.version(), "page", p))));
                Map<String,Object> response=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "independentPages", true, "observations", fields, "pageResults", cn.bidcheck.compat.Java8Api.toList(input.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED", "observations", cn.bidcheck.compat.Java8Api.toList(fields.stream().filter(f->f.get("sourcePage").equals(p))))))));
                response.put("factBatch",StructuredContextFacts.batch(doc,input,response,identity()));return response;
            }
        };
        cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page page:pages)processing.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        processing.finish(full,temporary.resolve("unused.pdf"),visual,1,true,16);
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals(1,requests.get());assertEquals(1,((Number)summary.get("contextCompleted")).intValue());assertEquals(0,((Number)summary.get("contextNoFacts")).intValue());
        java.util.Map<java.lang.String, java.lang.Object> window=store.maps("processing_window",d.projectId(),d.id()).get(0);assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 3, 5),object(window.get("factResult")).get("acceptedSourcePages"));
        assertEquals(3,store.maps("page_fact_window",d.projectId(),null).size(),"Independent batch keeps three single-page ledger windows");
        List<Map<String,Object>> occurrences=cn.bidcheck.compat.Java8Api.toList(((List<?>)ledger.documentView(full,"processing-test").get("occurrences")).stream().map(IncrementalProcessingIntegrationTest::object)
            .filter(o->"CONTEXT_FACT".equals(o.get("sourceDomain"))));
        assertEquals(3,occurrences.size());
        for(Map<String,Object> occurrence:occurrences){
            java.util.List<?> sources=(List<?>)occurrence.get("sources");assertEquals(1,sources.size());int page=((Number)object(object(sources.get(0)).get("evidence")).get("page")).intValue();
            java.util.Map<java.lang.String, java.lang.Object> attributes=object(object(occurrence.get("fact")).get("attributes"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(page),attributes.get("contextWindowPages"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),attributes.get("contextRelationIds"));
        }
    }
    @Test void addingOrChangingCheckpointsReusesSourceWindowsAndReevaluatesPersistedFacts()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("e2", 32));Page page=new Page(1,"合同编号：REUSE-001\n合同协议\n投标有效期：30天","PAGE","QWEN_PDF_OCR","");
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),cn.bidcheck.compat.Java8Api.listOf(page),cn.bidcheck.compat.Java8Api.listOf());AtomicInteger requests=new AtomicInteger();
        cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "checkpoint-independent-source-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());requests.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_NO_GROUNDED_OBSERVATIONS", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "status", "COMPLETED", "observations", cn.bidcheck.compat.Java8Api.listOf())));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});first.onPage(page,PageClassifier.nativeOnly(page.text(),1));first.finish(full,temporary.resolve("unused.pdf"),visual,1,true,8);
        java.util.Map<java.lang.String, java.lang.Object> before=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));assertEquals(1,requests.get());
        Checkpoint strict=check(Store.id(),"VALUE_COMPARE","bidValidity","90");cn.bidcheck.service.DocumentProcessingSession second=session(d,cn.bidcheck.compat.Java8Api.listOf(strict),()->{});
        java.util.Map<java.lang.String, java.lang.Object> pending=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));assertEquals(before.get("scope"),pending.get("scope"));assertNotEquals(before.get("checkScope"),pending.get("checkScope"));
        assertTrue(store.maps("incremental_check",d.projectId(),d.id()).stream().anyMatch(row->pending.get("checkScope").equals(row.get("checkScope"))&&"REVIEW_REQUIRED".equals(row.get("state"))),"New checks are evaluated before another page or visual response arrives");
        second.finish(full,temporary.resolve("unused.pdf"),visual,1,true,8);assertEquals(1,requests.get(),"Changing checks must not resend completed source windows");
        cn.bidcheck.service.DocumentProcessingSession relaxed=session(d,cn.bidcheck.compat.Java8Api.listOf(check(strict.id(),"VALUE_COMPARE","bidValidity","20")),()->{});relaxed.finish(full,temporary.resolve("unused.pdf"),visual,1,true,8);
        java.util.Map<java.lang.String, java.lang.Object> current=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));assertEquals(before.get("scope"),current.get("scope"));assertNotEquals(pending.get("checkScope"),current.get("checkScope"));
        assertTrue(store.maps("incremental_check",d.projectId(),d.id()).stream().anyMatch(row->current.get("checkScope").equals(row.get("checkScope"))&&"EVIDENCE_FOUND".equals(row.get("state"))));
        assertEquals(1,requests.get());assertEquals(1,store.maps("processing_window",d.projectId(),d.id()).size());assertEquals(1,store.maps("processing_page",d.projectId(),d.id()).size());
    }
    @Test void recoveredIndependentParentDoesNotOverwriteTheRepairedChildFactIndex()throws Exception{
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("f2", 32));List<Page> pages=cn.bidcheck.compat.Java8Api.toList(cn.bidcheck.compat.Java8Api.listOf(1, 3).stream()
            .map(p->new Page(p,"合同编号：REPAIRED-"+p+"\n合同协议","PAGE","QWEN_PDF_OCR","")));
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf());List<List<Integer>> requests=new ArrayList<>();
        VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "independent-parent-recovery-test";}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){return response(doc,input,permit,true);}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){return response(doc,input,permit,false);}
            private Map<String,Object> response(Document doc,List<Integer> input,java.util.function.BooleanSupplier permit,boolean independent){
                assertTrue(permit.getAsBoolean());requests.add(input);int page=independent?1:3;
                Map<String,Object> field=cn.bidcheck.compat.Java8Api.mapOf("sourcePage", page, "type", "FIELD", "label", "合同编号", "value", "REPAIRED-"+page, "sourceQuote", "合同编号：REPAIRED-"+page, "readability", "CLEAR", "source", cn.bidcheck.compat.Java8Api.mapOf("documentId", doc.id(), "projectId", doc.projectId(), "documentHash", doc.sha256(), "documentVersion", doc.version(), "page", page));
                Map<String,Object> result=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", independent?"PARTIAL":"OBSERVED", "errorCode", independent?"VISUAL_WINDOW_PARTIAL":"", "independentPages", independent, "observations", cn.bidcheck.compat.Java8Api.listOf(field), "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", page, "status", "COMPLETED", "observations", cn.bidcheck.compat.Java8Api.listOf(field)))));
                result.put("factBatch",StructuredContextFacts.batch(doc,input,result,identity()));return result;
            }
        };
        cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page page:pages)processing.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        processing.finish(full,temporary.resolve("unused.pdf"),visual,1,true,16);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 3), cn.bidcheck.compat.Java8Api.listOf(3)),requests);
        java.util.Map<java.lang.String, java.lang.Object> parent=store.maps("processing_window",d.projectId(),d.id()).stream().filter(row->((List<?>)row.get("pages")).size()==2).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("RECOVERED",parent.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 3),object(parent.get("factResult")).get("acceptedSourcePages"));assertEquals(2,((List<?>)object(parent.get("factResult")).get("occurrenceIds")).size());
        List<Map<String,Object>> occurrences=cn.bidcheck.compat.Java8Api.toList(((List<?>)ledger.documentView(full,"processing-test").get("occurrences")).stream().map(IncrementalProcessingIntegrationTest::object).filter(o->"CONTEXT_FACT".equals(o.get("sourceDomain"))));
        assertEquals(2,occurrences.size(),"Recovering the parent must preserve the repaired child's saved index");
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("REPAIRED-1", "REPAIRED-3"),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(occurrences.stream().map(o->object(o.get("fact")).get("rawValue")))));
        assertTrue(store.maps("page_fact_window",d.projectId(),null).stream().allMatch(row->((List<?>)row.get("occurrenceIds")).size()==1));
        session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,1,true,16);assertEquals(2,requests.size());
    }
    @Test void publicProgressFactsAndContinueUseThePublishedVersionWithoutOcrOrChangingTheDocument()throws Exception {
        String project=(String)service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "增量续跑验收")).get("id");
        cn.bidcheck.persistence.TaskRow task=service.upload(project,"BID",new org.springframework.mock.web.MockMultipartFile("file","bid.txt","text/plain","项目编号：INC001\n投标有效期：90天".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.execute(task);String id=(String)store.map(task.inputJson).get("documentId");Document original=service.document(id);
        java.util.Map<java.lang.String, java.lang.Object> processing=service.processing(id);assertEquals(true,processing.get("parsingComplete"));assertEquals(true,processing.get("configurationCurrent"));
        assertFalse(((List<?>)service.processingFacts(id).get("occurrences")).isEmpty(),"The API must preserve the original name/hash when validating facts");
        cn.bidcheck.persistence.TaskRow next=service.continueProcessing(id);assertEquals(next.id,service.continueProcessing(id).id);service.execute(next);
        assertEquals(original,service.document(id));assertEquals("SUCCEEDED",service.task(next.id).status);
        assertNotNull(service.publicTask(next).get("processingProgress"));
    }
    @Test void nativePdfSignaturePageStillRoutesToImageObservation()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("e", 64));Page page=new Page(1,"签署页\n法定代表人签字：","PAGE","PDFBOX","");
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),cn.bidcheck.compat.Java8Api.listOf(page),cn.bidcheck.compat.Java8Api.listOf());
        AtomicInteger calls=new AtomicInteger();cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "vector-signature-test";}
            public Map<String,Object> verifyWindow(Document doc,Path file,List<Integer> input,Map<String,Object> request,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());assertEquals("LAYOUT",request.get("task"));calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "status", "COMPLETED")));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});session.onPage(page,PageClassifier.nativeOnly(page.text(),0));session.finish(full,temporary.resolve("unused.pdf"),visual,4);
        assertEquals(1,calls.get());
    }
    @Test void rejectedFieldSurvivesMissingPageRecoveryAndBothRestartBoundaries()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("f", 64));List<Page> pages=new ArrayList<>();
        for(int i=1;i<=3;i++)pages.add(new Page(i,"合同编号：ABCD-001\n合同协议\n第"+i+"条 服务内容","PAGE","QWEN_PDF_OCR",""));
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf());
        List<List<Integer>> requested=new CopyOnWriteArrayList<>();AtomicBoolean failSingle=new AtomicBoolean(true);
        cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "rejected-context-recovery-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());requested.add(input);
                if(input.size()==3)return cn.bidcheck.compat.Java8Api.mapOf("status", "PARTIAL", "errorCode", "VISUAL_WINDOW_PARTIAL", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "status", "COMPLETED"), cn.bidcheck.compat.Java8Api.mapOf("page", 3, "status", "COMPLETED")), "factBatch", cn.bidcheck.compat.Java8Api.mapOf("sources", cn.bidcheck.compat.Java8Api.listOf(), "facts", cn.bidcheck.compat.Java8Api.listOf(), "rejected", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "field", "contractNumber", "code", "CONTEXT_QUOTE_NOT_ON_SOURCE_PAGE"))));
                if(failSingle.get())return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_TIMEOUT", "pageResults", cn.bidcheck.compat.Java8Api.listOf());
                return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 2, "status", "COMPLETED")));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});for(Page p:pages)first.onPage(p,PageClassifier.nativeOnly(p.text(),1));
        first.finish(full,temporary.resolve("unused.pdf"),visual,8);
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals("PARTIAL_FAILED",summary.get("status"));assertTrue(((Number)summary.get("contextPending")).intValue()>0);assertTrue(((Number)summary.get("contextReviewRequired")).intValue()>0);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(2)),requested,"A rejected field must not stop recovery of the genuinely missing page");
        failSingle.set(false);session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,8);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.listOf(1, 2, 3), cn.bidcheck.compat.Java8Api.listOf(2), cn.bidcheck.compat.Java8Api.listOf(2)),requested);
        summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals("REVIEW_REQUIRED",summary.get("status"));assertEquals(0,((Number)summary.get("contextPending")).intValue());
        Map<String,Object> parent=store.maps("processing_window",d.projectId(),d.id()).stream().filter(row->((List<?>)row.get("pages")).size()==3).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("REVIEW_REQUIRED",parent.get("status"));assertEquals(true,parent.get("reviewRequired"));
        // Simulate a process stop after the successful child committed but before its parent was reconciled.
        parent.put("status","VISUAL_WINDOW_PARTIAL");String windowKey=(String)parent.get("windowKey");
        store.save("processing_window",BidService.hash("processing-window:"+windowKey),d.projectId(),d.id(),parent);
        session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,8);
        assertEquals(3,requested.size());assertEquals("REVIEW_REQUIRED",store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("status"));
        assertEquals("REVIEW_REQUIRED",store.maps("processing_window",d.projectId(),d.id()).stream().filter(row->windowKey.equals(row.get("windowKey"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("status"));
    }
    @Test void ledgerRejectedFactCannotBecomeSuccessfulWindowEvenWhenVisualTranscriptionCompleted()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("1", 64));Page page=new Page(1,"合同编号：ABCD-001\n合同协议","PAGE","QWEN_PDF_OCR","");
        Document full=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),cn.bidcheck.compat.Java8Api.listOf(page),cn.bidcheck.compat.Java8Api.listOf());AtomicInteger calls=new AtomicInteger();
        cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "ledger-rejection-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());calls.incrementAndGet();
                Map<String,Object> invalidFact=cn.bidcheck.compat.Java8Api.mapOf("kind", "FIELD", "rawValue", "ABCD-001", "value", "ABCD-001", "field", "contractNumber", "scope", "UNRESOLVED", "sourceIds", cn.bidcheck.compat.Java8Api.listOf("missing-source"));
                return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "status", "COMPLETED")), "factBatch", cn.bidcheck.compat.Java8Api.mapOf("sources", cn.bidcheck.compat.Java8Api.listOf(), "facts", cn.bidcheck.compat.Java8Api.listOf(invalidFact), "rejected", cn.bidcheck.compat.Java8Api.listOf()));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});first.onPage(page,PageClassifier.nativeOnly(page.text(),1));first.finish(full,temporary.resolve("unused.pdf"),visual,4);
        java.util.Map<java.lang.String, java.lang.Object> row=store.maps("processing_window",d.projectId(),d.id()).get(0);assertEquals("REVIEW_REQUIRED",row.get("status"));assertEquals(true,row.get("reviewRequired"));
        assertFalse(((List<?>)((Map<?,?>)row.get("factResult")).get("rejected")).isEmpty());
        assertEquals("REVIEW_REQUIRED",store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("status"));
        session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{}).finish(full,temporary.resolve("unused.pdf"),visual,4);assertEquals(1,calls.get());
    }
    @Test void completeHtmlTablePersistsPhysicalCellFactsWithoutVisualAndRestoresThemBeforeFinalization()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("2", 64));Checkpoint cp=check(Store.id(),"VALUE_COMPARE","bidValidity","90");
        Page page=new Page(1,"投标有效期：30天\n"+insuranceTable(false),"PAGE","QWEN_PDF_OCR","");AtomicInteger visualCalls=new AtomicInteger();
        cn.bidcheck.verification.source.VisualVerificationService visual=tableVisual(visualCalls);cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(cp),()->{});first.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        Map<String,Object> row=store.maps("processing_page",d.projectId(),d.id()).get(0),factResult=object(row.get("factResult"));
        assertEquals("STRUCTURED_TABLE_PARSE",object(row.get("route")).get("parserStrategy"));assertEquals(true,object(factResult.get("structuredTable")).get("used"));
        assertEquals(11,((Number)object(factResult.get("structuredTable")).get("factCount")).intValue());
        List<Fact> saved=ledger.documentFacts(first.current(),"processing-test");assertEquals(11,saved.stream().filter(f->"INSURANCE_RECORD".equals(f.entityType())).count());
        assertEquals(1,saved.stream().filter(f->"insuredPersonName".equals(f.field())).count(),"Rowspan identity must be recorded once, not copied to each insurance row");
        assertEquals("REVIEW_REQUIRED",store.maps("incremental_check",d.projectId(),d.id()).get(0).get("state"));
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(cp),()->{});
        Object restored=org.springframework.test.util.ReflectionTestUtils.getField(resumed,"facts");
        assertInstanceOf(Map.class,restored);
        long restoredTableFacts=((Map<?,?>)restored).values().stream().map(v->(List<?>)v).flatMap(Collection::stream).map(Fact.class::cast).filter(f->"INSURANCE_RECORD".equals(f.entityType())).count();
        assertEquals(11,restoredTableFacts,"Restored sessions must retain structured facts before the final ledger refresh");
        resumed.onPage(page,PageClassifier.nativeOnly(page.text(),1));assertEquals(saved.size(),ledger.documentFacts(resumed.current(),"processing-test").size());
        resumed.finish(resumed.current(),temporary.resolve("unused.pdf"),visual,4);assertEquals(0,visualCalls.get());
        assertEquals(0,((Number)store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("pageReviewRequired")).intValue());
        Page changed=new Page(1,page.text().replace("<td>6</td>","<td>8</td>"),"PAGE","QWEN_PDF_OCR","");
        cn.bidcheck.service.DocumentProcessingSession reparsed=session(d,cn.bidcheck.compat.Java8Api.listOf(cp),()->{});reparsed.onPage(changed,PageClassifier.nativeOnly(changed.text(),1));
        List<Fact> current=ledger.documentFacts(reparsed.current(),"processing-test");
        assertTrue(current.stream().anyMatch(f->"paidMonths".equals(f.field())&&"8".equals(f.rawValue())));
        assertFalse(current.stream().anyMatch(f->"paidMonths".equals(f.field())&&"6".equals(f.rawValue())),"Changed OCR cells must retract the old current fact");
    }
    @Test void ambiguousHtmlTableStillUsesVisualAndDoesNotPublishDeterministicTableFacts()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("3", 64));Page page=new Page(1,insuranceTable(false).replace("colspan=\"2\"","colspan=\"3\""),"PAGE","QWEN_PDF_OCR","");
        AtomicInteger calls=new AtomicInteger();cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});session.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        java.util.Map<java.lang.String, java.lang.Object> row=store.maps("processing_page",d.projectId(),d.id()).get(0);assertNotEquals("STRUCTURED_TABLE_PARSE",object(row.get("route")).get("parserStrategy"));
        assertEquals(false,object(object(row.get("factResult")).get("structuredTable")).get("used"));
        assertTrue(ledger.documentFacts(session.current(),"processing-test").stream().noneMatch(f->"INSURANCE_RECORD".equals(f.entityType())));
        session.finish(session.current(),temporary.resolve("unused.pdf"),tableVisual(calls),4);assertEquals(1,calls.get());
    }
    @Test void emptyInsuranceIdentityIsNeverFilledAndReviewStatusSurvivesRestart()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("4", 64));Page page=new Page(1,insuranceTable(true),"PAGE","QWEN_PDF_OCR","");
        AtomicInteger calls=new AtomicInteger();cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});first.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        List<Fact> saved=ledger.documentFacts(first.current(),"processing-test");
        assertEquals(1,saved.stream().filter(f->"insuredPersonName".equals(f.field())).count());assertEquals(1,saved.stream().filter(f->"socialSecurityNumber".equals(f.field())).count());
        assertEquals(2,saved.stream().filter(f->"insuranceType".equals(f.field())).count());
        assertTrue(saved.stream().noneMatch(f->"personName".equals(f.field())),"An insurance record must not invent a personnel role");
        first.finish(first.current(),temporary.resolve("unused.pdf"),tableVisual(calls),4);assertEquals(0,calls.get());
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));assertEquals("REVIEW_REQUIRED",summary.get("status"));assertEquals(1,((Number)summary.get("pageReviewRequired")).intValue());
        String issues=store.encode(summary.get("pageReviewIssues"));assertTrue(issues.contains("身份信息为空"));assertFalse(issues.contains("张三"));assertFalse(issues.contains("TEST-ID-001"));
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});resumed.finish(resumed.current(),temporary.resolve("unused.pdf"),tableVisual(calls),4);
        assertEquals(0,calls.get());assertEquals("REVIEW_REQUIRED",store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("status"));
    }
    @Test void ordinaryAndDeferredPagesWithoutTablesDoNotCreateTableReviewIssues()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("5", 64));cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
        for(Page page:cn.bidcheck.compat.Java8Api.listOf(new Page(1,"项目编号：A001","PAGE","NATIVE",""), new Page(2,"","PAGE","QWEN_PDF_OCR","[OCR_DEFERRED] 等待识别")))
            session.onPage(page,PageClassifier.nativeOnly(page.text(),page.number()==1?0:1));
        session.finish(session.current(),temporary.resolve("unused.pdf"),null,4);
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals("PARTIAL_FAILED",summary.get("status"));assertEquals(0,((Number)summary.get("pageReviewRequired")).intValue());assertEquals(cn.bidcheck.compat.Java8Api.listOf(),summary.get("pageReviewIssues"));
    }
    @Test void completeTableDoesNotSuppressAnIndependentSignatureOnTheSamePage()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("6", 64));Page page=new Page(1,insuranceTable(false)+"\n签署页\n法定代表人签字：","PAGE","QWEN_PDF_OCR","");
        AtomicInteger calls=new AtomicInteger();cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "table-plus-signature-test";}
            public Map<String,Object> verifyWindow(Document doc,Path path,List<Integer> pages,Map<String,Object> request,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());assertEquals("LAYOUT",request.get("task"));calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "status", "COMPLETED")));
            }
        };
        cn.bidcheck.service.DocumentProcessingSession session=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});session.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        java.util.Map<java.lang.String, java.lang.Object> row=store.maps("processing_page",d.projectId(),d.id()).get(0);assertEquals("SIGNATURE_SEAL",object(row.get("route")).get("pageType"));
        assertEquals(true,object(object(row.get("factResult")).get("structuredTable")).get("used"));
        assertEquals(true,object(object(row.get("factResult")).get("structuredTable")).get("visualStillRequired"));
        session.finish(session.current(),temporary.resolve("unused.pdf"),visual,4);assertEquals(1,calls.get());
    }
    @Test void personConflictAppearsBeforePublicationSurvivesRestartAndRetractsOnCorrection()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("7", 64));cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
        Page one=credentialPage(1,"110101199001010011"),fifty=credentialPage(50,"110101199001010038");
        first.onPage(one,PageClassifier.nativeOnly(one.text(),0));
        java.util.Map<java.lang.String, java.lang.Object> initial=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals("PARTIAL",object(initial.get("personCredentialCoverage")).get("status"),"One completed page does not mean the document is fully covered");
        first.onPage(fifty,PageClassifier.nativeOnly(fifty.text(),0));
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertTrue(((Number)summary.get("personCredentialGroupCount")).intValue()>0);
        assertFalse(store.encode(summary.get("personCredentialGroups")).contains("110101199001010038"),"Progress previews must not include full credential numbers");
        assertTrue(((List<?>)summary.get("personCredentialGroups")).stream().map(IncrementalProcessingIntegrationTest::object).anyMatch(g->new HashSet<>((List<?>)g.get("sourcePages")).equals(cn.bidcheck.compat.Java8Api.setOf(1, 50))));
        assertNull(store.mapper().get(d.id()),"The durable page check must precede final document publication");
        cn.bidcheck.service.DocumentProcessingSession resumed=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});resumed.onPage(fifty,PageClassifier.nativeOnly(fifty.text(),0));
        assertEquals(summary.get("personCredentialGroups"),store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id())).get("personCredentialGroups"));
        Page corrected=credentialPage(50,"110101199001010011");resumed.onPage(corrected,PageClassifier.nativeOnly(corrected.text(),0));
        java.util.Map<java.lang.String, java.lang.Object> repaired=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertTrue(((List<?>)repaired.get("personCredentialGroups")).stream().map(IncrementalProcessingIntegrationTest::object).noneMatch(g->cn.bidcheck.compat.Java8Api.setOf("CONFLICT", "SUSPECTED").contains(g.get("status"))));
        java.util.Map<java.lang.String, java.lang.Object> facts=ledger.documentView(resumed.current(),"processing-test");
        assertFalse(store.encode(facts.get("personCredentialOccurrences")).contains("110101199001010038"));
    }
    @Test void credentialCoveragePersistsForUnreadableAndDeferredPagesInsteadOfClaimingComplete()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("8", 64));cn.bidcheck.service.DocumentProcessingSession first=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
        Page candidate=new Page(1,"中华人民共和国机动车驾驶证\n姓名：\n证号：","PAGE","QWEN_PDF_OCR","");
        Page deferred=new Page(2,"","PAGE","QWEN_PDF_OCR","[OCR_DEFERRED] 等待识别");
        first.onPage(candidate,PageClassifier.nativeOnly(candidate.text(),1));first.onPage(deferred,PageClassifier.nativeOnly(deferred.text(),1));
        first.finish(first.current(),temporary.resolve("unused.pdf"),null,4);
        java.util.Map<java.lang.String, java.lang.Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));java.util.Map<java.lang.String, java.lang.Object> coverage=object(summary.get("personCredentialCoverage"));
        assertEquals("PARTIAL",coverage.get("status"));assertFalse(((List<?>)coverage.get("issues")).isEmpty());assertEquals("PARTIAL_FAILED",summary.get("status"));
        assertEquals(2,store.maps("processing_page",d.projectId(),d.id()).size());
        assertTrue(store.maps("processing_page",d.projectId(),d.id()).stream().allMatch(row->object(row.get("factResult")).containsKey("personCredentials")));
    }
    @Test void scannedDrivingLicenseAndPassportRoutesStillReceiveTheirOwnVisualObservation()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("9", 64));AtomicInteger calls=new AtomicInteger();cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
        for(Page page:cn.bidcheck.compat.Java8Api.listOf(new Page(1,"中华人民共和国机动车驾驶证\n姓名：张三\n证号：110101199001010011","PAGE","QWEN_PDF_OCR",""), new Page(2,"中华人民共和国护照\n姓名：李四\n护照号码：E12345678","PAGE","QWEN_PDF_OCR","")))
            processing.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        cn.bidcheck.verification.source.VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "person-route-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> pages,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());assertEquals(1,pages.size(),"Independent holders must not be packed into one relationship window");calls.incrementAndGet();
                return cn.bidcheck.compat.Java8Api.mapOf("status", "OBSERVED", "errorCode", "", "pageResults", cn.bidcheck.compat.Java8Api.toList(pages.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED"))));
            }
        };
        processing.finish(processing.current(),temporary.resolve("unused.pdf"),visual,4);assertEquals(2,calls.get());
    }
    @Test void ledgerRejectedPersonNumberIsExcludedFromIncrementalComparisonAndRestoredCache()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("a1", 32));store.save("project",d.projectId(),d.projectId(),"",cn.bidcheck.compat.Java8Api.mapOf("id", d.projectId()));
        FactLedgerService guarded=org.mockito.Mockito.spy(ledger);
        org.mockito.Mockito.doAnswer(call->{
            Document doc=call.getArgument(0);Page page=call.getArgument(1);List<Fact> supplied=call.getArgument(2);String identity=call.getArgument(3);
            List<Fact> checked=cn.bidcheck.compat.Java8Api.toList(supplied.stream().map(f->{
                if(page.number()!=50||!"PERSON_CREDENTIAL".equals(f.entityType())||!"drivingLicenseNumber".equals(f.field()))return f;
                Evidence e=f.evidence();return new Fact(f.entityType(),f.entityKey(),f.field(),f.rawValue(),f.normalizedValue(),new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),"不在原页中的转录",e.anchor(),e.confidence()));
            }));
            return ledger.persistSuppliedPageFacts(doc,page,checked,identity);
        }).when(guarded).persistSuppliedPageFacts(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString());
        cn.bidcheck.service.DocumentProcessingSession processing=new DocumentProcessingSession(store,new TransactionTemplate(manager),()->{},OcrUnitObserver.noop(),guarded,d,Store.id(),"processing-test",cn.bidcheck.compat.Java8Api.listOf());
        for(Page page:cn.bidcheck.compat.Java8Api.listOf(credentialPage(1,"110101199001010011"), credentialPage(50,"110101199001010038")))processing.onPage(page,PageClassifier.nativeOnly(page.text(),0));
        Map<String,Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertTrue(((List<?>)summary.get("personCredentialGroups")).isEmpty(),"A rejected number must not create an incremental difference");
        assertEquals("PARTIAL",object(summary.get("personCredentialCoverage")).get("status"));
        java.util.Map<java.lang.String, java.lang.Object> badRow=store.maps("processing_page",d.projectId(),d.id()).stream().filter(row->((Number)object(row.get("page")).get("number")).intValue()==50).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals(1,((List<?>)object(badRow.get("factResult")).get("rejected")).size());
        cn.bidcheck.service.DocumentProcessingSession restored=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});restored.onPage(credentialPage(50,"110101199001010038"),PageClassifier.nativeOnly(credentialPage(50,"110101199001010038").text(),0));
        java.util.Map<java.lang.String, java.lang.Object> resumed=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));assertTrue(((List<?>)resumed.get("personCredentialGroups")).isEmpty());
        assertTrue(((List<?>)ledger.documentView(restored.current(),"processing-test").get("personCredentialOccurrences")).stream().map(IncrementalProcessingIntegrationTest::object).noneMatch(o->"drivingLicenseNumber".equals(o.get("personCredentialField"))&&"110101199001010038".equals(o.get("rawValue"))));
    }
    @Test void informationalCredentialGapsDoNotTurnCompletedProcessingIntoReviewRequired()throws Exception {
        Document d=metadata(Store.id(),Store.id(),cn.bidcheck.compat.Java8Api.repeat("b1", 32));cn.bidcheck.service.DocumentProcessingSession processing=session(d,cn.bidcheck.compat.Java8Api.listOf(),()->{});
        Page page=new Page(1,"中华人民共和国居民身份证\n姓名：测试甲\n身份证号：110101********1234","PAGE","NATIVE","");
        processing.onPage(page,PageClassifier.nativeOnly(page.text(),0));
        processing.finish(processing.current(),temporary.resolve("unused.pdf"),null,4);
        Map<String,Object> summary=store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));
        assertEquals("COMPLETED",summary.get("status"));assertEquals(0,((Number)summary.get("personCredentialGroupCount")).intValue());
        Map<String,Object> coverage=object(summary.get("personCredentialCoverage"));
        assertEquals("PARTIAL",coverage.get("status"));assertEquals(false,coverage.get("requiresReview"));
        assertFalse(((List<?>)coverage.get("informationalIssues")).isEmpty());
        assertTrue(store.encode(ledger.documentView(processing.current(),"processing-test").get("personCredentialOccurrences")).contains("110101********1234"));
    }
    private static Page credentialPage(int number,String license){return new Page(number,"中华人民共和国机动车驾驶证\n姓名：张三\n身份证号码：110101199001010011\n驾驶证号："+license+"\n性别：男\n出生日期：1990-01-01","PAGE","NATIVE","");}
    private static String insuranceTable(boolean blankIdentity){
        String span=blankIdentity?"":" rowspan=\"2\"";
        return "<table><tr><th rowspan=\"2\">序号</th><th rowspan=\"2\">姓名</th><th rowspan=\"2\">社会保障号码</th><th rowspan=\"2\">险种</th><th colspan=\"2\">缴费情况</th><th rowspan=\"2\">本单位实际缴费月数</th></tr>"
            +"<tr><th>起始年月</th><th>截止年月</th></tr><tr><td"+span+">1</td><td"+span+">张三</td><td"+span+">TEST-ID-001</td><td>基本养老保险</td><td>2025-01</td><td>2025-06</td><td>6</td></tr>"
            +"<tr>"+(blankIdentity?"<td></td><td></td><td></td>":"")+"<td>基本医疗保险</td><td>2025-01</td><td>2025-03</td><td>3</td></tr></table>";
    }
    private static VisualVerificationService tableVisual(AtomicInteger calls){
        return new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "table-structured-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> pages,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());calls.incrementAndGet();return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_NO_GROUNDED_OBSERVATIONS", "pageResults", cn.bidcheck.compat.Java8Api.toList(pages.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED"))));
            }
        };
    }
    private static List<Page> guardPages(int count,String warning){
        List<Page> pages=new ArrayList<>();for(int p=1;p<=count;p++)pages.add(new Page(p,"合同编号：GUARD-"+p+"\n合同协议","PAGE","QWEN_PDF_OCR",warning));return pages;
    }
    private static VisualVerificationService guardVisual(List<List<Integer>> requests,boolean partial){
        return new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "ocr-first-guard-test";}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){return response(doc,input,permit,true);}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){return response(doc,input,permit,false);}
            private Map<String,Object> response(Document doc,List<Integer> input,java.util.function.BooleanSupplier permit,boolean independent){
                if(!permit.getAsBoolean())return cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", "VISUAL_BUDGET_EXHAUSTED");
                requests.add(input);List<Integer> completed=partial?cn.bidcheck.compat.Java8Api.listOf(input.get(0)):input;
                List<Map<String,Object>> fields=cn.bidcheck.compat.Java8Api.toList(completed.stream().map(p->cn.bidcheck.compat.Java8Api.<String, Object>mapOf("sourcePage", p, "type", "FIELD", "label", "合同编号", "value", "GUARD-"+p, "sourceQuote", "合同编号：GUARD-"+p, "readability", "CLEAR", "source", cn.bidcheck.compat.Java8Api.mapOf("documentId", doc.id(), "projectId", doc.projectId(), "documentHash", doc.sha256(), "documentVersion", doc.version(), "page", p))));
                Map<String,Object> response=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", partial?"UNDETERMINED":"OBSERVED", "errorCode", partial?"VISUAL_WINDOW_PARTIAL":"", "independentPages", independent, "observations", fields, "pageResults", cn.bidcheck.compat.Java8Api.toList(completed.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED", "observations", cn.bidcheck.compat.Java8Api.toList(fields.stream().filter(f->f.get("sourcePage").equals(p))))))));
                response.put("factBatch",StructuredContextFacts.batch(doc,input,response,identity()));return response;
            }
        };
    }
    private static VisualVerificationService emptyIndependentVisual(String code,boolean completed){
        return new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "ocr-first-guard-test";}
            public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,java.util.function.BooleanSupplier permit,String scope){
                if(completed)assertTrue(permit.getAsBoolean());
                Map<String,Object> response=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", "UNDETERMINED", "errorCode", code, "observations", cn.bidcheck.compat.Java8Api.listOf(), "pageResults", completed?cn.bidcheck.compat.Java8Api.toList(input.stream().map(p->cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", "COMPLETED", "observations", cn.bidcheck.compat.Java8Api.listOf()))):cn.bidcheck.compat.Java8Api.listOf()));
                // Reproduces the production early-return envelope: batch exists but response mode is absent.
                response.put("factBatch",StructuredContextFacts.batch(doc,input,response,identity()));return response;
            }
        };
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
}

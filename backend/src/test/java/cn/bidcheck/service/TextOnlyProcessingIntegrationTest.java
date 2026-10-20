package cn.bidcheck.service;

import cn.bidcheck.document.*;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.OcrUnitObserver;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.VisualVerificationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:text-only-processing;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.visual.enabled=false","bidcheck.ocr.enabled=false",
    "bidcheck.ocr.pdf.enabled=false","bidcheck.semantic.url=","bidcheck.semantic.key=","bidcheck.semantic.model=",
    "bidcheck.semantic.extraction-url=","bidcheck.semantic.extraction-model="})
@AutoConfigureMockMvc
class TextOnlyProcessingIntegrationTest {
    static final Path DATA=tempData();
    static Path tempData(){try{return Files.createTempDirectory("bidcheck-text-only-test-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired Store store;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper jsonMapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired FactLedgerService ledger;
    @Autowired BidService service;
    @Autowired ReportService reports;
    @Autowired MockMvc mvc;
    @TempDir Path temporary;

    Document doc(List<Page> pages){String project=(String)service.createProject(Map.of("name","文字范围回归")).get("id");
        return new Document(Store.id(),project,"BID","source.pdf",1,"a".repeat(64),BidService.now(),pages,List.of());}
    DocumentProcessingSession session(Document d,boolean visual,List<Checkpoint> checks){
        Document metadata=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),List.of(),List.of());
        return new DocumentProcessingSession(store,new TransactionTemplate(manager),()->{},OcrUnitObserver.noop(),ledger,
            metadata,Store.id(),"text-scope-test",checks,visual);
    }
    Map<String,Object> summary(Document d){return store.getMap("processing_summary",DocumentProcessingSession.summaryId(d.id()));}
    static int number(Map<String,Object> map,String key){return ((Number)map.get(key)).intValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    VisualVerificationService forbiddenVisual(){return new VisualVerificationService(){
        public boolean enabled(){throw new AssertionError("Text-only must not even consult the visual provider");}
        public String identity(){throw new AssertionError("Text-only must not depend on visual identity");}
    };}
    void finish(DocumentProcessingSession session,Document d,VisualVerificationService visual)throws Exception{
        session.finish(d,temporary.resolve("unused.pdf"),visual,128,true,2048);
    }

    @Test void defaultPublicConfigurationSelectsFullTextWithoutDisablingIndependentVisualCapability()throws Exception{
        assertFalse(service.automaticVisualProcessingEnabled());assertEquals("TEXT_ONLY",service.documentProcessingMode());
        assertEquals("FULL_DOCUMENT_TEXT",service.ocrPlanningMode());
        mvc.perform(get("/api/config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.automaticVisualProcessingEnabled").value(false))
            .andExpect(jsonPath("$.documentProcessingMode").value("TEXT_ONLY"))
            .andExpect(jsonPath("$.ocrPlanningMode").value("FULL_DOCUMENT_TEXT"));
    }
    @Test void completeTextPreservesTablesPagesAndWarningsWithoutManufacturingVisualCompletion()throws Exception{
        String table="<table><tr><td>项目编号</td><td>TEXT-001</td></tr></table>";
        String warning="[OCR已识别][OCR结构未完成][LAYOUT_PDF_TEXT_RECOVERY] 原图布局需核对";
        Document d=doc(List.of(new Page(1,table,"PAGE","PDF_OCR",warning),
            new Page(2,"营业执照\n统一社会信用代码：91310101TEST","PAGE","PDF_OCR",""),
            new Page(3,"法定代表人签字及公章","PAGE","PDF_OCR","")));
        var session=session(d,false,List.of());finish(session,d,forbiddenVisual());
        assertEquals(d.pages(),session.current().pages());var s=summary(d);
        assertEquals("TEXT_ONLY",s.get("processingMode"));assertEquals("NOT_REQUESTED",s.get("visualAnalysisStatus"));
        assertEquals(false,s.get("automaticVisualRequested"));assertEquals(true,s.get("parsingComplete"));
        assertEquals(3,number(s,"processedPages"));assertEquals(0,number(s,"incompletePages"));
        for(String key:List.of("visualCalls","contextWindows","contextPending","contextCompleted","contextReviewRequired","contextNoFacts","contextLimitedPageCount"))assertEquals(0,number(s,key),key);
        assertTrue(store.maps("processing_window",d.projectId(),d.id()).isEmpty());
        assertEquals(false,map(s.get("visualBudget")).get("fullPlan"));
        assertEquals(warning,session.current().pages().get(0).warning());
    }
    @Test void missingOcrTextRemainsPartialFailureWithoutCreatingWaitingVisualJobs()throws Exception{
        Document d=doc(List.of(new Page(1,"已识别内容","PAGE","PDF_OCR",""),
            new Page(2,"","PAGE","OCR_DEFERRED","[OCR未完成] 识别失败")));
        finish(session(d,false,List.of()),d,forbiddenVisual());var s=summary(d);
        assertEquals("PARTIAL_FAILED",s.get("status"));assertEquals(1,number(s,"incompletePages"));
        assertEquals(0,number(s,"contextPending"));assertEquals(false,s.get("visualDeferredForOcr"));
        assertTrue(store.maps("processing_window",d.projectId(),d.id()).isEmpty());
    }
    @Test void textScopeDoesNotCountOrDeleteHistoricalStructuredReceipts()throws Exception{
        Document d=doc(List.of(new Page(1,"合同编号：OLD-001\n合同协议","PAGE","PDF_OCR","")));
        AtomicInteger calls=new AtomicInteger();VisualVerificationService visual=new VisualVerificationService(){
            public boolean enabled(){return true;}public String identity(){return "old-visual-test";}
            public Map<String,Object> extractContext(Document doc,Path path,List<Integer> pages,java.util.function.BooleanSupplier permit,String scope){
                assertTrue(permit.getAsBoolean());calls.incrementAndGet();
                return Map.of("status","UNDETERMINED","errorCode","VISUAL_NO_GROUNDED_OBSERVATIONS",
                    "pageResults",List.of(Map.of("page",1,"status","COMPLETED")));
            }
        };
        finish(session(d,true,List.of()),d,visual);String oldScope=summary(d).get("scope").toString();
        List<Map<String,Object>> old=store.maps("processing_window",d.projectId(),d.id());assertFalse(old.isEmpty());
        assertEquals(1,calls.get());finish(session(d,false,List.of()),d,forbiddenVisual());
        assertNotEquals(oldScope,summary(d).get("scope"));assertEquals(0,number(summary(d),"contextWindows"));
        assertEquals(old,store.maps("processing_window",d.projectId(),d.id()));
    }
    @Test void textFactsAndRulesRemainAvailableButDoNotCertifyBusinessPass()throws Exception{
        Document d=doc(List.of(new Page(1,"投标有效期：30天","PAGE","TEXT","")));
        Checkpoint cp=new Checkpoint(Store.id(),"投标有效期","响应","HIGH","VALUE_COMPARE","bidValidity",">=","90","天",true,true,true,null);
        var session=session(d,false,List.of(cp));finish(session,d,forbiddenVisual());
        var results=store.maps("incremental_check",d.projectId(),d.id());assertEquals(1,results.size());
        assertEquals("REVIEW_REQUIRED",results.get(0).get("state"));assertEquals("UNDETERMINED",results.get(0).get("businessDecision"));
        assertTrue(number(summary(d),"factCount")>0);assertFalse(ledger.documentFacts(session.current(),"text-scope-test").isEmpty());
    }
    @Test void textIdentityIgnoresVisualConfigurationWhileOptInIdentityRemainsSeparated(){
        Object originalVisual=ReflectionTestUtils.getField(service,"processingVisual");
        try{
            String textIdentity=service.processingIdentity(),textPreparation=service.ocrPreparationIdentity();
            ReflectionTestUtils.setField(service,"processingVisual",new VisualVerificationService(){public String identity(){return "new-independent-provider";}});
            assertEquals(textIdentity,service.processingIdentity());assertEquals(textPreparation,service.ocrPreparationIdentity());
            ReflectionTestUtils.setField(service,"automaticVisualProcessingEnabled",true);
            assertNotEquals(textIdentity,service.processingIdentity());assertNotEquals(textPreparation,service.ocrPreparationIdentity());
            assertEquals("FULL_DOCUMENT_FACTS",service.ocrPlanningMode());
        }finally{ReflectionTestUtils.setField(service,"processingVisual",originalVisual);ReflectionTestUtils.setField(service,"automaticVisualProcessingEnabled",false);}
    }
    @Test void formalTextConsistencyAndSemanticRulesStillExecuteAndFreezeTextScope()throws Exception{
        String project=(String)service.createProject(Map.of("name","完整文字检查")).get("id");
        var upload=service.upload(project,"BID",new MockMultipartFile("file","bid.txt","text/plain",("项目名称：文字项目\n投标人名称：甲公司\n投标有效期：90天\n售后服务：提供技术支持").getBytes(StandardCharsets.UTF_8)));
        service.execute(upload);String docId=BidService.text(store.map(upload.inputJson),"documentId");
        Checkpoint full=new Checkpoint(Store.id(),"全文一致性","一致性","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","同一主体信息一致","",true,true,true,null);
        Checkpoint semantic=new Checkpoint(Store.id(),"服务响应","技术","MEDIUM","SEMANTIC_RESPONSE","technicalResponse","RESPONDS","提供技术支持","",true,true,true,null);
        store.save("checkpoint",full.id(),project,"",full);store.save("checkpoint",semantic.id(),project,"",semantic);
        var started=service.startRun(project,docId,true);service.execute(service.task(BidService.text(started,"taskId")));
        Map<String,Object> payload=service.run(BidService.text(started,"id"),false),run=map(payload.get("run"));
        assertEquals("COMPLETED",run.get("status"));assertFalse(map(payload.get("consistency")).isEmpty());
        @SuppressWarnings("unchecked") List<Finding> findings=(List<Finding>)payload.get("results");
        assertTrue(findings.stream().anyMatch(f->full.id().equals(f.checkpoint().id())));
        assertTrue(findings.stream().anyMatch(f->semantic.id().equals(f.checkpoint().id())&&"UNDETERMINED".equals(f.evaluation().status())));
        var frozen=map(((List<?>)run.get("processingCoverage")).get(0));
        assertEquals("TEXT_ONLY",frozen.get("processingMode"));assertEquals("NOT_REQUESTED",frozen.get("visualAnalysisStatus"));
        assertTrue(frozen.containsKey("scope"));assertEquals(0,number(frozen,"contextWindows"));
    }
    @Test void allExportsKeepFrozenTextScopeAndLayoutGapsDespiteLaterStructuredProcessing()throws Exception{
        Document d=doc(List.of(new Page(1,"转录已保存","PAGE","PDF_OCR","[OCR已识别]")));
        String warning="第 1 页[OCR结构未完成] 表格关系待核对";
        d=new Document(d.id(),d.projectId(),d.role(),d.name(),d.version(),d.sha256(),d.createdAt(),d.pages(),List.of(warning));
        store.save("document",d.id(),d.projectId(),"",d);String runId=Store.id();
        Map<String,Object> frozen=BidService.fields("documentId",d.id(),"processingMode","TEXT_ONLY","visualAnalysisStatus","NOT_REQUESTED","automaticVisualRequested",false,
            "contextWindows",0,"contextPending",0,"contextCompleted",0,"contextLimitedPageCount",0);
        Map<String,Object> run=BidService.fields("id",runId,"projectId",d.projectId(),"documentId",d.id(),"status","COMPLETED","ruleVersion","fixture","processingCoverage",List.of(frozen));
        store.save("run",runId,d.projectId(),d.id(),run);
        Checkpoint cp=new Checkpoint(Store.id(),"文字检查","一致性","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","一致","",true,true,true,null);
        String findingId=Store.id();store.save("finding",findingId,d.projectId(),runId,new Finding(findingId,runId,cp,new Evaluation("UNDETERMINED","需核对文字","核对原文",List.of(),0,"DETERMINISTIC")));
        store.save("processing_summary",DocumentProcessingSession.summaryId(d.id()),d.projectId(),d.id(),BidService.fields("documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),
            "processingMode","STRUCTURED_VISUAL","visualAnalysisStatus","REQUESTED","contextPending",99));
        String html=new String(reports.generate(runId,"html").bytes(),StandardCharsets.UTF_8);
        String csv=new String(reports.generate(runId,"csv").bytes(),StandardCharsets.UTF_8);
        String json=new String(reports.generate(runId,"json").bytes(),StandardCharsets.UTF_8);
        assertTrue(html.contains("未请求逐页图像、签章或视觉字段核验"));assertTrue(html.contains("1 处文字或表格布局待复核"));
        assertFalse(html.contains("99 个未完成"));assertFalse(html.contains("图像分析："));
        assertTrue(csv.contains("未请求逐页图像、签章或视觉字段核验"));assertTrue(csv.contains("TEXT_ONLY"));assertTrue(csv.contains("NOT_REQUESTED"));
        var exported=jsonMapper.readTree(json);assertEquals(jsonMapper.valueToTree(frozen),exported.path("run").path("processingCoverage").get(0));
        // Unknown historical scope must never acquire the new text-only claim from today's configuration.
        run.put("processingCoverage",List.of(Map.of("documentId",d.id(),"contextWindows",3,"contextPending",2)));
        store.save("run",runId,d.projectId(),d.id(),run);String legacy=new String(reports.generate(runId,"html").bytes(),StandardCharsets.UTF_8);
        assertTrue(legacy.contains("2 个未完成"));assertFalse(legacy.contains("未请求逐页图像、签章或视觉字段核验"));
    }
}

package cn.bidcheck.service;

import cn.bidcheck.document.PageClassifier;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.OcrUnitObserver;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.StructuredContextFacts;
import cn.bidcheck.verification.source.VisualPageWindows;
import cn.bidcheck.verification.source.VisualVerificationService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:compact-visual-scheduling;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.visual.enabled=false"})
class CompactVisualSchedulingIntegrationTest {
    @Autowired Store store;
    @Autowired PlatformTransactionManager manager;
    @Autowired FactLedgerService ledger;
    @TempDir Path temporary;

    @Test void compactDenseTablesKeepEveryIndependentPageAndFactAsSingletons()throws Exception {
        Document doc=document(tablePages(6));var visual=new RecordingVisual(true);
        var processing=process(doc,visual,null);
        assertEquals(Set.of(List.of(1),List.of(2),List.of(3),List.of(4),List.of(5),List.of(6)),new HashSet<>(visual.requests));
        assertEquals(6,visual.requests.size());assertEquals(6,acceptedPages(doc,processing).size());
        assertEquals(6,number(summary(doc).get("contextCompleted")));
        assertEquals(0,number(summary(doc).get("contextPending")));
        assertEquals(12,number(object(summary(doc).get("visualBudget")).get("requiredCalls")));
    }

    @Test void compactHtmlTablesStaySingleEvenWhenTheirOtherMaterialRouteIsCertificate()throws Exception {
        List<Page> pages=new ArrayList<>();
        for(int p=1;p<=6;p++)pages.add(page(p,"营业执照\n合同编号：CERT-"+p+(p<=3?"\n<TABLE data-kind=\"other\"><tr><td>原页单元</td></tr></TABLE>":"")));
        Document doc=document(pages);var visual=new RecordingVisual(true);
        process(doc,visual,new PageClassifier.Decision("CERTIFICATE","INDEPENDENT_VISUAL","CERTIFICATE_V1","HIGH",false,"SMALL",true));
        assertEquals(Set.of(List.of(1),List.of(2),List.of(3),List.of(4,5,6)),new HashSet<>(visual.requests));
        assertEquals(6,visual.requests.stream().flatMap(List::stream).distinct().count());
    }

    @Test void compactStillMicrobatchesShortIndependentMaterials()throws Exception {
        List<Page> pages=new ArrayList<>();for(int p=1;p<=6;p++)pages.add(page(p,"营业执照\n合同编号：SHORT-"+p));
        Document doc=document(pages);var visual=new RecordingVisual(true);process(doc,visual,null);
        assertEquals(Set.of(List.of(1,2,3),List.of(4,5,6)),new HashSet<>(visual.requests));
        assertEquals(2,visual.requests.size());assertTrue(visual.independentRequests.stream().allMatch(Boolean::booleanValue));
    }

    @Test void compactDoesNotSplitARealContinuousTableRelationship()throws Exception {
        Document doc=document(List.of(page(1,"报价表\n合同编号：RELATED-001\n第一条 合同内容"),
            page(2,"报价表\n合同编号：RELATED-001\n第二条 合同内容"),page(3,"报价表\n合同编号：RELATED-001\n第三条 合同内容")));
        assertEquals(List.of(List.of(1,2,3)),VisualPageWindows.plan(doc));
        assertFalse(VisualPageWindows.relations(doc,List.of(1,2,3)).isEmpty());
        var visual=new RecordingVisual(true);process(doc,visual,null);
        assertEquals(List.of(List.of(1,2,3)),visual.requests);assertEquals(List.of(false),visual.independentRequests);
        assertFalse(((List<?>)store.maps("processing_window",doc.projectId(),doc.id()).get(0).get("relations")).isEmpty());
    }

    @Test void disabledFlagPreservesLegacyTableBatchesAndSuccessfulCacheReuse()throws Exception {
        Document doc=document(tablePages(6));var visual=new RecordingVisual(false);process(doc,visual,null);
        assertEquals(Set.of(List.of(1,2,3),List.of(4,5,6)),new HashSet<>(visual.requests));
        Map<String,Object> before=summary(doc);process(doc,visual,null);
        assertEquals(2,visual.requests.size());assertEquals(before.get("scope"),summary(doc).get("scope"));
        assertEquals("document-processing-4",summary(doc).get("version"));
    }

    @Test void compactIdentitySeparatesPriorSuccessButSameCompactScopeReusesAllFacts()throws Exception {
        Document doc=document(tablePages(3));var legacy=new RecordingVisual(false);process(doc,legacy,null);
        String legacyScope=String.valueOf(summary(doc).get("scope"));
        assertEquals(List.of(List.of(1,2,3)),legacy.requests);
        var compact=new RecordingVisual(true);var processing=process(doc,compact,null);
        String compactScope=String.valueOf(summary(doc).get("scope"));assertNotEquals(legacyScope,compactScope);
        assertEquals(Set.of(List.of(1),List.of(2),List.of(3)),new HashSet<>(compact.requests));
        assertEquals(Set.of(1,2,3),acceptedPages(doc,processing));
        var resumed=process(doc,compact,null);assertEquals(3,compact.requests.size());
        assertEquals(compactScope,summary(doc).get("scope"));assertEquals(Set.of(1,2,3),acceptedPages(doc,resumed));
        assertEquals(1,store.maps("processing_window",doc.projectId(),doc.id()).stream().filter(r->legacyScope.equals(r.get("scope"))).count());
        assertEquals(3,store.maps("processing_window",doc.projectId(),doc.id()).stream().filter(r->compactScope.equals(r.get("scope"))).count());
    }

    @Test void plainPdfRecoveryWithLayoutGapIsIncludedAndItsSuccessfulFactsResume()throws Exception {
        Page recovered=recoveryPage(1,"");
        assertEquals("SCAN_TEXT_PAGE",PageClassifier.nativeOnly(recovered.text(),1).pageType());
        Document doc=document(List.of(recovered));var visual=new RecordingVisual(true);
        var first=process(doc,visual,null);
        assertEquals(List.of(List.of(1)),visual.requests);assertEquals(Set.of(1),acceptedPages(doc,first));
        var resumed=process(doc,visual,null);
        assertEquals(1,visual.requests.size());assertEquals(Set.of(1),acceptedPages(doc,resumed));
    }

    @Test void ordinaryScanAndUnmarkedLayoutWarningsKeepTheirExistingNoVisualBehavior()throws Exception {
        Document doc=document(List.of(page(1,"项目名称：普通扫描文本"),
            new Page(2,"项目名称：普通转录文本","PAGE","QWEN_PDF_OCR","[OCR结构未完成] 无像素坐标")));
        var visual=new RecordingVisual(true);process(doc,visual,null);
        assertTrue(visual.requests.isEmpty());assertEquals(0,number(summary(doc).get("contextWindows")));
    }

    @Test void recoveryMarkerDoesNotBypassAnUnfinishedOcrPage()throws Exception {
        Document doc=document(List.of(recoveryPage(1,"[OCR未完成] 文字待恢复")));
        var visual=new RecordingVisual(true);process(doc,visual,null);
        assertTrue(visual.requests.isEmpty());assertEquals(Boolean.TRUE,summary(doc).get("visualDeferredForOcr"));
        assertEquals(1,number(summary(doc).get("contextPending")));
        assertEquals("WAITING_OCR",store.maps("processing_window",doc.projectId(),doc.id()).get(0).get("status"));
    }

    @Test void compactPlainRecoveryPagesStaySingleButDisabledModeRetainsMicrobatching()throws Exception {
        List<Page> pages=new ArrayList<>();for(int p=1;p<=3;p++)pages.add(recoveryPage(p,""));
        var compact=new RecordingVisual(true);Document compactDoc=document(pages);process(compactDoc,compact,null);
        assertEquals(Set.of(List.of(1),List.of(2),List.of(3)),new HashSet<>(compact.requests));
        assertEquals(3,compact.requests.size());assertEquals(0,number(summary(compactDoc).get("contextPending")));
        var legacy=new RecordingVisual(false);process(document(pages),legacy,null);
        assertEquals(List.of(List.of(1,2,3)),legacy.requests);
    }

    @Test void pdfRecoveryMarkerDoesNotExpandWordOrPartialProcessingScope()throws Exception {
        Document pdf=document(List.of(recoveryPage(1,"")));
        Document word=new Document(pdf.id(),pdf.projectId(),pdf.role(),"fixture.docx",pdf.version(),pdf.sha256(),pdf.createdAt(),pdf.pages(),List.of());
        var visual=new RecordingVisual(true);process(word,visual,null);
        assertTrue(visual.requests.isEmpty());
        process(document(List.of(recoveryPage(1,""))),visual,null,false);
        assertTrue(visual.requests.isEmpty());
    }

    private DocumentProcessingSession process(Document doc,RecordingVisual visual,PageClassifier.Decision route)throws Exception {
        return process(doc,visual,route,true);
    }
    private DocumentProcessingSession process(Document doc,RecordingVisual visual,PageClassifier.Decision route,boolean fullCoverage)throws Exception {
        store.save("project",doc.projectId(),doc.projectId(),"",Map.of("id",doc.projectId()));
        Document metadata=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),doc.version(),doc.sha256(),doc.createdAt(),List.of(),List.of());
        var processing=new DocumentProcessingSession(store,new TransactionTemplate(manager),()->{},OcrUnitObserver.noop(),ledger,metadata,Store.id(),"processing-"+visual.identity(),List.of());
        for(Page p:doc.pages())processing.onPage(p,route==null?PageClassifier.nativeOnly(p.text(),1):route);
        processing.finish(doc,temporary.resolve("unused.pdf"),visual,1,fullCoverage,128);return processing;
    }
    private Set<Integer> acceptedPages(Document doc,DocumentProcessingSession processing){
        Set<Integer> pages=new HashSet<>();String scope=String.valueOf(summary(doc).get("scope"));
        for(var row:store.maps("processing_window",doc.projectId(),doc.id()))if(scope.equals(row.get("scope")))
            for(Object value:(List<?>)object(row.get("factResult")).getOrDefault("acceptedSourcePages",List.of()))pages.add(number(value));
        assertFalse(ledger.documentView(processing.current(),String.valueOf(summary(doc).get("identity"))).isEmpty());return pages;
    }
    private Map<String,Object> summary(Document doc){return store.getMap("processing_summary",DocumentProcessingSession.summaryId(doc.id()));}
    private static Document document(List<Page> pages){return new Document(Store.id(),Store.id(),"BID","fixture.pdf",1,"d1".repeat(32),"2026-10-15T00:00:00Z",pages,List.of());}
    private static List<Page> tablePages(int count){List<Page> pages=new ArrayList<>();for(int p=1;p<=count;p++)pages.add(page(p,"报价表\n合同编号：DENSE-"+p+"\n金额：100.00元"));return pages;}
    private static Page page(int p,String text){return new Page(p,text,"PAGE","QWEN_PDF_OCR","");}
    private static Page recoveryPage(int p,String extra){return new Page(p,"项目名称：恢复文本样例"+p,"PAGE","QWEN_PDF_OCR","[LAYOUT_PDF_TEXT_RECOVERY] [OCR结构未完成] 版面待核对 "+extra);}
    private static int number(Object value){return ((Number)value).intValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}

    private static class RecordingVisual extends VisualVerificationService {
        final boolean compact;final List<List<Integer>> requests=new CopyOnWriteArrayList<>();
        final List<Boolean> independentRequests=new CopyOnWriteArrayList<>();
        RecordingVisual(boolean compact){this.compact=compact;}
        public boolean enabled(){return true;}
        public boolean compactStructuredOutputEnabled(){return compact;}
        public String identity(){return compact?"compact-scheduling-fixture-v1":"legacy-scheduling-fixture";}
        public Map<String,Object> extractContext(Document doc,Path path,List<Integer> input,BooleanSupplier permit,String scope){return respond(doc,input,permit,false);}
        public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> input,BooleanSupplier permit,String scope){return respond(doc,input,permit,true);}
        private Map<String,Object> respond(Document doc,List<Integer> input,BooleanSupplier permit,boolean independent){
            assertTrue(permit.getAsBoolean());requests.add(input);independentRequests.add(independent);
            List<Map<String,Object>> fields=input.stream().map(p->{
                Page page=doc.pages().stream().filter(x->x.number()==p).findFirst().orElseThrow();
                String label=page.text().contains("合同编号：")?"合同编号":"项目名称";
                String quote=page.text().lines().filter(line->line.startsWith(label+"：")).findFirst().orElseThrow();
                return Map.<String,Object>of("sourcePage",p,"type","FIELD","label",label,"value",quote.substring((label+"：").length()),"sourceQuote",quote,"readability","CLEAR",
                    "source",Map.of("documentId",doc.id(),"projectId",doc.projectId(),"documentHash",doc.sha256(),"documentVersion",doc.version(),"page",p));
            }).toList();
            Map<String,Object> out=new LinkedHashMap<>(Map.of("status","OBSERVED","errorCode","","independentPages",independent,"observations",fields,
                "pageResults",input.stream().map(p->Map.of("page",p,"status","COMPLETED","observations",fields.stream().filter(f->f.get("sourcePage").equals(p)).toList())).toList()));
            out.put("factBatch",StructuredContextFacts.batch(doc,input,out,identity()));return out;
        }
    }
}

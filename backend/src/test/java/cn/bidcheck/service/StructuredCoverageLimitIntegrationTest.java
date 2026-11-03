package cn.bidcheck.service;

import cn.bidcheck.document.OcrUnitObserver;
import cn.bidcheck.document.PageClassifier;
import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.verification.facts.FactLedgerService;
import cn.bidcheck.verification.source.StructuredContextFacts;
import cn.bidcheck.verification.source.VisualVerificationService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:structured-coverage-limit;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.visual.enabled=false"})
class StructuredCoverageLimitIntegrationTest {
    @Autowired Store store;
    @Autowired PlatformTransactionManager manager;
    @Autowired FactLedgerService ledger;
    @TempDir Path temporary;

    @Test void limitedFieldsKeepValidFactsButCannotCertifyCompleteStructure()throws Exception {
        Document doc=document(2);cn.bidcheck.service.StructuredCoverageLimitIntegrationTest.Fixture visual=new Fixture(true,false);
        process(doc,visual);Map<String,Object> summary=summary(doc);
        assertEquals(2,number(summary.get("contextLimitedPageCount")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 2),summary.get("contextLimitedPages"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELDS_REMAINING"),summary.get("contextCoverageLimitations"));
        assertEquals(0,number(summary.get("contextPending")));
        assertEquals(2,number(summary.get("contextReviewRequired")));
        assertEquals(0,number(summary.get("contextCompleted")));
        assertEquals("REVIEW_REQUIRED",summary.get("status"));
        for(java.util.Map<java.lang.String, java.lang.Object> window:store.maps("processing_window",doc.projectId(),doc.id())) {
            Map<String,Object> receipt=object(window.get("factResult"));
            assertEquals(1,number(receipt.get("accepted")));
            assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt.get("rejected"));
            assertEquals("REVIEW_REQUIRED",window.get("status"));
        }
    }

    @Test void limitedSuccessfulSourceAndReviewScopeSurviveResumeWithoutNewCalls()throws Exception {
        Document doc=document(1);cn.bidcheck.service.StructuredCoverageLimitIntegrationTest.Fixture visual=new Fixture(true,false);process(doc,visual);
        String scope=String.valueOf(summary(doc).get("scope"));process(doc,visual);
        assertEquals(1,visual.calls.get());assertEquals(scope,summary(doc).get("scope"));
        assertEquals(1,number(summary(doc).get("contextLimitedPageCount")));
        assertEquals(1,number(summary(doc).get("contextReviewRequired")));
        assertEquals(1,number(object(store.maps("processing_window",doc.projectId(),doc.id()).get(0).get("factResult")).get("accepted")));
    }

    @Test void emptyLimitedResponseRemainsReviewAndNoFactsInsteadOfSuccessfulStructure()throws Exception {
        Document doc=document(1);cn.bidcheck.service.StructuredCoverageLimitIntegrationTest.Fixture visual=new Fixture(true,true);process(doc,visual);
        assertEquals(1,number(summary(doc).get("contextLimitedPageCount")));
        assertEquals(1,number(summary(doc).get("contextNoFactPageCount")));
        assertEquals(1,number(summary(doc).get("contextReviewRequired")));
        assertEquals(0,number(summary(doc).get("contextCompleted")));
        assertEquals("REVIEW_REQUIRED",summary(doc).get("status"));
    }

    @Test void spanOnlyConfigurationKeepsDensePagesSeparateWithoutChangingLegacyScope()throws Exception {
        Document doc=document(3);cn.bidcheck.service.StructuredCoverageLimitIntegrationTest.Fixture visual=new Fixture(false,false);process(doc,visual);
        assertEquals(3,visual.calls.get());
        assertEquals(0,number(summary(doc).get("contextLimitedPageCount")));
        assertEquals(3,number(summary(doc).get("contextCompleted")));
        assertTrue(store.maps("processing_window",doc.projectId(),doc.id()).stream().allMatch(w->((List<?>)w.get("pages")).size()==1));
    }

    @Test void mixedFieldRejectionRetainsGoodFactAndAuditAcrossResume()throws Exception {
        Document doc=document(1);cn.bidcheck.service.StructuredCoverageLimitIntegrationTest.Fixture visual=new Fixture(true,false,true);process(doc,visual);process(doc,visual);
        assertEquals(1,visual.calls.get());
        Map<String,Object> window=store.maps("processing_window",doc.projectId(),doc.id()).get(0);
        assertEquals(1,number(object(window.get("factResult")).get("accepted")));
        assertEquals("REVIEW_REQUIRED",window.get("status"));
        assertEquals(0,number(summary(doc).get("contextCompleted")));
        assertEquals(0,number(summary(doc).get("contextPending")));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELDS_REJECTED"),summary(doc).get("contextCoverageLimitations"));
        Map<String,Object> response=object(window.get("response"));
        assertEquals(1,((List<?>)object(response.get("factBatch")).get("rejected")).size());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", 1, "inputIndex", 1, "code", "VISUAL_SOURCE_SPAN_ENTITY_TYPE_INVALID")),response.get("rejectedFields"));
    }

    @Test void allRejectedFieldsAreIncompleteAndNeverACompletedEmptyPage()throws Exception {
        Document doc=document(1);cn.bidcheck.service.StructuredCoverageLimitIntegrationTest.Fixture visual=new Fixture(true,true,true);process(doc,visual);
        Map<String,Object> window=store.maps("processing_window",doc.projectId(),doc.id()).get(0);
        assertEquals(1,visual.calls.get(),"All rejected fields must not trigger an automatic identical retry");
        assertEquals("VISUAL_SOURCE_SPAN_FIELDS_REJECTED",window.get("status"));
        assertEquals(1,number(summary(doc).get("contextPending")));
        assertEquals(0,number(summary(doc).get("contextCompleted")));
        assertEquals(0,number(summary(doc).get("contextNoFactPageCount")));
        assertEquals(1,number(summary(doc).get("contextLimitedPageCount")));
        assertFalse(object(window.get("response")).isEmpty());
        assertEquals(1,((List<?>)object(object(window.get("response")).get("factBatch")).get("rejected")).size());
    }

    private void process(Document doc,Fixture visual)throws Exception {
        store.save("project",doc.projectId(),doc.projectId(),"",cn.bidcheck.compat.Java8Api.mapOf("id", doc.projectId()));
        Document metadata=new Document(doc.id(),doc.projectId(),doc.role(),doc.name(),1,doc.sha256(),doc.createdAt(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.service.DocumentProcessingSession session=new DocumentProcessingSession(store,new TransactionTemplate(manager),()->{},OcrUnitObserver.noop(),ledger,metadata,Store.id(),"processing-"+visual.identity(),cn.bidcheck.compat.Java8Api.listOf());
        for(Page page:doc.pages())session.onPage(page,PageClassifier.nativeOnly(page.text(),1));
        session.finish(doc,temporary.resolve("unused.pdf"),visual,1,true,128);
    }
    private Map<String,Object> summary(Document doc){return store.getMap("processing_summary",DocumentProcessingSession.summaryId(doc.id()));}
    private static Document document(int count){
        List<Page> pages=new ArrayList<>();
        for(int p=1;p<=count;p++)pages.add(new Page(p,"报价表\n合同编号：LIMIT-"+p+"\n金额：100.00元","PAGE","QWEN_PDF_OCR",""));
        return new Document(Store.id(),Store.id(),"BID","fixture.pdf",1,cn.bidcheck.compat.Java8Api.repeat("a1", 32),"2026-10-15T00:00:00Z",pages,cn.bidcheck.compat.Java8Api.listOf());
    }
    private static int number(Object value){return ((Number)value).intValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    private static class Fixture extends VisualVerificationService {
        final boolean limited,empty,rejected;final AtomicInteger calls=new AtomicInteger();
        Fixture(boolean limited,boolean empty){this(limited,empty,false);}
        Fixture(boolean limited,boolean empty,boolean rejected){this.limited=limited;this.empty=empty;this.rejected=rejected;}
        public boolean enabled(){return true;}
        public boolean compactStructuredOutputEnabled(){return false;}
        public boolean sourceSpanOutputEnabled(){return true;}
        public String identity(){return "span-coverage-fixture-"+limited+"-"+empty+"-"+rejected;}
        public Map<String,Object> extractContext(Document doc,Path path,List<Integer> pages,BooleanSupplier permit,String scope){return response(doc,pages,permit);}
        public Map<String,Object> extractIndependentPages(Document doc,Path path,List<Integer> pages,BooleanSupplier permit,String scope){return response(doc,pages,permit);}
        private Map<String,Object> response(Document doc,List<Integer> pages,BooleanSupplier permit){
            assertTrue(permit.getAsBoolean());calls.incrementAndGet();assertEquals(1,pages.size());int p=pages.get(0);
            String quote="合同编号：LIMIT-"+p;
            List<Map<String,Object>> fields=empty?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("sourcePage", p, "type", "FIELD", "label", "合同编号", "value", "LIMIT-"+p, "sourceQuote", quote, "readability", "CLEAR", "source", cn.bidcheck.compat.Java8Api.mapOf("documentId", doc.id(), "projectId", doc.projectId(), "documentHash", doc.sha256(), "documentVersion", 1, "page", p)));
            List<String> codes=rejected?cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELDS_REJECTED"):limited?cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELDS_REMAINING"):cn.bidcheck.compat.Java8Api.listOf();
            boolean allRejected=rejected&&empty;
            Map<String,Object> out=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("status", allRejected?"UNDETERMINED":"OBSERVED", "errorCode", allRejected?"VISUAL_SOURCE_SPAN_FIELDS_REJECTED":"", "observations", fields, "coverageLimitedPages", limited?pages:cn.bidcheck.compat.Java8Api.listOf(), "coverageLimitations", codes, "pageResults", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", p, "status", allRejected?"FAILED":"COMPLETED", "observations", fields, "coverageLimitations", codes))));
            Map<String,Object> batch=new LinkedHashMap<>(StructuredContextFacts.batch(doc,pages,out,identity()));
            if(rejected){
                List<Map<String,Object>> refusals=cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("page", p, "inputIndex", 1, "code", "VISUAL_SOURCE_SPAN_ENTITY_TYPE_INVALID"));
                out.put("rejectedFields",refusals);batch.put("rejected",refusals);
            }
            out.put("factBatch",batch);return out;
        }
    }
}

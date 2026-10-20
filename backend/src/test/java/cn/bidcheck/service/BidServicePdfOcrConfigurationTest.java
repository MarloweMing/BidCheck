package cn.bidcheck.service;

import cn.bidcheck.ocr.*;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.semantic.SemanticChecker;
import cn.bidcheck.storage.ArtifactStorage;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BidServicePdfOcrConfigurationTest {
    @Test void enabledPdfExposesBothModelsAndBindsPdfSingleAndBudgetIdentities() {
        var base = service("pdf-a", "single-a", 1000);
        assertTrue(base.ocrEnabled()); assertTrue(base.pdfOcrEnabled());
        assertEquals("pdf-a", base.pdfOcrModel()); assertEquals(10, base.pdfOcrBatchPages()); assertEquals(5, base.pdfOcrConcurrency());
        assertTrue(base.ocrModel().contains("pdf-a")); assertTrue(base.ocrModel().contains("single-a"));
        assertNotEquals(base.ocrPreparationIdentity(), service("pdf-b", "single-a", 1000).ocrPreparationIdentity());
        assertNotEquals(base.ocrPreparationIdentity(), service("pdf-a", "single-b", 1000).ocrPreparationIdentity());
        assertNotEquals(base.ocrPreparationIdentity(), service("pdf-a", "single-a", 999).ocrPreparationIdentity());
    }
    @Test void legacyConstructorKeepsPdfDisabled() {
        BidService service = new BidService(mock(Store.class), mock(PlatformTransactionManager.class), mock(SemanticChecker.class), mock(ArtifactStorage.class), single("old"));
        assertTrue(service.ocrEnabled()); assertFalse(service.pdfOcrEnabled()); assertEquals("old", service.ocrModel());
    }
    private static BidService service(String pdfModel, String singleModel, int maximum) {
        PdfOcrClient pdf = new PdfOcrClient() {
            public boolean enabled() { return true; } public String modelName() { return pdfModel; } public String identity() { return pdfModel; }
            public BatchResult recognizePdf(byte[] pdf, List<Integer> pages) { throw new AssertionError("configuration test must not call cloud"); }
        };
        return new BidService(mock(Store.class), mock(PlatformTransactionManager.class), mock(SemanticChecker.class), mock(ArtifactStorage.class),
                single(singleModel), 100, 4, null, pdf, maximum, 67108864, 16777216);
    }
    private static OcrClient single(String model) {
        return new OcrClient() {
            public boolean enabled() { return true; } public String modelName() { return model; } public String identity() { return model; }
            public OcrResult recognize(byte[] bytes, String mime) throws IOException { throw new AssertionError("configuration test must not call cloud"); }
        };
    }
}

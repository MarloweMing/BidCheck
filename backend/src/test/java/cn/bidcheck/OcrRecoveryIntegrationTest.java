package cn.bidcheck;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.ocr.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ocr-recovery;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000","bidcheck.ocr.max-images=2","bidcheck.ocr.concurrency=1"})
@AutoConfigureMockMvc
class OcrRecoveryIntegrationTest {
    static final Path DATA;
    static {try{DATA=Files.createTempDirectory("bidcheck-ocr-recovery-");}catch(IOException e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;@Autowired MockMvc mvc;
    @MockitoBean OcrClient ocr;
    @BeforeEach void setup()throws Exception {
        when(ocr.enabled()).thenReturn(true);when(ocr.identity()).thenReturn("recovery-test-v1");when(ocr.modelName()).thenReturn("offline-test");
        when(ocr.recognize(any(byte[].class),anyString())).thenReturn(new OcrResult("营业执照：测试企业\n投标有效期：90天","TEST","offline-test",false));
    }
    TaskRow upload(int pages)throws Exception {
        String project=(String)service.createProject(Map.of("name","逐页恢复测试")).get("id");
        return service.upload(project,"TENDER",new MockMultipartFile("file","scan.pdf","application/pdf",pdf(pages)));
    }
    String documentId(TaskRow task){return (String)store.map(task.inputJson).get("documentId");}
    @Test void continuingBudgetProcessesNewPositionsAndKeepsOldEvidenceVersion()throws Exception {
        TaskRow first=upload(4);service.execute(first);Document original=service.document(documentId(first));
        verify(ocr,times(2)).recognize(any(byte[].class),anyString());
        assertThat(original.pages()).hasSize(4);assertThat(original.warnings()).anyMatch(w->w.contains("[OCR未完成]"));
        TaskRow continuation=service.recognizeRemaining(original.id());service.execute(continuation);
        Document next=service.document(documentId(continuation));
        verify(ocr,times(4)).recognize(any(byte[].class),anyString());
        assertThat(next.id()).isNotEqualTo(original.id());assertThat(next.sha256()).isEqualTo(original.sha256());
        assertThat(next.pages()).hasSize(4);assertThat(next.warnings()).noneMatch(w->w.contains("[OCR未完成]"));
        assertThat(service.document(original.id())).isEqualTo(original);
        assertThat(service.ocrProgress(next.id())).containsEntry("cachedUnitCount",2);
        mvc.perform(get("/api/documents/"+next.id()+"/ocr-progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.counts.SUCCEEDED").value(4)).andExpect(jsonPath("$.units[0].text").doesNotExist())
            .andExpect(jsonPath("$.units[0].result").doesNotExist());
        mvc.perform(post("/api/documents/"+next.id()+"/ocr-continue")).andExpect(status().isBadRequest());
    }
    @Test void interruptedParseCommitsFinishedUnitAndRetriesOnlyUnfinishedWork()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        when(ocr.recognize(any(byte[].class),anyString())).thenAnswer(call->{if(calls.incrementAndGet()==2)throw new InterruptedIOException("test interruption");
            return new OcrResult("测试证书：已识别的正文","TEST","offline-test",false);});
        TaskRow first=upload(2);
        try{assertThatThrownBy(()->service.execute(first)).isInstanceOf(IOException.class);}finally{Thread.interrupted();}
        String id=documentId(first);assertThat(store.mapper().get(id)).isNull();
        assertThat(((Map<?,?>)service.ocrProgress(id).get("counts")).get("SUCCEEDED")).isEqualTo(1);
        service.fail(first,"模拟处理中断");TaskRow retry=service.retry(first.id);service.execute(retry);
        assertThat(calls.get()).isEqualTo(3);assertThat(service.document(id).pages()).hasSize(2);
        assertThat(service.ocrProgress(id)).containsEntry("cachedUnitCount",1);
        assertThat(service.publicTask(service.task(first.id))).containsKey("ocrProgress");
    }
    @Test void aFailedPageDoesNotDiscardSuccessfulPagesOrPreventTargetedContinuation()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        when(ocr.recognize(any(byte[].class),anyString())).thenAnswer(call->{if(calls.incrementAndGet()==2)throw new IOException("temporary offline failure");
            return new OcrResult("测试证照：可靠可追溯正文","TEST","offline-test",false);});
        TaskRow first=upload(2);service.execute(first);String id=documentId(first);
        assertThat(((Map<?,?>)service.ocrProgress(id).get("counts")).get("FAILED")).isEqualTo(1);
        TaskRow continuation=service.recognizeRemaining(id);service.execute(continuation);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(service.document(documentId(continuation)).warnings()).noneMatch(w->w.contains("[OCR未完成]"));
        assertThat(service.document(id).warnings()).anyMatch(w->w.contains("[OCR未完成]"));
    }
    static byte[] pdf(int pages)throws Exception {
        try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) {
                PDPage page=new PDPage();document.addPage(page);BufferedImage image=new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,80,60);g.setColor(new Color(20+i*30,40,70));g.fillRect(5+i,5,50,40);g.dispose();
                try(PDPageContentStream stream=new PDPageContentStream(document,page)){stream.drawImage(LosslessFactory.createFromImage(document,image),40,300,400,300);}finally{image.flush();}
            }
            document.save(out);return out.toByteArray();
        }
    }
}

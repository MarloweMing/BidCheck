package cn.bidcheck;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.ocr.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ocr-workflow;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000"})
@AutoConfigureMockMvc
class OcrWorkflowIntegrationTest {
    private static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bidcheck-ocr-workflow-"); } catch(IOException e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { p.add("bidcheck.data",()->DATA.toString()); }
    @Autowired BidService service;
    @Autowired Store store;
    @Autowired MockMvc mvc;
    @MockBean OcrClient ocr;

    @BeforeEach void setup() throws Exception {
        when(ocr.enabled()).thenReturn(true); when(ocr.modelName()).thenReturn("test-ocr"); when(ocr.identity()).thenReturn("ocr-test-v1");
        when(ocr.recognize(any(byte[].class),anyString())).thenReturn(new OcrResult("项目编号：OCR-001\n投标有效期：60天","DASHSCOPE","test-ocr",false));
    }
    private String project() { return (String)service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "OCR回归")).get("id"); }
    private byte[] png() throws Exception {
        BufferedImage image=new BufferedImage(200,160,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(image,"PNG",bytes);return bytes.toByteArray();
    }
    private Document image(String project) throws Exception {
        TaskRow task=service.upload(project,"BID",new MockMultipartFile("file","scan.png","image/png",png()));service.execute(task);
        return service.document((String)store.map(task.inputJson).get("documentId"));
    }
    @Test void imageIsRecognizedIndexedAndCanBeComparedToOriginal() throws Exception {
        Document document=image(project());
        assertThat(document.pages()).hasSize(1);
        assertThat(document.pages().get(0).parser()).contains("OCR");
        assertThat(service.facts(document.id())).anyMatch(f->"bidValidity".equals(f.field())&&"60".equals(f.normalizedValue()));
        mvc.perform(get("/api/documents/"+document.id()+"/pages/1/image")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.ocrEnabled").value(true)).andExpect(jsonPath("$.ocrModel").value("test-ocr"));
        assertThat(store.documentSummaries(document.projectId()).get(0)).containsEntry("ocrPages",1).containsEntry("ocrIncomplete",false);
    }
    @Test void repeatedRecognitionCreatesOnePendingVersionAndPreservesOldEvidence() throws Exception {
        String p=project(); when(ocr.enabled()).thenReturn(false); Document old=image(p);
        when(ocr.enabled()).thenReturn(true);
        TaskRow next=service.recognizeAgain(old.id()),duplicate=service.recognizeAgain(old.id());
        assertThat(next.id).isEqualTo(duplicate.id);service.execute(next);
        Document updated=service.document((String)store.map(next.inputJson).get("documentId"));
        assertThat(updated.id()).isNotEqualTo(old.id());assertThat(updated.version()).isEqualTo(old.version()+1);
        assertThat(updated.sha256()).isEqualTo(old.sha256());assertThat(service.document(old.id())).isEqualTo(old);
        assertThat(updated.pages().get(0).text()).contains("OCR-001");
        assertThat(Files.readAllBytes(service.path(updated.id()))).isEqualTo(Files.readAllBytes(service.path(old.id())));
    }
    @Test void ocrFailureLeavesExplicitlyIncompleteDocumentWithoutFalseFacts() throws Exception {
        when(ocr.recognize(any(byte[].class),anyString())).thenThrow(new IOException("OCR服务暂不可用"));
        Document document=image(project());
        assertThat(document.warnings()).anyMatch(w->w.contains("[OCR未完成]"));
        assertThat(service.facts(document.id())).isEmpty();
        assertThat(store.documentSummaries(document.projectId()).get(0)).containsEntry("ocrIncomplete",true);
    }
    @Test void disabledOcrCannotCreateReprocessingTask() throws Exception {
        Document d=image(project()); when(ocr.enabled()).thenReturn(false);
        mvc.perform(post("/api/documents/"+d.id()+"/ocr")).andExpect(status().isBadRequest());
    }
    @Test void ocrNumericConflictRemainsReviewableAndReportKeepsProvenance() throws Exception {
        String p=project();
        TaskRow upload=service.upload(p,"TENDER",new MockMultipartFile("file","tender.txt","text/plain","投标有效期：不少于90天".getBytes(StandardCharsets.UTF_8)));
        service.execute(upload);String tender=(String)store.map(upload.inputJson).get("documentId");service.execute(service.extract(p,tender));
        for(Checkpoint c:store.list("checkpoint",p,tender,Checkpoint.class)) service.updateCheckpoint(c.id(),new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),c.expected(),c.unit(),c.required(),true,c.enabled(),c.source()));
        Document bid=image(p);Map<String,Object> run=service.startRun(p,bid.id());service.execute(service.task((String)run.get("taskId")));
        List<Finding> findings=store.list("finding",p,(String)run.get("id"),Finding.class);
        Finding validity=findings.stream().filter(f->"bidValidity".equals(f.checkpoint().field())).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertThat(validity.evaluation().status()).isEqualTo("FAIL");
        assertThat(validity.evaluation().method()).doesNotContain("OCR_REVIEW");
        assertThat(validity.assessment()).containsEntry("policyVersion","evidence-review-v3");
        assertThat(((Map<?,?>)validity.assessment().get("ocrReview")).get("required")).isEqualTo(true);
        assertThat(((Map<?,?>)validity.assessment().get("rawEvaluation")).get("status")).isEqualTo("FAIL");
        assertThat((List<?>)validity.assessment().get("adjustments")).isEmpty();
        assertThat(validity.evaluation().evidences()).anyMatch(e->e.documentId().equals(bid.id())&&e.page()==1);
        mvc.perform(get("/api/runs/"+run.get("id")+"/report").param("format","json")).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.containsString("OCR")));
        TaskRow reprocess=service.recognizeAgain(bid.id());service.execute(reprocess);
        String nextDocument=(String)store.map(reprocess.inputJson).get("documentId");
        Map<String,Object> nextRun=service.startRun(p,nextDocument);
        assertThat(nextRun.get("id")).isNotEqualTo(run.get("id"));
        assertThat(nextRun.get("documentId")).isEqualTo(nextDocument);
        assertThat(store.getMap("run",(String)run.get("id")).get("documentId")).isEqualTo(bid.id());
    }
}

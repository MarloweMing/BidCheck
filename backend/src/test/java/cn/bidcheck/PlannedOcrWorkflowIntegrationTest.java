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
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:planned-workflow;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000"})
class PlannedOcrWorkflowIntegrationTest {
    static final Path DATA;
    static {try{DATA=Files.createTempDirectory("bidcheck-planned-workflow-");}catch(IOException ex){throw new IllegalStateException(ex);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;@Autowired Store store;@Autowired JdbcTemplate jdbc;
    @MockBean OcrClient ocr;
    @BeforeEach void setup()throws Exception {
        when(ocr.enabled()).thenReturn(true);when(ocr.identity()).thenReturn("planned-ocr-test");when(ocr.modelName()).thenReturn("offline-test");
        when(ocr.recognize(any(byte[].class),anyString())).thenReturn(new OcrResult("SocialSecurity evidence\n项目编号：PLAN001\n投标有效期：90天","TEST","offline-test",false));
    }
    String project(){return (String)service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "计划识别回归")).get("id");}
    byte[] pdf()throws Exception {
        try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            String[] titles={"1 Finance","Finance continuation","2 OtherMaterial","3 SocialSecurity","Continuation","4 TechnicalPlan"};
            for(String title:titles){PDPage p=new PDPage();pdf.addPage(p);
                try(PDPageContentStream c=new PDPageContentStream(pdf,p)){
                    c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),16);c.newLineAtOffset(40,700);c.showText(title);c.endText();
                    BufferedImage image=new BufferedImage(40,30,BufferedImage.TYPE_INT_RGB);java.awt.Graphics2D g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,40,30);g.setColor(java.awt.Color.BLACK);g.drawString("IMG",3,17);g.dispose();
                    c.drawImage(LosslessFactory.createFromImage(pdf,image),40,350,300,220);image.flush();
                }
            }pdf.save(out);return out.toByteArray();
        }
    }
    Document upload(String p,byte[] bytes)throws Exception {
        TaskRow task=service.upload(p,"BID",new MockMultipartFile("file","scan.pdf","application/pdf",bytes));service.execute(task);
        return service.document((String)store.map(task.inputJson).get("documentId"));
    }
    Checkpoint confirm(String p)throws Exception {
        TaskRow tenderTask=service.upload(p,"TENDER",new MockMultipartFile("file","tender.txt","text/plain","须提供相关证明。".getBytes(StandardCharsets.UTF_8)));service.execute(tenderTask);
        String tender=(String)store.map(tenderTask.inputJson).get("documentId");service.execute(service.extract(p,tender));
        for(Checkpoint old:store.list("checkpoint",p,tender,Checkpoint.class))service.updateCheckpoint(old.id(),new Checkpoint(old.id(),old.title(),old.category(),old.severity(),old.type(),old.field(),old.operator(),old.expected(),old.unit(),old.required(),true,false,old.source()));
        Checkpoint c=new Checkpoint(Store.id(),"SocialSecurity","QUALIFICATION","HIGH","MATERIAL_EXISTENCE","material","exists","SocialSecurity","",true,true,true,new Evidence(tender,"tender.txt",1,"PARAGRAPH","须提供相关证明。","text",1));
        store.save("checkpoint",c.id(),p,tender,c);return c;
    }
    @Test void unconfirmedChecksStillRecognizeWholeFileForPersonnelFacts()throws Exception {
        Document doc=upload(project(),pdf());
        verify(ocr,times(6)).recognize(any(byte[].class),anyString());
        assertThat(doc.pages()).hasSize(6);assertThat(doc.pages()).noneMatch(page->Objects.toString(page.warning(),"").contains("[OCR_DEFERRED]"));
        assertThat(service.ocrPlan(doc.id())).containsEntry("mode","FULL");
    }
    @Test void confirmedChecksDoNotExcludeUnrelatedCredentialPages()throws Exception {
        String p=project();confirm(p);Document doc=upload(p,pdf());
        long recognized=doc.pages().stream().filter(page->page.parser().contains("OCR")).count();
        assertThat(recognized).isEqualTo(6);verify(ocr,times(6)).recognize(any(byte[].class),anyString());
        assertThat(doc.pages().get(3).text()).contains("PLAN001");assertThat(doc.pages().get(4).text()).contains("PLAN001");
        assertThat(doc.pages().get(5).text()).contains("PLAN001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qc_fact_occurrence WHERE project_id=? AND source_domain='NATIVE_FACT'",Long.class,p)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qc_record WHERE project_id=? AND kind='fact'",Long.class,p)).isZero();
    }
    @Test void laterConfirmedPlanCreatesNewImmutableEvidenceVersionBeforeCheck()throws Exception {
        String p=project();Document parsed=upload(p,pdf());
        List<Page> deferred=cn.bidcheck.compat.Java8Api.toList(parsed.pages().stream().map(page->new Page(page.number(),"",page.locationType(),"PDFBOX","[OCR_DEFERRED] [OCR未完成] 历史版本未识别")));
        Document original=new Document(parsed.id(),p,parsed.role(),parsed.name(),parsed.version(),parsed.sha256(),parsed.createdAt(),deferred,cn.bidcheck.compat.Java8Api.listOf("[OCR_DEFERRED] 历史版本"));
        store.save("document",original.id(),p,"",original);
        store.save("ocr_plan","ocr-plan:"+original.id(),p,original.id(),cn.bidcheck.compat.Java8Api.mapOf("mode", "NATIVE_ONLY", "requestedPlanHash", "historical-native-plan", "preparationIdentity", "historical"));
        clearInvocations(ocr);confirm(p);
        Map<String,Object> run=service.startRun(p,original.id());service.execute(service.task((String)run.get("taskId")));
        Map<String,Object> finished=store.getMap("run",(String)run.get("id"));Document prepared=service.document((String)finished.get("documentId"));
        assertThat(prepared.id()).isNotEqualTo(original.id());assertThat(prepared.version()).isGreaterThan(original.version());assertThat(prepared.sha256()).isEqualTo(original.sha256());
        assertThat(finished).containsEntry("inputDocumentId",original.id()).containsEntry("ocrPreparationComplete",true);
        assertThat(service.document(original.id())).isEqualTo(original);assertThat(Files.readAllBytes(service.path(prepared.id()))).isEqualTo(Files.readAllBytes(service.path(original.id())));
        assertThat(prepared.pages()).allMatch(page->page.text().contains("PLAN001")&&!Objects.toString(page.warning(),"").contains("[OCR_DEFERRED]"));
        // Validated OCR cache from the same immutable source is reusable across the new complete document version.
        verify(ocr,never()).recognize(any(byte[].class),anyString());
    }
    @Test void changedChecksReuseAlreadyCompleteDocument()throws Exception {
        String p=project();byte[] bytes=pdf();Document nativeOnly=upload(p,bytes);confirm(p);Document selected=upload(p,bytes);
        assertThat(selected.id()).isEqualTo(nativeOnly.id());assertThat(selected.version()).isEqualTo(nativeOnly.version());
        assertThat(service.document(nativeOnly.id())).isEqualTo(nativeOnly);
    }
    @Test void changedOcrIdentityDoesNotHitOldUploadInsideTransaction()throws Exception {
        String p=project();byte[] bytes=pdf();Document old=upload(p,bytes);
        when(ocr.identity()).thenReturn("planned-ocr-test-new");Document fresh=upload(p,bytes);
        assertThat(fresh.id()).isNotEqualTo(old.id());assertThat(fresh.version()).isGreaterThan(old.version());
    }
    @Test void uploadsRespectPendingPreparationVersionReservations()throws Exception {
        String p=project();store.save("ocr_preparation","reserved-test:"+p,p,"",cn.bidcheck.compat.Java8Api.mapOf("role", "BID", "version", 10));
        assertThat(upload(p,pdf()).version()).isEqualTo(11);
    }
    @Test void queuedCheckRejectsChangedOcrConfigurationBeforeCallingOcr()throws Exception {
        String p=project();Document doc=upload(p,pdf());confirm(p);Map<String,Object> run=service.startRun(p,doc.id());
        clearInvocations(ocr);when(ocr.identity()).thenReturn("changed-after-queue");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.execute(service.task((String)run.get("taskId"))));
        verify(ocr,never()).recognize(any(byte[].class),anyString());
    }
    @Test void projectFactMigrationUsesDurableTaskAndCanBeRepeatedWithoutDuplicates()throws Exception {
        String p=project();Document doc=upload(p,pdf());clearInvocations(ocr);
        TaskRow migration=service.migrateProjectFacts(p);assertThat(service.migrateProjectFacts(p).id).isEqualTo(migration.id);service.execute(migration);
        assertThat(service.task(migration.id).status).isEqualTo("SUCCEEDED");
        assertThat(service.factMigrations(p)).hasSize(1).first().extracting(m->m.get("status")).isEqualTo("COMPLETED");
        Long before=jdbc.queryForObject("SELECT COUNT(*) FROM qc_fact_occurrence WHERE project_id=?",Long.class,p);
        service.execute(service.migrateProjectFacts(p));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qc_fact_occurrence WHERE project_id=?",Long.class,p)).isEqualTo(before);
        assertThat(service.document(doc.id())).isEqualTo(doc);verify(ocr,never()).recognize(any(byte[].class),anyString());
    }
}

package cn.bidcheck;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:check-renewal;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000"})
@AutoConfigureMockMvc
class CheckRenewalIntegrationTest {
    private static final Path DATA;
    static {try {DATA=Files.createTempDirectory("bidcheck-renewal-");} catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bidcheck.data",()->DATA.toString());}
    @Autowired BidService service;
    @Autowired Store store;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String project(){return service.createProject(cn.bidcheck.compat.Java8Api.mapOf("name", "待解析项目")).get("id").toString();}
    TaskRow upload(String project,String role,String content) throws Exception {
        return service.upload(project,role,new MockMultipartFile("file","需求样例.txt","text/plain",content.getBytes(StandardCharsets.UTF_8)));
    }
    String parse(String project,String role,String content) throws Exception {
        TaskRow task=upload(project,role,content);service.execute(task);return store.map(task.inputJson).get("documentId").toString();
    }
    void confirm(String project){for(Checkpoint c:(List<Checkpoint>)service.detail(project).get("checkpoints"))service.updateCheckpoint(c.id(),new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),c.expected(),c.unit(),c.required(),true,c.enabled(),c.source()));}

    @Test void extractsProjectFieldsAndPreservesManualCorrectionsAcrossVersions() throws Exception {
        String p=project();String first=parse(p,"TENDER","项目名称：数智平台采购\n项目编号：ZB-2026-01\n开标时间：2026年11月3日 9时30分\n投标有效期：90天");
        Map<String,Object> info=store.getMap("project",p);
        assertThat(info).containsEntry("name","数智平台采购").containsEntry("projectNumber","ZB-2026-01").containsEntry("openingTime","2026-11-03T09:30");
        assertThat((Map<?,?>)info.get("fieldSources")).hasSize(3);
        mvc.perform(put("/api/projects/"+p).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"人工核定项目\",\"openingTime\":\"2026-11-04T10:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("manualFields[0]").value("name"));
        String second=parse(p,"TENDER","项目名称：新版本名称\n项目编号：ZB-2026-02\n开标时间：2026-11-05 14:00");
        service.execute(service.extract(p,second));
        info=store.getMap("project",p);
        assertThat(info).containsEntry("name","人工核定项目").containsEntry("projectNumber","ZB-2026-02").containsEntry("openingTime","2026-11-04T10:00");
        service.execute(service.extract(p,first));
        assertThat(store.getMap("project",p)).containsEntry("projectNumber","ZB-2026-01").containsEntry("name","人工核定项目");
        mvc.perform(put("/api/projects/"+p).contentType(MediaType.APPLICATION_JSON).content("{\"openingTime\":\"2026-02-30\"}")).andExpect(status().isBadRequest());
        assertThat(store.getMap("project",p)).containsEntry("openingTime","2026-11-04T10:00");
    }

    @Test void outOfOrderParsesDoNotReplaceNewerInformationAndMissingValuesRemainEditable() throws Exception {
        String p=project();TaskRow old=upload(p,"TENDER","项目名称：旧版本\n项目编号：OLD");TaskRow latest=upload(p,"TENDER","项目名称：新版本\n项目编号：NEW");
        service.execute(latest);service.execute(old);
        assertThat(store.getMap("project",p)).containsEntry("name","新版本").containsEntry("projectNumber","NEW").containsEntry("openingTime","");
        assertThat(((Map<?,?>)store.getMap("project",p).get("fieldWarnings")).containsKey("openingTime")).isTrue();
        assertThat(service.publicTask(old)).containsEntry("fileName","需求样例.txt").containsEntry("role","TENDER");
        service.updateProject(p,cn.bidcheck.compat.Java8Api.mapOf("openingTime", "2026-12-01"));
        assertThat(store.getMap("project",p)).containsEntry("openingTime","2026-12-01");
    }

    @Test void conflictingSourceValuesAreNotSilentlyChosen() throws Exception {
        String p=project();parse(p,"TENDER","项目编号：A-01\n项目编号：B-02\n开标时间：2026-12-01\n开标时间：2026-12-02");
        assertThat(store.getMap("project",p)).containsEntry("projectNumber","").containsEntry("openingTime","");
        Map<?,?> warnings=(Map<?,?>)store.getMap("project",p).get("fieldWarnings");
        assertThat(warnings.get("projectNumber").toString()).contains("多个值");
    }

    @Test void manualCrudIsProjectScopedAndDeletedRulesStayOutOfReextractionWhileSnapshotSurvives() throws Exception {
        String p=project();String tender=parse(p,"TENDER","项目编号：CRUD-01\n投标有效期：90天");service.execute(service.extract(p,tender));
        String response=mvc.perform(post("/api/projects/"+p+"/checkpoints").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"提供人员保障方案\",\"category\":\"响应检查\",\"expected\":\"人员稳定措施、补充及替换方式\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Checkpoint added=json.readValue(response,Checkpoint.class);
        assertThat(added.source()).isNull();assertThat(added.confirmed()).isFalse();
        String bid=parse(p,"BID","项目编号：CRUD-01\n投标有效期：100天");confirm(p);
        Map<String,Object> run=service.startRun(p,bid);String runId=run.get("id").toString();
        Checkpoint extracted=((List<Checkpoint>)service.detail(p).get("checkpoints")).stream().filter(c->!c.id().equals(added.id())).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        mvc.perform(delete("/api/checkpoints/"+added.id())).andExpect(status().isOk());
        mvc.perform(delete("/api/checkpoints/"+extracted.id())).andExpect(status().isOk());
        service.execute(service.extract(p,tender));
        assertThat((List<Checkpoint>)service.detail(p).get("checkpoints")).noneMatch(c->cn.bidcheck.compat.Java8Api.setOf(added.id(), extracted.id()).contains(c.id()));
        assertThat(store.list("snapshot",p,runId,Checkpoint.class)).anyMatch(c->c.id().equals(added.id())).anyMatch(c->c.id().equals(extracted.id()));
        assertThat(store.list("snapshot",p,runId,Checkpoint.class).stream().filter(c->c.id().equals(added.id())).findFirst().orElseThrow(java.util.NoSuchElementException::new).confirmed()).isTrue();
        mvc.perform(put("/api/checkpoints/"+added.id()).contentType(MediaType.APPLICATION_JSON).content(response)).andExpect(status().isNotFound());
        String other=project();mvc.perform(post("/api/projects/"+other+"/checkpoints").contentType(MediaType.APPLICATION_JSON).content(response)).andExpect(status().isBadRequest());
    }

    @Test void openingDateChangesProduceNewFrozenRunAndDoNotChangeExistingRun() throws Exception {
        String p=project();String tender=parse(p,"TENDER","项目名称：效期核对\n开标时间：2026-11-03\n投标截止时间：2026-11-02");service.execute(service.extract(p,tender));
        Checkpoint c=service.createCheckpoint(p,cn.bidcheck.compat.Java8Api.mapOf("title", "签署日期不得晚于开标时间", "category", "全文信息检查", "type", "FULL_TEXT_REVIEW", "field", "signingAfterOpening", "operator", "REVIEW", "expected", "2026-11-03", "unit", "DATE"));
        String bid=parse(p,"BID","项目名称：效期核对\n签署日期：2026-11-04");confirm(p);
        String run1=service.startRun(p,bid).get("id").toString();service.updateProject(p,cn.bidcheck.compat.Java8Api.mapOf("openingTime", "2026-11-05"));
        String run2=service.startRun(p,bid).get("id").toString();assertThat(run2).isNotEqualTo(run1);
        assertThat(store.list("snapshot",p,run1,Checkpoint.class).stream().filter(x->x.id().equals(c.id())).findFirst().orElseThrow(java.util.NoSuchElementException::new).expected()).isEqualTo("2026-11-03");
        assertThat(store.list("snapshot",p,run2,Checkpoint.class).stream().filter(x->x.id().equals(c.id())).findFirst().orElseThrow(java.util.NoSuchElementException::new).expected()).isEqualTo("2026-11-05");
    }

    @Test void emptyParsedTextBlocksExtractionAndUploadLimitIsExposed() throws Exception {
        String p=project();String id=parse(p,"TENDER","\n\t\n");
        assertThat(store.documentSummaries(p).get(0)).containsEntry("readableText",false);
        assertThatThrownBy(()->service.extract(p,id)).hasMessageContaining("没有可读取文本");
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("maxDocumentsPerProject").value(100));
        mvc.perform(post("/api/projects/"+p+"/checkpoints").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"空项目不能加项\",\"category\":\"资格检查\",\"expected\":\"要求\"}")).andExpect(status().isBadRequest());
    }
}

package cn.bidcheck;

import cn.bidcheck.persistence.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
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
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:bidcheck-test;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
    private static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bidcheck-workflow-test-"); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("bidcheck.data",()->DATA.toString()); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordMapper mapper;
    JsonNode result(org.springframework.test.web.servlet.ResultActions call) throws Exception { return json.readTree(call.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    String create(String name) throws Exception { return result(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("name",name,"code","TEST-2026"))))).get("id").asText(); }
    void complete(String id) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).until(()-> {
            TaskRow t=mapper.task(id); if("FAILED".equals(t.status)) throw new AssertionError(t.error); return "SUCCEEDED".equals(t.status);
        });
    }
    String upload(String project,String role,String name,String text) throws Exception {
        JsonNode task=result(mvc.perform(multipart("/api/projects/"+project+"/documents").file(new MockMultipartFile("file",name,"text/plain",text.getBytes(StandardCharsets.UTF_8))).param("role",role)));
        complete(task.get("id").asText()); JsonNode detail=result(mvc.perform(get("/api/projects/"+project)));
        String id=""; for(JsonNode d:detail.get("documents")) if(role.equals(d.get("role").asText())) id=d.get("id").asText(); return id;
    }
    @Test void endToEndRequiresConfirmationAndKeepsEvidenceAndReviewHistory() throws Exception {
        String project=create("闭环验证");
        String tender=upload(project,"TENDER","招标.txt","项目编号：TEST-2026\n投标有效期不得少于90天。\n最高限价：200万元\n车辆信息表与行驶证车牌应保持一致。");
        String bid=upload(project,"BID","投标.txt","投标函\n项目编号：TEST-2026\n投标有效期：60天\n投标总价：230万元\n车辆信息表\n车牌号码：渝A12345\nVIN：LSVA12345678901234\f机动车行驶证\n号牌号码：渝A54321\nVIN：LSVA12345678901234");
        mvc.perform(post("/api/projects/"+project+"/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+bid+"\"}")).andExpect(status().isBadRequest());
        JsonNode task=result(mvc.perform(post("/api/projects/"+project+"/checkpoints/extract").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+tender+"\"}")));
        complete(task.get("id").asText());
        JsonNode detail=result(mvc.perform(get("/api/projects/"+project)));
        assertThat(detail.get("checkpoints").size()).isGreaterThan(0);
        mvc.perform(post("/api/projects/"+project+"/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+bid+"\"}")).andExpect(status().isBadRequest());
        for(JsonNode c:detail.get("checkpoints")) { ((com.fasterxml.jackson.databind.node.ObjectNode)c).put("confirmed",true); result(mvc.perform(put("/api/checkpoints/"+c.get("id").asText()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(c)))); }
        JsonNode run=result(mvc.perform(post("/api/projects/"+project+"/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+bid+"\"}")));
        complete(run.get("taskId").asText()); String runId=run.get("id").asText();
        JsonNode evaluated=result(mvc.perform(get("/api/runs/"+runId)));
        assertThat(evaluated.get("run").get("status").asText()).isEqualTo("COMPLETED");
        int failures=0; String resultId="";
        for(JsonNode finding:evaluated.get("results")) if("FAIL".equals(finding.get("evaluation").get("status").asText())) {
            failures++; resultId=finding.get("id").asText(); assertThat(finding.get("evaluation").get("evidences").size()).isGreaterThan(0);
        }
        assertThat(failures).isGreaterThanOrEqualTo(2);
        String reviewBody="{\"status\":\"WARNING\",\"note\":\"已核对原件，等待澄清\",\"reviewer\":\"测试复核员\"}";
        result(mvc.perform(post("/api/results/"+resultId+"/review").contentType(MediaType.APPLICATION_JSON).content(reviewBody)));
        JsonNode reviewed=result(mvc.perform(get("/api/runs/"+runId)));
        assertThat(reviewed.get("reviews").size()).isEqualTo(1);
        assertThat(reviewed.get("results")).isEqualTo(evaluated.get("results"));
        JsonNode duplicate=result(mvc.perform(post("/api/projects/"+project+"/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+bid+"\"}")));
        assertThat(duplicate.get("id").asText()).isEqualTo(runId);
        JsonNode rerun=result(mvc.perform(post("/api/projects/"+project+"/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+bid+"\",\"force\":true}")));
        assertThat(rerun.get("id").asText()).isNotEqualTo(runId);
        complete(rerun.get("taskId").asText());
        assertThat(result(mvc.perform(get("/api/runs/"+runId))).get("reviews").size()).isEqualTo(1);
        mvc.perform(get("/api/runs/"+runId+"/report?format=html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("已核对原件")));
        mvc.perform(get("/api/runs/"+runId+"/report?format=csv")).andExpect(status().isOk());
        mvc.perform(get("/api/runs/"+runId+"/report?format=json")).andExpect(status().isOk());
        String other=create("其他项目");
        mvc.perform(post("/api/projects/"+other+"/runs").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\""+bid+"\"}")).andExpect(status().isBadRequest());
    }
    @Test void rejectsUnsafeInputsAndCrossOriginMutation() throws Exception {
        String project=create("输入检查");
        mvc.perform(multipart("/api/projects/"+project+"/documents").file(new MockMultipartFile("file","script.html","text/html","<script>bad()</script>".getBytes(StandardCharsets.UTF_8))).param("role","BID")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects").header("Origin","https://outside.example").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bad\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/not-a-document/raw")).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/not-a-task/retry")).andExpect(status().isNotFound());
    }
}

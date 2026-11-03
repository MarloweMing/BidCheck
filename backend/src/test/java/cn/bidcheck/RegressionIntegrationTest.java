package cn.bidcheck;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.engine.CheckEngine;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** User-visible regressions for upload identity, tender selection and exported evidence. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:bidcheck-regression;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class RegressionIntegrationTest {
    private static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bidcheck-regression-"); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("bidcheck.data",()->DATA.toString()); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordMapper mapper;
    @Autowired Store store;

    private JsonNode result(ResultActions request) throws Exception {
        return json.readTree(request.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private String create(String name) throws Exception {
        return result(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("name", name, "code", "REG-2026"))))).path("id").asText();
    }
    private JsonNode detail(String project) throws Exception { return result(mvc.perform(get("/api/projects/"+project))); }
    private JsonNode upload(String project,String role,String name,String text) throws Exception {
        return result(mvc.perform(multipart("/api/projects/"+project+"/documents").file(new MockMultipartFile("file",name,"text/plain",text.getBytes(StandardCharsets.UTF_8))).param("role",role)));
    }
    private void complete(String id) {
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(100)).until(()->{
            TaskRow task=mapper.task(id);
            if(task!=null && "FAILED".equals(task.status)) throw new AssertionError("任务失败："+task.error);
            return task!=null && "SUCCEEDED".equals(task.status);
        });
    }
    private String uploadCompleted(String project,String role,String name,String text) throws Exception {
        JsonNode task=upload(project,role,name,text); complete(task.path("id").asText());
        assertThat(task.path("documentId").asText()).isNotBlank();
        return task.path("documentId").asText();
    }
    private void extract(String project,String document) throws Exception {
        JsonNode task=result(mvc.perform(post("/api/projects/"+project+"/checkpoints/extract").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("documentId", document)))));
        complete(task.path("id").asText());
    }

    @Test void concurrentUploadsReceiveDistinctVersionsAndReadableOriginals() throws Exception {
        String project=create("并发上传版本"); int count=4;
        ExecutorService senders=Executors.newFixedThreadPool(count);
        CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1);
        List<Future<JsonNode>> requests=new ArrayList<>();
        try {
            for(int i=0;i<count;i++) {
                final int version=i;
                requests.add(senders.submit(()->{
                    ready.countDown();
                    if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("并发起始信号超时");
                    return upload(project,"BID","投标修订"+version+".txt","项目编号：REG-2026\n投标有效期："+(90+version)+"天");
                }));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<JsonNode> tasks=new ArrayList<>();
            for(Future<JsonNode> request:requests) tasks.add(request.get(20,TimeUnit.SECONDS));
            Set<String> ids=new HashSet<>();
            for(JsonNode task:tasks) { ids.add(task.path("documentId").asText()); complete(task.path("id").asText()); }
            assertThat(ids).hasSize(count).doesNotContain("");
            List<Integer> versions=new ArrayList<>(); Set<String> originals=new HashSet<>();
            for(JsonNode document:detail(project).path("documents")) {
                versions.add(document.path("version").asInt());
                byte[] raw=mvc.perform(get("/api/documents/"+document.path("id").asText()+"/raw"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                String original=new String(raw,StandardCharsets.UTF_8); originals.add(original);
                assertThat(original).contains("项目编号：REG-2026");
            }
            assertThat(versions).containsExactlyInAnyOrder(1,2,3,4);
            assertThat(originals).hasSize(count);
        } finally { start.countDown(); senders.shutdownNow(); }
    }

    @Test void duplicateUploadReusesDocumentBeforeAndAfterParsingCompletes() throws Exception {
        String project=create("重复上传身份"); String text="项目编号：REG-2026\n投标有效期：99天";
        JsonNode first=upload(project,"BID","投标原件.txt",text);
        // Deliberately do not wait for the first parse before sending the same bytes again.
        JsonNode second=upload(project,"BID","投标副本.txt",text);
        assertThat(second.path("documentId").asText()).isEqualTo(first.path("documentId").asText()).isNotBlank();
        complete(first.path("id").asText()); complete(second.path("id").asText());
        JsonNode third=upload(project,"BID","投标再次上传.txt",text); complete(third.path("id").asText());
        assertThat(third.path("documentId").asText()).isEqualTo(first.path("documentId").asText());
        assertThat(third.path("id").asText()).as("解析完成后继续复用原解析任务").isEqualTo(first.path("id").asText());
        JsonNode documents=detail(project).path("documents");
        assertThat(documents.size()).isEqualTo(1);
        assertThat(documents.get(0).path("version").asInt()).isEqualTo(1);
        mvc.perform(get("/api/documents/"+first.path("documentId").asText()+"/raw"))
            .andExpect(status().isOk()).andExpect(content().bytes(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void switchingTenderVersionsPreservesConfirmationEditsAndDisabledPreferences() throws Exception {
        String project=create("招标版本切换");
        String tenderA=uploadCompleted(project,"TENDER","招标A.txt","项目编号：REG-2026\n投标有效期：不少于90天\n最高限价：200万元");
        String tenderB=uploadCompleted(project,"TENDER","招标B.txt","项目编号：REG-2026\n投标有效期：不少于120天\n最高限价：150万元");
        String bid=uploadCompleted(project,"BID","投标.txt","项目编号：REG-2026\n投标有效期：110天\n投标总价：180万元");
        extract(project,tenderA);
        Map<String,JsonNode> chosenA=new LinkedHashMap<>(); String disabledId="",validityId="";
        for(JsonNode original:detail(project).path("checkpoints")) {
            ObjectNode edit=original.deepCopy();
            edit.put("confirmed",true);
            if("companyName".equals(edit.path("field").asText())) {
                edit.put("enabled",false); edit.put("confirmed",false); disabledId=edit.path("id").asText();
            }
            if("bidValidity".equals(edit.path("field").asText())) { edit.put("expected","100"); validityId=edit.path("id").asText(); }
            JsonNode saved=result(mvc.perform(put("/api/checkpoints/"+edit.path("id").asText()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit))));
            chosenA.put(saved.path("id").asText(),saved);
        }
        assertThat(disabledId).isNotBlank(); assertThat(validityId).isNotBlank();
        extract(project,tenderB);
        Set<String> bIds=new HashSet<>();
        for(JsonNode rule:detail(project).path("checkpoints")) { bIds.add(rule.path("id").asText()); assertThat(rule.path("confirmed").asBoolean()).isFalse(); }
        assertThat(bIds).doesNotContainAnyElementsOf(chosenA.keySet());
        extract(project,tenderA);
        Map<String,JsonNode> restoredA=new LinkedHashMap<>();
        for(JsonNode rule:detail(project).path("checkpoints")) restoredA.put(rule.path("id").asText(),rule);
        assertThat(restoredA).isEqualTo(chosenA);
        JsonNode run=result(mvc.perform(post("/api/projects/"+project+"/runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("documentId", bid)))));
        complete(run.path("taskId").asText());
        JsonNode results=result(mvc.perform(get("/api/runs/"+run.path("id").asText()))).path("results");
        boolean foundValidity=false;
        for(JsonNode finding:results) {
            String ruleId=finding.path("checkpoint").path("id").asText();
            assertThat(ruleId).isNotEqualTo(disabledId); assertThat(bIds).doesNotContain(ruleId);
            if(ruleId.equals(validityId)) {
                foundValidity=true;
                assertThat(finding.path("checkpoint").path("expected").asText()).isEqualTo("100");
                assertThat(finding.path("evaluation").path("status").asText()).isEqualTo("PASS");
            }
        }
        assertThat(foundValidity).isTrue();
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Seed {
        private final String runId;
        private final String findingId;
        private final String quote;
        private final String tenderName;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Seed(@com.fasterxml.jackson.annotation.JsonProperty("runId") String runId, @com.fasterxml.jackson.annotation.JsonProperty("findingId") String findingId, @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote, @com.fasterxml.jackson.annotation.JsonProperty("tenderName") String tenderName) { this.runId = runId; this.findingId = findingId; this.quote = quote; this.tenderName = tenderName; }
        public String runId() { return runId; }
        public String findingId() { return findingId; }
        public String quote() { return quote; }
        public String tenderName() { return tenderName; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Seed that = (Seed) other; return java.util.Objects.equals(this.runId, that.runId) && java.util.Objects.equals(this.findingId, that.findingId) && java.util.Objects.equals(this.quote, that.quote) && java.util.Objects.equals(this.tenderName, that.tenderName); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.runId); hash = 31 * hash + java.util.Objects.hashCode(this.findingId); hash = 31 * hash + java.util.Objects.hashCode(this.quote); hash = 31 * hash + java.util.Objects.hashCode(this.tenderName); return hash; }
        @Override public String toString() { return "Seed[" + "runId=" + runId + ", findingId=" + findingId + ", quote=" + quote + ", tenderName=" + tenderName + "]"; }
}
    private Seed semanticReport() throws Exception {
        String project=create("报告证据回归"),bidId=Store.id(),tenderId=Store.id(),runId=Store.id(),findingId=Store.id(),checkId=Store.id();
        String quote="必须提交专门的售后服务方案。",tenderName="招标依据专用文档.txt";
        Document bid=new Document(bidId,project,"BID","投标文件.txt",1,"bid-hash",BidService.now(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,"项目编号：REG-2026","PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
        Document tender=new Document(tenderId,project,"TENDER",tenderName,1,"tender-hash",BidService.now(),cn.bidcheck.compat.Java8Api.listOf(new Page(7,quote,"PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf());
        Evidence source=new Evidence(tenderId,tenderName,7,"PAGE",quote,"p7-l1",1);
        Checkpoint checkpoint=new Checkpoint(checkId,"售后服务方案响应","技术响应","MEDIUM","SEMANTIC_RESPONSE","technicalResponse","RESPONDS",quote,"",true,true,true,source);
        Evaluation evaluation=new CheckEngine().evaluate(checkpoint,bid,cn.bidcheck.compat.Java8Api.listOf());
        assertThat(evaluation.status()).isEqualTo("UNDETERMINED");
        store.save("document",bidId,project,"",bid); store.save("document",tenderId,project,"",tender);
        store.save("run",runId,project,bidId,BidService.fields("id",runId,"projectId",project,"documentId",bidId,"status","COMPLETED","ruleVersion","regression","createdAt",BidService.now()));
        store.save("finding",findingId,project,runId,new Finding(findingId,runId,checkpoint,evaluation));
        return new Seed(runId,findingId,quote,tenderName);
    }
    private String report(String run,String format) throws Exception {
        return mvc.perform(get("/api/runs/"+run+"/report").param("format",format)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void multilineSpreadsheetFormulaIsEscapedWithoutChangingSavedReview() throws Exception {
        Seed seed=semanticReport(); String note="=SUM(1,2)\n第二行是人工复核意见";
        result(mvc.perform(post("/api/results/"+seed.findingId()+"/review").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cn.bidcheck.compat.Java8Api.mapOf("status", "WARNING", "note", note, "reviewer", "回归复核员")))));
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        int noteColumn=rows.get(0).indexOf("复核意见"); assertThat(noteColumn).isGreaterThanOrEqualTo(0);
        assertThat(rows.get(1).get(noteColumn)).isEqualTo("'"+note);
        JsonNode saved=result(mvc.perform(get("/api/runs/"+seed.runId())));
        assertThat(saved.path("reviews").get(0).path("note").asText()).isEqualTo(note);
    }

    @Test void tenderEvidenceIsNeverExportedAsEvidenceFromTheBid() throws Exception {
        Seed seed=semanticReport(); String html=report(seed.runId(),"html");
        assertThat(html).contains(seed.quote()).contains(seed.tenderName());
        int bidStart=html.indexOf("<h3>投标证据</h3>"); assertThat(bidStart).isGreaterThanOrEqualTo(0);
        String bidSection=html.substring(bidStart,html.indexOf("</article>",bidStart));
        assertThat(bidSection).contains("未取得充分的投标证据").doesNotContain(seed.quote()).doesNotContain(seed.tenderName());
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        assertThat(rows.get(1).get(rows.get(0).indexOf("招标依据"))).contains(seed.quote()).contains(seed.tenderName());
        assertThat(rows.get(1).get(rows.get(0).indexOf("投标证据"))).isEmpty();
    }

    @Test void supplementalTenderDeadlineIsExportedOnceAndNeverMixedWithBidEvidence() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        Finding original=store.get("finding",seed.findingId(),Finding.class);Evidence source=original.checkpoint().source();
        run.put("tenderDocumentId",source.documentId());store.save("run",seed.runId(),project,bidId,run);
        String deadline="投标截止时间：2030-06-20 09:30，原文标记 <须核对>。",bidQuote="本投标材料出具日期：2030-06-01。";
        Document tender=store.get("document",source.documentId(),Document.class);
        store.save("document",tender.id(),project,"",new Document(tender.id(),project,"TENDER",tender.name(),1,tender.sha256(),tender.createdAt(),
            cn.bidcheck.compat.Java8Api.listOf(tender.pages().get(0), new Page(8,deadline,"PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf()));
        Evidence duplicateSource=new Evidence(source.documentId(),source.documentName(),source.page(),source.locationType(),source.quote(),"different-anchor",0.5);
        Evidence additional=new Evidence(source.documentId(),source.documentName(),8,"PAGE",deadline,"T2",0.9);
        Evidence bid=new Evidence(bidId,"投标文件.txt",1,"PAGE",bidQuote,"B1",0.9);
        String otherTender=Store.id(),otherBid=Store.id(),foreign=Store.id(),unknown=Store.id(),foreignProject=create("另一项目");
        for(String id:cn.bidcheck.compat.Java8Api.listOf(otherTender, otherBid, foreign)) {
            String owner=id.equals(foreign)?foreignProject:project,role=id.equals(otherBid)?"BID":"TENDER";
            store.save("document",id,owner,"",new Document(id,owner,role,"不属于本次检查.txt",1,"synthetic",BidService.now(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,"排除原文","PAGE","TEXT",null)),cn.bidcheck.compat.Java8Api.listOf()));
        }
        List<Evidence> refs=cn.bidcheck.compat.Java8Api.listOf(duplicateSource, additional, new Evidence(additional.documentId(),additional.documentName(),8,"PAGE",deadline,"duplicate-T",0.2), bid, new Evidence(otherTender,"其他招标版本",1,"PAGE","不应导出的其他招标依据","T3",1), new Evidence(otherBid,"其他投标版本",1,"PAGE","不应导出的其他投标证据","B2",1), new Evidence(foreign,"跨项目文件",1,"PAGE","不应导出的跨项目引用","X1",1), new Evidence(unknown,"未知文件",1,"PAGE","不应导出的未知引用","X2",1));
        Evaluation evaluated=new Evaluation("UNDETERMINED","核对日期依据","请复核",refs,0.5,"DETERMINISTIC");
        Finding updated=new Finding(seed.findingId(),seed.runId(),original.checkpoint(),evaluated);
        store.save("finding",seed.findingId(),project,seed.runId(),updated);
        String html=report(seed.runId(),"html");
        int tenderStart=html.indexOf("<h3>招标依据</h3>"),bidStart=html.indexOf("<h3>投标证据</h3>");
        String tenderSection=html.substring(tenderStart,bidStart),bidSection=html.substring(bidStart,html.indexOf("</article>",bidStart));
        assertThat(tenderSection).contains("<h3>补充招标依据</h3>").contains("&lt;须核对&gt;").doesNotContain(bidQuote);
        assertThat(tenderSection.split(java.util.regex.Pattern.quote(seed.quote()),-1)).hasSize(2);
        assertThat(tenderSection.split("投标截止时间",-1)).hasSize(2);
        assertThat(bidSection).contains(bidQuote).doesNotContain("投标截止时间").doesNotContain(seed.quote());
        assertThat(html).doesNotContain("不应导出的").doesNotContain("<须核对>");
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        String tenderCell=rows.get(1).get(rows.get(0).indexOf("招标依据")),bidCell=rows.get(1).get(rows.get(0).indexOf("投标证据"));
        assertThat(tenderCell).contains(seed.quote()).contains(deadline).doesNotContain(bidQuote).doesNotContain("不应导出的");
        assertThat(tenderCell.split("投标截止时间",-1)).hasSize(2);
        assertThat(bidCell).contains(bidQuote).doesNotContain("投标截止时间").doesNotContain("不应导出的");
        Finding exported=json.treeToValue(json.readTree(report(seed.runId(),"json")).path("results").get(0),Finding.class);
        assertThat(exported.checkpoint()).isEqualTo(updated.checkpoint());
        assertThat(exported.evaluation()).isEqualTo(updated.evaluation());
        assertThat(exported.assessment()).containsKey("businessPresentation");
        assertThat(store.get("finding",updated.id(),Finding.class)).isEqualTo(updated);
    }

    @Test void unknownAndCrossProjectCheckpointSourcesAreNotRenderedAsTenderEvidence() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId"),foreignProject=create("跨项目依据回归"),foreignId=Store.id();
        store.save("document",foreignId,foreignProject,"",new Document(foreignId,foreignProject,"TENDER","外部招标.txt",1,"synthetic",BidService.now(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf()));
        Finding original=store.get("finding",seed.findingId(),Finding.class);Checkpoint c=original.checkpoint();
        run.put("tenderDocumentId",c.source().documentId());store.save("run",seed.runId(),project,bidId,run);
        for(String invalidId:cn.bidcheck.compat.Java8Api.listOf(foreignId, Store.id())) {
            Evidence invalid=new Evidence(invalidId,"无效招标引用",1,"PAGE","不可展示的外部正文","X",1);
            Checkpoint checkpoint=new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),c.expected(),c.unit(),c.required(),c.confirmed(),c.enabled(),invalid);
            Evaluation evaluated=new Evaluation("UNDETERMINED","无有效依据","请核对",cn.bidcheck.compat.Java8Api.listOf(invalid),0,"DETERMINISTIC");
            store.save("finding",seed.findingId(),project,seed.runId(),new Finding(seed.findingId(),seed.runId(),checkpoint,evaluated));
            assertThat(report(seed.runId(),"html")).contains("未取得本次招标文件的有效依据").doesNotContain("不可展示的外部正文").doesNotContain("无效招标引用");
            List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
            assertThat(rows.get(1).get(rows.get(0).indexOf("招标依据"))).isEmpty();
            assertThat(rows.get(1).get(rows.get(0).indexOf("投标证据"))).isEmpty();
        }
    }

    @Test void layoutGapsRemainVisibleInApiAndAllExportsWhenTranscriptionSucceeded() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        String warning="第 1 页[OCR结构未完成] 表格关系 <需核对>";
        store.save("document",bidId,project,"",new Document(bidId,project,"BID","结构待复核.pdf",1,"b",BidService.now(),
            cn.bidcheck.compat.Java8Api.listOf(new Page(1,"转录已保存","PAGE","PDF_OCR","[OCR已识别]")),cn.bidcheck.compat.Java8Api.listOf(warning)));
        run.put("processingCoverage",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", bidId, "contextWindows", 3, "contextPending", 2, "contextReviewRequired", 1)));
        run.put("retrievalPreparation",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("denseCoverageComplete", false, "mode", "BM25_DEGRADED")));
        store.save("run",seed.runId(),project,bidId,run);
        JsonNode payload=result(mvc.perform(get("/api/runs/"+seed.runId()).param("includeContent","false")));
        JsonNode coverage=null;for(JsonNode doc:payload.path("ocrContext").path("documents")) if(bidId.equals(doc.path("documentId").asText()))coverage=doc;
        assertThat(coverage).isNotNull();assertThat(coverage.path("ocrCoverageComplete").asBoolean()).isTrue();
        assertThat(coverage.path("structureIncompleteCount").asInt()).isEqualTo(1);
        assertThat(coverage.path("processing").path("contextPending").asInt()).isEqualTo(2);
        String html=report(seed.runId(),"html");
        assertThat(html).contains("1 处文字或表格布局待复核","查看 1 处文字或表格布局待复核位置","&lt;需核对&gt;","2 个未完成","全文向量索引未完整建立").doesNotContain("<需核对>");
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        assertThat(rows.get(1).get(rows.get(0).indexOf("解析范围"))).contains(warning,"2 个未完成","全文向量索引未完整建立");
        assertThat(report(seed.runId(),"json")).contains("structureIncompleteCount","processingCoverage");
    }

    @Test void limitedFieldCoverageRemainsFrozenInApiHtmlCsvAndJsonDespiteLaterProcessing() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        Finding original=store.get("finding",seed.findingId(),Finding.class);
        store.save("document",bidId,project,"",new Document(bidId,project,"BID","范围有限.pdf",1,"bid-hash",BidService.now(),
            cn.bidcheck.compat.Java8Api.listOf(new Page(1,"已保存的有效字段","PAGE","PDF_OCR",null), new Page(2,"第二页","PAGE","PDF_OCR",null), new Page(3,"第三页","PAGE","PDF_OCR",null)),cn.bidcheck.compat.Java8Api.listOf()));
        List<String> reasons=cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELD_LIMIT_REACHED", "STRUCTURED_FIELDS_REMAINING", "STRUCTURED_SOURCE_TRUNCATED", "STRUCTURED_FIELDS_REJECTED", "STRUCTURED_OUTPUT_TARGET_EXCEEDED");
        Map<String,Object> frozen=BidService.fields("documentId",bidId,"contextWindows",3,"contextPending",0,"contextReviewRequired",0,"contextNoFacts",0,
            "contextLimitedPageCount",2,"contextLimitedPages",cn.bidcheck.compat.Java8Api.listOf(1, 3),"contextCoverageLimitations",reasons);
        run.put("processingCoverage",cn.bidcheck.compat.Java8Api.listOf(frozen));store.save("run",seed.runId(),project,bidId,run);
        store.save("processing_summary","processing:"+bidId,project,bidId,BidService.fields("documentId",bidId,"documentHash","bid-hash","documentVersion",1,
            "contextLimitedPageCount",1,"contextLimitedPages",cn.bidcheck.compat.Java8Api.listOf(2),"contextCoverageLimitations",cn.bidcheck.compat.Java8Api.listOf("LATER_PROCESSING_ONLY")));
        JsonNode payload=result(mvc.perform(get("/api/runs/"+seed.runId()).param("includeContent","false")));
        JsonNode coverage=null;for(JsonNode doc:payload.path("ocrContext").path("documents")) if(bidId.equals(doc.path("documentId").asText()))coverage=doc.path("processing");
        assertThat(coverage).isEqualTo(json.valueToTree(frozen));
        String html=report(seed.runId(),"html");
        assertThat(html).contains("2 页字段提取范围有限，需核对原文","0 个未完成","单页字段数量达到提取上限","原页仍有字段未提取","本次仅处理了部分原文内容",
            "/api/documents/"+bidId+"/pages/1/image","/api/documents/"+bidId+"/pages/3/image","STRUCTURED_SOURCE_TRUNCATED",
            "未通过来源校验的字段未采纳，不代表原文没有材料","字段数量超过建议范围，已接受字段仍保留，需核对完整性")
            .doesNotContain("LATER_PROCESSING_ONLY","/api/documents/"+bidId+"/pages/2/image");
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        assertThat(rows.get(1).get(rows.get(0).indexOf("解析范围"))).contains("2 页字段提取范围有限","第 1 页","第 3 页","本次仅处理了部分原文内容");
        JsonNode csvCoverage=json.readTree(rows.get(1).get(rows.get(0).indexOf("结构字段范围（冻结记录）"))).get(0);
        assertThat(csvCoverage.path("documentId").asText()).isEqualTo(bidId);
        assertThat(csvCoverage.path("contextLimitedPageCount").asInt()).isEqualTo(2);
        assertThat(csvCoverage.path("contextLimitedPages")).isEqualTo(json.valueToTree(cn.bidcheck.compat.Java8Api.listOf(1, 3)));
        assertThat(csvCoverage.path("contextCoverageLimitations")).isEqualTo(json.valueToTree(reasons));
        JsonNode exported=json.readTree(report(seed.runId(),"json"));
        assertThat(exported.path("run").path("processingCoverage").get(0)).isEqualTo(json.valueToTree(frozen));
        assertThat(store.get("finding",seed.findingId(),Finding.class)).isEqualTo(original);
    }

    @Test void legacyReportDoesNotInventLimitedFieldCoverageFromLiveProcessingSummary() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        store.save("processing_summary","processing:"+bidId,project,bidId,BidService.fields("documentId",bidId,"documentHash","bid-hash","documentVersion",1,
            "contextLimitedPageCount",99,"contextLimitedPages",cn.bidcheck.compat.Java8Api.listOf(1),"contextCoverageLimitations",cn.bidcheck.compat.Java8Api.listOf("STRUCTURED_FIELD_LIMIT_REACHED")));
        JsonNode payload=result(mvc.perform(get("/api/runs/"+seed.runId()).param("includeContent","false")));
        for(JsonNode document:payload.path("ocrContext").path("documents")) assertThat(document.path("processing").has("contextLimitedPageCount")).isFalse();
        assertThat(report(seed.runId(),"html")).doesNotContain("字段提取范围有限","STRUCTURED_FIELD_LIMIT_REACHED");
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        assertThat(rows.get(1).get(rows.get(0).indexOf("结构字段范围（冻结记录）"))).isEqualTo("[]");
        assertThat(report(seed.runId(),"json")).doesNotContain("contextLimitedPageCount","STRUCTURED_FIELD_LIMIT_REACHED");
    }

    @Test void historicalOcrExplanationSeparatesTenderCoverageWithoutChangingFrozenDecision() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        Finding previous=store.get("finding",seed.findingId(),Finding.class);Evidence source=previous.checkpoint().source();
        String tenderId=source.documentId(),otherTender=Store.id();
        run.put("tenderDocumentId",tenderId);store.save("run",seed.runId(),project,bidId,run);
        String failure="第 9 个内容块[OCR未完成] 图像无法读取 <须核对>";
        store.save("document",tenderId,project,"",new Document(tenderId,project,"TENDER","招标范围.docx",1,"t",BidService.now(),
            cn.bidcheck.compat.Java8Api.listOf(new Page(7,seed.quote(),"PAGE","TEXT",null), new Page(9,"附件位置","BLOCK","POI_NATIVE",failure)),cn.bidcheck.compat.Java8Api.listOf(failure)));
        store.save("document",bidId,project,"",new Document(bidId,project,"BID","投标识别.pdf",3,"b",BidService.now(),
            cn.bidcheck.compat.Java8Api.listOf(new Page(1,"服务承诺","PAGE","PDFBOX_OCR","第 1 页[OCR已识别] 请核对")),cn.bidcheck.compat.Java8Api.listOf("第 1 页[OCR已识别] 请核对")));
        store.save("document",otherTender,project,"",new Document(otherTender,project,"TENDER","后续招标版本不可替换历史范围.docx",2,"new",BidService.now(),
            cn.bidcheck.compat.Java8Api.listOf(new Page(9,"后续新内容","BLOCK","POI_NATIVE",null)),cn.bidcheck.compat.Java8Api.listOf()));
        String prefix="部分图像OCR未完成，现有文字不能证明全部内容已经核验。";
        String businessReason="尚缺完整响应依据；模型提出 OCR 文字须结合上下文核对。",businessSuggestion="补充服务说明 <附件>。";
        String rawSuggestion="请核对原图中的名称、数字、日期及标识；OCR文字不能证明签字盖章有效。"+businessSuggestion;
        Evaluation evaluation=new Evaluation("UNDETERMINED",prefix+businessReason,rawSuggestion,
            cn.bidcheck.compat.Java8Api.listOf(new Evidence(bidId,"投标识别.pdf",1,"PAGE","服务承诺","B1",0.5)),0,"DASHSCOPE:test/OCR_REVIEW");
        Finding frozen=new Finding(seed.findingId(),seed.runId(),previous.checkpoint(),evaluation);
        store.save("finding",frozen.id(),project,seed.runId(),frozen);
        JsonNode payload=result(mvc.perform(get("/api/runs/"+seed.runId()).param("includeContent","false")));
        JsonNode presentation=payload.path("ocrContext").path("findings").path(frozen.id());
        assertThat(presentation.path("legacyClarified").asBoolean()).isTrue();
        assertThat(presentation.path("reason").asText()).isEqualTo(businessReason);
        assertThat(presentation.path("suggestion").asText()).isEqualTo(businessSuggestion);
        assertThat(payload.path("ocrContext").path("documents").size()).isEqualTo(2);
        Map<String,JsonNode> coverage=new HashMap<>();payload.path("ocrContext").path("documents").forEach(d->coverage.put(d.path("documentId").asText(),d));
        assertThat(coverage.get(tenderId).path("incompleteCount").asInt()).isEqualTo(1);
        assertThat(coverage.get(bidId).path("incompleteCount").asInt()).isZero();
        assertThat(coverage.get(tenderId).path("incompleteLocations").get(0).path("page").asInt()).isEqualTo(9);
        for(JsonNode document:payload.path("documents")) assertThat(document.has("pages")).isFalse();
        String html=report(seed.runId(),"html");
        assertThat(html).contains("本次文档解析范围","招标范围.docx","投标识别.pdf","查看 1 处未完成位置","&lt;须核对&gt;",
            "<p class=analysis-reason>"+businessReason+"</p>","解析提示已按范围澄清，历史结论未修改","查看历史原始说明")
            .doesNotContain("后续招标版本不可替换历史范围","<须核对>","<附件>");
        assertThat(html.split(java.util.regex.Pattern.quote(prefix),-1)).hasSize(2);
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));List<String> header=rows.get(0),row=rows.get(1);
        assertThat(row.get(header.indexOf("说明"))).isEqualTo(businessReason);
        assertThat(row.get(header.indexOf("修改建议"))).isEqualTo(row.get(header.indexOf("下一步")));
        assertThat(row.get(header.indexOf("解析范围"))).contains("招标范围.docx","投标识别.pdf","9",failure,"历史结论未修改")
            .doesNotContain("后续招标版本不可替换历史范围");
        assertThat(row.get(header.indexOf("原始自动说明"))).contains(prefix+businessReason,rawSuggestion);
        JsonNode exported=json.readTree(report(seed.runId(),"json"));
        Finding exportedFinding=json.treeToValue(exported.path("results").get(0),Finding.class);
        assertThat(exportedFinding.checkpoint()).isEqualTo(frozen.checkpoint());
        assertThat(exportedFinding.evaluation()).isEqualTo(frozen.evaluation());
        assertThat(exportedFinding.assessment()).containsKey("businessPresentation");
        assertThat(exported.path("ocrContext").path("findings").path(frozen.id()).path("reason").asText()).isEqualTo(businessReason);
        assertThat(store.get("finding",frozen.id(),Finding.class)).isEqualTo(frozen);
    }

    @Test void plannedOcrCoverageSeparatesDeferredFromFailureWithoutChangingFrozenPass() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        Finding original=store.get("finding",seed.findingId(),Finding.class);
        run.put("tenderDocumentId",original.checkpoint().source().documentId());store.save("run",seed.runId(),project,bidId,run);
        String deferred="第 2 页[OCR_DEFERRED][OCR未完成] 等待检查计划 <延期>",failed="第 3 页[OCR未完成] 识别超时";
        store.save("document",bidId,project,"",new Document(bidId,project,"BID","计划范围.pdf",4,"hash",BidService.now(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,"已签署的服务承诺文字","PAGE","PDFBOX_NATIVE",""), new Page(2,"","PAGE","PDFBOX_NATIVE",deferred), new Page(3,"","PAGE","PDFBOX_NATIVE",failed)),cn.bidcheck.compat.Java8Api.listOf(deferred, failed)));
        Evaluation passed=new Evaluation("PASS","本项已定位到所需服务承诺。","",cn.bidcheck.compat.Java8Api.listOf(new Evidence(bidId,"计划范围.pdf",1,"PAGE","已签署的服务承诺文字","p1",1)),1,"DETERMINISTIC");
        Finding frozen=new Finding(seed.findingId(),seed.runId(),original.checkpoint(),passed);
        store.save("finding",frozen.id(),project,seed.runId(),frozen);
        JsonNode payload=result(mvc.perform(get("/api/runs/"+seed.runId()).param("includeContent","false")));
        JsonNode metadata=null;for(JsonNode value:payload.path("documents")) if(bidId.equals(value.path("id").asText())) metadata=value;
        assertThat(metadata).isNotNull();assertThat(metadata.has("pages")).isFalse();
        assertThat(metadata.path("ocrCoverage").path("incompleteCount").asInt()).isEqualTo(2);
        assertThat(metadata.path("ocrCoverage").path("deferredCount").asInt()).isEqualTo(1);
        assertThat(metadata.path("ocrCoverage").path("failedCount").asInt()).isEqualTo(1);
        assertThat(metadata.path("ocrCoverage").path("ocrCoverageComplete").asBoolean()).isFalse();
        String html=report(seed.runId(),"html");
        assertThat(html).contains("业务结论（自动）：满足要求","本项已定位到所需服务承诺。","1 处待按需补识别","另有 1 处识别未完成","查看 1 处待按需补识别位置","查看 1 处未完成位置","&lt;延期&gt;").doesNotContain("<延期>");
        String attention=html.substring(html.indexOf("<details open>"),html.indexOf("</details>",html.indexOf("<details open>")));
        assertThat(attention).contains(failed).doesNotContain("OCR_DEFERRED");
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
        assertThat(rows.get(1).get(rows.get(0).indexOf("解析范围"))).contains("待按需补识别 · 投标文件第 2 页","识别未完成 · 投标文件第 3 页");
        JsonNode exported=json.readTree(report(seed.runId(),"json"));
        Finding exportedFinding=json.treeToValue(exported.path("results").get(0),Finding.class);
        assertThat(exportedFinding.checkpoint()).isEqualTo(frozen.checkpoint());
        assertThat(exportedFinding.evaluation()).isEqualTo(frozen.evaluation());
        assertThat(exportedFinding.assessment()).containsKey("businessPresentation");
        assertThat(store.get("finding",frozen.id(),Finding.class)).isEqualTo(frozen);
    }

    @Test void reportNeverRemovesUnmarkedPrefixOrModelAuthoredOcrReason() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());String project=BidService.text(run,"projectId");
        Finding previous=store.get("finding",seed.findingId(),Finding.class);
        for(Evaluation evaluation:cn.bidcheck.compat.Java8Api.listOf(new Evaluation("UNDETERMINED","部分图像OCR未完成，现有文字不能证明全部内容已经核验。模型原始判断。","保持建议",cn.bidcheck.compat.Java8Api.listOf(),0,"DASHSCOPE:test"), new Evaluation("UNDETERMINED","模型认为 OCR 转录无法辨认附件，需核对原件。","保持建议",cn.bidcheck.compat.Java8Api.listOf(),0,"DASHSCOPE:test/OCR_REVIEW"))) {
            store.save("finding",seed.findingId(),project,seed.runId(),new Finding(seed.findingId(),seed.runId(),previous.checkpoint(),evaluation));
            String html=report(seed.runId(),"html");
            assertThat(html).contains("<p class=analysis-reason>"+evaluation.reason()+"</p>").doesNotContain("查看历史原始说明");
            List<List<String>> rows=csvRows(report(seed.runId(),"csv"));
            assertThat(rows.get(1).get(rows.get(0).indexOf("说明"))).isEqualTo(evaluation.reason());
            assertThat(rows.get(1).get(rows.get(0).indexOf("修改建议"))).isEqualTo(rows.get(1).get(rows.get(0).indexOf("下一步")));
            assertThat(rows.get(1).get(rows.get(0).indexOf("原始模型建议（未作为用户待办）"))).isEqualTo(evaluation.suggestion());
        }
    }

    @Test void reportKeepsBusinessPassSeparateFromOcrReviewAndUnrelatedPageFailure() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());
        String project=BidService.text(run,"projectId"),bidId=BidService.text(run,"documentId");
        Finding original=store.get("finding",seed.findingId(),Finding.class);
        run.put("tenderDocumentId",original.checkpoint().source().documentId());store.save("run",seed.runId(),project,bidId,run);
        String failure="第 3 页[OCR未完成] 不属于本项的图像识别超时";
        store.save("document",bidId,project,"",new Document(bidId,project,"BID","业务与原图分层.pdf",3,"hash",BidService.now(),cn.bidcheck.compat.Java8Api.listOf(new Page(1,"原生服务承诺","PAGE","PDFBOX_NATIVE",""), new Page(2,"OCR服务承诺","PAGE","PDFBOX_OCR","第 2 页[OCR已识别] 请核对"), new Page(3,"其他附件","PAGE","PDFBOX_NATIVE",failure)),cn.bidcheck.compat.Java8Api.listOf("第 2 页[OCR已识别] 请核对", failure)));
        Evaluation passed=new Evaluation("PASS","已定位到要求的服务承诺。","",cn.bidcheck.compat.Java8Api.listOf(new Evidence(bidId,"业务与原图分层.pdf",2,"PAGE","OCR服务承诺","B1",0)),0.75,"DASHSCOPE:test");
        Map<String,Object> review=cn.bidcheck.compat.Java8Api.mapOf("required", true, "incomplete", false, "reason", "业务证据充分；请核对相关原图文字。", "locations", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", bidId, "page", 2, "locationType", "PAGE", "label", "投标文件第 2 页")));
        Map<String,Object> assessment=cn.bidcheck.compat.Java8Api.mapOf("rawEvaluation", passed, "preOcrEvaluation", passed, "adjustments", cn.bidcheck.compat.Java8Api.listOf(), "ocrReview", review, "retrieval", cn.bidcheck.compat.Java8Api.mapOf("mode", "HYBRID"), "model", cn.bidcheck.compat.Java8Api.mapOf("state", "COMPLETED", "attempts", cn.bidcheck.compat.Java8Api.listOf()));
        Finding finding=new Finding(seed.findingId(),seed.runId(),original.checkpoint(),passed,assessment);
        store.save("finding",finding.id(),project,seed.runId(),finding);
        JsonNode frozen=json.valueToTree(store.get("finding",finding.id(),Finding.class));
        String html=report(seed.runId(),"html");
        assertThat(html).contains("业务结论（自动）：满足要求","原图复核提示","业务证据充分；请核对相关原图文字。","查看原始判断与调整说明","投标文件第 2 页","混合证据检索");
        List<List<String>> rows=csvRows(report(seed.runId(),"csv"));List<String> header=rows.get(0),row=rows.get(1);
        assertThat(header.subList(0,15)).containsExactly("项目ID","检查批次ID","检查项","分类","风险","自动结论","人工结论","说明","修改建议","招标依据","投标证据","复核人","复核意见","解析范围","原始自动说明");
        assertThat(row.get(header.indexOf("自动结论"))).isEqualTo("通过");
        assertThat(row.get(header.indexOf("原图复核提示"))).contains("请核对相关原图文字","投标文件第 2 页");
        assertThat(row.get(header.indexOf("原始判断"))).contains("通过");
        JsonNode exported=json.readTree(report(seed.runId(),"json"));
        Finding exportedFinding=json.treeToValue(exported.path("results").get(0),Finding.class);
        assertThat(exportedFinding.checkpoint()).isEqualTo(finding.checkpoint());
        assertThat(exportedFinding.evaluation()).isEqualTo(finding.evaluation());
        assertThat(exportedFinding.assessment()).containsKey("businessPresentation");
        assertThat(json.<JsonNode>valueToTree(store.get("finding",finding.id(),Finding.class))).isEqualTo(frozen);

        Evaluation nativePass=new Evaluation("PASS","原生正文已经证明服务承诺。","",cn.bidcheck.compat.Java8Api.listOf(new Evidence(bidId,"业务与原图分层.pdf",1,"PAGE","原生服务承诺","B1",1)),1,"DETERMINISTIC");
        Map<String,Object> nativeAssessment=cn.bidcheck.compat.Java8Api.mapOf("rawEvaluation", nativePass, "preOcrEvaluation", nativePass, "adjustments", cn.bidcheck.compat.Java8Api.listOf(), "ocrReview", cn.bidcheck.compat.Java8Api.mapOf("required", false, "incomplete", false, "reason", "", "locations", cn.bidcheck.compat.Java8Api.listOf()), "retrieval", cn.bidcheck.compat.Java8Api.mapOf("mode", "DETERMINISTIC"));
        store.save("finding",finding.id(),project,seed.runId(),new Finding(finding.id(),seed.runId(),finding.checkpoint(),nativePass,nativeAssessment));
        String nativeHtml=report(seed.runId(),"html"),article=nativeHtml.substring(nativeHtml.indexOf("<article "),nativeHtml.indexOf("</article>"));
        assertThat(nativeHtml).contains(failure);
        assertThat(article).contains("业务结论（自动）：满足要求","原生正文已经证明服务承诺").doesNotContain("原图复核提示");
        rows=csvRows(report(seed.runId(),"csv"));header=rows.get(0);row=rows.get(1);
        assertThat(row.get(header.indexOf("自动结论"))).isEqualTo("通过");assertThat(row.get(header.indexOf("原图复核提示"))).isEmpty();
    }

    @Test void reportDistinguishesUnavailableModelFromInsufficientEvidenceAndRetainsRawJudgment() throws Exception {
        Seed seed=semanticReport();Map<String,Object> run=store.getMap("run",seed.runId());String project=BidService.text(run,"projectId");
        Finding original=store.get("finding",seed.findingId(),Finding.class);
        for(String code:cn.bidcheck.compat.Java8Api.listOf("MODEL_TIMEOUT", "PERSONNEL_COVERAGE_INCOMPLETE", "MODEL_CITATION_INVALID", "EVIDENCE_SCOPE_INCOMPLETE")) {
            boolean failed=code.equals("MODEL_TIMEOUT");
            Evaluation finalEvaluation=new Evaluation("UNDETERMINED",failed?"服务暂未返回结果。":"现有依据不能支撑完整结论。","请核对原文件。",cn.bidcheck.compat.Java8Api.listOf(),0,"DASHSCOPE:test");
            Map<String,Object> assessment=new LinkedHashMap<>();
            assessment.put("rawEvaluation",failed?null:new Evaluation("PASS","模型曾提出全部符合 <待校验>。","",cn.bidcheck.compat.Java8Api.listOf(),0.75,"DASHSCOPE:test"));
            List<Map<String,String>> adjustments=new ArrayList<>();adjustments.add(cn.bidcheck.compat.Java8Api.mapOf("code", code, "reason", "本次校验调整 <依据>。"));
            if(failed||code.equals("MODEL_CITATION_INVALID")) adjustments.add(cn.bidcheck.compat.Java8Api.mapOf("code", "EVIDENCE_SCOPE_INCOMPLETE", "reason", "同时存在证据范围缺口，优先显示模型核验问题。"));
            assessment.put("preOcrEvaluation",finalEvaluation);assessment.put("adjustments",adjustments);
            assessment.put("ocrReview",cn.bidcheck.compat.Java8Api.mapOf("required", false, "incomplete", false, "locations", cn.bidcheck.compat.Java8Api.listOf(), "reason", ""));
            assessment.put("retrieval",cn.bidcheck.compat.Java8Api.mapOf("mode", "LEXICAL"));assessment.put("model",cn.bidcheck.compat.Java8Api.mapOf("state", failed?"UNAVAILABLE":"GUARDED"));
            Finding finding=new Finding(seed.findingId(),seed.runId(),original.checkpoint(),finalEvaluation,assessment);store.save("finding",finding.id(),project,seed.runId(),finding);
            String expected;
switch (code) {case "MODEL_TIMEOUT":expected = "模型核验未完成"; break;case "MODEL_CITATION_INVALID":expected = "模型输出未通过校验"; break;case "EVIDENCE_SCOPE_INCOMPLETE":expected = "证据未覆盖检查范围"; break;default:expected = "证据覆盖不足"; break;}
            String html=report(seed.runId(),"html");assertThat(html).contains("核验状态："+expected,"本次校验调整 &lt;依据&gt;。","查看原始判断与调整说明").doesNotContain("<依据>");
            List<List<String>> rows=csvRows(report(seed.runId(),"csv"));List<String> header=rows.get(0),row=rows.get(1);
            assertThat(row.get(header.indexOf("核验状态"))).isEqualTo(expected);assertThat(row.get(header.indexOf("自动结论"))).isEqualTo("待确认");
            if(failed) assertThat(row.get(header.indexOf("原始判断"))).isEmpty();else assertThat(row.get(header.indexOf("原始判断"))).contains("通过","<待校验>");
            JsonNode exported=json.readTree(report(seed.runId(),"json"));assertThat(exported.path("results").get(0).path("assessment").path("adjustments").get(0).path("code").asText()).isEqualTo(code);
        }
    }

    /** Decode exported CSV as cells, including quoted newlines and doubled quote characters. */
    private List<List<String>> csvRows(String input) {
        String text=input.startsWith("\uFEFF")?input.substring(1):input;
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder cell=new StringBuilder(); boolean quoted=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"') {
                if(quoted && i+1<text.length() && text.charAt(i+1)=='"') { cell.append('"'); i++; }
                else quoted=!quoted;
            } else if(c==',' && !quoted) { row.add(cell.toString()); cell.setLength(0); }
            else if((c=='\r'||c=='\n') && !quoted) {
                if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row=new ArrayList<>();
            } else cell.append(c);
        }
        if(cell.length()>0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        assertThat(quoted).as("CSV quotes must be balanced").isFalse();
        return rows;
    }
}

package cn.bidcheck.verification;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.engine.VerificationEngine;
import cn.bidcheck.verification.engine.ModelGateway;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static cn.bidcheck.service.BidService.fields;

/** Exercises real persistence/leases/HTTP/input events, with inference isolated from provider billing. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:verification-flow;DB_CLOSE_DELAY=-1","bidcheck.verification.input-poll-ms=150","bidcheck.check-concurrency=1"})
@AutoConfigureMockMvc
class VerificationWorkflowIntegrationTest {
    private static final Path DATA;
    static {try {DATA=Files.createTempDirectory("verification-flow-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {r.add("bidcheck.data",()->DATA.toString());}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store store;
    @Autowired BidService bids;
    @MockBean VerificationEngine engine;
    @BeforeEach @SuppressWarnings("unchecked") void inference() {
        doAnswer(invocation->{
            Checkpoint c=invocation.getArgument(0);List<Document> docs=invocation.getArgument(1);Map<String,Object> context=invocation.getArgument(3);
            Consumer<Map<String,Object>> observer=(Consumer<Map<String,Object>>)context.get("stepObserver");
            observer.accept(fields("id","one","type","FACT_EXTRACTION","inputFingerprint",context.get("analysisRevision"),"status","COMPLETED","output",cn.bidcheck.compat.Java8Api.mapOf("documents", docs.size())));
            boolean quotation=docs.stream().anyMatch(d->d.pages().stream().anyMatch(p->p.text().contains("投标总价：100元")));
            return fields("checkpointId",c.id(),"title",c.title(),"businessDecision",quotation?"PASS":"UNDETERMINED","decisionBasis","RULE_COMPUTED",
                "workflowState",quotation?"COMPLETED":"WAIT_INPUT","complete",quotation,"obligations",cn.bidcheck.compat.Java8Api.listOf(fields("id","o1","description","报价核验","status",quotation?"PASS":"UNDETERMINED","reason","依据当前冻结资料")),
                "gaps",quotation?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listOf(fields("code","DOCUMENT_NOT_RECEIVED","description","未收到报价分册","requiredInput","本项目报价","resumeTrigger","DOCUMENT_ADDED")),"evidences",cn.bidcheck.compat.Java8Api.listOf(),"reason",quotation?"资料集合包含报价":"等待报价","metrics",cn.bidcheck.compat.Java8Api.mapOf());
        }).when(engine).evaluate(any(),anyList(),any(),anyMap());
    }
    private JsonNode jsonResponse(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response=result.andReturn().getResponse();assertThat(response.getStatus()).isBetween(200,299);return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    private String project() throws Exception {return jsonResponse(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"核验流程测试\"}"))).path("id").asText();}
    private void complete(String taskId) {await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(80)).until(()->{TaskRow t=store.mapper().task(taskId);if("FAILED".equals(t.status))throw new AssertionError(t.error);return "SUCCEEDED".equals(t.status);});}
    private String upload(String project,String role,String name,String content) throws Exception {
        JsonNode task=jsonResponse(mvc.perform(multipart("/api/projects/"+project+"/documents").file(new MockMultipartFile("file",name,"text/plain",content.getBytes(StandardCharsets.UTF_8))).param("role",role)));
        complete(task.path("id").asText());return task.path("documentId").asText();
    }
    private String checkpoint(String project,String tender) {
        String id=Store.id();Document d=bids.document(tender);Page p=d.pages().get(0);
        Checkpoint c=new Checkpoint(id,"报价<测试>","响应检查","HIGH","SEMANTIC_RESPONSE","quotationScope","RESPONDS","报价不超过200元","元",true,true,true,
            new Evidence(tender,d.name(),p.number(),p.locationType(),p.text(),"p1-l1",1));
        store.save("checkpoint",id,project,tender,c);currentRules(project,tender);return id;
    }
    /** These fixtures seed a completed extraction and user confirmation, rather than invoking inference. */
    private void currentRules(String project,String tender) {
        Map<String,Object> state=store.getMap("project",project);
        Map<String,Object> versions=new LinkedHashMap<>();
        if(state.get("checkpointRuleVersions") instanceof Map<?,?>)((Map<?,?>) (state.get("checkpointRuleVersions"))).forEach((key,value)->versions.put(key.toString(),value));
        versions.put(tender,BidService.RULE_VERSION);
        state.put("checkpointRuleVersions",versions);state.put("checkpointRuleVersion",BidService.RULE_VERSION);
        state.put("activeTenderId",tender);state.put("requestedTenderId",tender);
        store.save("project",project,project,"",state);
    }
    private JsonNode start(String project,String tender,String bid) throws Exception {
        return jsonResponse(mvc.perform(post("/api/projects/"+project+"/verification-runs").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("tenderDocumentId",tender,"documentIds",cn.bidcheck.compat.Java8Api.listOf(bid),"evaluationStage","PRE_SUBMISSION")))));
    }
    @Test void summarySeparatesServiceFailuresFromInputGaps() {
        Map<String,Object> result=VerificationService.summary(cn.bidcheck.compat.Java8Api.listOf(fields("businessDecision","UNDETERMINED","complete",false,"workflowState","ENGINE_FAILURE"), fields("businessDecision","UNDETERMINED","complete",false,"workflowState","WAIT_INPUT")),2);
        assertThat(result.get("errors")).isEqualTo(1);assertThat(result.get("waiting")).isEqualTo(2);
    }
    @Test void selectedHistoricalTenderCannotBorrowTheActiveTendersCurrentRuleVersion() throws Exception {
        String project=project(),oldTender=upload(project,"TENDER","旧招标.txt","旧版本报价不超过200元"),
                currentTender=upload(project,"TENDER","当前招标.txt","当前版本报价不超过200元"),
                bid=upload(project,"BID","投标.txt","投标总价：100元");
        checkpoint(project,oldTender);checkpoint(project,currentTender);
        Map<String,Object> state=store.getMap("project",project);
        state.put("checkpointRuleVersions",cn.bidcheck.compat.Java8Api.mapOf(oldTender, "old-rules", currentTender, BidService.RULE_VERSION));
        assertThat(state.get("activeTenderId")).isEqualTo(currentTender);
        store.save("project",project,project,"",state);
        mvc.perform(post("/api/projects/"+project+"/verification-runs").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(fields("tenderDocumentId",oldTender,"documentIds",cn.bidcheck.compat.Java8Api.listOf(bid)))))
                .andExpect(status().isBadRequest()).andExpect(response -> assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("提取规则已更新"));
        assertThat(store.maps("verification_run",project,null)).isEmpty();
        JsonNode current=start(project,currentTender,bid);complete(current.path("taskId").asText());
    }
    @Test void supplementationPreservesBothVolumesOldReportAndDurableSteps() throws Exception {
        String project=project(),tender=upload(project,"TENDER","招标.txt","投标截止时间：2025年08月05日。\n报价不超过200元"),bid=upload(project,"BID","商务.txt","商务内容，报价详见报价部分");
        checkpoint(project,tender);JsonNode run=start(project,tender,bid);String runId=run.path("id").asText();complete(run.path("taskId").asText());
        JsonNode old=jsonResponse(mvc.perform(get("/api/verification-runs/"+runId)));
        byte[] oldExport=mvc.perform(get("/api/verification-runs/"+runId+"/report?format=json")).andReturn().getResponse().getContentAsByteArray();
        assertThat(old.path("summary").path("automaticComplete").asInt()).isZero();
        assertThat(old.path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(old.path("run").path("workflowState").asText()).isEqualTo("WAIT_INPUT");
        assertThat(store.maps("verification_step",project,null)).hasSize(1);
        assertThat(start(project,tender,bid).path("id").asText()).isEqualTo(runId);
        JsonNode parse=jsonResponse(mvc.perform(multipart("/api/projects/"+project+"/verification-inputs")
            .file(new MockMultipartFile("file","报价.txt","text/plain","本项目投标总价：100元".getBytes(StandardCharsets.UTF_8)))
            .param("role","BID").param("purpose","QUOTATION").param("runId",runId)));
        complete(parse.path("id").asText());
        await().atMost(Duration.ofSeconds(25)).until(()->store.maps("verification_run",project,null).size()==2);
        Map<String,Object> revised=store.maps("verification_run",project,null).stream().filter(r->!runId.equals(r.get("id"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);complete(revised.get("taskId").toString());
        JsonNode latest=jsonResponse(mvc.perform(get("/api/verification-runs/"+revised.get("id"))));
        assertThat(latest.path("run").path("documentIds").size()).isEqualTo(2);
        assertThat(latest.path("summary").path("automaticComplete").asInt()).isEqualTo(1);
        assertThat(jsonResponse(mvc.perform(get("/api/verification-runs/"+runId))).path("results")).isEqualTo(old.path("results"));
        assertThat(mvc.perform(get("/api/verification-runs/"+runId+"/report?format=json")).andReturn().getResponse().getContentAsByteArray()).isEqualTo(oldExport);
        mvc.perform(post("/api/verification-runs/"+runId+"/revisions").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}")).andExpect(status().isConflict());
        mvc.perform(get("/api/verification-runs/"+revised.get("id")+"/report?format=html")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("报价&lt;测试&gt;")));
        mvc.perform(get("/api/verification-runs/"+revised.get("id")+"/report?format=json")).andExpect(status().isOk());
    }
    @Test void validatesProjectScopeDatesAndRejectsExternallySubmittedPass() throws Exception {
        String project=project(),tender=upload(project,"TENDER","招标.txt","投标截止时间：2025年08月05日。\n报价不超过200元"),bid=upload(project,"BID","投标.txt","投标总价：100元");
        checkpoint(project,tender);String other=project(),foreign=upload(other,"BID","外部.txt","其他项目");
        mvc.perform(post("/api/projects/"+project+"/verification-runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fields("tenderDocumentId",tender,"documentIds",cn.bidcheck.compat.Java8Api.listOf(foreign))))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/"+project+"/verification-runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fields("tenderDocumentId",tender,"documentIds",cn.bidcheck.compat.Java8Api.listOf())))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/"+project+"/verification-runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fields("tenderDocumentId",tender,"documentIds",cn.bidcheck.compat.Java8Api.listOf(bid),"referenceDate","2025-10-08","referenceDateSourceDocumentId",tender)))).andExpect(status().isBadRequest());
        JsonNode run=start(project,tender,bid);complete(run.path("taskId").asText());
        mvc.perform(post("/api/projects/"+project+"/verification-external-records").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("runId",run.path("id").asText(),"kind","CREDIT","subject","某公司","source","示例记录","result","PASS")))).andExpect(status().isBadRequest());
        JsonNode explicit=jsonResponse(mvc.perform(post("/api/verification-runs/"+run.path("id").asText()+"/revisions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("expectedRevision",1,"referenceDate","2025-08-05","referenceDateSourceDocumentId",tender)))));
        complete(explicit.path("taskId").asText());
        assertThat(explicit.path("referenceDateEvidence").path("quote").asText()).contains("投标截止时间");
        assertThat(explicit.path("parentRunId").asText()).isEqualTo(run.path("id").asText());
    }
    @Test @SuppressWarnings("unchecked") void restartKeepsCompletedConclusionsAndRestoresUnfinishedSteps() throws Exception {
        String project=project(),tender=upload(project,"TENDER","依据.txt","核验提交材料"),bid=upload(project,"BID","材料.txt","投标总价：100元");
        checkpoint(project,tender);checkpoint(project,tender);
        List<Checkpoint> ordered=store.list("checkpoint",project,tender,Checkpoint.class);String second=ordered.get(1).id();
        Map<String,AtomicInteger> calls=new ConcurrentHashMap<>();AtomicBoolean resumed=new AtomicBoolean();
        doAnswer(invocation->{
            Checkpoint cp=invocation.getArgument(0);Map<String,Object> context=invocation.getArgument(3);int call=calls.computeIfAbsent(cp.id(),k->new AtomicInteger()).incrementAndGet();
            if(cp.id().equals(second)&&call>1)resumed.set(!VerificationService.maps(context.get("priorSteps")).isEmpty());
            ((Consumer<Map<String,Object>>)context.get("stepObserver")).accept(fields("id","durable-fact","type","EXTRACT","status","COMPLETED","output",cn.bidcheck.compat.Java8Api.mapOf("value", "100")));
            if(cp.id().equals(second)&&call==1)throw new IllegalStateException("测试处理进程在计算阶段中断");
            return fields("businessDecision","PASS","complete",true,"workflowState","COMPLETED","decisionBasis","RULE_COMPUTED","obligations",cn.bidcheck.compat.Java8Api.listOf(),"gaps",cn.bidcheck.compat.Java8Api.listOf(),"reason","已完成的计算");
        }).when(engine).evaluate(any(),anyList(),any(),anyMap());
        JsonNode run=start(project,tender,bid);String taskId=run.path("taskId").asText();
        await().atMost(Duration.ofSeconds(25)).until(()->"FAILED".equals(bids.task(taskId).status));
        List<Map<String,Object>> before=store.maps("verification_result",project,run.path("id").asText());assertThat(before).hasSize(1);
        bids.retry(taskId);complete(taskId);
        assertThat(calls.get(ordered.get(0).id()).get()).isEqualTo(1);assertThat(calls.get(second).get()).isEqualTo(2);assertThat(resumed).isTrue();
        assertThat(store.maps("verification_result",project,run.path("id").asText())).hasSize(2).contains(before.get(0));
    }
    @Test void rejectsAnotherDatesValueBesideTheDeadlineField() throws Exception {
        String project=project(),tender=upload(project,"TENDER","日期.txt","投标截止时间：2030年04月01日\n合同工期起算日期：2030年06月01日"),bid=upload(project,"BID","投标.txt","投标总价：100元");
        checkpoint(project,tender);
        mvc.perform(post("/api/projects/"+project+"/verification-runs").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("tenderDocumentId",tender,"documentIds",cn.bidcheck.compat.Java8Api.listOf(bid),"referenceDate","2030-06-01","referenceDateSourceDocumentId",tender)))).andExpect(status().isBadRequest());
    }
    @Test void aClarificationRequiresAnExplicitSourcedRuleRevision() throws Exception {
        String project=project(),tender=upload(project,"TENDER","规则.txt","报价不得超过200元"),bid=upload(project,"BID","投标.txt","本项目投标总价：100元");String cp=checkpoint(project,tender);
        JsonNode first=start(project,tender,bid);complete(first.path("taskId").asText());
        String amended=upload(project,"TENDER","澄清.txt","澄清：报价上限由200元调整为150元，其他条款不变。");
        JsonNode second=jsonResponse(mvc.perform(post("/api/verification-runs/"+first.path("id").asText()+"/revisions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("documentIds",cn.bidcheck.compat.Java8Api.listOf(bid, amended))))));complete(second.path("taskId").asText());
        JsonNode detail=jsonResponse(mvc.perform(get("/api/verification-runs/"+second.path("id").asText())));assertThat(detail.path("results").get(0).path("businessDecision").asText()).isEqualTo("UNDETERMINED");
        JsonNode change=detail.path("run").path("requirementChanges").get(0);assertThat(change.path("requiresConfirmation").asBoolean()).isTrue();List<String> candidates=new ArrayList<>();change.path("candidates").forEach(c->candidates.add(c.path("id").asText()));
        java.util.Map<java.lang.String, java.lang.Object> override=fields("checkpointId",cp,"expected","报价不得超过150元","source",fields("documentId",amended,"page",1,"quote","报价上限由200元调整为150元"),"candidateIds",candidates,"confirmed",true);
        JsonNode third=jsonResponse(mvc.perform(post("/api/verification-runs/"+second.path("id").asText()+"/revisions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("expectedRevision",2,"acceptedCheckpointOverrides",cn.bidcheck.compat.Java8Api.listOf(override))))));complete(third.path("taskId").asText());
        assertThat(store.list("verification_check",project,third.path("id").asText(),Checkpoint.class).get(0).expected()).isEqualTo("报价不得超过150元");
        assertThat(store.list("verification_check",project,first.path("id").asText(),Checkpoint.class).get(0).expected()).isEqualTo("报价不超过200元");
        assertThat(jsonResponse(mvc.perform(get("/api/verification-runs/"+third.path("id").asText()))).path("summary").path("automaticComplete").asInt()).isEqualTo(1);
    }
    @Test void sourcedPricingCreatesCalculatedRevisionWithoutChangingOldGap() throws Exception {
        String formula=PricingVerificationTest.formula();String project=project(),tender=upload(project,"TENDER","价格规则.txt",formula);
        String roster="本项目全部有效投标人不含税评标价名单：甲公司、乙公司。已完成算术修正。";
        String a="甲公司 不含税评标价：1000000元",b="乙公司 不含税评标价：1000000元";
        String bid=upload(project,"BID","报价记录.txt",roster+"\n"+a+"\n"+b);Document tenderDoc=bids.document(tender);String cp=Store.id();
        store.save("checkpoint",cp,project,tender,new Checkpoint(cp,"价格评分","评分","","SEMANTIC_RESPONSE","scoring","",formula,"",false,true,true,
            new Evidence(tender,tenderDoc.name(),1,"BLOCK",formula,"p1",1)));
        currentRules(project,tender);
        JsonNode first=start(project,tender,bid);complete(first.path("taskId").asText());
        JsonNode before=jsonResponse(mvc.perform(get("/api/verification-runs/"+first.path("id").asText())));
        assertThat(before.path("results").get(0).path("businessDecision").asText()).isEqualTo("UNDETERMINED");
        Map<String,Object> data=fields("confirmed",true,"rosterComplete",true,"arithmeticCorrectionsConfirmed",true,"taxBasis","EXCLUDING_TAX","targetBidder","甲公司",
            "rosterSource",fields("documentId",bid,"page",1,"quote",roster),"entries",cn.bidcheck.compat.Java8Api.listOf(fields("bidder","甲公司","price","100","unit","万元","source",fields("documentId",bid,"page",1,"quote",a)), fields("bidder","乙公司","price","1000000","unit","元","source",fields("documentId",bid,"page",1,"quote",b))));
        // Input-unit conversion is checked against original amounts, not a claimed business conclusion.
        JsonNode second=jsonResponse(mvc.perform(post("/api/verification-runs/"+first.path("id").asText()+"/revisions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("expectedRevision",1,"pricingData",data)))));complete(second.path("taskId").asText());
        JsonNode calculated=jsonResponse(mvc.perform(get("/api/verification-runs/"+second.path("id").asText()))).path("results").get(0);
        assertThat(calculated.path("proof").path("score").asText()).isEqualTo("54.74");
        assertThat(calculated.path("proof").path("officialReviewScore").asBoolean()).isFalse();
        assertThat(jsonResponse(mvc.perform(get("/api/verification-runs/"+first.path("id").asText()))).path("results")).isEqualTo(before.path("results"));
        Map<String,Object> wrong=new LinkedHashMap<>(data);wrong.put("rosterSource",fields("documentId",bid,"page",1,"quote","不存在的名单原文"));
        mvc.perform(post("/api/verification-runs/"+second.path("id").asText()+"/revisions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("expectedRevision",2,"pricingData",wrong)))).andExpect(status().isBadRequest());
    }
    @Test void sharedFactsAvoidDuplicateExtractionAndNewSourcesStillRevealConflicts() throws Exception {
        AtomicInteger extractions=new AtomicInteger();
        ModelGateway gateway=new ModelGateway() {
            public boolean enabled(){return true;}public String identity(){return "shared-flow-fixture";}
            public JsonNode request(String operation,Map<String,Object> input) {
                if(!operation.equals("EXTRACT")) throw new AssertionError("Typed fixture requires no rule compilation");
                extractions.incrementAndGet();List<Map<String,Object>> facts=new ArrayList<>();
                for(Map<String,Object> source:VerificationService.maps(input.get("sources"))) {
                    String quote=Objects.toString(source.get("quote"),"");java.util.regex.Matcher value=java.util.regex.Pattern.compile("服务期[：:]([0-9]+)天").matcher(quote);
                    if(value.find())facts.add(fields("kind","NUMBER","subject","","field","服务期","rawValue",value.group(1),"value",value.group(1),"unit","天","basis","服务期","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source.get("id")),"attributes",cn.bidcheck.compat.Java8Api.mapOf()));
                }
                return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", facts));
            }
        };
        VerificationEngine delegate=new VerificationEngine(gateway,null);
        doAnswer(invocation->delegate.evaluate(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3)))
            .when(engine).evaluate(any(),anyList(),any(),anyMap());
        String project=project(),tender=upload(project,"TENDER","共享事实依据.txt","服务期不超过200天"),bid=upload(project,"BID","原服务期.txt","本项目服务期：100天");
        for(int n=0;n<2;n++){String id=Store.id();Document d=bids.document(tender);store.save("checkpoint",id,project,tender,
            new Checkpoint(id,"服务期核验","响应","HIGH","VALUE_COMPARE","服务期","<=","200","天",true,true,true,new Evidence(tender,d.name(),1,d.pages().get(0).locationType(),d.pages().get(0).text(),"p1",1)));}
        currentRules(project,tender);
        JsonNode first=start(project,tender,bid);String runId=first.path("id").asText();complete(first.path("taskId").asText());
        JsonNode firstDetail=jsonResponse(mvc.perform(get("/api/verification-runs/"+runId)));
        assertThat(firstDetail.path("summary").path("automaticComplete").asInt()).isEqualTo(2);
        assertThat(extractions.get()).isEqualTo(1);
        JsonNode ledger=jsonResponse(mvc.perform(get("/api/verification-runs/"+runId+"/facts")));
        assertThat(ledger.path("summary").path("occurrences").asInt()).isEqualTo(1);
        assertThat(ledger.path("summary").path("sharedOccurrences").asInt()).isEqualTo(1);
        assertThat(ledger.path("occurrences").get(0).path("usedByCheckpoints").size()).isEqualTo(2);
        JsonNode relationshipGraph=jsonResponse(mvc.perform(get("/api/runs/"+runId+"/evidence-graph")));
        assertThat(relationshipGraph.path("revision").path("projectId").asText()).isEqualTo(project);
        assertThat(relationshipGraph.path("revision").path("documents").get(0).path("documentId").asText()).isEqualTo(bid);
        assertThat(store.getMap("verification_run",runId)).containsKey("evidenceGraph");
        byte[] oldExport=mvc.perform(get("/api/verification-runs/"+runId+"/report?format=json")).andReturn().getResponse().getContentAsByteArray();
        String changed=upload(project,"BID","补充服务期.txt","本项目服务期：300天");
        JsonNode second=jsonResponse(mvc.perform(post("/api/verification-runs/"+runId+"/revisions").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(fields("expectedRevision",1,"documentIds",cn.bidcheck.compat.Java8Api.listOf(bid, changed))))));complete(second.path("taskId").asText());
        JsonNode changedDetail=jsonResponse(mvc.perform(get("/api/verification-runs/"+second.path("id").asText())));
        assertThat(extractions.get()).isGreaterThan(1);
        assertThat(changedDetail.path("results").toString()).contains("CONFLICTING_EVIDENCE");
        assertThat(changedDetail.path("summary").path("automaticComplete").asInt()).isZero();
        assertThat(jsonResponse(mvc.perform(get("/api/verification-runs/"+runId+"/facts")))).isEqualTo(ledger);
        assertThat(mvc.perform(get("/api/verification-runs/"+runId+"/report?format=json")).andReturn().getResponse().getContentAsByteArray()).isEqualTo(oldExport);
    }
}

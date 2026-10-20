package cn.bidcheck;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.*;
import cn.bidcheck.service.BidService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:enterprise;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000"})
@AutoConfigureMockMvc
class EnterpriseRegressionTest {
    private static final Path DATA;
    static { try { DATA=Files.createTempDirectory("bidcheck-enterprise-"); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("bidcheck.data",()->DATA.toString()); }
    @Autowired BidService service;
    @Autowired Store store;
    @Autowired RecordMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String project() { return (String)service.createProject(Map.of("name","代码审查回归","code","REVIEW")).get("id"); }
    private TaskRow upload(String project,String role,String text) throws Exception {
        return service.upload(project,role,new MockMultipartFile("file","fixture.txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));
    }
    private String parsed(String project,String role,String text) throws Exception {
        TaskRow task=upload(project,role,text); service.execute(task); return (String)store.map(task.inputJson).get("documentId");
    }
    private record Ready(String project,String tender,String bid) {}
    private Ready ready() throws Exception {
        String p=project(),t=parsed(p,"TENDER","项目编号：REVIEW\n投标有效期：不少于90天"),b=parsed(p,"BID","项目编号：REVIEW\n投标有效期：100天");
        service.execute(service.extract(p,t));
        for(Checkpoint c:store.list("checkpoint",p,t,Checkpoint.class)) service.updateCheckpoint(c.id(),new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),c.expected(),c.unit(),c.required(),true,c.enabled(),c.source()));
        return new Ready(p,t,b);
    }

    @Test void compactPollingOmitsOriginalTextAndRetainsLocations() throws Exception {
        String p=project(); String body="项目编号：REVIEW\n"+"原始正文不会在轮询传输。".repeat(8000); String id=parsed(p,"BID",body);
        String compact=mvc.perform(get("/api/projects/"+p).param("includeContent","false")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String full=mvc.perform(get("/api/documents/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(compact).doesNotContain("原始正文").contains("pageCount","locationType","contentIncluded");
        assertThat(compact.length()).isLessThan(10_000);
        assertThat(compact.length()*15).isLessThan(full.length());
        assertThat(json.readTree(compact).path("documents").get(0).path("id").asText()).isEqualTo(id);
    }
    @Test void competingStartRequestsCreateOneFrozenRun() throws Exception {
        Ready r=ready(); ExecutorService senders=Executors.newFixedThreadPool(4);
        CountDownLatch start=new CountDownLatch(1); List<Future<String>> requests=new ArrayList<>();
        try {
            for(int i=0;i<4;i++) requests.add(senders.submit(()->{start.await();return (String)service.startRun(r.project(),r.bid()).get("id");}));
            start.countDown();Set<String> ids=new HashSet<>();for(Future<String> request:requests) ids.add(request.get(10,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            Map<String,Object> run=store.getMap("run",ids.iterator().next());
            assertThat(run).containsEntry("tenderDocumentId",r.tender()).containsEntry("ruleVersion",BidService.RULE_VERSION);
        } finally { senders.shutdownNow(); }
    }
    @Test void olderExtractionCannotReplaceLatestTenderSelection() throws Exception {
        String p=project(),a=parsed(p,"TENDER","项目编号：A\n投标有效期：不少于90天"),b=parsed(p,"TENDER","项目编号：B\n投标有效期：不少于120天");
        TaskRow older=service.extract(p,a),latest=service.extract(p,b);
        service.execute(latest);service.execute(older);
        assertThat(store.getMap("project",p)).containsEntry("activeTenderId",b);
        assertThat(mapper.task(older.id).stage).contains("替代");
    }
    @Test void changedEngineDoesNotMixHistoricalAndNewFindings() throws Exception {
        Ready r=ready();Map<String,Object> run=service.startRun(r.project(),r.bid());
        run.put("ruleVersion","legacy-engine");store.save("run",(String)run.get("id"),r.project(),r.bid(),run);
        assertThatThrownBy(()->service.execute(service.task((String)run.get("taskId"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("引擎已升级");
    }
    @Test void exhaustedTaskAndRunBecomeTerminalTogether() throws Exception {
        Ready r=ready();Map<String,Object> run=service.startRun(r.project(),r.bid());TaskRow task=service.task((String)run.get("taskId"));
        for(int attempt=0;attempt<3;attempt++) {
            long deadline=System.currentTimeMillis()+60000;
            assertThat(mapper.claimTask(task.id,BidService.now(),Store.id(),deadline)).isEqualTo(1);
            mapper.recoverTasks(deadline+1,BidService.now());
        }
        service.exhaust(mapper.task(task.id));
        assertThat(mapper.task(task.id).status).isEqualTo("FAILED");
        assertThat(store.getMap("run",(String)run.get("id"))).containsEntry("status","FAILED");
        assertThatThrownBy(()->service.retry(task.id)).hasMessageContaining("3次");
    }
    @Test void modifiedCachedOriginalCannotBeDownloadedAsEvidence() throws Exception {
        String p=project(),id=parsed(p,"BID","项目编号：REVIEW\n投标有效期：90天");
        Files.writeString(DATA.resolve("files").resolve(id+".bin"),"被修改的缓存",StandardCharsets.UTF_8);
        mvc.perform(get("/api/documents/"+id+"/raw")).andExpect(status().isBadRequest());
    }
    @Test void oldDocumentsGetMetadataWithoutLosingOriginal() throws Exception {
        String p=project(),id=Store.id();
        Document document=new Document(id,p,"BID","legacy.txt",1,"hash",BidService.now(),List.of(new Page(1,"原件保留","PAGE","TEXT",null)),List.of());
        Stored row=new Stored();row.id=id;row.kind="document";row.projectId=p;row.parentId="";row.body=store.encode(document);row.createdAt=System.currentTimeMillis();mapper.save(row);
        assertThat(store.documentSummaries(p)).hasSize(1);
        assertThat(service.document(id).pages().get(0).text()).isEqualTo("原件保留");
    }
    @Test void legacyFactsAreRebuiltBeforeNewChecksWithoutChangingOriginal() throws Exception {
        String p=project(),id=parsed(p,"BID","投标人名称：测试科技有限公司\n投标有效期：100天");
        String original=store.require("document",id).body;
        store.mapper().deleteChildren("fact",id);
        store.save("fact_index","facts-version:"+id,p,id,Map.of("version","legacy-engine"));
        List<Fact> refreshed=service.facts(id);
        assertThat(refreshed).isNotEmpty().anyMatch(f->f.rawValue().contains("测试科技"));
        assertThat(store.getMap("fact_index","facts-version:"+id)).containsEntry("version",BidService.RULE_VERSION);
        assertThat(service.facts(id)).isEqualTo(refreshed);
        assertThat(store.require("document",id).body).isEqualTo(original);
    }
    @Test void hostileHostCannotReadLocalProjectsThroughDnsRebinding() throws Exception {
        mvc.perform(get("/api/projects").with(r->{r.setServerName("attacker.example");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").header("Origin","https://attacker.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").with(r->{r.setRemoteAddr("192.168.1.50");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void oversizedJsonAndMissingParametersReturnClientErrors() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("{\"name\":\""+"x".repeat(66000)+"\"}")).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/projects/"+project()+"/documents")).andExpect(status().isBadRequest());
    }
}

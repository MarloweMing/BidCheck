package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.TemplateApiException;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Fact;
import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.TaskRow;
import cn.bidcheck.service.BidService;
import cn.bidcheck.verification.facts.FactLedgerService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:template-incremental-recalculation;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=",
    "bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false"})
class TemplateIncrementalRecalculationIntegrationTest {
    static final Path DATA=temp();
    static Path temp(){try{return Files.createTempDirectory("template-incremental-recalculation-");}catch(Exception failure){throw new IllegalStateException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",DATA::toString);}

    @Autowired BidService bids;
    @Autowired Store store;
    @Autowired TemplateIncrementalRecalculationService recalculations;
    @Autowired FactLedgerService factLedger;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void recalculatesOnlyAffectedTemplateAndKeepsHistoricalFindingsImmutable() throws Exception {
        Fixture fixture=fixture();Map<String,Object> graph=bids.templateDependencyGraph(fixture.runId());long revision=((Number)graph.get("revision")).longValue();
        String changedNeed=needFor(graph,fixture.firstCheckpoint());String retainedNeed=needFor(graph,fixture.secondCheckpoint());
        Map<String,String> originalBodies=findingBodies(fixture);

        Map<String,Object> plan=recalculations.plan(fixture.runId(),revision,List.of(changedNeed));
        assertThat(plan.get("affectedCheckpointIds")).asList().containsExactly(fixture.firstCheckpoint());
        assertThat(plan.get("retainedCheckpointIds")).asList().contains(fixture.secondCheckpoint());
        assertThat(plan).containsEntry("historicalRunUnchanged",true);

        backfillBid(fixture);
        Map<String,Object> receipt=recalculations.recalculate(fixture.runId(),revision,List.of(changedNeed),plan.get("sourceResultDigest").toString());
        assertThat(receipt).containsEntry("status","COMPLETED").containsEntry("historicalRunUnchanged",true)
            .containsEntry("publicationState","CANDIDATE_ONLY").containsEntry("recalculatedCheckpointCount",1).containsEntry("retainedCheckpointCount",1);
        assertThat(receipt.get("recalculatedCheckpointIds")).asList().containsExactly(fixture.firstCheckpoint());
        assertThat(receipt.get("retainedCheckpointIds")).asList().containsExactly(fixture.secondCheckpoint());
        assertThat(receipt.get("results")).asList().hasSize(1);
        Map<?,?> conclusions=(Map<?,?>)receipt.get("newConclusions");assertThat(conclusions.containsKey(fixture.firstCheckpoint())).isTrue();assertThat(conclusions.containsKey(fixture.secondCheckpoint())).isFalse();
        assertThat(findingBodies(fixture)).isEqualTo(originalBodies);
        assertThat(BidService.text(store.getMap("run",fixture.runId()),"status")).isEqualTo("COMPLETED");
        assertThat(recalculations.list(fixture.runId())).extracting(value->value.get("id")).containsExactly(receipt.get("id"));
        assertThat(recalculations.get(fixture.runId(),receipt.get("id").toString())).containsEntry("historicalRunUnchanged",true);

        // Same revision and source snapshot are idempotent; the retained evidence key never ran.
        assertThat(recalculations.recalculate(fixture.runId(),revision,List.of(changedNeed),plan.get("sourceResultDigest").toString()).get("id")).isEqualTo(receipt.get("id"));
        assertThat(retainedNeed).isNotBlank();

        addNativeFact(fixture);Map<String,Object> refreshed=recalculations.recalculate(fixture.runId(),revision,List.of(changedNeed),plan.get("sourceResultDigest").toString());
        assertThat(refreshed.get("id")).isNotEqualTo(receipt.get("id"));
        assertThat(refreshed.get("evidenceSnapshotDigest")).isNotEqualTo(receipt.get("evidenceSnapshotDigest"));
        assertThat(refreshed).containsEntry("recalculatedCheckpointCount",1).containsEntry("historicalRunUnchanged",true);
        assertThat(recalculations.list(fixture.runId())).hasSize(2);
    }

    @Test void rejectsRevisionAndSourceDigestConflictsAndPersistsUnknownNeedAsNoOp() throws Exception {
        Fixture fixture=fixture();Map<String,Object> graph=bids.templateDependencyGraph(fixture.runId());long revision=((Number)graph.get("revision")).longValue();String changedNeed=needFor(graph,fixture.firstCheckpoint());

        assertThatThrownBy(()->recalculations.recalculate(fixture.runId(),revision+1,List.of(changedNeed),""))
            .isInstanceOfSatisfying(TemplateApiException.class,failure->assertThat(failure.code()).isEqualTo("DEPENDENCY_GRAPH_REVISION_CHANGED"));
        assertThatThrownBy(()->recalculations.recalculate(fixture.runId(),revision,List.of(changedNeed),"not-the-source-digest"))
            .isInstanceOfSatisfying(TemplateApiException.class,failure->assertThat(failure.code()).isEqualTo("SOURCE_FINDINGS_CHANGED"));

        Map<String,Object> noOp=recalculations.recalculate(fixture.runId(),revision,List.of("evidence-key-with-no-subscribers"),graph.get("sourceResultDigest").toString());
        assertThat(noOp).containsEntry("status","NO_OP").containsEntry("noOp",true).containsEntry("recalculatedCheckpointCount",0).containsEntry("changedCheckpointCount",0)
            .containsEntry("historicalRunUnchanged",true);
        assertThat(noOp.get("results")).asList().isEmpty();
        assertThat(noOp.get("retainedCheckpointIds")).asList().containsExactlyInAnyOrder(fixture.firstCheckpoint(),fixture.secondCheckpoint());
    }

    private Fixture fixture() throws Exception {
        String project=BidService.text(bids.createProject(Map.of("name","依赖级增量重算测试")),"id");
        String tender=parsed(project,"TENDER","投标文件应提供营业执照正文和法定代表人身份证明正文。");bids.execute(bids.extract(project,tender));
        for(Checkpoint checkpoint:checkpoints(project))bids.updateCheckpoint(checkpoint.id(),disabled(checkpoint));
        Document tenderDocument=bids.document(tender);Checkpoint first=material(tenderDocument,"营业执照"),second=material(tenderDocument,"法定代表人身份证明");
        store.save("checkpoint",first.id(),project,tender,first);store.save("checkpoint",second.id(),project,tender,second);
        String bid=parsed(project,"BID","投标文件封面\n本页无资格材料正文。");
        Map<String,Object> run=bids.startRun(project,bid);bids.execute(bids.task(run.get("taskId").toString()));
        assertThat(BidService.text(store.getMap("run",run.get("id").toString()),"status")).isEqualTo("COMPLETED");
        return new Fixture(project,run.get("id").toString(),bid,first.id(),second.id());
    }

    private static Checkpoint material(Document tender,String title){String quote=tender.pages().get(0).text();Evidence source=new Evidence(tender.id(),tender.name(),tender.pages().get(0).number(),tender.pages().get(0).locationType(),quote,"tender-requirement",1d);
        return new Checkpoint(Store.id(),title,"形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS",title,"",true,true,true,source);}
    private static Checkpoint disabled(Checkpoint checkpoint){return new Checkpoint(checkpoint.id(),checkpoint.title(),checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),true,false,checkpoint.source());}
    @SuppressWarnings("unchecked") private List<Checkpoint> checkpoints(String project){return (List<Checkpoint>)bids.detail(project).get("checkpoints");}
    private String parsed(String project,String role,String text)throws Exception{TaskRow task=bids.upload(project,role,new MockMultipartFile("file",role.toLowerCase()+".txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));bids.execute(task);return BidService.text(store.map(task.inputJson),"documentId");}

    private static String needFor(Map<String,Object> graph,String checkpointId){Map<?,?> reverse=(Map<?,?>)graph.get("reverseNeedIndex");return reverse.entrySet().stream().filter(entry->entry.getValue() instanceof Collection<?> ids&&ids.contains(checkpointId)).map(entry->entry.getKey().toString()).findFirst().orElseThrow(()->new AssertionError("checkpoint="+checkpointId+", graph="+graph));}
    private void backfillBid(Fixture fixture){Document document=bids.document(fixture.bidDocumentId());String text="营业执照正文\n名称：海南测试有限公司\n统一社会信用代码：91460000TEST000001\n类型：有限责任公司\n住所：海南省海口市测试大道1号\n法定代表人：张三\n注册资本：壹仟万元\n成立日期：2020年1月1日\n营业期限：长期\n经营范围：建筑工程施工。\n法定代表人身份证明正文\n法定代表人：张三\n职务：总经理\n特此证明。";var pages=document.pages().stream().map(page->new cn.bidcheck.domain.Domain.Page(page.number(),text,page.locationType(),page.parser(),page.warning(),page.layout())).toList();store.save("document",document.id(),document.projectId(),"",new Document(document.id(),document.projectId(),document.role(),document.name(),document.version(),document.sha256(),document.createdAt(),pages,document.warnings()));}
    private Map<String,String> findingBodies(Fixture fixture){Map<String,String> bodies=new LinkedHashMap<>();for(String checkpointId:List.of(fixture.firstCheckpoint(),fixture.secondCheckpoint())){String id=BidService.stableId(fixture.runId()+":"+checkpointId);bodies.put(checkpointId,store.require("finding",id).body);}return bodies;}
    private void addNativeFact(Fixture fixture){Map<String,Object> run=store.getMap("run",fixture.runId());Document document=bids.document(BidService.text(run,"documentId"));var page=document.pages().get(0);
        String quote=page.text().contains("营业执照正文")?"营业执照正文":page.text();Evidence evidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),quote,"incremental-fact",1d);
        Fact fact=new Fact("COMPANY","incremental-company","additionalMaterial",quote,quote,evidence);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored->{store.lock("project",fixture.projectId());factLedger.persistNativeFacts(document,List.of(fact),BidService.RULE_VERSION);});}
    private record Fixture(String projectId,String runId,String bidDocumentId,String firstCheckpoint,String secondCheckpoint){}
}

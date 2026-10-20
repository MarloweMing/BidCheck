package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.TemplateApiException;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.TaskRow;
import cn.bidcheck.service.BidService;
import cn.bidcheck.service.ReportService;
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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:template-incremental-publication;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=",
    "bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false"})
class TemplateIncrementalPublicationIntegrationTest {
    private static final String ORIGINAL_BID_TEXT="投标文件封面\n本页无资格材料正文。";
    private static final String COMPLETE_BID_TEXT=ORIGINAL_BID_TEXT
        +"\n营业执照正文\n名称：海南测试有限公司\n统一社会信用代码：91460000TEST000001"
        +"\n类型：有限责任公司\n住所：海南省海口市测试大道1号\n法定代表人：张三"
        +"\n注册资本：壹仟万元\n成立日期：2020年1月1日\n营业期限：长期\n经营范围：建筑工程施工。"
        +"\n法定代表人身份证明正文\n法定代表人：张三\n职务：总经理\n特此证明。";
    static final Path DATA=temp();
    static Path temp(){try{return Files.createTempDirectory("template-incremental-publication-");}catch(Exception failure){throw new IllegalStateException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",DATA::toString);}

    @Autowired BidService bids;
    @Autowired Store store;
    @Autowired TemplateIncrementalRecalculationService recalculations;
    @Autowired TemplateIncrementalPublicationService publications;
    @Autowired ReportService reports;

    @Test void approvalPublishesImmutableVersionAndLeavesHistoricalRunUntouched() throws Exception {
        Fixture fixture=fixture();Map<String,String> originals=findingBodies(fixture);backfillBid(fixture);
        Map<String,Object> candidate=candidate(fixture,fixture.firstCheckpoint());
        assertThat(candidate).containsEntry("changedCheckpointCount",1).containsEntry("publicationState","CANDIDATE_ONLY");
        assertThat(BidService.text(candidate,"candidateContentDigest")).isNotBlank();
        assertThat(publications.decision(fixture.runId(),BidService.text(candidate,"id")))
            .containsEntry("decisionRevision",0).containsEntry("publicationState","CANDIDATE_ONLY");

        Map<String,Object> decision=publications.decide(fixture.runId(),BidService.text(candidate,"id"),request("APPROVE","审核员甲","证据补齐后同意发布",candidate));
        assertThat(decision).containsEntry("decision","APPROVE").containsEntry("decisionRevision",1)
            .containsEntry("publicationState","PUBLISHED").containsEntry("historicalRunUnchanged",true);
        String versionId=BidService.text(decision,"publishedResultVersionId");assertThat(versionId).isNotBlank();
        Map<String,Object> version=publications.version(fixture.runId(),versionId);
        assertThat(version).containsEntry("scope","TEMPLATE_CHECKPOINTS").containsEntry("revision",1)
            .containsEntry("candidateRecordId",candidate.get("id")).containsEntry("historicalRunUnchanged",true);
        assertThat(version.get("results")).asList().hasSize(2);
        assertThat(publications.versions(fixture.runId())).extracting(value->value.get("id")).containsExactly(versionId);
        assertThat(BidService.text(publications.currentHead(fixture.runId()),"currentVersionId")).isEqualTo(versionId);
        @SuppressWarnings("unchecked") Map<String,Object> runPublished=(Map<String,Object>)bids.run(fixture.runId(),false).get("publishedResultVersion");
        assertThat(runPublished)
            .containsEntry("id",versionId).containsEntry("publishedBy","审核员甲").containsEntry("publicationNote","证据补齐后同意发布");

        Map<String,Object> sourceExport=store.map(new String(reports.generate(fixture.runId(),"json").bytes(),StandardCharsets.UTF_8));
        Map<String,Object> currentExport=store.map(new String(reports.generate(fixture.runId(),"json","current").bytes(),StandardCharsets.UTF_8));
        assertThat(sourceExport).doesNotContainKey("resultVersion");
        assertThat(currentExport).containsEntry("resultVersionSelection","PUBLISHED").containsEntry("historicalSourceResultsUnchanged",true);
        @SuppressWarnings("unchecked") Map<String,Object> exportedVersion=(Map<String,Object>)currentExport.get("resultVersion");
        assertThat(exportedVersion).containsEntry("id",versionId).containsEntry("publishedBy","审核员甲");
        assertThat(new String(reports.generate(fixture.runId(),"html","current").bytes(),StandardCharsets.UTF_8))
            .contains("结果版本：V1","发布人：审核员甲","发布说明：证据补齐后同意发布");

        assertThat(findingBodies(fixture)).isEqualTo(originals);
        assertThat(BidService.text(store.getMap("run",fixture.runId()),"status")).isEqualTo("COMPLETED");
        Map<String,Object> decorated=recalculations.get(fixture.runId(),BidService.text(candidate,"id"));
        assertThat(decorated).containsEntry("publicationState","PUBLISHED").containsEntry("publishedResultVersionId",versionId);
        assertThat(recalculations.list(fixture.runId())).singleElement().satisfies(value->assertThat(value).containsEntry("publicationState","PUBLISHED"));

        // An exact retry is idempotent and does not create a second version or audit decision.
        Map<String,Object> retry=publications.decide(fixture.runId(),BidService.text(candidate,"id"),request("APPROVE","审核员甲","证据补齐后同意发布",candidate));
        assertThat(retry).isEqualTo(decision);assertThat(publications.versions(fixture.runId())).hasSize(1);
        assertThat(store.maps(TemplateIncrementalPublicationService.DECISION_KIND,fixture.projectId(),null)).hasSize(1);
        assertThat(store.maps("audit",fixture.projectId(),BidService.text(candidate,"id"))).hasSize(1);
    }

    @Test void rejectionIsFinalAuditedAndDifferentRetryConflicts() throws Exception {
        Fixture fixture=fixture();backfillBid(fixture);Map<String,Object> candidate=candidate(fixture,fixture.firstCheckpoint());
        Map<String,Object> blankNote=new LinkedHashMap<>(request("REJECT","审核员乙","有效说明",candidate));blankNote.put("note","  ");
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(candidate,"id"),blankNote))
            .isInstanceOfSatisfying(TemplateApiException.class,error->{assertThat(error.code()).isEqualTo("SPEC_VALIDATION_FAILED");assertThat(error.fieldErrors()).singleElement().satisfies(field->assertThat(field.get("field")).isEqualTo("note"));});
        Map<String,Object> longNote=new LinkedHashMap<>(request("REJECT","审核员乙","有效说明",candidate));longNote.put("note","核".repeat(3001));
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(candidate,"id"),longNote))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("SPEC_VALIDATION_FAILED"));
        Map<String,Object> wrongRevision=new LinkedHashMap<>(request("REJECT","审核员乙","原文定位仍需核实",candidate));wrongRevision.put("expectedDecisionRevision",1);
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(candidate,"id"),wrongRevision))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("DECISION_REVISION_CHANGED"));
        Map<String,Object> rejected=publications.decide(fixture.runId(),BidService.text(candidate,"id"),request("REJECT","审核员乙","原文定位仍需核实",candidate));
        assertThat(rejected).containsEntry("decision","REJECT").containsEntry("publicationState","REJECTED")
            .containsEntry("publishedResultVersionId","");
        assertThat(publications.versions(fixture.runId())).isEmpty();
        assertThat(publications.decision(fixture.runId(),BidService.text(candidate,"id"))).isEqualTo(rejected);
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(candidate,"id"),request("APPROVE","审核员乙","改为批准",candidate)))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("CANDIDATE_ALREADY_DECIDED"));
    }

    @Test void approvalRejectsStaleEvidenceAndCandidateBasedOnSupersededPublishedVersion() throws Exception {
        Fixture fixture=fixture();backfillBid(fixture);
        Map<String,Object> first=candidate(fixture,fixture.firstCheckpoint());Map<String,Object> second=candidate(fixture,fixture.secondCheckpoint());
        // Any evidence change after calculation fences publication.
        appendBidText(fixture,"\n补充但与检查无关的证据页文字");
        Map<String,Object> staleFirst=first;
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(staleFirst,"id"),request("APPROVE","审核员丙","批准",staleFirst)))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("EVIDENCE_SNAPSHOT_CHANGED"));

        // Recalculate both candidates on one stable base. Publishing one advances the immutable head;
        // the other must be recalculated rather than overwriting retained changes from that version.
        first=candidate(fixture,fixture.firstCheckpoint());second=candidate(fixture,fixture.secondCheckpoint());
        publications.decide(fixture.runId(),BidService.text(first,"id"),request("APPROVE","审核员丙","先发布营业执照",first));
        Map<String,Object> staleSecond=second;
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(staleSecond,"id"),request("APPROVE","审核员丁","再发布身份证明",staleSecond)))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("RESULT_VERSION_BASE_CHANGED"));
        assertThat(publications.versions(fixture.runId())).hasSize(1);
    }

    @Test void approvalBindsCandidateToResolutionPolicyIdentity() throws Exception {
        Fixture fixture=fixture();backfillBid(fixture);Map<String,Object> candidate=candidate(fixture,fixture.firstCheckpoint());
        assertThat(BidService.text(candidate,"resolutionPolicyIdentity")).isEqualTo(bids.resolutionPolicyIdentity());
        Map<String,Object> altered=new LinkedHashMap<>(candidate);altered.put("resolutionPolicyIdentity","different-policy");
        assertThat(publications.candidateDigest(altered)).isNotEqualTo(candidate.get("candidateContentDigest"));

        Map<String,Object> run=new LinkedHashMap<>(store.getMap("run",fixture.runId()));run.put("resolutionPolicyIdentity","superseded-policy");
        store.save("run",fixture.runId(),fixture.projectId(),fixture.bidDocumentId(),run);
        assertThatThrownBy(()->publications.decide(fixture.runId(),BidService.text(candidate,"id"),request("APPROVE","审核员庚","策略变更后不得发布旧候选",candidate)))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("RESULT_POLICY_CHANGED"));
        assertThat(publications.versions(fixture.runId())).isEmpty();
    }

    @Test void recalculationUsesPublishedHeadAsBaselineForNoOpAndReturnToEarlierConclusion() throws Exception {
        Fixture fixture=fixture();setBidText(fixture,COMPLETE_BID_TEXT);
        Map<String,Object> completeCandidate=candidate(fixture,fixture.firstCheckpoint());
        Map<String,Object> completeDecision=publications.decide(fixture.runId(),BidService.text(completeCandidate,"id"),
            request("APPROVE","审核员戊","发布材料完整结论",completeCandidate));
        String completeVersionId=BidService.text(completeDecision,"publishedResultVersionId");
        Map<String,Object> completeVersion=publications.version(fixture.runId(),completeVersionId);

        // Evidence and conclusion are still B. Recalculation is relative to published B, so it is a no-op.
        Map<String,Object> sameConclusion=candidate(fixture,fixture.firstCheckpoint());
        assertThat(sameConclusion).containsEntry("baseResultVersionId",completeVersionId)
            .containsEntry("baseResultVersionRevision",1).containsEntry("baseResultDigest",completeVersion.get("resultDigest"))
            .containsEntry("status","NO_OP").containsEntry("noOp",true).containsEntry("changedCheckpointCount",0);
        assertThat(sameConclusion.get("changedCheckpointIds")).asList().isEmpty();
        assertThat((Map<?,?>)sameConclusion.get("newConclusions")).isEmpty();
        assertThat(sameConclusion.get("results")).asList().singleElement().satisfies(result->
            assertThat(((Map<?,?>)result).get("evaluationChanged")).isEqualTo(false));

        // Evidence returns to A, the source run's original conclusion. It still differs from published B
        // and must therefore produce and publish a second immutable result version.
        setBidText(fixture,ORIGINAL_BID_TEXT);
        Map<String,Object> originalConclusion=candidate(fixture,fixture.firstCheckpoint());
        assertThat(originalConclusion).containsEntry("baseResultVersionId",completeVersionId)
            .containsEntry("baseResultVersionRevision",1).containsEntry("noOp",false).containsEntry("changedCheckpointCount",1);
        assertThat(originalConclusion.get("changedCheckpointIds")).asList().containsExactly(fixture.firstCheckpoint());
        Map<String,Object> originalDecision=publications.decide(fixture.runId(),BidService.text(originalConclusion,"id"),
            request("APPROVE","审核员己","证据回退后发布原结论",originalConclusion));
        Map<String,Object> originalVersion=publications.version(fixture.runId(),BidService.text(originalDecision,"publishedResultVersionId"));
        assertThat(originalVersion).containsEntry("revision",2).containsEntry("previousResultVersionId",completeVersionId);
        assertThat(publications.versions(fixture.runId())).hasSize(2);
    }

    private Map<String,Object> candidate(Fixture fixture,String checkpointId){Map<String,Object> graph=bids.templateDependencyGraph(fixture.runId());long revision=((Number)graph.get("revision")).longValue();String need=needFor(graph,checkpointId);
        Map<String,Object> plan=recalculations.plan(fixture.runId(),revision,List.of(need));return recalculations.recalculate(fixture.runId(),revision,List.of(need),BidService.text(plan,"sourceResultDigest"));}
    private static Map<String,Object> request(String decision,String reviewer,String note,Map<String,Object> candidate){Map<String,Object> request=new LinkedHashMap<>();request.put("decision",decision);request.put("reviewer",reviewer);request.put("note",note);request.put("expectedDecisionRevision",0);
        request.put("expectedSourceResultDigest",candidate.get("sourceResultDigest"));request.put("expectedEvidenceSnapshotDigest",candidate.get("evidenceSnapshotDigest"));return request;}

    private Fixture fixture() throws Exception {
        String project=BidService.text(bids.createProject(Map.of("name","增量候选审核发布测试")),"id");
        String tender=parsed(project,"TENDER","投标文件应提供营业执照正文和法定代表人身份证明正文。");bids.execute(bids.extract(project,tender));
        for(Checkpoint checkpoint:checkpoints(project))bids.updateCheckpoint(checkpoint.id(),disabled(checkpoint));
        Document tenderDocument=bids.document(tender);Checkpoint first=material(tenderDocument,"营业执照"),second=material(tenderDocument,"法定代表人身份证明");
        store.save("checkpoint",first.id(),project,tender,first);store.save("checkpoint",second.id(),project,tender,second);
        String bid=parsed(project,"BID",ORIGINAL_BID_TEXT);Map<String,Object> run=bids.startRun(project,bid);bids.execute(bids.task(run.get("taskId").toString()));
        assertThat(BidService.text(store.getMap("run",run.get("id").toString()),"status")).isEqualTo("COMPLETED");
        return new Fixture(project,run.get("id").toString(),bid,first.id(),second.id());
    }
    private void backfillBid(Fixture fixture){setBidText(fixture,COMPLETE_BID_TEXT);}
    private void setBidText(Fixture fixture,String text){Document document=bids.document(fixture.bidDocumentId());List<Page> pages=document.pages().stream().map(page->new Page(page.number(),text,page.locationType(),page.parser(),page.warning(),page.layout())).toList();
        store.save("document",document.id(),document.projectId(),"",new Document(document.id(),document.projectId(),document.role(),document.name(),document.version(),document.sha256(),document.createdAt(),pages,document.warnings()));}
    private void appendBidText(Fixture fixture,String addition){Document document=bids.document(fixture.bidDocumentId());List<Page> pages=document.pages().stream().map(page->new Page(page.number(),page.text()+addition,page.locationType(),page.parser(),page.warning(),page.layout())).toList();
        store.save("document",document.id(),document.projectId(),"",new Document(document.id(),document.projectId(),document.role(),document.name(),document.version(),document.sha256(),document.createdAt(),pages,document.warnings()));}
    private static Checkpoint material(Document tender,String title){String quote=tender.pages().get(0).text();Evidence source=new Evidence(tender.id(),tender.name(),tender.pages().get(0).number(),tender.pages().get(0).locationType(),quote,"tender-requirement",1d);
        return new Checkpoint(Store.id(),title,"形式检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS",title,"",true,true,true,source);}
    private static Checkpoint disabled(Checkpoint checkpoint){return new Checkpoint(checkpoint.id(),checkpoint.title(),checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),true,false,checkpoint.source());}
    @SuppressWarnings("unchecked") private List<Checkpoint> checkpoints(String project){return (List<Checkpoint>)bids.detail(project).get("checkpoints");}
    private String parsed(String project,String role,String text)throws Exception{TaskRow task=bids.upload(project,role,new MockMultipartFile("file",role.toLowerCase()+".txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));bids.execute(task);return BidService.text(store.map(task.inputJson),"documentId");}
    private static String needFor(Map<String,Object> graph,String checkpointId){Map<?,?> reverse=(Map<?,?>)graph.get("reverseNeedIndex");return reverse.entrySet().stream().filter(entry->entry.getValue() instanceof Collection<?> ids&&ids.contains(checkpointId)).map(entry->entry.getKey().toString()).findFirst().orElseThrow();}
    private Map<String,String> findingBodies(Fixture fixture){Map<String,String> bodies=new LinkedHashMap<>();for(String checkpointId:List.of(fixture.firstCheckpoint(),fixture.secondCheckpoint()))bodies.put(checkpointId,store.require("finding",BidService.stableId(fixture.runId()+":"+checkpointId)).body);return bodies;}
    private record Fixture(String projectId,String runId,String bidDocumentId,String firstCheckpoint,String secondCheckpoint){}
}

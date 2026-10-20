package cn.bidcheck.service;

import cn.bidcheck.checktemplate.CheckSpec;
import cn.bidcheck.checktemplate.CheckSpecificationStoreService;
import cn.bidcheck.checktemplate.TemplateApiException;
import cn.bidcheck.consistency.ConsistencyRunSupport;
import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.persistence.Store;
import cn.bidcheck.persistence.TaskRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:check-set-run;DB_CLOSE_DELAY=-1",
    "bidcheck.tasks.poll-ms=600000","bidcheck.check-mode=RULES_RETRIEVAL","bidcheck.semantic.url=",
    "bidcheck.semantic.key=","bidcheck.semantic.model=","bidcheck.ocr.enabled=false","bidcheck.ocr.pdf.enabled=false"})
class CheckSetRunBindingIntegrationTest {
    static final Path DATA=temp();
    static Path temp(){try{return Files.createTempDirectory("check-set-run-");}catch(Exception failure){throw new IllegalStateException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",DATA::toString);}
    @Autowired BidService service;@Autowired Store store;@Autowired CheckSpecificationStoreService specifications;

    @Test void runUsesTheRequestedConfirmedManifestAndScopesIdempotencyToItsFrozenRequest() throws Exception {
        String project=BidService.text(service.createProject(Map.of("name","检查集绑定测试")),"id");
        String tender=parsed(project,"TENDER","投标人应提供营业执照正文。");service.execute(service.extract(project,tender));
        for(Checkpoint checkpoint:checkpoints(project))service.updateCheckpoint(checkpoint.id(),disabled(checkpoint));
        Document tenderDocument=store.get("document",tender,Document.class);String checkpointId=Store.id();
        Evidence source=new Evidence(tender,tenderDocument.name(),1,tenderDocument.pages().get(0).locationType(),"投标人应提供营业执照正文。","p1-requirement",1d);
        Checkpoint checkpoint=new Checkpoint(checkpointId,"营业执照","资格检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","营业执照","",true,true,true,source);
        store.save("checkpoint",checkpointId,project,tender,checkpoint);

        Map<String,Object> revision1=specifications.putSpec(checkpointId,spec(0,tenderDocument,source,"营业执照"));
        String specId=revision1.get("id").toString();confirmSet(project,tender,"set-a",specId,"confirm-a");
        Map<String,Object> revision2=specifications.putSpec(checkpointId,spec(1,tenderDocument,source,"营业执照副本"));
        assertThat(revision2).containsEntry("revision",2);
        confirmSet(project,tender,"set-b",specId,"confirm-b");

        String bid=parsed(project,"BID","营业执照正文\n统一社会信用代码：91460000TEST000001");
        Map<String,Object> runA=service.startRun(project,bid,false,"set-a",2,"start-fixed-key");
        Map<String,Object> replay=service.startRun(project,bid,false,"set-a",2,"start-fixed-key");
        assertThat(replay.get("id")).isEqualTo(runA.get("id"));
        assertThatThrownBy(()->service.startRun(project,bid,false,"set-b",2,"start-fixed-key"))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        Map<String,Object> runB=service.startRun(project,bid,false,"set-b",2,"start-b-key");

        CheckSpec frozenA=specifications.findSnapshotSpec(project,runA.get("id").toString(),checkpointId).orElseThrow();
        CheckSpec frozenB=specifications.findSnapshotSpec(project,runB.get("id").toString(),checkpointId).orElseThrow();
        assertThat(frozenA.parameters()).containsEntry("materialType","营业执照");
        assertThat(frozenB.parameters()).containsEntry("materialType","营业执照副本");
        assertThat(runA).containsEntry("checkSetId","set-a").containsEntry("manifestRevision",2);
        assertThat(runB).containsEntry("checkSetId","set-b").containsEntry("manifestRevision",2);
        List<Checkpoint> runSnapshot=store.list("snapshot",project,runA.get("id").toString(),Checkpoint.class);
        assertThat(service.allCheckpointsUseFrozenTemplates(project,runA.get("id").toString(),runSnapshot)).isTrue();
        service.execute(service.task(runA.get("taskId").toString()));
        assertThat(store.mapper().get(ConsistencyRunSupport.reportId(runA.get("id").toString()))).isNull();
        assertThat(store.getMap("run",runA.get("id").toString())).doesNotContainKey("retrievalPreparation");

        Checkpoint before=store.get("checkpoint",checkpointId,Checkpoint.class);Checkpoint candidate=new Checkpoint(checkpointId,"不应半保存",before.category(),before.severity(),before.type(),before.field(),before.operator(),before.expected(),before.unit(),before.required(),before.confirmed(),before.enabled(),before.source());
        Map<String,Object> expectedMap=store.map(store.encode(before)),candidateMap=store.map(store.encode(candidate));
        assertThatThrownBy(()->service.updateCheckpointConfiguration(checkpointId,Map.of("expectedCheckpoint",expectedMap,"checkpoint",candidateMap,"spec",spec(99,tenderDocument,source,"错误修订")))).isInstanceOf(TemplateApiException.class);
        assertThat(store.get("checkpoint",checkpointId,Checkpoint.class)).isEqualTo(before);

        service.updateCheckpoint(checkpointId,new Checkpoint(checkpointId,"营业执照（已修改）",checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),true,true,checkpoint.source()));
        assertThatThrownBy(()->service.startRun(project,bid,false,"set-b",2,"changed-checkpoint-key"))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("CHECKPOINT_MANIFEST_CHANGED"));
    }

    private void confirmSet(String project,String tender,String set,String specId,String key){
        specifications.putCheckSet(project,set,Map.of("expectedRevision",0,"tenderDocumentId",tender,"specIds",List.of(specId)));
        specifications.confirmCheckSet(project,set,Map.of("expectedRevision",1,"idempotencyKey",key,"confirmedBy","tester"));
    }
    private Map<String,Object> spec(int expectedRevision,Document tender,Evidence evidence,String materialType){
        Map<String,Object> ref=new LinkedHashMap<>();ref.put("documentId",tender.id());ref.put("documentVersion",tender.version());ref.put("documentHash",tender.sha256());ref.put("parserFingerprint",evidence.anchor());ref.put("sourceHash","");ref.put("locationType",evidence.locationType());ref.put("location",evidence.page());ref.put("quote",evidence.quote());ref.put("startOffset",null);ref.put("endOffset",null);
        Map<String,Object> parameters=Map.of("materialType",materialType,"requireBody",true);List<Map<String,Object>> sources=List.of(ref);
        List<Map<String,Object>> bindings=parameters.entrySet().stream().map(entry->Map.<String,Object>of("parameter",entry.getKey(),"value",entry.getValue(),"origin","MANUAL_CONFIRMED","reason","测试确认","sources",sources)).toList();
        Map<String,Object> request=new LinkedHashMap<>();request.put("expectedRevision",expectedRevision);request.put("businessItemId","license-item");request.put("category","QUALIFICATION");request.put("purpose","MANDATORY");request.put("verificationMode","DOCUMENT");request.put("templateId","MATERIAL_PRESENT");request.put("templateVersion","1.0");request.put("proofStandard","MATERIAL_SUBMISSION");request.put("subjectSelector",Map.of("entityType","ORGANIZATION","role","BIDDER","scope","CURRENT_PROJECT","constraints",Map.of()));request.put("parameters",parameters);request.put("applicability",Map.of("state","APPLIES","reason","","sources",List.of()));request.put("requirementRefs",sources);request.put("parameterBindings",bindings);request.put("requiredFields",Map.of());request.put("evidenceContract",Map.of());request.put("sourceCoverage",Map.of("state","FULL","unsupportedFragments",List.of()));return request;
    }
    @SuppressWarnings("unchecked") private List<Checkpoint> checkpoints(String project){return (List<Checkpoint>)service.detail(project).get("checkpoints");}
    private static Checkpoint disabled(Checkpoint checkpoint){return new Checkpoint(checkpoint.id(),checkpoint.title(),checkpoint.category(),checkpoint.severity(),checkpoint.type(),checkpoint.field(),checkpoint.operator(),checkpoint.expected(),checkpoint.unit(),checkpoint.required(),true,false,checkpoint.source());}
    private String parsed(String project,String role,String text)throws Exception{TaskRow task=service.upload(project,role,new MockMultipartFile("file",role.toLowerCase()+".txt","text/plain",text.getBytes(StandardCharsets.UTF_8)));service.execute(task);return BidService.text(store.map(task.inputJson),"documentId");}
}

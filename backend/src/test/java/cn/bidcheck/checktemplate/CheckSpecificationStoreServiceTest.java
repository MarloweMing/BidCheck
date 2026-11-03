package cn.bidcheck.checktemplate;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:check-spec-store;DB_CLOSE_DELAY=-1","bidcheck.ocr.enabled=false"})
@AutoConfigureMockMvc
class CheckSpecificationStoreServiceTest {
    private static final Path DATA;
    static {try{DATA=Files.createTempDirectory("bidcheck-spec-store-");}catch(Exception failure){throw new IllegalStateException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("bidcheck.data",DATA::toString);}
    @Autowired Store store;
    @Autowired CheckSpecificationStoreService specifications;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String project,tender,checkpoint;

    @BeforeEach void fixture(){
        project=UUID.randomUUID().toString();tender=UUID.randomUUID().toString();checkpoint=UUID.randomUUID().toString();
        store.save("project",project,project,"",cn.bidcheck.compat.Java8Api.mapOf("id", project, "name", "模板测试项目", "activeTenderId", tender));
        Document document=new Document(tender,project,"TENDER","招标.txt",1,cn.bidcheck.compat.Java8Api.repeat("a", 64),"2026-10-19T00:00:00Z",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"应提供营业执照正文。","PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());
        store.save("document",tender,project,"",document);
        Evidence source=new Evidence(tender,"招标.txt",1,"PAGE","应提供营业执照正文。","p1-l1",1d);
        store.save("checkpoint",checkpoint,project,tender,new Checkpoint(checkpoint,"营业执照","资格检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS","营业执照","",true,true,true,source));
    }

    @Test void versionsConfirmsAndFreezesSpecsWithoutOverwritingHistory(){
        Map<String,Object> saved=specifications.putSpec(checkpoint,specRequest(0,"营业执照"));
        assertThat(saved).extracting("revision","confirmed").containsExactly(1,false);
        assertThat(map(saved.get("validation"))).containsEntry("valid",true);
        assertThat(maps(saved.get("requirementRefs")).get(0).get("parserFingerprint"))
            .isEqualTo(cn.bidcheck.verification.facts.FactLedgerService.parserFingerprint(store.get("document",tender,Document.class).pages().get(0)))
            .isNotEqualTo("text-v1");
        assertThatThrownBy(()->specifications.putSpec(checkpoint,specRequest(0,"另一材料")))
            .isInstanceOf(TemplateApiException.class).hasMessageContaining("刷新");
        try {mvc.perform(put("/api/checkpoints/"+checkpoint+"/spec").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(specRequest(0,"另一材料"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT")).andExpect(jsonPath("$.currentRevision").value(1));}
        catch(Exception failure){throw new AssertionError(failure);}

        String specId=saved.get("id").toString();
        Map<String,Object> draft=specifications.putCheckSet(project,"main",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 0, "tenderDocumentId", tender, "specIds", cn.bidcheck.compat.Java8Api.listOf(specId), "sourceCoverage", cn.bidcheck.compat.Java8Api.mapOf("registered", 1)));
        assertThat(draft).containsEntry("revision",1).containsEntry("state","DRAFT");
        Map<String,Object> confirmation=cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 1, "idempotencyKey", "confirm-main-1", "confirmedBy", "tester", "revisionReason", "initial");
        Map<String,Object> confirmed=specifications.confirmCheckSet(project,"main",confirmation);
        assertThat(confirmed).containsEntry("revision",2).containsEntry("state","CONFIRMED");
        assertThat(specifications.confirmCheckSet(project,"main",confirmation)).containsEntry("revision",2);
        assertThat(specifications.findConfirmedSpec(project,checkpoint)).isPresent();

        Checkpoint legacy=store.get("checkpoint",checkpoint,Checkpoint.class);
        String identity=specifications.confirmedSpecsIdentity(project,cn.bidcheck.compat.Java8Api.listOf(legacy));
        assertThat(identity).startsWith("template-specs:");
        Map<String,Object> snapshot=specifications.snapshotForRun(project,"run-1",cn.bidcheck.compat.Java8Api.listOf(legacy));
        assertThat(snapshot.get("contentDigest")).asString().hasSize(64);
        assertThat(specifications.findSnapshotSpec(project,"run-1",checkpoint)).isPresent();
        Map<String,Object> setSnapshot=specifications.freezeSnapshot(project,"run-2","main",2,cn.bidcheck.compat.Java8Api.listOf(tender));
        assertThat(setSnapshot.get("specs")).asList().hasSize(1);

        specifications.putSpec(checkpoint,specRequest(1,"更新后的营业执照"));
        assertThat(specifications.findConfirmedSpec(project,checkpoint).orElseThrow(java.util.NoSuchElementException::new).parameters()).containsEntry("materialType","营业执照");
        Map<String,Object> frozenSet=specifications.getCheckSet(project,"main");
        Map<String,Object> oldSpec=maps(frozenSet.get("specs")).get(0);
        assertThat(map(oldSpec.get("parameters"))).containsEntry("materialType","营业执照");
    }

    @Test void immutableInsertAcceptsSameContentAndRejectsConflictingContent() throws Exception {
        String id="immutable-"+UUID.randomUUID();
        assertThat(store.insertImmutableIfAbsent("check_spec_revision",id,project,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("value", 1)).inserted()).isTrue();
        assertThat(store.insertImmutableIfAbsent("check_spec_revision",id,project,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("value", 1)).inserted()).isFalse();
        assertThatThrownBy(()->store.insertImmutableIfAbsent("check_spec_revision",id,project,checkpoint,cn.bidcheck.compat.Java8Api.mapOf("value", 2)))
            .isInstanceOf(IllegalStateException.class).hasMessage("IMMUTABLE_ID_CONFLICT");
        mvc.perform(get("/api/check-templates")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test void confirmationBindsIdempotencyToTheSetAndReusesPublishedSpecRevision() {
        Map<String,Object> saved=specifications.putSpec(checkpoint,specRequest(0,"营业执照"));String specId=saved.get("id").toString();
        specifications.putCheckSet(project,"set-a",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 0, "tenderDocumentId", tender, "specIds", cn.bidcheck.compat.Java8Api.listOf(specId)));
        Map<String,Object> request=cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 1, "idempotencyKey", "same-key", "confirmedBy", "tester");
        specifications.confirmCheckSet(project,"set-a",request);

        specifications.putCheckSet(project,"set-b",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 0, "tenderDocumentId", tender, "specIds", cn.bidcheck.compat.Java8Api.listOf(specId)));
        assertThatThrownBy(()->specifications.confirmCheckSet(project,"set-b",request))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(specifications.getCheckSet(project,"set-b")).containsEntry("state","DRAFT");

        Map<String,Object> second=specifications.confirmCheckSet(project,"set-b",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 1, "idempotencyKey", "set-b-key", "confirmedBy", "tester"));
        assertThat(second).containsEntry("state","CONFIRMED");
        assertThat(maps(second.get("specs"))).hasSize(1);
    }

    @Test void confirmationRejectsAChangedSpecRevisionAfterTheSetWasReviewed() {
        Map<String,Object> saved=specifications.putSpec(checkpoint,specRequest(0,"营业执照"));String specId=saved.get("id").toString();
        specifications.putCheckSet(project,"stale",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 0, "tenderDocumentId", tender, "specIds", cn.bidcheck.compat.Java8Api.listOf(specId)));
        specifications.putSpec(checkpoint,specRequest(1,"更新后的营业执照"));

        assertThatThrownBy(()->specifications.confirmCheckSet(project,"stale",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 1, "idempotencyKey", "stale-key")))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.code()).isEqualTo("SPEC_REVISION_CHANGED"));
        assertThat(specifications.getCheckSet(project,"stale")).containsEntry("state","DRAFT");
    }

    @Test void nonApplicabilityRequiresAReasonAndAnExactTenderSource() {
        Map<String,Object> missing=specRequest(0,"营业执照");missing.put("applicability",cn.bidcheck.compat.Java8Api.mapOf("state", "DOES_NOT_APPLY", "reason", "", "sources", cn.bidcheck.compat.Java8Api.listOf()));
        assertThatThrownBy(()->specifications.putSpec(checkpoint,missing)).isInstanceOf(TemplateApiException.class)
            .satisfies(error->assertThat(((TemplateApiException)error).fieldErrors().toString()).contains("NON_APPLICABILITY"));

        Map<String,Object> wrongVersion=specRequest(0,"营业执照");
        Map<String,Object> source=new LinkedHashMap<>(maps(wrongVersion.get("requirementRefs")).get(0));source.put("documentVersion",2);
        wrongVersion.put("requirementRefs",cn.bidcheck.compat.Java8Api.listOf(source));
        assertThatThrownBy(()->specifications.putSpec(checkpoint,wrongVersion)).isInstanceOf(TemplateApiException.class)
            .satisfies(error->assertThat(((TemplateApiException)error).fieldErrors().toString()).contains("版本"));
    }

    @Test void checkSetRejectsSpecsFromAnotherTenderAndSourceQuoteMustExistAtTheLocation() {
        Map<String,Object> saved=specifications.putSpec(checkpoint,specRequest(0,"营业执照"));
        String otherTender=UUID.randomUUID().toString();store.save("document",otherTender,project,"",new Document(otherTender,project,"TENDER","招标B.txt",1,cn.bidcheck.compat.Java8Api.repeat("c", 64),"2026-10-19T00:00:00Z",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"另一份招标要求。","PAGE","TEXT","")),cn.bidcheck.compat.Java8Api.listOf()));
        assertThatThrownBy(()->specifications.putCheckSet(project,"cross-tender",cn.bidcheck.compat.Java8Api.mapOf("expectedRevision", 0, "tenderDocumentId", otherTender, "specIds", cn.bidcheck.compat.Java8Api.listOf(saved.get("id")))))
            .isInstanceOfSatisfying(TemplateApiException.class,error->assertThat(error.fieldErrors().toString()).contains("不属于当前检查集"));

        Map<String,Object> forged=specRequest(1,"营业执照");Map<String,Object> source=new LinkedHashMap<>(maps(forged.get("requirementRefs")).get(0));source.put("quote","招标原文中并不存在的要求");
        forged.put("requirementRefs",cn.bidcheck.compat.Java8Api.listOf(source));forged.put("parameterBindings",bindings(map(forged.get("parameters")),cn.bidcheck.compat.Java8Api.listOf(source)));
        assertThatThrownBy(()->specifications.putSpec(checkpoint,forged)).isInstanceOf(TemplateApiException.class)
            .satisfies(error->assertThat(((TemplateApiException)error).fieldErrors().toString()).contains("原文不匹配"));
    }

    private Map<String,Object> specRequest(int expectedRevision,String materialType){
        Map<String,Object> source=new LinkedHashMap<>();source.put("documentId",tender);source.put("documentVersion",1);source.put("documentHash",cn.bidcheck.compat.Java8Api.repeat("a", 64));source.put("parserFingerprint","text-v1");source.put("sourceHash",cn.bidcheck.compat.Java8Api.repeat("b", 64));source.put("locationType","PAGE");source.put("location",1);source.put("quote","应提供营业执照正文。");source.put("startOffset",null);source.put("endOffset",null);
        Map<String,Object> request=new LinkedHashMap<>();request.put("expectedRevision",expectedRevision);request.put("businessItemId","business-license");request.put("category","QUALIFICATION");request.put("purpose","MANDATORY");request.put("verificationMode","DOCUMENT");request.put("templateId","MATERIAL_PRESENT");request.put("templateVersion","1.0");request.put("proofStandard","MATERIAL_SUBMISSION");
        Map<String,Object> parameters=cn.bidcheck.compat.Java8Api.mapOf("materialType", materialType, "requireBody", true);
        request.put("subjectSelector",cn.bidcheck.compat.Java8Api.mapOf("entityType", "ORGANIZATION", "role", "BIDDER", "scope", "CURRENT_PROJECT", "constraints", cn.bidcheck.compat.Java8Api.mapOf()));request.put("parameters",parameters);request.put("applicability",cn.bidcheck.compat.Java8Api.mapOf("state", "APPLIES", "reason", "", "sources", cn.bidcheck.compat.Java8Api.listOf()));request.put("requirementRefs",cn.bidcheck.compat.Java8Api.listOf(source));request.put("parameterBindings",bindings(parameters,cn.bidcheck.compat.Java8Api.listOf(source)));request.put("requiredFields",cn.bidcheck.compat.Java8Api.mapOf());request.put("evidenceContract",cn.bidcheck.compat.Java8Api.mapOf());request.put("sourceCoverage",cn.bidcheck.compat.Java8Api.mapOf("state", "FULL", "mapped", true, "unsupportedFragments", cn.bidcheck.compat.Java8Api.listOf()));return request;
    }
    private static List<Map<String,Object>> bindings(Map<String,Object> parameters,List<Map<String,Object>> sources){return cn.bidcheck.compat.Java8Api.toList(parameters.entrySet().stream().map(entry->cn.bidcheck.compat.Java8Api.<String, Object>mapOf("parameter", entry.getKey(), "value", entry.getValue(), "origin", "MANUAL_CONFIRMED", "reason", "测试确认", "sources", sources)));}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}

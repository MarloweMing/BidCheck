package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSemanticProgressTest {
    @Test void nativeAppointmentReadRecomputesWithoutAnExtraModelTurn(){
        var h=new EvidenceInvestigationTest().host("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        var result=h.call("search_evidence",Map.of("query","项目负责人"));
        assertFalse(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());
        var receipt=(Map<?,?>)result.get("computationReceipt");assertNotNull(receipt);
        assertEquals("HOST_RULE_RECOMPUTE",receipt.get("kind"));assertEquals(true,receipt.get("targetCheckCompleted"));
        assertEquals(result.get("targetEvidenceRevision"),receipt.get("targetEvidenceRevision"));
        assertTrue(result.get("expectedFactContracts").toString().contains("测试甲"));
    }
    EvidenceInvestigation.Host certificateHost(){
        var helper=new EvidenceInvestigationTest();var corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n姓名：测试甲\n注册建造师证书")));
        Rule rule=new Rule("cert","负责人证书","CERTIFICATE","项目负责人","证书","EXISTS","注册建造师证书","",Map.of(),"负责人应具备证书","",List.of());
        Map<String,Object> ctx=new LinkedHashMap<>();return new EvidenceInvestigation.Host(helper.checkpoint(),new Plan("ALL",List.of(rule),"LOCAL",List.of()),List.of(rule),corpus,new LinkedHashMap<>(),ctx,new SharedFactReuse(corpus,ctx,"test","test"),new RunSession(ModelGateway.disabled(),ctx,"test"),null,new ArrayList<>(),Set.of());
    }
    @Test void roleAsCertificateSubjectGetsTypedRepairAndCannotBecomeVisualWork(){
        var h=certificateHost();String source=h.corpus.all.get(0).id();h.disclosed.put(source,h.corpus.all.get(0));
        var fact=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","value","注册建造师证书","sourceIds",List.of(source),"attributes",Map.of());
        var result=h.call("submit_candidate_fact",Map.of("fact",fact));
        assertEquals("REJECTED",result.get("status"));assertEquals(List.of("RESOLVE_SUBJECT_FIRST"),result.get("recoveryActions"));
        assertTrue(result.get("rejections").toString().contains("SUBJECT_BINDING_REQUIRED"));assertFalse(str(result,"rejectionPath").isBlank());
        assertFalse(h.call("inspect_page_region",Map.of("sourceId",source,"field","证书")).containsKey("observations"));
        assertTrue(h.visualRequests.isEmpty());assertTrue(h.facts.isEmpty());
    }
    @Test void rejectionKeepsCandidateDiagnosticAndSamePathIgnoresExplanation(){
        var h=certificateHost();String source=h.corpus.all.get(0).id();
        var fact=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","value","注册建造师证书","basis","解释一","sourceIds",List.of(source));
        var a=h.call("submit_candidate_fact",Map.of("fact",fact));fact.put("basis","解释二");
        var b=h.call("submit_candidate_fact",Map.of("fact",fact));assertEquals(a.get("rejectionPath"),b.get("rejectionPath"));assertEquals(a.get("targetEvidenceRevision"),b.get("targetEvidenceRevision"));
        assertTrue(h.session.steps.toString().contains("candidateDiagnostic"));assertTrue(h.session.steps.toString().contains("注册建造师证书"));
    }
    @Test void validationFailureDoesNotAutomaticallyRequestAnImage(){
        for(String code:List.of("RAW_VALUE_UNGROUNDED","VALUE_UNGROUNDED","RELATION_ENDPOINTS_UNPROVEN","CERTIFICATE_SUBJECT_UNPROVEN","SUBJECT_BINDING_REQUIRED","FACT_OUTSIDE_TARGET","MONTH_ROW_UNPROVEN"))assertFalse(RecoveryFailureRouting.candidateNeedsImage(code),code);
        assertEquals("READ_SOURCE_OR_TABLE",RecoveryFailureRouting.candidateAction("RAW_VALUE_UNGROUNDED"));
        assertTrue(RecoveryFailureRouting.candidateNeedsImage("OCR_TEXT_UNREADABLE"));
        assertTrue(RecoveryFailureRouting.requirement("COMMITMENT_REQUIREMENT_BINDING_REQUIRED"));
    }
    @Test void aNewValidatedLayoutChangesOnlyItsSourcesFailureRevision(){
        var h=certificateHost();Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        var candidate=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","value","注册建造师证书","sourceIds",List.of(source.id()));
        var before=h.call("submit_candidate_fact",Map.of("fact",candidate));
        Map<String,Object> cell=linked("documentId","bid","documentHash",source.documentHash(),"documentVersion",1,"locationType",source.evidence().locationType(),"location",source.evidence().page(),"spanId","cell-one","kind","TABLE_CELL","text","测试甲");
        h.ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,List<Map<String,Object>>>)q->List.of(cell));
        var read=h.call("read_table_record",Map.of("sourceId",source.id(),"rowOffset",0));
        var after=h.call("submit_candidate_fact",Map.of("fact",candidate));
        assertNotEquals(before.get("targetEvidenceRevision"),read.get("targetEvidenceRevision"));
        assertNotEquals(before.get("rejectionEvidenceRevision"),after.get("rejectionEvidenceRevision"));
        cell.put("location",999);h.ctx.put("_validatedLayoutCells",List.of(cell));
        var unrelated=h.call("submit_candidate_fact",Map.of("fact",candidate));
        assertEquals(before.get("rejectionEvidenceRevision"),unrelated.get("rejectionEvidenceRevision"));
    }
    @Test void aResolvedBindingDoesNotPermanentlyBlockReadingAnActualUnclearField(){
        var h=certificateHost();Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        h.call("submit_candidate_fact",Map.of("fact",linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","sourceIds",List.of(source.id()))));
        assertEquals("REJECTED",h.call("inspect_page_region",Map.of("sourceId",source.id(),"field","证书")).get("status"));
        h.call("search_evidence",Map.of("query","本项目项目负责人姓名"));
        assertFalse(h.facts.isEmpty());
        assertEquals("PENDING",h.call("inspect_page_region",Map.of("sourceId",source.id(),"field","证书")).get("status"));
    }
}

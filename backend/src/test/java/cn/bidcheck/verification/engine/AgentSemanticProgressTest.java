package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSemanticProgressTest {
    @Test void nativeAppointmentReadRecomputesWithoutAnExtraModelTurn(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=new EvidenceInvestigationTest().host("本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        java.util.Map<java.lang.String, java.lang.Object> result=h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));
        assertFalse(ProofVerifier.strings(result.get("acceptedFactIds")).isEmpty());
        java.util.Map<?, ?> receipt=(Map<?,?>)result.get("computationReceipt");assertNotNull(receipt);
        assertEquals("HOST_RULE_RECOMPUTE",receipt.get("kind"));assertEquals(true,receipt.get("targetCheckCompleted"));
        assertEquals(result.get("targetEvidenceRevision"),receipt.get("targetEvidenceRevision"));
        assertTrue(result.get("expectedFactContracts").toString().contains("测试甲"));
    }
    EvidenceInvestigation.Host certificateHost(){
        cn.bidcheck.verification.engine.EvidenceInvestigationTest helper=new EvidenceInvestigationTest();cn.bidcheck.verification.engine.EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n姓名：测试甲\n注册建造师证书")));
        Rule rule=new Rule("cert","负责人证书","CERTIFICATE","项目负责人","证书","EXISTS","注册建造师证书","",cn.bidcheck.compat.Java8Api.mapOf(),"负责人应具备证书","",cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> ctx=new LinkedHashMap<>();return new EvidenceInvestigation.Host(helper.checkpoint(),new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(rule),"LOCAL",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(rule),corpus,new LinkedHashMap<>(),ctx,new SharedFactReuse(corpus,ctx,"test","test"),new RunSession(ModelGateway.disabled(),ctx,"test"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    @Test void roleAsCertificateSubjectGetsTypedRepairAndCannotBecomeVisualWork(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=certificateHost();String source=h.corpus.all.get(0).id();h.disclosed.put(source,h.corpus.all.get(0));
        java.util.Map<java.lang.String, java.lang.Object> fact=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","value","注册建造师证书","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf());
        java.util.Map<java.lang.String, java.lang.Object> result=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact));
        assertEquals("REJECTED",result.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf("RESOLVE_SUBJECT_FIRST"),result.get("recoveryActions"));
        assertTrue(result.get("rejections").toString().contains("SUBJECT_BINDING_REQUIRED"));assertFalse(cn.bidcheck.compat.Java8Api.isBlank(str(result,"rejectionPath")));
        assertFalse(h.call("inspect_page_region",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source, "field", "证书")).containsKey("observations"));
        assertTrue(h.visualRequests.isEmpty());assertTrue(h.facts.isEmpty());
    }
    @Test void rejectionKeepsCandidateDiagnosticAndSamePathIgnoresExplanation(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=certificateHost();String source=h.corpus.all.get(0).id();
        java.util.Map<java.lang.String, java.lang.Object> fact=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","value","注册建造师证书","basis","解释一","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source));
        java.util.Map<java.lang.String, java.lang.Object> a=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact));fact.put("basis","解释二");
        java.util.Map<java.lang.String, java.lang.Object> b=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact));assertEquals(a.get("rejectionPath"),b.get("rejectionPath"));assertEquals(a.get("targetEvidenceRevision"),b.get("targetEvidenceRevision"));
        assertTrue(h.session.steps.toString().contains("candidateDiagnostic"));assertTrue(h.session.steps.toString().contains("注册建造师证书"));
    }
    @Test void validationFailureDoesNotAutomaticallyRequestAnImage(){
        for(String code:cn.bidcheck.compat.Java8Api.listOf("RAW_VALUE_UNGROUNDED", "VALUE_UNGROUNDED", "RELATION_ENDPOINTS_UNPROVEN", "CERTIFICATE_SUBJECT_UNPROVEN", "SUBJECT_BINDING_REQUIRED", "FACT_OUTSIDE_TARGET", "MONTH_ROW_UNPROVEN"))assertFalse(RecoveryFailureRouting.candidateNeedsImage(code),code);
        assertEquals("READ_SOURCE_OR_TABLE",RecoveryFailureRouting.candidateAction("RAW_VALUE_UNGROUNDED"));
        assertTrue(RecoveryFailureRouting.candidateNeedsImage("OCR_TEXT_UNREADABLE"));
        assertTrue(RecoveryFailureRouting.requirement("COMMITMENT_REQUIREMENT_BINDING_REQUIRED"));
    }
    @Test void aNewValidatedLayoutChangesOnlyItsSourcesFailureRevision(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=certificateHost();Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        java.util.Map<java.lang.String, java.lang.Object> candidate=linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","value","注册建造师证书","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source.id()));
        java.util.Map<java.lang.String, java.lang.Object> before=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", candidate));
        Map<String,Object> cell=linked("documentId","bid","documentHash",source.documentHash(),"documentVersion",1,"locationType",source.evidence().locationType(),"location",source.evidence().page(),"spanId","cell-one","kind","TABLE_CELL","text","测试甲");
        h.ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,List<Map<String,Object>>>)q->cn.bidcheck.compat.Java8Api.listOf(cell));
        java.util.Map<java.lang.String, java.lang.Object> read=h.call("read_table_record",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id(), "rowOffset", 0));
        java.util.Map<java.lang.String, java.lang.Object> after=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", candidate));
        assertNotEquals(before.get("targetEvidenceRevision"),read.get("targetEvidenceRevision"));
        assertNotEquals(before.get("rejectionEvidenceRevision"),after.get("rejectionEvidenceRevision"));
        cell.put("location",999);h.ctx.put("_validatedLayoutCells",cn.bidcheck.compat.Java8Api.listOf(cell));
        java.util.Map<java.lang.String, java.lang.Object> unrelated=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", candidate));
        assertEquals(before.get("rejectionEvidenceRevision"),unrelated.get("rejectionEvidenceRevision"));
    }
    @Test void aResolvedBindingDoesNotPermanentlyBlockReadingAnActualUnclearField(){
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=certificateHost();Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", linked("kind","CERTIFICATE","subject","项目负责人","field","证书","rawValue","注册建造师证书","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source.id()))));
        assertEquals("REJECTED",h.call("inspect_page_region",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id(), "field", "证书")).get("status"));
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "本项目项目负责人姓名"));
        assertFalse(h.facts.isEmpty());
        assertEquals("PENDING",h.call("inspect_page_region",cn.bidcheck.compat.Java8Api.mapOf("sourceId", source.id(), "field", "证书")).get("status"));
    }
}

package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class InferenceClaimsTest {
    @Test void automaticNormalizationRequiresDeterministicEquivalenceAndThreshold(){
        Rule rule=new Rule("grade","资质等级","CERTIFICATE","测试单位","grade",">=","二级","",cn.bidcheck.compat.Java8Api.mapOf(),"二级及以上","",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=new EvidenceInvestigationTest().host(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(rule),"LOCAL",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(rule),"企业名称：测试单位\n资质等级：贰级");Source source=h.corpus.all.get(0);h.disclosed.put(source.id(),source);
        Map<String,Object> inference=linked("type","GRADE_NORMALIZATION","claim","等级贰级等价二级","reasoning","中文数字等价归一化","confidence",0.95,"risks",cn.bidcheck.compat.Java8Api.listOf(),"evidence",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",source.id(),"quote","资质等级：贰级")));
        Map<String,Object> p=linked("kind","INFERRED","rawValue","贰级","field","grade","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source.id()),"attributes",linked("inference",inference,"proposedFact",linked("kind","CERTIFICATE","subject","测试单位","field","grade","rawValue","贰级","value","二级","scope","UNRESOLVED","attributes",cn.bidcheck.compat.Java8Api.mapOf("grade", "贰级"))));
        h.ctx.put("inferenceNormalizationMinConfidence","NaN");assertEquals("NEEDS_CONFIRMATION",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "grade", "fact", p)).get("status"));assertTrue(h.facts.isEmpty());
        inference.remove("type");
        h.ctx.put("inferenceNormalizationMinConfidence","0.9");assertFalse(ProofVerifier.strings(h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "grade", "fact", p)).get("acceptedFactIds")).isEmpty());
    }
    EvidenceInvestigation.Host host(){return new EvidenceInvestigationTest().host("项目负责人相关证明资料\n姓名：张*超\n注册编号：京123456");}
    Map<String,Object> proposal(EvidenceInvestigation.Host h){Source s=h.corpus.all.get(0);h.disclosed.put(s.id(),s);
        return linked("kind","INFERRED","field","role","rawValue","张*超","sourceIds",cn.bidcheck.compat.Java8Api.listOf(s.id()),"attributes",linked(
            "inference",linked("claim","打码姓名可能指张国超","reasoning","同一组任职与注册证材料，需人工确认是否同一人。","confidence",1d,"risks",cn.bidcheck.compat.Java8Api.listOf("MASKED_NAME"),"evidence",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",s.id(),"quote","姓名：张*超"))),
            "proposedFact",linked("kind","PERSON_ROLE","subject","项目负责人","field","role","rawValue","张*超","value","张国超","scope","CURRENT_PROJECT","attributes",linked("sourceVerified",true,"serverValidated",true))));}
    @Test void highConfidenceCannotAcceptMaskedNameAndForgedCitationsAreRejected(){cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();Map<String,Object> p=proposal(h);
        java.util.Map<java.lang.String, java.lang.Object> receipt=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "role", "fact", p));assertEquals("NEEDS_CONFIRMATION",receipt.get("status"));assertTrue(h.facts.isEmpty());assertNotEquals("PASS",h.decision.status());
        java.util.Map<java.lang.String, java.lang.Object> proposed=RuleEvaluator.maps(h.ctx.get("inferredFacts")).get(0);assertEquals(cn.bidcheck.compat.Java8Api.mapOf(),((Map<?,?>)proposed.get("proposedFact")).get("attributes"));
        Map<String,Object> bad=proposal(h);Map<String,Object> attrs=(Map<String,Object>)bad.get("attributes");((Map<String,Object>)attrs.get("inference")).put("evidence",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("sourceId", h.corpus.all.get(0).id(), "quote", "姓名：张国超")));
        assertEquals("INFERENCE_CITATION_UNGROUNDED",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "role", "fact", bad)).get("error"));}
    @Test void confirmedInferenceLoadsOnlyForCurrentSourceAndRecomputesRole(){cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "role", "fact", proposal(h)));
        Map<String,Object> saved=new LinkedHashMap<>(RuleEvaluator.maps(h.ctx.get("inferredFacts")).get(0));saved.putAll(linked("status","CONFIRMED","actor","审核人","decidedAt","2026-11-02"));
        Map<String,Object> context=new LinkedHashMap<>();context.put("confirmedInferenceLookup",(Supplier<List<Map<String,Object>>>)(()->cn.bidcheck.compat.Java8Api.listOf(saved)));
        List<Fact> facts=InferenceClaims.confirmed(context,h.corpus);assertEquals(1,facts.size());assertTrue(InferenceClaims.humanConfirmed(facts.get(0)));
        Decision decision=new RuleEvaluator(facts,h.corpus,context).evaluate(new EvidenceInvestigationTest().rule());assertEquals("PASS",decision.status(),decision.toString());
        assertTrue(InferenceClaims.confirmed(cn.bidcheck.compat.Java8Api.mapOf("confirmedInferenceLookup", cn.bidcheck.compat.Java8Api.listOf(saved)),h.corpus).isEmpty(),"JSON cannot supply trusted callback");
        EvidenceCorpus changed=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",2,"姓名：张*超")));assertTrue(InferenceClaims.confirmed(context,changed).isEmpty());}
    @Test void proposalDoesNotMaskUncompiledOrFailedObligation(){cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "role", "fact", proposal(h)));
        Rule rule=new EvidenceInvestigationTest().rule();Decision unresolved=new Decision("role","任职","UNDETERMINED","失败",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(new Gap("MODEL_HTTP_503","失败","重试","RETRY_FAILED_STEP")),cn.bidcheck.compat.Java8Api.mapOf());
        Decision pending=InferenceClaims.pending(rule,unresolved,h.ctx);assertEquals("UNDETERMINED",pending.status());assertEquals(unresolved.gaps(),pending.gaps());}
    @Test void rejectedInferenceIsNotProposedAgainUntilEvidenceChanges(){cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host();Map<String,Object> p=proposal(h);h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "role", "fact", p));Map<String,Object> old=RuleEvaluator.maps(h.ctx.get("inferredFacts")).get(0);
        h.ctx.put("inferenceDecisionsLookup",(Supplier<List<Map<String,Object>>>)(()->cn.bidcheck.compat.Java8Api.listOf(linked("engineClaimId",old.get("id"),"id","saved","status","REJECTED"))));
        assertEquals("INFERENCE_PREVIOUSLY_REJECTED",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "role", "fact", p)).get("error"));assertTrue(h.facts.isEmpty());}
}

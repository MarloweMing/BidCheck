package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgrammaticInferenceCandidatesTest {
    final EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","p",1,"项目负责人相关证明\n姓名：张*超\n注册编号：京123456")));
    final Source source=corpus.all.get(0);
    final Rule rule=new Rule("role","任职关联","PERSON_ROLE","项目负责人","personName","EXISTS","","",cn.bidcheck.compat.Java8Api.mapOf(),"拟派项目负责人","",cn.bidcheck.compat.Java8Api.listOf());
    Map<String,Object> candidate(){return linked("type","MASKED_PERSON_IDENTITY","claim","请确认张*超是否对应张国超","reasoning","已定位打码姓名原文，需审核者确认对应关系，不能自动替换姓名。","confidence",0.6,"risks",cn.bidcheck.compat.Java8Api.listOf("MASKED_NAME"),
        "proposedFact",linked("kind","PERSON_ROLE","subject","项目负责人","field","personName","rawValue","张*超","value","张国超","scope","CURRENT_PROJECT","attributes",cn.bidcheck.compat.Java8Api.mapOf()),
        "evidence",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",source.id(),"quote","姓名：张*超")),"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.listOf("PERSON_ROLE_REQUIRED"));}
    Decision unresolved(Map<String,Object> candidate){return new Decision(rule.id(),rule.description(),"UNDETERMINED","关系待确认",cn.bidcheck.compat.Java8Api.listOf(source.evidence()),cn.bidcheck.compat.Java8Api.listOf(gap("PERSON_ROLE_REQUIRED","尚未确定任职人员")),linked("confirmationCandidates",cn.bidcheck.compat.Java8Api.listOf(candidate)));}
    @Test void groundedProgramCandidatePersistsWithoutAnyAgentSubmissionAndDoesNotBecomePass(){
        List<Map<String,Object>> saved=new ArrayList<>(),logs=new ArrayList<>();Map<String,Object> context=new HashMap<>();context.put("inferencePublisher",(Consumer<Map<String,Object>>)saved::add);context.put("inferenceAudit",(Consumer<Map<String,Object>>)logs::add);
        Decision result=InferenceClaims.discover(rule,unresolved(candidate()),cn.bidcheck.compat.Java8Api.listOf(),corpus,context);
        assertEquals("NEEDS_CONFIRMATION",result.status());assertEquals(1,saved.size());assertEquals(ProgrammaticInferenceCandidates.VERSION,saved.get(0).get("origin"));
        assertTrue(logs.stream().anyMatch(row->"INFERENCE_PENDING_CONFIRMATION".equals(row.get("type"))));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),InferenceClaims.confirmed(context,corpus),"Unconfirmed question is never a trusted fact");
        InferenceClaims.discover(rule,unresolved(candidate()),cn.bidcheck.compat.Java8Api.listOf(),corpus,context);assertEquals(1,saved.size(),"Repeated recomputation must not create duplicate cards");
    }
    @Test void unsupportedQuoteAndPureSystemFailureDoNotCreateManualWork(){
        List<Map<String,Object>> saved=new ArrayList<>();Map<String,Object> context=new HashMap<>();context.put("inferencePublisher",(Consumer<Map<String,Object>>)saved::add);
        Map<String,Object> fabricated=candidate();fabricated.put("evidence",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",source.id(),"quote","张国超已任命")));
        assertEquals("UNDETERMINED",InferenceClaims.discover(rule,unresolved(fabricated),cn.bidcheck.compat.Java8Api.listOf(),corpus,context).status());assertTrue(saved.isEmpty());
        Decision failure=new Decision(rule.id(),rule.description(),"UNDETERMINED","请求失败",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("MODEL_HTTP_503","服务失败")),linked("confirmationCandidates",cn.bidcheck.compat.Java8Api.listOf(candidate())));
        assertEquals("UNDETERMINED",InferenceClaims.discover(rule,failure,cn.bidcheck.compat.Java8Api.listOf(),corpus,context).status());assertTrue(saved.isEmpty());
    }
    @Test void sourcePairContractCandidateCanAskAboutNonliteralAliasButCannotAutoCountIt(){
        EvidenceCorpus c=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","p",1,"业绩申报表\n合成大楼装饰项目"), SharedFactReuseTest.doc("attachment","p",1,"合成大楼装修合同\n合同金额：200万元")));
        Source first=c.all.get(0),second=c.all.get(1);Rule r=new Rule("contract","业绩","SCORE_COUNT","投标人","performance","AT_LEAST","1","",cn.bidcheck.compat.Java8Api.mapOf(),"业绩须对应申报清单","",cn.bidcheck.compat.Java8Api.listOf());
        Map<String,Object> proposed=linked("kind","RELATION","subject","合成大楼装修合同","field","CONTRACT_RECORD_MATCH","rawValue","合成大楼装饰项目","value","合成大楼装饰项目","scope","HISTORICAL_PROJECT","attributes",linked("object","合成大楼装饰项目","inventoryRow","1","contractSourceIds",cn.bidcheck.compat.Java8Api.listOf(second.id())));
        Map<String,Object> candidate=linked("type","CONTRACT_RECORD_MATCH","claim","请确认申报表与合同是否同一项目","reasoning","名称相似且存在两端原文，仍需确认项目对应。","confidence",0.7,"risks",cn.bidcheck.compat.Java8Api.listOf("名称不同"),"proposedFact",proposed,"evidence",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",first.id(),"quote","合成大楼装饰项目"), linked("sourceId",second.id(),"quote","合成大楼装修合同")),"unresolvedGapCodes",cn.bidcheck.compat.Java8Api.listOf("PERFORMANCE_PROOF_COVERAGE_REQUIRED"));
        Decision d=new Decision(r.id(),r.description(),"UNDETERMINED","申报行关系待定",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("PERFORMANCE_PROOF_COVERAGE_REQUIRED","关系待定")),linked("confirmationCandidates",cn.bidcheck.compat.Java8Api.listOf(candidate),"confirmationCoversAllGaps",true));
        List<Map<String,Object>> saved=new ArrayList<>();Map<String,Object> context=new HashMap<>();context.put("inferencePublisher",(Consumer<Map<String,Object>>)saved::add);
        assertEquals("NEEDS_CONFIRMATION",InferenceClaims.discover(r,d,cn.bidcheck.compat.Java8Api.listOf(),c,context).status());assertEquals(1,saved.size());assertEquals("1",((Map<?,?>)((Map<?,?>)saved.get(0).get("proposedFact")).get("attributes")).get("inventoryRow"));
        assertTrue(InferenceClaims.confirmed(context,c).isEmpty());
    }
    @Test void addingContractQuestionDoesNotHideOtherMissingRequirements(){
        java.util.Map<java.lang.String, java.lang.Object> candidate=candidate();Decision d=new Decision(rule.id(),rule.description(),"UNDETERMINED","另缺材料",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("PERSON_ROLE_REQUIRED","关系"), gap("MATERIAL_REQUIRED","其他材料")),linked("confirmationCandidates",cn.bidcheck.compat.Java8Api.listOf(candidate)));
        Map<String,Object> context=new HashMap<>();context.put("enableProgrammaticInferences",true);
        Decision result=InferenceClaims.discover(rule,d,cn.bidcheck.compat.Java8Api.listOf(),corpus,context);assertEquals("UNDETERMINED",result.status());assertEquals(2,result.gaps().size());assertFalse(RuleEvaluator.maps(result.proof().get("inferredFacts")).isEmpty());
    }
}

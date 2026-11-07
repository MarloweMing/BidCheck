package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgrammaticInferenceCandidatesTest {
    @Test void oneCheckpointSourcePairHasOneCardIdentityAcrossTargetsAndOneDecisionApplies(){
        ConfirmedSubjectBindingsTest fixture=new ConfirmedSubjectBindingsTest();EvidenceCorpus c=fixture.corpus();
        List<Fact> facts=Arrays.asList(fixture.role(c),fixture.certificate(c));
        Rule first=fixture.rule(false);
        assertFalse(new SubjectIdentity(facts,c,Collections.emptyList()).samePerson(facts.get(0),facts.get(1)));
        Rule second=new Rule("another-target",first.description(),first.predicate(),first.subject(),first.field(),first.operator(),first.expected(),first.unit(),first.parameters(),first.sourceQuote(),"",Collections.emptyList());
        Map<String,Object> context=linked("enableProgrammaticInferences",true,"inferenceCheckpointId","synthetic-shared-checkpoint");
        List<Decision> decisions=new ArrayList<>();
        for(Rule target:Arrays.asList(first,second))decisions.add(InferenceClaims.discover(target,new Decision(target.id(),target.description(),"UNDETERMINED","身份待确认",Collections.emptyList(),Collections.singletonList(gap("ENTITY_IDENTITY_AMBIGUOUS","对应关系")),Collections.emptyMap()),facts,c,context));
        List<Map<String,Object>> cards=RuleEvaluator.maps(context.get("inferredFacts"));
        assertEquals(1,cards.stream().map(card->card.get("id")).distinct().count());
        assertEquals(1,RuleEvaluator.combine("root","两个条件","ALL",decisions).proof().get("pendingConfirmationCount"));
        Map<String,Object> confirmed=new LinkedHashMap<>(cards.get(0));confirmed.put("status","CONFIRMED");confirmed.put("engineClaimId",confirmed.get("id"));confirmed.put("checkpointId","synthetic-shared-checkpoint");
        context.put("confirmedInferenceLookup",(java.util.function.Supplier<Object>)()->Collections.singletonList(confirmed));
        List<Fact> approved=InferenceClaims.confirmed(context,c);assertEquals(1,approved.size());
        List<Fact> bound=new ArrayList<>(facts);bound.addAll(approved);
        assertTrue(new SubjectIdentity(bound,c,Collections.emptyList()).samePerson(facts.get(0),facts.get(1)));
        RuleEvaluator recomputed=new RuleEvaluator(bound,c,Collections.emptyMap());
        assertEquals("PASS",recomputed.evaluate(first).status());assertEquals("PASS",recomputed.evaluate(second).status());
        assertEquals("UNDETERMINED",decisions.get(0).status());assertEquals("UNDETERMINED",decisions.get(1).status());
        assertTrue(approved.stream().noneMatch(f->"CERTIFICATE".equals(f.kind())),"Confirmation adds only the relationship, not certificate compliance");
    }
    @Test void scoringTitleGapWithMaskedZhangNameCreatesQuestionWithoutAutomaticIdentity(){
        PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();
        EvidenceCorpus c=fixture.corpus(fixture.appointment("项目负责人","张国超","110101199001010011","",""),
            "全国职称评审信息查询\n| 姓名 | 张*超 |\n| 证书编号 | SYNTHETIC-20****18 |");
        List<Fact> facts=fixture.checked(c,fixture.role(c,1,"项目负责人","张国超","",""));
        Rule r=new Rule("synthetic-title","项目负责人职称评分","SCORE_COUNT","项目负责人","职称","","","",Collections.emptyMap(),"项目负责人实力","",Collections.emptyList());
        Decision d=new Decision(r.id(),r.description(),"UNDETERMINED","身份未定",Collections.emptyList(),
            Arrays.asList(gap("SCORING_TITLE_IDENTITY_REQUIRED","职称查询姓名遮蔽"),gap("AGENT_INVESTIGATION_STALLED","未发现新证据")),Collections.emptyMap());
        Map<String,Object> context=linked("enableProgrammaticInferences",true,"inferenceCheckpointId","synthetic-title-checkpoint");
        Decision pending=InferenceClaims.discover(r,d,facts,c,context);
        List<Map<String,Object>> cards=RuleEvaluator.maps(context.get("inferredFacts"));
        assertEquals(1,cards.size());assertEquals("MASKED_PERSON_IDENTITY",cards.get(0).get("inferenceType"));
        assertEquals("UNDETERMINED",pending.status());assertEquals(1,pending.proof().get("pendingConfirmationCount"));
        assertTrue(InferenceClaims.confirmed(context,c).isEmpty(),"A masked name is never automatically confirmed");
        assertTrue(cards.get(0).get("evidences").toString().contains("张*超"));
    }
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
        assertEquals("UNDETERMINED",result.status());assertEquals(1,saved.size());assertEquals(ProgrammaticInferenceCandidates.VERSION,saved.get(0).get("origin"));
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
        assertEquals("UNDETERMINED",InferenceClaims.discover(r,d,cn.bidcheck.compat.Java8Api.listOf(),c,context).status());assertEquals(1,saved.size());assertEquals("1",((Map<?,?>)((Map<?,?>)saved.get(0).get("proposedFact")).get("attributes")).get("inventoryRow"));
        assertTrue(InferenceClaims.confirmed(context,c).isEmpty());
    }
    @Test void addingContractQuestionDoesNotHideOtherMissingRequirements(){
        java.util.Map<java.lang.String, java.lang.Object> candidate=candidate();Decision d=new Decision(rule.id(),rule.description(),"UNDETERMINED","另缺材料",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("PERSON_ROLE_REQUIRED","关系"), gap("MATERIAL_REQUIRED","其他材料")),linked("confirmationCandidates",cn.bidcheck.compat.Java8Api.listOf(candidate)));
        Map<String,Object> context=new HashMap<>();context.put("enableProgrammaticInferences",true);
        Decision result=InferenceClaims.discover(rule,d,cn.bidcheck.compat.Java8Api.listOf(),corpus,context);assertEquals("UNDETERMINED",result.status());assertEquals(2,result.gaps().size());assertFalse(RuleEvaluator.maps(result.proof().get("inferredFacts")).isEmpty());
    }
}

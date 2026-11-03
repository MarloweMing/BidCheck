package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentInvestigationScopeTest {
    @Test void currentServerReceiptClosesUnusedAnyButKeepsCommonConditionsAndDiagnosticInventory(){
        Rule accepted=role("accepted","项目负责人"),outside=role("outside","技术负责人");
        Rule policy=new Rule("policy","新政策换证","UNSUPPORTED","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"按新政策换证","",cn.bidcheck.compat.Java8Api.listOf());
        Rule any=new Rule("any","替代资质","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"满足任一路径","ANY",cn.bidcheck.compat.Java8Api.listOf(accepted, policy));
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(any, outside),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, policy, outside),"资料");
        h.evaluator.decisions.put("any",new Decision("any","替代资质","PASS","已证明",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        java.util.Map<java.lang.String, java.lang.Object> receipt=AgentInvestigationScope.targetReceipt(h.rules,plan,h.evaluator);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("accepted", "policy"),receipt.get("completedTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("outside"),receipt.get("remainingTargetObligationIds"));
        java.util.Map<java.lang.String, java.lang.Object> audit=CompilationRecovery.activeAudit(plan,h.evaluator,cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("VALIDATED",audit.get("state"));assertEquals("UNRESOLVED",audit.get("compileInventoryState"));
        assertEquals(1,RuleEvaluator.maps(audit.get("allCompileDiagnostics")).size());
        h.evaluator.decisions.remove("any");
        assertEquals("UNRESOLVED",CompilationRecovery.activeAudit(plan,h.evaluator,cn.bidcheck.compat.Java8Api.mapOf()).get("state"));
        Plan invalid=new Plan("ALL",plan.rules(),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf("全局结构不完整"));
        h.evaluator.decisions.put("any",new Decision("any","替代资质","PASS","已证明",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("UNRESOLVED",CompilationRecovery.activeAudit(invalid,h.evaluator,cn.bidcheck.compat.Java8Api.mapOf()).get("state"));
    }
    @Test void navigationWithoutAcceptedFactsDoesNotChangeTargetEvidenceRevision(){
        Rule r=role("role","项目负责人");cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(r),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(r),"项目负责人任职条件说明，本页不指定具体人员");
        Source source=h.corpus.all.get(0);
        h.ctx.put("_validatedLayoutCells",cn.bidcheck.compat.Java8Api.listOf(linked("documentId",source.evidence().documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"locationType",source.evidence().locationType(),"location",source.evidence().page(),"text","项目负责人任职条件说明")));
        Map<?,?> context=(Map<?,?>)h.start().get("context");Object before=((Map<?,?>)context.get("targetEvidenceRevisions")).get("role");
        h.disclosed.put(h.corpus.all.get(0).id(),h.corpus.all.get(0));
        Map<?,?> after=(Map<?,?>)h.recompute().get("targetEvidenceRevisions");
        assertEquals(before,after.get("role"));
    }
    @Test void finalAuditReplacesStaleWorkerTargetIdsWithCurrentProofButRetainsWorkerHistory(){
        Rule accepted=role("accepted","项目负责人"),unused=material(),outside=role("outside","技术负责人");
        Rule any=new Rule("any","任选路径","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"岗位或社保","ANY",cn.bidcheck.compat.Java8Api.listOf(accepted, unused));
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(any, outside),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(accepted, unused),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        cn.bidcheck.verification.agent.AgentInvestigator sdk=new AgentInvestigator(){
            public boolean enabled(){return true;}public String identity(){return "stale-audit-test";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> events){
                handler.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));
                return linked("status","FAILED","errorCode","AGENT_STALLED_WITH_REMAINING_TARGETS","remainingTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("social"),"completedTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("accepted"),"exhaustedTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf("social"),"targetProgressLedger",cn.bidcheck.compat.Java8Api.listOf(linked("targetObligationId","social","state","NO_MORE_EVIDENCE")));
            }
        };
        java.util.Map<java.lang.String, java.lang.Object> audit=EvidenceInvestigation.run(sdk,h);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),audit.get("remainingTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("accepted", "social"),audit.get("completedTargetObligationIds"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("social"),audit.get("workerRemainingTargetObligationIds"));
        assertEquals(false,audit.get("businessCheckCompleted"));
        assertEquals("COMPLETED",audit.get("status"));assertEquals("FAILED",audit.get("workerStatus"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),audit.get("exhaustedTargetObligationIds"));assertEquals("PROVEN",RuleEvaluator.maps(audit.get("targetProgressLedger")).get(0).get("state"));
    }
    private final ObjectMapper json=new ObjectMapper();
    private final EvidenceInvestigationTest fixture=new EvidenceInvestigationTest();
    private Rule role(String id,String subject){return new Rule(id,subject+"任职","PERSON_ROLE",subject,"role","","","",cn.bidcheck.compat.Java8Api.mapOf(),"本项目应配置"+subject,"",cn.bidcheck.compat.Java8Api.listOf());}
    private Rule material(){return new Rule("social","提交社保证明","MATERIAL","","material","EXISTS","社保证明","",cn.bidcheck.compat.Java8Api.mapOf(),"提交社保证明","",cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> context(String...ids){return linked("recoveryMode","TARGETED","recoveryStrategy","TARGETED_EVIDENCE","recoveryTargetObligationIds",cn.bidcheck.compat.Java8Api.listOf(ids));}
    private EvidenceInvestigation.Host host(Plan plan,List<Rule> targets,String text){
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,text)));Map<String,Object> ctx=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(fixture.checkpoint(),plan,targets,corpus,new LinkedHashMap<>(),ctx,
            new SharedFactReuse(corpus,ctx,"scope-test","scope-test"),new RunSession(ModelGateway.disabled(),ctx,"scope"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
    }
    @Test void observedRoleTwoAndFiveAlreadyPassingCannotReopenTheOtherWholeTreeGaps(){
        Rule two=role("D49da4c734dd7c16f1fe7-ROLE-2","项目负责人"),five=role("D49da4c734dd7c16f1fe7-ROLE-5","安全员");
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(two, five, material()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(two, five),"本项目人员配置\n项目负责人：测试甲\n安全员：测试乙");
        for(Rule rule:cn.bidcheck.compat.Java8Api.listOf(two, five))h.evaluator.decisions.put(rule.id(),new Decision(rule.id(),rule.description(),"PASS","已证明",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        h.evaluator.decisions.put("social",new Decision("social","社保证明","UNDETERMINED","未闭合",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(gap("CONTENT_EVIDENCE_REQUIRED","未证明")),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentInvestigationScope.pending(plan,plan.rules(),context(two.id(),five.id()),h.evaluator));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentInvestigationScope.pending(plan,plan.rules(),context(five.id()),h.evaluator));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(material()),AgentInvestigationScope.pending(plan,plan.rules(),context("social"),h.evaluator));
    }
    @Test void explicitUnknownOrEmptyTargetsNeverWidenToWholeTree(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(r, material()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(r),"资料");
        assertTrue(AgentInvestigationScope.pending(plan,plan.rules(),context(),h.evaluator).isEmpty());
        assertTrue(AgentInvestigationScope.pending(plan,plan.rules(),context("unknown"),h.evaluator).isEmpty());
    }
    @Test void previouslyPassingTargetWithNewCounterEvidenceIsNotFilteredByItsOldVerdict(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(r),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(r),"本项目人员配置\n拟在本合同任职：项目负责人\n项目负责人：测试甲\n项目负责人：测试乙\n姓名：测试甲\n姓名：测试乙");String source=h.corpus.all.get(0).id();
        for(String name:cn.bidcheck.compat.Java8Api.listOf("测试甲", "测试乙"))h.facts.put(name,new Fact(name,"PERSON_ROLE","项目负责人","姓名",name,name,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(source),cn.bidcheck.compat.Java8Api.mapOf()));
        h.recompute();assertFalse(AgentInvestigationScope.closed(h.evaluator.decisions.get("role")),h.decision.toString());
        assertTrue(h.decision.gaps().stream().anyMatch(g->g.code().equals("CONFLICTING_EVIDENCE")),h.decision.toString());
        Map<String,Object> ctx=context("role");ctx.put("previousAtomicCheck",cn.bidcheck.compat.Java8Api.mapOf("businessDecision", "PASS", "complete", true, "conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", "role", "status", "PASS"))));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(r),AgentInvestigationScope.pending(plan,plan.rules(),ctx,h.evaluator));
        assertFalse(h.decision.gaps().isEmpty());
    }
    @Test void conditionalSelectsOnlyApplicableBranchAndUnprovenConditionFirst(){
        Rule gate=role("gate","项目负责人"),yes=material(),no=new Rule("alternative","替代材料","MATERIAL","","material","EXISTS","替代材料","",cn.bidcheck.compat.Java8Api.mapOf(),"提交替代材料","",cn.bidcheck.compat.Java8Api.listOf());
        Rule group=new Rule("conditional","条件要求","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"有项目负责人则提交社保证明，否则提交替代材料","CONDITIONAL",cn.bidcheck.compat.Java8Api.listOf(gate, yes, no));
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(group),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(gate, yes, no),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(gate),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(gate, yes, no),context("conditional"),h.evaluator));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(gate),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(gate, yes, no),context("social"),h.evaluator));
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));h.recompute();assertEquals("PASS",h.evaluator.decisions.get("gate").status());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(yes),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(gate, yes, no),context("conditional"),h.evaluator));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(gate, yes, no),context("alternative"),h.evaluator));
    }
    @Test void aSatisfiedAnyGroupDoesNotReopenItsUnfinishedAlternativeWhenAnotherItemHasGaps(){
        Rule accepted=role("role","项目负责人"),unused=material(),outside=role("outside","技术负责人");
        Rule any=new Rule("any","替代要求","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"岗位任职或社保证明","ANY",cn.bidcheck.compat.Java8Api.listOf(accepted, unused));
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(any, outside),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, unused, outside),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));h.recompute();assertEquals("PASS",h.evaluator.decisions.get("any").status());assertFalse(h.decision.gaps().isEmpty());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, unused, outside),context("any"),h.evaluator));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, unused, outside),context("social"),h.evaluator));
    }
    @Test void aNewlySatisfiedAlternativeEndsTheOriginalTargetSessionWithoutCompletingOtherItems(){
        Rule accepted=role("role","项目负责人"),unused=material(),outside=role("outside","技术负责人");
        Rule any=new Rule("any","替代要求","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"岗位任职或社保证明","ANY",cn.bidcheck.compat.Java8Api.listOf(accepted, unused));
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(any, outside),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, unused),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        assertEquals(false,h.recompute().get("targetCheckCompleted"));
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));java.util.Map<java.lang.String, java.lang.Object> receipt=h.recompute();
        assertEquals(true,receipt.get("targetCheckCompleted"));assertEquals(false,receipt.get("businessCheckCompleted"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt.get("gaps"));assertEquals("UNDETERMINED",h.decision.status());
    }
    @Test void conditionalDoesNotReAddTheUnneededAlternativeOfAnAlreadyProvenAnyGate(){
        Rule accepted=role("role","项目负责人"),unused=material(),yes=role("yes","技术负责人"),no=role("no","安全员");
        Rule gate=new Rule("any-gate","适用前提","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"岗位任职或社保证明","ANY",cn.bidcheck.compat.Java8Api.listOf(accepted, unused));
        Rule conditional=new Rule("conditional","条件要求","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"适用时配置技术负责人，否则配置安全员","CONDITIONAL",cn.bidcheck.compat.Java8Api.listOf(gate, yes, no));
        Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(conditional),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, unused, yes, no),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));h.recompute();assertEquals("PASS",h.evaluator.decisions.get("any-gate").status());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(yes),AgentInvestigationScope.pending(plan,cn.bidcheck.compat.Java8Api.listOf(accepted, unused, yes, no),context("conditional"),h.evaluator));
    }
    @Test void finishingTargetKeepsOtherBusinessObligationsUnfinished(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(r, material()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(r),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));java.util.Map<java.lang.String, java.lang.Object> receipt=h.recompute();
        assertEquals(true,receipt.get("targetCheckCompleted"));assertEquals(false,receipt.get("businessCheckCompleted"));
        assertEquals("UNDETERMINED",receipt.get("businessDecision"));assertFalse(h.decision.gaps().isEmpty());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),receipt.get("gaps"));assertEquals(1,RuleEvaluator.maps(receipt.get("obligations")).size());
    }
    @Test void handoffCarriesOnlyTargetFactsAndClosedKnownFactDoesNotAuthorizeFurtherNavigationOrSubmission(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(r, material()),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf());cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(plan,cn.bidcheck.compat.Java8Api.listOf(r),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Fact known=h.corpus.nativeFacts(cn.bidcheck.compat.Java8Api.listOf(r),h.corpus.all).get(0);h.facts.put(known.id(),known);
        h.facts.put("other",new Fact("other","FIELD","","无关字段","测试甲","测试甲","","","UNRESOLVED",known.sourceIds(),cn.bidcheck.compat.Java8Api.mapOf()));
        Map<?,?> start=(Map<?,?>)h.start().get("context");assertEquals(1,((List<?>)start.get("knownFacts")).size());
        Map<?,?> requirement=(Map<?,?>)start.get("requirement");assertEquals(2,((List<?>)requirement.get("rules")).size(),"requirement keeps the full server plan");
        assertEquals(1,((List<?>)start.get("targetObligations")).size(),"current evidence targets remain separately scoped");
        assertEquals("TARGET_FACTS_V1",start.get("workflowPolicy"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(r.id()),start.get("targetObligationIds"));
        assertTrue(h.disclosed.isEmpty());assertTrue(h.navigation.containsKey(known.sourceIds().get(0)));
        java.util.Map<java.lang.String, java.lang.Object> candidate=SharedFactReuse.value(known);
        assertEquals("INVALID_TARGET_RECEIPT",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", candidate)).get("error"));
        assertEquals("INVALID_TARGET_RECEIPT",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", "outside", "fact", candidate)).get("error"));
        java.util.Map<java.lang.String, java.lang.Object> arguments=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("targetObligationId", r.id(), "fact", candidate);
        java.util.Map<java.lang.String, java.lang.Object> unread=h.call("submit_candidate_fact",arguments);assertEquals("REJECTED",unread.get("status"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(),unread.get("acceptedFactIds"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(r.id()),unread.get("affectedTargetObligationIds"));
        assertTrue(h.disclosed.isEmpty(),"An explicit target still does not authorize a navigation-only source as evidence");
        assertEquals("TARGET_STATE_UNCHANGED",h.call("read_section",cn.bidcheck.compat.Java8Api.mapOf("sourceId", known.sourceIds().get(0))).get("error"));
        assertEquals("TARGET_STATE_UNCHANGED",h.call("submit_candidate_fact",arguments).get("error"));
        int factCount=h.facts.size();
        java.util.Map<java.lang.String, java.lang.Object> unrelated=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("targetObligationId", r.id(), "fact", SharedFactReuse.value(h.facts.get("other"))));
        assertEquals("REJECTED",unrelated.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(),unrelated.get("acceptedFactIds"));assertEquals(factCount,h.facts.size(),"Naming the target cannot make an unrelated fact admissible");
    }
    @Test void wrongRelationAndGenericFieldAreRecoverablyRejectedThenExactRegistrationRelationWorks(){
        Rule registered=new Rule("registered","注册单位对应","RELATION","测试甲","注册单位","","甲公司","",cn.bidcheck.compat.Java8Api.mapOf(),"测试甲注册单位为甲公司","",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(registered),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(registered),"本项目人员资料\n姓名：测试甲；注册单位：甲公司；委派：乙公司。其他材料内容用于确认本文属于当前项目。 ");
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "测试甲"));String source=h.disclosed.keySet().iterator().next();
        Map<String,Object> fact=linked("kind","RELATION","subject","测试甲","field","委派","rawValue","乙公司","value","乙公司","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "乙公司"));
        java.util.Map<java.lang.String, java.lang.Object> wrong=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact));assertEquals("FACT_OUTSIDE_TARGET",wrong.get("error"));assertEquals(true,wrong.get("recoverable"));assertTrue(h.facts.isEmpty());
        fact.putAll(linked("kind","FIELD","field","注册单位","rawValue","甲公司","value","甲公司","attributes",cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact)).get("error"));
        fact.putAll(linked("kind","RELATION","attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "甲公司")));
        assertEquals("COMPLETED",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", fact)).get("status"));
        assertEquals(true,h.recompute().get("targetCheckCompleted"));
    }
    @Test void relationPathAdmitsOnlyTheNextEdgeFromAnAlreadyVerifiedPrefix(){
        Rule path=new Rule("path","可追溯单位职责","RELATION","测试甲","","","设计","",cn.bidcheck.compat.Java8Api.mapOf("path", cn.bidcheck.compat.Java8Api.listOf("所属单位", "负责")),"测试甲所属单位负责设计","",cn.bidcheck.compat.Java8Api.listOf());
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(path),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(path),"本项目人员资料\n测试甲所属单位甲公司。甲公司负责设计。本项目原文还列示乙公司负责施工，但不能作为测试甲单位的路径。 ");
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "测试甲"));String source=h.disclosed.keySet().iterator().next();
        Map<String,Object> first=linked("kind","RELATION","subject","测试甲","field","所属单位","rawValue","甲公司","value","甲公司","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "甲公司"));
        Map<String,Object> next=new LinkedHashMap<>(first);next.putAll(linked("subject","甲公司","field","负责","rawValue","设计","value","设计","attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "设计")));
        assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", next)).get("error"));
        assertEquals("COMPLETED",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", first)).get("status"));
        assertEquals("COMPLETED",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", next)).get("status"));
        assertEquals(true,h.recompute().get("targetCheckCompleted"));
        Map<String,Object> unrelated=new LinkedHashMap<>(next);unrelated.putAll(linked("subject","乙公司","rawValue","施工","value","施工","attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "施工")));
        assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", unrelated)).get("error"));
    }
    @Test void registrationExecutorHasSeparateEmploymentAndCertificateContracts(){
        Rule registration=new Rule("registration","在本单位完成注册","RELATION","测试甲","注册单位","","","",cn.bidcheck.compat.Java8Api.mapOf(),"测试甲应在本单位完成注册","",cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(RegistrationEmployerProof.applies(registration));
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host h=host(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(registration),"FIXTURE",cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf(registration),
            "本项目人员资料\n测试甲所属单位甲公司；测试甲委派甲公司；测试甲注册单位甲公司。\n注册建造师证书\n证书编号：AB123\n姓名：测试甲\n注册单位：甲公司\n本页保存独立关系和证书字段，字段不得彼此替代。");
        h.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "测试甲"));String source=h.disclosed.keySet().iterator().next();
        Map<String,Object> relation=linked("kind","RELATION","subject","测试甲","field","所属单位","rawValue","甲公司","value","甲公司","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),"attributes",cn.bidcheck.compat.Java8Api.mapOf("object", "甲公司"));
        java.util.Map<java.lang.String, java.lang.Object> employment=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", relation));assertEquals("COMPLETED",employment.get("status"),employment.toString());
        Map<String,Object> certificate=linked("kind","CERTIFICATE","subject","测试甲","field","证书","rawValue","AB123","value","AB123","unit","","basis","","scope","UNRESOLVED","sourceIds",cn.bidcheck.compat.Java8Api.listOf(source),
            "attributes",cn.bidcheck.compat.Java8Api.mapOf("category", "注册建造师证书", "number", "AB123", "numberField", "证书编号", "employer", "甲公司"));
        java.util.Map<java.lang.String, java.lang.Object> credential=h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", certificate));assertEquals("COMPLETED",credential.get("status"),credential.toString());
        assertTrue(h.facts.values().stream().anyMatch(f->f.kind().equals("RELATION")&&f.field().equals("所属单位")));
        assertTrue(h.facts.values().stream().anyMatch(f->f.kind().equals("CERTIFICATE")));
        relation.put("field","注册单位");assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", relation)).get("error"));
        relation.put("field","委派");assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",cn.bidcheck.compat.Java8Api.mapOf("fact", relation)).get("error"));
        Map<String,Object> contract=AgentInvestigationScope.contracts(cn.bidcheck.compat.Java8Api.listOf(registration)).get(0);
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("CURRENT_APPOINTMENT", "INDEPENDENT_EMPLOYMENT_RELATION", "CERTIFICATE_REGISTERED_EMPLOYER"),contract.get("requiredProofRoles"));
    }
    @Test void engineDoesNotCallInvestigatorForClosedTargetsEvenWhenWholeItemStillHasGap(){
        Rule r=role("role","项目负责人");List<Rule> rules=cn.bidcheck.compat.Java8Api.listOf(r, material());AtomicInteger agentCalls=new AtomicInteger(),extractCalls=new AtomicInteger();
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "scope-regression";}public JsonNode request(String op,Map<String,Object> input){
            if("EXTRACT".equals(op))extractCalls.incrementAndGet();assertEquals("COMPILE",op);return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", rules));}};
        VerificationEngine engine=new VerificationEngine(model,null);engine.setInvestigator(new AgentInvestigator(){public boolean enabled(){return true;}public String identity(){return "scope-regression";}public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> events){agentCalls.incrementAndGet();fail("Closed target must not start Agent");return cn.bidcheck.compat.Java8Api.mapOf();}});
        cn.bidcheck.domain.Domain.Document bid=SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(bid));
        Map<String,Object> ctx=context("role");ctx.put("allowModelRuleCompilation",true);ctx.put("priorSteps",cn.bidcheck.compat.Java8Api.listOf(linked("type","EVIDENCE_PLAN_0","status","COMPLETED","schemaVersion",VERSION,"modelIdentity",model.identity(),"output",cn.bidcheck.compat.Java8Api.mapOf("candidates", corpus.inputs(corpus.all)))));
        Checkpoint cp=new Checkpoint("cp","项目负责人及社保","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置项目负责人；提交社保证明","",true,true,true,null);
        java.util.Map<java.lang.String, java.lang.Object> result=engine.evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(bid),null,ctx);assertEquals(0,agentCalls.get());assertEquals(0,extractCalls.get());assertEquals("UNDETERMINED",result.get("businessDecision"));
        assertEquals("PASS",RuleEvaluator.maps(result.get("obligations")).stream().filter(d->"role".equals(d.get("id"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).get("status"));
        assertEquals(cn.bidcheck.compat.Java8Api.mapOf(),result.get("agentInvestigation"));assertFalse((boolean)result.get("complete"));
    }
}

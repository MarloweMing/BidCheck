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
        Rule policy=new Rule("policy","新政策换证","UNSUPPORTED","","","","","",Map.of(),"按新政策换证","",List.of());
        Rule any=new Rule("any","替代资质","GROUP","","","","","",Map.of(),"满足任一路径","ANY",List.of(accepted,policy));
        Plan plan=new Plan("ALL",List.of(any,outside),"FIXTURE",List.of());
        var h=host(plan,List.of(accepted,policy,outside),"资料");
        h.evaluator.decisions.put("any",new Decision("any","替代资质","PASS","已证明",List.of(),List.of(),Map.of()));
        var receipt=AgentInvestigationScope.targetReceipt(h.rules,plan,h.evaluator);
        assertEquals(List.of("accepted","policy"),receipt.get("completedTargetObligationIds"));
        assertEquals(List.of("outside"),receipt.get("remainingTargetObligationIds"));
        var audit=CompilationRecovery.activeAudit(plan,h.evaluator,Map.of());
        assertEquals("VALIDATED",audit.get("state"));assertEquals("UNRESOLVED",audit.get("compileInventoryState"));
        assertEquals(1,RuleEvaluator.maps(audit.get("allCompileDiagnostics")).size());
        h.evaluator.decisions.remove("any");
        assertEquals("UNRESOLVED",CompilationRecovery.activeAudit(plan,h.evaluator,Map.of()).get("state"));
        Plan invalid=new Plan("ALL",plan.rules(),"FIXTURE",List.of("全局结构不完整"));
        h.evaluator.decisions.put("any",new Decision("any","替代资质","PASS","已证明",List.of(),List.of(),Map.of()));
        assertEquals("UNRESOLVED",CompilationRecovery.activeAudit(invalid,h.evaluator,Map.of()).get("state"));
    }
    @Test void navigationWithoutAcceptedFactsDoesNotChangeTargetEvidenceRevision(){
        Rule r=role("role","项目负责人");var h=host(new Plan("ALL",List.of(r),"FIXTURE",List.of()),List.of(r),"项目负责人任职条件说明，本页不指定具体人员");
        Source source=h.corpus.all.get(0);
        h.ctx.put("_validatedLayoutCells",List.of(linked("documentId",source.evidence().documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"locationType",source.evidence().locationType(),"location",source.evidence().page(),"text","项目负责人任职条件说明")));
        Map<?,?> context=(Map<?,?>)h.start().get("context");Object before=((Map<?,?>)context.get("targetEvidenceRevisions")).get("role");
        h.disclosed.put(h.corpus.all.get(0).id(),h.corpus.all.get(0));
        Map<?,?> after=(Map<?,?>)h.recompute().get("targetEvidenceRevisions");
        assertEquals(before,after.get("role"));
    }
    @Test void finalAuditReplacesStaleWorkerTargetIdsWithCurrentProofButRetainsWorkerHistory(){
        Rule accepted=role("accepted","项目负责人"),unused=material(),outside=role("outside","技术负责人");
        Rule any=new Rule("any","任选路径","GROUP","","","","","",Map.of(),"岗位或社保","ANY",List.of(accepted,unused));
        var h=host(new Plan("ALL",List.of(any,outside),"FIXTURE",List.of()),List.of(accepted,unused),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        var sdk=new AgentInvestigator(){
            public boolean enabled(){return true;}public String identity(){return "stale-audit-test";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> events){
                handler.call("search_evidence",Map.of("query","项目负责人"));
                return linked("status","FAILED","errorCode","AGENT_STALLED_WITH_REMAINING_TARGETS","remainingTargetObligationIds",List.of("social"),"completedTargetObligationIds",List.of("accepted"),"exhaustedTargetObligationIds",List.of("social"),"targetProgressLedger",List.of(linked("targetObligationId","social","state","NO_MORE_EVIDENCE")));
            }
        };
        var audit=EvidenceInvestigation.run(sdk,h);
        assertEquals(List.of(),audit.get("remainingTargetObligationIds"));
        assertEquals(List.of("accepted","social"),audit.get("completedTargetObligationIds"));
        assertEquals(List.of("social"),audit.get("workerRemainingTargetObligationIds"));
        assertEquals(false,audit.get("businessCheckCompleted"));
        assertEquals("COMPLETED",audit.get("status"));assertEquals("FAILED",audit.get("workerStatus"));
        assertEquals(List.of(),audit.get("exhaustedTargetObligationIds"));assertEquals("PROVEN",RuleEvaluator.maps(audit.get("targetProgressLedger")).get(0).get("state"));
    }
    private final ObjectMapper json=new ObjectMapper();
    private final EvidenceInvestigationTest fixture=new EvidenceInvestigationTest();
    private Rule role(String id,String subject){return new Rule(id,subject+"任职","PERSON_ROLE",subject,"role","","","",Map.of(),"本项目应配置"+subject,"",List.of());}
    private Rule material(){return new Rule("social","提交社保证明","MATERIAL","","material","EXISTS","社保证明","",Map.of(),"提交社保证明","",List.of());}
    private Map<String,Object> context(String...ids){return linked("recoveryMode","TARGETED","recoveryStrategy","TARGETED_EVIDENCE","recoveryTargetObligationIds",List.of(ids));}
    private EvidenceInvestigation.Host host(Plan plan,List<Rule> targets,String text){
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,text)));Map<String,Object> ctx=new LinkedHashMap<>();
        return new EvidenceInvestigation.Host(fixture.checkpoint(),plan,targets,corpus,new LinkedHashMap<>(),ctx,
            new SharedFactReuse(corpus,ctx,"scope-test","scope-test"),new RunSession(ModelGateway.disabled(),ctx,"scope"),null,new ArrayList<>(),Set.of());
    }
    @Test void observedRoleTwoAndFiveAlreadyPassingCannotReopenTheOtherWholeTreeGaps(){
        Rule two=role("D49da4c734dd7c16f1fe7-ROLE-2","项目负责人"),five=role("D49da4c734dd7c16f1fe7-ROLE-5","安全员");
        Plan plan=new Plan("ALL",List.of(two,five,material()),"FIXTURE",List.of());var h=host(plan,List.of(two,five),"本项目人员配置\n项目负责人：测试甲\n安全员：测试乙");
        for(Rule rule:List.of(two,five))h.evaluator.decisions.put(rule.id(),new Decision(rule.id(),rule.description(),"PASS","已证明",List.of(),List.of(),Map.of()));
        h.evaluator.decisions.put("social",new Decision("social","社保证明","UNDETERMINED","未闭合",List.of(),List.of(gap("CONTENT_EVIDENCE_REQUIRED","未证明")),Map.of()));
        assertEquals(List.of(),AgentInvestigationScope.pending(plan,plan.rules(),context(two.id(),five.id()),h.evaluator));
        assertEquals(List.of(),AgentInvestigationScope.pending(plan,plan.rules(),context(five.id()),h.evaluator));
        assertEquals(List.of(material()),AgentInvestigationScope.pending(plan,plan.rules(),context("social"),h.evaluator));
    }
    @Test void explicitUnknownOrEmptyTargetsNeverWidenToWholeTree(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",List.of(r,material()),"FIXTURE",List.of());var h=host(plan,List.of(r),"资料");
        assertTrue(AgentInvestigationScope.pending(plan,plan.rules(),context(),h.evaluator).isEmpty());
        assertTrue(AgentInvestigationScope.pending(plan,plan.rules(),context("unknown"),h.evaluator).isEmpty());
    }
    @Test void previouslyPassingTargetWithNewCounterEvidenceIsNotFilteredByItsOldVerdict(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",List.of(r),"FIXTURE",List.of());
        var h=host(plan,List.of(r),"本项目人员配置\n拟在本合同任职：项目负责人\n项目负责人：测试甲\n项目负责人：测试乙\n姓名：测试甲\n姓名：测试乙");String source=h.corpus.all.get(0).id();
        for(String name:List.of("测试甲","测试乙"))h.facts.put(name,new Fact(name,"PERSON_ROLE","项目负责人","姓名",name,name,"","","CURRENT_PROJECT",List.of(source),Map.of()));
        h.recompute();assertFalse(AgentInvestigationScope.closed(h.evaluator.decisions.get("role")),h.decision.toString());
        assertTrue(h.decision.gaps().stream().anyMatch(g->g.code().equals("CONFLICTING_EVIDENCE")),h.decision.toString());
        Map<String,Object> ctx=context("role");ctx.put("previousAtomicCheck",Map.of("businessDecision","PASS","complete",true,"conditions",List.of(Map.of("id","role","status","PASS"))));
        assertEquals(List.of(r),AgentInvestigationScope.pending(plan,plan.rules(),ctx,h.evaluator));
        assertFalse(h.decision.gaps().isEmpty());
    }
    @Test void conditionalSelectsOnlyApplicableBranchAndUnprovenConditionFirst(){
        Rule gate=role("gate","项目负责人"),yes=material(),no=new Rule("alternative","替代材料","MATERIAL","","material","EXISTS","替代材料","",Map.of(),"提交替代材料","",List.of());
        Rule group=new Rule("conditional","条件要求","GROUP","","","","","",Map.of(),"有项目负责人则提交社保证明，否则提交替代材料","CONDITIONAL",List.of(gate,yes,no));
        Plan plan=new Plan("ALL",List.of(group),"FIXTURE",List.of());var h=host(plan,List.of(gate,yes,no),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        assertEquals(List.of(gate),AgentInvestigationScope.pending(plan,List.of(gate,yes,no),context("conditional"),h.evaluator));
        assertEquals(List.of(gate),AgentInvestigationScope.pending(plan,List.of(gate,yes,no),context("social"),h.evaluator));
        h.call("search_evidence",Map.of("query","项目负责人"));h.recompute();assertEquals("PASS",h.evaluator.decisions.get("gate").status());
        assertEquals(List.of(yes),AgentInvestigationScope.pending(plan,List.of(gate,yes,no),context("conditional"),h.evaluator));
        assertEquals(List.of(),AgentInvestigationScope.pending(plan,List.of(gate,yes,no),context("alternative"),h.evaluator));
    }
    @Test void aSatisfiedAnyGroupDoesNotReopenItsUnfinishedAlternativeWhenAnotherItemHasGaps(){
        Rule accepted=role("role","项目负责人"),unused=material(),outside=role("outside","技术负责人");
        Rule any=new Rule("any","替代要求","GROUP","","","","","",Map.of(),"岗位任职或社保证明","ANY",List.of(accepted,unused));
        Plan plan=new Plan("ALL",List.of(any,outside),"FIXTURE",List.of());var h=host(plan,List.of(accepted,unused,outside),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        h.call("search_evidence",Map.of("query","项目负责人"));h.recompute();assertEquals("PASS",h.evaluator.decisions.get("any").status());assertFalse(h.decision.gaps().isEmpty());
        assertEquals(List.of(),AgentInvestigationScope.pending(plan,List.of(accepted,unused,outside),context("any"),h.evaluator));
        assertEquals(List.of(),AgentInvestigationScope.pending(plan,List.of(accepted,unused,outside),context("social"),h.evaluator));
    }
    @Test void aNewlySatisfiedAlternativeEndsTheOriginalTargetSessionWithoutCompletingOtherItems(){
        Rule accepted=role("role","项目负责人"),unused=material(),outside=role("outside","技术负责人");
        Rule any=new Rule("any","替代要求","GROUP","","","","","",Map.of(),"岗位任职或社保证明","ANY",List.of(accepted,unused));
        Plan plan=new Plan("ALL",List.of(any,outside),"FIXTURE",List.of());var h=host(plan,List.of(accepted,unused),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        assertEquals(false,h.recompute().get("targetCheckCompleted"));
        h.call("search_evidence",Map.of("query","项目负责人"));var receipt=h.recompute();
        assertEquals(true,receipt.get("targetCheckCompleted"));assertEquals(false,receipt.get("businessCheckCompleted"));
        assertEquals(List.of(),receipt.get("gaps"));assertEquals("UNDETERMINED",h.decision.status());
    }
    @Test void conditionalDoesNotReAddTheUnneededAlternativeOfAnAlreadyProvenAnyGate(){
        Rule accepted=role("role","项目负责人"),unused=material(),yes=role("yes","技术负责人"),no=role("no","安全员");
        Rule gate=new Rule("any-gate","适用前提","GROUP","","","","","",Map.of(),"岗位任职或社保证明","ANY",List.of(accepted,unused));
        Rule conditional=new Rule("conditional","条件要求","GROUP","","","","","",Map.of(),"适用时配置技术负责人，否则配置安全员","CONDITIONAL",List.of(gate,yes,no));
        Plan plan=new Plan("ALL",List.of(conditional),"FIXTURE",List.of());var h=host(plan,List.of(accepted,unused,yes,no),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        h.call("search_evidence",Map.of("query","项目负责人"));h.recompute();assertEquals("PASS",h.evaluator.decisions.get("any-gate").status());
        assertEquals(List.of(yes),AgentInvestigationScope.pending(plan,List.of(accepted,unused,yes,no),context("conditional"),h.evaluator));
    }
    @Test void finishingTargetKeepsOtherBusinessObligationsUnfinished(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",List.of(r,material()),"FIXTURE",List.of());var h=host(plan,List.of(r),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        h.call("search_evidence",Map.of("query","项目负责人"));var receipt=h.recompute();
        assertEquals(true,receipt.get("targetCheckCompleted"));assertEquals(false,receipt.get("businessCheckCompleted"));
        assertEquals("UNDETERMINED",receipt.get("businessDecision"));assertFalse(h.decision.gaps().isEmpty());
        assertEquals(List.of(),receipt.get("gaps"));assertEquals(1,RuleEvaluator.maps(receipt.get("obligations")).size());
    }
    @Test void handoffCarriesOnlyTargetFactsAndClosedKnownFactDoesNotAuthorizeFurtherNavigationOrSubmission(){
        Rule r=role("role","项目负责人");Plan plan=new Plan("ALL",List.of(r,material()),"FIXTURE",List.of());var h=host(plan,List.of(r),"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Fact known=h.corpus.nativeFacts(List.of(r),h.corpus.all).get(0);h.facts.put(known.id(),known);
        h.facts.put("other",new Fact("other","FIELD","","无关字段","测试甲","测试甲","","","UNRESOLVED",known.sourceIds(),Map.of()));
        Map<?,?> start=(Map<?,?>)h.start().get("context");assertEquals(1,((List<?>)start.get("knownFacts")).size());
        Map<?,?> requirement=(Map<?,?>)start.get("requirement");assertEquals(2,((List<?>)requirement.get("rules")).size(),"requirement keeps the full server plan");
        assertEquals(1,((List<?>)start.get("targetObligations")).size(),"current evidence targets remain separately scoped");
        assertEquals("TARGET_FACTS_V1",start.get("workflowPolicy"));assertEquals(List.of(r.id()),start.get("targetObligationIds"));
        assertTrue(h.disclosed.isEmpty());assertTrue(h.navigation.containsKey(known.sourceIds().get(0)));
        var candidate=SharedFactReuse.value(known);
        assertEquals("INVALID_TARGET_RECEIPT",h.call("submit_candidate_fact",Map.of("fact",candidate)).get("error"));
        assertEquals("INVALID_TARGET_RECEIPT",h.call("submit_candidate_fact",Map.of("targetObligationId","outside","fact",candidate)).get("error"));
        var arguments=Map.<String,Object>of("targetObligationId",r.id(),"fact",candidate);
        var unread=h.call("submit_candidate_fact",arguments);assertEquals("REJECTED",unread.get("status"));
        assertEquals(List.of(),unread.get("acceptedFactIds"));assertEquals(List.of(r.id()),unread.get("affectedTargetObligationIds"));
        assertTrue(h.disclosed.isEmpty(),"An explicit target still does not authorize a navigation-only source as evidence");
        assertEquals("TARGET_STATE_UNCHANGED",h.call("read_section",Map.of("sourceId",known.sourceIds().get(0))).get("error"));
        assertEquals("TARGET_STATE_UNCHANGED",h.call("submit_candidate_fact",arguments).get("error"));
        int factCount=h.facts.size();
        var unrelated=h.call("submit_candidate_fact",Map.of("targetObligationId",r.id(),"fact",SharedFactReuse.value(h.facts.get("other"))));
        assertEquals("REJECTED",unrelated.get("status"));assertEquals(List.of(),unrelated.get("acceptedFactIds"));assertEquals(factCount,h.facts.size(),"Naming the target cannot make an unrelated fact admissible");
    }
    @Test void wrongRelationAndGenericFieldAreRecoverablyRejectedThenExactRegistrationRelationWorks(){
        Rule registered=new Rule("registered","注册单位对应","RELATION","测试甲","注册单位","","甲公司","",Map.of(),"测试甲注册单位为甲公司","",List.of());
        var h=host(new Plan("ALL",List.of(registered),"FIXTURE",List.of()),List.of(registered),"本项目人员资料\n姓名：测试甲；注册单位：甲公司；委派：乙公司。其他材料内容用于确认本文属于当前项目。 ");
        h.call("search_evidence",Map.of("query","测试甲"));String source=h.disclosed.keySet().iterator().next();
        Map<String,Object> fact=linked("kind","RELATION","subject","测试甲","field","委派","rawValue","乙公司","value","乙公司","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(source),"attributes",Map.of("object","乙公司"));
        var wrong=h.call("submit_candidate_fact",Map.of("fact",fact));assertEquals("FACT_OUTSIDE_TARGET",wrong.get("error"));assertEquals(true,wrong.get("recoverable"));assertTrue(h.facts.isEmpty());
        fact.putAll(linked("kind","FIELD","field","注册单位","rawValue","甲公司","value","甲公司","attributes",Map.of()));
        assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",Map.of("fact",fact)).get("error"));
        fact.putAll(linked("kind","RELATION","attributes",Map.of("object","甲公司")));
        assertEquals("COMPLETED",h.call("submit_candidate_fact",Map.of("fact",fact)).get("status"));
        assertEquals(true,h.recompute().get("targetCheckCompleted"));
    }
    @Test void relationPathAdmitsOnlyTheNextEdgeFromAnAlreadyVerifiedPrefix(){
        Rule path=new Rule("path","可追溯单位职责","RELATION","测试甲","","","设计","",Map.of("path",List.of("所属单位","负责")),"测试甲所属单位负责设计","",List.of());
        var h=host(new Plan("ALL",List.of(path),"FIXTURE",List.of()),List.of(path),"本项目人员资料\n测试甲所属单位甲公司。甲公司负责设计。本项目原文还列示乙公司负责施工，但不能作为测试甲单位的路径。 ");
        h.call("search_evidence",Map.of("query","测试甲"));String source=h.disclosed.keySet().iterator().next();
        Map<String,Object> first=linked("kind","RELATION","subject","测试甲","field","所属单位","rawValue","甲公司","value","甲公司","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(source),"attributes",Map.of("object","甲公司"));
        Map<String,Object> next=new LinkedHashMap<>(first);next.putAll(linked("subject","甲公司","field","负责","rawValue","设计","value","设计","attributes",Map.of("object","设计")));
        assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",Map.of("fact",next)).get("error"));
        assertEquals("COMPLETED",h.call("submit_candidate_fact",Map.of("fact",first)).get("status"));
        assertEquals("COMPLETED",h.call("submit_candidate_fact",Map.of("fact",next)).get("status"));
        assertEquals(true,h.recompute().get("targetCheckCompleted"));
        Map<String,Object> unrelated=new LinkedHashMap<>(next);unrelated.putAll(linked("subject","乙公司","rawValue","施工","value","施工","attributes",Map.of("object","施工")));
        assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",Map.of("fact",unrelated)).get("error"));
    }
    @Test void registrationExecutorHasSeparateEmploymentAndCertificateContracts(){
        Rule registration=new Rule("registration","在本单位完成注册","RELATION","测试甲","注册单位","","","",Map.of(),"测试甲应在本单位完成注册","",List.of());
        assertTrue(RegistrationEmployerProof.applies(registration));
        var h=host(new Plan("ALL",List.of(registration),"FIXTURE",List.of()),List.of(registration),
            "本项目人员资料\n测试甲所属单位甲公司；测试甲委派甲公司；测试甲注册单位甲公司。\n注册建造师证书\n证书编号：AB123\n姓名：测试甲\n注册单位：甲公司\n本页保存独立关系和证书字段，字段不得彼此替代。");
        h.call("search_evidence",Map.of("query","测试甲"));String source=h.disclosed.keySet().iterator().next();
        Map<String,Object> relation=linked("kind","RELATION","subject","测试甲","field","所属单位","rawValue","甲公司","value","甲公司","unit","","basis","","scope","CURRENT_PROJECT","sourceIds",List.of(source),"attributes",Map.of("object","甲公司"));
        var employment=h.call("submit_candidate_fact",Map.of("fact",relation));assertEquals("COMPLETED",employment.get("status"),employment.toString());
        Map<String,Object> certificate=linked("kind","CERTIFICATE","subject","测试甲","field","证书","rawValue","AB123","value","AB123","unit","","basis","","scope","UNRESOLVED","sourceIds",List.of(source),
            "attributes",Map.of("category","注册建造师证书","number","AB123","numberField","证书编号","employer","甲公司"));
        var credential=h.call("submit_candidate_fact",Map.of("fact",certificate));assertEquals("COMPLETED",credential.get("status"),credential.toString());
        assertTrue(h.facts.values().stream().anyMatch(f->f.kind().equals("RELATION")&&f.field().equals("所属单位")));
        assertTrue(h.facts.values().stream().anyMatch(f->f.kind().equals("CERTIFICATE")));
        relation.put("field","注册单位");assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",Map.of("fact",relation)).get("error"));
        relation.put("field","委派");assertEquals("FACT_OUTSIDE_TARGET",h.call("submit_candidate_fact",Map.of("fact",relation)).get("error"));
        Map<String,Object> contract=AgentInvestigationScope.contracts(List.of(registration)).get(0);
        assertEquals(List.of("CURRENT_APPOINTMENT","INDEPENDENT_EMPLOYMENT_RELATION","CERTIFICATE_REGISTERED_EMPLOYER"),contract.get("requiredProofRoles"));
    }
    @Test void engineDoesNotCallInvestigatorForClosedTargetsEvenWhenWholeItemStillHasGap(){
        Rule r=role("role","项目负责人");List<Rule> rules=List.of(r,material());AtomicInteger agentCalls=new AtomicInteger(),extractCalls=new AtomicInteger();
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "scope-regression";}public JsonNode request(String op,Map<String,Object> input){
            if("EXTRACT".equals(op))extractCalls.incrementAndGet();assertEquals("COMPILE",op);return json.valueToTree(Map.of("logic","ALL","rules",rules));}};
        VerificationEngine engine=new VerificationEngine(model,null);engine.setInvestigator(new AgentInvestigator(){public boolean enabled(){return true;}public String identity(){return "scope-regression";}public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> events){agentCalls.incrementAndGet();fail("Closed target must not start Agent");return Map.of();}});
        var bid=SharedFactReuseTest.doc("bid","project",1,"本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");EvidenceCorpus corpus=new EvidenceCorpus(List.of(bid));
        Map<String,Object> ctx=context("role");ctx.put("allowModelRuleCompilation",true);ctx.put("priorSteps",List.of(linked("type","EVIDENCE_PLAN_0","status","COMPLETED","schemaVersion",VERSION,"modelIdentity",model.identity(),"output",Map.of("candidates",corpus.inputs(corpus.all)))));
        Checkpoint cp=new Checkpoint("cp","项目负责人及社保","资格","HIGH","SEMANTIC_RESPONSE","","","本项目应配置项目负责人；提交社保证明","",true,true,true,null);
        var result=engine.evaluate(cp,List.of(bid),null,ctx);assertEquals(0,agentCalls.get());assertEquals(0,extractCalls.get());assertEquals("UNDETERMINED",result.get("businessDecision"));
        assertEquals("PASS",RuleEvaluator.maps(result.get("obligations")).stream().filter(d->"role".equals(d.get("id"))).findFirst().orElseThrow().get("status"));
        assertEquals(Map.of(),result.get("agentInvestigation"));assertFalse((boolean)result.get("complete"));
    }
}

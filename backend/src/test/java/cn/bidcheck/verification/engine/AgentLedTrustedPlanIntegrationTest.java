package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentLedTrustedPlanIntegrationTest {
    private static final String DESIGN="设计单位资质要求：须满足下列条件之一：①住建主管部门颁发的工程设计综合资质甲级。②住建主管部门颁发的工程设计建筑行业乙级（含）以上资质或工程设计建筑工程专业乙级（含）以上资质或建筑装饰工程设计专项丙级（含）以上资质。";

    private static Checkpoint checkpoint(String source){
        return new Checkpoint("agent-server-plan","资格要求","资格","HIGH","SEMANTIC_RESPONSE","qualification","",source,"",true,true,true,
            new Evidence("tender","招标文件",1,"BLOCK",source,"p1",1));
    }
    private static Document document(String id,String role,String name,String hash,String text){
        return new Document(id,"project",role,name,1,hash,"",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"BLOCK","NATIVE","")),cn.bidcheck.compat.Java8Api.listOf());
    }
    private static List<Map<String,Object>> maps(Object value){
        if(!(value instanceof List<?>))return cn.bidcheck.compat.Java8Api.listOf();
        return cn.bidcheck.compat.Java8Api.toList(((List<?>) (value)).stream().filter(Map.class::isInstance).map(v->(Map<String,Object>)v));
    }
    private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)((Map<?,?>) (value)):cn.bidcheck.compat.Java8Api.mapOf();}
    private static AgentInvestigator capture(AtomicInteger calls,AtomicReference<Map<String,Object>> start){
        return new AgentInvestigator(){
            public boolean enabled(){return true;}
            public String identity(){return "no-network-agent";}
            public Map<String,Object> investigate(Map<String,Object> request,ToolHandler handler,Consumer<Map<String,Object>> events){
                calls.incrementAndGet();start.set(request);return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "modelCalls", 0, "toolCalls", 0);
            }
        };
    }
    private static ModelGateway rejectingModel(AtomicInteger calls){
        return new ModelGateway(){
            public boolean enabled(){return true;}
            public String identity(){return "must-not-author-rules";}
            public JsonNode request(String operation,Map<String,Object> input){calls.incrementAndGet();throw new AssertionError("AGENT_LED must not call model operation "+operation+" outside the evidence SDK");}
        };
    }
    private static Map<String,Object> run(String source,AgentInvestigator agent,AtomicInteger modelCalls){
        return run(source,agent,modelCalls,cn.bidcheck.compat.Java8Api.mapOf());
    }
    private static Map<String,Object> run(String source,AgentInvestigator agent,AtomicInteger modelCalls,Map<String,Object> additional){
        VerificationEngine engine=new VerificationEngine(rejectingModel(modelCalls),null);engine.setInvestigator(agent);
        Document tender=document("tender","TENDER","招标文件","tender-hash",source);
        Document bid=document("bid","BID","投标文件","bid-hash","投标文件目录，尚无本项证明正文。");
        Map<String,Object> context=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("reviewMode", "AGENT_LED", "allowModelRuleCompilation", true, "focusedCheck", true));context.putAll(additional);
        return engine.evaluate(checkpoint(source),cn.bidcheck.compat.Java8Api.listOf(bid),tender,context);
    }

    @Test void agentLedUsesServerCompilerAndExposesOnlyEvidenceWorkflow(){
        AtomicInteger agentCalls=new AtomicInteger(),modelCalls=new AtomicInteger();AtomicReference<Map<String,Object>> start=new AtomicReference<>();
        Map<String,Object> result=run("设计单位须提交营业执照。",capture(agentCalls,start),modelCalls);
        assertEquals(1,agentCalls.get());assertEquals(0,modelCalls.get());
        assertEquals("TARGET_FACTS_V1",map(start.get().get("context")).get("workflowPolicy"));
        List<String> tools=cn.bidcheck.compat.Java8Api.toList(maps(start.get().get("tools")).stream().map(t->Objects.toString(t.get("name"),"")));
        assertTrue(tools.contains("search_evidence"));assertTrue(tools.contains("recompute_check"));assertFalse(tools.contains("submit_review_plan"));
        Map<String,Object> review=map(result.get("agentReview"));
        assertEquals("SERVER_REQUIREMENT_COMPILER",review.get("planAuthority"));assertEquals(true,review.get("serverOwnedPlan"));
        assertEquals(false,review.get("modelAuthoredPlan"));assertEquals(0,review.get("submitReviewPlanCalls"));
        List<Map<String,Object>> steps=maps(result.get("steps"));
        assertTrue(steps.stream().anyMatch(s->"AGENT_SERVER_PLAN".equals(s.get("type"))));
        assertTrue(steps.stream().noneMatch(s->"REVIEW_PLAN".equals(s.get("type"))));
        Map<String,Object> metrics=map(result.get("metrics"));assertEquals(0,metrics.get("agentPlanSubmissionCalls"));assertEquals(true,metrics.get("agentEvidenceOnly"));
    }

    @Test void sourceOwnedAlternativeTreeReachesEvaluationWithoutFlattening(){
        AtomicInteger agentCalls=new AtomicInteger(),modelCalls=new AtomicInteger();AtomicReference<Map<String,Object>> start=new AtomicReference<>();
        Map<String,Object> result=run(DESIGN,capture(agentCalls,start),modelCalls);Plan plan=(Plan)result.get("plan");
        assertEquals(0,modelCalls.get());assertEquals(1,agentCalls.get());
        assertTrue(all(plan.rules()).stream().anyMatch(r->"ANY".equals(r.logic())&&r.children().size()>=2),plan.toString());
        Map<String,Object> audit=map(map(result.get("agentReview")).get("planAudit"));
        assertTrue(((Number)map(audit.get("logicCounts")).get("ANY")).intValue()>0,audit.toString());
        assertEquals(true,audit.get("conditionTreePreserved"));
    }

    @Test void structuralAuditCountsAllSupportedConditionTreeOperators(){
        Rule gate=leaf("gate","INPUT"),yes=leaf("yes","MATERIAL"),no=leaf("no","NOT_APPLICABLE");
        Rule choice=new Rule("choice","任一","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"任一","ANY",cn.bidcheck.compat.Java8Api.listOf(yes, no));
        Rule conditional=new Rule("conditional","条件","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"若适用","CONDITIONAL",cn.bidcheck.compat.Java8Api.listOf(gate, choice, no));
        Rule each=new Rule("each","逐主体","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf("subjects", cn.bidcheck.compat.Java8Api.listOf("甲", "乙")),"各成员","FOR_EACH",cn.bidcheck.compat.Java8Api.listOf(yes));
        Rule all=new Rule("all","全部","GROUP","","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"全部要求","ALL",cn.bidcheck.compat.Java8Api.listOf(conditional, each));
        Map<String,Object> audit=VerificationEngine.trustedPlanAudit(new Plan("ALL",cn.bidcheck.compat.Java8Api.listOf(all),"SERVER_FIXTURE",cn.bidcheck.compat.Java8Api.listOf()));Map<String,Object> counts=map(audit.get("logicCounts"));
        for(String logic:cn.bidcheck.compat.Java8Api.listOf("ALL", "ANY", "CONDITIONAL", "FOR_EACH"))assertTrue(((Number)counts.get(logic)).intValue()>0,logic+counts);
        assertEquals(true,audit.get("conditionTreePreserved"));assertEquals(0,audit.get("submitReviewPlanCalls"));
    }

    @Test void unsupportedServerLeafRemainsSystemIncompleteAndDoesNotInvokeAgentOrModel(){
        AtomicInteger agentCalls=new AtomicInteger(),modelCalls=new AtomicInteger();AtomicReference<Map<String,Object>> start=new AtomicReference<>();
        Map<String,Object> result=run("投标人应满足另行规定的特别条件。",capture(agentCalls,start),modelCalls);Plan plan=(Plan)result.get("plan");
        assertEquals(0,modelCalls.get());assertEquals(0,agentCalls.get());
        assertTrue(RequirementCompiler.leaves(plan.rules()).stream().anyMatch(r->"UNSUPPORTED".equals(r.predicate())),plan.toString());
        assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals(false,result.get("complete"));
        assertTrue(maps(result.get("gaps")).stream().anyMatch(g->Objects.toString(g.get("code"),"").contains("COMPILATION")||Objects.toString(g.get("code"),"").contains("UNSUPPORTED")),result.get("gaps").toString());
    }

    @Test void unavailableAgentCannotSilentlyFallBackToOrdinaryModelExtraction(){
        AtomicInteger modelCalls=new AtomicInteger();Map<String,Object> result=run("设计单位须提交营业执照。",null,modelCalls);
        assertEquals(0,modelCalls.get());assertEquals("UNDETERMINED",result.get("businessDecision"));assertEquals(false,result.get("complete"));
        assertEquals("AGENT_INVESTIGATOR_UNAVAILABLE",map(result.get("metrics")).get("stopReason"));
        assertTrue(maps(result.get("gaps")).stream().anyMatch(g->"AGENT_INVESTIGATION_UNAVAILABLE".equals(g.get("code"))),result.get("gaps").toString());
        assertTrue(maps(result.get("steps")).stream().noneMatch(s->cn.bidcheck.compat.Java8Api.setOf("REVIEW_PLAN", "EXTRACT").contains(s.get("type"))));
    }

    @Test void legacyModelAuthoredPlanIsRejectedBeforeLocalRecoveryReplay(){
        String source="设计单位须提交营业执照。";Map<String,Object> forged=cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(linked("id","forged","description","旧模型计划","predicate","MATERIAL","subject","投标人","field","material","operator","EXISTS","expected","其他材料","unit","","parameters",cn.bidcheck.compat.Java8Api.mapOf(),"sourceQuote",source,"logic","","children",cn.bidcheck.compat.Java8Api.listOf())));
        Map<String,Object> previous=cn.bidcheck.compat.Java8Api.mapOf("plan", forged, "agentReview", cn.bidcheck.compat.Java8Api.mapOf("planAuthority", "MODEL_AUTHORED"));AtomicInteger modelCalls=new AtomicInteger(),agentCalls=new AtomicInteger();AtomicReference<Map<String,Object>> start=new AtomicReference<>();
        Map<String,Object> result=run(source,capture(agentCalls,start),modelCalls,cn.bidcheck.compat.Java8Api.mapOf("previousAtomicCheck", previous, "recoveryMode", "RECOMPUTE_ONLY", "recoveryModelAllowed", false));Plan plan=(Plan)result.get("plan");
        assertEquals(0,modelCalls.get());assertEquals(0,agentCalls.get());assertNotEquals("LOCAL_REVALIDATION",plan.method());
        assertTrue(all(plan.rules()).stream().noneMatch(r->"forged".equals(r.id())),plan.toString());
        assertEquals("NON_SERVER_PLAN",map(map(result.get("agentReview")).get("planAudit")).get("priorPlanRejected"));
        assertTrue(maps(result.get("steps")).stream().noneMatch(s->"REVIEW_PLAN".equals(s.get("type"))));
    }

    private static Rule leaf(String id,String predicate){return new Rule(id,id,predicate,"","","","","",cn.bidcheck.compat.Java8Api.mapOf(),id,"",cn.bidcheck.compat.Java8Api.listOf());}
    private static List<Rule> all(List<Rule> roots){List<Rule> out=new ArrayList<>();for(Rule rule:roots){out.add(rule);out.addAll(all(rule.children()));}return out;}
}

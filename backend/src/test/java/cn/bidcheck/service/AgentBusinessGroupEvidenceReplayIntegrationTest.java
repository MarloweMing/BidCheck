package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.agent.AgentInvestigator;
import cn.bidcheck.verification.engine.ModelGateway;
import cn.bidcheck.verification.engine.VerificationEngine;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact engine replay path used by a BidService business-group follower. */
class AgentBusinessGroupEvidenceReplayIntegrationTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void followerRevalidatesLeaderSourcesAndFactsWithoutASecondAgentInvestigation(){
        AtomicInteger agentCalls=new AtomicInteger();
        ModelGateway compiler=new ModelGateway(){
            public boolean enabled(){return true;}
            public String identity(){return "group-replay-compiler";}
            public JsonNode request(String operation,Map<String,Object> input){
                assertEquals("COMPILE",operation);
                return json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(BidService.fields(
                    "id","role","description","项目负责人任职","predicate","PERSON_ROLE","subject","项目负责人",
                    "field","role","operator","","expected","","unit","","parameters",cn.bidcheck.compat.Java8Api.mapOf(),
                    "sourceQuote","本项目应配置项目负责人","logic","","children",cn.bidcheck.compat.Java8Api.listOf()))));
            }
        };
        VerificationEngine engine=new VerificationEngine(compiler,null);
        engine.setInvestigator(new AgentInvestigator(){
            public boolean enabled(){return true;}
            public String identity(){return "group-replay-agent";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> events){
                agentCalls.incrementAndGet();handler.call("search_evidence",cn.bidcheck.compat.Java8Api.mapOf("query", "项目负责人"));handler.call("recompute_check",cn.bidcheck.compat.Java8Api.mapOf());
                return cn.bidcheck.compat.Java8Api.mapOf("status", "COMPLETED", "requestId", "leader-request", "modelCalls", 1, "toolCalls", 2);
            }
        });
        Document bid=document("hash-bid","本项目人员配置\n项目负责人：测试甲\n拟在本合同任职：项目负责人\n姓名：测试甲");
        Checkpoint leaderCheckpoint=checkpoint("leader"),followerCheckpoint=checkpoint("follower");
        Map<String,Object> leaderContext=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("allowModelRuleCompilation", true, "recoveryStrategy", "TARGETED_EVIDENCE"));
        Map<String,Object> leaderResult=engine.evaluate(leaderCheckpoint,cn.bidcheck.compat.Java8Api.listOf(bid),null,leaderContext);
        assertEquals("PASS",leaderResult.get("businessDecision"),leaderResult.toString());assertEquals(1,agentCalls.get());
        Finding leader=new Finding("leader-finding","run",leaderCheckpoint,new Evaluation("PASS","","",cn.bidcheck.compat.Java8Api.listOf(),0,"AGENT_LED_REVIEW"),
            cn.bidcheck.compat.Java8Api.mapOf("atomicCheck", cn.bidcheck.compat.Java8Api.mapOf("agentInvestigation", leaderResult.get("agentInvestigation"))));
        AgentBusinessGroupReuse.Membership membership=new AgentBusinessGroupReuse.Membership("group","leader",cn.bidcheck.compat.Java8Api.listOf("leader", "follower"),cn.bidcheck.compat.Java8Api.mapOf());
        AgentBusinessGroupReuse.EvidenceReceipt receipt=AgentBusinessGroupReuse.evidenceReceipt(membership,"follower",leader,
            maps(leaderResult.get("steps")));
        assertTrue(receipt.reusable(),"leader must publish a source-scoped grounded snapshot");

        Map<String,Object> followerContext=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("allowModelRuleCompilation", true, "priorSteps", cn.bidcheck.compat.Java8Api.listOf()));
        assertTrue(AgentBusinessGroupReuse.attach(receipt,followerContext));
        followerContext.put("recoveryStrategy","RECOMPUTE_SHARED_EVIDENCE");followerContext.put("recoveryMode","TARGETED");
        followerContext.put("recoveryModelAllowed",false);
        Map<String,Object> followerResult=engine.evaluate(followerCheckpoint,cn.bidcheck.compat.Java8Api.listOf(bid),null,followerContext);
        assertEquals("PASS",followerResult.get("businessDecision"),followerResult.toString());
        assertEquals(true,followerResult.get("complete"));assertEquals(cn.bidcheck.compat.Java8Api.mapOf(),followerResult.get("agentInvestigation"));
        assertEquals(1,agentCalls.get(),"follower must use the receipt and independently recompute without another Agent call");

        Document changed=document("changed-hash","历史项目人员：测试乙");
        Map<String,Object> staleContext=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("allowModelRuleCompilation", true, "priorSteps", cn.bidcheck.compat.Java8Api.listOf()));
        assertTrue(AgentBusinessGroupReuse.attach(receipt,staleContext));
        staleContext.put("recoveryStrategy","RECOMPUTE_SHARED_EVIDENCE");staleContext.put("recoveryMode","TARGETED");
        staleContext.put("recoveryModelAllowed",false);
        Map<String,Object> stale=engine.evaluate(followerCheckpoint,cn.bidcheck.compat.Java8Api.listOf(changed),null,staleContext);
        assertNotEquals("PASS",stale.get("businessDecision"),"a changed frozen source must invalidate the fanned-out observation");
        assertEquals(1,agentCalls.get());
    }

    private Checkpoint checkpoint(String id){return new Checkpoint(id,"项目负责人","资格检查","HIGH","SEMANTIC_RESPONSE","","",
        "本项目应配置项目负责人","",true,true,true,null);}
    private Document document(String hash,String text){return new Document("bid","project","BID","bid.txt",1,hash,"",
        cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PARAGRAPH","TEXT","")),cn.bidcheck.compat.Java8Api.listOf());}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> maps(Object value){return value instanceof Collection<?>
        ?cn.bidcheck.compat.Java8Api.toList(((Collection<?>) (value)).stream().filter(Map.class::isInstance).map(row->(Map<String,Object>)row)):cn.bidcheck.compat.Java8Api.listOf();}
}

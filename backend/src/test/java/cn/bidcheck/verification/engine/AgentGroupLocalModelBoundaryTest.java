package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.agent.AgentInvestigator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentGroupLocalModelBoundaryTest {
    @Test void groupLocalRecoveryCannotInvokeModelOrAgentEvenWhenAnOldPlanIsRejected(){
        AtomicInteger permits=new AtomicInteger();ModelGateway model=new ModelGateway(){
            public boolean enabled(){return true;}public String identity(){return "model-boundary";}
            public JsonNode request(String operation,Map<String,Object> input){throw new AssertionError("local recovery called "+operation);}
        };
        VerificationEngine engine=new VerificationEngine(model,null);engine.setInvestigator(new AgentInvestigator(){
            public boolean enabled(){return true;}public String identity(){return "agent-boundary";}
            public Map<String,Object> investigate(Map<String,Object> start,ToolHandler handler,Consumer<Map<String,Object>> event){throw new AssertionError("local recovery invoked Agent");}
        });
        String requirement="投标人须提交有效营业执照。";
        Document tender=new Document("tender","p","TENDER","招标",1,"tender-hash","",List.of(new Page(1,requirement,"BLOCK","TEXT","")),List.of());
        Document bid=new Document("bid","p","BID","投标",1,"bid-hash","",List.of(new Page(1,"投标人：甲公司","PAGE","PDF_TEXT","")),List.of());
        Checkpoint cp=new Checkpoint("follower","营业执照","资格检查","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",requirement,"",true,true,true,new Evidence("tender","招标",1,"BLOCK",requirement,"",1));
        Map<String,Object> context=new LinkedHashMap<>();context.put("reviewMode","AGENT_LED");context.put("recoveryMode","EVIDENCE_RECOMPUTE");
        context.put("recoveryStrategy","RECOMPUTE_SHARED_EVIDENCE");context.put("recoveryModelAllowed",false);context.put("priorSteps",List.of());
        context.put("previousAtomicCheck",Map.of("plan",Map.of("rules",List.of()),"conditions",List.of()));
        context.put("modelAttemptPermit",(BooleanSupplier)()->{permits.incrementAndGet();return false;});
        Map<String,Object> result=assertDoesNotThrow(()->engine.evaluate(cp,List.of(bid),tender,context));
        assertEquals(0,permits.get(),"local compilation/replay must avoid requesting even a model permit");
        assertEquals(0,((Number)((Map<?,?>)result.get("metrics")).get("modelCalls")).intValue());
        assertFalse(Thread.currentThread().isInterrupted(),"missing local proof is not task cancellation");
    }
}

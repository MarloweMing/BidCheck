package cn.bidcheck.verification.engine;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionContractIsolationTest {
    @Test void timeoutsAndCredentialsDoNotChangeSuccessfulExtractionIdentityButModelDoes(){
        var first=new VerificationModelClient("http://127.0.0.1/chat/completions","key-a","model-a",Duration.ofSeconds(1));
        var changed=new VerificationModelClient("http://127.0.0.1/chat/completions","key-b","model-a",Duration.ofSeconds(90));
        assertEquals(first.identity(),changed.identity());
        assertNotEquals(first.identity(),new VerificationModelClient("http://127.0.0.1/chat/completions","key-a","model-b",Duration.ofSeconds(1)).identity());
    }
    @Test void extractionStepsSurviveDecisionOnlyVersionChangesButNotChangedInput()throws Exception{
        AtomicInteger requests=new AtomicInteger();
        ModelGateway gateway=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "test-contract";}
            public JsonNode request(String operation,Map<String,Object> input){requests.incrementAndGet();return new ObjectMapper().valueToTree(Map.of("facts",List.of()));}};
        Map<String,Object> input=Map.of("sources",List.of(Map.of("id","s1","quote","原文")));
        RunSession original=new RunSession(gateway,Map.of(),"source-set");original.model("EXTRACT",input);
        Map<String,Object> completed=new LinkedHashMap<>(original.steps.stream().filter(s->"COMPLETED".equals(s.get("status"))).findFirst().orElseThrow());
        assertEquals(ExtractionContracts.VERSION,completed.get("schemaVersion"));completed.put("engineVersion","prior-business-only-version");
        RunSession reused=new RunSession(gateway,Map.of("priorSteps",List.of(completed)),"source-set");reused.model("EXTRACT",input);
        assertEquals(1,requests.get());assertEquals(1,reused.resumedSteps);
        reused.model("EXTRACT",Map.of("sources",List.of(Map.of("id","s1","quote","修改后的原文"))));assertEquals(2,requests.get());
        completed.put("schemaVersion","incompatible-extraction-contract");
        new RunSession(gateway,Map.of("priorSteps",List.of(completed)),"source-set").model("EXTRACT",input);assertEquals(3,requests.get());
    }
}

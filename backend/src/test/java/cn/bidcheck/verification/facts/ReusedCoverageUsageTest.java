package cn.bidcheck.verification.facts;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;
import static org.junit.jupiter.api.Assertions.*;

class ReusedCoverageUsageTest {
    @Test void negativeReadReuseCreatesCurrentRunCoverageDependencyWithoutNewObservation(){
        FactLedgerServiceTest fixture=new FactLedgerServiceTest();Document doc=fixture.doc("doc","报价说明正文，未列金额。");List<Document> documents=List.of(doc);AtomicInteger calls=new AtomicInteger();ObjectMapper json=new ObjectMapper();
        ModelGateway model=new ModelGateway(){public boolean enabled(){return true;}public String identity(){return "model";}public JsonNode request(String operation,Map<String,Object> input){assertEquals("EXTRACT",operation);calls.incrementAndGet();return json.valueToTree(Map.of("facts",List.of()));}};
        VerificationEngine engine=new VerificationEngine(model,null);Checkpoint checkpoint=new Checkpoint("amount","报价","商务","HIGH","VALUE_COMPARE","amount","<=","100","元",true,true,true,null);
        Function<String,Map<String,Object>> context=run->Map.of("sharedFactLookup",(Function<Map<String,Object>,List<Map<String,Object>>>)request->fixture.ledger.lookup("project","engine-identity",documents,request),"sharedFactPublisher",(Consumer<Map<String,Object>>)batch->fixture.ledger.publish("project",run,"amount","engine-identity",documents,batch));
        assertEquals("UNDETERMINED",engine.evaluate(checkpoint,documents,null,context.apply("first")).get("businessDecision"));Map<String,Object> frozen=fixture.ledger.snapshot("project","first","engine-identity",documents);
        Map<String,Object> second=engine.evaluate(checkpoint,documents,null,context.apply("second"));assertEquals(1,calls.get());assertEquals("UNDETERMINED",second.get("businessDecision"));
        Map<String,Object> current=fixture.ledger.snapshot("project","second","engine-identity",documents);List<Map<String,Object>> coverage=maps(current.get("fieldCoverage"));assertFalse(coverage.isEmpty());
        assertTrue(coverage.stream().allMatch(row->"READ_NOT_FOUND".equals(row.get("status"))&&Boolean.TRUE.equals(row.get("reusedObservation"))&&Boolean.FALSE.equals(row.get("observedInCurrentRun"))&&Boolean.FALSE.equals(row.get("absenceProven"))&&!text(row,"reusedFromBatchId").isBlank()));
        assertFalse(maps(current.get("coverageDependencies")).isEmpty());assertTrue(maps(current.get("coverageDependencies")).stream().allMatch(row->"second".equals(row.get("runId"))));assertEquals(frozen,fixture.ledger.snapshot("project","first","engine-identity",documents));assertTrue(maps(current.get("occurrences")).isEmpty());
    }
}

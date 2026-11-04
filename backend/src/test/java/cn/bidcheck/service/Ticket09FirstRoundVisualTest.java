package cn.bidcheck.service;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.service.BidService.*;

class Ticket09FirstRoundVisualTest {
    Finding finding(String status){return new Finding("f","r",null,new Evaluation(status,"合成结论","",Collections.emptyList(),0,"TEST"),fields("atomicCheck",fields("gaps",Collections.singletonList(fields("code","UNCOVERED_OTHER_TARGET")))));}
    Finding adopt(Finding before,Map<String,Object> old,Map<String,Object> refreshed,java.util.function.Function<Map<String,Object>,Finding> evaluate)throws Exception{
        java.lang.reflect.Method m=BidService.class.getDeclaredMethod("adoptFirstRoundVisual",Finding.class,Map.class,Map.class,java.util.function.Function.class);m.setAccessible(true);return (Finding)m.invoke(null,before,old,refreshed,evaluate);
    }
    @Test void cachedPositiveAndNegativeBothRecomputeOnlyTheirCoveredTargetWithoutModel()throws Exception{
        for(String result:Arrays.asList("PASS","FAIL","UNDETERMINED")){
            Map<String,Object> observation=fields("obligationId","covered","type","SEAL","value",result,"source",fields("documentId","synthetic","documentHash","hash","documentVersion",1,"page",2));
            AtomicInteger count=new AtomicInteger();Finding after=adopt(finding("UNDETERMINED"),Collections.emptyMap(),fields("visualObservations",Collections.singletonList(observation)),ctx->{
                count.incrementAndGet();assertEquals(false,ctx.get("recoveryModelAllowed"));assertEquals("EVIDENCE_RECOMPUTE",ctx.get("recoveryMode"));assertEquals(Collections.singletonList("covered"),ctx.get("recoveryTargetObligationIds"));return finding(result);
            });
            assertEquals(1,count.get());assertEquals(result,after.evaluation().status());assertEquals("UNCOVERED_OTHER_TARGET",text(CheckResolution.maps(CheckResolution.atomic(after).get("gaps")).get(0),"code"));
        }
    }
    @Test void absentOrUnchangedCacheKeepsOriginalFindingAndDoesNotEvaluate()throws Exception{
        Finding before=finding("UNDETERMINED");Map<String,Object> current=fields("visualObservations",Collections.singletonList(fields("obligationId","already","value","old")));
        assertSame(before,adopt(before,current,current,ctx->{fail("duplicate receipt must not recompute");return null;}));
        assertSame(before,adopt(before,Collections.emptyMap(),Collections.emptyMap(),ctx->{fail("cache miss remains pending");return null;}));
    }
}

package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ticket231QualificationDecisionReuseTest {
    @Test void threeLegacyCardIdsRepresentOneRowWithoutConsumingASecondRow(){
        InferenceConfirmationServiceTest fixture=new InferenceConfirmationServiceTest();
        fixture.pendingGap("run",fixture.cp,"QUALIFICATION_QUERY_IDENTITY_REQUIRED");
        String first=String.join("",Collections.nCopies(64,"a")),second=String.join("",Collections.nCopies(64,"b"));
        for(String target:Arrays.asList("URL","SCREENSHOT","MATCH","OTHER_ROW")){
            String row="OTHER_ROW".equals(target)?second:first;
            fixture.service.capture("run","cp",fixture.bid,fixture.tender,fields("id","legacy-"+target,"targetId",target,"claim",row,"unresolvedGapCodes",Collections.singletonList("QUALIFICATION_QUERY_IDENTITY_REQUIRED"),
                "proposedFact",fields("kind","RELATION","field","QUALIFICATION_ROW_NUMBER","subject","合成施工单位","value","D1001","attributes",fields("rowIdentity",row)),
                "evidences",Arrays.asList(fields("sourceId","query","documentId","bid","documentHash","hash","documentVersion",1),
                    fields("sourceId","certificate","documentId","bid","documentHash","hash","documentVersion",1))));
        }
        List<Map<String,Object>> visible=fixture.service.list("run");assertEquals(2,visible.size());
        String id=text(visible.stream().filter(c->first.equals(c.get("claim"))).findFirst().get(),"id");
        fixture.service.decide("run",id,fixture.decision("CONFIRMED"));
        visible=fixture.service.list("run");assertEquals(2,visible.size());
        assertEquals("CONFIRMED",visible.stream().filter(c->first.equals(c.get("claim"))).findFirst().get().get("status"));
        assertEquals("NEEDS_CONFIRMATION",visible.stream().filter(c->second.equals(c.get("claim"))).findFirst().get().get("status"));
        verify(fixture.bids,times(1)).recoverRun("project","run");
    }
}

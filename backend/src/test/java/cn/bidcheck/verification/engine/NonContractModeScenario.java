package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

public final class NonContractModeScenario {
    public static void verify(){
        Rule r=new Rule("label","项目标记","FIELD","","标记","EXISTS","","",Collections.emptyMap(),"应提供标记","",Collections.emptyList());
        List<Rule> rules=Collections.singletonList(r);
        EvidenceInvestigation.Host h=new EvidenceInvestigationTest().host(new Plan("ALL",rules,"LOCAL",Collections.emptyList()),rules,"标记：白玉");
        Map<String,Object> definition=EvidenceInvestigation.tools(rules).stream().filter(t->"submit_candidate_fact".equals(t.get("name"))).findFirst().get();
        assertFalse(definition.get("parameters").toString().contains("submissionMode"));
        h.call("search_evidence",linked("query","标记","targetObligationId","label"));
        String source=h.disclosed.keySet().iterator().next();
        h.call("read_section",linked("sourceId",source,"targetObligationId","label"));
        Map<String,Object> fact=linked("kind","FIELD","field","标记","rawValue","白玉","value","白玉","sourceIds",Collections.singletonList(source));
        Map<String,Object> receipt=h.call("submit_candidate_fact",linked("targetObligationId","label","fact",fact,
            "submissionMode","CONTRACT_FIELDS","contractFields",Arrays.asList(linked("field","bogus"))));
        assertNotNull(receipt.get("protocolNotice"),receipt.toString());
        assertEquals("COMPLETED",receipt.get("status"),receipt.toString());
        assertTrue(h.facts.values().stream().anyMatch(f->"白玉".equals(f.rawValue())));
    }
    private NonContractModeScenario(){}
}

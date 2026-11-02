package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real host seam: INFERRED's early return must obey the same receipt contract as normal facts. */
class AgentInferredReceiptProtocolTest {
    @Test void inferredOutsideTargetDoesNotReturnEightGlobalWorkStatesOrClaimBusinessSuccess(){
        Rule a=new Rule("A","A证","CERTIFICATE","李明","证书","EXISTS","A类","",Map.of(),"应提供有效A证","",List.of());
        Rule b=new Rule("B","B证","CERTIFICATE","王强","证书","EXISTS","B类","",Map.of(),"应提供有效B证","",List.of());
        var rules=List.of(a,b);var corpus=new EvidenceCorpus(List.of(SharedFactReuseTest.doc("bid","project",1,"姓名：李明\n登记资料","姓名：王强\n登记资料")));
        Map<String,Object> context=new LinkedHashMap<>();
        var host=new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",rules,"LOCAL",List.of()),rules,corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"disabled","test"),new RunSession(ModelGateway.disabled(),context,"test"),null,new ArrayList<>(),Set.of());
        host.start();Source source=corpus.all.get(0);host.disclosed.put(source.id(),source);var factsBefore=Map.copyOf(host.facts);
        // MONTHS is a legal proposed-fact type, but not the certificate target's contract.
        var fact=linked("kind","INFERRED","attributes",linked("inference",linked("claim","人员月份关联","reasoning","原文需要人工确认","confidence",0.95,
            "evidence",List.of(linked("sourceId",source.id(),"quote","姓名：李明"))),"proposedFact",linked("kind","MONTHS")));
        var result=host.call("submit_candidate_fact",linked("targetObligationId","B","fact",fact));
        assertEquals("REJECTED",result.get("status"));assertEquals("FACT_OUTSIDE_TARGET",result.get("error"));
        var items=RuleEvaluator.maps(result.get("semanticWorkItems"));assertEquals(1,items.size(),result.toString());assertEquals("B",items.get(0).get("targetObligationId"));
        assertFalse(str(items.get(0),"workKey").isBlank());assertFalse(str(items.get(0),"evidenceRevision").isBlank());
        assertFalse(Boolean.TRUE.equals(result.get("targetCheckCompleted")));assertFalse(Boolean.TRUE.equals(result.get("businessCheckCompleted")));
        assertEquals(factsBefore,host.facts);assertEquals(List.of("B"),result.get("affectedTargetObligationIds"));
    }
}

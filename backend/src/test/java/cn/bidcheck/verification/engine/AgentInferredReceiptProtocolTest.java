package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real host seam: INFERRED's early return must obey the same receipt contract as normal facts. */
class AgentInferredReceiptProtocolTest {
    @Test void inferredOutsideTargetDoesNotReturnEightGlobalWorkStatesOrClaimBusinessSuccess(){
        Rule a=new Rule("A","A证","CERTIFICATE","李明","证书","EXISTS","A类","",cn.bidcheck.compat.Java8Api.mapOf(),"应提供有效A证","",cn.bidcheck.compat.Java8Api.listOf());
        Rule b=new Rule("B","B证","CERTIFICATE","王强","证书","EXISTS","B类","",cn.bidcheck.compat.Java8Api.mapOf(),"应提供有效B证","",cn.bidcheck.compat.Java8Api.listOf());
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Rule> rules=cn.bidcheck.compat.Java8Api.listOf(a, b);cn.bidcheck.verification.engine.EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(SharedFactReuseTest.doc("bid","project",1,"姓名：李明\n登记资料","姓名：王强\n登记资料")));
        Map<String,Object> context=new LinkedHashMap<>();
        cn.bidcheck.verification.engine.EvidenceInvestigation.Host host=new EvidenceInvestigation.Host(new EvidenceInvestigationTest().checkpoint(),new Plan("ALL",rules,"LOCAL",cn.bidcheck.compat.Java8Api.listOf()),rules,corpus,new LinkedHashMap<>(),context,
            new SharedFactReuse(corpus,context,"disabled","test"),new RunSession(ModelGateway.disabled(),context,"test"),null,new ArrayList<>(),cn.bidcheck.compat.Java8Api.setOf());
        host.start();Source source=corpus.all.get(0);host.disclosed.put(source.id(),source);java.util.Map<java.lang.String, cn.bidcheck.verification.engine.VerificationTypes.Fact> factsBefore=cn.bidcheck.compat.Java8Api.mapCopyOf(host.facts);
        // MONTHS is a legal proposed-fact type, but not the certificate target's contract.
        java.util.Map<java.lang.String, java.lang.Object> fact=linked("kind","INFERRED","attributes",linked("inference",linked("claim","人员月份关联","reasoning","原文需要人工确认","confidence",0.95,
            "evidence",cn.bidcheck.compat.Java8Api.listOf(linked("sourceId",source.id(),"quote","姓名：李明"))),"proposedFact",linked("kind","MONTHS")));
        java.util.Map<java.lang.String, java.lang.Object> result=host.call("submit_candidate_fact",linked("targetObligationId","B","fact",fact));
        assertEquals("REJECTED",result.get("status"));assertEquals("FACT_OUTSIDE_TARGET",result.get("error"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> items=RuleEvaluator.maps(result.get("semanticWorkItems"));assertEquals(1,items.size(),result.toString());assertEquals("B",items.get(0).get("targetObligationId"));
        assertFalse(cn.bidcheck.compat.Java8Api.isBlank(str(items.get(0),"workKey")));assertFalse(cn.bidcheck.compat.Java8Api.isBlank(str(items.get(0),"evidenceRevision")));
        assertFalse(Boolean.TRUE.equals(result.get("targetCheckCompleted")));assertFalse(Boolean.TRUE.equals(result.get("businessCheckCompleted")));
        assertEquals(factsBefore,host.facts);assertEquals(cn.bidcheck.compat.Java8Api.listOf("B"),result.get("affectedTargetObligationIds"));
    }
}

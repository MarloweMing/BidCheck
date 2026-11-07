package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class ContractRecordAnchorTest {
    static Map<String,Object> identity(Fixture f) {
        Map<String,Object> fact=f.candidate("contractName",NAME);
        fact.put("attributes",linked("contractName",NAME,"contractNumber",NUMBER));
        return fact;
    }
    static Map<String,Object> submit(Fixture f,Map<String,Object> atom) {
        return f.host.call("submit_candidate_fact",linked("targetObligationId",f.target,"submissionMode","CONTRACT_FIELDS",
            "fact",identity(f),"contractFields",Collections.singletonList(atom)));
    }
    static Map<String,Object> amount(Source page) {
        return linked("field","amount","rawValue","200","sourceIds",Collections.singletonList(page.id()),
            "attributes",linked("amount","200","amountBasis","合同金额","unit","万元"));
    }
    @Test void amountOnBodyPageWithCoverAnchorIsAccepted() throws Exception {
        Fixture f=new Fixture(identityBody(),"合同金额：200万元");
        Map<String,Object> result=submit(f,amount(source(f.corpus,5)));
        assertTrue(RuleEvaluator.maps(result.get("rejections")).isEmpty(),result::toString);
        Map<String,Object> record=(Map<String,Object>)result.get("contractRecord");
        assertEquals(false,record.get("businessProof"));assertEquals(NUMBER,record.get("contractNumber"));
        Fact fact=f.host.facts.values().stream().filter(v->v.field().equals("amount")).findFirst().get();
        assertEquals(Collections.singletonList(source(f.corpus,4).id()),fact.attributes().get("recordAnchorSourceIds"));
        assertEquals(new HashSet<>(Arrays.asList(source(f.corpus,4).id(),source(f.corpus,5).id())),new HashSet<>(fact.sourceIds()));
    }
    @Test void amountOnlyOnCoverAnchorIsRejected() throws Exception {
        Fixture f=new Fixture(identityBody()+"\n合同金额：200万元","本页没有金额。");
        Map<String,Object> result=submit(f,amount(source(f.corpus,4)));
        assertFalse(RuleEvaluator.maps(result.get("rejections")).isEmpty());
        assertEquals("/contractFields/0/rawValue",RuleEvaluator.maps(result.get("rejections")).get(0).get("fieldPath"));
        assertFalse(f.host.facts.values().stream().anyMatch(v->v.field().equals("amount")));
    }
    @Test void anchorFromAnotherDeclaredRowIsRejected() throws Exception {
        String other="乙中心装饰装修工程施工合同";
        EvidenceCorpus corpus=corpus("本项目投标函",list("1 "+NAME+" 签订时间：2023年1月1日\n2 "+other+" 签订时间：2024年1月1日"),
            "①"+NAME,identityBody(),"②"+other,"合同金额：200万元");
        Rule rule=withRows(scoringRule(),corpus);
        List<Rule> rules=SourcePerformanceScore.evidenceRules(rule);
        List<Rule> targets=new ArrayList<>();for(Rule r:rules)if(r.predicate().equals("CONTRACT"))targets.add(r);
        assertEquals(2,targets.size());
        Map<String,Object> ctx=dates();ctx.put("reviewMode","AGENT_LED");
        EvidenceInvestigation.Host host=new EvidenceInvestigation.Host(checkpoint(),new Plan("ALL",Collections.singletonList(rule),"LOCAL",Collections.emptyList()),rules,corpus,
            new LinkedHashMap<>(),ctx,new SharedFactReuse(corpus,ctx,"p","cp"),new RunSession(ModelGateway.disabled(),ctx,"cp"),
            null,new ArrayList<>(),Collections.emptySet());
        host.start();for(Source s:corpus.all)host.disclosed.put(s.id(),s);
        Source cover=source(corpus,4),body=source(corpus,6);
        host.call("read_section",linked("targetObligationId",targets.get(0).id(),"sourceId",cover.id()));
        host.call("read_section",linked("targetObligationId",targets.get(1).id(),"sourceId",body.id()));
        Map<String,Object> base=linked("kind","CONTRACT","field","contractNumber","rawValue",NUMBER,
            "attributes",linked("contractNumber",NUMBER),"sourceIds",Collections.singletonList(cover.id()));
        Map<String,Object> receipt=host.call("submit_candidate_fact",linked("targetObligationId",targets.get(0).id(),"fact",base));
        Map<String,Object> record=(Map<String,Object>)receipt.get("contractRecord");assertNotNull(record,receipt::toString);
        Map<String,Object> atom=amount(body);atom.put("recordId",record.get("recordId"));
        Map<String,Object> result=host.call("submit_candidate_fact",linked("targetObligationId",targets.get(1).id(),"fact",base,
            "submissionMode","CONTRACT_FIELDS","contractFields",Collections.singletonList(atom)));
        assertTrue(result.toString().contains("CONTRACT_PAGE_LINK_UNPROVEN"),result::toString);
        assertFalse(host.facts.values().stream().anyMatch(v->v.field().equals("amount")));
    }
    @Test void conflictingNumberWithRecordIdIsRejected() throws Exception {
        Fixture f=new Fixture(identityBody(),"合同金额：200万元");
        Map<String,Object> record=(Map<String,Object>)f.submit(identity(f)).get("contractRecord");assertNotNull(record);
        Map<String,Object> atom=amount(source(f.corpus,5));atom.put("recordId",record.get("recordId"));
        ((Map<String,Object>)atom.get("attributes")).put("contractNumber","OTHER-009");
        Map<String,Object> result=submit(f,atom);
        assertTrue(result.toString().contains("CONTRACT_IDENTITY_CONFLICT"));
        assertFalse(f.host.facts.values().stream().anyMatch(v->v.field().equals("amount")));
    }
    @Test void submissionAndScoringUseSameRecordBinding() throws Exception {
        Fixture f=new Fixture(identityBody(),"合同金额：200万元");
        Map<String,Object> receipt=submit(f,amount(source(f.corpus,5)));
        Fact amount=f.host.facts.values().stream().filter(v->v.field().equals("amount")).findFirst().orElse(null);
        assertNotNull(amount,receipt::toString);
        RuleEvaluator evaluator=new RuleEvaluator(new ArrayList<>(f.host.facts.values()),f.corpus,f.host.ctx);
        Decision result=evaluator.evaluate(f.parent);
        assertTrue(RuleEvaluator.maps(result.proof().get("contractChecks")).stream()
            .anyMatch(c->ProofVerifier.strings(c.get("factIds")).contains(amount.id())),result::toString);
        assertNotEquals("PASS",result.status());
    }
}

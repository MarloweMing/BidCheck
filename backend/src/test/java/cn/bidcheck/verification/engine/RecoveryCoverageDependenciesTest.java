package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.FieldCoverage;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCoverageDependenciesTest {
    private final Rule rule=SharedFactReuseTest.rule();
    private final EvidenceCorpus corpus=new EvidenceCorpus(Collections.singletonList(
        SharedFactReuseTest.doc("bid","project",1,"投标报价为80元。","另一处投标报价为120元。")));
    private String key(){return FieldCoverage.key(SharedFactReuse.observationTarget(rule));}
    private Map<String,Object> previous(List<String> required){
        return linked("recoveryMode","TARGETED","previousAtomicCheck",linked("coverageDependencies",
            Collections.singletonList(linked("obligationId",rule.id(),"observationKey",key(),"sourceIds",required,"gapCode","EVIDENCE_LOOKUP_INCOMPLETE"))));
    }
    private Decision guarded(SharedFactReuse shared,List<String> required){
        Map<String,Object> context=previous(required);
        RecoveryCoverageDependencies deps=new RecoveryCoverageDependencies(context,Collections.singletonList(rule),Collections.singletonList(rule),corpus.all,"model");
        deps.apply(context,shared);
        Decision pass=new Decision(rule.id(),"报价","PASS","局部已证实",Collections.emptyList(),Collections.emptyList(),Collections.emptyMap());
        return RecoveryCoverageDependencies.guard(rule,pass,context);
    }
    @Test void requiresExactSourceAndConditionCoverage(){
        Function<Map<String,Object>,?> lookup=req->Collections.singletonList(linked("recordType","LOOKUP_METADATA","lookupPagesComplete",true,"coveredSourceIds",req.get("sourceIds")));
        SharedFactReuse shared=new SharedFactReuse(corpus,linked("sharedFactLookup",lookup),"model","engine");
        Source first=corpus.all.get(0),last=corpus.all.get(corpus.all.size()-1);
        shared.lookup(Collections.singletonList(rule),Collections.singletonList(first),Collections.emptyList());
        assertEquals("UNDETERMINED",guarded(shared,Arrays.asList(first.id(),last.id())).status());
        assertEquals("PASS",guarded(shared,Collections.singletonList(first.id())).status());
        assertEquals("PASS",guarded(shared,Collections.emptyList()).status());
        assertEquals("UNDETERMINED",guarded(shared,Collections.singletonList("unread")).status());
    }
    @Test void truncatingAnotherObservationDoesNotReopenCompletedTarget(){
        Rule other=new Rule("other","其他字段","FIELD","","other","=","","",Collections.emptyMap(),"","",Collections.emptyList());
        Function<Map<String,Object>,?> lookup=req->ProofVerifier.strings(req.get("observationKeys")).contains(key())
            ?Collections.singletonList(linked("recordType","LOOKUP_METADATA","lookupPagesComplete",true,"coveredSourceIds",req.get("sourceIds")))
            :Collections.singletonList(linked("recordType","LOOKUP_METADATA","factLookupTruncated",true));
        SharedFactReuse shared=new SharedFactReuse(corpus,linked("sharedFactLookup",lookup),"model","engine");
        shared.lookup(Collections.singletonList(rule),corpus.all,Collections.emptyList());
        shared.lookup(Collections.singletonList(other),corpus.all,Collections.emptyList());
        assertTrue(shared.factLookupTruncated());assertFalse(shared.truncatedFor(key()));
        assertEquals("PASS",guarded(shared,Collections.singletonList(corpus.all.get(0).id())).status());
    }
    @Test void incompleteContinuationCannotAcknowledgeConsumedScope(){
        Function<Map<String,Object>,?> lookup=req->Collections.singletonList(linked("recordType","LOOKUP_METADATA","nextCursor","same"));
        SharedFactReuse shared=new SharedFactReuse(corpus,linked("sharedFactLookup",lookup),"model","engine");
        shared.lookup(Collections.singletonList(rule),corpus.all,Collections.emptyList());
        assertTrue(shared.truncatedFor(key()));assertTrue(shared.coveredSources(key()).isEmpty());
    }
    @Test void secondPageCounterevidenceIsVerifiedBeforeCoverageCloses(){
        SharedFactReuseTest fixtures=new SharedFactReuseTest();
        Rule price=fixtures.pricedRule("100");
        EvidenceCorpus prices=new EvidenceCorpus(Collections.singletonList(SharedFactReuseTest.doc("bid","project",1,
            "本项目甲公司，不划分标段，人民币含税投标总报价：80元。","本项目甲公司，不划分标段，人民币含税投标总报价：120元。")));
        Function<Map<String,Object>,?> callback=req->{
            boolean last=req.containsKey("cursor");String amount=last?"120":"80";Source source=prices.all.get(last?1:0);
            Fact fact=new Fact("price-"+amount,"NUMBER","甲公司","amount",amount,amount,"元","投标总报价","CURRENT_PROJECT",
                Collections.singletonList(source.id()),linked("lot","不划分标段","currency","人民币","taxBasis","含税"));
            Map<String,Object> entry=SharedFactReuseTest.entry(prices,SharedFactReuse.value(fact));
            if(last){entry.put("lookupPagesComplete",true);entry.put("coveredSourceIds",req.get("sourceIds"));}
            else entry.put("nextCursor","next");
            return Collections.singletonList(entry);
        };
        Map<String,Object> context=linked("sharedFactLookup",callback);
        SharedFactReuse shared=new SharedFactReuse(prices,context,"model","engine");
        List<Fact> returned=shared.lookup(Collections.singletonList(price),prices.all,Collections.emptyList());
        assertTrue(returned.stream().anyMatch(f->"120".equals(f.value())));
        assertNotEquals("PASS",new RuleEvaluator(returned,prices,context).evaluate(price).status());
    }
}

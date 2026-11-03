package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Fast existence still uses the ordinary source-review and identity proof contracts. */
class PersonnelCountProofContractTest {
    private final PersonnelCountRoleTest data=new PersonnelCountRoleTest();
    private Rule one(){return data.count("专职安全员","","1","专职安全员1人。",cn.bidcheck.compat.Java8Api.mapOf());}

    @Test void unrelatedEmployerDoesNotBlockExistenceOrLoseSharedReviewProof(){
        EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲",data.appointment("专职安全员","李乙","","甲公司",""));
        List<Fact> facts=data.checked(corpus,data.role(corpus,1,"专职安全员","张甲","",""),data.role(corpus,2,"专职安全员","李乙","甲公司",""));
        Rule rule=one();RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,cn.bidcheck.compat.Java8Api.mapOf());evaluator.incompleteSourceReview.add(rule.id());
        Decision result=evaluator.evaluate(rule);assertEquals("PASS",result.status(),result.toString());
        assertEquals("专职安全员",result.proof().get("requestedRole"));assertEquals("ROLE_CONFIGURATION",result.proof().get("comparison"));
        assertEquals(1,result.proof().get("requiredCount"));assertEquals(false,result.proof().get("populationComplete"));
        assertEquals(0,result.proof().get("provenDistinctPeople"));assertEquals(1,result.proof().get("ignoredUnrelatedCandidateCount"));
        assertTrue(result.proof().containsKey("sourceReviewClosure"));
    }

    @Test void unrelatedEmployerCannotHideConflictingStrongIdentities(){
        EvidenceCorpus corpus=data.corpus(data.appointment("专职安全员","张甲","110101199001010011","",""),
                data.appointment("专职安全员","张甲","110101199001010022","甲公司",""));
        List<Fact> facts=data.checked(corpus,data.role(corpus,1,"专职安全员","张甲","",""),data.role(corpus,2,"专职安全员","张甲","甲公司",""));
        Decision result=data.evaluate(corpus,facts,one());assertEquals("UNDETERMINED",result.status());
        assertEquals("PERSON_COUNT_IDENTITY_AMBIGUOUS",result.gaps().get(0).code());
    }

    @Test void contradictoryIdentifiersInsideOneFactAreNotJustMissingIdentity(){
        EvidenceCorpus corpus=data.corpus(data.appointment("专职安全员","张甲","110101199001010011","",""),
                data.appointment("专职安全员","张甲","110101199001010022","",""));
        Fact fact=new Fact("joined","PERSON_ROLE","专职安全员","role","张甲","张甲","","","CURRENT_PROJECT",
                cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().map(Source::id)),cn.bidcheck.compat.Java8Api.mapOf());
        List<Fact> facts=data.checked(corpus,fact);Decision result=data.evaluate(corpus,facts,one());
        assertEquals("UNDETERMINED",result.status());assertEquals("PERSON_COUNT_IDENTITY_AMBIGUOUS",result.gaps().get(0).code());
    }

    @Test void aPriorWitnessCannotHideNewStrongIdentityConflictsOnResume(){
        EvidenceCorpus before=data.corpus("本项目专职安全员：张甲");List<Fact> original=data.checked(before,data.role(before,1,"专职安全员","张甲","",""));
        Rule rule=one();Decision prior=data.evaluate(before,original,rule);assertEquals("PASS",prior.status());
        EvidenceCorpus changed=data.corpus("本项目专职安全员：张甲",data.appointment("专职安全员","张甲","110101199001010011","",""),
                data.appointment("专职安全员","张甲","110101199001010022","",""));
        List<Fact> current=data.checked(changed,data.role(changed,1,"专职安全员","张甲","",""),
                data.role(changed,2,"专职安全员","张甲","",""),data.role(changed,3,"专职安全员","张甲","",""));
        assertEquals(original.get(0).id(),current.get(0).id(),"unchanged witness is intentionally still replayable");
        assertFalse(SourceLocalExistenceClosure.proven(rule,prior,new RuleEvaluator(current,changed,cn.bidcheck.compat.Java8Api.mapOf())));
    }
}

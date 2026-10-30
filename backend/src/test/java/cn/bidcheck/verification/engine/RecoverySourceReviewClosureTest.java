package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.FieldCoverage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the recovery dependency gate after the normal evaluator coverage gate. */
class RecoverySourceReviewClosureTest {
    final PersonnelCountRoleTest data=new PersonnelCountRoleTest();
    record Recovery(Map<String,Object> context,SharedFactReuse shared,RecoveryCoverageDependencies dependencies){}
    private Rule one(){return data.count("专职安全员","","1","专职安全员1人。",Map.of());}
    private List<Fact> roles(EvidenceCorpus corpus){return data.checked(corpus,data.role(corpus,1,"专职安全员","张甲","",""));}
    private Decision evaluate(EvidenceCorpus corpus,List<Fact> facts,Rule rule){
        RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,Map.of());evaluator.incompleteSourceReview.add(rule.id());return evaluator.evaluate(rule);
    }
    private Map<String,Object> dependency(Rule rule,String code,EvidenceCorpus corpus){
        return linked("obligationId",rule.id(),"observationKey",FieldCoverage.key(SharedFactReuse.observationTarget(rule)),"gapCode",code,
            "sourceIds",corpus.all.stream().map(Source::id).toList(),"businessProof",false);
    }
    private Recovery recovery(EvidenceCorpus corpus,List<Rule> rules,String code,Map<String,Object> extraPrevious){
        Map<String,Object> previous=new LinkedHashMap<>(extraPrevious);previous.put("coverageDependencies",rules.stream().map(r->dependency(r,code,corpus)).toList());
        Map<String,Object> context=new LinkedHashMap<>(Map.of("recoveryStrategy","TARGETED_EVIDENCE","previousAtomicCheck",previous));
        SharedFactReuse shared=new SharedFactReuse(corpus,context,"test-model","test-engine");
        RecoveryCoverageDependencies dependencies=new RecoveryCoverageDependencies(context,rules,rules,corpus.all,"test-model");dependencies.apply(context,shared);
        return new Recovery(context,shared,dependencies);
    }
    private List<Map<String,Object>> snapshot(Recovery recovery,EvidenceCorpus corpus,List<Rule> rules,RuleEvaluator evaluator){
        return recovery.dependencies().snapshot(recovery.context(),rules,evaluator.decisions,recovery.shared(),corpus.all);
    }
    @Test void localExistenceClosesBothGatesAndIsNotPersistedAsUnfinishedAgain(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲","本项目资料员：李乙");
        Recovery recovery=recovery(corpus,List.of(rule),"SHARED_SOURCE_REVIEW_REQUIRED",Map.of());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());evaluator.incompleteSourceReview.add(rule.id());
        Decision result=evaluator.evaluate(rule);assertEquals("PASS",result.status(),result.toString());assertTrue(snapshot(recovery,corpus,List.of(rule),evaluator).isEmpty());
        // apply may run again after another shared read. It must revalidate, not resurrect
        // an eternal persisted blocker or rely on an old serialized PASS.
        recovery.dependencies().apply(recovery.context(),recovery.shared());
        RuleEvaluator refreshed=new RuleEvaluator(roles(corpus),corpus,recovery.context());assertEquals("PASS",refreshed.evaluate(rule).status());
        assertTrue(snapshot(recovery,corpus,List.of(rule),refreshed).isEmpty());
    }
    @Test void anInheritedDependencyAloneRunsTheSameCurrentSourceProof(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");
        Recovery recovery=recovery(corpus,List.of(rule),"SHARED_SOURCE_REVIEW_REQUIRED",Map.of());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());
        Decision result=evaluator.evaluate(rule);assertEquals("PASS",result.status());assertTrue(result.proof().containsKey("sourceReviewClosure"));
    }
    @Test void onlyTheExactClosedObligationIsRemovedFromSnapshot(){
        Rule rule=one(),other=new Rule("other","资料员人数","PERSON_COUNT","资料员","","","1","",Map.of(),"资料员1人","",List.of());
        EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");Recovery recovery=recovery(corpus,List.of(rule,other),"SHARED_SOURCE_REVIEW_REQUIRED",Map.of());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());assertEquals("PASS",evaluator.evaluate(rule).status());
        List<Map<String,Object>> pending=snapshot(recovery,corpus,List.of(rule,other),evaluator);assertEquals(1,pending.size());assertEquals("other",pending.get(0).get("obligationId"));
    }
    @Test void unreadSharedFactLookupCannotBeClosedByLocalExistence(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");Recovery recovery=recovery(corpus,List.of(rule),"EVIDENCE_LOOKUP_INCOMPLETE",Map.of());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());evaluator.incompleteSourceReview.add(rule.id());
        Decision result=evaluator.evaluate(rule);assertEquals("UNDETERMINED",result.status());assertEquals("EVIDENCE_LOOKUP_INCOMPLETE",result.gaps().get(0).code());
        assertEquals(1,snapshot(recovery,corpus,List.of(rule),evaluator).size());
    }
    @Test void aLaterSourceReviewGapCannotOverwriteTheStrongerLookupDependency(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");
        Recovery recovery=recovery(corpus,List.of(rule),"EVIDENCE_LOOKUP_INCOMPLETE",Map.of("conditions",List.of(Map.of("id",rule.id(),"gaps",List.of(Map.of("code","SHARED_SOURCE_REVIEW_REQUIRED"))))));
        Decision result=new RuleEvaluator(roles(corpus),corpus,recovery.context()).evaluate(rule);
        assertEquals("UNDETERMINED",result.status());assertEquals("EVIDENCE_LOOKUP_INCOMPLETE",result.gaps().get(0).code());
    }
    @Test void sameProjectRevocationKeepsInheritedDependencyPending(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲","本项目专职安全员张甲已撤回。");
        Recovery recovery=recovery(corpus,List.of(rule),"SHARED_SOURCE_REVIEW_REQUIRED",Map.of());RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());
        Decision result=evaluator.evaluate(rule);assertEquals("UNDETERMINED",result.status());assertEquals("SHARED_SOURCE_REVIEW_REQUIRED",result.gaps().get(0).code());
        assertEquals(1,snapshot(recovery,corpus,List.of(rule),evaluator).size());
    }
    @Test void aSerializedClosureCannotSkipFreshCounterevidenceReview(){
        Rule rule=one();EvidenceCorpus original=data.corpus("本项目专职安全员：张甲");Decision prior=evaluate(original,roles(original),rule);assertEquals("PASS",prior.status());
        EvidenceCorpus changed=data.corpus("本项目专职安全员：张甲","本项目专职安全员张甲已撤回。");Recovery recovery=recovery(changed,List.of(rule),"SHARED_SOURCE_REVIEW_REQUIRED",Map.of());
        Decision guarded=RecoveryCoverageDependencies.guard(rule,prior,new RuleEvaluator(roles(changed),changed,recovery.context()));
        assertEquals("UNDETERMINED",guarded.status());assertEquals("SHARED_SOURCE_REVIEW_REQUIRED",guarded.gaps().get(0).code());
    }
    @Test void anotherObservationScopeCannotUseAnOldLocalReceipt(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");Recovery recovery=recovery(corpus,List.of(rule),"SHARED_SOURCE_REVIEW_REQUIRED",Map.of());
        Decision prior=evaluate(corpus,roles(corpus),rule);Rule wide=new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),Map.of("requiresFullDocument",true),rule.sourceQuote(),rule.logic(),rule.children());
        Decision guarded=RecoveryCoverageDependencies.guard(wide,prior,new RuleEvaluator(roles(corpus),corpus,recovery.context()));assertEquals("UNDETERMINED",guarded.status());
    }
}

package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.facts.FieldCoverage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the recovery dependency gate after the normal evaluator coverage gate. */
class RecoverySourceReviewClosureTest {
    final PersonnelCountRoleTest data=new PersonnelCountRoleTest();
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Recovery{
        private final Map<String,Object> context;
        private final SharedFactReuse shared;
        private final RecoveryCoverageDependencies dependencies;
        @com.fasterxml.jackson.annotation.JsonCreator
        Recovery(@com.fasterxml.jackson.annotation.JsonProperty("context") Map<String,Object> context, @com.fasterxml.jackson.annotation.JsonProperty("shared") SharedFactReuse shared, @com.fasterxml.jackson.annotation.JsonProperty("dependencies") RecoveryCoverageDependencies dependencies) { this.context = context; this.shared = shared; this.dependencies = dependencies; }
        public Map<String,Object> context() { return context; }
        public SharedFactReuse shared() { return shared; }
        public RecoveryCoverageDependencies dependencies() { return dependencies; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Recovery that = (Recovery) other; return java.util.Objects.equals(this.context, that.context) && java.util.Objects.equals(this.shared, that.shared) && java.util.Objects.equals(this.dependencies, that.dependencies); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.context); hash = 31 * hash + java.util.Objects.hashCode(this.shared); hash = 31 * hash + java.util.Objects.hashCode(this.dependencies); return hash; }
        @Override public String toString() { return "Recovery[" + "context=" + context + ", shared=" + shared + ", dependencies=" + dependencies + "]"; }
}
    private Rule one(){return data.count("专职安全员","","1","专职安全员1人。",cn.bidcheck.compat.Java8Api.mapOf());}
    private List<Fact> roles(EvidenceCorpus corpus){return data.checked(corpus,data.role(corpus,1,"专职安全员","张甲","",""));}
    private Decision evaluate(EvidenceCorpus corpus,List<Fact> facts,Rule rule){
        RuleEvaluator evaluator=new RuleEvaluator(facts,corpus,cn.bidcheck.compat.Java8Api.mapOf());evaluator.incompleteSourceReview.add(rule.id());return evaluator.evaluate(rule);
    }
    private Map<String,Object> dependency(Rule rule,String code,EvidenceCorpus corpus){
        return linked("obligationId",rule.id(),"observationKey",FieldCoverage.key(SharedFactReuse.observationTarget(rule)),"gapCode",code,
            "sourceIds",cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().map(Source::id)),"businessProof",false);
    }
    private Recovery recovery(EvidenceCorpus corpus,List<Rule> rules,String code,Map<String,Object> extraPrevious){
        Map<String,Object> previous=new LinkedHashMap<>(extraPrevious);previous.put("coverageDependencies",cn.bidcheck.compat.Java8Api.toList(rules.stream().map(r->dependency(r,code,corpus))));
        Map<String,Object> context=new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("recoveryStrategy", "TARGETED_EVIDENCE", "previousAtomicCheck", previous));
        SharedFactReuse shared=new SharedFactReuse(corpus,context,"test-model","test-engine");
        RecoveryCoverageDependencies dependencies=new RecoveryCoverageDependencies(context,rules,rules,corpus.all,"test-model");dependencies.apply(context,shared);
        return new Recovery(context,shared,dependencies);
    }
    private List<Map<String,Object>> snapshot(Recovery recovery,EvidenceCorpus corpus,List<Rule> rules,RuleEvaluator evaluator){
        return recovery.dependencies().snapshot(recovery.context(),rules,evaluator.decisions,recovery.shared(),corpus.all);
    }
    @Test void localExistenceClosesBothGatesAndIsNotPersistedAsUnfinishedAgain(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲","本项目资料员：李乙");
        Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule),"SHARED_SOURCE_REVIEW_REQUIRED",cn.bidcheck.compat.Java8Api.mapOf());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());evaluator.incompleteSourceReview.add(rule.id());
        Decision result=evaluator.evaluate(rule);assertEquals("PASS",result.status(),result.toString());assertTrue(snapshot(recovery,corpus,cn.bidcheck.compat.Java8Api.listOf(rule),evaluator).isEmpty());
        // apply may run again after another shared read. It must revalidate, not resurrect
        // an eternal persisted blocker or rely on an old serialized PASS.
        recovery.dependencies().apply(recovery.context(),recovery.shared());
        RuleEvaluator refreshed=new RuleEvaluator(roles(corpus),corpus,recovery.context());assertEquals("PASS",refreshed.evaluate(rule).status());
        assertTrue(snapshot(recovery,corpus,cn.bidcheck.compat.Java8Api.listOf(rule),refreshed).isEmpty());
    }
    @Test void anInheritedDependencyAloneRunsTheSameCurrentSourceProof(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");
        Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule),"SHARED_SOURCE_REVIEW_REQUIRED",cn.bidcheck.compat.Java8Api.mapOf());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());
        Decision result=evaluator.evaluate(rule);assertEquals("PASS",result.status());assertTrue(result.proof().containsKey("sourceReviewClosure"));
    }
    @Test void onlyTheExactClosedObligationIsRemovedFromSnapshot(){
        Rule rule=one(),other=new Rule("other","资料员人数","PERSON_COUNT","资料员","","","1","",cn.bidcheck.compat.Java8Api.mapOf(),"资料员1人","",cn.bidcheck.compat.Java8Api.listOf());
        EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule, other),"SHARED_SOURCE_REVIEW_REQUIRED",cn.bidcheck.compat.Java8Api.mapOf());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());assertEquals("PASS",evaluator.evaluate(rule).status());
        List<Map<String,Object>> pending=snapshot(recovery,corpus,cn.bidcheck.compat.Java8Api.listOf(rule, other),evaluator);assertEquals(1,pending.size());assertEquals("other",pending.get(0).get("obligationId"));
    }
    @Test void unreadSharedFactLookupCannotBeClosedByLocalExistence(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule),"EVIDENCE_LOOKUP_INCOMPLETE",cn.bidcheck.compat.Java8Api.mapOf());
        RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());evaluator.incompleteSourceReview.add(rule.id());
        Decision result=evaluator.evaluate(rule);assertEquals("UNDETERMINED",result.status());assertEquals("EVIDENCE_LOOKUP_INCOMPLETE",result.gaps().get(0).code());
        assertEquals(1,snapshot(recovery,corpus,cn.bidcheck.compat.Java8Api.listOf(rule),evaluator).size());
    }
    @Test void aLaterSourceReviewGapCannotOverwriteTheStrongerLookupDependency(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");
        Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule),"EVIDENCE_LOOKUP_INCOMPLETE",cn.bidcheck.compat.Java8Api.mapOf("conditions", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("id", rule.id(), "gaps", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "SHARED_SOURCE_REVIEW_REQUIRED"))))));
        Decision result=new RuleEvaluator(roles(corpus),corpus,recovery.context()).evaluate(rule);
        assertEquals("UNDETERMINED",result.status());assertEquals("EVIDENCE_LOOKUP_INCOMPLETE",result.gaps().get(0).code());
    }
    @Test void sameProjectRevocationKeepsInheritedDependencyPending(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲","本项目专职安全员张甲已撤回。");
        Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule),"SHARED_SOURCE_REVIEW_REQUIRED",cn.bidcheck.compat.Java8Api.mapOf());RuleEvaluator evaluator=new RuleEvaluator(roles(corpus),corpus,recovery.context());
        Decision result=evaluator.evaluate(rule);assertEquals("UNDETERMINED",result.status());assertEquals("SHARED_SOURCE_REVIEW_REQUIRED",result.gaps().get(0).code());
        assertEquals(1,snapshot(recovery,corpus,cn.bidcheck.compat.Java8Api.listOf(rule),evaluator).size());
    }
    @Test void aSerializedClosureCannotSkipFreshCounterevidenceReview(){
        Rule rule=one();EvidenceCorpus original=data.corpus("本项目专职安全员：张甲");Decision prior=evaluate(original,roles(original),rule);assertEquals("PASS",prior.status());
        EvidenceCorpus changed=data.corpus("本项目专职安全员：张甲","本项目专职安全员张甲已撤回。");Recovery recovery=recovery(changed,cn.bidcheck.compat.Java8Api.listOf(rule),"SHARED_SOURCE_REVIEW_REQUIRED",cn.bidcheck.compat.Java8Api.mapOf());
        Decision guarded=RecoveryCoverageDependencies.guard(rule,prior,new RuleEvaluator(roles(changed),changed,recovery.context()));
        assertEquals("UNDETERMINED",guarded.status());assertEquals("SHARED_SOURCE_REVIEW_REQUIRED",guarded.gaps().get(0).code());
    }
    @Test void anotherObservationScopeCannotUseAnOldLocalReceipt(){
        Rule rule=one();EvidenceCorpus corpus=data.corpus("本项目专职安全员：张甲");Recovery recovery=recovery(corpus,cn.bidcheck.compat.Java8Api.listOf(rule),"SHARED_SOURCE_REVIEW_REQUIRED",cn.bidcheck.compat.Java8Api.mapOf());
        Decision prior=evaluate(corpus,roles(corpus),rule);Rule wide=new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),cn.bidcheck.compat.Java8Api.mapOf("requiresFullDocument", true),rule.sourceQuote(),rule.logic(),rule.children());
        Decision guarded=RecoveryCoverageDependencies.guard(wide,prior,new RuleEvaluator(roles(corpus),corpus,recovery.context()));assertEquals("UNDETERMINED",guarded.status());
    }
}

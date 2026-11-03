package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class InlineQualificationEvaluationTest {
    private static final String COMPANY="华北建设有限公司";
    private static final String CATEGORY="建筑装修装饰工程专业承包";
    private static final String CHOICE="2.2.2施工单位资质要求：具有住建主管部门颁发的建筑装修装饰工程专业承包贰级（含）以上资质或根据《建设工程企业资质管理制度改革方案通知》的规定已换发新证取得相应资质的投标人";
    private static final String LICENSE="且具有住建主管部门颁发的有效的安全生产许可证。";
    private static final String PROOF="须提交有效证明文件(并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致)。";
    private Plan plan(String source)throws Exception{
        Checkpoint cp=new Checkpoint("inline","施工资质","资格","HIGH","SEMANTIC_RESPONSE","qualification","",source,"",true,true,true,new Evidence("t","招标",1,"BLOCK",source,"p1",1));
        Plan plan=new RequirementCompiler().compile(cp,new RunSession(ModelGateway.disabled(),cn.bidcheck.compat.Java8Api.mapOf("originalRequirement", source, "focusedCheck", true),"inline-test"));
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());return plan;
    }
    @Test void sourceChoiceMandatoryLicenseAndProofRemainSeparate(){
        cn.bidcheck.verification.engine.QualificationRequirementTree.Tree tree=QualificationRequirementTree.parse(CHOICE+";"+LICENSE+PROOF).orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("施工单位",tree.subject());assertEquals(CHOICE,tree.choiceQuote());assertEquals(2,tree.branches().size());
        assertEquals(CATEGORY,tree.branches().get(0).category());assertEquals("贰级",tree.branches().get(0).grade());assertEquals(">=",tree.branches().get(0).gradeOperator());
        assertEquals("QUALIFICATION_REPLACEMENT_MAPPING_REQUIRED",tree.branches().get(1).unresolved());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(LICENSE),tree.mandatoryQuotes());assertEquals(PROOF,tree.proofQuote());
    }
    @Test void cannotDiscardExtraConjunctionsOrInventAReplacementPolicy(){
        assertTrue((!QualificationRequirementTree.parse(CHOICE+";且必须提供项目业绩。"+PROOF).isPresent()));
        assertTrue((!QualificationRequirementTree.parse(CHOICE.replace("已换发新证取得相应资质","按项目组自行决定资质")+";"+LICENSE).isPresent()));
        assertTrue((!QualificationRequirementTree.parse(CHOICE+";"+LICENSE+PROOF+"还须提供近三年财务报告。").isPresent()));
    }
    @Test void verifiedOrdinaryRoutePassesChoiceWhileUnknownReplacementRemainsUnknown()throws Exception{
        Plan plan=plan(CHOICE);cn.bidcheck.verification.engine.InlineQualificationEvaluationTest.Fixture fixture=fixture("壹级");Rule choice=choice(plan);
        RuleEvaluator evaluator=new RuleEvaluator(fixture.facts(),fixture.corpus(),cn.bidcheck.compat.Java8Api.mapOf());Decision result=evaluator.evaluate(choice);
        assertEquals("PASS",result.status(),result.toString());
        assertTrue(choice.children().stream().filter(r->r.predicate().equals("UNSUPPORTED")).allMatch(r->evaluator.decisions.get(r.id()).status().equals("UNDETERMINED")));
    }
    @Test void ordinaryQualificationCannotBypassSafetyLicenseOrCommonProof()throws Exception{
        Plan plan=plan(CHOICE+";"+LICENSE+PROOF);cn.bidcheck.verification.engine.InlineQualificationEvaluationTest.Fixture fixture=fixture("壹级");RuleEvaluator evaluator=new RuleEvaluator(fixture.facts(),fixture.corpus(),cn.bidcheck.compat.Java8Api.mapOf());
        Decision result=RuleEvaluator.combine("cp","资格","ALL",cn.bidcheck.compat.Java8Api.toList(plan.rules().stream().map(evaluator::evaluate)));
        assertEquals("PASS",evaluator.decisions.get(choice(plan).id()).status());assertEquals("UNDETERMINED",result.status());
        List<Rule> leaves=RequirementCompiler.leaves(plan.rules());
        assertTrue(leaves.stream().anyMatch(r->r.predicate().equals("CERTIFICATE")&&str(r.parameters(),"category").equals("安全生产许可证")));
        assertTrue(leaves.stream().anyMatch(r->r.predicate().equals("VALIDITY")&&str(r.parameters(),"category").equals("安全生产许可证")));
        assertTrue(leaves.stream().anyMatch(r->r.predicate().equals("SAME_FIELD")&&r.sourceQuote().equals(PROOF)));
    }
    @Test void aFailedOrdinaryRouteCannotTurnUnknownPolicyIntoOverallFailure()throws Exception{
        Plan plan=plan(CHOICE);cn.bidcheck.verification.engine.InlineQualificationEvaluationTest.Fixture fixture=fixture("叁级");Rule choice=choice(plan);RuleEvaluator evaluator=new RuleEvaluator(fixture.facts(),fixture.corpus(),cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("UNDETERMINED",evaluator.evaluate(choice).status());
        Rule ordinary=choice.children().stream().filter(r->r.predicate().equals("CERTIFICATE")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertEquals("FAIL",evaluator.decisions.get(ordinary.id()).status());
    }
    private Rule choice(Plan plan){return CompilationRecovery.flatten(plan.rules()).stream().filter(r->r.logic().equals("ANY")).findFirst().orElseThrow(java.util.NoSuchElementException::new);}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Fixture {
        private final EvidenceCorpus corpus;
        private final List<Fact> facts;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Fixture(@com.fasterxml.jackson.annotation.JsonProperty("corpus") EvidenceCorpus corpus, @com.fasterxml.jackson.annotation.JsonProperty("facts") List<Fact> facts) { this.corpus = corpus; this.facts = facts; }
        public EvidenceCorpus corpus() { return corpus; }
        public List<Fact> facts() { return facts; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Fixture that = (Fixture) other; return java.util.Objects.equals(this.corpus, that.corpus) && java.util.Objects.equals(this.facts, that.facts); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.corpus); hash = 31 * hash + java.util.Objects.hashCode(this.facts); return hash; }
        @Override public String toString() { return "Fixture[" + "corpus=" + corpus + ", facts=" + facts + "]"; }
}
    private Fixture fixture(String grade){
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(new Page(1,"本项目联合体协议书\n施工单位："+COMPANY,"PAGE","NATIVE",""), new Page(2,"本项目资格证明\n企业名称："+COMPANY+"\n资质类别："+CATEGORY+"\n等级："+grade+"\n证书编号：D10001","PAGE","NATIVE",""));
        EvidenceCorpus corpus=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("b","p","BID","合成投标",1,"hash","",pages,cn.bidcheck.compat.Java8Api.listOf())));
        String one=corpus.all.stream().filter(s->s.evidence().page()==1).findFirst().orElseThrow(java.util.NoSuchElementException::new).id(),two=corpus.all.stream().filter(s->s.evidence().page()==2).findFirst().orElseThrow(java.util.NoSuchElementException::new).id();
        List<Fact> raw=cn.bidcheck.compat.Java8Api.listOf(new Fact("role","RELATION","施工单位","：",COMPANY,COMPANY,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(one),cn.bidcheck.compat.Java8Api.mapOf("object", COMPANY)), new Fact("certificate","CERTIFICATE",COMPANY,"certificate",CATEGORY,CATEGORY,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(two),cn.bidcheck.compat.Java8Api.mapOf("category", CATEGORY, "grade", grade, "number", "D10001", "numberField", "证书编号")));
        cn.bidcheck.verification.engine.ProofVerifier.Verified checked=new ProofVerifier().verify(new ObjectMapper().valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", raw)),corpus.all);
        assertTrue(checked.rejected().isEmpty(),checked.rejected().toString());return new Fixture(corpus,checked.facts());
    }
}

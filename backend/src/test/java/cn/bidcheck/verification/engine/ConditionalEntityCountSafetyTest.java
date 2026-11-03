package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.domain.Domain.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent safety review: real source verifier/compiler/evaluator, synthetic documents, no model. */
class ConditionalEntityCountSafetyTest {
    private final ObjectMapper json=new ObjectMapper();
    private EvidenceCorpus corpus(String text){return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("d","p","BID","fixture.pdf",1,"sha","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf())));}
    private Map<String,Object> node(String id,String predicate,String subject,String quote){return linked("id",id,"predicate",predicate,"subject",subject,"description",quote,"sourceQuote",quote,"parameters",cn.bidcheck.compat.Java8Api.mapOf(),"children",cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> conditional(String source,String condition,String required,String otherwise){return linked("id","parent","predicate","GROUP","sourceQuote",source,"logic","CONDITIONAL",
        "children",cn.bidcheck.compat.Java8Api.listOf(node("condition","MATERIAL","",condition), node("required","MATERIAL","",required), node("inactive","NOT_APPLICABLE","",otherwise)));}
    private Map<String,Object> proof(String source,String condition,String required,String otherwise){return ConditionalRequirement.inactiveBranch(json.valueToTree(conditional(source,condition,required,otherwise)),2,source);}
    private Rule inactive(String quote,Map<String,Object> proof){return new Rule("inactive",quote,"NOT_APPLICABLE","","","","","",cn.bidcheck.compat.Java8Api.mapOf("inactiveBranchProof", proof),quote,"",cn.bidcheck.compat.Java8Api.listOf());}
    private Plan compile(String source,String type,Map<String,Object> rule){
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint("cp","数量或提交义务","资格","HIGH",type,"memberCount","<=",source,"家",true,true,true,null);
        return new RequirementCompiler().compileResponse(cp,json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("logic", "ALL", "rules", cn.bidcheck.compat.Java8Api.listOf(rule))),source,true,cn.bidcheck.compat.Java8Api.setOf("PERSONNEL_CONSORTIUM"));
    }
    private Rule countRule(String source,String operator,String expected){return new Rule("count",source,"ENTITY_COUNT","联合体成员","memberCount",operator,expected,"家",cn.bidcheck.compat.Java8Api.mapOf(),source,"",cn.bidcheck.compat.Java8Api.listOf());}
    private List<Fact> members(EvidenceCorpus c,String...names){
        List<Fact> raw=new ArrayList<>();for(String name:names)raw.add(new Fact("member:"+name,"RELATION","联合体成员","：",name,name,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf("object", name)));
        cn.bidcheck.verification.engine.ProofVerifier.Verified checked=new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", raw)),c.all);
        assertTrue(checked.rejected().isEmpty(),checked.rejected().toString());return checked.facts();
    }
    private Decision count(EvidenceCorpus c,List<Fact> facts){return new RuleEvaluator(facts,c,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(countRule("联合体成员不得超过2家。","<=","2"));}

    @Test void explicitFalseBranchSubmissionCannotBecomeNotApplicable(){
        String source="若联合体投标，应提交联合体协议；非联合体投标人应提交独立投标声明。";
        assertTrue(proof(source,"若联合体投标","应提交联合体协议","非联合体投标人应提交独立投标声明").isEmpty(),"False branch has its own explicit obligation");
    }
    @Test void unconditionalSubmissionAfterConditionalClauseCannotDisappear(){
        String source="若联合体投标，应提交联合体协议；投标人均应提交营业执照。";
        assertTrue(proof(source,"若联合体投标","应提交联合体协议","投标人均应提交营业执照").isEmpty(),"An independent obligation survives a false condition");
    }
    @Test void onlyThenBranchCannotMintAFalseBranchProof(){
        String source="若联合体投标，应提交联合体协议。";com.fasterxml.jackson.databind.JsonNode parent=json.valueToTree(conditional(source,"若联合体投标","应提交联合体协议",source));
        assertTrue(ConditionalRequirement.inactiveBranch(parent,1,source).isEmpty());
        assertTrue(ConditionalRequirement.inactiveBranch(parent,2,"无此条款").isEmpty());
    }
    @Test void sourceProofAloneCannotBeExecutedWithoutAProvenFalseCondition(){
        String source="若联合体投标，应提交联合体协议。";java.util.Map<java.lang.String, java.lang.Object> p=proof(source,"若联合体投标","应提交联合体协议",source);
        assertFalse(p.isEmpty(),"A source restricted conditional may have an inactive path");
        EvidenceCorpus c=corpus("当前投标方式尚不明确");RuleEvaluator evaluator=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),c,cn.bidcheck.compat.Java8Api.mapOf());
        assertEquals("UNDETERMINED",evaluator.evaluate(inactive(source,p)).status());
        evaluator.decisions.put("condition",new Decision("condition","条件","UNDETERMINED","未识别",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).evidence()),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("UNDETERMINED",evaluator.evaluate(inactive(source,p)).status());
        evaluator.decisions.put("condition",new Decision("condition","条件","PASS","已成立",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).evidence()),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("UNDETERMINED",evaluator.evaluate(inactive(source,p)).status());
        evaluator.decisions.put("condition",new Decision("condition","条件","FAIL","无证据的反面结论",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("UNDETERMINED",evaluator.evaluate(inactive(source,p)).status());
    }
    @Test void provenFalseConditionAndRestrictedSourceAllowInactivePath(){
        String source="若联合体投标，应提交联合体协议。";java.util.Map<java.lang.String, java.lang.Object> p=proof(source,"若联合体投标","应提交联合体协议",source);
        EvidenceCorpus c=corpus("投标方式：独立投标");RuleEvaluator evaluator=new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),c,cn.bidcheck.compat.Java8Api.mapOf());
        evaluator.decisions.put("condition",new Decision("condition","是否联合体投标","FAIL","原文明确独立投标",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).evidence()),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals("NOT_APPLICABLE",evaluator.evaluate(inactive(source,p)).status());
    }
    @Test void topLevelNoopIsRejectedEvenWithForgedInactiveProof(){
        String source="投标人应提交营业执照。";java.util.Map<java.lang.String, java.lang.Object> n=node("r","NOOP","投标人",source);
        n.put("parameters",cn.bidcheck.compat.Java8Api.mapOf("inactiveBranchProof", cn.bidcheck.compat.Java8Api.mapOf("version", "source-conditional-1", "conditionId", "x", "selectedWhen", "FAIL", "branchIndex", 2, "sourceQuote", source)));
        cn.bidcheck.verification.engine.VerificationTypes.Plan plan=compile(source,"SEMANTIC_RESPONSE",n);assertTrue(plan.rules().stream().allMatch(r->r.predicate().equals("UNSUPPORTED")));
    }
    @Test void partialListAndACompleteStatementMissingOneMemberCannotPass(){
        for(String composition:cn.bidcheck.compat.Java8Api.listOf("", "由甲公司、乙公司、丙公司组成联合体。")){
            EvidenceCorpus c=corpus("本项目联合体协议书\n"+composition+"\n联合体成员：甲公司\n联合体成员：乙公司");
            assertEquals("UNDETERMINED",count(c,members(c,"甲公司","乙公司")).status());
        }
    }
    @Test void explicitCompleteTwoMembersPassAndThreeMembersFail(){
        for(int total:cn.bidcheck.compat.Java8Api.listOf(2, 3)){
            String[] names=total==2?new String[]{"甲公司","乙公司"}:new String[]{"甲公司","乙公司","丙公司"};
            String text="本项目联合体协议书\n由"+String.join("、",names)+"组成联合体。\n"+String.join("\n",cn.bidcheck.compat.Java8Api.toList(Arrays.stream(names).map(n->"联合体成员："+n)));
            EvidenceCorpus c=corpus(text);assertEquals(total==2?"PASS":"FAIL",count(c,members(c,names)).status());
        }
    }
    @Test void allMemberNamesFormIsAcceptedOnlyWithEachSourceBoundRole(){
        EvidenceCorpus c=corpus("本项目联合体协议书\n甲公司、乙公司[所有成员单位名称]自愿组成：联合体。\n联合体成员：甲公司\n联合体成员：乙公司");
        assertEquals("PASS",count(c,members(c,"甲公司","乙公司")).status());
        assertEquals("UNDETERMINED",count(c,members(c,"甲公司")).status());
    }
    @Test void aConflictingCompleteCompositionCannotBeIgnoredByPartialExtraction(){
        EvidenceCorpus c=corpus("本项目联合体协议书\n由甲公司、乙公司组成联合体。\n由甲公司、乙公司、丙公司组成联合体。\n联合体成员：甲公司\n联合体成员：乙公司");
        assertNotEquals("PASS",count(c,members(c,"甲公司","乙公司")).status(),"Complete set is not whatever subset the extractor supplied");
    }
    @Test void differentLotCompositionsDoNotSupplyAGenericCurrentMemberCount(){
        EvidenceCorpus c=corpus("本项目联合体协议书\n第一标段由甲公司、乙公司组成联合体。\n第二标段由甲公司、乙公司、丙公司组成联合体。\n联合体成员：甲公司\n联合体成员：乙公司");
        assertNotEquals("PASS",count(c,members(c,"甲公司","乙公司")).status());
    }
    @Test void entityCountNormalizationPreservesSourceThresholdAndNotMoney(){
        String source="联合体成员不得超过2家。";Rule input=new Rule("r",source,"NUMBER","联合体成员","memberCount",">=","99","人",cn.bidcheck.compat.Java8Api.mapOf(),source,"",cn.bidcheck.compat.Java8Api.listOf());
        Rule normalized=EntityCountRequirement.normalize(input);assertEquals("ENTITY_COUNT",normalized.predicate());assertEquals("2",normalized.expected());assertEquals("<=",normalized.operator());assertEquals("家",normalized.unit());
        Rule money=new Rule("p","报价不超过2万元","NUMBER","投标人","price","<=","2","万元",cn.bidcheck.compat.Java8Api.mapOf(),"报价不超过2万元","",cn.bidcheck.compat.Java8Api.listOf());
        assertEquals(money,EntityCountRequirement.normalize(money));
    }
    @Test void valueCompareOrganizationCountMayKeepItsEquivalentTypedPredicate(){
        String source="联合体成员不得超过2家。";java.util.Map<java.lang.String, java.lang.Object> n=node("r","NUMBER","联合体成员",source);n.putAll(cn.bidcheck.compat.Java8Api.mapOf("field", "memberCount", "operator", "<=", "expected", "2", "unit", "家"));
        Plan plan=compile(source,"VALUE_COMPARE",n);assertEquals("ENTITY_COUNT",plan.rules().get(0).predicate());
        assertTrue(plan.issues().stream().noneMatch(x->x.contains("业务谓词未保留")),plan.issues().toString());
    }
    @Test void aNeighboringOrganizationCountCannotReplaceThePriceObligation(){
        String source="联合体成员不得超过2家；投标人投标总报价不得超过500万元。";
        Rule price=new Rule("price","投标报价最高限价","NUMBER","投标人","price","<=","500","万元",cn.bidcheck.compat.Java8Api.mapOf("amountBasis", "投标总报价"),source,"",cn.bidcheck.compat.Java8Api.listOf());
        Rule normalized=EntityCountRequirement.normalize(price);
        assertEquals("NUMBER",normalized.predicate());assertEquals("投标人",normalized.subject());assertEquals("500",normalized.expected());assertEquals("万元",normalized.unit());
    }
    @Test void anyAlternativeCannotBorrowAnotherCompanyOrLot(){
        for(String source:cn.bidcheck.compat.Java8Api.listOf("施工单位要求：提交甲证书；设计单位要求：提交乙证书。", "施工单位要求：第一标段提交甲证书或第二标段提交乙证书。")){
            java.util.Map<java.lang.String, java.lang.Object> a=node("a","MATERIAL","施工单位","甲证书");java.util.Map<java.lang.String, java.lang.Object> b=node("b","MATERIAL","施工单位","乙证书");
            java.util.Map<java.lang.String, java.lang.Object> parent=node("parent","GROUP","施工单位",source);parent.put("logic","ANY");parent.put("children",cn.bidcheck.compat.Java8Api.listOf(a, b));
            Plan plan=compile(source,"SEMANTIC_RESPONSE",parent);assertEquals("UNSUPPORTED",plan.rules().get(0).children().get(1).predicate());
        }
    }
    @Test void anyAlternativeMayInheritOnlyOneExplicitCommonActor(){
        String source="施工单位要求：须提交甲证书或乙证书。";
        java.util.Map<java.lang.String, java.lang.Object> parent=node("parent","GROUP","施工单位",source);parent.put("logic","ANY");parent.put("children",cn.bidcheck.compat.Java8Api.listOf(node("a","MATERIAL","施工单位","甲证书"), node("b","MATERIAL","施工单位","乙证书")));
        Plan plan=compile(source,"SEMANTIC_RESPONSE",parent);assertTrue(plan.rules().get(0).children().stream().allMatch(r->r.subject().equals("施工单位")&&r.predicate().equals("MATERIAL")));
    }
}

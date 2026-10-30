package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual scoped fact validation and business evaluation, not merely parser shape. */
class QualificationRequirementEvaluationTest {
    private static final String COMPANY="华北设计有限公司";
    private static final String DESIGN="设计单位资质要求：须满足下列条件之一：①住建主管部门颁发的工程设计综合资质甲级。②住建主管部门颁发的工程设计建筑行业乙级（含）以上资质或工程设计建筑工程专业乙级（含）以上资质或建筑装饰工程设计专项丙级（含）以上资质。或根据《资质管理制度改革方案通知》的规定已换发新证取得相应资质的投标人。";
    private static final String PROOF="须提交有效证明文件（并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致）。";
    private final ObjectMapper json=new ObjectMapper();
    private static final ModelGateway OFFLINE=new ModelGateway(){public boolean enabled(){return false;}public String identity(){return "offline";}public JsonNode request(String op,Map<String,Object> in){throw new AssertionError("no model permitted");}};
    private Plan plan(String text)throws Exception{
        Checkpoint cp=new Checkpoint("q","设计资质","资格","HIGH","SEMANTIC_RESPONSE","qualification","",text,"",true,true,true,new Evidence("t","招标",1,"BLOCK",text,"p1",1));
        Plan plan=new RequirementCompiler().compile(cp,new RunSession(OFFLINE,Map.of("originalRequirement",text,"focusedCheck",true),"qualification-evaluation"));
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());return plan;
    }
    private record Fixture(EvidenceCorpus corpus,List<Fact> facts){}
    private Fixture fixture(boolean historicalRole,Map<String,String> certificateGrades,String... queryPages){
        List<Page> pages=new ArrayList<>();pages.add(new Page(1,(historicalRole?"历史项目联合体协议书":"本项目联合体协议书")+"\n设计单位："+COMPANY,"PAGE","NATIVE",""));
        int page=2;for(var entry:certificateGrades.entrySet())pages.add(new Page(page++,"本项目资格证明\n"+entry.getKey()+"\n企业名称："+COMPANY+"\n证书编号：D"+page+"001\n等级："+entry.getValue()+"\n证书有效期：2024年01月01日至2027年01月01日","PAGE","NATIVE",""));
        for(String query:queryPages)pages.add(new Page(page++,query,"PAGE","NATIVE",""));
        EvidenceCorpus corpus=new EvidenceCorpus(List.of(new Document("bid","p","BID","合成投标文件",1,"hash","",pages,List.of())));
        List<Fact> facts=new ArrayList<>();Source role=source(corpus,1);
        facts.add(new Fact("role","RELATION","设计单位","：",COMPANY,COMPANY,"","",historicalRole?"HISTORICAL_PROJECT":"CURRENT_PROJECT",List.of(role.id()),Map.of("object",COMPANY)));
        page=2;for(var entry:certificateGrades.entrySet()){
            String number="D"+(page+1)+"001";
            facts.add(new Fact("cert-"+page,"CERTIFICATE",COMPANY,"certificate",entry.getKey(),entry.getKey(),"","","CURRENT_PROJECT",List.of(source(corpus,page).id()),Map.of("category",entry.getKey(),"grade",entry.getValue(),"number",number,"numberField","证书编号","validityField","证书有效期","start","2024-01-01","end","2027-01-01")));page++;
        }
        var verified=new ProofVerifier().verify(json.valueToTree(Map.of("facts",facts)),corpus.all);
        assertTrue(verified.rejected().isEmpty(),verified.rejected().toString());assertEquals(facts.size(),verified.facts().size());
        return new Fixture(corpus,verified.facts());
    }
    private Source source(EvidenceCorpus corpus,int page){return corpus.all.stream().filter(s->s.evidence().page()==page).findFirst().orElseThrow();}
    private Rule choice(Plan plan){return all(plan.rules()).stream().filter(r->r.logic().equals("ANY")).findFirst().orElseThrow();}
    private List<Rule> all(List<Rule> roots){List<Rule> out=new ArrayList<>();for(Rule r:roots){out.add(r);out.addAll(all(r.children()));}return out;}
    private Decision evaluate(Plan plan,RuleEvaluator evaluator){return RuleEvaluator.combine("root","资质检查",plan.logic(),plan.rules().stream().map(evaluator::evaluate).toList());}
    private Map<String,Object> deadline(String date){return linked("referenceDate",date,"referenceDateSourceVerified",true,"referenceDateEvidence",Map.of("documentId","t","quote","投标截止日期："+date,"confirmation","SOURCE_UNAMBIGUOUS","confirmed",true));}

    @Test void oneCompleteHigherGradeAlternativePassesWithoutProvingOtherOptions()throws Exception{
        Plan plan=plan(DESIGN);Fixture data=fixture(false,Map.of("工程设计建筑行业","甲级"));
        RuleEvaluator evaluator=new RuleEvaluator(data.facts(),data.corpus(),Map.of());Decision result=evaluate(plan,evaluator);
        assertEquals("PASS",result.status(),result.toString());Rule choice=choice(plan);assertEquals(5,choice.children().size());
        assertEquals(1,choice.children().stream().filter(r->"PASS".equals(evaluator.decisions.get(r.id()).status())).count());
        assertTrue(choice.children().stream().filter(r->r.predicate().equals("UNSUPPORTED")).allMatch(r->"UNDETERMINED".equals(evaluator.decisions.get(r.id()).status())));
    }
    @Test void commonProofCannotBeSkippedEvenWhenQualificationAlternativePasses()throws Exception{
        Plan plan=plan(DESIGN+PROOF);Fixture data=fixture(false,Map.of("工程设计建筑行业","甲级"));
        RuleEvaluator evaluator=new RuleEvaluator(data.facts(),data.corpus(),Map.of());Decision result=evaluate(plan,evaluator);
        assertEquals("PASS",evaluator.decisions.get(choice(plan).id()).status());
        assertEquals("UNDETERMINED",result.status(),result.toString());assertFalse(result.gaps().isEmpty());
    }
    @Test void commonProofMustUseTheCertificateThatActuallySatisfiedQualification()throws Exception{
        Plan plan=plan(DESIGN+PROOF);Fixture data=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D9001","甲级",true));
        RuleEvaluator evaluator=new RuleEvaluator(data.facts(),data.corpus(),Map.of());Decision result=evaluate(plan,evaluator);
        assertEquals("PASS",evaluator.decisions.get(choice(plan).id()).status());
        assertEquals("UNDETERMINED",result.status(),result.toString());
        assertTrue(result.gaps().stream().anyMatch(g->g.code().equals("QUALIFICATION_QUERY_IDENTITY_REQUIRED")));
    }
    @Test void matchingCertificateQueryAndCurrentCompanyRelationCompleteTheProof()throws Exception{
        Plan plan=plan(DESIGN+PROOF);Fixture data=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D3001","甲级",true));
        RuleEvaluator evaluator=new RuleEvaluator(data.facts(),data.corpus(),deadline("2025-08-05"));Decision result=evaluate(plan,evaluator);
        assertEquals("PASS",result.status(),result.toString());
        assertEquals(1,evaluator.qualificationProofBundles.size(),"the four proof conditions reuse one source-bound bundle");
        assertTrue(result.proof().toString().contains("D3001"));assertTrue(result.proof().toString().contains("selectedQualificationRuleId"));
        assertTrue(result.evidences().stream().anyMatch(e->e.page()==2));assertTrue(result.evidences().stream().anyMatch(e->e.page()==3));
        assertTrue(result.proof().toString().contains("SELECTED_CERTIFICATE_VALIDITY_AT_TENDER_EVENT"));
    }
    @Test void commonValidProofCannotPassWithoutConfirmedDeadline()throws Exception{Plan plan=plan(DESIGN+PROOF);Fixture f=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D3001","甲级",true));Decision d=evaluate(plan,new RuleEvaluator(f.facts(),f.corpus(),Map.of()));assertEquals("UNDETERMINED",d.status());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("REFERENCE_TIME_UNCONFIRMED")));}
    @Test void completeQueryCannotHideCertificateExpiredAtDeadline()throws Exception{Plan plan=plan(DESIGN+PROOF);Fixture f=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D3001","甲级",true));Decision d=evaluate(plan,new RuleEvaluator(f.facts(),f.corpus(),deadline("2028-08-05")));assertEquals("FAIL",d.status());assertTrue(d.proof().toString().contains("2028-08-05"));}
    @Test void queryWithoutSourceBoundCertificateDatesCannotCloseValidity()throws Exception{Plan plan=plan(DESIGN+PROOF);Fixture f=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D3001","甲级",true));List<Fact> facts=new ArrayList<>();for(Fact fact:f.facts()){if(!fact.kind().equals("CERTIFICATE")){facts.add(fact);continue;}Map<String,Object> a=new LinkedHashMap<>(fact.attributes());a.remove("start");a.remove("end");facts.add(new Fact(fact.id(),fact.kind(),fact.subject(),fact.field(),fact.rawValue(),fact.value(),fact.unit(),fact.basis(),fact.scope(),fact.sourceIds(),a));}Decision d=evaluate(plan,new RuleEvaluator(facts,f.corpus(),deadline("2025-08-05")));assertEquals("UNDETERMINED",d.status());}
    @Test void sameCertificateNumberDoesNotHideDifferentGradeOrMissingQueryUrl()throws Exception{
        Plan plan=plan(DESIGN+PROOF);
        Fixture mismatch=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D3001","乙级",true));
        assertEquals("FAIL",evaluate(plan,new RuleEvaluator(mismatch.facts(),mismatch.corpus(),Map.of())).status());
        Fixture missingUrl=fixture(false,Map.of("工程设计建筑行业","甲级"),query("D3001","甲级",false));
        Decision incomplete=evaluate(plan,new RuleEvaluator(missingUrl.facts(),missingUrl.corpus(),Map.of()));
        assertEquals("UNDETERMINED",incomplete.status());assertTrue(incomplete.gaps().stream().anyMatch(g->g.code().equals("QUALIFICATION_QUERY_URL_REQUIRED")));
    }
    @Test void conflictingQueryCategoriesCannotBorrowASeparateRequirementHeading()throws Exception{
        String misleading=query("D3001","甲级",true).replace("资质类别：工程设计建筑行业","资质类别：工程设计市政行业\n资质类别：工程设计电力行业")+"\n本项目要求：\n工程设计建筑行业";
        Plan plan=plan(DESIGN+PROOF);Fixture data=fixture(false,Map.of("工程设计建筑行业","甲级"),misleading);
        assertTrue(data.corpus().all.stream().anyMatch(s->s.evidence().page()==3&&s.evidence().quote().contains(misleading)),"the complete mixed record must reach the original-source reader");
        RuleEvaluator evaluator=new RuleEvaluator(data.facts(),data.corpus(),Map.of());Decision result=evaluate(plan,evaluator);
        assertEquals("PASS",evaluator.decisions.get(choice(plan).id()).status());
        assertEquals("UNDETERMINED",result.status(),result.toString());
        assertTrue(result.gaps().stream().anyMatch(g->g.code().equals("QUALIFICATION_QUERY_IDENTITY_REQUIRED")));
    }
    private String query(String number,String grade,boolean url){return "本项目资质证明查询截图\n企业名称："+COMPANY+"\n资质类别：工程设计建筑行业\n证书编号："+number+"\n等级："+grade+(url?"\n查询网址：https://qualification.test/query":"");}
    @Test void allKnownRoutesFailButUnknownReplacementPreventsOverallDenial()throws Exception{
        Plan plan=plan(DESIGN.replace("专项丙级","专项乙级"));Map<String,String> grades=new LinkedHashMap<>();
        grades.put("工程设计综合资质","乙级");grades.put("工程设计建筑行业","丙级");grades.put("工程设计建筑工程专业","丙级");grades.put("建筑装饰工程设计专项","丙级");
        Fixture data=fixture(false,grades);RuleEvaluator evaluator=new RuleEvaluator(data.facts(),data.corpus(),Map.of());Decision result=evaluate(plan,evaluator);
        assertEquals("UNDETERMINED",result.status(),result.toString());
        // Check each explicit route with its own applicable credential. The ordinary evaluator
        // conservatively keeps a mixed-category candidate set unresolved on negative results.
        List<Decision> alternatives=new ArrayList<>();
        for(Rule branch:choice(plan).children()){
            if(branch.predicate().equals("UNSUPPORTED")){alternatives.add(evaluator.evaluate(branch));continue;}
            String category=str(branch.parameters(),"category");Fixture own=fixture(false,Map.of(category,grades.get(category)));
            Decision decision=new RuleEvaluator(own.facts(),own.corpus(),Map.of()).evaluate(branch);
            assertEquals("FAIL",decision.status(),decision.toString());alternatives.add(decision);
        }
        assertEquals("UNDETERMINED",RuleEvaluator.combine("choice","设计资质","ANY",alternatives).status());
    }
    @Test void higherGradeInAnotherCertificateCategoryNeverSatisfiesDesignQualification()throws Exception{
        Plan plan=plan(DESIGN);Fixture data=fixture(false,Map.of("公路工程施工总承包","一级"));
        assertEquals("UNDETERMINED",evaluate(plan,new RuleEvaluator(data.facts(),data.corpus(),Map.of())).status());
    }
    @Test void historicalDesignRoleCannotBindCurrentQualificationDespiteMatchingCompanyCertificate()throws Exception{
        Plan plan=plan(DESIGN);Fixture data=fixture(true,Map.of("工程设计建筑行业","甲级"));
        assertEquals(List.of(),DomainBindings.subjects("设计单位",data.facts(),data.corpus()));
        assertEquals("UNDETERMINED",evaluate(plan,new RuleEvaluator(data.facts(),data.corpus(),Map.of())).status());
    }
}

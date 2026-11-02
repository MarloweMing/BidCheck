package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

class PersonnelCredentialProofTest {
    private static final String PROOF="须提交有效证明文件（并提供查询网址及网址查询截图，查询截图信息应与证明文件信息一致）(注册类资质须已在本单位完成注册)。";
    private Plan compile(String source)throws Exception{Checkpoint cp=new Checkpoint("p","人员要求","资格","HIGH","SEMANTIC_RESPONSE","qualification","",source,"",true,true,true,new Evidence("t","招标文件",1,"BLOCK",source,"p1",1));return new RequirementCompiler().compile(cp,new RunSession(ModelGateway.disabled(),Map.of("originalRequirement",source,"focusedCheck",true),"personnel-source-proof"));}
    @Test void personnelSourceClausesCompileOnceWithoutPollutedCategories()throws Exception{
        for(String header:List.of("施工企业主要负责人资格要求：具有住建主管部门颁发的有效的安全生产考核合格证（A类）或建筑施工企业主要负责人安全生产考核合格证书。","项目负责人（可兼任施工部分负责人）资格要求：具有住建主管部门颁发的贰级含以上注册建造师执业资格证书（房屋建筑工程专业）。","施工部分负责人资格要求：具有住建主管部门颁发的贰级含以上注册建造师执业资格证书（房屋建筑工程专业）及住建主管部门颁发的有效期内的安全生产考核合格证（B证）或施工企业项目负责人安全生产考核合格证书。","专职安全员：具有住建主管部门颁发的有效的安全生产考核合格证（C类）或施工企业专职安全生产管理人员安全生产考核合格证书（C3类)。")){
            Plan plan=compile(header+PROOF);assertTrue(plan.issues().isEmpty(),plan.issues().toString());List<Rule> leaves=RequirementCompiler.leaves(plan.rules());
            List<Rule> certificates=leaves.stream().filter(r->r.predicate().equals("CERTIFICATE")).toList();assertEquals(header.contains("及住建")?2:1,certificates.size(),plan.toString());
            assertTrue(certificates.stream().allMatch(PersonnelCredentialProof::applies));assertTrue(leaves.stream().noneMatch(r->r.predicate().equals("UNSUPPORTED")));
            assertTrue(certificates.stream().noneMatch(r->str(r.parameters(),"category").matches(".*(?:资格要求|或|具有|C类).*")));
            assertEquals(certificates.size(),leaves.stream().filter(r->r.predicate().equals("VALIDITY")).count());
            Rule match=leaves.stream().filter(r->r.predicate().equals("SAME_FIELD")).findFirst().orElseThrow();assertEquals(header+PROOF,match.parameters().get("certificateRequirementQuote"));
        }
    }
    @Test void anExtraUnrecognisedCredentialRequirementIsNeverDropped()throws Exception{String q="项目负责人资格要求：具有住建主管部门颁发的贰级含以上注册建造师执业资格证书（房屋建筑工程专业）及特殊境外执照。"+PROOF;Plan plan=compile(q);assertTrue(RequirementCompiler.leaves(plan.rules()).stream().noneMatch(PersonnelCredentialProof::applies),"closed source compiler must not swallow unsupported suffix");}
    @Test void regularAndFinancialNumeralsKeepTypedGradeInProductionCompiler()throws Exception{for(String grade:List.of("二","贰")){String source="施工部分负责人资格要求：具有住建主管部门颁发的"+grade+"级含以上注册建造师执业资格证书（房屋建筑工程专业）及住建主管部门颁发的有效期内的安全生产考核合格证（B证）或施工企业项目负责人安全生产考核合格证书。"+PROOF;Plan plan=compile(source);assertTrue(plan.issues().isEmpty(),plan.issues().toString());List<Rule> certificates=RequirementCompiler.leaves(plan.rules()).stream().filter(r->r.predicate().equals("CERTIFICATE")).toList();assertEquals(2,certificates.size(),plan.toString());assertTrue(certificates.stream().allMatch(PersonnelCredentialProof::sourceGradeBound));Rule builder=certificates.stream().filter(r->"REGISTERED_BUILDER".equals(r.parameters().get("personnelCredentialKind"))).findFirst().orElseThrow();Map<String,Object> changed=new HashMap<>(builder.parameters());changed.put("requiredGrade","一级");Rule forged=new Rule(builder.id(),builder.description(),builder.predicate(),builder.subject(),builder.field(),builder.operator(),builder.expected(),builder.unit(),changed,builder.sourceQuote(),builder.logic(),builder.children());assertNotNull(RequirementGrounding.issue(forged));}}
    private Decision safety(String number,String period,boolean identity,String allowed){
        return safety(number,period,identity,allowed,"",Map.of());
    }
    private Decision safety(String number,String period,boolean identity,String allowed,String event,Map<String,Object> eventDates){
        String personId="110101198001010010",appointment="投标文件\n项目名称：北方中心装修工程\n本项目拟派李明担任施工负责人。"+(identity?"\n姓名：李明\n身份证号："+personId:"");
        String cert="建筑施工企业项目负责人安全生产考核合格证书\n姓名：李明\n证件号码："+personId+"\n企业名称：北方设计有限公司\n编号："+number+"\n"+period;
        var c=new EvidenceCorpus(List.of(new Document("b","p","BID","投标文件",1,"h","",List.of(new Page(1,appointment,"PAGE","TEXT",""),new Page(2,cert,"PAGE","TEXT","")),List.of())));Fact role=new Fact("role","PERSON_ROLE","施工负责人","姓名","李明","李明","","","CURRENT_PROJECT",List.of(c.all.get(0).id()),Map.of());
        String quote="施工负责人具有有效的安全生产考核合格证（"+allowed+"类）"+(event.equals("OPENING_DATE")?"，在开标日期有效":"");Rule rule=new Rule("c",quote,period.equals("CATEGORY")?"CERTIFICATE":"VALIDITY","施工负责人","certificate","","","",Map.of("personnelCredentialKind","SAFETY","category","安全生产考核合格证","allowedSafetyClasses",List.of(allowed),"referenceEvent",event),quote,"",List.of());
        Map<String,Object> context=linked("referenceDate","2025-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",Map.of("documentId","t","quote","投标截止日期：2025年8月5日","confirmation","SOURCE_UNAMBIGUOUS","confirmed",true));
        if(!eventDates.isEmpty())context.put("referenceDates",eventDates);
        return PersonnelCredentialProof.evaluate(rule,new RuleEvaluator(List.of(role),c,context));
    }
    @Test void samePersonFullPeriodComputesWithoutModel(){assertEquals("PASS",safety("京建安B(2099)9990001","有效期：2024年1月1日至2027年1月1日",true,"B").status());}
    @Test void cutoffBeforeValidityIsSourcedRisk(){assertEquals("FAIL",safety("京建安B(2099)9990001","有效期：2026年1月1日至2027年1月1日",true,"B").status());}
    @Test void explicitExpiryOnlyCanBeComparedWithoutInventingAStart(){
        assertEquals("PASS",safety("京建安B(2099)9990001","有效期至：2027年1月1日",true,"B").status());
        assertEquals("FAIL",safety("京建安B(2099)9990001","有效期至：2025年1月1日",true,"B").status());
    }
    @Test void sameNameWithoutStrongIdentityCannotAttachCertificate(){Decision d=safety("京建安B(2099)9990001","有效期：2024年1月1日至2027年1月1日",false,"B");assertEquals("UNDETERMINED",d.status());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("ENTITY_IDENTITY_AMBIGUOUS")));}
    @Test void typeMismatchDoesNotBecomeBroadSafetyPass(){assertEquals("UNDETERMINED",safety("京建安B(2099)9990001","CATEGORY",true,"A").status());}
    @Test void openingValidityNeverFallsBackToBidDeadline(){Decision missing=safety("京建安B(2099)9990001","有效期：2024年1月1日至2027年1月1日",true,"B","OPENING_DATE",Map.of());assertEquals("UNDETERMINED",missing.status());assertTrue(missing.gaps().stream().anyMatch(g->g.code().equals("REFERENCE_TIME_UNCONFIRMED")));Map<String,Object> opening=linked("referenceDate","2028-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",Map.of("documentId","t","quote","开标日期：2028年8月5日","confirmation","SOURCE_UNAMBIGUOUS","confirmed",true));Decision actual=safety("京建安B(2099)9990001","有效期：2024年1月1日至2027年1月1日",true,"B","OPENING_DATE",Map.of("OPENING_DATE",opening));assertEquals("FAIL",actual.status(),actual.toString());assertEquals("2028-08-05",actual.proof().get("referenceDate"));}
    private Decision builderValidity(String buildingEnd,String useEnd,String required){
        return builderValidity(buildingEnd,useEnd,required,java.util.function.UnaryOperator.identity());
    }
    private Decision builderValidity(String buildingEnd,String useEnd,String required,java.util.function.UnaryOperator<String> certificateChange){
        String appointment="投标文件\n项目名称：北方中心装修工程\n本项目拟派李明担任施工负责人。\n姓名：李明\n身份证号：110101198001010010";
        String cert="一级建造师注册证书\n姓名：李明\n身份证号：110101198001010010\n注册编号：京1999999999990001\n聘用企业：北方建设有限公司\n使用有效期：2025-01-01至"+useEnd+"\n注册专业：机电工程(有效期：2024-01-01至2027-12-31)\n建筑工程(有效期：2024-01-01至"+buildingEnd+")";
        cert=certificateChange.apply(cert);
        var corpus=new EvidenceCorpus(List.of(new Document("b","p","BID","投标文件",1,"h","",List.of(new Page(1,appointment,"PAGE","TEXT",""),new Page(2,cert,"PAGE","TEXT","")),List.of())));Fact role=new Fact("role","PERSON_ROLE","施工负责人","姓名","李明","李明","","","CURRENT_PROJECT",List.of(corpus.all.get(0).id()),Map.of());
        String quote="施工负责人应具有有效的一级注册建造师证书（房屋建筑工程专业）";Rule r=new Rule("v",quote,"VALIDITY","施工负责人","certificate","","","",Map.of("personnelCredentialKind","REGISTERED_BUILDER","requiredProfession",required),quote,"",List.of());
        return PersonnelCredentialProof.evaluate(r,new RuleEvaluator(List.of(role),corpus,linked("referenceDate","2025-08-05","referenceDateSourceVerified",true,"referenceDateEvidence",Map.of("documentId","t","quote","投标截止日期：2025年8月5日","confirmation","SOURCE_UNAMBIGUOUS","confirmed",true))));
    }
    @Test void otherProfessionalPeriodCannotRescueExpiredRequiredProfession(){Decision d=builderValidity("2025-07-01","2026-01-01","房屋建筑工程");assertEquals("FAIL",d.status(),d.toString());assertEquals("建筑工程",d.proof().get("selectedProfession"));}
    @Test void certificateUsePeriodAndRequiredProfessionMustBothCoverTenderDate(){assertEquals("PASS",builderValidity("2026-08-01","2026-01-01","房屋建筑工程").status());assertEquals("FAIL",builderValidity("2026-08-01","2025-07-01","房屋建筑工程").status());}
    @Test void ungroundedProfessionCannotSelectALongerValidity(){Decision d=builderValidity("2025-07-01","2026-01-01","机电工程");assertEquals("UNDETERMINED",d.status());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN")));}
    @Test void unparsedExplicitUseRestrictionCannotDisappear(){Decision d=builderValidity("2026-08-01","2026-01-01","房屋建筑工程",s->s.replace("使用有效期：2025-01-01至2026-01-01","使用有效期：见电子证书使用时限说明"));assertEquals("UNDETERMINED",d.status(),d.toString());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("VALIDITY_RANGE_REQUIRED")));}
    @Test void missingRequiredProfessionCannotBorrowOnlyOtherProfessionPeriod(){Decision d=builderValidity("2026-08-01","2026-01-01","房屋建筑工程",s->s.replace("\n建筑工程(有效期：2024-01-01至2026-08-01)",""));assertEquals("UNDETERMINED",d.status(),d.toString());assertTrue(d.gaps().stream().anyMatch(g->g.code().equals("CERTIFICATE_PROFESSION_REQUIRED")));}
}

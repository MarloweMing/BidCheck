package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonnelEmployerBindingsTest {
    final PersonnelCountRoleTest fixture=new PersonnelCountRoleTest();
    @Test void missingEmploymentFactMayBeRecoveredOnlyFromTheIdentifiedAppointmentPacket() {
        String id="110101199001010015";
        Rule rule=new Rule("m","本单位连续社保","MONTHS","李明","社保","","","",
                linked("employer","本单位","count",3),"在本单位缴纳社保","",Collections.emptyList());
        String certificate="一级建造师注册证书\n6\n姓名：李明\n出生日期：1990年1月1日\n注册编号：京1999999999990001\n聘用企业：北方设计有限公司\n注册专业：建筑工程(有效期：2024-01-01至2027-01-01)";
        for(String document:Arrays.asList(certificate,certificate.replace("1990年1月1日","1990年1月2日"),certificate.replace("聘用企业","注册单位"))){
            EvidenceCorpus c=fixture.corpus("投标文件\n项目名称：北方中心办公楼装修工程\n1","目录\n1.项目负责人 ............ 3\n2",
                    "1.项目负责人\n附表6-1 项目负责人情况表\n姓名 李明 年龄35\n身份证号 "+id+"\n拟在本合同任职 项目负责人\n主要工作经历\n任职经历\n3",
                    "（2）证明文件扫描件\n4","身份证\n5\n姓名 李明\n出生 1990 年 1 月 1 日\n公民身份号码 "+id,document);
            Fact role=new Fact("role","PERSON_ROLE","项目负责人","姓名","李明","李明","","","CURRENT_PROJECT",
                    Collections.singletonList(fixture.source(c,3).id()),Collections.emptyMap());
            assertFalse(PersonnelEmployerBindings.resolve(rule,Collections.singletonList(role),c).resolved());
            PersonnelEmployerBindings.Binding resolved=PersonnelEmployerBindings.resolveForSocialCheck(rule,Collections.singletonList(role),c,Collections.emptyList());
            assertEquals(certificate.equals(document),resolved.resolved(),resolved.audit().toString());
            if(resolved.resolved())assertEquals(Collections.singletonList("北方设计有限公司"),resolved.employers());
        }
    }
    final String agreement="联合体协议书\n合成施工有限公司、合成设计有限公司[所有成员单位名称]自愿组成：合成施工有限公司和合成设计有限公司联合体，共同投标北方中心装修工程/标段。\n合成施工有限公司负责施工部分；合成设计有限公司负责设计部分。";
    EvidenceCorpus appointed(String text){return fixture.corpus("投标文件\n项目名称：北方中心装修工程\n投标人名称：（盖章）合成施工有限公司",agreement,text);}
    Map<String,Object> reference(){return cn.bidcheck.compat.Java8Api.mapOf("referenceDate", "2025-08-05", "referenceDateSourceVerified", true, "referenceDateEvidence", cn.bidcheck.compat.Java8Api.mapOf("documentId", "tender", "quote", "投标截止时间：2025年8月5日", "confirmation", "SOURCE_UNAMBIGUOUS", "confirmed", true));}
    Rule rule(String selector){return new Rule("months","本单位社保","MONTHS","测试甲","社保","","","",cn.bidcheck.compat.Java8Api.mapOf("employer", "本单位", "count", 3, "personnelRoleSelector", selector),selector+"在本单位缴纳社保","",cn.bidcheck.compat.Java8Api.listOf());}
    @Test void onlyExplicitAppointmentClauseBindsProjectLeaderToConsortiumLead(){
        EvidenceCorpus corpus=fixture.corpus("投标文件\n项目名称：北方中心装修工程",agreement+"\n联合体牵头人：合成施工有限公司。","本项目人员配置\n项目负责人：测试甲");
        List<Fact> roles=fixture.checked(corpus,fixture.role(corpus,3,"项目负责人","测试甲","",""));
        Rule base=rule("项目负责人");
        Rule explicit=new Rule(base.id(),base.description(),base.predicate(),base.subject(),base.field(),base.operator(),base.expected(),base.unit(),base.parameters(),"项目负责人应由牵头单位委派。"+base.sourceQuote(),"",base.children());
        assertEquals(Collections.singletonList("合成施工有限公司"),PersonnelEmployerBindings.resolve(explicit,roles,corpus).employers());
        assertFalse(PersonnelEmployerBindings.resolve(base,roles,corpus).resolved());
    }
    @Test void designAndConstructionResolveToTheirOwnMemberNotTheLead(){
        for(String role:cn.bidcheck.compat.Java8Api.listOf("设计部分负责人", "施工部分负责人")){
            cn.bidcheck.verification.engine.EvidenceCorpus c=appointed("本项目人员配置\n"+role+"：测试甲");java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> fs=fixture.checked(c,fixture.role(c,3,role,"测试甲","",""));cn.bidcheck.verification.engine.PersonnelEmployerBindings.Binding binding=PersonnelEmployerBindings.resolve(rule(role),fs,c);
            assertTrue(binding.resolved(),binding.toString());assertEquals(cn.bidcheck.compat.Java8Api.listOf(role.startsWith("设计")?"合成设计有限公司":"合成施工有限公司"),binding.employers());assertEquals(true,binding.audit().get("doesNotProveEmployment"));
        }
    }
    @Test void sourceEmploymentTakesPriorityOverWorkAllocation(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=appointed("本项目合成另一有限公司设计部分负责人：测试甲");java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> fs=fixture.checked(c,fixture.role(c,3,"设计部分负责人","测试甲","合成另一有限公司",""));assertEquals(cn.bidcheck.compat.Java8Api.listOf("合成另一有限公司"),PersonnelEmployerBindings.resolve(rule("设计负责人"),fs,c).employers());
    }
    @Test void aRuleHintCannotCreateAnAppointmentAndProjectLeaderDoesNotImplyLeadEmployer(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=appointed("本项目人员配置\n项目负责人：测试甲");assertFalse(PersonnelEmployerBindings.resolve(rule("设计负责人"),cn.bidcheck.compat.Java8Api.listOf(),c).resolved());
        java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> fs=fixture.checked(c,fixture.role(c,3,"项目负责人","测试甲","",""));assertFalse(PersonnelEmployerBindings.resolve(rule("项目负责人"),fs,c).resolved());
        cn.bidcheck.verification.engine.VerificationTypes.Decision result=new RuleEvaluator(fs,c,cn.bidcheck.compat.Java8Api.mapOf()).months(rule("项目负责人"),cn.bidcheck.compat.Java8Api.listOf());assertEquals("SOCIAL_EMPLOYER_CONFIRMATION_REQUIRED",result.gaps().get(0).code());assertEquals(true,result.proof().get("confirmationRequired"));
    }
    @Test void socialProofDoesNotSupplyItsOwnExpectedEmployer(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=fixture.corpus("本项目资料\n测试甲 合成施工有限公司 2025-01 2025-02 2025-03");Fact social=new Fact("social","MONTHS","测试甲","社保","社保","社保","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf("person", "测试甲", "employer", "合成施工有限公司", "months", cn.bidcheck.compat.Java8Api.listOf("2025-01", "2025-02", "2025-03")));
        assertFalse(PersonnelEmployerBindings.resolve(rule("设计负责人"),cn.bidcheck.compat.Java8Api.listOf(social),c).resolved());
    }
    @Test void repeatedSameIdentityCanReuseEmployerWithoutCountingTwice(){
        cn.bidcheck.verification.engine.EvidenceCorpus c=fixture.corpus(fixture.appointment("专职安全员","张甲","110101199001010023","甲公司",""),fixture.appointment("专职安全员","张甲","110101199001010023","",""));java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> fs=fixture.checked(c,fixture.role(c,1,"专职安全员","张甲","甲公司",""),fixture.role(c,2,"专职安全员","张甲","",""));cn.bidcheck.verification.engine.VerificationTypes.Decision result=fixture.evaluate(c,fs,fixture.count(">=","1"));assertEquals("PASS",result.status(),result.toString());assertEquals(1,result.proof().get("provenDistinctPeople"));assertEquals("SOURCE_VALIDATED_SAME_PERSON_ONLY_NO_NEW_EMPLOYMENT_FACT",result.proof().get("memberReusePolicy"));
    }
    @Test void personnelPerformanceUsesTheSameMemberMappingAndStillRequiresRealSocialMonths(){
        String appointed="本项目人员配置\n姓名：测试甲\n拟在本合同任职 设计部分负责人\n缴费单位：合成设计有限公司\n缴费月份：2025-01、2025-02、2025-03";cn.bidcheck.verification.engine.EvidenceCorpus c=appointed(appointed);Fact role=fixture.checked(c,fixture.role(c,3,"设计部分负责人","测试甲","","")).get(0);Fact social=new Fact("social","MONTHS","测试甲","社保","2025-01","2025-01","","","CURRENT_PROJECT",role.sourceIds(),cn.bidcheck.compat.Java8Api.mapOf("person", "测试甲", "employer", "合成设计有限公司", "months", cn.bidcheck.compat.Java8Api.listOf("2025-01", "2025-02", "2025-03")));Rule score=new Rule("score","设计负责人业绩","SCORE_COUNT","设计负责人","","","","",cn.bidcheck.compat.Java8Api.mapOf("months", 3, "earliestMonth", "2025-01"),"设计负责人在本单位连续缴纳三个月社保","",cn.bidcheck.compat.Java8Api.listOf());
        fixture.checked(c,role,social);Decision evaluated=SourcePerformanceScore.social(score,"测试甲",cn.bidcheck.compat.Java8Api.listOf(role),new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(role, social),c,reference()));assertEquals("PASS",evaluated.status(),evaluated.toString());
        assertNotEquals("PASS",SourcePerformanceScore.social(score,"测试甲",cn.bidcheck.compat.Java8Api.listOf(role),new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(role),c,reference())).status());
    }
}
